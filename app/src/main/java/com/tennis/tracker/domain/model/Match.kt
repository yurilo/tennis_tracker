package com.tennis.tracker.domain.model

enum class MatchState {
    LIVE,
    UPCOMING,
    FINISHED
}

data class Match(
    val id: String,
    val tournamentName: String = "Tennis",
    val eventGrouping: String = "Men's Singles", // e.g. Men's Singles, Women's Singles
    val roundName: String = "",
    val state: MatchState,
    val statusDetail: String, // e.g. "Final", "In Progress - Set 3", "Scheduled 7:00 PM EDT"
    val player1: Competitor,
    val player2: Competitor,
    val venue: String = "Center Court",
    val court: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val durationMinutes: Int? = null,
    val espnEventId: String? = null,
    val espnWatchId: String? = null,
    val deepLink: DeepLink? = null,
    val youtubeVideoId: String? = null,
    val youtubeDeepLink: DeepLink? = null,
    val tournament: Tournament? = null,
    val tour: TourType? = null,
    val category: TournamentCategory? = null,
    val surface: CourtSurface? = null
) {
    /**
     * Secondary constructor preserving 100% binary backward compatibility with pre-compiled modules.
     */
    constructor(
        id: String,
        tournamentName: String = "Tennis",
        eventGrouping: String = "Men's Singles",
        roundName: String = "",
        state: MatchState,
        statusDetail: String,
        player1: Competitor,
        player2: Competitor,
        venue: String = "Center Court",
        court: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        durationMinutes: Int? = null,
        espnEventId: String? = null,
        espnWatchId: String? = null,
        deepLink: DeepLink? = null,
        youtubeVideoId: String? = null,
        youtubeDeepLink: DeepLink? = null
    ) : this(
        id = id,
        tournamentName = tournamentName,
        eventGrouping = eventGrouping,
        roundName = roundName,
        state = state,
        statusDetail = statusDetail,
        player1 = player1,
        player2 = player2,
        venue = venue,
        court = court,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        espnEventId = espnEventId,
        espnWatchId = espnWatchId,
        deepLink = deepLink,
        youtubeVideoId = youtubeVideoId,
        youtubeDeepLink = youtubeDeepLink,
        tournament = null,
        tour = null,
        category = null,
        surface = null
    )

    /**
     * Copy overload providing 100% binary backward compatibility with pre-compiled bytecode calling copy with 18 parameters.
     */
    fun copy(
        id: String = this.id,
        tournamentName: String = this.tournamentName,
        eventGrouping: String = this.eventGrouping,
        roundName: String = this.roundName,
        state: MatchState = this.state,
        statusDetail: String = this.statusDetail,
        player1: Competitor = this.player1,
        player2: Competitor = this.player2,
        venue: String = this.venue,
        court: String? = this.court,
        startTime: String? = this.startTime,
        endTime: String? = this.endTime,
        durationMinutes: Int? = this.durationMinutes,
        espnEventId: String? = this.espnEventId,
        espnWatchId: String? = this.espnWatchId,
        deepLink: DeepLink? = this.deepLink,
        youtubeVideoId: String? = this.youtubeVideoId,
        youtubeDeepLink: DeepLink? = this.youtubeDeepLink
    ): Match = Match(
        id = id,
        tournamentName = tournamentName,
        eventGrouping = eventGrouping,
        roundName = roundName,
        state = state,
        statusDetail = statusDetail,
        player1 = player1,
        player2 = player2,
        venue = venue,
        court = court,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        espnEventId = espnEventId,
        espnWatchId = espnWatchId,
        deepLink = deepLink,
        youtubeVideoId = youtubeVideoId,
        youtubeDeepLink = youtubeDeepLink,
        tournament = this.tournament,
        tour = this.tour,
        category = this.category,
        surface = this.surface
    )

    val effectiveTour: TourType

        get() = tour ?: tournament?.tour ?: when {
            eventGrouping.contains("women", ignoreCase = true) -> TourType.WTA
            eventGrouping.contains("men", ignoreCase = true) -> TourType.ATP
            else -> TourType.COMBINED
        }

    val effectiveCategory: TournamentCategory
        get() = category ?: tournament?.category ?: TournamentCategory.ATP_250

    val effectiveSurface: CourtSurface
        get() = surface ?: tournament?.surface ?: CourtSurface.HARD

    val displayCourt: String?
        get() = court?.takeIf { it.isNotBlank() }
            ?: venue.takeIf { it.isNotBlank() && !it.equals("New York, USA", ignoreCase = true) && !it.equals("TBD", ignoreCase = true) }

    val hasValidCompetitors: Boolean
        get() = !player1.isTbdOrPlaceholder && !player2.isTbdOrPlaceholder

    val title: String
        get() = "${player1.name} vs ${player2.name}"

    val formattedStartTime: String?
        get() = getFormattedStartTime(java.time.ZoneId.systemDefault())

    fun getFormattedStartTime(
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        is24Hour: Boolean = false
    ): String? {
        return com.tennis.tracker.util.TimezoneFormatter.formatIsoToLocal(startTime, zoneId, is24Hour)
            ?: com.tennis.tracker.util.TimezoneFormatter.convertEdtStringToLocal(statusDetail, zoneId, is24Hour)
            ?: startTime
    }

    val displayStatus: String
        get() = getDisplayStatus(java.time.ZoneId.systemDefault())

    fun getDisplayStatus(
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        is24Hour: Boolean = false
    ): String {
        return com.tennis.tracker.util.TimezoneFormatter.getDisplayStatus(this, zoneId, is24Hour)
    }

    fun getFormattedDuration(nowInstant: java.time.Instant = java.time.Instant.now()): String? {
        return when (state) {
            MatchState.LIVE -> {
                if (startTime.isNullOrEmpty()) return estimateLiveDuration()
                try {
                    val start = com.tennis.tracker.util.TimezoneFormatter.parseToInstant(startTime) ?: java.time.Instant.parse(startTime)
                    val duration = java.time.Duration.between(start, nowInstant)
                    val totalMinutes = duration.toMinutes()
                    if (totalMinutes > 0 && totalMinutes < 480) {
                        formatMinutes(totalMinutes)
                    } else {
                        estimateLiveDuration()
                    }
                } catch (_: Throwable) {
                    estimateLiveDuration()
                }
            }
            MatchState.FINISHED -> {
                if (durationMinutes != null && durationMinutes > 0) {
                    formatMinutes(durationMinutes.toLong())
                } else if (!startTime.isNullOrEmpty() && !endTime.isNullOrEmpty()) {
                    try {
                        val start = com.tennis.tracker.util.TimezoneFormatter.parseToInstant(startTime) ?: java.time.Instant.parse(startTime)
                        val end = com.tennis.tracker.util.TimezoneFormatter.parseToInstant(endTime) ?: java.time.Instant.parse(endTime)
                        val duration = java.time.Duration.between(start, end)
                        val totalMinutes = duration.toMinutes().coerceAtLeast(1)
                        formatMinutes(totalMinutes)
                    } catch (_: Throwable) {
                        estimateFinishedDuration()
                    }
                } else {
                    estimateFinishedDuration()
                }
            }
            MatchState.UPCOMING -> null
        }
    }

    private fun estimateLiveDuration(): String? {
        val totalSets = player1.setScores.size
        val estimatedMins = when (totalSets) {
            1 -> 42L
            2 -> 95L   // 1h 35m
            3 -> 148L  // 2h 28m
            4 -> 198L  // 3h 18m
            else -> 85L
        }
        return formatMinutes(estimatedMins)
    }

    private fun estimateFinishedDuration(): String? {
        val totalSets = player1.setScores.size
        if (totalSets == 0) return null
        val estimatedMins = when (totalSets) {
            2 -> 85L   // 1h 25m
            3 -> 135L  // 2h 15m
            4 -> 190L  // 3h 10m
            else -> 235L // 3h 55m
        }
        return formatMinutes(estimatedMins)
    }

    private fun formatMinutes(minutes: Long): String {
        val hours = minutes / 60
        val remainingMins = minutes % 60
        return when {
            hours > 0 && remainingMins > 0 -> "${hours}h ${remainingMins}m"
            hours > 0 -> "${hours}h"
            else -> "${remainingMins}m"
        }
    }
}
