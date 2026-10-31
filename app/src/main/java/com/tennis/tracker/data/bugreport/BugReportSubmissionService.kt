package com.tennis.tracker.data.bugreport

import android.util.Log
import com.tennis.tracker.data.diagnostic.PiiScrubber
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sealed representation of bug report submission outcome.
 */
sealed class SubmissionResult {
    /**
     * Successfully created an issue on GitHub.
     */
    data class Success(
        val issueNumber: Int,
        val issueUrl: String
    ) : SubmissionResult()

    /**
     * Submission failed, relay unavailable, or rate limited; the caller should copy [fullMarkdownBody]
     * to the clipboard and launch [fallbackUrl] in the browser.
     */
    data class FallbackRequired(
        val fallbackUrl: String,
        val fullMarkdownBody: String,
        val reason: String
    ) : SubmissionResult()
}

/**
 * Contract for submitting bug reports through relay, direct GitHub API, or web fallback.
 */
interface BugReportSubmissionServiceContract {
    suspend fun submitReport(
        report: BugReport,
        snapshot: DiagnosticSnapshot,
        endpoint: String? = null,
        customToken: String? = null,
        isOnline: Boolean = true
    ): SubmissionResult
}

/**
 * Resilient bug report submission service supporting dual submission paths:
 * 1. Primary: Cloudflare Worker relay endpoint with rate-limiting and server-side GitHub secret.
 * 2. Direct: Direct GitHub REST API v3 submission using user-provided Personal Access Token.
 * 3. Graceful web fallback for offline conditions, timeouts, rate limiting, and unconfigured relays.
 */
class BugReportSubmissionService(
    private val client: OkHttpClient = defaultClient(),
    private val defaultEndpoint: String = DEFAULT_ENDPOINT,
    private val issueUrlBuilder: GitHubIssueUrlBuilder = GitHubIssueUrlBuilder
) : BugReportSubmissionServiceContract {

    companion object {
        private const val TAG = "BugReportSubmission"
        const val DEFAULT_ENDPOINT = AppSettings.DEFAULT_BUG_REPORT_ENDPOINT
        const val GITHUB_ISSUES_API_URL = "https://api.github.com/repos/yurilo/tennis_tracker/issues"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    override suspend fun submitReport(
        report: BugReport,
        snapshot: DiagnosticSnapshot,
        endpoint: String?,
        customToken: String?,
        isOnline: Boolean
    ): SubmissionResult = withContext(Dispatchers.IO) {
        val fullMarkdownBody = snapshot.toFullIssueBody(report)
        val fallbackResult = issueUrlBuilder.buildSafeFallbackUrl(report, snapshot)

        // Offline pre-check
        if (!isOnline) {
            Log.i(TAG, "Device is offline. Triggering fallback to web URL.")
            return@withContext SubmissionResult.FallbackRequired(
                fallbackUrl = fallbackResult.url,
                fullMarkdownBody = fullMarkdownBody,
                reason = "Device is offline"
            )
        }

        // 1. Direct GitHub REST API override when custom token is configured
        if (!customToken.isNullOrBlank()) {
            return@withContext submitDirectToGitHub(report, snapshot, fullMarkdownBody, fallbackResult, customToken)
        }

        // 2. Primary submission via Cloudflare Worker relay
        return@withContext submitViaRelay(report, snapshot, fullMarkdownBody, fallbackResult, endpoint)
    }

    private fun submitDirectToGitHub(
        report: BugReport,
        snapshot: DiagnosticSnapshot,
        fullMarkdownBody: String,
        fallbackResult: GitHubIssueUrlBuilder.FallbackResult,
        token: String
    ): SubmissionResult {
        val cleanToken = token.trim()
        val authHeader = if (cleanToken.startsWith("Bearer ", ignoreCase = true) ||
            cleanToken.startsWith("token ", ignoreCase = true)) {
            cleanToken
        } else {
            "Bearer $cleanToken"
        }

        return try {
            val scrubbedSummary = PiiScrubber.scrub(report.summary.trim())
            val title = "[${report.category.displayName}] $scrubbedSummary"
            val payload = JSONObject().apply {
                put("title", title)
                put("body", fullMarkdownBody)
                val labelsArray = JSONArray().apply {
                    put("bug")
                    put("in-app-report")
                    put(report.category.labelSlug)
                }
                put("labels", labelsArray)
            }

            val request = Request.Builder()
                .url(GITHUB_ISSUES_API_URL)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "TennisTracker/${snapshot.appVersion}")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(responseBodyStr)
                    val issueNum = json.getInt("number")
                    val issueUrl = json.getString("html_url")
                    Log.i(TAG, "Direct GitHub issue created: #$issueNum")
                    SubmissionResult.Success(issueNumber = issueNum, issueUrl = issueUrl)
                } else {
                    val errorMsg = runCatching { JSONObject(responseBodyStr).optString("message") }
                        .getOrDefault("HTTP ${response.code}")
                    Log.w(TAG, "Direct GitHub submission rejected: HTTP ${response.code} ($errorMsg)")
                    SubmissionResult.FallbackRequired(
                        fallbackUrl = fallbackResult.url,
                        fullMarkdownBody = fullMarkdownBody,
                        reason = "GitHub API HTTP ${response.code}: $errorMsg"
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Direct GitHub submission network failure: ${e.message}")
            SubmissionResult.FallbackRequired(
                fallbackUrl = fallbackResult.url,
                fullMarkdownBody = fullMarkdownBody,
                reason = "Network error: ${e.message ?: "Device is offline"}"
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "Direct GitHub submission unexpected error: ${t.message}", t)
            SubmissionResult.FallbackRequired(
                fallbackUrl = fallbackResult.url,
                fullMarkdownBody = fullMarkdownBody,
                reason = "Submission error: ${t.message ?: "Unknown error"}"
            )
        }
    }

    private fun submitViaRelay(
        report: BugReport,
        snapshot: DiagnosticSnapshot,
        fullMarkdownBody: String,
        fallbackResult: GitHubIssueUrlBuilder.FallbackResult,
        endpoint: String?
    ): SubmissionResult {
        val targetEndpoint = endpoint?.trim()?.takeIf { it.isNotBlank() } ?: defaultEndpoint

        return try {
            val scrubbedSummary = PiiScrubber.scrub(report.summary.trim())
            val title = "[${report.category.displayName}] $scrubbedSummary"
            val payload = JSONObject().apply {
                put("category", report.category.labelSlug)
                put("summary", scrubbedSummary)
                put("description", PiiScrubber.scrub(report.description.trim()))
                report.cleanedReproductionSteps?.let {
                    put("reproduction_steps", PiiScrubber.scrub(it))
                    put("reproductionSteps", PiiScrubber.scrub(it))
                }
                put("title", title)
                put("body", fullMarkdownBody)
                val labelsArray = JSONArray().apply {
                    put("bug")
                    put("in-app-report")
                    put(report.category.labelSlug)
                }
                put("labels", labelsArray)
                put("diagnostic_snapshot", snapshot.toJson())
                put("diagnosticSnapshot", snapshot.toJson())
            }

            val request = Request.Builder()
                .url(targetEndpoint)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "TennisTracker/${snapshot.appVersion} (Android ${snapshot.osVersion}; ${snapshot.formFactor})")
                .header("X-Client-Version", snapshot.appVersion)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBodyStr = response.body?.string().orEmpty()
                val responseJson = runCatching { JSONObject(responseBodyStr) }.getOrNull()

                if (response.isSuccessful && responseJson != null) {
                    val issueNum = responseJson.optInt(
                        "issue_number",
                        responseJson.optInt("issueNumber", responseJson.optInt("number", 0))
                    )
                    val issueUrl = responseJson.optString(
                        "issue_url",
                        responseJson.optString("issueUrl", responseJson.optString("html_url", ""))
                    )

                    if (issueNum > 0 && issueUrl.isNotBlank()) {
                        Log.i(TAG, "Relay created GitHub issue #$issueNum")
                        SubmissionResult.Success(issueNumber = issueNum, issueUrl = issueUrl)
                    } else {
                        val reason = responseJson.optString("error", "Invalid relay response payload")
                        SubmissionResult.FallbackRequired(
                            fallbackUrl = fallbackResult.url,
                            fullMarkdownBody = fullMarkdownBody,
                            reason = reason
                        )
                    }
                } else {
                    val status = response.code
                    val errorDetail = responseJson?.optString("error")
                        ?.takeIf { it.isNotBlank() }
                        ?: responseJson?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: responseJson?.optString("reason")
                        ?.takeIf { it.isNotBlank() }
                        ?: "HTTP $status"

                    val reason = when (status) {
                        429 -> "Relay rate limit exceeded ($errorDetail)"
                        503 -> "Relay token unconfigured ($errorDetail)"
                        else -> "Relay returned HTTP $status ($errorDetail)"
                    }
                    Log.w(TAG, "Relay submission failed with HTTP $status: $reason")
                    SubmissionResult.FallbackRequired(
                        fallbackUrl = fallbackResult.url,
                        fullMarkdownBody = fullMarkdownBody,
                        reason = reason
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Relay connection failed (offline or timeout): ${e.message}")
            SubmissionResult.FallbackRequired(
                fallbackUrl = fallbackResult.url,
                fullMarkdownBody = fullMarkdownBody,
                reason = "Device is offline or connection timed out"
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "Relay unexpected exception: ${t.message}", t)
            SubmissionResult.FallbackRequired(
                fallbackUrl = fallbackResult.url,
                fullMarkdownBody = fullMarkdownBody,
                reason = "Submission error: ${t.message ?: "Unknown"}"
            )
        }
    }
}
