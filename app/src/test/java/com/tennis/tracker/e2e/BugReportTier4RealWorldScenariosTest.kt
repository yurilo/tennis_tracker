package com.tennis.tracker.e2e

import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReport
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportCategory
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportSubmissionService
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticBreadcrumb
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticBreadcrumbBuffer
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.GitHubIssueUrlBuilder
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MatchContextSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MockWorkerRelay
import com.tennis.tracker.e2e.harness.BugReportContractHarness.ScoreboardStateSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SettingsSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SubmissionResult
import com.tennis.tracker.e2e.harness.BugReportContractHarness.TriageEngineHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 4: Real-World Scenarios Test Suite for In-App Bug Reporting & Automated Triage.
 *
 * Verifies 6 complete end-to-end user journeys and triage lifecycle workflows.
 */
class BugReportTier4RealWorldScenariosTest {

    /**
     * Scenario 1: Global bug report submitted from Scoreboard TopAppBar while browsing ATP Tour live matches.
     * Flow: Global action -> Capture scoreboard state & settings -> Worker submission -> Triage report generated.
     */
    @Test
    fun test_scenario1_globalScoreboardBugReportSubmittedViaWorkerRelay() = runBlocking {
        // 1. User browsing ATP matches on Laptop layout
        val scoreboardState = ScoreboardStateSnapshot(
            tourFilter = "ATP_TOUR",
            currentTournament = "Rolex Paris Masters",
            matchTypeFilter = "LIVE",
            totalMatches = 28,
            filteredMatches = 6,
            liveMatches = 3
        )
        val settings = SettingsSnapshot(
            autoRefreshInterval = "30s",
            matchRetention = "24h"
        )
        val snapshot = DiagnosticSnapshot(
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED",
            scoreboardState = scoreboardState,
            settingsSnapshot = settings
        )

        // 2. User opens BugReport sheet from TopAppBar and fills details
        val userReport = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Live score updates lagging by 2 games",
            description = "Match between Alcaraz and Sinner is at 4-3, but scoreboard shows 3-2 for over 10 minutes",
            reproductionSteps = "1. Filter by ATP Tour\n2. Select Rolex Paris Masters\n3. Observe Alcaraz match"
        )

        // 3. Submit report via Worker relay
        val relay = MockWorkerRelay()
        val service = BugReportSubmissionService(relay)
        val result = service.submitReport(userReport, snapshot, clientIp = "192.168.1.55")

        assertTrue("Submission should succeed via worker relay", result is SubmissionResult.Success)
        val success = result as SubmissionResult.Success
        assertEquals(101, success.issueNumber)
        assertEquals("https://github.com/yurilo/tennis_tracker/issues/101", success.issueUrl)

        // 4. Automated triage action runs on GitHub issue
        val fullIssueBody = snapshot.toFullIssueBody(userReport)
        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "[In-App Report] ${userReport.summary}",
            issueBody = fullIssueBody,
            categorySlug = userReport.category.slug
        )

        assertTrue(triage.diagnosticSummary.contains("LAPTOP"))
        assertTrue(triage.diagnosticSummary.contains("ATP_TOUR"))
        assertTrue(triage.suspectedFiles.any { it.contains("ScoreboardRepositoryImpl.kt") })
        val comment = triage.toMarkdownComment()
        assertTrue(comment.contains("### 1. Diagnostic Summary"))
        assertTrue(comment.contains("### 5. Proposed Patch / Verification Steps"))
    }

    /**
     * Scenario 2: Contextual bug report from Match Detail sheet with live score discrepancy.
     * Flow: Match card -> Contextual report -> Scrub referee email & credentials -> Generate issue payload.
     */
    @Test
    fun test_scenario2_contextualMatchBugReportWithDetailedCourtAndScoreSnapshot() = runBlocking {
        // 1. User watching Wimbledon Women's Final
        val matchContext = MatchContextSnapshot(
            matchId = "40159988",
            tournamentName = "Wimbledon",
            tournamentId = "wimbledon-2026",
            eventGrouping = "Women's Singles",
            effectiveTour = "WTA",
            effectiveCategory = "GRAND_SLAM",
            state = "LIVE",
            court = "Centre Court"
        )
        val buffer = DiagnosticBreadcrumbBuffer()
        buffer.log("ScoreboardRepo", "Received payload from espn.com with token=ghp_LeakToken12345678901234567890")
        buffer.log("MatchDetail", "User referee.chair@wimbledon.org opened match 40159988")

        val snapshot = DiagnosticSnapshot(
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT",
            matchContext = matchContext,
            breadcrumbs = buffer.getRecentBreadcrumbs()
        )

        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Wimbledon Final set score reversed",
            description = "Player 1 won Set 1 (6-4) but app shows 4-6."
        )

        // 2. Validate scrubbing & serialization
        val fullBody = snapshot.toFullIssueBody(report)
        assertFalse("Leaked token in breadcrumb must be scrubbed", fullBody.contains("ghp_LeakToken12345678901234567890"))
        assertFalse("Leaked referee email in breadcrumb must be scrubbed", fullBody.contains("referee.chair@wimbledon.org"))
        assertTrue(fullBody.contains("Centre Court"))
        assertTrue(fullBody.contains("Women's Singles"))

        // 3. Triage parsing
        val triage = TriageEngineHarness.triageIssue("Issue", fullBody, "score-live-data")
        assertTrue(triage.diagnosticSummary.contains("Contextual match ID: `40159988`"))
    }

    /**
     * Scenario 3: Complete offline fallback flow with clipboard copy & URL generation.
     * Flow: Offline state -> Worker skipped -> Fallback URL built -> Clipboard guidance provided.
     */
    @Test
    fun test_scenario3_completeOfflineFallbackWithClipboardGuidanceAndPreFilledUrl() = runBlocking {
        val report = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "Foldable screen half-split clipping scores",
            description = "On Samsung Galaxy Fold inner display, set scores are clipped at 840dp width."
        )
        val snapshot = DiagnosticSnapshot(
            formFactor = "PHONE",
            screenWidthBucket = "MEDIUM",
            scoreboardState = ScoreboardStateSnapshot(isOffline = true)
        )

        val service = BugReportSubmissionService()
        val result = service.submitReport(report, snapshot, isOnline = false)

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertEquals("Device is offline", fallback.reason)

        // Check fallback URL
        assertTrue(fallback.fallbackUrl.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        assertTrue(fallback.fallbackUrl.contains("labels=bug%2Cin-app-report%2Cui-layout"))
        assertTrue(fallback.fullMarkdownBody.contains("Foldable screen half-split clipping scores"))
        assertTrue(fallback.fullMarkdownBody.contains("<!-- BUGREPORT_JSON_START -->"))
    }

    /**
     * Scenario 4: Home Widget synchronization failure report with settings diagnostics.
     * Flow: User logs widget sync issue -> Diagnostics capture 15m sync mode & worker logs -> Triage matches widget.
     */
    @Test
    fun test_scenario4_homeWidgetSyncFailureReportWithSettingsDiagnostics() = runBlocking {
        val settings = SettingsSnapshot(
            widgetSyncMode = "15m",
            autoRefreshInterval = "30s"
        )
        val buffer = DiagnosticBreadcrumbBuffer()
        buffer.log("WidgetAutoSync", "Periodic work scheduled: 15m cadence")
        buffer.log("WidgetWorker", "Sync failed: WorkManager constrained by battery saver")

        val snapshot = DiagnosticSnapshot(
            settingsSnapshot = settings,
            breadcrumbs = buffer.getRecentBreadcrumbs()
        )
        val report = BugReport(
            category = BugReportCategory.HOME_WIDGET,
            summary = "Widget stuck showing yesterday's match",
            description = "Home widget does not refresh despite 15m sync interval configured"
        )

        val fullBody = snapshot.toFullIssueBody(report)
        assertTrue(fullBody.contains("WidgetSync=15m"))
        assertTrue(fullBody.contains("WorkManager constrained by battery saver"))

        // Triage mapping
        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "[In-App Report] Widget stuck",
            issueBody = fullBody,
            categorySlug = "home-widget"
        )
        assertTrue(triage.suspectedFiles.any { it.contains("WidgetAutoSyncManager.kt") })
        assertTrue(triage.suspectedFiles.any { it.contains("TennisWidgetProvider.kt") })
    }

    /**
     * Scenario 5: Adversarial/accidental credential leak defense across complete payload.
     * Flow: User pastes crash report with sensitive keys, emails, paths -> 100% sanitized before submission.
     */
    @Test
    fun test_scenario5_adversarialCredentialLeakDefenseAcrossCompletePayload() {
        val crashLog = """
            Crash at /storage/emulated/10/agy/us_open_tracker/repo.kt:88
            Connected to 192.168.1.120:443
            AuthHeader: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.SecretPayload
            GithubToken: ghp_99887766554433221100aabbccddeeff1122
            DevContact: engineer@tennis-tracker.app
            Password: password=MySecretDBPassword#2026
        """.trimIndent()

        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Crash on fetch with token ghp_99887766554433221100aabbccddeeff1122",
            description = crashLog
        )
        val snapshot = DiagnosticSnapshot()
        val fullBody = snapshot.toFullIssueBody(report)

        // Verify zero sensitive data exists
        assertFalse(fullBody.contains("ghp_99887766554433221100aabbccddeeff1122"))
        assertFalse(fullBody.contains("engineer@tennis-tracker.app"))
        assertFalse(fullBody.contains("MySecretDBPassword#2026"))
        assertFalse(fullBody.contains("192.168.1.120"))
        assertFalse(fullBody.contains("/storage/emulated/10"))
        assertFalse(fullBody.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.SecretPayload"))

        // Verify replacement redaction markers
        assertTrue(fullBody.contains("[REDACTED_GITHUB_TOKEN]"))
        assertTrue(fullBody.contains("[REDACTED_EMAIL]"))
        assertTrue(fullBody.contains("[REDACTED_PASSWORD]"))
        assertTrue(fullBody.contains("[REDACTED_IP]"))
        assertTrue(fullBody.contains("[REDACTED_PATH]"))
        assertTrue(fullBody.contains("[REDACTED_BEARER_TOKEN]"))
    }

    /**
     * Scenario 6: End-to-end automated triage pipeline simulation.
     * Flow: Simulated GitHub Issue Webhook -> Extract JSON -> Correlate commits -> Produce 5-section markdown report.
     */
    @Test
    fun test_scenario6_endToEndGitHubActionsTriagePipelineSimulation() {
        // 1. Simulate incoming GitHub issue payload
        val report = BugReport(
            category = BugReportCategory.FILTER_TOUR_MISMATCH,
            summary = "Grand Slam filter omitting US Open doubles",
            description = "Doubles matches not appearing when Grand Slams filter is active"
        )
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 25,
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT",
            scoreboardState = ScoreboardStateSnapshot(
                tourFilter = "GRAND_SLAMS",
                totalMatches = 36,
                filteredMatches = 0
            )
        )
        val issueBody = snapshot.toFullIssueBody(report)

        // 2. Triage engine processing
        val triageReport = TriageEngineHarness.triageIssue(
            issueTitle = "[In-App Report] Grand Slam filter omitting US Open doubles",
            issueBody = issueBody,
            categorySlug = "filter-tour"
        )

        // 3. Verify 5 sections
        val markdown = triageReport.toMarkdownComment()
        assertTrue("Section 1 required", markdown.contains("### 1. Diagnostic Summary"))
        assertTrue("Section 2 required", markdown.contains("### 2. Suspected Files & Symbols"))
        assertTrue("Section 3 required", markdown.contains("### 3. Related Commits / Issues"))
        assertTrue("Section 4 required", markdown.contains("### 4. Root-Cause Analysis"))
        assertTrue("Section 5 required", markdown.contains("### 5. Proposed Patch / Verification Steps"))

        // 4. Verify candidate patch diff block
        assertTrue(markdown.contains("```diff"))
        assertTrue(markdown.contains("TournamentFiltering.kt") || markdown.contains("TourTierFilter.kt"))
    }
}
