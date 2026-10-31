package com.tennis.tracker.domain.model.bugreport

import com.tennis.tracker.data.diagnostic.PiiScrubber

/**
 * Domain model representing user-submitted bug report content.
 */
data class BugReport(
    val category: BugReportCategory = BugReportCategory.OTHER,
    val summary: String = "",
    val description: String = "",
    val reproductionSteps: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Checks if minimum required user content is populated.
     */
    val isValid: Boolean
        get() = summary.trim().isNotBlank() && description.trim().isNotBlank()

    /**
     * Formats standardized GitHub issue title with zero-PII sanitization applied to user summary.
     */
    val formattedTitle: String
        get() = "[${category.displayName}] ${PiiScrubber.scrub(summary.trim())}"

    /**
     * Explicit zero-PII sanitized issue title for external API and URL submission.
     */
    val scrubbedTitle: String
        get() = formattedTitle

    /**
     * Normalized optional reproduction steps.
     */
    val cleanedReproductionSteps: String?
        get() = reproductionSteps?.trim()?.takeIf { it.isNotBlank() }
}
