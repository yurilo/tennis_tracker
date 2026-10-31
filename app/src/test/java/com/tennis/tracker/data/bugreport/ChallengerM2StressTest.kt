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
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLHandshakeException

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 2.
 * Stress-tests GitHubIssueUrlBuilder and BugReportSubmissionService against:
 * 1. Extreme inputs (10k+ chars, multi-byte UTF-8, emoji sequences, URL-breaking chars).
 * 2. Surrogate boundary split conditions.
 * 3. Network failures (timeouts, aborts, truncated streams, SSL errors).
 * 4. Malformed JSON and HTTP status codes (200 with HTML, broken JSON, 4xx, 5xx).
 * 5. URL length constraint hard guarantee (<= 2000 chars under all conditions).
 * 6. Malformed endpoints, header injections, and PII leakage audit.
 */
class ChallengerM2StressTest {

    private val sampleSnapshot = DiagnosticSnapshot(
        appVersion = "1.0.25",
        versionCode = 26,
        formFactor = "PHONE",
        screenWidthBucket = "COMPACT"
    )

    private fun mockClient(interceptor: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> interceptor(chain.request()) }
            .build()
    }

    private fun textResponse(request: Request, code: Int, bodyStr: String, mediaType: String = "application/json"): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(bodyStr.toResponseBody(mediaType.toMediaType()))
            .build()
    }

    // =========================================================================
    // 1. URL BUILDER: EXTREME INPUTS & 2000-CHAR HARD LENGTH CONSTRAINT
    // =========================================================================

    @Test
    fun testSafeFallbackUrl_under2000Chars_hugeSummary15kChars() {
        val hugeSummary = "A".repeat(15000)
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = hugeSummary,
            description = "Short description"
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, sampleSnapshot)

        assertTrue("Excessive summary must trigger truncation", result.wasTruncated)
        assertTrue(
            "Fallback URL length must NEVER exceed 2000 chars regardless of input size: length=${result.url.length}",
            result.url.length <= 2000
        )
        assertTrue(result.url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        assertTrue("Full markdown body must retain complete 15k summary", result.fullMarkdownBody.contains(hugeSummary))
    }

    @Test
    fun testSafeFallbackUrl_under2000Chars_hugeDescription50kChars() {
        val hugeDescription = "D".repeat(50000)
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Live score frozen",
            description = hugeDescription,
            reproductionSteps = "Step 1\nStep 2\n" + "S".repeat(5000)
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, sampleSnapshot)

        assertTrue(result.wasTruncated)
        assertTrue(
            "Fallback URL length must NEVER exceed 2000 chars with 50k description: length=${result.url.length}",
            result.url.length <= 2000
        )
        assertTrue(result.fullMarkdownBody.contains(hugeDescription))
    }

    @Test
    fun testSafeFallbackUrl_under2000Chars_multiByteUtf8ChineseJapaneseArabicCyrillic() {
        // Multi-byte characters take 2-4 bytes in UTF-8, which translates to 6-12 percent-encoded chars each (%XX%YY)
        val multiByteSummary = "🎾 测试网球 錦標賽 بطولة كرة المضرب Тест матча 100% " + "世界".repeat(1000)
        val multiByteDesc = "詳細情報: " + "こんにちは世界".repeat(500)
        val report = BugReport(
            category = BugReportCategory.FILTER_TOUR_MISMATCH,
            summary = multiByteSummary,
            description = multiByteDesc
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, sampleSnapshot)

        assertTrue(result.wasTruncated)
        assertTrue(
            "Fallback URL with multi-byte characters must NEVER exceed 2000 chars: length=${result.url.length}",
            result.url.length <= 2000
        )
        assertTrue(result.url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        assertTrue(result.fullMarkdownBody.contains("测试网球"))
        assertTrue(result.fullMarkdownBody.contains("بطولة كرة المضرب"))
    }

    @Test
    fun testSafeFallbackUrl_under2000Chars_emojiSequencesAndZwj() {
        // Emojis with zero-width joiners, skin tones, surrogate pairs
        val emojiFlood = "🎾🏆🥇👨‍👩‍👧‍👦🏳️‍🌈🧑🏾‍🦱⚡🔥💥".repeat(300)
        val report = BugReport(
            category = BugReportCategory.WATCH_HIGHLIGHTS,
            summary = "Emoji bug: $emojiFlood",
            description = "Description with emojis: $emojiFlood"
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, sampleSnapshot)

        assertTrue(result.wasTruncated)
        assertTrue(
            "Fallback URL with emoji flood must strictly satisfy <= 2000 chars: length=${result.url.length}",
            result.url.length <= 2000
        )
        assertTrue(result.fullMarkdownBody.contains("👨‍👩‍👧‍👦"))
    }

    @Test
    fun testSafeFallbackUrl_surrogatePairBoundarySplit_doesNotThrow() {
        // In UTF-16, an emoji like 😃 is 2 chars (\uD83D\uDE03).
        // If clampedTitle cuts between high surrogate and low surrogate, URLEncoder must not crash.
        // Category displayName: "Other" -> "[Other] " is 8 chars.
        // We want char at index 56 to be high surrogate, index 57 to be low surrogate.
        // 56 - 8 = 48 ASCII chars before the emoji.
        val prefix = "X".repeat(48)
        val surrogateEmoji = "\uD83D\uDE03" // 2 chars: high surrogate \uD83D (56), low surrogate \uDE03 (57)
        val summary = prefix + surrogateEmoji + " extra text that forces truncation " + "Z".repeat(500)
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = summary,
            description = "Description"
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, sampleSnapshot)

        assertNotNull(result.url)
        assertTrue("URL must be <= 2000 chars: length=${result.url.length}", result.url.length <= 2000)
        assertTrue(result.url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
    }

    @Test
    fun testSafeFallbackUrl_urlBreakingCharacters_properlyPercentEncoded() {
        // Characters that break raw URLs if not percent-encoded:
        val dangerousChars = "& = ? # / \\ + % \" ' ` <script>alert('xss')</script> \r\n\t \u0000"
        val report = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "Layout break with $dangerousChars",
            description = "Desc with $dangerousChars"
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, sampleSnapshot)

        val url = result.url
        assertTrue(url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        // Check that literal breaking characters are not present unencoded in query
        val queryPart = url.substringAfter("https://github.com/yurilo/tennis_tracker/issues/new?")
        assertFalse("Raw '#' must not appear in query", queryPart.contains("#"))
        assertFalse("Raw '<' must not appear in query", queryPart.contains("<"))
        assertFalse("Raw '>' must not appear in query", queryPart.contains(">"))
        assertFalse("Raw '\"' must not appear in query", queryPart.contains("\""))
        assertFalse("Raw line breaks must not appear in query", queryPart.contains("\n") || queryPart.contains("\r"))

        // Verify the URL query can be parsed cleanly into parameters
        val params = queryPart.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = parts[0]
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.toString()) else ""
            key to value
        }

        assertTrue("Query must contain title", params.containsKey("title"))
        assertTrue("Query must contain body", params.containsKey("body"))
        assertTrue("Query must contain labels", params.containsKey("labels"))
        assertEquals("bug,in-app-report,ui-layout", params["labels"])
        assertTrue("Title should safely contain decoded script text", params["title"]!!.contains("<script>"))
    }

    // =========================================================================
    // 2. BUG REPORT SUBMISSION SERVICE: NETWORK FAILURES & INTERCEPTORS
    // =========================================================================

    @Test
    fun testSubmission_socketTimeout_returnsFallbackRequired() = runTest {
        val client = mockClient {
            throw SocketTimeoutException("Read timed out after 15000ms")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Timeout test", description = "Test"),
            sampleSnapshot
        )

        assertTrue("SocketTimeoutException must return FallbackRequired, got: $result", result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.fallbackUrl.isNotBlank())
        assertTrue(fallback.reason.contains("offline", ignoreCase = true) || fallback.reason.contains("timed out", ignoreCase = true))
    }

    @Test
    fun testSubmission_connectException_returnsFallbackRequired() = runTest {
        val client = mockClient {
            throw ConnectException("Failed to connect to /104.21.55.2:443")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Connect refused test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.fallbackUrl.isNotBlank())
    }

    @Test
    fun testSubmission_unknownHostException_returnsFallbackRequired() = runTest {
        val client = mockClient {
            throw UnknownHostException("Unable to resolve host telemetry.tennis-tracker.app")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "DNS failure test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testSubmission_sslHandshakeException_returnsFallbackRequired() = runTest {
        val client = mockClient {
            throw SSLHandshakeException("Cert chain validation failed")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "SSL error test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testSubmission_connectionResetByPeer_returnsFallbackRequired() = runTest {
        val client = mockClient {
            throw SocketException("Connection reset by peer")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Connection reset test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testSubmission_truncatedStream_returnsFallbackRequired() = runTest {
        // Response begins returning HTTP 200, but reading response body stream abruptly throws IOException/EOFException
        val client = mockClient { req ->
            val faultySource = object : ForwardingSource(Buffer().writeUtf8("""{"issue_number": 999""")) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    super.read(sink, byteCount)
                    throw ProtocolException("unexpected end of stream")
                }
            }
            val faultyBody = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = 1000L
                override fun source(): BufferedSource = faultySource.buffer()
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(faultyBody)
                .build()
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Truncated stream test", description = "Test"),
            sampleSnapshot
        )

        assertTrue("Truncated stream must be handled cleanly as FallbackRequired, got: $result", result is SubmissionResult.FallbackRequired)
    }

    // =========================================================================
    // 3. MALFORMED JSON & UNEXPECTED HTTP BODIES
    // =========================================================================

    @Test
    fun testSubmission_relayReturnsHtmlInsteadOfJson_returnsFallbackRequired() = runTest {
        val client = mockClient { req ->
            textResponse(req, 200, "<html><body>502 Bad Gateway</body></html>", "text/html")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "HTML response test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
        val fallback = result as SubmissionResult.FallbackRequired
        assertTrue(fallback.fallbackUrl.isNotBlank())
    }

    @Test
    fun testSubmission_relayReturnsMalformedBrokenJson_returnsFallbackRequired() = runTest {
        val client = mockClient { req ->
            textResponse(req, 200, """{"success": true, "issue_num""")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Malformed JSON test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testSubmission_relayReturnsEmptyBody_returnsFallbackRequired() = runTest {
        val client = mockClient { req ->
            textResponse(req, 200, "")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Empty body test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testSubmission_relayReturnsJsonArray_returnsFallbackRequired() = runTest {
        val client = mockClient { req ->
            textResponse(req, 200, """[{"issue_number": 123}]""")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "JSON Array test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testSubmission_relayReturnsNegativeOrZeroIssueNumber_returnsFallbackRequired() = runTest {
        val client = mockClient { req ->
            textResponse(req, 200, """{"success": true, "issue_number": 0, "issue_url": "https://github.com/..."}""")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Zero issue number test", description = "Test"),
            sampleSnapshot
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testDirectGitHub_returnsMalformedJson_returnsFallbackRequired() = runTest {
        val client = mockClient { req ->
            textResponse(req, 201, "<html>Not JSON</html>")
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Direct GitHub malformed", description = "Test"),
            sampleSnapshot,
            customToken = "ghp_validTokenMock"
        )

        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testDirectGitHub_returnsMissingHtmlUrl_returnsFallbackRequired() = runTest {
        val client = mockClient { req ->
            textResponse(req, 201, """{"number": 123}""") // missing html_url
        }
        val service = BugReportSubmissionService(client = client)

        val result = service.submitReport(
            BugReport(summary = "Direct GitHub missing url", description = "Test"),
            sampleSnapshot,
            customToken = "ghp_validTokenMock"
        )

        // getString("html_url") throws JSONException which is caught and mapped to FallbackRequired
        assertTrue(result is SubmissionResult.FallbackRequired)
    }

    // =========================================================================
    // 4. MALFORMED CONFIGURATIONS & INJECTION ATTEMPTS
    // =========================================================================

    @Test
    fun testSubmission_malformedEndpointUrl_neverThrowsException() = runTest {
        val client = mockClient { req -> textResponse(req, 200, "{}") }
        val service = BugReportSubmissionService(client = client)

        // OkHttp Request.Builder().url() throws IllegalArgumentException on bad URL
        val result = service.submitReport(
            BugReport(summary = "Bad endpoint", description = "Test"),
            sampleSnapshot,
            endpoint = "http://"
        )

        assertTrue("Malformed endpoint URL must be caught and return FallbackRequired", result is SubmissionResult.FallbackRequired)
    }

    @Test
    fun testSubmission_headerInjectionInCustomToken_neverThrowsException() = runTest {
        val client = mockClient { req -> textResponse(req, 200, "{}") }
        val service = BugReportSubmissionService(client = client)

        // OkHttp Request.Builder().header() throws IllegalArgumentException on newline/control chars
        val result = service.submitReport(
            BugReport(summary = "Token injection", description = "Test"),
            sampleSnapshot,
            customToken = "ghp_test\r\nX-Injected-Header: evil"
        )

        assertTrue("Header injection in custom token must be caught and return FallbackRequired", result is SubmissionResult.FallbackRequired)
    }

    // =========================================================================
    // 5. PII REDACTION AUDIT IN FALLBACK URL AND DIRECT SUBMISSION
    // =========================================================================

    @Test
    fun testAudit_piiLeakageInSummaryFallbackUrl() {
        val secretToken = "ghp_1234567890abcdef1234567890abcdef"
        val userEmail = "developer@tennis-tracker.app"
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Crash when using $secretToken or $userEmail",
            description = "Token was $secretToken"
        )

        val fallbackResult = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, sampleSnapshot)

        // In fallbackResult.fullMarkdownBody, PiiScrubber is applied to summary and description:
        assertFalse(
            "fullMarkdownBody must NOT contain secretToken",
            fallbackResult.fullMarkdownBody.contains(secretToken)
        )
        assertFalse(
            "fullMarkdownBody must NOT contain userEmail",
            fallbackResult.fullMarkdownBody.contains(userEmail)
        )

        // Check that fallback URL title parameter does NOT leak secret tokens or user emails
        val result = fallbackResult
        assertFalse("Fallback URL must NOT leak raw token", result.url.contains("ghp_"))
        assertFalse("Fallback URL must NOT leak secretToken", result.url.contains(secretToken))
        assertFalse("Fallback URL must NOT leak userEmail", result.url.contains(userEmail))
        assertTrue(
            "Fallback URL must contain redacted token indicator",
            result.url.contains("[REDACTED_GH_TOKEN]") || result.url.contains("%5BREDACTED_GH_TOKEN%5D")
        )
    }
}
