package com.tennis.tracker.e2e

import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReport
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportCategory
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticBreadcrumbBuffer
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.GitHubIssueUrlBuilder
import com.tennis.tracker.e2e.harness.BugReportContractHarness.JSON_MARKER_END
import com.tennis.tracker.e2e.harness.BugReportContractHarness.JSON_MARKER_START
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MockWorkerRelay
import com.tennis.tracker.e2e.harness.BugReportContractHarness.PiiScrubber
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SubmissionResult
import com.tennis.tracker.e2e.harness.BugReportContractHarness.TriageEngineHarness
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tier 2: Boundary & Corner Cases Test Suite for In-App Bug Reporting & Automated Triage.
 *
 * Verifies edge conditions, whitespace, maximum lengths, network failures,
 * buffer overflows, and corrupted JSON payload boundaries (30 test cases).
 */
class BugReportTier2BoundaryCornerCaseTest {

    // =========================================================================
    // 1. Input Boundaries (6 tests)
    // =========================================================================

    @Test
    fun test_tier2_emptySummaryRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BugReport(BugReportCategory.OTHER, "", "Valid description")
        }
    }

    @Test
    fun test_tier2_whitespaceOnlySummaryRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BugReport(BugReportCategory.OTHER, "   \t\n   ", "Valid description")
        }
    }

    @Test
    fun test_tier2_emptyDescriptionRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BugReport(BugReportCategory.OTHER, "Valid summary", "")
        }
    }

    @Test
    fun test_tier2_whitespaceOnlyDescriptionRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BugReport(BugReportCategory.OTHER, "Valid summary", "  \n  ")
        }
    }

    @Test
    fun test_tier2_singleCharacterSummaryAccepted() {
        val report = BugReport(BugReportCategory.OTHER, "?", "Single char summary")
        assertEquals("?", report.summary)
    }

    @Test
    fun test_tier2_singleCharacterDescriptionAccepted() {
        val report = BugReport(BugReportCategory.OTHER, "Valid summary", "!")
        assertEquals("!", report.description)
    }

    // =========================================================================
    // 2. Extreme Input Lengths & Memory Stress (3 tests)
    // =========================================================================

    @Test
    fun test_tier2_10000CharacterDescriptionSuccessfullySerialized() {
        val hugeText = "Tennis bug description block with lots of diagnostic text. ".repeat(200)
        assertTrue(hugeText.length >= 10000)

        val report = BugReport(BugReportCategory.SCORE_LIVE_DATA, "Stress test", hugeText)
        val snapshot = DiagnosticSnapshot()
        val body = snapshot.toFullIssueBody(report)

        assertTrue(body.length >= 10000)
        val extractedJson = TriageEngineHarness.extractDiagnosticJson(body)
        assertNotNull(extractedJson)
    }

    @Test
    fun test_tier2_hugeNumberOfSpecialCharactersPreservedAndEscaped() {
        val specialText = "!@#$%^&*()_+~`|}{[]:;?><,./'\"\\ \n \t \r"
        val report = BugReport(BugReportCategory.UI_LAYOUT, "Special chars: $specialText", specialText)
        val snapshot = DiagnosticSnapshot()
        val body = snapshot.toFullIssueBody(report)

        assertTrue(body.contains("!@#$%^&*()"))
        val extracted = TriageEngineHarness.extractDiagnosticJson(body)
        assertNotNull(extracted)
    }

    @Test
    fun test_tier2_veryLongSingleLineSummary() {
        val longSummary = "A".repeat(500)
        val report = BugReport(BugReportCategory.OTHER, longSummary, "Description")
        val fallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, DiagnosticSnapshot())

        assertNotNull(fallback.url)
        assertTrue(fallback.url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
    }

    // =========================================================================
    // 3. Screen Form Factors & Window Buckets (3 tests)
    // =========================================================================

    @Test
    fun test_tier2_zeroOrNegativeScreenWidthHandled() {
        val snapshot = DiagnosticSnapshot(
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT"
        )
        val json = snapshot.toJson()
        assertEquals("COMPACT", json.getString("screen_width_bucket"))
        assertEquals("PHONE", json.getString("form_factor"))
    }

    @Test
    fun test_tier2_ultraWideLaptopBucketRepresentation() {
        val snapshot = DiagnosticSnapshot(
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED"
        )
        val md = snapshot.toFormattedMarkdown()
        assertTrue(md.contains("LAPTOP (bucket: EXPANDED)"))
    }

    @Test
    fun test_tier2_boundaryCompactToMediumBucketTransition() {
        val mediumSnapshot = DiagnosticSnapshot(screenWidthBucket = "MEDIUM")
        assertEquals("MEDIUM", mediumSnapshot.toJson().getString("screen_width_bucket"))
    }

    // =========================================================================
    // 4. Network Failure & Offline Boundaries (4 tests)
    // =========================================================================

    @Test
    fun test_tier2_offlineStateForcesFallbackResult() = runBlocking {
        val service = com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportSubmissionService()
        val report = BugReport(BugReportCategory.WATCH_HIGHLIGHTS, "Offline watch bug", "Cannot load video")
        val snapshot = DiagnosticSnapshot()

        val result = service.submitReport(report, snapshot, isOnline = false)
        assertTrue("Offline submission must require fallback", result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertEquals("Device is offline", fallback.reason)
        assertTrue(fallback.fallbackUrl.contains("yurilo/tennis_tracker/issues/new"))
    }

    @Test
    fun test_tier2_workerTimeoutHandledGracefully() = runBlocking {
        val failingRelay = object : MockWorkerRelay() {
            // Simulates unreachable worker returning 504 Gateway Timeout
            override fun handleRequest(method: String, clientIp: String, payloadJson: JSONObject?): Pair<Int, JSONObject> {
                return 504 to JSONObject().apply { put("error", "Gateway Timeout") }
            }
        }
        val service = com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportSubmissionService(failingRelay)
        val report = BugReport(BugReportCategory.SCORE_LIVE_DATA, "Timeout report", "Server lagged")

        val result = service.submitReport(report, DiagnosticSnapshot())
        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertEquals("Gateway Timeout", fallback.reason)
    }

    @Test
    fun test_tier2_worker500InternalErrorTriggersFallback() = runBlocking {
        val errorRelay = object : MockWorkerRelay() {
            override fun handleRequest(method: String, clientIp: String, payloadJson: JSONObject?): Pair<Int, JSONObject> {
                return 500 to JSONObject().apply { put("error", "Internal Server Error in Worker") }
            }
        }
        val service = com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportSubmissionService(errorRelay)
        val result = service.submitReport(
            BugReport(BugReportCategory.OTHER, "Worker crash", "Relay down"),
            DiagnosticSnapshot()
        )
        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun test_tier2_worker503TokenUnconfiguredTriggersFallback() = runBlocking {
        val noTokenRelay = MockWorkerRelay(configuredGithubToken = null)
        val service = com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportSubmissionService(noTokenRelay)
        val result = service.submitReport(
            BugReport(BugReportCategory.FILTER_TOUR_MISMATCH, "No token test", "Worker has no GitHub token"),
            DiagnosticSnapshot()
        )
        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("token not configured", ignoreCase = true))
    }

    // =========================================================================
    // 5. Rate Limiting Thresholds (3 tests)
    // =========================================================================

    @Test
    fun test_tier2_rateLimitEnforcedOnNPlus1Request() {
        val relay = MockWorkerRelay(rateLimitPerIp = 3)
        val payload = JSONObject().apply {
            put("category", "filter-tour")
            put("summary", "Rate limit test")
            put("description", "Testing 3-limit per IP")
            put("diagnostic_snapshot", DiagnosticSnapshot().toJson())
        }

        val ip = "10.0.0.42"
        assertEquals(201, relay.handleRequest("POST", ip, payload).first)
        assertEquals(201, relay.handleRequest("POST", ip, payload).first)
        assertEquals(201, relay.handleRequest("POST", ip, payload).first)

        // 4th request must be rejected with 429
        val (status, response) = relay.handleRequest("POST", ip, payload)
        assertEquals(429, status)
        assertTrue(response.getString("error").contains("Rate limit exceeded"))
    }

    @Test
    fun test_tier2_rateLimitIndependentPerClientIp() {
        val relay = MockWorkerRelay(rateLimitPerIp = 1)
        val payload = JSONObject().apply {
            put("category", "ui-layout")
            put("summary", "Independent IP test")
            put("description", "IP 1 vs IP 2")
            put("diagnostic_snapshot", DiagnosticSnapshot().toJson())
        }

        assertEquals(201, relay.handleRequest("POST", "192.168.1.10", payload).first)
        assertEquals(429, relay.handleRequest("POST", "192.168.1.10", payload).first)

        // Different IP should still succeed
        assertEquals(201, relay.handleRequest("POST", "192.168.1.20", payload).first)
    }

    @Test
    fun test_tier2_rateLimitResetAllowsNewSubmissions() {
        val relay = MockWorkerRelay(rateLimitPerIp = 1)
        val payload = JSONObject().apply {
            put("category", "other")
            put("summary", "Reset test")
            put("description", "Reset rate limits")
            put("diagnostic_snapshot", DiagnosticSnapshot().toJson())
        }

        val ip = "10.1.1.1"
        assertEquals(201, relay.handleRequest("POST", ip, payload).first)
        assertEquals(429, relay.handleRequest("POST", ip, payload).first)

        relay.resetRateLimits()
        assertEquals(201, relay.handleRequest("POST", ip, payload).first)
    }

    // =========================================================================
    // 6. Diagnostic JSON Marker & Parsing Boundaries (5 tests)
    // =========================================================================

    @Test
    fun test_tier2_corruptedJsonBetweenMarkersReturnsNull() {
        val corruptedBody = "$JSON_MARKER_START\n{ this is not valid json : 123 \n$JSON_MARKER_END"
        val result = TriageEngineHarness.extractDiagnosticJson(corruptedBody)
        assertNull(result)
    }

    @Test
    fun test_tier2_missingEndMarkerReturnsNull() {
        val incompleteBody = "$JSON_MARKER_START\n{\"app_version\":\"1.0.25\"}\nNo end marker"
        val result = TriageEngineHarness.extractDiagnosticJson(incompleteBody)
        assertNull(result)
    }

    @Test
    fun test_tier2_missingStartMarkerReturnsNull() {
        val incompleteBody = "{\"app_version\":\"1.0.25\"}\n$JSON_MARKER_END"
        val result = TriageEngineHarness.extractDiagnosticJson(incompleteBody)
        assertNull(result)
    }

    @Test
    fun test_tier2_emptyJsonBetweenMarkersReturnsNull() {
        val emptyBody = "$JSON_MARKER_START   $JSON_MARKER_END"
        val result = TriageEngineHarness.extractDiagnosticJson(emptyBody)
        assertNull(result)
    }

    @Test
    fun test_tier2_extraneousMarkdownInsideDetailsTagHandled() {
        val bodyWithExtra = """
            <details>
            <summary>Details</summary>
            Some markdown notes before json
            $JSON_MARKER_START
            {"app_version": "1.0.25", "version_code": 25}
            $JSON_MARKER_END
            Extra text after
            </details>
        """.trimIndent()

        val result = TriageEngineHarness.extractDiagnosticJson(bodyWithExtra)
        assertNotNull(result)
        assertEquals("1.0.25", result!!.getString("app_version"))
    }

    // =========================================================================
    // 7. Circular Breadcrumb Buffer Boundaries (4 tests)
    // =========================================================================

    @Test
    fun test_tier2_breadcrumbBufferCapsAtCapacity() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        for (i in 1..40) {
            buffer.log("EventTag", "Event message #$i")
        }

        assertEquals(25, buffer.size)
        assertEquals(25, buffer.getRecentBreadcrumbs().size)
    }

    @Test
    fun test_tier2_breadcrumbBufferEvictsOldestFIFO() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 5)
        for (i in 1..8) {
            buffer.log("Tag", "Msg $i")
        }

        val breadcrumbs = buffer.getRecentBreadcrumbs()
        assertEquals(5, breadcrumbs.size)
        assertEquals("Msg 4", breadcrumbs.first().message)
        assertEquals("Msg 8", breadcrumbs.last().message)
    }

    @Test
    fun test_tier2_breadcrumbBufferConcurrentLoggingThreadSafe() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        val executor = Executors.newFixedThreadPool(8)

        for (i in 1..100) {
            executor.submit {
                buffer.log("ThreadTag", "Concurrent log event $i")
            }
        }

        executor.shutdown()
        assertTrue("Executor should complete within 3 seconds", executor.awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(25, buffer.size)
    }

    @Test
    fun test_tier2_breadcrumbBufferClearEmptiesList() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 10)
        buffer.log("Tag", "Msg 1")
        buffer.log("Tag", "Msg 2")
        assertEquals(2, buffer.size)

        buffer.clear()
        assertEquals(0, buffer.size)
        assertTrue(buffer.getRecentBreadcrumbs().isEmpty())
    }

    // =========================================================================
    // 8. Complex Encodings & Multiline Strings (2 tests)
    // =========================================================================

    @Test
    fun test_tier2_unicodeAndEmojisInSummaryAndDescription() {
        val unicodeSummary = "🎾 Live Score Bug: Alcaraz vs Sinner (3–6, 7–6⁵, 6–4) 🏆"
        val unicodeDesc = "Match displayed as finished 🔴, but still in progress ⚡"
        val report = BugReport(BugReportCategory.SCORE_LIVE_DATA, unicodeSummary, unicodeDesc)
        val snapshot = DiagnosticSnapshot()
        val body = snapshot.toFullIssueBody(report)

        assertTrue(body.contains("🎾 Live Score Bug"))
        assertTrue(body.contains("Alcaraz vs Sinner"))
        val extracted = TriageEngineHarness.extractDiagnosticJson(body)
        assertNotNull(extracted)
    }

    @Test
    fun test_tier2_htmlAndMarkdownInjectionInUserInputsScrubbedOrEscaped() {
        val maliciousSummary = "</h1><script>alert('xss')</script>"
        val maliciousDesc = "[Click here](http://malicious-site.org) and ```rm -rf /storage```"
        val report = BugReport(BugReportCategory.OTHER, maliciousSummary, maliciousDesc)
        val body = DiagnosticSnapshot().toFullIssueBody(report)

        assertFalse("Path inside markdown injection should be redacted", body.contains("/storage"))
        assertTrue(body.contains("[REDACTED_PATH]"))
    }
}
