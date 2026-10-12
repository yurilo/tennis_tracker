package com.usopen.tracker.domain.model

/**
 * Background synchronization policy for home screen widgets.
 */
enum class WidgetSyncMode(
    val displayName: String,
    val description: String
) {
    ADAPTIVE(
        displayName = "Adaptive",
        description = "Intelligent sync: 1m for live matches, 5m for upcoming, 15m when idle."
    ),
    INTERVAL_15M(
        displayName = "15 minutes",
        description = "Fixed background sync interval of 15 minutes."
    ),
    INTERVAL_30M(
        displayName = "30 minutes",
        description = "Fixed background sync interval of 30 minutes for lower battery usage."
    ),
    DISABLED(
        displayName = "Disabled",
        description = "Background sync disabled; widget updates on device wake or tap only."
    );

    /**
     * Resolves the background sync interval in milliseconds for the given set of matches and followed IDs.
     * Returns null when background sync is [DISABLED].
     */
    fun resolveIntervalMillis(
        matches: List<Match>,
        followedIds: Set<String>
    ): Long? {
        return when (this) {
            DISABLED -> null
            INTERVAL_15M -> 15 * 60 * 1000L
            INTERVAL_30M -> 30 * 60 * 1000L
            ADAPTIVE -> {
                val relevantMatches = if (followedIds.isNotEmpty()) {
                    matches.filter { it.id in followedIds }
                } else {
                    matches
                }

                if (relevantMatches.isEmpty()) {
                    900_000L // 15 minutes default / idle
                } else when {
                    relevantMatches.any { it.state == MatchState.LIVE } -> 60_000L // 1 minute
                    relevantMatches.any { it.state == MatchState.UPCOMING } -> 300_000L // 5 minutes
                    else -> 900_000L // 15 minutes
                }
            }
        }
    }

    companion object {
        val DEFAULT: WidgetSyncMode = ADAPTIVE

        fun fromNameOrDefault(name: String?): WidgetSyncMode {
            if (name == null) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: IllegalArgumentException) {
                DEFAULT
            }
        }
    }
}
