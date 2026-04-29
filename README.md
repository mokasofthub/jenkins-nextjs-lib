<div align="center">

<h1>jenkins-nextjs-lib</h1>

<p><strong>Jenkins Shared Library — reusable CI/CD pipeline for containerised Next.js applications deployed to AWS ECS Fargate</strong></p>

<p>
  <img src="https://img.shields.io/badge/Jenkins-Shared_Library-D24939?style=for-the-badge&logo=jenkins&logoColor=white" alt="Jenkins">
  <img src="https://img.shields.io/badge/Groovy-Declarative_Pipeline-4298B8?style=for-the-badge&logo=apache-groovy&logoColor=white" alt="Groovy">
  <img src="https://img.shields.io/badge/Docker-build_%26_push-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker">
  <img src="https://img.shields.io/badge/AWS-ECR_%2B_ECS_Fargate-FF9900?style=for-the-badge&logo=amazonaws&logoColor=white" alt="AWS">
  <img src="https://img.shields.io/badge/CloudFront-CDN-FF9900?style=for-the-badge&logo=amazonaws&logoColor=white" alt="CloudFront">
</p>

A single `buildNextApp()` call that wires up a complete CI/CD pipeline — from GitHub push to a live deployment on AWS ECS Fargate.

</div>

---

## Table of Contents

- [Overview](#overview)
- [Pipeline Stages](#pipeline-stages)
- [Pipeline Flow](#pipeline-flow)
- [Jenkins Setup](#jenkins-setup)
- [Usage](#usage)
- [Parameters](#parameters)
- [Required Credentials](#required-credentials)
- [Agent Requirements](#agent-requirements)
- [IAM Permissions](#iam-permissions)
- [License](#license)

---

## Overview

This library exposes a single function `buildNextApp(Map config)` that configures a complete Declarative Pipeline.

- **CI stages** (Checkout → Build) run on **every branch and pull request**
- **Deployment stages** (Docker → ECS → CloudFront) run only on the **configured deploy branch** (default: `main`)

```
GitHub push / PR
       │
       ▼
Jenkins (EC2)
       │
       ├─ [all branches & PRs]
       │       Checkout → Install (npm ci)
       │           └── Lint (next lint)
       │               └── Test (jest)
       │                   └── Build (next build)
       │
       └─ [main branch only]
               Docker Build
                   └── Push to Amazon ECR  (<commit-sha> + latest)
                           └── Register new ECS task definition revision
                                   └── aws ecs update-service
                                           └── wait services-stable
                                                   └── CloudFront invalidation
                                                       (if cloudfrontDistributionId is set)
```

---

## Pipeline Stages

| Stage | Runs on | Description |
|---|---|---|
| **Checkout** | All branches | Clone repo · log branch and commit SHA |
| **Install** | All branches | `npm ci --prefer-offline` |
| **Lint** | All branches | `npm run lint` |
| **Test** | All branches | `npm test` |
| **Build** | All branches | `npm run build` |
| **Docker Build & Push** | Deploy branch | Authenticate to ECR · build multi-stage image · push `<sha>` + `latest` tags |
| **Deploy to ECS** | Deploy branch | Inject new image URI into task definition · `update-service` · `wait services-stable` |
| **Invalidate CloudFront** | Deploy branch | `/*` invalidation — skipped if `cloudfrontDistributionId` is not set |

---

## Pipeline Flow

### Docker Build & Push (detail)

```
1. aws sts get-caller-identity          → resolve ECR registry URL dynamically
2. aws ecr get-login-password           → authenticate Docker to ECR
3. aws ecr describe-repositories        → create ECR repo if it does not exist  (idempotent)
4. aws ecr put-lifecycle-policy         → keep last 5 images, expire older ones  (idempotent)
5. docker build --build-arg ...         → multi-stage build, injects env vars at build time
6. docker push <sha> + latest           → push both tags
```

### Deploy to ECS (detail)

```
1. aws ecs describe-task-definition     → fetch current task definition JSON
2. python3 (inline)                     → inject new image URI into containerDefinitions
3. aws ecs register-task-definition     → register new revision
4. aws ecs update-service               → point service to new revision
                                           minimumHealthyPercent=0, maximumPercent=100
                                           (~5s downtime — no ALB required)
5. aws ecs wait services-stable         → block until the new task is healthy
```

---

## Jenkins Setup

### 1. Add the shared library

**Manage Jenkins → Configure System → Global Trusted Pipeline Libraries**

| Field | Value |
|---|---|
| Name | `jenkins-nextjs-lib` |
| Default version | `main` |
| Retrieval method | Modern SCM — Git |
| Repository URL | `git@github.com:<your-org>/jenkins-nextjs-lib.git` |
| Load implicitly | Off — load via `@Library` in each `Jenkinsfile` |

### 2. Install required plugins

| Plugin | Purpose |
|---|---|
| **GitHub Branch Source** | PR discovery, branch indexing for Multibranch Pipelines |
| **GitHub** | Webhook integration · provides `GIT_BRANCH` / `GIT_COMMIT` env vars |
| **Pipeline** | Declarative pipeline support |
| **Credentials Binding** | Exposes secrets via `withCredentials` |

### 3. Configure the GitHub webhook

**GitHub repo → Settings → Webhooks → Add webhook**

| Field | Value |
|---|---|
| Payload URL | `https://<your-jenkins-host>/github-webhook/` |
| Content type | `application/json` |
| Events | Push + Pull request |

In your Jenkins job → **Build Triggers** → enable **"GitHub hook trigger for GITScm polling"**.

### 4. Agent requirements

| Tool | Minimum version | Notes |
|---|---|---|
| `node` | ≥ 20 | Must be on `PATH` |
| `npm` | ≥ 10 | Bundled with Node |
| `docker` | ≥ 24 | Daemon must be accessible by the Jenkins user |
| `aws` CLI | v2 | Must be on `PATH` |
| `python3` | ≥ 3.8 | Used for task definition JSON manipulation |

---

## Usage

Add a `Jenkinsfile` to the root of your Next.js application:

```groovy
// Jenkins must have 'jenkins-nextjs-lib' configured as a Global Trusted Pipeline Library
@Library('jenkins-nextjs-lib') _

buildNextApp(
    awsRegion:     'us-east-1',
    ecrRepository: 'my-next-app',
    ecsCluster:    'my-cluster',
    ecsService:    'my-service',
    containerName: 'my-next-app',
    // Optional: triggers CloudFront cache invalidation after every deploy
    // cloudfrontDistributionId: 'EXXXXXXXXXXXXX',
)
```

---

## Parameters

| Parameter | Required | Default | Description |
|---|---|---|---|
| `deployBranch` | No | `main` | Branch that triggers Docker build + ECS deployment |
| `awsRegion` | No | `us-east-1` | AWS region for ECR and ECS |
| `ecrRepository` | **Yes** | — | ECR repository name (created automatically if absent) |
| `ecsCluster` | **Yes** | — | ECS cluster name |
| `ecsService` | **Yes** | — | ECS service name |
| `containerName` | No | `ecrRepository` | Container name inside the ECS task definition |
| `cloudfrontDistributionId` | No | `''` | CloudFront distribution ID — omit to skip cache invalidation |

---

## Required Credentials

Store the following in **Manage Jenkins → Credentials → Global**:

| Credential ID | Type | Description |
|---|---|---|
| `aws-access-key-id` | Secret text | IAM access key ID |
| `aws-secret-access-key` | Secret text | IAM secret access key |
| `formspree-form-id` | Secret text | Injected as `NEXT_PUBLIC_FORMSPREE_FORM_ID` at Docker build time |

> Credentials are never logged or printed. They are bound to environment variables inside `withCredentials` blocks for the duration of the relevant stage only.

---

## IAM Permissions

The IAM identity whose keys are stored in Jenkins requires the following minimum permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "ecr:GetAuthorizationToken",
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:CreateRepository",
      "ecr:DescribeRepositories",
      "ecr:PutLifecyclePolicy",
      "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
      "ecs:UpdateService",
      "ecs:DescribeServices",
      "iam:PassRole",
      "cloudfront:CreateInvalidation",
      "sts:GetCallerIdentity"
    ],
    "Resource": "*"
  }]
}
```

> For production environments, scope `Resource` to specific ARNs (ECR repo ARN, ECS cluster ARN, ECS service ARN) to follow least-privilege principles.

---

## License

[MIT](./LICENSE)
