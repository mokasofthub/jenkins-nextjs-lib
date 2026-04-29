<div align="center">

<h1>jenkins-nextjs-lib</h1>

<p><strong>Jenkins Shared Library — CI/CD pipeline for Next.js apps on AWS ECS Fargate</strong></p>

<p>
  <img src="https://img.shields.io/badge/Jenkins-Shared_Library-D24939?style=for-the-badge&logo=jenkins&logoColor=white" alt="Jenkins">
  <img src="https://img.shields.io/badge/Groovy-Declarative_Pipeline-4298B8?style=for-the-badge&logo=apache-groovy&logoColor=white" alt="Groovy">
  <img src="https://img.shields.io/badge/AWS-ECR_%2B_ECS_Fargate-FF9900?style=for-the-badge&logo=amazonaws&logoColor=white" alt="AWS">
</p>

</div>

---

A single `buildNextApp()` call in your `Jenkinsfile` runs a full CI/CD pipeline:

- **All branches & PRs** → Checkout · Install · Lint · Test · Build
- **`main` only** → Docker Build & Push to ECR → Deploy to ECS → CloudFront invalidation

## Usage

```groovy
@Library('jenkins-nextjs-lib') _

buildNextApp(
    awsRegion:     'us-east-1',
    ecrRepository: 'my-next-app',
    ecsCluster:    'my-cluster',
    ecsService:    'my-service',
    // cloudfrontDistributionId: 'EXXXXXXXXXXXXX',  // optional
)
```

## Parameters

| Parameter | Required | Default | Description |
|---|---|---|---|
| `deployBranch` | No | `main` | Branch that triggers Docker build + ECS deploy |
| `awsRegion` | No | `us-east-1` | AWS region |
| `ecrRepository` | **Yes** | — | ECR repository name (auto-created if absent) |
| `ecsCluster` | **Yes** | — | ECS cluster name |
| `ecsService` | **Yes** | — | ECS service name |
| `containerName` | No | `ecrRepository` | Container name in the task definition |
| `cloudfrontDistributionId` | No | `''` | CloudFront distribution ID — omit to skip invalidation |

## Jenkins Setup

**1. Add the library** — Manage Jenkins → Configure System → Global Trusted Pipeline Libraries

| Field | Value |
|---|---|
| Name | `jenkins-nextjs-lib` |
| Default version | `main` |
| Repository URL | `git@github.com:<your-org>/jenkins-nextjs-lib.git` |

**2. Add credentials** — Manage Jenkins → Credentials → Global

| ID | Type |
|---|---|
| `aws-access-key-id` | Secret text |
| `aws-secret-access-key` | Secret text |
| `formspree-form-id` | Secret text |

**3. Required plugins** — GitHub Branch Source · GitHub · Pipeline · Credentials Binding

**4. Agent must have:** `node` ≥ 20, `docker` ≥ 24, `aws` CLI v2, `python3` ≥ 3.8

## IAM Permissions

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload", "ecr:PutImage",
      "ecr:CreateRepository", "ecr:DescribeRepositories", "ecr:PutLifecyclePolicy",
      "ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition",
      "ecs:UpdateService", "ecs:DescribeServices",
      "iam:PassRole", "cloudfront:CreateInvalidation", "sts:GetCallerIdentity"
    ],
    "Resource": "*"
  }]
}
```

## License

[MIT](./LICENSE)
