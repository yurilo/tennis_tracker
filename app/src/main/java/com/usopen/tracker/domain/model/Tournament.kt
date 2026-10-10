package com.usopen.tracker.domain.model

/**
 * Domain abstraction representing a tennis tournament.
 */
data class Tournament(
    val id: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
    val defaultVenue: String = "Center Court",
    val isGrandSlam: Boolean = false
) {
    /**
     * Checks whether an event or competition tournament string matches this tournament.
     * Matches against displayName and known aliases (case-insensitive, trimmed).
     * If tournament is 'ALL', matches any non-empty tournament.
     */
    fun matches(rawTournamentName: String?): Boolean {
        if (rawTournamentName.isNullOrBlank()) return false
        if (this.id.equals("all", ignoreCase = true)) return true
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
    val ALL = Tournament(
        id = "all",
        displayName = "All Tournaments",
        aliases = setOf("All", "Any"),
        defaultVenue = "Center Court",
        isGrandSlam = false
    )

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

    val INDIAN_WELLS = Tournament(
        id = "indian_wells",
        displayName = "Indian Wells",
        aliases = setOf("Indian Wells", "BNP Paribas Open"),
        defaultVenue = "Stadium 1",
        isGrandSlam = false
    )

    val MIAMI_OPEN = Tournament(
        id = "miami_open",
        displayName = "Miami Open",
        aliases = setOf("Miami Open", "Miami"),
        defaultVenue = "Stadium Court",
        isGrandSlam = false
    )

    val MADRID_OPEN = Tournament(
        id = "madrid_open",
        displayName = "Madrid Open",
        aliases = setOf("Madrid Open", "Mutua Madrid Open"),
        defaultVenue = "Manolo Santana Stadium",
        isGrandSlam = false
    )

    val ITALIAN_OPEN = Tournament(
        id = "italian_open",
        displayName = "Italian Open",
        aliases = setOf("Italian Open", "Rome", "Internazionali BNL d'Italia"),
        defaultVenue = "Campo Centrale",
        isGrandSlam = false
    )

    val GRAND_SLAMS: List<Tournament> = listOf(
        US_OPEN,
        AUSTRALIAN_OPEN,
        ROLAND_GARROS,
        WIMBLEDON
    )

    val POPULAR_TOURNAMENTS: List<Tournament> = listOf(
        ALL,
        US_OPEN,
        AUSTRALIAN_OPEN,
        ROLAND_GARROS,
        WIMBLEDON,
        INDIAN_WELLS,
        MIAMI_OPEN,
        MADRID_OPEN,
        ITALIAN_OPEN
    )

    /**
     * Finds a tournament by ID, defaulting to ALL if not found.
     */
    fun fromId(id: String): Tournament {
        return POPULAR_TOURNAMENTS.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALL
    }
}
