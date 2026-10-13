package com.tennis.tracker.domain.model

/**
 * Domain abstraction representing official tournament tier categories in ATP and WTA circuits.
 */
enum class TournamentCategory(
    val displayName: String,
    val shortName: String,
    val rankingPoints: Int
) {
    GRAND_SLAM("Grand Slam", "GS", 2000),
    MASTERS_1000("Masters 1000", "1000", 1000),
    WTA_1000("WTA 1000", "1000", 1000),
    ATP_500("ATP 500", "500", 500),
    WTA_500("WTA 500", "500", 500),
    ATP_250("ATP 250", "250", 250),
    WTA_250("WTA 250", "250", 250),
    FINALS("Year-End Finals", "Finals", 1500);

    val isGrandSlam: Boolean
        get() = this == GRAND_SLAM

    val is1000: Boolean
        get() = this == MASTERS_1000 || this == WTA_1000

    val is500: Boolean
        get() = this == ATP_500 || this == WTA_500

    val is250: Boolean
        get() = this == ATP_250 || this == WTA_250

    val is500Or250: Boolean
        get() = is500 || is250

    val isFinals: Boolean
        get() = this == FINALS

    val tourType: TourType
        get() = when (this) {
            GRAND_SLAM, FINALS -> TourType.COMBINED
            MASTERS_1000, ATP_500, ATP_250 -> TourType.ATP
            WTA_1000, WTA_500, WTA_250 -> TourType.WTA
        }

    companion object {
        fun fromString(value: String?): TournamentCategory? {
            if (value.isNullOrBlank()) return null
            val clean = value.trim().uppercase()
            return entries.firstOrNull {
                it.name == clean ||
                    it.displayName.equals(clean, ignoreCase = true) ||
                    it.shortName.equals(clean, ignoreCase = true)
            }
        }
    }
}
