package com.usopen.tracker.domain.model

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
    val youtubeDeepLink: DeepLink? = null
) {
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
        return com.usopen.tracker.util.TimezoneFormatter.formatIsoToLocal(startTime, zoneId, is24Hour)
            ?: com.usopen.tracker.util.TimezoneFormatter.convertEdtStringToLocal(statusDetail, zoneId, is24Hour)
            ?: startTime
    }

    val displayStatus: String
        get() = getDisplayStatus(java.time.ZoneId.systemDefault())

    fun getDisplayStatus(
        zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        is24Hour: Boolean = false
    ): String {
        return com.usopen.tracker.util.TimezoneFormatter.getDisplayStatus(this, zoneId, is24Hour)
    }

    fun getFormattedDuration(nowInstant: java.time.Instant = java.time.Instant.now()): String? {
        return when (state) {
            MatchState.LIVE -> {
                if (startTime.isNullOrEmpty()) return estimateLiveDuration()
                try {
                    val start = com.usopen.tracker.util.TimezoneFormatter.parseToInstant(startTime) ?: java.time.Instant.parse(startTime)
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
                        val start = com.usopen.tracker.util.TimezoneFormatter.parseToInstant(startTime) ?: java.time.Instant.parse(startTime)
                        val end = com.usopen.tracker.util.TimezoneFormatter.parseToInstant(endTime) ?: java.time.Instant.parse(endTime)
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
