import com.moka.AwsUtils

/**
 * rollbackNextApp — Roll back an ECS Fargate service to a previous task definition revision.
 *
 * Usage in a Jenkins Pipeline job:
 *   @Library('jenkins-nextjs-lib') _
 *   rollbackNextApp(
 *     ecsCluster:               'moka-cluster',
 *     ecsService:               'moka-service',
 *     awsRegion:                'us-east-1',
 *     cloudfrontDistributionId: 'EXXXXXXXXXXXXX',  // optional
 *     cloudfrontOriginId:       'ecs-moka'          // optional, default: 'ecs-moka'
 *   )
 *
 * Run from Jenkins → Build with Parameters.
 * Leave TASK_DEFINITION_REVISION blank to auto-select the previous revision.
 */
def call(Map config = [:]) {

    def ecsCluster         = config.ecsCluster              ?: error('ecsCluster is required')
    def ecsService         = config.ecsService              ?: error('ecsService is required')
    def awsRegion          = config.awsRegion               ?: 'us-east-1'
    def cloudfrontDistId   = config.cloudfrontDistributionId ?: ''
    def cloudfrontOriginId = config.cloudfrontOriginId      ?: 'ecs-moka'

    // Shared credential binding reused across all stages.
    def awsCreds = [
        string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY'),
    ]

    pipeline {
        agent any

        parameters {
            string(
                name:         'TASK_DEFINITION_REVISION',
                defaultValue: '',
                description:  'Task definition to roll back to (e.g. moka-software-business:4). Leave blank to auto-select the previous revision.'
            )
        }

        options {
            timeout(time: 15, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        stages {

            // ── Stage 1: resolve which revision to restore ────────────────────
            stage('Resolve Target Revision') {
                steps {
                    withCredentials(awsCreds) {
                        script {
                            if (params.TASK_DEFINITION_REVISION?.trim()) {
                                env.TARGET_REVISION = params.TASK_DEFINITION_REVISION.trim()
                                echo "Using provided revision: ${env.TARGET_REVISION}"
                            } else {
                                def current = sh(
                                    script: """
                                        aws ecs describe-services \
                                          --cluster ${ecsCluster} \
                                          --services ${ecsService} \
                                          --region   ${awsRegion} \
                                          --query    'services[0].taskDefinition' \
                                          --output   text
                                    """,
                                    returnStdout: true
                                ).trim()

                                echo "Current revision: ${current}"

                                // ARN format: arn:aws:ecs:<region>:<account>:task-definition/<family>:<number>
                                def family     = current.tokenize('/').last().tokenize(':')[0]
                                def currentNum = current.tokenize(':').last().toInteger()

                                if (currentNum <= 1) {
                                    error("Already at revision 1 — nothing to roll back to.")
                                }

                                env.TARGET_REVISION = "${family}:${currentNum - 1}"
                                echo "Auto-selected revision: ${env.TARGET_REVISION}"
                            }
                        }
                    }
                }
            }

            // ── Stage 2: point the ECS service at the previous task def ───────
            stage('Roll Back ECS Service') {
                steps {
                    withCredentials(awsCreds) {
                        sh """
                            aws ecs update-service \
                              --cluster         ${ecsCluster} \
                              --service         ${ecsService} \
                              --task-definition ${env.TARGET_REVISION} \
                              --force-new-deployment \
                              --region          ${awsRegion}

                            aws ecs wait services-stable \
                              --cluster  ${ecsCluster} \
                              --services ${ecsService} \
                              --region   ${awsRegion}
                        """
                        echo "ECS service rolled back to ${env.TARGET_REVISION}"
                    }
                }
            }

            // ── Stage 3: update CloudFront to the new task's public IP ────────
            // Skipped when cloudfrontDistributionId is not configured.
            // Required because Fargate assigns a new public IP on every task restart.
            stage('Update CloudFront Origin') {
                when {
                    expression { return cloudfrontDistId != '' }
                }
                steps {
                    withCredentials(awsCreds) {
                        script {
                            // Resolve the new task's public IP via shared utility
                            def aws   = new AwsUtils(this)
                            def newIp = aws.getTaskPublicIp(awsRegion, ecsCluster, ecsService)

                            // Load the Python patch script from resources/ —
                            // avoids inline Python with Groovy string interpolation
                            def pyScript = libraryResource('scripts/update-cf-origin.py')
                            writeFile file: 'update-cf-origin.py', text: pyScript

                            sh """
                                ETAG=\$(aws cloudfront get-distribution-config \
                                  --id    ${cloudfrontDistId} \
                                  --query 'ETag' \
                                  --output text)

                                aws cloudfront get-distribution-config \
                                  --id    ${cloudfrontDistId} \
                                  --query 'DistributionConfig' > cf-config.json

                                python3 update-cf-origin.py \
                                  --config-file cf-config.json \
                                  --origin-id   ${cloudfrontOriginId} \
                                  --new-ip      ${newIp}

                                aws cloudfront update-distribution \
                                  --id                  ${cloudfrontDistId} \
                                  --if-match            \$ETAG \
                                  --distribution-config file://cf-config.json
                            """

                            // Invalidate cache via shared utility
                            aws.invalidateCloudFront(cloudfrontDistId)
                            echo "CloudFront origin updated to ${newIp}"
                        }
                    }
                }
            }
        }

        post {
            success { echo "Rollback to ${env.TARGET_REVISION} completed successfully." }
            failure { echo "Rollback FAILED — check the logs above." }
            always  { cleanWs() }
        }
    }
}
