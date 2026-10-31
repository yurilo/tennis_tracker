package com.tennis.tracker.e2e

import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReport
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportCategory
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportSubmissionService
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticBreadcrumbBuffer
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.GitHubIssueUrlBuilder
import com.tennis.tracker.e2e.harness.BugReportContractHarness.JSON_MARKER_END
import com.tennis.tracker.e2e.harness.BugReportContractHarness.JSON_MARKER_START
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MatchContextSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MockWorkerRelay
import com.tennis.tracker.e2e.harness.BugReportContractHarness.ScoreboardStateSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SettingsSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SubmissionResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-End Test Suite for In-App Bug Reporting System (R1, R2, R3).
 *
 * Verifies end-to-end user workflows, diagnostic capture, zero-PII scrubbing,
 * Cloudflare Worker relay integration, and graceful web fallback.
 */
class BugReportingE2ETest {

    @Test
    fun testE2E_phoneGlobalBugReport_fullLifecycle() = runBlocking {
        val report = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "TopAppBar title truncated on compact phone screen",
            description = "In portrait mode on 360dp width, scoreboard title overlaps actions",
            reproductionSteps = "Rotate device to portrait on compact screen"
        )
        val snapshot = DiagnosticSnapshot(
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT",
            scoreboardState = ScoreboardStateSnapshot(tourFilter = "ALL")
        )

        val relay = MockWorkerRelay()
        val service = BugReportSubmissionService(relay)
        val result = service.submitReport(report, snapshot)

        assertTrue(result is SubmissionResult.Success)
        val success = result as SubmissionResult.Success
        assertEquals(101, success.issueNumber)
        assertTrue(success.issueUrl.contains("yurilo/tennis_tracker/issues/101"))
    }

    @Test
    fun testE2E_laptopContextualMatchBugReport_fullLifecycle() = runBlocking {
        val matchCtx = MatchContextSnapshot(
            matchId = "40158877",
            tournamentName = "Australian Open",
            tournamentId = "aus-open-2026",
            eventGrouping = "Men's Singles",
            effectiveTour = "ATP",
            effectiveCategory = "GRAND_SLAM",
            state = "LIVE",
            court = "Rod Laver Arena"
        )
        val snapshot = DiagnosticSnapshot(
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED",
            matchContext = matchCtx
        )
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Game score not matching live stream",
            description = "Stream commentary says 4-3 deuce, app says 3-3"
        )

        val relay = MockWorkerRelay()
        val service = BugReportSubmissionService(relay)
        val result = service.submitReport(report, snapshot)

        assertTrue(result is SubmissionResult.Success)
        val fullBody = snapshot.toFullIssueBody(report)
        assertTrue(fullBody.contains("Rod Laver Arena"))
        assertTrue(fullBody.contains("Australian Open"))
    }

    @Test
    fun testE2E_zeroPiiSanitization_redactsTokensAcrossAllInputChannels() {
        val buffer = DiagnosticBreadcrumbBuffer()
        buffer.log("Repo", "Authenticated with ghp_TopSecret1234567890abcdef1234567890 for test@tennis.org")

        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Auth failed with key=SecretKey999988887777666655554444",
            description = "Encountered 401 at /storage/emulated/10/agy/test.db with IP 192.168.1.50"
        )
        val snapshot = DiagnosticSnapshot(breadcrumbs = buffer.getRecentBreadcrumbs())
        val body = snapshot.toFullIssueBody(report)

        assertFalse(body.contains("ghp_TopSecret"))
        assertFalse(body.contains("SecretKey9999"))
        assertFalse(body.contains("test@tennis.org"))
        assertFalse(body.contains("/storage/emulated/10"))
        assertFalse(body.contains("192.168.1.50"))

        assertTrue(body.contains("[REDACTED_GITHUB_TOKEN]"))
        assertTrue(body.contains("[REDACTED_API_KEY]"))
        assertTrue(body.contains("[REDACTED_EMAIL]"))
        assertTrue(body.contains("[REDACTED_PATH]"))
        assertTrue(body.contains("[REDACTED_IP]"))
    }

    @Test
    fun testE2E_breadcrumbRingBuffer_evictionAndCapacity() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        for (i in 1..30) {
            buffer.log("TestTag", "Log entry #$i")
        }

        val entries = buffer.getRecentBreadcrumbs()
        assertEquals(25, entries.size)
        assertEquals("Log entry #6", entries.first().message)
        assertEquals("Log entry #30", entries.last().message)
    }

    @Test
    fun testE2E_offlineGracefulFallback_generatesPreFilledUrlAndClipboardGuidance() = runBlocking {
        val report = BugReport(
            category = BugReportCategory.WATCH_HIGHLIGHTS,
            summary = "Cannot play YouTube highlights",
            description = "Player returns 404 error code"
        )
        val snapshot = DiagnosticSnapshot(
            scoreboardState = ScoreboardStateSnapshot(isOffline = true)
        )

        val service = BugReportSubmissionService()
        val result = service.submitReport(report, snapshot, isOnline = false)

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.fallbackUrl.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        assertTrue(fallback.fallbackUrl.contains("labels=bug%2Cin-app-report%2Cwatch-highlights"))
        assertTrue(fallback.fullMarkdownBody.contains("Cannot play YouTube highlights"))
    }

    @Test
    fun testE2E_workerRelayRateLimiting_enforcesThrottlingAndFallsBack() = runBlocking {
        val relay = MockWorkerRelay(rateLimitPerIp = 2)
        val service = BugReportSubmissionService(relay)
        val report = BugReport(BugReportCategory.HOME_WIDGET, "Widget crash", "Null pointer")
        val snapshot = DiagnosticSnapshot()

        assertTrue(service.submitReport(report, snapshot, clientIp = "10.0.0.1") is SubmissionResult.Success)
        assertTrue(service.submitReport(report, snapshot, clientIp = "10.0.0.1") is SubmissionResult.Success)

        // 3rd hit throttled -> FallbackRequired
        val thirdResult = service.submitReport(report, snapshot, clientIp = "10.0.0.1")
        assertTrue(thirdResult is SubmissionResult.FallbackRequired)
        val fallback = thirdResult as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("Rate limit exceeded"))
    }

    @Test
    fun testE2E_customGithubTokenSettingsOverride_bypassesRelay() = runBlocking {
        val brokenWorker = MockWorkerRelay(configuredGithubToken = null)
        val service = BugReportSubmissionService(brokenWorker)
        val report = BugReport(BugReportCategory.OTHER, "Settings token report", "Bypassing worker")

        val result = service.submitReport(
            report,
            DiagnosticSnapshot(),
            customToken = "ghp_ValidPersonalToken12345678901234567890"
        )
        assertTrue(result is SubmissionResult.Success)
        assertEquals(501, (result as SubmissionResult.Success).issueNumber)
    }

    @Test
    fun testE2E_machineReadableJsonBlock_embeddedAndParseable() {
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Score parsed incorrectly",
            description = "Match score 6-2, 6-4"
        )
        val snapshot = DiagnosticSnapshot(appVersion = "1.0.25", versionCode = 25)
        val fullBody = snapshot.toFullIssueBody(report)

        assertTrue(fullBody.contains(JSON_MARKER_START))
        assertTrue(fullBody.contains(JSON_MARKER_END))

        val jsonStr = fullBody.substringAfter(JSON_MARKER_START).substringBefore(JSON_MARKER_END).trim()
        val parsed = JSONObject(jsonStr)
        assertEquals("1.0.25", parsed.getString("app_version"))
        assertEquals(25, parsed.getInt("version_code"))
    }
}
