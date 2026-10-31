package com.tennis.tracker.data.bugreport

import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BugReportSubmissionServiceTest {

    private val sampleReport = BugReport(
        category = BugReportCategory.FILTER_TOUR_MISMATCH,
        summary = "Live match missing under ATP filter",
        description = "Carlos Alcaraz match is not visible under ATP drawer filter",
        reproductionSteps = "1. Select ATP filter\n2. Open scoreboard"
    )

    private val sampleSnapshot = DiagnosticSnapshot(
        appVersion = "1.0.25",
        versionCode = 26,
        formFactor = "PHONE",
        screenWidthBucket = "COMPACT"
    )

    private fun mockOkHttpClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }

    private fun jsonResponse(request: Request, code: Int, jsonBody: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(jsonBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun testRelaySubmission_success200_returnsSuccessWithIssueNumberAndUrl() = runTest {
        var interceptedRequest: Request? = null
        val client = mockOkHttpClient { req ->
            interceptedRequest = req
            jsonResponse(
                req,
                201,
                """{"success":true,"issue_number":101,"issue_url":"https://github.com/yurilo/tennis_tracker/issues/101"}"""
            )
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot)

        assertTrue("Expected Success result, got: $result", result is SubmissionResult.Success)
        val success = result as SubmissionResult.Success
        assertEquals(101, success.issueNumber)
        assertEquals("https://github.com/yurilo/tennis_tracker/issues/101", success.issueUrl)

        val req = interceptedRequest!!
        assertEquals("https://telemetry.tennis-tracker.app/bug-report", req.url.toString())
        assertEquals("TennisTracker/1.0.25 (Android unknown; PHONE)", req.header("User-Agent"))
        assertEquals("1.0.25", req.header("X-Client-Version"))
    }

    @Test
    fun testDirectGitHubSubmission_withCustomToken_success201() = runTest {
        var interceptedRequest: Request? = null
        val client = mockOkHttpClient { req ->
            interceptedRequest = req
            jsonResponse(
                req,
                201,
                """{"number":202,"html_url":"https://github.com/yurilo/tennis_tracker/issues/202"}"""
            )
        }

        val service = BugReportSubmissionService(client = client)
        val customToken = "ghp_mockSecretToken456"
        val result = service.submitReport(sampleReport, sampleSnapshot, customToken = customToken)

        assertTrue(result is SubmissionResult.Success)
        val success = result as SubmissionResult.Success
        assertEquals(202, success.issueNumber)
        assertEquals("https://github.com/yurilo/tennis_tracker/issues/202", success.issueUrl)

        val req = interceptedRequest!!
        assertEquals("https://api.github.com/repos/yurilo/tennis_tracker/issues", req.url.toString())
        assertEquals("Bearer $customToken", req.header("Authorization"))
        assertEquals("application/vnd.github+json", req.header("Accept"))
        assertEquals("2022-11-28", req.header("X-GitHub-Api-Version"))
    }

    @Test
    fun testRelaySubmission_rateLimit429_returnsFallbackRequired() = runTest {
        val client = mockOkHttpClient { req ->
            jsonResponse(
                req,
                429,
                """{"error":"Rate limit exceeded. Maximum 5 bug reports per 10 minutes.","reason":"RATE_LIMIT_EXCEEDED","retry_after":600}"""
            )
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot)

        assertTrue("Expected FallbackRequired on 429, got: $result", result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.fallbackUrl.contains("https://github.com/yurilo/tennis_tracker/issues/new"))
        assertTrue(fallback.fullMarkdownBody.contains("<!-- BUGREPORT_JSON_START -->"))
        assertTrue(fallback.reason.contains("rate limit", ignoreCase = true) || fallback.reason.contains("429"))
    }

    @Test
    fun testRelaySubmission_unconfigured503_returnsFallbackRequired() = runTest {
        val client = mockOkHttpClient { req ->
            jsonResponse(
                req,
                503,
                """{"fallback_required":true,"reason":"RELAY_TOKEN_UNCONFIGURED","error":"GitHub relay token not configured on worker"}"""
            )
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot)

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("unconfigured", ignoreCase = true) || fallback.reason.contains("503"))
        assertTrue(fallback.fallbackUrl.isNotBlank())
        assertTrue(fallback.fullMarkdownBody.contains("Live match missing under ATP filter"))
    }

    @Test
    fun testRelaySubmission_networkOfflineIOException_returnsFallbackRequired() = runTest {
        val client = mockOkHttpClient {
            throw IOException("Connect timed out to telemetry.tennis-tracker.app")
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot)

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("offline", ignoreCase = true) || fallback.reason.contains("timed out", ignoreCase = true))
        assertTrue(fallback.fallbackUrl.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
    }

    @Test
    fun testSubmission_isOnlineFalse_immediatelyReturnsFallbackRequiredWithoutNetwork() = runTest {
        var networkHit = false
        val client = mockOkHttpClient { req ->
            networkHit = true
            jsonResponse(req, 201, "{}")
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot, isOnline = false)

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertEquals("Device is offline", fallback.reason)
        assertEquals(false, networkHit)
    }

    @Test
    fun testDirectGitHubSubmission_invalidToken401_returnsFallbackRequired() = runTest {
        val client = mockOkHttpClient { req ->
            jsonResponse(req, 401, """{"message":"Bad credentials","documentation_url":"https://docs.github.com"}""")
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot, customToken = "ghp_bad_token")

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("401") || fallback.reason.contains("Bad credentials"))
    }

    @Test
    fun testCustomEndpointOverride_usesCustomUrlInsteadOfDefault() = runTest {
        var requestedUrl = ""
        val customUrl = "https://custom-relay.internal.net/submit-report"
        val client = mockOkHttpClient { req ->
            requestedUrl = req.url.toString()
            jsonResponse(req, 200, """{"issue_number":777,"issue_url":"https://github.com/yurilo/tennis_tracker/issues/777"}""")
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot, endpoint = customUrl)

        assertTrue(result is SubmissionResult.Success)
        assertEquals(customUrl, requestedUrl)
    }

    @Test
    fun testRelaySubmission_server500Error_returnsFallbackRequired() = runTest {
        val client = mockOkHttpClient { req ->
            jsonResponse(req, 500, """{"error":"Internal Database Error"}""")
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot)

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.reason.contains("500") || fallback.reason.contains("Error"))
    }

    @Test
    fun testRelaySubmission_malformedResponsePayload_returnsFallbackRequired() = runTest {
        val client = mockOkHttpClient { req ->
            jsonResponse(req, 200, """{"status":"ok_but_missing_issue_data"}""")
        }

        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(sampleReport, sampleSnapshot)

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.fallbackUrl.isNotBlank())
    }

    @Test
    fun testDirectGitHubSubmission_scrubsPiiInPayloadTitle() = runTest {
        var interceptedTitle = ""
        val secretToken = "ghp_9999999999abcdef9999999999abcdef"
        val client = mockOkHttpClient { req ->
            val bodyString = req.body?.let {
                val buffer = okio.Buffer()
                it.writeTo(buffer)
                buffer.readUtf8()
            }.orEmpty()
            val json = JSONObject(bodyString)
            interceptedTitle = json.optString("title")
            jsonResponse(req, 201, """{"number":303,"html_url":"https://github.com/yurilo/tennis_tracker/issues/303"}""")
        }

        val reportWithPii = sampleReport.copy(summary = "Crash with token $secretToken")
        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(reportWithPii, sampleSnapshot, customToken = "ghp_user_token")

        assertTrue(result is SubmissionResult.Success)
        assertFalse("Direct GitHub title must NOT contain raw token", interceptedTitle.contains(secretToken))
        assertFalse("Direct GitHub title must NOT contain ghp_ prefix", interceptedTitle.contains("ghp_"))
        assertTrue("Direct GitHub title must contain [REDACTED_GH_TOKEN]", interceptedTitle.contains("[REDACTED_GH_TOKEN]"))
        assertEquals("[Filter / Tour Mismatch] Crash with token [REDACTED_GH_TOKEN]", interceptedTitle)
    }

    @Test
    fun testRelaySubmission_scrubsPiiInPayloadTitleAndSummary() = runTest {
        var interceptedTitle = ""
        var interceptedSummary = ""
        val secretToken = "ghp_8888888888abcdef8888888888abcdef"
        val client = mockOkHttpClient { req ->
            val bodyString = req.body?.let {
                val buffer = okio.Buffer()
                it.writeTo(buffer)
                buffer.readUtf8()
            }.orEmpty()
            val json = JSONObject(bodyString)
            interceptedTitle = json.optString("title")
            interceptedSummary = json.optString("summary")
            jsonResponse(req, 201, """{"success":true,"issue_number":404,"issue_url":"https://github.com/yurilo/tennis_tracker/issues/404"}""")
        }

        val reportWithPii = sampleReport.copy(summary = "Relay test with $secretToken")
        val service = BugReportSubmissionService(client = client)
        val result = service.submitReport(reportWithPii, sampleSnapshot)

        assertTrue(result is SubmissionResult.Success)
        assertFalse("Relay title must NOT contain raw token", interceptedTitle.contains(secretToken))
        assertFalse("Relay summary must NOT contain raw token", interceptedSummary.contains(secretToken))
        assertFalse("Relay title must NOT contain ghp_ prefix", interceptedTitle.contains("ghp_"))
        assertTrue(interceptedTitle.contains("[REDACTED_GH_TOKEN]"))
        assertTrue(interceptedSummary.contains("[REDACTED_GH_TOKEN]"))
    }

    @Test
    fun testSubmission_coroutineCancellation_rethrowsCancellationException() = runTest {
        val client = mockOkHttpClient {
            throw kotlinx.coroutines.CancellationException("Parent coroutine cancelled")
        }
        val service = BugReportSubmissionService(client = client)

        var thrown = false
        try {
            service.submitReport(sampleReport, sampleSnapshot)
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = true
        }

        assertTrue("CancellationException must be rethrown rather than caught as FallbackRequired", thrown)
    }
}
