<div align="center">

<h1>jenkins-nextjs-lib</h1>

<p><strong>Jenkins Shared Library &mdash; CI/CD pipeline for Next.js apps on AWS ECS Fargate</strong></p>

<p>
  <img src="https://img.shields.io/badge/Jenkins-Shared_Library-D24939?style=for-the-badge&logo=jenkins&logoColor=white" alt="Jenkins">
  <img src="https://img.shields.io/badge/Groovy-Declarative_Pipeline-4298B8?style=for-the-badge&logo=apache-groovy&logoColor=white" alt="Groovy">
  <img src="https://img.shields.io/badge/AWS-ECR_%2B_ECS_Fargate-FF9900?style=for-the-badge&logo=amazonaws&logoColor=white" alt="AWS">
  <img src="https://img.shields.io/badge/Docker-multi--stage-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker">
  <img src="https://img.shields.io/badge/Node.js-20-339933?style=for-the-badge&logo=node.js&logoColor=white" alt="Node.js">
</p>

</div>

---

A reusable Jenkins Shared Library that delivers a full CI/CD pipeline for any Next.js application with a single `buildNextApp()` call in your `Jenkinsfile`.

**One call does all of this:**

- All branches & PRs → Checkout · Install · Lint · Test · Build
- `main` branch only → Docker Build & Push to ECR → Deploy to ECS Fargate → Update Route 53 origin A record → CloudFront cache invalidation

---

## Table of Contents

- [How It Works](#how-it-works)
- [Quick Start](#quick-start)
- [Parameters](#parameters)
- [Jenkins Setup](#jenkins-setup)
- [Agent Requirements](#agent-requirements)
- [Required Credentials](#required-credentials)
- [IAM Permissions](#iam-permissions)
- [Pipeline Stages](#pipeline-stages)
- [Architecture](#architecture)
- [Consuming Repo Example](#consuming-repo-example)
- [License](#license)

---

## How It Works

The library lives in `vars/buildNextApp.groovy`. When you call `buildNextApp(...)` from your project's `Jenkinsfile`, it:

1. Generates a full `pipeline { ... }` block dynamically with all stages pre-wired.
2. Runs CI stages (install, lint, test, build) on **every** branch and pull request.
3. Runs deploy stages (Docker build, ECR push, ECS update, CloudFront invalidation) **only** when the commit lands on the configured deploy branch (default: `main`).
4. Automatically creates the ECR repository if it does not exist, and applies a lifecycle policy to keep only the 5 most recent images.
5. Registers a new ECS task definition revision pointing to the freshly pushed image, then calls `aws ecs update-service` and waits for the service to stabilise before marking the build green.

---

## Quick Start

### Step 1 — Add the library to your `Jenkinsfile`

In the consuming repository (e.g. `moka-software-business`), your `Jenkinsfile` looks like this:

```groovy
@Library('jenkins-nextjs-lib') _

buildNextApp(
    awsRegion:                 'us-east-1',
    ecrRepository:             'moka-software-business',
    ecsCluster:                'moka-cluster',
    ecsService:                'moka-service',
    cloudfrontDistributionId:  'EXXXXXXXXXXXXX',        // optional — omit to skip invalidation
    route53HostedZoneId:       'ZXXXXXXXXXXXXX',        // optional — enables Update Origin IP stage
    originRecordName:          'origin.example.com',   // FQDN of the Fargate task A record
)
```

That is the entire `Jenkinsfile`. No other pipeline code is needed.

---

## Parameters

| Parameter | Required | Default | Description |
|---|---|---|---|
| `deployBranch` | No | `main` | Branch that triggers Docker build + ECS deploy |
| `awsRegion` | No | `us-east-1` | AWS region for all API calls |
| `ecrRepository` | **Yes** | — | ECR repository name. Auto-created if it does not exist. |
| `ecsCluster` | **Yes** | — | ECS cluster name |
| `ecsService` | **Yes** | — | ECS service name |
| `containerName` | No | value of `ecrRepository` | Container name in the ECS task definition |
| `cloudfrontDistributionId` | No | `''` | CloudFront distribution ID. Omit or leave empty to skip invalidation. |
| `route53HostedZoneId` | No | `''` | Route 53 hosted zone ID. Required to enable the **Update Origin IP** stage. |
| `originRecordName` | No | `''` | FQDN of the Route 53 A record that points to the ECS Fargate task (e.g. `origin.example.com`). Required alongside `route53HostedZoneId`. |

---

## Jenkins Setup

Follow these steps **once** when setting up a new Jenkins server.

### Step 1 — Register the shared library

Go to: **Manage Jenkins → Configure System → Global Trusted Pipeline Libraries**

| Field | Value |
|---|---|
| **Name** | `jenkins-nextjs-lib` |
| **Default version** | `main` |
| **Retrieval method** | Modern SCM → Git |
| **Repository URL** | `https://github.com/mokasofthub/jenkins-nextjs-lib.git` |
| **Credentials** | *(leave blank for public repo, or add SSH/token for private)* |

Click **Save**.

---

### Step 2 — Install required plugins

Go to: **Manage Jenkins → Plugins → Available**

Install all of the following (restart Jenkins after):

| Plugin | Why it is needed |
|---|---|
| **Pipeline** | Declarative pipeline support |
| **GitHub** | Webhook listener (`/github-webhook/`) |
| **GitHub Branch Source** | Multibranch pipeline + PR builds |
| **Credentials Binding** | Inject secrets as environment variables |
| **Git** | Checkout stage |

---

### Step 3 — Add credentials

Go to: **Manage Jenkins → Credentials → System → Global credentials → Add Credential**

Add each credential below as **Secret text**:

| Credential ID | Value |
|---|---|
| `aws-access-key-id` | Your AWS access key ID |
| `aws-secret-access-key` | Your AWS secret access key |
| `formspree-form-id` | Your Formspree form ID (injected at Docker build time) |

> **Security note:** Never store AWS credentials in source code or `Jenkinsfile`. The library reads them via `withCredentials` and never prints them to the build log.

---

### Step 4 — Configure the GitHub webhook

In your GitHub repository, go to **Settings → Webhooks → Add webhook**:

| Field | Value |
|---|---|
| **Payload URL** | `http://<your-jenkins-host>:8080/github-webhook/` |
| **Content type** | `application/json` |
| **Events** | Push + Pull request |

This makes Jenkins start a build automatically on every push and pull request.

---

### Step 5 — Create the Jenkins job

1. Click **New Item** → **Multibranch Pipeline** → OK
2. Under **Branch Sources**, add your GitHub repository
3. Under **Build Configuration**, set the script path to `Jenkinsfile`
4. Save — Jenkins will scan all branches and PRs immediately

---

## Agent Requirements

The Jenkins agent (the machine that actually runs the build) must have the following tools installed and on `PATH`:

| Tool | Minimum version | Purpose |
|---|---|---|
| Node.js | 20 | `npm ci`, `npm run lint`, `npm test`, `npm run build` |
| npm | 10 | Package management |
| Docker | 24 | Build and push container images |
| AWS CLI | v2 | ECR login, ECS updates, CloudFront invalidation |
| Python 3 | 3.8 | Parse and mutate the ECS task definition JSON |

Check versions on the agent:

```bash
node --version
npm --version
docker --version
aws --version
python3 --version
```

---

## Required Credentials

| Credential ID | Type | Used in stage |
|---|---|---|
| `aws-access-key-id` | Secret text | Docker Build & Push · Deploy to ECS · Invalidate CloudFront |
| `aws-secret-access-key` | Secret text | Docker Build & Push · Deploy to ECS · Invalidate CloudFront |
| `formspree-form-id` | Secret text | Docker Build & Push (injected as `--build-arg`) |

---

## IAM Permissions

The AWS IAM user whose keys are stored in Jenkins needs the following minimum permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sts:GetCallerIdentity",
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
        "ecs:ListTasks",
        "ecs:DescribeTasks",
        "ec2:DescribeNetworkInterfaces",
        "iam:PassRole",
        "route53:ChangeResourceRecordSets",
        "cloudfront:CreateInvalidation"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## Pipeline Stages

### Stages that run on every branch and PR

| Stage | Command | Fails if |
|---|---|---|
| **Checkout** | `checkout scm` | Repo unreachable |
| **Install** | `npm ci --prefer-offline` | `package-lock.json` mismatch or registry unreachable |
| **Lint** | `npm run lint` | Any ESLint error |
| **Test** | `npm test` | Any test fails |
| **Build** | `npm run build` | TypeScript error or Next.js build error |

### Stages that run only on the deploy branch (`main`)

| Stage | What it does |
|---|---|
| **Docker Build & Push** | Logs in to ECR, creates the repository if absent, applies the 5-image lifecycle policy, builds the image with `<git-sha>` and `latest` tags, pushes both. |
| **Deploy to ECS** | Fetches the current task definition, swaps the container image to the new one, registers the new revision, calls `aws ecs update-service`, waits until `services-stable`. |
| **Update Origin IP** | Resolves the public IP of the newly running Fargate task (via ENI) and upserts the Route 53 A record (e.g. `origin.example.com`) so CloudFront always reaches the live task. Skipped automatically if `route53HostedZoneId` or `originRecordName` is not set. |
| **Invalidate CloudFront** | Calls `aws cloudfront create-invalidation --paths '/*'`. Skipped automatically if `cloudfrontDistributionId` is not set. |

### Post-build hooks (always run)

| Hook | What it does |
|---|---|
| `success` | Logs branch name and build number |
| `failure` | Logs branch name and build number |
| `always` | `docker image prune -f --filter "until=24h"` to reclaim disk space; `cleanWs()` to clean the workspace |

---

## Architecture

### How a deploy flows end-to-end

```
  Developer
      │  git push to main
      ▼
  GitHub
      │  POST /github-webhook/
      ▼
  Jenkins Agent
      │
      ├── npm ci --prefer-offline
      ├── npm run lint
      ├── npm test
      ├── npm run build
      │
      ├── docker build --build-arg FORMSPREE_ID=... -t <ecr-uri>:<sha> .
      ├── docker push <ecr-uri>:<sha>
      ├── docker push <ecr-uri>:latest
      │
      ├── aws ecs describe-task-definition  →  /tmp/task-def.json
      ├── python3: swap container image in JSON
      ├── aws ecs register-task-definition  →  new revision ARN
      ├── aws ecs update-service --task-definition <new ARN>
      ├── aws ecs wait services-stable
      │
      ├── aws ecs list-tasks + describe-tasks → get ENI ID
      ├── aws ec2 describe-network-interfaces → get public IP
      ├── aws route53 change-resource-record-sets (UPSERT origin.example.com → new IP)
      │
      └── aws cloudfront create-invalidation --paths '/*'
              │
              ▼
         CloudFront edge flushes cache
              │
              ▼
         Users see the new version
```

### Image tagging strategy

Every push to `main` produces two tags:

| Tag | Format | Purpose |
|---|---|---|
| Commit SHA | `abc1234` (first 7 chars) | Immutable — can roll back to any specific commit |
| Latest | `latest` | Convenient reference — always points to the most recent build |

### ECR lifecycle policy

Applied on every `main` build (idempotent):

```json
{
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
}
```

This keeps storage costs under $0.10/month regardless of how often you deploy.

---

## Consuming Repo Example

The [`moka-software-business`](https://github.com/mokasofthub/moka-software-busness) portfolio site is the reference consumer of this library. Its full `Jenkinsfile`:

```groovy
@Library('jenkins-nextjs-lib') _

buildNextApp(
    deployBranch:             'main',
    awsRegion:                'us-east-1',
    ecrRepository:            'moka-software-business',
    ecsCluster:               'moka-cluster',
    ecsService:               'moka-service',
    containerName:            'moka-software-business',
    cloudfrontDistributionId: 'E2MZ1JOJMAKL7T',
    route53HostedZoneId:      'Z060171628JQ5P7XSLA4C',
    originRecordName:         'origin.mokasoftwarebusness.com',
)
```

That is the entire pipeline definition — 13 lines, all stages handled by the library.

---

## License

[MIT](./LICENSE)
