package com.usopen.tracker.domain.model

import com.usopen.tracker.ui.viewmodel.TourTierFilter

/**
 * Preferred tournament tier filter applied when the app launches.
 */
enum class StartupTournamentTier(
    val displayName: String,
    val tourTierFilter: TourTierFilter
) {
    ALL(
        displayName = "All Tournaments",
        tourTierFilter = TourTierFilter.ALL
    ),
    GRAND_SLAMS(
        displayName = "Grand Slams",
        tourTierFilter = TourTierFilter.GRAND_SLAMS
    ),
    ATP_TOUR(
        displayName = "ATP Tour",
        tourTierFilter = TourTierFilter.ATP_TOUR
    ),
    WTA_TOUR(
        displayName = "WTA Tour",
        tourTierFilter = TourTierFilter.WTA_TOUR
    );

    companion object {
        val DEFAULT: StartupTournamentTier = ALL

        fun fromNameOrDefault(name: String?): StartupTournamentTier {
            if (name == null) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: IllegalArgumentException) {
                DEFAULT
            }
        }

        fun fromTourTierFilter(filter: TourTierFilter): StartupTournamentTier {
            return entries.firstOrNull { it.tourTierFilter == filter } ?: ALL
        }
    }
}
