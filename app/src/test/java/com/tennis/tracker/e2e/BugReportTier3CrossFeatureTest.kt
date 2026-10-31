package com.tennis.tracker.e2e

import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReport
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportCategory
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportSubmissionService
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticBreadcrumb
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticBreadcrumbBuffer
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MatchContextSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MockWorkerRelay
import com.tennis.tracker.e2e.harness.BugReportContractHarness.PiiScrubber
import com.tennis.tracker.e2e.harness.BugReportContractHarness.ScoreboardStateSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SettingsSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SubmissionResult
import com.tennis.tracker.e2e.harness.BugReportContractHarness.TriageEngineHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 3: Cross-Feature Interactions Test Suite for In-App Bug Reporting & Automated Triage.
 *
 * Verifies multi-feature interactions, failovers, cross-cutting PII scrubbing,
 * routing decisions, and end-to-end data fidelity (15 test cases).
 */
class BugReportTier3CrossFeatureTest {

    // =========================================================================
    // 1. Contextual Match Reporting + Active Filters + Scrubbing + Breadcrumbs (3 tests)
    // =========================================================================

    @Test
    fun test_tier3_contextualMatch_withFilters_andBreadcrumbs_serializedCleanly() {
        val breadcrumbs = listOf(
            DiagnosticBreadcrumb(tag = "ScoreboardRepo", message = "Fetched 18 matches from ESPN"),
            DiagnosticBreadcrumb(tag = "FilterManager", message = "Applied tour tier filter GRAND_SLAMS")
        )
        val matchCtx = MatchContextSnapshot(
            matchId = "40156789",
            tournamentName = "Roland Garros",
            eventGrouping = "Men's Singles",
            effectiveTour = "ATP",
            effectiveCategory = "GRAND_SLAM",
            state = "LIVE",
            court = "Court Philippe-Chatrier"
        )
        val snapshot = DiagnosticSnapshot(
            scoreboardState = ScoreboardStateSnapshot(
                tourFilter = "GRAND_SLAMS",
                totalMatches = 18,
                filteredMatches = 6
            ),
            matchContext = matchCtx,
            breadcrumbs = breadcrumbs
        )
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Tiebreak score not incrementing",
            description = "Tiebreak reached 6-6 but stopped updating"
        )

        val fullBody = snapshot.toFullIssueBody(report)
        assertTrue(fullBody.contains("Court Philippe-Chatrier"))
        assertTrue(fullBody.contains("Roland Garros"))
        assertTrue(fullBody.contains("Applied tour tier filter GRAND_SLAMS"))

        val parsed = TriageEngineHarness.extractDiagnosticJson(fullBody)
        assertNotNull(parsed)
        assertEquals("Roland Garros", parsed!!.getJSONObject("match_context").getString("tournament_name"))
        assertEquals(2, parsed.getJSONArray("breadcrumbs").length())
    }

    @Test
    fun test_tier3_breadcrumbsPiiScrubbedBeforeSnapshotAttached() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 10)
        buffer.log(
            tag = "NetworkClient",
            message = "GET https://api.tennis.org/data?api_key=SecretKey12345678901234567890 for user=referee@tennis.org"
        )

        val recent = buffer.getRecentBreadcrumbs()
        assertEquals(1, recent.size)
        val logged = recent.first()

        assertFalse(logged.message.contains("SecretKey12345678901234567890"))
        assertFalse(logged.message.contains("referee@tennis.org"))
        assertTrue(logged.message.contains("[REDACTED_API_KEY]"))
        assertTrue(logged.message.contains("[REDACTED_EMAIL]"))
    }

    @Test
    fun test_tier3_contextualMatchDetailsPropagateToTriageJson() {
        val matchCtx = MatchContextSnapshot(
            matchId = "998877",
            tournamentName = "Wimbledon",
            eventGrouping = "Women's Singles",
            effectiveTour = "WTA",
            effectiveCategory = "GRAND_SLAM",
            state = "FINAL",
            court = "Centre Court"
        )
        val report = BugReport(
            category = BugReportCategory.FILTER_TOUR_MISMATCH,
            summary = "WTA Wimbledon final tagged as ATP",
            description = "Match appears under ATP filter"
        )
        val body = DiagnosticSnapshot(matchContext = matchCtx).toFullIssueBody(report)
        val triage = TriageEngineHarness.triageIssue("Issue", body, "filter-tour")

        assertTrue(triage.diagnosticSummary.contains("Contextual match ID: `998877`"))
    }

    // =========================================================================
    // 2. Cloudflare Worker Relay to Fallback Transitions (3 tests)
    // =========================================================================

    @Test
    fun test_tier3_relayRateLimitedTransitionsToSafeFallbackUrl() = runBlocking {
        val relay = MockWorkerRelay(rateLimitPerIp = 1)
        val service = BugReportSubmissionService(relay)
        val report = BugReport(BugReportCategory.HOME_WIDGET, "Widget refresh issue", "Not auto syncing")
        val snapshot = DiagnosticSnapshot()

        // 1st request succeeds
        val res1 = service.submitReport(report, snapshot, clientIp = "172.16.0.1")
        assertTrue(res1 is SubmissionResult.Success)

        // 2nd request hits 429 and gracefully falls back
        val res2 = service.submitReport(report, snapshot, clientIp = "172.16.0.1")
        assertTrue(res2 is SubmissionResult.FallbackRequired)
        val fallback = res2 as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("Rate limit exceeded"))
        assertTrue(fallback.fallbackUrl.contains("yurilo/tennis_tracker/issues/new"))
    }

    @Test
    fun test_tier3_unconfiguredTokenTransitionsToFallbackWithClipboardInstruction() = runBlocking {
        val relay = MockWorkerRelay(configuredGithubToken = null)
        val service = BugReportSubmissionService(relay)
        val report = BugReport(BugReportCategory.UI_LAYOUT, "Buttons off screen", "Layout bug on fold")

        val result = service.submitReport(report, DiagnosticSnapshot())
        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("token not configured", ignoreCase = true))
    }

    @Test
    fun test_tier3_workerSuccessDoesNotGenerateFallbackUrl() = runBlocking {
        val service = BugReportSubmissionService(MockWorkerRelay())
        val report = BugReport(BugReportCategory.WATCH_HIGHLIGHTS, "Highlight link dead", "URL 404")

        val result = service.submitReport(report, DiagnosticSnapshot())
        assertTrue(result is SubmissionResult.Success)
        val success = result as SubmissionResult.Success
        assertTrue(success.issueNumber > 0)
        assertTrue(success.issueUrl.startsWith("https://github.com/yurilo/tennis_tracker/issues/"))
    }

    // =========================================================================
    // 3. Settings Custom GitHub Token vs Worker Relay Routing (3 tests)
    // =========================================================================

    @Test
    fun test_tier3_customTokenInSettingsBypassesWorkerRelay() = runBlocking {
        // Even if worker is misconfigured (null token), custom token in Settings routes directly
        val brokenWorker = MockWorkerRelay(configuredGithubToken = null)
        val service = BugReportSubmissionService(brokenWorker)
        val report = BugReport(BugReportCategory.OTHER, "Custom token report", "Direct API test")

        val result = service.submitReport(
            report,
            DiagnosticSnapshot(),
            customToken = "ghp_UserConfiguredPersonalAccessToken123456"
        )
        assertTrue("Custom token should bypass worker and succeed", result is SubmissionResult.Success)
        assertEquals(501, (result as SubmissionResult.Success).issueNumber)
    }

    @Test
    fun test_tier3_emptyTokenInSettingsUsesWorkerRelay() = runBlocking {
        val workingRelay = MockWorkerRelay()
        val service = BugReportSubmissionService(workingRelay)
        val report = BugReport(BugReportCategory.OTHER, "Relay report", "Using relay")

        val result = service.submitReport(report, DiagnosticSnapshot(), customToken = "")
        assertTrue(result is SubmissionResult.Success)
    }

    @Test
    fun test_tier3_blankTokenFallsBackToWorkerRelay() = runBlocking {
        val workingRelay = MockWorkerRelay()
        val service = BugReportSubmissionService(workingRelay)
        val report = BugReport(BugReportCategory.SCORE_LIVE_DATA, "Blank token", "Testing whitespace token")

        val result = service.submitReport(report, DiagnosticSnapshot(), customToken = "   ")
        assertTrue(result is SubmissionResult.Success)
    }

    // =========================================================================
    // 4. Round-Trip Serialization & Triage Extraction (3 tests)
    // =========================================================================

    @Test
    fun test_tier3_fullDiagnosticRoundTrip_preservesScoreboardAndSettings() {
        val originalSnapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 25,
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED",
            scoreboardState = ScoreboardStateSnapshot(
                tourFilter = "WTA_TOUR",
                totalMatches = 40,
                filteredMatches = 12
            ),
            settingsSnapshot = SettingsSnapshot(
                autoRefreshInterval = "2m",
                widgetSyncMode = "15m"
            )
        )
        val report = BugReport(BugReportCategory.SCORE_LIVE_DATA, "Round trip test", "Data preservation test")
        val body = originalSnapshot.toFullIssueBody(report)

        val extracted = TriageEngineHarness.extractDiagnosticJson(body)
        assertNotNull(extracted)
        assertEquals("WTA_TOUR", extracted!!.getJSONObject("scoreboard_state").getString("tour_filter"))
        assertEquals(40, extracted.getJSONObject("scoreboard_state").getInt("total_matches"))
        assertEquals("2m", extracted.getJSONObject("settings_snapshot").getString("auto_refresh_interval"))
        assertEquals("15m", extracted.getJSONObject("settings_snapshot").getString("widget_sync_mode"))
    }

    @Test
    fun test_tier3_triageMappingIdentifiesScoreboardModuleOnFilterMismatch() {
        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "WTA filter showing Davis Cup",
            issueBody = DiagnosticSnapshot().toFullIssueBody(
                BugReport(BugReportCategory.FILTER_TOUR_MISMATCH, "Filter bug", "Details")
            ),
            categorySlug = "filter-tour"
        )
        assertTrue(triage.suspectedFiles.any { it.endsWith("TournamentFiltering.kt") })
        assertTrue(triage.suspectedFiles.any { it.endsWith("TourTierFilter.kt") })
    }

    @Test
    fun test_tier3_triageMappingIdentifiesWidgetModuleOnWidgetIssue() {
        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "Widget not updating on home screen",
            issueBody = DiagnosticSnapshot().toFullIssueBody(
                BugReport(BugReportCategory.HOME_WIDGET, "Widget stale", "Details")
            ),
            categorySlug = "home-widget"
        )
        assertTrue(triage.suspectedFiles.any { it.endsWith("WidgetAutoSyncManager.kt") })
        assertTrue(triage.suspectedFiles.any { it.endsWith("TennisWidgetProvider.kt") })
    }

    // =========================================================================
    // 5. Multi-Credential Complex Scrubbing Scenarios (3 tests)
    // =========================================================================

    @Test
    fun test_tier3_mixedCredentialsAcrossSummaryDescriptionAndBreadcrumbsRedacted() {
        val leakSummary = "Leak in ghp_000011112222333344445555666677778888"
        val leakDesc = "User dev@test.com passed pwd=SuperPass123 to server 192.168.0.50"
        val report = BugReport(BugReportCategory.OTHER, leakSummary, leakDesc)

        val buffer = DiagnosticBreadcrumbBuffer()
        buffer.log("AuthTrace", "Bearer eyJhbGciOiJIUzI1NiI5890abcdef12345 at /home/user/.git/config")
        val snapshot = DiagnosticSnapshot(breadcrumbs = buffer.getRecentBreadcrumbs())

        val fullBody = snapshot.toFullIssueBody(report)

        assertFalse(fullBody.contains("ghp_00001111"))
        assertFalse(fullBody.contains("dev@test.com"))
        assertFalse(fullBody.contains("SuperPass123"))
        assertFalse(fullBody.contains("192.168.0.50"))
        assertFalse(fullBody.contains("/home/user/.git/config"))
        assertFalse(fullBody.contains("eyJhbGciOiJIUzI1NiI5890abcdef12345"))

        assertTrue(fullBody.contains("[REDACTED_GITHUB_TOKEN]"))
        assertTrue(fullBody.contains("[REDACTED_EMAIL]"))
        assertTrue(fullBody.contains("[REDACTED_PASSWORD]"))
        assertTrue(fullBody.contains("[REDACTED_IP]"))
        assertTrue(fullBody.contains("[REDACTED_PATH]"))
        assertTrue(fullBody.contains("[REDACTED_BEARER_TOKEN]"))
    }

    @Test
    fun test_tier3_scrubberLeavesTennisScoresAndPlayerNamesUntouched() {
        val tennisText = "Djokovic d. Alcaraz 6-4, 3-6, 7-6(5) on Court 1 with 15-40 break point."
        val scrubbed = PiiScrubber.scrub(tennisText)

        assertEquals(tennisText, scrubbed)
    }

    @Test
    fun test_tier3_scrubberProtectsPathsWithinStackTraces() {
        val stackTrace = """
            java.lang.NullPointerException: Scoreboard failed
                at com.tennis.tracker.ui.ScoreboardViewModel.loadMatches(/storage/emulated/10/agy/src/ScoreboardViewModel.kt:42)
                at com.tennis.tracker.data.ScoreboardRepositoryImpl.fetch(/data/user/0/com.tennis.tracker/repo.kt:15)
        """.trimIndent()

        val scrubbed = PiiScrubber.scrub(stackTrace)
        assertFalse(scrubbed.contains("/storage/emulated/10"))
        assertFalse(scrubbed.contains("/data/user/0"))
        assertTrue(scrubbed.contains("[REDACTED_PATH]"))
    }
}
