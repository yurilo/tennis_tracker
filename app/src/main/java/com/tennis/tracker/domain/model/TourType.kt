package com.tennis.tracker.domain.model

/**
 * Domain abstraction representing the professional tennis tour organization.
 */
enum class TourType(
    val displayName: String,
    val code: String
) {
    ATP("ATP Tour", "ATP"),
    WTA("WTA Tour", "WTA"),
    COMBINED("Combined", "ATP/WTA");

    val isAtp: Boolean
        get() = this == ATP || this == COMBINED

    val isWta: Boolean
        get() = this == WTA || this == COMBINED

    companion object {
        fun fromCode(code: String?): TourType {
            if (code == null) return COMBINED
            return when (code.trim().uppercase()) {
                "ATP" -> ATP
                "WTA" -> WTA
                "COMBINED", "ATP/WTA", "JOINT" -> COMBINED
                else -> COMBINED
            }
        }
    }
}
