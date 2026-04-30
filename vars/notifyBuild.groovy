/**
 * notifyBuild — Publish GitHub PR status checks via the Jenkins Checks API plugin.
 *
 * What it does:
 *   For 'pending': marks the Blue Ocean check as IN_PROGRESS (build just started).
 *   For all other states: marks both checks as COMPLETED with the appropriate conclusion.
 *
 * Two checks are published per build so developers get two convenient links on the PR:
 *   • continuous-integration/jenkins/blue-ocean → visual pipeline view
 *   • continuous-integration/jenkins/console    → raw log output
 *
 * Requires: Checks API plugin (https://plugins.jenkins.io/checks-api)
 * Note: valid `conclusion` values — SUCCESS, FAILURE, NEUTRAL, CANCELED,
 *       TIME_OUT, ACTION_REQUIRED, SKIPPED (NOT "CANCELLED" with double-L)
 *
 * Usage:
 *   notifyBuild('pending',  env.BUILD_URL)   // call right after checkout
 *   notifyBuild('success',  env.BUILD_URL)   // call in post { success  { ... } }
 *   notifyBuild('failure',  env.BUILD_URL)   // call in post { failure  { ... } }
 *   notifyBuild('unstable', env.BUILD_URL)   // call in post { unstable { ... } }
 *   notifyBuild('aborted',  env.BUILD_URL)   // call in post { aborted  { ... } }
 */
def call(String state, String buildUrl) {
    // Build the deep-link URLs for each check's "Details" button on GitHub
    def blueOcean = "${buildUrl}display/redirect"  // Blue Ocean pipeline view
    def console   = "${buildUrl}console"            // Classic console output

    // ── Pending state ─────────────────────────────────────────────────────────
    // Only one check is published: the console URL isn't useful yet since the
    // build just started. The Blue Ocean check is set to IN_PROGRESS (spinner).
    if (state == 'pending') {
        publishChecks(
            name:       'continuous-integration/jenkins/blue-ocean',
            status:     'IN_PROGRESS',   // Shows a spinner on the GitHub PR page
            summary:    'Build in progress…',
            detailsURL: blueOcean
        )
        return
    }

    // ── Completed states ──────────────────────────────────────────────────────
    // Map the Jenkins pipeline result to the GitHub Checks API conclusion enum.
    // `unstable` (test failures) maps to FAILURE so the PR is blocked by default.
    def conclusionMap = [
        success  : 'SUCCESS',
        failure  : 'FAILURE',
        unstable : 'FAILURE',   // unstable = test failures → still block the PR
        aborted  : 'CANCELED',  // single-L: the Checks API enum is CANCELED not CANCELLED
    ]

    // Human-readable summary shown in the GitHub Checks detail panel
    def summaryMap = [
        success  : 'All checks passed',
        failure  : 'Build failed — see Blue Ocean for details',
        unstable : 'Build unstable (test failures)',
        aborted  : 'Build aborted',
    ]

    // Slightly different copy for the console check (raw logs context)
    def consoleSummaryMap = [
        success  : 'Build logs available',
        failure  : 'Build failed — see console logs',
        unstable : 'Test failures — see console logs',
        aborted  : 'Build aborted',
    ]

    // Fallback to FAILURE for any unexpected state value
    def conclusion = conclusionMap[state] ?: 'FAILURE'

    // Publish the Blue Ocean check (visual pipeline view)
    publishChecks(
        name:       'continuous-integration/jenkins/blue-ocean',
        conclusion: conclusion,
        summary:    summaryMap[state] ?: 'Build ended',
        detailsURL: blueOcean
    )

    // Publish the console check (raw log output)
    publishChecks(
        name:       'continuous-integration/jenkins/console',
        conclusion: conclusion,
        summary:    consoleSummaryMap[state] ?: 'See console logs',
        detailsURL: console
    )
}
