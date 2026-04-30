/**
 * buildNextApp — Jenkins shared library entry point.
 *
 * Orchestrates CI/CD for a Next.js app deployed to AWS ECS Fargate.
 * Delegates heavy lifting to helper vars: dockerEcr, deployEcs, notifyBuild.
 *
 * Minimal Jenkinsfile usage:
 *   @Library('jenkins-nextjs-lib') _
 *   buildNextApp(
 *     ecrRepository: 'my-app',
 *     ecsCluster:    'my-cluster',
 *     ecsService:    'my-service'
 *   )
 */
def call(Map config = [:]) {

    // ── Configuration ─────────────────────────────────────────────────────────
    // Branch that triggers Docker build + AWS deployment (all other branches
    // only run Checkout → Install → Lint → Test → Build).
    def deployBranch = config.deployBranch ?: 'main'

    // Consolidate all config into a single map so it can be forwarded to
    // dockerEcr() and deployEcs() with the `+` operator (map merge).
    def cfg = [
        awsRegion:                config.awsRegion               ?: 'us-east-1',
        // Required: name of the ECR repository (e.g. 'moka-software')
        ecrRepository:            config.ecrRepository           ?: error('ecrRepository is required'),
        // Required: name of the ECS cluster
        ecsCluster:               config.ecsCluster              ?: error('ecsCluster is required'),
        // Required: name of the ECS service inside the cluster
        ecsService:               config.ecsService              ?: error('ecsService is required'),
        // Name of the Docker container in the task definition — defaults to the repo name
        containerName:            config.containerName           ?: config.ecrRepository,
        // Optional: CloudFront distribution ID — triggers /*  invalidation after each deploy
        cloudfrontDistributionId: config.cloudfrontDistributionId ?: '',
        // Optional: Route 53 hosted zone ID — needed to update the origin A record
        // after each deploy (Fargate tasks get a new public IP on every restart)
        route53HostedZoneId:      config.route53HostedZoneId     ?: '',
        // Optional: FQDN of the Route 53 A record to upsert (e.g. 'origin.example.com')
        originRecordName:         config.originRecordName        ?: '',
    ]

    // Closure that returns true when the current build is on the deploy branch.
    // Used in both Docker and Deploy `when` blocks to avoid code duplication.
    // The double check covers both multibranch pipelines (branch()) and single
    // pipeline jobs where GIT_BRANCH is set to 'origin/main' instead of 'main'.
    def onDeploy = { -> branch(deployBranch) || env.GIT_BRANCH == "origin/${deployBranch}" }

    // ── Pipeline ──────────────────────────────────────────────────────────────
    pipeline {
        agent any

        triggers {
            githubPush()           // Triggered by GitHub webhook on every push
            cron('H 2 * * *')     // Nightly build at ~2 AM to catch dependency drift
        }

        environment {
            // Short commit SHA used as the Docker image tag (e.g. 'a3f1c2b').
            // Falls back to 'latest' if GIT_COMMIT is not available.
            IMAGE_TAG = "${env.GIT_COMMIT?.take(7) ?: 'latest'}"

            // Contact form ID injected at build time via Jenkins credential store.
            // This avoids hardcoding the value in the Dockerfile or source code.
            NEXT_PUBLIC_FORMSPREE_FORM_ID = credentials('formspree-form-id')
        }

        options {
            timeout(time: 30, unit: 'MINUTES')         // Kill runaway builds
            disableConcurrentBuilds(abortPrevious: true) // Cancel outdated PR builds
            buildDiscarder(logRotator(numToKeepStr: '10')) // Keep only the last 10 builds
        }

        stages {

            // ── CI stages (all branches) ──────────────────────────────────────

            stage('Checkout') {
                steps {
                    checkout scm
                    // Mark the GitHub PR check as "in progress" right after checkout
                    // so developers see immediate feedback without waiting for all stages.
                    notifyBuild('pending', env.BUILD_URL)
                }
            }

            stage('Install') {
                steps {
                    // --prefer-offline reuses the npm cache to speed up builds.
                    // `ci` (vs `install`) ensures package-lock.json is strictly respected.
                    sh 'npm ci --prefer-offline'
                }
            }

            stage('Lint')  { steps { sh 'npm run lint'  } }
            stage('Test')  { steps { sh 'npm test'      } }
            stage('Build') { steps { sh 'npm run build' } }

            // ── CD stages (deploy branch only) ───────────────────────────────

            stage('Docker Build & Push') {
                // Skip this stage on feature branches to save time and ECR storage.
                when { expression { onDeploy() } }
                steps {
                    // Merge cfg with the runtime values that are only available
                    // after the pipeline has started (IMAGE_TAG, credentials).
                    dockerEcr(cfg + [
                        imageTag:  env.IMAGE_TAG,
                        buildArgs: [NEXT_PUBLIC_FORMSPREE_FORM_ID: env.NEXT_PUBLIC_FORMSPREE_FORM_ID]
                    ])
                }
            }

            stage('Deploy to AWS') {
                // Runs after a successful Docker push — deploys the new image to ECS,
                // optionally updates the Route 53 origin record, and invalidates CloudFront.
                when { expression { onDeploy() } }
                steps {
                    // env.IMAGE_URI is set by dockerEcr() in the previous stage.
                    deployEcs(cfg + [imageUri: env.IMAGE_URI])
                }
            }
        }

        // ── Post-build notifications ──────────────────────────────────────────
        // Each block publishes two GitHub checks: Blue Ocean link + console link.
        post {
            success  { notifyBuild('success',  env.BUILD_URL) }
            failure  { notifyBuild('failure',  env.BUILD_URL) }
            unstable { notifyBuild('unstable', env.BUILD_URL) }  // Test failures
            aborted  { notifyBuild('aborted',  env.BUILD_URL) }
            always {
                // Remove dangling/unused Docker layers to prevent disk exhaustion.
                sh 'docker image prune -f || true'
                cleanWs()  // Wipe the workspace so the next build starts clean
            }
        }
    }
}
