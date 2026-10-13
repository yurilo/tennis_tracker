package com.tennis.tracker.ui.viewmodel

import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.TourType

/**
 * Filter enum representing professional tennis tour circuits and tournament tier levels.
 */
enum class TourTierFilter(val displayName: String, val icon: String) {
    ALL("All", "🎾"),
    GRAND_SLAMS("Grand Slams", "🏆"),
    ATP_TOUR("ATP Tour", "🔷"),
    WTA_TOUR("WTA Tour", "🟣"),
    MASTERS_1000("1000s", "⭐"),
    TIER_500_250("500s/250s", "⚡");

    /**
     * Evaluates whether a tournament and/or match satisfies this tour tier filter.
     *
     * @param tournament The tournament to test (optional if match provided).
     * @param match The specific match to test (optional if tournament provided).
     * @return true if the entity satisfies the filter criteria.
     */
    fun matches(tournament: Tournament?, match: Match? = null): Boolean {
        if (this == ALL) return true
        val cat = match?.effectiveCategory ?: tournament?.category ?: TournamentCategory.ATP_250
        val tour = match?.effectiveTour ?: tournament?.tour ?: TourType.COMBINED
        val isGs = tournament?.isGrandSlam == true || match?.tournament?.isGrandSlam == true || cat.isGrandSlam

        val isMenGrouping = match?.eventGrouping?.let {
            it.contains("men", ignoreCase = true) && !it.contains("women", ignoreCase = true)
        } ?: false
        val isWomenGrouping = match?.eventGrouping?.contains("women", ignoreCase = true) == true

        return when (this) {
            ALL -> true
            GRAND_SLAMS -> isGs
            ATP_TOUR -> if (match != null) (match.effectiveTour == TourType.ATP || (match.effectiveTour == TourType.COMBINED && !isWomenGrouping) || isMenGrouping) else tour.isAtp
            WTA_TOUR -> if (match != null) (match.effectiveTour == TourType.WTA || (match.effectiveTour == TourType.COMBINED && !isMenGrouping) || isWomenGrouping) else tour.isWta
            MASTERS_1000 -> cat.is1000
            TIER_500_250 -> cat.is500Or250
        }
    }

    /**
     * Convenience overload evaluating a match directly.
     */
    fun matches(match: Match): Boolean = matches(match.tournament, match)
}
