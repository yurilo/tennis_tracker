package com.tennis.tracker.domain.model

/**
 * Retention duration policy for keeping finished matches in the Following list before auto-cleanup.
 */
enum class MatchRetentionPolicy(
    val durationMillis: Long,
    val displayName: String,
    val description: String
) {
    HOURS_12(
        durationMillis = 12 * 60 * 60 * 1000L,
        displayName = "12 hours",
        description = "Retain finished matches for 12 hours after completion."
    ),
    HOURS_24(
        durationMillis = 24 * 60 * 60 * 1000L,
        displayName = "24 hours",
        description = "Retain finished matches for 24 hours after completion (default)."
    ),
    HOURS_48(
        durationMillis = 48 * 60 * 60 * 1000L,
        displayName = "48 hours",
        description = "Retain finished matches for 48 hours after completion."
    ),
    DAYS_7(
        durationMillis = 7 * 24 * 60 * 60 * 1000L,
        displayName = "7 days",
        description = "Retain finished matches for up to 7 days after completion."
    ),
    NEVER(
        durationMillis = Long.MAX_VALUE,
        displayName = "Never auto-clear",
        description = "Keep followed matches indefinitely until manually unfollowed."
    );

    /**
     * Determines whether a match finished at [completionTimeMillis] has expired under this policy.
     * Always returns false if this policy is [NEVER] or if [completionTimeMillis] is non-positive.
     */
    fun isExpired(
        completionTimeMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (this == NEVER) return false
        if (completionTimeMillis <= 0L) return false
        val elapsed = nowMillis - completionTimeMillis
        return elapsed >= durationMillis
    }

    companion object {
        val DEFAULT: MatchRetentionPolicy = HOURS_24

        fun fromNameOrDefault(name: String?): MatchRetentionPolicy {
            if (name == null) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: IllegalArgumentException) {
                DEFAULT
            }
        }
    }
}
