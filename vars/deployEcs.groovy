/**
 * deployEcs — Deploy a new Docker image to AWS ECS Fargate.
 *
 * What it does (in order):
 *   1. Fetches the current ECS task definition
 *   2. Patches the image URI for the target container (via Python)
 *   3. Registers the new task definition revision
 *   4. Updates the ECS service to use it and waits for stability
 *   5. (Optional) Looks up the new Fargate task's public IP and updates Route 53
 *   6. (Optional) Invalidates the CloudFront cache so users get the new version
 *
 * Required Jenkins credentials:
 *   aws-access-key-id     — AWS_ACCESS_KEY_ID
 *   aws-secret-access-key — AWS_SECRET_ACCESS_KEY
 *
 * Required params:
 *   awsRegion, ecsCluster, ecsService, containerName, imageUri
 *
 * Optional params:
 *   route53HostedZoneId + originRecordName  → update origin A record after deploy
 *   cloudfrontDistributionId               → invalidate CloudFront cache
 */
def call(Map config) {
    def awsRegion        = config.awsRegion
    def ecsCluster       = config.ecsCluster
    def ecsService       = config.ecsService
    def containerName    = config.containerName
    def imageUri         = config.imageUri
    def r53ZoneId        = config.route53HostedZoneId     ?: ''
    def originRecord     = config.originRecordName        ?: ''
    def cloudfrontDistId = config.cloudfrontDistributionId ?: ''

    withCredentials([
        string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
        string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
    ]) {
        script {
            // Always deploy; optionally update DNS and invalidate cache afterward.
            _ecsUpdateService(awsRegion, ecsCluster, ecsService, containerName, imageUri)

            // Update Route 53 only when both zone ID and record name are provided.
            // Needed because ECS Fargate assigns a new public IP on every task restart.
            if (r53ZoneId && originRecord) {
                _updateOriginIp(awsRegion, ecsCluster, ecsService, r53ZoneId, originRecord)
            }

            // Invalidate CloudFront only when a distribution ID is provided.
            if (cloudfrontDistId) {
                _invalidateCloudFront(cloudfrontDistId)
            }
        }
    }
}

// ── Private helpers ──────────────────────────────────────────────────────────

/**
 * Register a new task definition revision with the updated image, then
 * update the service to use it and wait for the deployment to stabilise.
 *
 * Why Python for the JSON patch?
 *   The AWS CLI does not allow updating a single container image in-place.
 *   The only way is to: fetch task def → mutate image field → register new revision.
 *   Python is available on all Jenkins agents and handles JSON safely.
 *
 * Deployment strategy: minimumHealthyPercent=0 / maximumPercent=100
 *   The site has no ALB, so we accept ~5s downtime: the old task stops first,
 *   then the new one starts. Add an ALB later for zero-downtime rolling deploys.
 */
private def _ecsUpdateService(String region, String cluster, String service, String container, String imageUri) {
    // Step 1 — fetch the current task definition JSON to /tmp
    sh """
        aws ecs describe-task-definition \
            --task-definition ${container} --region ${region} \
            --query taskDefinition > /tmp/task-def.json
    """

    sh """
        # Step 2 — patch the image field for our container using Python,
        # and strip read-only fields that would cause a RegisterTaskDefinition error.
        python3 -c "
import json
with open('/tmp/task-def.json') as f: td = json.load(f)
for c in td['containerDefinitions']:
    if c['name'] == '${container}': c['image'] = '${imageUri}'
for k in ['taskDefinitionArn','revision','status','requiresAttributes','compatibilities','registeredAt','registeredBy']:
    td.pop(k, None)
import sys; json.dump(td, sys.stdout)
" > /tmp/new-task-def.json

        # Step 3 — register the patched definition as a new revision
        NEW_ARN=\$(aws ecs register-task-definition \
            --cli-input-json file:///tmp/new-task-def.json --region ${region} \
            --query 'taskDefinition.taskDefinitionArn' --output text)

        # Step 4 — point the service at the new revision and trigger a deployment
        aws ecs update-service \
            --cluster ${cluster} --service ${service} \
            --task-definition \$NEW_ARN \
            --deployment-configuration minimumHealthyPercent=0,maximumPercent=100 \
            --region ${region}

        # Step 5 — block until the service reports steady state (or timeout in ~10 min)
        aws ecs wait services-stable \
            --cluster ${cluster} --services ${service} --region ${region}
    """
    echo "✅ ECS deploy complete — ${imageUri}"
}

/**
 * Resolve the public IP of the newly started Fargate task and upsert a Route 53
 * A record so CloudFront's origin always points at the live task.
 *
 * Flow: ECS service → task ARN → ENI (network interface) → public IP → Route 53 UPSERT
 */
private def _updateOriginIp(String region, String cluster, String service, String zoneId, String recordName) {
    sh """
        # Get the ARN of the running task from the service
        TASK_ARN=\$(aws ecs list-tasks --cluster ${cluster} --service-name ${service} \
            --region ${region} --query 'taskArns[0]' --output text)

        # Each Fargate task has a VPC ENI — extract its ID from the task attachment details
        ENI_ID=\$(aws ecs describe-tasks --cluster ${cluster} --tasks "\$TASK_ARN" \
            --region ${region} \
            --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' \
            --output text)

        # The ENI carries the public IP assigned by Fargate at launch time
        NEW_IP=\$(aws ec2 describe-network-interfaces \
            --network-interface-ids "\$ENI_ID" --region ${region} \
            --query 'NetworkInterfaces[0].Association.PublicIp' --output text)

        # Build the Route 53 change-batch JSON and write it to /tmp
        python3 -c "
import json
change={'Comment':'Update ECS Fargate origin IP','Changes':[{'Action':'UPSERT','ResourceRecordSet':{'Name':'${recordName}','Type':'A','TTL':60,'ResourceRecords':[{'Value':'\$NEW_IP'}]}}]}
open('/tmp/r53-update.json','w').write(json.dumps(change))
"
        # UPSERT: creates the record if it doesn't exist, updates it if it does
        aws route53 change-resource-record-sets \
            --hosted-zone-id ${zoneId} \
            --change-batch file:///tmp/r53-update.json --no-cli-pager

        echo "✅ Route 53 origin updated to \$NEW_IP"
    """
}

/**
 * Invalidate all CloudFront cached objects so end users immediately see
 * the newly deployed version without waiting for TTL expiry.
 */
private def _invalidateCloudFront(String distId) {
    sh """
        aws cloudfront create-invalidation \
            --distribution-id ${distId} --paths '/*' --region us-east-1
    """
    echo "✅ CloudFront cache invalidated"
}
