package com.tennis.tracker.data.bugreport

import com.tennis.tracker.data.diagnostic.PiiScrubber
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds pre-filled GitHub issue creation URLs with strict query length constraints (<= 2000 chars)
 * and RFC 3986 percent-encoding to ensure safe launching on mobile browsers.
 */
object GitHubIssueUrlBuilder {

    const val GITHUB_REPO = "yurilo/tennis_tracker"
    const val BASE_URL = "https://github.com/$GITHUB_REPO/issues/new"
    const val MAX_SAFE_URL_LENGTH = 2000

    data class FallbackResult(
        val url: String,
        val fullMarkdownBody: String,
        val wasTruncated: Boolean,
        val clipboardInstructions: String
    )

    /**
     * Slices string up to [count] characters without splitting UTF-16 surrogate pairs.
     */
    fun safeTake(text: String, count: Int): String {
        if (text.length <= count) return text
        val cut = if (count > 0 && Character.isHighSurrogate(text[count - 1])) count - 1 else count
        return text.take(cut)
    }

    /**
     * Safely clamps [text] so that its RFC 3986 percent-encoded representation consumes at most
     * [maxEncodedLength] characters, taking care never to split UTF-16 surrogate pairs.
     */
    fun clampToEncodedBudget(text: String, maxEncodedLength: Int): String {
        if (maxEncodedLength <= 0) return ""
        if (encodeParam(text).length <= maxEncodedLength) return text

        val reserveEllipsis = maxEncodedLength >= 4
        val targetBudget = if (reserveEllipsis) maxEncodedLength - 3 else maxEncodedLength

        var low = 0
        var high = text.length
        var best = ""

        while (low <= high) {
            val mid = (low + high) / 2
            var candidateLen = mid
            if (candidateLen in 1 until text.length && Character.isHighSurrogate(text[candidateLen - 1])) {
                candidateLen--
            }
            val candidate = text.take(candidateLen)
            if (encodeParam(candidate).length <= targetBudget) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return if (reserveEllipsis && best.isNotEmpty() && best.length < text.length) {
            best + "..."
        } else {
            best
        }
    }

    fun buildNewIssueUrl(
        title: String,
        body: String,
        labels: List<String>,
        baseUrl: String = BASE_URL
    ): String {
        val encodedTitle = encodeParam(title)
        val encodedBody = encodeParam(body)
        val encodedLabels = encodeParam(labels.joinToString(","))
        return "$baseUrl?title=$encodedTitle&body=$encodedBody&labels=$encodedLabels"
    }

    fun buildSafeFallbackUrl(
        report: BugReport,
        snapshot: DiagnosticSnapshot,
        maxUrlLength: Int = MAX_SAFE_URL_LENGTH,
        baseUrl: String = BASE_URL
    ): FallbackResult {
        val scrubbedSummary = PiiScrubber.scrub(report.summary.trim())
        val title = "[${report.category.displayName}] $scrubbedSummary"
        val fullBody = snapshot.toFullIssueBody(report)
        val labels = listOf("bug", "in-app-report", report.category.labelSlug)

        val fullUrl = buildNewIssueUrl(title, fullBody, labels, baseUrl)
        if (fullUrl.length <= maxUrlLength) {
            return FallbackResult(
                url = fullUrl,
                fullMarkdownBody = fullBody,
                wasTruncated = false,
                clipboardInstructions = "Issue details pre-filled in your browser."
            )
        }

        // URL exceeds mobile limit; construct compact body with clipboard paste notice
        val scrubbedDesc = PiiScrubber.scrub(report.description.trim())
        val descSnippet = if (scrubbedDesc.length > 200) {
            scrubbedDesc.take(200) + "... [truncated; full text in clipboard]"
        } else {
            scrubbedDesc
        }

        val compactBody = buildString {
            append("## Bug Report: ").append(scrubbedSummary).append("\n\n")
            append("**Category**: ").append(report.category.displayName)
                .append(" (`").append(report.category.labelSlug).append("`)\n\n")
            append("### Description\n").append(descSnippet).append("\n\n")
            append("> 📋 **Notice:** Full diagnostic report was copied to your clipboard.\n")
            append("> Please paste it below into the issue body before submitting:\n\n")
            append("```markdown\n[Paste full diagnostic bundle from clipboard here]\n```\n\n")
            append("---\n")
            append("*Environment: App v").append(snapshot.appVersion)
                .append(" (").append(snapshot.versionCode).append("), ")
                .append(snapshot.formFactor).append(", ")
                .append(snapshot.screenWidthBucket).append("*")
        }

        var safeUrl = buildNewIssueUrl(title, compactBody, labels, baseUrl)
        if (safeUrl.length > maxUrlLength) {
            val minimalBody = "## Bug Report: $scrubbedSummary\n\n" +
                "> 📋 **Notice:** Full diagnostic report copied to clipboard. Please paste below:\n\n" +
                "```markdown\n[Paste clipboard here]\n```"
            safeUrl = buildNewIssueUrl(title, minimalBody, labels, baseUrl)
        }
        if (safeUrl.length > maxUrlLength) {
            // Absolute hard guarantee: truncate title if needed to ensure strictly <= maxUrlLength
            val clampedTitle = if (title.length > 60) safeTake(title, 57) + "..." else title
            val notice = "> 📋 Full report copied to clipboard. Please paste below."
            safeUrl = buildNewIssueUrl(clampedTitle, notice, labels, baseUrl)
        }
        if (safeUrl.length > maxUrlLength) {
            // Progressive dynamic degradation and surrogate-safe clamping for tight custom maxUrlLength
            val candidateBodies = listOf(
                "> 📋 Full report copied to clipboard. Please paste below.",
                "> Full report in clipboard.",
                ""
            )
            val candidateLabels = listOf(
                labels,
                emptyList()
            )

            var resolvedUrl: String? = null

            for (activeLabels in candidateLabels) {
                for (body in candidateBodies) {
                    val urlWithoutTitle = buildNewIssueUrl("", body, activeLabels, baseUrl)
                    if (urlWithoutTitle.length <= maxUrlLength) {
                        val remainingTitleBudget = maxUrlLength - urlWithoutTitle.length
                        val clampedTitle = clampToEncodedBudget(title, remainingTitleBudget)
                        val candidateUrl = buildNewIssueUrl(clampedTitle, body, activeLabels, baseUrl)
                        if (candidateUrl.length <= maxUrlLength) {
                            resolvedUrl = candidateUrl
                            break
                        }
                    }
                }
                if (resolvedUrl != null) break
            }

            safeUrl = resolvedUrl ?: baseUrl.take(maxUrlLength)
        }

        return FallbackResult(
            url = safeUrl,
            fullMarkdownBody = fullBody,
            wasTruncated = true,
            clipboardInstructions = "Full diagnostic report copied to clipboard. Please paste into GitHub issue body."
        )
    }

    fun encodeParam(param: String): String {
        return URLEncoder.encode(param, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }
}
