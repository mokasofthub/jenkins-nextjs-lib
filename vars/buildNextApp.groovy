def call(Map config = [:]) {
    def deployBranch        = config.deployBranch        ?: 'main'
    def awsRegion           = config.awsRegion           ?: 'us-east-1'
    def ecrRepository       = config.ecrRepository       ?: error('ecrRepository is required')
    def ecsCluster          = config.ecsCluster          ?: error('ecsCluster is required')
    def ecsService          = config.ecsService          ?: error('ecsService is required')
    def containerName       = config.containerName       ?: ecrRepository
    // cloudfrontDistributionId: optional — triggers a cache invalidation after deploy
    // e.g. 'EXXXXXXXXXXXXX'
    def cloudfrontDistId    = config.cloudfrontDistributionId ?: ''
    // route53HostedZoneId + originRecordName: optional — updates the CloudFront origin A record
    // after each deploy so the new Fargate task IP is used automatically
    def r53HostedZoneId     = config.route53HostedZoneId ?: ''
    def originRecordName    = config.originRecordName    ?: ''

    pipeline {
        agent any

        triggers {
            // Requires Jenkins GitHub Plugin
            // GitHub webhook URL: https://<your-jenkins-host>/github-webhook/
            githubPush()
            cron('H 2 * * *')
        }

        environment {
            AWS_REGION     = "${awsRegion}"
            ECR_REPOSITORY = "${ecrRepository}"
            ECS_CLUSTER    = "${ecsCluster}"
            ECS_SERVICE    = "${ecsService}"
            CONTAINER_NAME = "${containerName}"
            IMAGE_TAG      = "${env.GIT_COMMIT?.take(7) ?: 'latest'}"
            NEXT_PUBLIC_FORMSPREE_FORM_ID = credentials('formspree-form-id')
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds(abortPrevious: true)
            buildDiscarder(logRotator(numToKeepStr: '10'))
        }

        stages {

            stage('Checkout') {
                steps {
                    checkout scm
                    echo "Branch: ${env.GIT_BRANCH} | Commit: ${env.GIT_COMMIT}"
                    githubNotify(
                        context:     'ci/jenkins',
                        status:      'PENDING',
                        description: 'Build in progress…'
                    )
                }
            }

            stage('Install') {
                steps {
                    sh 'node --version && npm --version'
                    sh 'npm ci --prefer-offline'
                }
            }

            stage('Lint') {
                steps {
                    sh 'npm run lint'
                }
            }

            stage('Test') {
                steps {
                    sh 'npm test'
                }
            }

            stage('Build') {
                steps {
                    sh 'npm run build'
                }
            }

            stage('Docker Build & Push') {
                when {
                    anyOf {
                        branch deployBranch
                        expression { env.GIT_BRANCH == "origin/${deployBranch}" }
                    }
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
                        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
                    ]) {
                        script {
                            def accountId = sh(
                                script: 'aws sts get-caller-identity --query Account --output text',
                                returnStdout: true
                            ).trim()

                            env.ECR_REGISTRY     = "${accountId}.dkr.ecr.${awsRegion}.amazonaws.com"
                            env.IMAGE_URI        = "${env.ECR_REGISTRY}/${ecrRepository}:${env.IMAGE_TAG}"
                            env.IMAGE_URI_LATEST = "${env.ECR_REGISTRY}/${ecrRepository}:latest"

                            sh """
                                aws ecr get-login-password --region ${awsRegion} | \
                                docker login --username AWS --password-stdin ${env.ECR_REGISTRY}
                            """

                            sh """
                                aws ecr describe-repositories \
                                    --repository-names ${ecrRepository} \
                                    --region ${awsRegion} \
                                || aws ecr create-repository \
                                    --repository-name ${ecrRepository} \
                                    --region ${awsRegion}
                            """

                            sh """
                                aws ecr put-lifecycle-policy \
                                    --repository-name ${ecrRepository} \
                                    --region ${awsRegion} \
                                    --lifecycle-policy-text '{
                                      "rules": [{
                                        "rulePriority": 1,
                                        "description": "Keep last 5 images",
                                        "selection": {
                                          "tagStatus": "any",
                                          "countType": "imageCountMoreThan",
                                          "countNumber": 5
                                        },
                                        "action": { "type": "expire" }
                                      }]
                                    }'
                            """

                            sh """
                                docker build \
                                    --build-arg NEXT_PUBLIC_FORMSPREE_FORM_ID=${env.NEXT_PUBLIC_FORMSPREE_FORM_ID} \
                                    --tag ${env.IMAGE_URI} \
                                    --tag ${env.IMAGE_URI_LATEST} \
                                    .
                            """
                            sh "docker push ${env.IMAGE_URI}"
                            sh "docker push ${env.IMAGE_URI_LATEST}"
                        }
                    }
                }
            }

            stage('Deploy to ECS') {
                when {
                    anyOf {
                        branch deployBranch
                        expression { env.GIT_BRANCH == "origin/${deployBranch}" }
                    }
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
                        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
                    ]) {
                        script {
                            sh """
                                aws ecs describe-task-definition \
                                    --task-definition ${containerName} \
                                    --region ${awsRegion} \
                                    --query taskDefinition \
                                    > /tmp/task-def.json
                            """

                            sh """
                                NEW_TASK_DEF=\$(python3 -c "
import json
with open('/tmp/task-def.json') as f:
    td = json.load(f)
for c in td['containerDefinitions']:
    if c['name'] == '${containerName}':
        c['image'] = '${env.IMAGE_URI}'
for key in ['taskDefinitionArn','revision','status','requiresAttributes',
            'compatibilities','registeredAt','registeredBy']:
    td.pop(key, None)
print(json.dumps(td))
")
                                echo "\$NEW_TASK_DEF" > /tmp/new-task-def.json

                                NEW_ARN=\$(aws ecs register-task-definition \
                                    --cli-input-json file:///tmp/new-task-def.json \
                                    --region ${awsRegion} \
                                    --query 'taskDefinition.taskDefinitionArn' \
                                    --output text)

                                echo "Registered task def: \$NEW_ARN"

                                # No ALB: stop old task first, start new one.
                                # ~5-10s downtime acceptable for portfolio site.
                                # Add ALB later to get zero-downtime rolling deploy.
                                aws ecs update-service \
                                    --cluster ${ecsCluster} \
                                    --service ${ecsService} \
                                    --task-definition \$NEW_ARN \
                                    --deployment-configuration minimumHealthyPercent=0,maximumPercent=100 \
                                    --region ${awsRegion}
                            """

                            sh """
                                aws ecs wait services-stable \
                                    --cluster ${ecsCluster} \
                                    --services ${ecsService} \
                                    --region ${awsRegion}
                            """

                            echo "✅ Deployment complete — image: ${env.IMAGE_URI}"
                        }
                    }
                }
            }

            // ── Update CloudFront origin A record with new Fargate task IP ────
            stage('Update Origin IP') {
                when {
                    allOf {
                        anyOf {
                            branch deployBranch
                            expression { env.GIT_BRANCH == "origin/${deployBranch}" }
                        }
                        expression { return r53HostedZoneId != '' && originRecordName != '' }
                    }
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
                        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
                    ]) {
                        script {
                            sh """
                                TASK_ARN=\$(aws ecs list-tasks \\
                                    --cluster ${ecsCluster} \\
                                    --service-name ${ecsService} \\
                                    --region ${awsRegion} \\
                                    --query 'taskArns[0]' --output text)

                                ENI_ID=\$(aws ecs describe-tasks \\
                                    --cluster ${ecsCluster} \\
                                    --tasks "\$TASK_ARN" \\
                                    --region ${awsRegion} \\
                                    --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' \\
                                    --output text)

                                NEW_IP=\$(aws ec2 describe-network-interfaces \\
                                    --network-interface-ids "\$ENI_ID" \\
                                    --region ${awsRegion} \\
                                    --query 'NetworkInterfaces[0].Association.PublicIp' \\
                                    --output text)

                                echo "New task IP: \$NEW_IP"

                                python3 -c "
import json, subprocess
change = {
  'Comment': 'Update ECS Fargate origin IP',
  'Changes': [{
    'Action': 'UPSERT',
    'ResourceRecordSet': {
      'Name': '${originRecordName}',
      'Type': 'A',
      'TTL': 60,
      'ResourceRecords': [{'Value': '\$NEW_IP'}]
    }
  }]
}
with open('/tmp/r53-update.json','w') as f:
    json.dump(change, f)
print('change written')
"

                                aws route53 change-resource-record-sets \\
                                    --hosted-zone-id ${r53HostedZoneId} \\
                                    --change-batch file:///tmp/r53-update.json \\
                                    --no-cli-pager

                                echo "✅ Origin record updated to \$NEW_IP"
                            """
                        }
                    }
                }
            }

            // ── CloudFront cache invalidation after deploy ────────────────────
            stage('Invalidate CloudFront') {
                when {
                    allOf {
                        anyOf {
                            branch deployBranch
                            expression { env.GIT_BRANCH == "origin/${deployBranch}" }
                        }
                        expression { return cloudfrontDistId != '' }
                    }
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
                        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
                    ]) {
                        sh """
                            aws cloudfront create-invalidation \
                                --distribution-id ${cloudfrontDistId} \
                                --paths '/*' \
                                --region us-east-1
                        """
                        echo "✅ CloudFront cache invalidated"
                    }
                }
            }
        }

        post {
            success {
                echo "✅ Pipeline passed on branch: ${env.GIT_BRANCH ?: env.BRANCH_NAME} — build #${env.BUILD_NUMBER}"
                githubNotify(
                    context:     'ci/jenkins',
                    status:      'SUCCESS',
                    description: 'All checks passed'
                )
            }
            failure {
                echo "❌ Pipeline failed on branch: ${env.GIT_BRANCH ?: env.BRANCH_NAME} — check build #${env.BUILD_NUMBER}"
                githubNotify(
                    context:     'ci/jenkins',
                    status:      'FAILURE',
                    description: 'Build failed — check Jenkins logs'
                )
            }
            unstable {
                githubNotify(
                    context:     'ci/jenkins',
                    status:      'FAILURE',
                    description: 'Build unstable (test failures)'
                )
            }
            aborted {
                githubNotify(
                    context:     'ci/jenkins',
                    status:      'ERROR',
                    description: 'Build aborted'
                )
            }
            always {
                sh 'docker image prune -f --filter "until=24h" || true'
                cleanWs()
            }
        }
    }
}
