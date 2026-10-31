package com.tennis.tracker.e2e.harness

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.regex.Pattern

/**
 * Universal Opaque-Box Test Harness for In-App Bug Reporting & Automated Triage.
 *
 * Implements authoritative interface contracts specified in:
 * - ORIGINAL_REQUEST.md (R1, R2, R3, R4, R5)
 * - PROJECT.md § Interface Contracts
 */
object BugReportContractHarness {

    const val JSON_MARKER_START = "<!-- BUGREPORT_JSON_START -->"
    const val JSON_MARKER_END = "<!-- BUGREPORT_JSON_END -->"
    const val GITHUB_REPO = "yurilo/tennis_tracker"
    const val DEFAULT_WORKER_ENDPOINT = "https://telemetry.tennis-tracker.workers.dev/bug-report"

    // =========================================================================
    // 1. Bug Report Domain Models
    // =========================================================================

    enum class BugReportCategory(val displayName: String, val slug: String) {
        FILTER_TOUR_MISMATCH("Filter / Tour Mismatch", "filter-tour"),
        SCORE_LIVE_DATA("Score / Live Data", "score-live-data"),
        WATCH_HIGHLIGHTS("Watch / Highlights", "watch-highlights"),
        HOME_WIDGET("Home Widget", "home-widget"),
        UI_LAYOUT("UI / Layout", "ui-layout"),
        OTHER("Other", "other");

        companion object {
            fun fromSlug(slug: String): BugReportCategory? =
                values().firstOrNull { it.slug.equals(slug, ignoreCase = true) }
        }
    }

    data class BugReport(
        val category: BugReportCategory,
        val summary: String,
        val description: String,
        val reproductionSteps: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        init {
            require(summary.isNotBlank()) { "Bug report summary cannot be empty or blank" }
            require(description.isNotBlank()) { "Bug report description cannot be empty or blank" }
        }
    }

    data class DiagnosticBreadcrumb(
        val timestamp: Long = System.currentTimeMillis(),
        val tag: String,
        val message: String,
        val metadata: Map<String, String> = emptyMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("timestamp", timestamp)
            put("tag", tag)
            put("message", message)
            if (metadata.isNotEmpty()) {
                val metaJson = JSONObject()
                metadata.forEach { (k, v) -> metaJson.put(k, v) }
                put("metadata", metaJson)
            }
        }
    }

    // =========================================================================
    // 2. Zero-PII Sanitization Engine (PiiScrubber)
    // =========================================================================

    object PiiScrubber {
        private val GITHUB_TOKEN_PATTERN = Pattern.compile(
            "\\b(gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{30,82})\\b"
        )
        private val BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\b(bearer\\s+)([A-Za-z0-9_\\-\\.~+/]{16,})\\b"
        )
        private val API_KEY_QUERY_PATTERN = Pattern.compile(
            "(?i)\\b((?:api[_-]?key|key|token|secret|auth|access_key)[\\s]*[=:][\\s]*['\"]?)([A-Za-z0-9_\\-]{16,})(['\"]?)"
        )
        private val GOOGLE_API_KEY_PATTERN = Pattern.compile(
            "\\b(AIza[A-Za-z0-9_\\-]{30,40})\\b"
        )
        private val PASSWORD_PATTERN = Pattern.compile(
            "(?i)\\b((?:password|pwd|passwd)[\\s]*[=:][\\s]*['\"]?)([^'\"\\s,;]+)(['\"]?)"
        )
        private val IPV4_PATTERN = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"
        )
        private val EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
        )
        private val FILE_PATH_PATTERN = Pattern.compile(
            "(?:/(?:storage|home|Users|data|var|tmp|etc|opt)(?:/[A-Za-z0-9._\\-/]*)?|[A-Za-z]:\\\\[A-Za-z0-9._\\\\\\-]+)"
        )

        fun scrub(input: String?): String {
            if (input == null) return ""
            var result = input

            // Redact GitHub tokens
            result = GITHUB_TOKEN_PATTERN.matcher(result).replaceAll("[REDACTED_GITHUB_TOKEN]")

            // Redact Bearer tokens
            result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("$1[REDACTED_BEARER_TOKEN]")

            // Redact Google / AWS style API keys
            result = GOOGLE_API_KEY_PATTERN.matcher(result).replaceAll("[REDACTED_API_KEY]")

            // Redact Key=value pairs
            result = API_KEY_QUERY_PATTERN.matcher(result).replaceAll("$1[REDACTED_API_KEY]$3")

            // Redact Passwords
            result = PASSWORD_PATTERN.matcher(result).replaceAll("$1[REDACTED_PASSWORD]$3")

            // Redact Emails
            result = EMAIL_PATTERN.matcher(result).replaceAll("[REDACTED_EMAIL]")

            // Redact Local File System Paths
            result = FILE_PATH_PATTERN.matcher(result).replaceAll("[REDACTED_PATH]")

            // Redact IP Addresses (exclude standard 0.0.0.0 if not needed, but here sanitize all)
            result = IPV4_PATTERN.matcher(result).replaceAll("[REDACTED_IP]")

            return result
        }
    }

    // =========================================================================
    // 3. Thread-Safe Circular Breadcrumb Ring Buffer
    // =========================================================================

    class DiagnosticBreadcrumbBuffer(val capacity: Int = 25) {
        private val buffer = ConcurrentLinkedDeque<DiagnosticBreadcrumb>()

        fun log(tag: String, message: String, metadata: Map<String, String> = emptyMap()) {
            val scrubbedTag = PiiScrubber.scrub(tag)
            val scrubbedMsg = PiiScrubber.scrub(message)
            val scrubbedMeta = metadata.mapValues { PiiScrubber.scrub(it.value) }

            val breadcrumb = DiagnosticBreadcrumb(
                timestamp = System.currentTimeMillis(),
                tag = scrubbedTag,
                message = scrubbedMsg,
                metadata = scrubbedMeta
            )

            synchronized(buffer) {
                while (buffer.size >= capacity) {
                    buffer.pollFirst() // evict oldest
                }
                buffer.offerLast(breadcrumb)
            }
        }

        fun getRecentBreadcrumbs(): List<DiagnosticBreadcrumb> {
            synchronized(buffer) {
                return buffer.toList()
            }
        }

        fun clear() {
            synchronized(buffer) {
                buffer.clear()
            }
        }

        val size: Int
            get() = synchronized(buffer) { buffer.size }
    }

    // =========================================================================
    // 4. Diagnostic Snapshots (State, Settings, Match Context)
    // =========================================================================

    data class ScoreboardStateSnapshot(
        val tourFilter: String = "ALL",
        val currentTournament: String? = null,
        val matchTypeFilter: String = "ALL",
        val matchFilter: String = "ALL",
        val searchActive: Boolean = false,
        val isOffline: Boolean = false,
        val totalMatches: Int = 0,
        val filteredMatches: Int = 0,
        val liveMatches: Int = 0,
        val followedMatches: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("tour_filter", tourFilter)
            put("current_tournament", currentTournament ?: JSONObject.NULL)
            put("match_type_filter", matchTypeFilter)
            put("match_filter", matchFilter)
            put("search_active", searchActive)
            put("is_offline", isOffline)
            put("total_matches", totalMatches)
            put("filtered_matches", filteredMatches)
            put("live_matches", liveMatches)
            put("followed_matches", followedMatches)
        }
    }

    data class SettingsSnapshot(
        val autoRefreshInterval: String = "30s",
        val matchRetention: String = "24h",
        val widgetSyncMode: String = "ADAPTIVE",
        val defaultTournament: String = "ALL",
        val telemetryEnabled: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("auto_refresh_interval", autoRefreshInterval)
            put("match_retention", matchRetention)
            put("widget_sync_mode", widgetSyncMode)
            put("default_tournament", defaultTournament)
            put("telemetry_enabled", telemetryEnabled)
        }
    }

    data class MatchContextSnapshot(
        val matchId: String,
        val tournamentName: String,
        val tournamentId: String? = null,
        val eventGrouping: String = "Men's Singles",
        val effectiveTour: String = "ATP",
        val effectiveCategory: String = "GRAND_SLAM",
        val state: String = "LIVE",
        val court: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("match_id", matchId)
            put("tournament_name", tournamentName)
            put("tournament_id", tournamentId ?: JSONObject.NULL)
            put("event_grouping", eventGrouping)
            put("effective_tour", effectiveTour)
            put("effective_category", effectiveCategory)
            put("state", state)
            put("court", court ?: JSONObject.NULL)
        }
    }

    data class DiagnosticSnapshot(
        val appVersion: String = "1.0.25",
        val versionCode: Int = 25,
        val formFactor: String = "PHONE", // "PHONE" or "LAPTOP"
        val screenWidthBucket: String = "COMPACT", // "COMPACT", "MEDIUM", "EXPANDED"
        val scoreboardState: ScoreboardStateSnapshot = ScoreboardStateSnapshot(),
        val settingsSnapshot: SettingsSnapshot = SettingsSnapshot(),
        val matchContext: MatchContextSnapshot? = null,
        val breadcrumbs: List<DiagnosticBreadcrumb> = emptyList()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("app_version", appVersion)
            put("version_code", versionCode)
            put("form_factor", formFactor)
            put("screen_width_bucket", screenWidthBucket)
            put("scoreboard_state", scoreboardState.toJson())
            put("settings_snapshot", settingsSnapshot.toJson())
            if (matchContext != null) {
                put("match_context", matchContext.toJson())
            }
            val breadcrumbsArray = JSONArray()
            breadcrumbs.forEach { breadcrumbsArray.put(it.toJson()) }
            put("breadcrumbs", breadcrumbsArray)
        }

        fun toFormattedMarkdown(): String {
            val sb = StringBuilder()
            sb.appendLine("### Diagnostic Snapshot")
            sb.appendLine("- **App Version**: $appVersion (versionCode: $versionCode)")
            sb.appendLine("- **Form Factor**: $formFactor (bucket: $screenWidthBucket)")
            sb.appendLine(
                "- **Scoreboard State**: TourFilter=${scoreboardState.tourFilter}, " +
                        "MatchType=${scoreboardState.matchTypeFilter}, Offline=${scoreboardState.isOffline}, " +
                        "Matches(Total=${scoreboardState.totalMatches}, Filtered=${scoreboardState.filteredMatches}, Live=${scoreboardState.liveMatches})"
            )
            sb.appendLine(
                "- **Settings**: AutoRefresh=${settingsSnapshot.autoRefreshInterval}, " +
                        "Retention=${settingsSnapshot.matchRetention}, WidgetSync=${settingsSnapshot.widgetSyncMode}, " +
                        "Telemetry=${settingsSnapshot.telemetryEnabled}"
            )
            if (matchContext != null) {
                sb.appendLine(
                    "- **Contextual Match**: [${matchContext.matchId}] ${matchContext.tournamentName} - " +
                            "${matchContext.eventGrouping} (${matchContext.effectiveTour} / ${matchContext.effectiveCategory}) [${matchContext.state}]"
                )
            }
            if (breadcrumbs.isNotEmpty()) {
                sb.appendLine("- **Recent Breadcrumbs** (${breadcrumbs.size} events):")
                breadcrumbs.takeLast(5).forEach { b ->
                    sb.appendLine("  - `[${b.tag}]` ${b.message}")
                }
            }
            return sb.toString().trimEnd()
        }

        fun toFullIssueBody(userReport: BugReport): String {
            val scrubbedSummary = PiiScrubber.scrub(userReport.summary)
            val scrubbedDesc = PiiScrubber.scrub(userReport.description)
            val scrubbedSteps = PiiScrubber.scrub(userReport.reproductionSteps ?: "None provided")

            val jsonString = toJson().toString(2)

            return buildString {
                appendLine("## Bug Report: $scrubbedSummary")
                appendLine()
                appendLine("**Category**: ${userReport.category.displayName} (`${userReport.category.slug}`)")
                appendLine("**Reported**: ${Instant.ofEpochMilli(userReport.timestamp)}")
                appendLine()
                appendLine("### Description")
                appendLine(scrubbedDesc)
                appendLine()
                appendLine("### Steps to Reproduce")
                appendLine(scrubbedSteps)
                appendLine()
                appendLine(toFormattedMarkdown())
                appendLine()
                appendLine("<details>")
                appendLine("<summary>Machine-Readable Diagnostic Bundle</summary>")
                appendLine()
                appendLine(JSON_MARKER_START)
                appendLine(jsonString)
                appendLine(JSON_MARKER_END)
                appendLine()
                appendLine("</details>")
            }
        }
    }

    // =========================================================================
    // 5. GitHub Issue URL & Fallback Builder
    // =========================================================================

    object GitHubIssueUrlBuilder {
        private const val BASE_URL = "https://github.com/$GITHUB_REPO/issues/new"
        const val SAFE_MAX_URL_LENGTH = 3500

        fun buildNewIssueUrl(
            title: String,
            body: String,
            labels: List<String>,
            baseUrl: String = BASE_URL
        ): String {
            val encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            val encodedBody = URLEncoder.encode(body, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            val encodedLabels = URLEncoder.encode(labels.joinToString(","), StandardCharsets.UTF_8.toString()).replace("+", "%20")

            return "$baseUrl?title=$encodedTitle&body=$encodedBody&labels=$encodedLabels"
        }

        data class FallbackResult(
            val url: String,
            val fullMarkdownBody: String,
            val wasTruncated: Boolean,
            val clipboardInstructions: String
        )

        fun buildSafeFallbackUrl(
            report: BugReport,
            snapshot: DiagnosticSnapshot,
            maxUrlLength: Int = SAFE_MAX_URL_LENGTH
        ): FallbackResult {
            val title = "[In-App Report] ${PiiScrubber.scrub(report.summary)}"
            val fullBody = snapshot.toFullIssueBody(report)
            val labels = listOf("bug", "in-app-report", report.category.slug)

            val fullUrl = buildNewIssueUrl(title, fullBody, labels)
            if (fullUrl.length <= maxUrlLength) {
                return FallbackResult(
                    url = fullUrl,
                    fullMarkdownBody = fullBody,
                    wasTruncated = false,
                    clipboardInstructions = "Issue details pre-filled in your browser."
                )
            }

            // Truncate body for URL while providing clipboard notice
            val descSnippet = PiiScrubber.scrub(report.description).let {
                if (it.length > 100) it.take(100) + "..." else it
            }

            var summaryBody = buildString {
                appendLine("## Bug Report: ${PiiScrubber.scrub(report.summary)}")
                appendLine()
                appendLine("**Category**: ${report.category.displayName} (`${report.category.slug}`)")
                appendLine()
                appendLine("### Description")
                appendLine(descSnippet)
                appendLine()
                appendLine("> 📋 Full diagnostic report has been copied to your clipboard. Please paste it below:")
                appendLine()
                appendLine("```markdown")
                appendLine("[Paste clipboard here]")
                appendLine("```")
            }

            var safeUrl = buildNewIssueUrl(title, summaryBody, labels)
            if (safeUrl.length > maxUrlLength) {
                val minimalBody = "## Bug Report: ${PiiScrubber.scrub(report.summary)}\n\n" +
                        "> 📋 Full diagnostic report copied to clipboard. Please paste below:\n\n" +
                        "```markdown\n[Paste clipboard here]\n```"
                safeUrl = buildNewIssueUrl(title, minimalBody, labels)
            }
            return FallbackResult(
                url = safeUrl,
                fullMarkdownBody = fullBody,
                wasTruncated = true,
                clipboardInstructions = "Full diagnostic report copied to clipboard. Please paste into GitHub issue body."
            )
        }
    }

    // =========================================================================
    // 6. Submission Service & Cloudflare Worker Relay Simulator
    // =========================================================================

    sealed class SubmissionResult {
        data class Success(val issueNumber: Int, val issueUrl: String) : SubmissionResult()
        data class FallbackRequired(
            val fallbackUrl: String,
            val fullMarkdownBody: String,
            val reason: String
        ) : SubmissionResult()
    }

    open class MockWorkerRelay(
        private val rateLimitPerIp: Int = 5,
        var configuredGithubToken: String? = "mock_secret_gh_token"
    ) {
        private val requestCounts = mutableMapOf<String, Int>()

        open fun handleRequest(
            method: String,
            clientIp: String,
            payloadJson: JSONObject?
        ): Pair<Int, JSONObject> {
            if (method == "OPTIONS") {
                return 204 to JSONObject().apply { put("cors", true) }
            }

            if (method != "POST") {
                return 405 to JSONObject().apply { put("error", "Method Not Allowed") }
            }

            // Rate limiting check
            val currentCount = requestCounts.getOrDefault(clientIp, 0)
            if (currentCount >= rateLimitPerIp) {
                return 429 to JSONObject().apply {
                    put("error", "Rate limit exceeded. Maximum $rateLimitPerIp bug reports per hour.")
                }
            }

            // Token check
            if (configuredGithubToken.isNullOrBlank()) {
                return 503 to JSONObject().apply {
                    put("error", "GitHub relay token not configured on worker")
                }
            }

            if (payloadJson == null) {
                return 400 to JSONObject().apply { put("error", "Empty payload") }
            }

            // Schema validation
            val summary = payloadJson.optString("summary")
            val description = payloadJson.optString("description")
            val category = payloadJson.optString("category")
            val diagnosticSnapshot = payloadJson.optJSONObject("diagnostic_snapshot")

            if (summary.isBlank() || description.isBlank() || category.isBlank() || diagnosticSnapshot == null) {
                return 400 to JSONObject().apply {
                    put("error", "Missing required fields: summary, description, category, or diagnostic_snapshot")
                }
            }

            // Valid category check
            val categoryObj = BugReportCategory.fromSlug(category) ?: try {
                BugReportCategory.valueOf(category)
            } catch (e: Exception) {
                null
            }
            if (categoryObj == null) {
                return 400 to JSONObject().apply {
                    put("error", "Invalid category: $category")
                }
            }

            requestCounts[clientIp] = currentCount + 1
            val issueNumber = 100 + requestCounts[clientIp]!!
            return 201 to JSONObject().apply {
                put("success", true)
                put("issue_number", issueNumber)
                put("issue_url", "https://github.com/$GITHUB_REPO/issues/$issueNumber")
            }
        }

        fun resetRateLimits() {
            requestCounts.clear()
        }
    }

    class BugReportSubmissionService(
        private val workerRelay: MockWorkerRelay = MockWorkerRelay()
    ) {
        suspend fun submitReport(
            report: BugReport,
            snapshot: DiagnosticSnapshot,
            endpoint: String? = null,
            customToken: String? = null,
            isOnline: Boolean = true,
            clientIp: String = "127.0.0.1"
        ): SubmissionResult {
            // Check offline state
            if (!isOnline) {
                val fallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot)
                return SubmissionResult.FallbackRequired(
                    fallbackUrl = fallback.url,
                    fullMarkdownBody = fallback.fullMarkdownBody,
                    reason = "Device is offline"
                )
            }

            // If user supplied a custom token in settings, simulate direct GitHub REST submission
            if (!customToken.isNullOrBlank()) {
                val issueNum = 501
                return SubmissionResult.Success(
                    issueNumber = issueNum,
                    issueUrl = "https://github.com/$GITHUB_REPO/issues/$issueNum"
                )
            }

            // Primary submission via Worker relay
            val payload = JSONObject().apply {
                put("category", report.category.slug)
                put("summary", PiiScrubber.scrub(report.summary))
                put("description", PiiScrubber.scrub(report.description))
                report.reproductionSteps?.let { put("reproduction_steps", PiiScrubber.scrub(it)) }
                put("diagnostic_snapshot", snapshot.toJson())
            }

            val (statusCode, responseJson) = workerRelay.handleRequest("POST", clientIp, payload)

            return if (statusCode in 200..204 && responseJson.optBoolean("success")) {
                SubmissionResult.Success(
                    issueNumber = responseJson.getInt("issue_number"),
                    issueUrl = responseJson.getString("issue_url")
                )
            } else {
                val reason = responseJson.optString("error", "HTTP $statusCode from relay")
                val fallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot)
                SubmissionResult.FallbackRequired(
                    fallbackUrl = fallback.url,
                    fullMarkdownBody = fallback.fullMarkdownBody,
                    reason = reason
                )
            }
        }
    }

    // =========================================================================
    // 7. Automated Triage & Correlation Engine (Python script contract replica)
    // =========================================================================

    object TriageEngineHarness {
        private val JSON_BLOCK_PATTERN = Pattern.compile(
            Pattern.quote(JSON_MARKER_START) + "\\s*([\\s\\S]*?)\\s*" + Pattern.quote(JSON_MARKER_END)
        )

        fun extractDiagnosticJson(issueBody: String): JSONObject? {
            val matcher = JSON_BLOCK_PATTERN.matcher(issueBody)
            if (!matcher.find()) return null
            val rawJson = matcher.group(1).trim()
            return try {
                JSONObject(rawJson)
            } catch (e: Exception) {
                null
            }
        }

        fun mapCategoryToSuspectedFiles(categorySlug: String): List<String> = when (categorySlug.lowercase()) {
            "filter-tour" -> listOf(
                "app/src/main/java/com/tennis/tracker/data/TournamentFiltering.kt",
                "app/src/main/java/com/tennis/tracker/ui/viewmodel/TourTierFilter.kt",
                "app/src/main/java/com/tennis/tracker/domain/model/TournamentResolver.kt",
                "app/src/main/java/com/tennis/tracker/ui/viewmodel/ScoreboardViewModel.kt"
            )
            "score-live-data" -> listOf(
                "app/src/main/java/com/tennis/tracker/data/remote/EspnScoreboardApi.kt",
                "app/src/main/java/com/tennis/tracker/data/repository/ScoreboardRepositoryImpl.kt",
                "app/src/main/java/com/tennis/tracker/domain/model/Match.kt",
                "app/src/main/java/com/tennis/tracker/domain/model/GameScore.kt"
            )
            "watch-highlights" -> listOf(
                "app/src/main/java/com/tennis/tracker/data/remote/EspnWatchMatcher.kt",
                "app/src/main/java/com/tennis/tracker/data/remote/YouTubeHighlightResolver.kt",
                "app/src/main/java/com/tennis/tracker/data/remote/EspnWatchApi.kt"
            )
            "home-widget" -> listOf(
                "app/src/main/java/com/tennis/tracker/domain/WidgetAutoSyncManager.kt",
                "app/src/main/java/com/tennis/tracker/data/WidgetSyncWorker.kt",
                "app/src/main/java/com/tennis/tracker/ui/widget/TennisWidgetProvider.kt"
            )
            "ui-layout" -> listOf(
                "app/src/main/java/com/tennis/tracker/ui/screen/ScoreboardScreen.kt",
                "app/src/main/java/com/tennis/tracker/ui/screen/MatchCopyBottomSheet.kt",
                "app/src/main/java/com/tennis/tracker/ui/bugreport/BugReportBottomSheet.kt"
            )
            else -> listOf(
                "app/src/main/java/com/tennis/tracker/TennisApp.kt",
                "app/src/main/java/com/tennis/tracker/MainActivity.kt"
            )
        }

        data class TriageReport(
            val diagnosticSummary: String,
            val suspectedFiles: List<String>,
            val relatedCommits: List<String>,
            val rootCauseAnalysis: String,
            val proposedPatch: String
        ) {
            fun toMarkdownComment(): String = buildString {
                appendLine("## 🤖 Automated Bug Triage & Correlation Report")
                appendLine()
                appendLine("### 1. Diagnostic Summary")
                appendLine(diagnosticSummary)
                appendLine()
                appendLine("### 2. Suspected Files & Symbols")
                suspectedFiles.forEach { appendLine("- `$it`") }
                appendLine()
                appendLine("### 3. Related Commits / Issues")
                relatedCommits.forEach { appendLine("- $it") }
                appendLine()
                appendLine("### 4. Root-Cause Analysis")
                appendLine(rootCauseAnalysis)
                appendLine()
                appendLine("### 5. Proposed Patch / Verification Steps")
                appendLine(proposedPatch)
            }
        }

        fun triageIssue(
            issueTitle: String,
            issueBody: String,
            categorySlug: String
        ): TriageReport {
            val json = extractDiagnosticJson(issueBody)
            val suspectedFiles = mapCategoryToSuspectedFiles(categorySlug)

            val appVersion = json?.optString("app_version", "unknown") ?: "unknown"
            val formFactor = json?.optString("form_factor", "PHONE") ?: "PHONE"
            val scoreboardState = json?.optJSONObject("scoreboard_state")
            val tourFilter = scoreboardState?.optString("tour_filter", "ALL") ?: "ALL"
            val matchContext = json?.optJSONObject("match_context")
            val matchId = matchContext?.optString("match_id", "none") ?: "none"

            val summary = "Reported on version `$appVersion` ($formFactor). Active tour filter: `$tourFilter`. Contextual match ID: `$matchId`."
            val commits = listOf(
                "feat(bugreport): in-app reporting and diagnostic snapshot collector",
                "fix(filter): multi-tournament tour tier filter predicate resolution"
            )
            val rootCause = "Heuristic analysis indicates issue in category `$categorySlug` affecting component `${suspectedFiles.firstOrNull() ?: "core"}`."
            val patch = "```diff\n--- a/${suspectedFiles.firstOrNull() ?: "app/src/main/java/com/tennis/tracker/MainActivity.kt"}\n+++ b/${suspectedFiles.firstOrNull() ?: "app/src/main/java/com/tennis/tracker/MainActivity.kt"}\n@@ -1,3 +1,3 @@\n+// Verification patch candidate\n```"

            return TriageReport(
                diagnosticSummary = summary,
                suspectedFiles = suspectedFiles,
                relatedCommits = commits,
                rootCauseAnalysis = rootCause,
                proposedPatch = patch
            )
        }
    }
}
