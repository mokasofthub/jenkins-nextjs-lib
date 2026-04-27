# jenkins-nextjs-lib

**Jenkins Shared Library — Next.js CI/CD Pipeline**

![Jenkins](https://img.shields.io/badge/Jenkins-Shared_Library-D24939?style=flat-square&logo=jenkins&logoColor=white)
![Groovy](https://img.shields.io/badge/Groovy-pipeline-4298B8?style=flat-square&logo=apache-groovy&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-build_%26_push-2496ED?style=flat-square&logo=docker&logoColor=white)
![AWS ECR](https://img.shields.io/badge/AWS-ECR-FF9900?style=flat-square&logo=amazonaws&logoColor=white)
![AWS ECS](https://img.shields.io/badge/AWS-ECS_Fargate-FF9900?style=flat-square&logo=amazonaws&logoColor=white)

A reusable Jenkins Shared Library that encapsulates a full CI/CD pipeline for containerised Next.js applications deployed to AWS ECS Fargate via Amazon ECR.

---

## Table of Contents

- [Overview](#overview)
- [Pipeline Stages](#pipeline-stages)
- [Jenkins Setup](#jenkins-setup)
- [Usage](#usage)
- [Parameters](#parameters)
- [Required Jenkins Credentials](#required-jenkins-credentials)
- [Agent Requirements](#agent-requirements)
- [IAM Permissions](#iam-permissions)

---

## Overview

This library exposes a single `buildNextApp(Map config)` call that wires up a complete Declarative Pipeline:

```
GitHub push → Jenkins webhook → Checkout → Install → Lint → Test → Build
                                                               ↓ (main branch only)
                                                    Docker Build & Push to ECR
                                                               ↓
                                                       Deploy to ECS Fargate
                                                               ↓  (optional)
                                                  CloudFront cache invalidation
```

CI stages (checkout → build) run on **every branch**. Deployment stages run on the **configured deploy branch only** (default: `main`).

---

## Pipeline Stages

| Stage | Runs on | Description |
|---|---|---|
| **Checkout** | All branches | Clone repo, log branch and commit SHA |
| **Install** | All branches | `npm ci --prefer-offline` |
| **Lint** | All branches | `npm run lint` |
| **Test** | All branches | `npm test` |
| **Build** | All branches | `npm run build` |
| **Docker Build & Push** | Deploy branch | Authenticate to ECR, build multi-stage image, push `<sha>` + `latest` tags |
| **Deploy to ECS** | Deploy branch | Register new task definition revision, update ECS service |
| **Invalidate CloudFront** | Deploy branch | *(Optional)* Invalidate `/*` on the configured distribution |

---

## Jenkins Setup

### 1. Add the library

In Jenkins → **Manage Jenkins → Configure System → Global Trusted Pipeline Libraries**:

| Field | Value |
|---|---|
| Name | `jenkins-nextjs-lib` |
| Default version | `main` |
| Source | **Git** → `git@github.com:mokasofthub/jenkins-nextjs-lib.git` |
| Load implicitly | Off (load via `@Library`) |

### 2. Install required plugins

| Plugin | Purpose |
|---|---|
| **GitHub** | Webhook integration, `GIT_BRANCH` / `GIT_COMMIT` env vars |
| **Pipeline** | Declarative pipeline support |
| **Credentials Binding** | Expose secrets via `withCredentials` |

### 3. Configure the GitHub webhook

In your GitHub repository → **Settings → Webhooks → Add webhook**:

| Field | Value |
|---|---|
| Payload URL | `https://<your-jenkins-host>/github-webhook/` |
| Content type | `application/json` |
| Trigger | **Just the push event** |

In your Jenkins job → **Build Triggers** → enable **"GitHub hook trigger for GITScm polling"**.

---

## Usage

In your application's `Jenkinsfile`:

```groovy
// Jenkins must have 'jenkins-nextjs-lib' configured as a Global Trusted Pipeline Library
@Library('jenkins-nextjs-lib') _

buildNextApp(
    deployBranch:  'main',
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

## Required Jenkins Credentials

Store the following in **Manage Jenkins → Credentials → Global**:

| Credential ID | Type | Description |
|---|---|---|
| `aws-access-key-id` | Secret text | IAM access key ID |
| `aws-secret-access-key` | Secret text | IAM secret access key |
| `formspree-form-id` | Secret text | Formspree form ID injected as `NEXT_PUBLIC_FORMSPREE_FORM_ID` at Docker build time |

> Credentials are never logged or exposed. They are bound to environment variables inside `withCredentials` blocks for the duration of the relevant stage only.

---

## Agent Requirements

The Jenkins build agent must have the following available:

| Tool | Minimum version |
|---|---|
| `node` | ≥ 20 |
| `npm` | ≥ 10 |
| `docker` | ≥ 24 (daemon accessible by the Jenkins user) |
| `aws` CLI | ≥ 2 |
| `python3` | ≥ 3.8 (used for task definition JSON manipulation) |

---

## IAM Permissions

The IAM user whose keys are stored in Jenkins requires:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
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
        "ecs:WaitUntilServicesStable",
        "iam:PassRole",
        "cloudfront:CreateInvalidation",
        "sts:GetCallerIdentity"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## License

[MIT](./LICENSE) — free to adapt for your own Jenkins pipelines.
