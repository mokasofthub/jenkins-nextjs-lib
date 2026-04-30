/**
 * dockerEcr — Build a Docker image and push it to AWS ECR.
 *
 * What it does (in order):
 *   1. Retrieves the AWS account ID via STS to build the ECR registry URL
 *   2. Authenticates Docker against ECR using a temporary token
 *   3. Creates the ECR repository if it doesn't exist yet
 *   4. Applies a lifecycle policy that keeps only the last 5 images (cost control)
 *   5. Builds the Docker image with any provided build-args
 *   6. Pushes both a versioned tag (git SHA) and `latest`
 *
 * Sets for downstream stages:
 *   env.ECR_REGISTRY     — e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com
 *   env.IMAGE_URI        — full image URI with versioned tag
 *   env.IMAGE_URI_LATEST — full image URI with :latest tag
 *
 * Required Jenkins credentials:
 *   aws-access-key-id     — AWS_ACCESS_KEY_ID
 *   aws-secret-access-key — AWS_SECRET_ACCESS_KEY
 *
 * Usage:
 *   dockerEcr(
 *     awsRegion:     'us-east-1',
 *     ecrRepository: 'my-app',
 *     imageTag:      env.IMAGE_TAG,
 *     buildArgs:     [MY_VAR: 'value']   // optional
 *   )
 */
def call(Map config) {
    def awsRegion     = config.awsRegion
    def ecrRepository = config.ecrRepository
    def imageTag      = config.imageTag
    def buildArgs     = config.buildArgs ?: [:]

    // Credentials are injected as env vars scoped to this block only.
    // They are never printed to the console or stored in build artifacts.
    withCredentials([
        string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
    ]) {
        script {
            // ── Step 1: Resolve the ECR registry URL ─────────────────────────
            // STS GetCallerIdentity returns the AWS account ID without needing
            // any additional IAM permissions — it works with any valid credential.
            def accountId = sh(
                script: 'aws sts get-caller-identity --query Account --output text',
                returnStdout: true
            ).trim()

            env.ECR_REGISTRY     = "${accountId}.dkr.ecr.${awsRegion}.amazonaws.com"
            env.IMAGE_URI        = "${env.ECR_REGISTRY}/${ecrRepository}:${imageTag}"
            env.IMAGE_URI_LATEST = "${env.ECR_REGISTRY}/${ecrRepository}:latest"

            // ── Step 2: Docker login to ECR ───────────────────────────────────
            // `get-login-password` produces a short-lived token (12 h) that is
            // piped directly into `docker login` — no plaintext password on disk.
            sh "aws ecr get-login-password --region ${awsRegion} | docker login --username AWS --password-stdin ${env.ECR_REGISTRY}"

            // ── Step 3: Ensure the ECR repository exists ──────────────────────
            // `describe-repositories` returns exit 1 if the repo is missing,
            // which triggers the `||` branch to create it on first run.
            sh """
                aws ecr describe-repositories \
                    --repository-names ${ecrRepository} --region ${awsRegion} \
                || aws ecr create-repository \
                    --repository-name ${ecrRepository} --region ${awsRegion}
            """

            // ── Step 4: Lifecycle policy — keep the 5 most recent images ─────
            // ECR charges per GB stored. Without a policy, old images accumulate
            // indefinitely. This rule expires images once there are more than 5.
            sh """
                aws ecr put-lifecycle-policy \
                    --repository-name ${ecrRepository} \
                    --region ${awsRegion} \
                    --lifecycle-policy-text '{"rules":[{"rulePriority":1,"description":"Keep last 5 images","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":5},"action":{"type":"expire"}}]}'
            """

            // ── Step 5 & 6: Build and push ────────────────────────────────────
            // Convert the buildArgs map to --build-arg KEY=VALUE pairs.
            // Build-args are only available at build time and are NOT baked into
            // the final image layers if NEXT_PUBLIC_ vars are used via next.config.
            def buildArgStr = buildArgs.collect { k, v -> "--build-arg ${k}=${v}" }.join(' ')

            // Tag with both the commit SHA (immutable, traceable) and `latest`
            // (convenient for ECS task definitions that always pull latest).
            sh "docker build ${buildArgStr} --tag ${env.IMAGE_URI} --tag ${env.IMAGE_URI_LATEST} ."
            sh "docker push ${env.IMAGE_URI}"
            sh "docker push ${env.IMAGE_URI_LATEST}"
        }
    }
}
