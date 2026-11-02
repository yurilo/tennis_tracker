package com.tennis.tracker.ui.viewmodel

import com.tennis.tracker.domain.model.Match

/**
 * Filter level for slicing tournament draws by match format (All Types / Singles / Doubles).
 * Works orthogonally with [TourTierFilter] (which handles All Tours, Grand Slams, ATP Tour, WTA Tour, 1000s, 500s/250s).
 */
enum class MatchTypeFilter(
    val displayName: String,
    val icon: String
) {
    ALL("All Types", "🎾"),
    SINGLES("Singles", "👤"),
    DOUBLES("Doubles", "👥");

    fun matches(match: Match): Boolean {
        val grouping = match.eventGrouping.lowercase()
        val isDoublesMatch = match.player1.isDoubles ||
            match.player2.isDoubles ||
            grouping.contains("doubles") ||
            grouping.contains("mixed")

        return when (this) {
            ALL -> true
            SINGLES -> !isDoublesMatch
            DOUBLES -> isDoublesMatch
        }
    }
}
