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

- All branches & PRs → Checkout · Install · Lint · Test · Build · GitHub PR status check
- `main` branch only → Docker Build & Push to ECR → Deploy to ECS Fargate → Update Route 53 origin A record → CloudFront cache invalidation

### Library structure

Follows the [official Jenkins shared library layout](https://www.jenkins.io/doc/book/pipeline/shared-libraries/#directory-structure):

```
(root)
├── vars/                          ← public API — one file per callable step
│   ├── buildNextApp.groovy        — CI + CD pipeline entry point
│   ├── rollbackNextApp.groovy     — roll back ECS service to a previous revision
│   ├── dockerEcr.groovy           — Docker build + ECR push + lifecycle policy
│   ├── deployEcs.groovy           — ECS deploy + Route 53 update + CloudFront invalidation
│   └── notifyBuild.groovy         — GitHub/GitLab commit status notifications
│
├── src/                           ← reusable Groovy classes (standard package layout)
│   └── com/moka/
│       └── AwsUtils.groovy        — shared AWS helpers (get task IP, invalidate CloudFront)
│
└── resources/                     ← non-Groovy files loaded via libraryResource()
    └── scripts/
        └── update-cf-origin.py    — patch a CloudFront DistributionConfig JSON in-place
```

How the entry points relate:

```
buildNextApp
│
├── notifyBuild('pending')                  ← right after checkout
├── dockerEcr(...)                          ← Docker Build & Push stage
├── deployEcs(...)                          ← Deploy to AWS stage
│     ├── ECS task definition patch + rollout
│     ├── Route 53 A record update          (only if route53HostedZoneId is set)
│     └── CloudFront invalidation           (only if cloudfrontDistributionId is set)
└── notifyBuild('success'|'failure'|...)    ← post block

rollbackNextApp
│
├── Resolve target revision                 (auto-detect previous, or use parameter)
├── aws ecs update-service                  ← point service at previous task def
├── aws ecs wait services-stable
└── Update CloudFront origin + invalidate   (only if cloudfrontDistributionId is set)
      └── AwsUtils.getTaskPublicIp()        ← shared helper
      └── update-cf-origin.py               ← loaded from resources/
```

---

## Table of Contents

- [How It Works](#how-it-works)
- [Quick Start](#quick-start)
- [Rollback](#rollback)
- [Parameters](#parameters)
- [Jenkins Setup](#jenkins-setup)
- [Agent Requirements](#agent-requirements)
- [Required Credentials](#required-credentials)
- [IAM Permissions](#iam-permissions)
- [Pipeline Stages](#pipeline-stages)
- [PR Status Checks](#pr-status-checks)
- [Architecture](#architecture)
- [Consuming Repo Example](#consuming-repo-example)
- [License](#license)

---

## How It Works

When you call `buildNextApp(...)` from your `Jenkinsfile`, it generates a complete declarative `pipeline { ... }` block. The heavy AWS logic is delegated to three helper files so `buildNextApp.groovy` stays readable at a glance.

1. **Checkout** — clones the repo, then immediately calls `notifyBuild('pending')` to show a spinner on the GitHub PR.
2. **CI stages** (Install → Lint → Test → Build) run on **every** branch and PR.
3. **Docker Build & Push** (`dockerEcr`) — logs in to ECR, creates the repository if it does not exist, enforces a 5-image lifecycle policy, builds the image tagged with the short git SHA and `latest`, then pushes both.
4. **Deploy to AWS** (`deployEcs`) — fetches the ECS task definition, patches the container image via Python, registers a new revision, calls `aws ecs update-service`, and waits for stability. Optionally updates Route 53 and invalidates CloudFront.
5. **Post block** — calls `notifyBuild('success'|'failure'|'unstable'|'aborted')` to post the final check status to GitHub.

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

## Rollback

Use `rollbackNextApp` to roll back an ECS service to a previous task definition revision without triggering a full CI/CD run.

### Step 1 — Create a Jenkins Pipeline job

1. **New Item → Pipeline** — name it `moka-rollback` (or any name you prefer)
2. Under **Pipeline**, select **Pipeline script** and paste:

```groovy
@Library('jenkins-nextjs-lib') _

rollbackNextApp(
    ecsCluster:               'moka-cluster',
    ecsService:               'moka-service',
    awsRegion:                'us-east-1',
    cloudfrontDistributionId: 'EXXXXXXXXXXXXX',  // optional — omit to skip CloudFront update
    cloudfrontOriginId:       'ecs-moka'          // optional — default: 'ecs-moka'
)
```

3. Save — do **not** trigger it yet.

### Step 2 — Find the revision to roll back to

```bash
aws ecs list-task-definitions \
  --family-prefix moka-software-business \
  --sort DESC \
  --region us-east-1
```

Output:
```
moka-software-business:5   ← current (broken)
moka-software-business:4   ← roll back to this
moka-software-business:3
```

### Step 3 — Trigger the rollback

1. Go to **Jenkins → moka-rollback → Build with Parameters**
2. Enter `moka-software-business:4` in the `TASK_DEFINITION_REVISION` field
3. Click **Build** — or leave the field blank to auto-select the revision one step back

### What rollbackNextApp does

| Stage | Action |
|---|---|
| **Resolve Target Revision** | Uses the provided revision or auto-detects `current - 1` |
| **Roll Back ECS Service** | `aws ecs update-service --task-definition <revision>` + waits for stability |
| **Update CloudFront Origin** | Resolves the new task's public IP via `AwsUtils`, patches the distribution config with `update-cf-origin.py`, then invalidates the cache — skipped if `cloudfrontDistributionId` is not set |

> **Why Update CloudFront Origin?** Fargate assigns a new public IP to the task on every restart — including rollbacks. Without this stage, CloudFront would still point at the old (now stopped) task's IP.

### Rollback parameters

| Parameter | Required | Default | Description |
|---|---|---|---|
| `ecsCluster` | **Yes** | — | ECS cluster name |
| `ecsService` | **Yes** | — | ECS service name |
| `awsRegion` | No | `us-east-1` | AWS region |
| `cloudfrontDistributionId` | No | `''` | CloudFront distribution ID — enables the **Update CloudFront Origin** stage |
| `cloudfrontOriginId` | No | `'ecs-moka'` | The CloudFront origin ID to update (must match the origin ID in your distribution config) |

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
| **Repository URL** | `https://gitlab.com/mokadev26/jenkins-nextjs-lib.git` |
| **Credentials** | *(leave blank for public repo, or add a GitLab PAT for private)* |

Click **Save**.

---

### Step 2 — Install required plugins

Go to: **Manage Jenkins → Plugins → Available**

Install all of the following (restart Jenkins after):

| Plugin | Why it is needed |
|---|---|
| **Pipeline** | Declarative pipeline support |
| **GitLab** | Webhook listener + push triggers (`gitlabPush`) |
| **GitLab Branch Source** | Multibranch pipeline + MR builds |
| **Credentials Binding** | Inject secrets as environment variables |
| **Git** | Checkout stage |

> The library posts two **commit statuses** per build via the [GitHub Statuses API](https://docs.github.com/en/rest/commits/statuses) (not the Checks API). Clicking **Details** on a PR redirects the user **directly** to Jenkins — no intermediate GitHub Checks page.

---

### Step 3 — Add credentials

Go to: **Manage Jenkins → Credentials → System → Global credentials → Add Credential**

Add each credential below as **Secret text**:

| Credential ID | Type | Value |
|---|---|---|
| `aws-access-key-id` | Secret text | Your AWS access key ID |
| `aws-secret-access-key` | Secret text | Your AWS secret access key |
| `formspree-form-id` | Secret text | Your Formspree form ID (injected at Docker build time) |
| `gitlab-api-token` | Secret text | GitLab Personal Access Token with **`api`** scope — used to post commit statuses |

> **Security note:** Never store AWS credentials in source code or `Jenkinsfile`. The library reads them via `withCredentials` and never prints them to the build log.

#### GitLab Personal Access Token (for commit statuses)

1. Go to **GitLab → User Settings → Access Tokens**
2. Click **Add new token**
3. Set a name (e.g. `jenkins-commit-status`), select the **`api`** scope
4. Copy the token and add it to Jenkins as a **Secret text** credential with ID `gitlab-api-token`

---

### Step 4 — Configure the GitLab webhook

In your GitLab repository, go to **Settings → Webhooks → Add new webhook**:

| Field | Value |
|---|---|
| **URL** | `http://<your-jenkins-host>:8080/gitlab-webhook/` |
| **Secret token** | *(optional — set one and store it in Jenkins as `gitlab-webhook-secret`)* |
| **Trigger** | Push events + Merge request events |

This makes Jenkins start a build automatically on every push and merge request.

---

### Step 5 — Create the Jenkins job

1. Click **New Item** → **Multibranch Pipeline** → OK
2. Under **Branch Sources**, add your GitLab repository
3. Under **Build Configuration**, set the script path to `Jenkinsfile`
4. Save — Jenkins will scan all branches and MRs immediately

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

> **Note — `EcsOriginIPUpdate` inline policy**: `ecs:ListTasks`, `ecs:DescribeTasks`, `ec2:DescribeNetworkInterfaces`, and `route53:ChangeResourceRecordSets` are required by the **Update Origin IP** stage and are **not** included in the standard ECS managed policies. Attach them as an inline policy on the IAM user:
>
> ```bash
> aws iam put-user-policy \
>   --user-name <your-jenkins-iam-user> \
>   --policy-name EcsOriginIPUpdate \
>   --policy-document '{
>     "Version": "2012-10-17",
>     "Statement": [{
>       "Effect": "Allow",
>       "Action": [
>         "ecs:ListTasks",
>         "ecs:DescribeTasks",
>         "ec2:DescribeNetworkInterfaces",
>         "route53:ChangeResourceRecordSets"
>       ],
>       "Resource": "*"
>     }]
>   }'
> ```

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

| Stage | File | What it does |
|---|---|---|
| **Docker Build & Push** | `dockerEcr.groovy` | Logs in to ECR, creates the repository if absent, applies the 5-image lifecycle policy, builds the image with `<git-sha>` and `latest` tags, pushes both. |
| **Deploy to AWS** | `deployEcs.groovy` | Orchestrates three sub-steps in sequence: ① ECS task definition update + service rollout + stability wait, ② Route 53 A record upsert with the new Fargate task IP (skipped if `route53HostedZoneId` not set), ③ CloudFront invalidation (skipped if `cloudfrontDistributionId` not set). |

### Post-build hooks (always run)

| Hook | What it does |
|---|---|
| `success` | `notifyBuild('success')` → posts ✅ to both GitHub checks |
| `failure` | `notifyBuild('failure')` → posts ❌ to both GitHub checks |
| `unstable` | `notifyBuild('unstable')` → posts ❌ FAILURE (test failures block the PR) |
| `aborted` | `notifyBuild('aborted')` → posts CANCELED to both GitHub checks |
| `always` | `docker image prune -f` to reclaim disk space; `cleanWs()` to wipe the workspace |

---

## PR Status Checks

The library posts two **commit statuses** per build via `curl` against the [GitHub Statuses API](https://docs.github.com/en/rest/commits/statuses). Unlike the Checks API, commit statuses redirect **directly** to Jenkins when clicking Details.

| Status context | Links to |
|---|---|
| `continuous-integration/jenkins/blue-ocean` | Blue Ocean visual pipeline view |
| `continuous-integration/jenkins/console` | Raw console output |

### Check lifecycle

```
git push / PR opened
      │
      ▼
  Checkout stage
      │
      └── notifyBuild('pending') → 🔄 IN_PROGRESS spinner on PR

  ... all stages run ...

  post { success }  → notifyBuild('success')  → ✅ SUCCESS  on both checks
  post { failure }  → notifyBuild('failure')  → ❌ FAILURE  on both checks
  post { unstable } → notifyBuild('unstable') → ❌ FAILURE  on both checks
  post { aborted }  → notifyBuild('aborted')  → ⊘ CANCELED on both checks
```

### Require checks before merging

In your GitHub repository go to **Settings → Branches → Branch protection rules → Edit (main)** and enable:
- ✅ Require status checks to pass before merging
- Add `continuous-integration/jenkins/blue-ocean` as a required check

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

The [`moka-software-business`](https://gitlab.com/mokadev26/moka-software-business) portfolio site is the reference consumer of this library. Its full `Jenkinsfile`:

```groovy
@Library('jenkins-nextjs-lib') _

buildNextApp(
    deployBranch:             'main',
    awsRegion:                'us-east-1',
    ecrRepository:            'moka-software-business',
    ecsCluster:               'moka-cluster',
    ecsService:               'moka-service',
    containerName:            'moka-software-business',
    // cloudfrontDistributionId: uncomment and set once CloudFront is created
    // cloudfrontDistributionId: 'EXXXXXXXXXXXXX',
    // route53HostedZoneId:      'ZXXXXXXXXXXXXX',
    // originRecordName:         'origin.mokasoftwarebusness.com',
)
```

That is the entire pipeline definition — 13 lines, all stages handled by the library.

---

## License

[MIT](./LICENSE)
