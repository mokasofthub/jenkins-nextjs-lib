/**
 * notifyBuild — Post GitHub commit status checks via the Statuses API.
 *
 * Unlike the Checks API (publishChecks), commit statuses redirect the user
 * DIRECTLY to the target URL when they click "Details" on the PR — no
 * intermediate GitHub Checks page.
 *
 * Two statuses are posted per build:
 *   • continuous-integration/jenkins/blue-ocean → Blue Ocean pipeline view
 *   • continuous-integration/jenkins/console    → raw console logs
 *
 * Requires: Jenkins credential 'github-token' (PAT with repo:status scope)
 *           env.GIT_COMMIT must be set (always true after checkout scm)
 *           env.GIT_URL must be set (always true after checkout scm)
 *
 * Usage:
 *   notifyBuild('pending',  env.BUILD_URL)
 *   notifyBuild('success',  env.BUILD_URL)
 *   notifyBuild('failure',  env.BUILD_URL)
 *   notifyBuild('unstable', env.BUILD_URL)
 *   notifyBuild('aborted',  env.BUILD_URL)
 */
def call(String state, String buildUrl) {

    // ── Derive URLs ───────────────────────────────────────────────────────────
    // Build the Blue Ocean deep-link from BUILD_URL:
    //   .../job/<pipeline>/job/<branch>/<number>/
    //   → .../blue/organizations/jenkins/<pipeline>/detail/<branch>/<number>/pipeline
    def blueOcean = buildUrl.replaceAll(
        '/job/([^/]+)/job/([^/]+)/(\\d+)/',
        '/blue/organizations/jenkins/$1/detail/$2/$3/pipeline'
    )
    def console = "${buildUrl}console"

    // ── Map Jenkins state → GitHub Statuses API state ─────────────────────────
    // GitHub accepts: pending, success, failure, error
    def githubStateMap = [
        pending  : 'pending',
        success  : 'success',
        failure  : 'failure',
        unstable : 'failure',  // test failures → block the PR
        aborted  : 'error',
    ]
    def githubState = githubStateMap[state] ?: 'error'

    def blueOceanDesc = [
        pending  : 'Build in progress…',
        success  : 'Build succeeded',
        failure  : 'Build failed',
        unstable : 'Build unstable (test failures)',
        aborted  : 'Build aborted',
    ]
    def consoleDesc = [
        pending  : 'Build in progress — logs available shortly',
        success  : 'Build logs available',
        failure  : 'Build failed — see console logs',
        unstable : 'Test failures — see console logs',
        aborted  : 'Build aborted',
    ]

    // ── Post both statuses via GitHub API ─────────────────────────────────────
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        script {
            // Parse owner/repo from GIT_URL (supports both https and ssh formats)
            // e.g. https://github.com/mokasofthub/moka-software-busness.git
            //      git@github.com:mokasofthub/moka-software-busness.git
            def repoSlug = env.GIT_URL
                .replaceAll('.*github\\.com[:/]', '')
                .replaceAll('\\.git$', '')
            def sha = env.GIT_COMMIT

            def apiBase = "https://api.github.com/repos/${repoSlug}/statuses/${sha}"

            // Resolve descriptions outside the sh block to avoid single-quote conflicts
            // inside the JSON payload (shell single-quoted strings can't contain single quotes)
            def boDesc  = blueOceanDesc[state] ?: 'Build ended'
            def conDesc = consoleDesc[state]   ?: 'See console logs'

            // Post Blue Ocean status — "Details" clicks go directly to Blue Ocean
            sh """
                curl -s -X POST ${apiBase} \\
                  -H "Authorization: token \$GITHUB_TOKEN" \\
                  -H "Content-Type: application/json" \\
                  -d '{"state":"${githubState}","target_url":"${blueOcean}","description":"${boDesc}","context":"continuous-integration/jenkins/blue-ocean"}'
            """

            // Post console status — "Details" clicks go directly to the console log
            sh """
                curl -s -X POST ${apiBase} \\
                  -H "Authorization: token \$GITHUB_TOKEN" \\
                  -H "Content-Type: application/json" \\
                  -d '{"state":"${githubState}","target_url":"${console}","description":"${conDesc}","context":"continuous-integration/jenkins/console"}'
            """
        }
    }
}
