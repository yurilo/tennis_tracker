package com.usopen.tracker.domain.model

/**
 * Domain abstraction representing a tennis tournament.
 */
data class Tournament(
    val id: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
    val defaultVenue: String = "Arthur Ashe Stadium",
    val isGrandSlam: Boolean = false
) {
    /**
     * Checks whether an event or competition tournament string matches this tournament.
     * Matches against displayName and known aliases (case-insensitive, trimmed).
     */
    fun matches(rawTournamentName: String?): Boolean {
        if (rawTournamentName.isNullOrBlank()) return false
        val clean = rawTournamentName.trim().lowercase()
        val displayClean = displayName.lowercase()
        if (clean == displayClean || clean.contains(displayClean) || displayClean.contains(clean)) {
            return true
        }
        return aliases.any { alias ->
            val aliasClean = alias.trim().lowercase()
            clean == aliasClean || clean.contains(aliasClean) || aliasClean.contains(clean)
        }
    }
}

/**
 * Standard tournament definitions and registry.
 */
object Tournaments {
    val US_OPEN = Tournament(
        id = "us_open",
        displayName = "US Open",
        aliases = setOf("US Open", "U.S. Open", "United States Open", "US Open Tennis"),
        defaultVenue = "Arthur Ashe Stadium",
        isGrandSlam = true
    )

    val AUSTRALIAN_OPEN = Tournament(
        id = "australian_open",
        displayName = "Australian Open",
        aliases = setOf("Australian Open", "Aus Open"),
        defaultVenue = "Rod Laver Arena",
        isGrandSlam = true
    )

    val ROLAND_GARROS = Tournament(
        id = "roland_garros",
        displayName = "Roland Garros",
        aliases = setOf("Roland Garros", "French Open", "Roland-Garros"),
        defaultVenue = "Court Philippe-Chatrier",
        isGrandSlam = true
    )

    val WIMBLEDON = Tournament(
        id = "wimbledon",
        displayName = "Wimbledon",
        aliases = setOf("Wimbledon", "The Championships, Wimbledon"),
        defaultVenue = "Centre Court",
        isGrandSlam = true
    )

    val GRAND_SLAMS: List<Tournament> = listOf(
        US_OPEN,
        AUSTRALIAN_OPEN,
        ROLAND_GARROS,
        WIMBLEDON
    )

    /**
     * Finds a tournament by ID, defaulting to US Open if not found.
     */
    fun fromId(id: String): Tournament {
        return GRAND_SLAMS.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: US_OPEN
    }
}
