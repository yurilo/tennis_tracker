package com.tennis.tracker.ui.viewmodel

import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.TourType

/**
 * Filter level for slicing tournament draws by tour gender (ATP / WTA) or format (Singles / Doubles).
 * Especially useful in combined events (Grand Slams, Indian Wells, Miami, Madrid, Rome, etc.)
 * or when browsing across all tournaments.
 */
enum class MatchTypeFilter(
    val displayName: String,
    val icon: String
) {
    ALL("All Types", "🎾"),
    ATP("ATP", "🔷"),
    WTA("WTA", "🟣"),
    SINGLES("Singles", "👤"),
    DOUBLES("Doubles", "👥");

    fun matches(match: Match): Boolean {
        val grouping = match.eventGrouping.lowercase()
        val isDoublesMatch = match.player1.isDoubles ||
            match.player2.isDoubles ||
            grouping.contains("doubles") ||
            grouping.contains("mixed")
        val isWomen = grouping.contains("women") || grouping.contains("wta")
        val isMen = (grouping.contains("men") && !isWomen) || grouping.contains("atp")
        val isMixed = grouping.contains("mixed")

        return when (this) {
            ALL -> true
            ATP -> !isMixed && (isMen || match.effectiveTour == TourType.ATP || (match.effectiveTour == TourType.COMBINED && !isWomen))
            WTA -> !isMixed && (isWomen || match.effectiveTour == TourType.WTA)
            SINGLES -> !isDoublesMatch
            DOUBLES -> isDoublesMatch
        }
    }
}
