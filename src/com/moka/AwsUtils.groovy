package com.moka

/**
 * Shared AWS helpers for ECS Fargate and CloudFront operations.
 *
 * Centralises logic that is reused across multiple pipeline entry points
 * (buildNextApp, rollbackNextApp, etc.) to avoid code duplication.
 *
 * Usage (inside a `script` block in any vars/ file):
 *
 *   import com.moka.AwsUtils
 *
 *   withCredentials([...]) {
 *       def aws   = new AwsUtils(this)
 *       def newIp = aws.getTaskPublicIp('us-east-1', 'my-cluster', 'my-service')
 *       aws.invalidateCloudFront('EXXXXXXXXXXXXX')
 *   }
 *
 * IMPORTANT: instantiate with `this` from inside a pipeline script block so
 * the instance has access to `sh` and `echo` pipeline steps.
 * AWS credentials must already be injected (via withCredentials) before calling
 * any method — this class does not manage credentials itself.
 */
class AwsUtils implements Serializable {

    private static final long serialVersionUID = 1L

    /** The pipeline script context — provides access to sh, echo, etc. */
    private final def steps

    AwsUtils(def steps) {
        this.steps = steps
    }

    // ── ECS ──────────────────────────────────────────────────────────────────

    /**
     * Resolve the current public IP of the running Fargate task for a service.
     *
     * Flow: ECS service → task ARN → ENI (network interface ID) → public IP.
     * Each Fargate task gets a fresh public IP on every restart, so this must
     * be called *after* the service has stabilised.
     *
     * @param region   AWS region (e.g. 'us-east-1')
     * @param cluster  ECS cluster name
     * @param service  ECS service name
     * @return         Public IPv4 address as a String
     */
    String getTaskPublicIp(String region, String cluster, String service) {

        // Step 1 — get the ARN of the currently running task
        // list-tasks returns running tasks only; index [0] is safe because
        // the caller always waits for services-stable before calling this method.
        def taskArn = steps.sh(
            script: """
                aws ecs list-tasks \
                  --cluster ${cluster} \
                  --service-name ${service} \
                  --region ${region} \
                  --query 'taskArns[0]' \
                  --output text
            """,
            returnStdout: true
        ).trim()

        // Step 2 — resolve the Elastic Network Interface (ENI) attached to the task
        // Fargate attaches one ENI per task; the public IP lives on that ENI.
        // The JMESPath filter targets the 'networkInterfaceId' detail by name
        // because the attachments array has no guaranteed order.
        def eniId = steps.sh(
            script: """
                aws ecs describe-tasks \
                  --cluster ${cluster} \
                  --tasks ${taskArn} \
                  --region ${region} \
                  --query "tasks[0].attachments[0].details[?name=='networkInterfaceId'].value" \
                  --output text
            """,
            returnStdout: true
        ).trim()

        // Step 3 — get the public IPv4 from the ENI
        // This IP changes on every task restart (including deployments and rollbacks),
        // which is why CloudFront's origin must be updated after each one.
        def ip = steps.sh(
            script: """
                aws ec2 describe-network-interfaces \
                  --network-interface-ids ${eniId} \
                  --region ${region} \
                  --query 'NetworkInterfaces[0].Association.PublicIp' \
                  --output text
            """,
            returnStdout: true
        ).trim()

        steps.echo "Resolved public IP for ${service}: ${ip}"
        return ip
    }

    // ── CloudFront ────────────────────────────────────────────────────────────

    /**
     * Invalidate all cached objects in a CloudFront distribution.
     * Call this after a deploy or rollback so users immediately see the new version.
     *
     * @param distId  CloudFront distribution ID (e.g. 'EXXXXXXXXXXXXX')
     */
    void invalidateCloudFront(String distId) {
        // '/*' invalidates every cached object in the distribution.
        // CloudFront charges $0.005 per 1,000 paths after the first 1,000 free
        // invalidation paths per month — negligible for a portfolio site.
        // cloudfront API is global (us-east-1) regardless of the app's region.
        steps.sh """
            aws cloudfront create-invalidation \
              --distribution-id ${distId} \
              --paths '/*' \
              --region us-east-1
        """
        steps.echo "CloudFront cache invalidated for distribution ${distId}"
    }
}
