package com.tennis.tracker.data.diagnostic

import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import org.json.JSONObject

/**
 * Builds formatted human-readable Markdown and machine-parseable JSON bundles
 * for GitHub issues, strictly bounded by [BUGREPORT_JSON_START] and [BUGREPORT_JSON_END].
 */
object BugReportPayloadFormatter {

    const val JSON_START_DELIMITER = "<!-- BUGREPORT_JSON_START -->"
    const val JSON_END_DELIMITER = "<!-- BUGREPORT_JSON_END -->"

    private val DELIMITER_SANITIZE_REGEX = Regex("<!--\\s*BUGREPORT_JSON", RegexOption.IGNORE_CASE)

    internal fun sanitizeDelimiters(input: String): String {
        return input.replace(DELIMITER_SANITIZE_REGEX, "&lt;!-- BUGREPORT_JSON")
    }

    /**
     * Formats full GitHub issue body with user content, diagnostic tables,
     * and delimited JSON block.
     */
    fun formatFullIssueBody(
        userReport: BugReport,
        snapshot: DiagnosticSnapshot
    ): String {
        val cleanSummary = sanitizeDelimiters(PiiScrubber.scrub(userReport.summary.trim()))
        val cleanDescription = sanitizeDelimiters(PiiScrubber.scrub(userReport.description.trim()))
        val cleanSteps = userReport.cleanedReproductionSteps?.let { sanitizeDelimiters(PiiScrubber.scrub(it)) }

        val sb = StringBuilder()
        sb.append("## User Bug Report\n\n")
        sb.append("**Category:** `").append(userReport.category.displayName).append("`\n")
        sb.append("**Summary:** ").append(cleanSummary).append("\n\n")

        sb.append("### Description\n")
        sb.append(cleanDescription).append("\n\n")

        sb.append("### Steps to Reproduce\n")
        if (!cleanSteps.isNullOrBlank()) {
            sb.append(cleanSteps).append("\n\n")
        } else {
            sb.append("None provided\n\n")
        }

        sb.append("---\n\n")
        sb.append(formatMarkdownSummary(snapshot)).append("\n\n")

        sb.append("<details>\n")
        sb.append("<summary>Diagnostic Bugreport Bundle (Machine-Readable JSON)</summary>\n\n")
        sb.append(JSON_START_DELIMITER).append("\n")
        sb.append(formatJsonSnapshot(snapshot)).append("\n")
        sb.append(JSON_END_DELIMITER).append("\n")
        sb.append("</details>\n")

        return sb.toString()
    }

    /**
     * Formats the human-readable Markdown diagnostic tables and breadcrumb list.
     */
    fun formatMarkdownSummary(snapshot: DiagnosticSnapshot): String {
        val sb = StringBuilder()
        sb.append("### Diagnostic Environment\n")
        sb.append("| Parameter | Value |\n")
        sb.append("|---|---|\n")
        sb.append("| **App Version** | `").append(escapeMarkdown(snapshot.appVersion))
            .append(" (").append(snapshot.versionCode).append(")` |\n")
        sb.append("| **Form Factor** | `").append(snapshot.formFactor)
            .append("` (`").append(snapshot.screenWidthBucket).append("`) |\n")
        sb.append("| **Tour Filter** | `").append(escapeMarkdown(snapshot.scoreboardState.tourFilter)).append("` |\n")
        sb.append("| **Tournament** | `").append(escapeMarkdown(snapshot.scoreboardState.currentTournament)).append("` |\n")
        sb.append("| **Match Type Filter** | `").append(escapeMarkdown(snapshot.scoreboardState.matchTypeFilter)).append("` |\n")
        sb.append("| **Match Filter** | `").append(escapeMarkdown(snapshot.scoreboardState.matchFilter)).append("` |\n")
        sb.append("| **Offline Mode** | `").append(if (snapshot.scoreboardState.isOffline) "YES" else "NO").append("` |\n")
        sb.append("| **Match Counts** | Total: ").append(snapshot.scoreboardState.totalMatches)
            .append(", Filtered: ").append(snapshot.scoreboardState.filteredMatches)
            .append(", Live: ").append(snapshot.scoreboardState.liveMatches)
            .append(", Followed: ").append(snapshot.scoreboardState.followedMatches).append(" |\n")
        sb.append("| **Auto-Refresh** | `").append(escapeMarkdown(snapshot.settingsSnapshot.autoRefreshInterval)).append("` |\n")
        sb.append("| **Widget Sync** | `").append(escapeMarkdown(snapshot.settingsSnapshot.widgetSyncMode)).append("` |\n")

        val match = snapshot.matchContext
        if (match != null) {
            sb.append("\n### Contextual Match Details\n")
            sb.append("- **Match ID:** `").append(escapeMarkdown(match.matchId)).append("`\n")
            sb.append("- **Tournament:** ").append(escapeMarkdown(match.tournamentName))
            if (!match.tournamentId.isNullOrBlank()) {
                sb.append(" (`").append(escapeMarkdown(match.tournamentId)).append("`)")
            }
            sb.append("\n")
            sb.append("- **Event Grouping:** ").append(escapeMarkdown(match.eventGrouping)).append("\n")
            sb.append("- **Tour / Category:** `").append(escapeMarkdown(match.effectiveTour))
                .append("` / `").append(escapeMarkdown(match.effectiveCategory)).append("`\n")
            sb.append("- **State / Status:** `").append(escapeMarkdown(match.state)).append("`")
            if (!match.statusDetail.isNullOrBlank()) {
                sb.append(" (").append(escapeMarkdown(match.statusDetail)).append(")")
            }
            sb.append("\n")
            if (!match.court.isNullOrBlank()) {
                sb.append("- **Court:** ").append(escapeMarkdown(match.court)).append("\n")
            } else if (!match.venue.isNullOrBlank()) {
                sb.append("- **Venue:** ").append(escapeMarkdown(match.venue)).append("\n")
            }
        }

        if (snapshot.breadcrumbs.isNotEmpty()) {
            sb.append("\n### Recent Diagnostic Events (Last ").append(snapshot.breadcrumbs.size).append(")\n")
            for (crumb in snapshot.breadcrumbs) {
                sb.append("- `").append(crumb.isoTimestamp).append("` ")
                sb.append("**[").append(escapeMarkdown(crumb.tag)).append("]** ")
                sb.append(escapeMarkdown(crumb.message)).append("\n")
            }
        }

        return sb.toString()
    }

    /**
     * Serializes [DiagnosticSnapshot] to formatted JSON string.
     */
    fun formatJsonSnapshot(snapshot: DiagnosticSnapshot, indentSpaces: Int = 2): String {
        return snapshot.toJson().toString(indentSpaces)
    }

    /**
     * Extracts and parses the embedded [JSONObject] from a GitHub issue body.
     */
    fun extractJsonFromIssueBody(body: String): JSONObject? {
        val startIndex = body.lastIndexOf(JSON_START_DELIMITER)
        if (startIndex == -1) return null
        val contentStart = startIndex + JSON_START_DELIMITER.length

        val endIndex = body.indexOf(JSON_END_DELIMITER, contentStart)
        if (endIndex == -1) return null

        val jsonString = body.substring(contentStart, endIndex).trim()
        return runCatching { JSONObject(jsonString) }.getOrNull()
    }

    private fun escapeMarkdown(value: String): String {
        return value.replace("|", "\\|")
    }
}
