package com.tennis.tracker.domain.model.bugreport

/**
 * Standard issue categories for in-app bug reports mapped to GitHub issue label slugs.
 */
enum class BugReportCategory(
    val displayName: String,
    val labelSlug: String,
    val icon: String,
    val description: String
) {
    FILTER_TOUR_MISMATCH(
        displayName = "Filter / Tour Mismatch",
        labelSlug = "filter-tour",
        icon = "⚡",
        description = "Tournament, tour tier, or category filter discrepancies"
    ),
    SCORE_LIVE_DATA(
        displayName = "Score / Live Data",
        labelSlug = "score-live-data",
        icon = "🎾",
        description = "Outdated or incorrect scores, set status, or live counts"
    ),
    WATCH_HIGHLIGHTS(
        displayName = "Watch / Highlights",
        labelSlug = "watch-highlights",
        icon = "📺",
        description = "ESPN or YouTube highlight deep links failing or misdirected"
    ),
    HOME_WIDGET(
        displayName = "Home Widget",
        labelSlug = "home-widget",
        icon = "📱",
        description = "Home screen widget sync, rendering, or interaction issues"
    ),
    UI_LAYOUT(
        displayName = "UI / Layout",
        labelSlug = "ui-layout",
        icon = "📐",
        description = "Responsive layout, font scaling, or display cutoff issues"
    ),
    OTHER(
        displayName = "Other",
        labelSlug = "other",
        icon = "❓",
        description = "General bugs or unclassified behavior"
    );

    companion object {
        fun fromLabelSlug(slug: String?): BugReportCategory {
            if (slug.isNullOrBlank()) return OTHER
            val normalized = slug.trim().lowercase()
            return entries.firstOrNull {
                it.labelSlug.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: OTHER
        }

        fun fromDisplayName(name: String?): BugReportCategory {
            if (name.isNullOrBlank()) return OTHER
            val trimmed = name.trim()
            return entries.firstOrNull {
                it.displayName.equals(trimmed, ignoreCase = true) ||
                    it.name.equals(trimmed, ignoreCase = true)
            } ?: OTHER
        }
    }
}
