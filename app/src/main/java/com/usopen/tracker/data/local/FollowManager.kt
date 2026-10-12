package com.usopen.tracker.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchRetentionPolicy
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.repository.SettingsRepository
import com.usopen.tracker.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages followed (opt-in) match IDs using SharedPreferences with an in-memory StateFlow
 * for instant reactive UI updates and widget synchronization.
 * Also manages automatic dynamic expiration and cleanup for completed matches based on user retention settings.
 */
class FollowManager(
    context: Context? = null,
    preferences: SharedPreferences? = null,
    settingsRepository: SettingsRepository? = null
) {

    private val appContext: Context? = context?.applicationContext

    private val prefs: SharedPreferences = preferences
        ?: appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: error("Either context or SharedPreferences must be provided to FollowManager")

    private var settingsRepository: SettingsRepository? = settingsRepository

    constructor(
        context: Context?,
        preferences: SharedPreferences?
    ) : this(context, preferences, null)

    constructor(
        context: Context?,
        settingsRepository: SettingsRepository?
    ) : this(context, null as SharedPreferences?, settingsRepository)

    private val _followedMatchIds = MutableStateFlow<Set<String>>(loadFollowedMatchIds())
    val followedMatchIds: StateFlow<Set<String>> = _followedMatchIds.asStateFlow()

    private fun loadFollowedMatchIds(): Set<String> {
        val rawSet = prefs.getStringSet(KEY_FOLLOWED_MATCHES, null)
        return rawSet?.toSet() ?: emptySet()
    }

    @Volatile
    private var manualRetentionPolicy: MatchRetentionPolicy? = null

    fun attachSettingsRepository(repository: SettingsRepository) {
        this.settingsRepository = repository
    }

    fun getSettingsRepository(): SettingsRepository? = settingsRepository

    fun setRetentionPolicy(policy: MatchRetentionPolicy) {
        this.manualRetentionPolicy = policy
    }

    fun getActiveRetentionPolicy(): MatchRetentionPolicy {
        return manualRetentionPolicy
            ?: settingsRepository?.getSettings()?.matchRetention
            ?: MatchRetentionPolicy.HOURS_24
    }

    /**
     * Checks if a match is currently followed.
     */
    fun isFollowed(matchId: String): Boolean {
        return _followedMatchIds.value.contains(matchId)
    }

    /**
     * Toggles the follow status of a match.
     * Returns true if the match is now followed, false if unfollowed.
     * If the match is already finished, checks whether it has expired under the active retention policy.
     * When policy is NEVER, matches can be followed indefinitely without restriction.
     */
    fun toggleFollow(
        context: Context? = appContext,
        matchId: String,
        match: Match? = null
    ): Boolean {
        return toggleFollow(context, matchId, match, null)
    }

    /**
     * Overload supporting an explicit retention policy override.
     */
    fun toggleFollow(
        context: Context?,
        matchId: String,
        match: Match?,
        retentionPolicyOverride: MatchRetentionPolicy?
    ): Boolean = toggleFollow(context, matchId, match, retentionPolicyOverride, System.currentTimeMillis())

    /**
     * Overload supporting an explicit retention policy override and reference timestamp.
     */
    fun toggleFollow(
        context: Context?,
        matchId: String,
        match: Match?,
        retentionPolicyOverride: MatchRetentionPolicy?,
        nowMillis: Long
    ): Boolean {
        val current = _followedMatchIds.value.toMutableSet()
        val isNowFollowed = if (current.contains(matchId)) {
            current.remove(matchId)
            prefs.edit().remove(KEY_COMPLETED_PREFIX + matchId).apply()
            false
        } else {
            if (match != null && match.state == MatchState.FINISHED) {
                val policy = retentionPolicyOverride ?: getActiveRetentionPolicy()
                val completionTime = getCompletionTimestamp(match, nowMillis)
                if (policy.isExpired(completionTime, nowMillis)) {
                    // Match completed >= retention duration ago, cannot follow
                    return false
                }
            }
            current.add(matchId)
            true
        }
        saveFollowedMatchIds(context, current)
        return isNowFollowed
    }

    /**
     * Opts in to follow a specific match.
     */
    fun followMatch(context: Context? = appContext, matchId: String) {
        val current = _followedMatchIds.value.toMutableSet()
        if (current.add(matchId)) {
            saveFollowedMatchIds(context, current)
        }
    }

    /**
     * Opts out from following a specific match.
     */
    fun unfollowMatch(context: Context? = appContext, matchId: String) {
        val current = _followedMatchIds.value.toMutableSet()
        if (current.remove(matchId)) {
            prefs.edit().remove(KEY_COMPLETED_PREFIX + matchId).apply()
            saveFollowedMatchIds(context, current)
        }
    }

    /**
     * Returns the current snapshot of followed match IDs.
     */
    fun getFollowedMatchIds(): Set<String> {
        return _followedMatchIds.value
    }

    /**
     * Resolves the completion timestamp in epoch milliseconds for a finished match.
     * Uses previously recorded timestamp, or parses match.endTime, or match.startTime + duration.
     */
    fun getCompletionTimestamp(match: Match, nowMillis: Long = System.currentTimeMillis()): Long {
        val stored = prefs.getLong(KEY_COMPLETED_PREFIX + match.id, -1L)
        if (stored > 0L) {
            return stored
        }

        // 1. Try match.endTime
        if (!match.endTime.isNullOrBlank()) {
            val parsedEnd = com.usopen.tracker.util.TimezoneFormatter.parseToInstant(match.endTime)
            if (parsedEnd != null) {
                val epoch = parsedEnd.toEpochMilli()
                prefs.edit().putLong(KEY_COMPLETED_PREFIX + match.id, epoch).apply()
                return epoch
            }
        }

        // 2. Try match.startTime + duration
        if (!match.startTime.isNullOrBlank()) {
            val parsedStart = com.usopen.tracker.util.TimezoneFormatter.parseToInstant(match.startTime)
            if (parsedStart != null) {
                val durationMinutes = match.durationMinutes?.toLong() ?: estimateDurationMinutes(match)
                val estimatedEnd = parsedStart.plusSeconds(durationMinutes * 60L).toEpochMilli()
                // A finished match cannot complete in the future relative to nowMillis
                val epoch = minOf(estimatedEnd, nowMillis)
                prefs.edit().putLong(KEY_COMPLETED_PREFIX + match.id, epoch).apply()
                return epoch
            }
        }

        // 3. Fallback: record now as completion timestamp
        prefs.edit().putLong(KEY_COMPLETED_PREFIX + match.id, nowMillis).apply()
        return nowMillis
    }

    private fun estimateDurationMinutes(match: Match): Long {
        val detail = match.statusDetail.lowercase()
        if (detail.contains("retired") || detail.contains("walkover") || detail.contains("default") || detail.contains("w/o")) {
            return 30L
        }
        val totalSets = match.player1.setScores.size
        return when (totalSets) {
            1 -> 45L
            2 -> 90L   // 1h 30m average for 2 sets
            3 -> 135L  // 2h 15m average for 3 sets
            4 -> 180L  // 3h average for 4 sets
            5 -> 225L  // 3h 45m average for 5 sets
            else -> 90L
        }
    }

    /**
     * Automatically removes any followed matches that are in FINISHED state and have expired under the active retention policy.
     * When retention policy is NEVER, skips cleanup completely, retaining matches indefinitely.
     * Returns the set of match IDs that were removed.
     */
    fun cleanupExpiredFollowedMatches(
        context: Context? = appContext,
        allMatches: List<Match>,
        nowMillis: Long = System.currentTimeMillis()
    ): Set<String> {
        return cleanupExpiredFollowedMatches(context, allMatches, nowMillis, null)
    }

    /**
     * Overload supporting an explicit retention policy override.
     */
    fun cleanupExpiredFollowedMatches(
        context: Context?,
        allMatches: List<Match>,
        nowMillis: Long,
        retentionPolicyOverride: MatchRetentionPolicy?
    ): Set<String> {
        val policy = retentionPolicyOverride ?: getActiveRetentionPolicy()
        if (policy == MatchRetentionPolicy.NEVER) {
            return emptySet()
        }

        val currentFollowed = _followedMatchIds.value
        if (currentFollowed.isEmpty()) return emptySet()

        val matchesMap = allMatches.associateBy { it.id }
        val toRemove = mutableSetOf<String>()
        val editor = prefs.edit()

        for (matchId in currentFollowed) {
            val match = matchesMap[matchId] ?: run {
                if (context != null) {
                    try {
                        MatchCacheManager.loadMatches(context).firstOrNull { it.id == matchId }
                    } catch (_: Throwable) { null }
                } else null
            }

            if (match != null) {
                if (match.state == MatchState.FINISHED) {
                    val completionTime = getCompletionTimestamp(match, nowMillis)
                    if (policy.isExpired(completionTime, nowMillis)) {
                        toRemove.add(matchId)
                        editor.remove(KEY_COMPLETED_PREFIX + matchId)
                    }
                }
            } else {
                // If match is no longer in tournament schedule or cache, check if it had a stored completion timestamp
                val stored = prefs.getLong(KEY_COMPLETED_PREFIX + matchId, -1L)
                if (stored > 0L && policy.isExpired(stored, nowMillis)) {
                    toRemove.add(matchId)
                    editor.remove(KEY_COMPLETED_PREFIX + matchId)
                }
            }
        }

        if (toRemove.isNotEmpty()) {
            val updated = currentFollowed - toRemove
            editor.putStringSet(KEY_FOLLOWED_MATCHES, updated).apply()
            _followedMatchIds.value = updated
            triggerWidgetUpdate(context)
        } else {
            editor.apply()
        }

        return toRemove
    }

    private fun saveFollowedMatchIds(context: Context? = appContext, ids: Set<String>) {
        prefs.edit().putStringSet(KEY_FOLLOWED_MATCHES, ids).apply()
        _followedMatchIds.value = ids.toSet()

        // Ensure ScoreboardRepositoryImpl has appContext for cache synchronization
        try {
            context?.applicationContext?.let {
                com.usopen.tracker.data.repository.ScoreboardRepositoryImpl.appContext = it
            }
        } catch (_: Throwable) {}

        // Notify home screen widget to refresh its data
        triggerWidgetUpdate(context)
    }

    private fun triggerWidgetUpdate(context: Context? = appContext) {
        val ctx = context?.applicationContext ?: return
        try {
            val providerClass = Class.forName("com.usopen.tracker.ui.widget.FollowedMatchesWidgetProvider")
            val method = providerClass.getMethod("updateAllWidgets", Context::class.java)
            method.invoke(null, ctx)
        } catch (_: Throwable) {
            // Widget provider may not be initialized yet during testing
        }
    }

    companion object {
        const val PREFS_NAME = "us_open_followed_matches"
        const val KEY_FOLLOWED_MATCHES = "followed_match_ids"
        const val KEY_COMPLETED_PREFIX = "match_completed_at_"
        const val EXPIRATION_MILLIS = 24 * 60 * 60 * 1000L // 24 hours canonical fallback

        @Volatile
        private var instance: FollowManager? = null

        fun getInstance(context: Context): FollowManager {
            try {
                com.usopen.tracker.data.repository.ScoreboardRepositoryImpl.appContext = context.applicationContext
            } catch (_: Throwable) {}
            return instance ?: synchronized(this) {
                instance ?: run {
                    val repo = try {
                        SettingsRepositoryImpl.getInstance(context.applicationContext)
                    } catch (_: Throwable) {
                        null
                    }
                    FollowManager(
                        context = context.applicationContext,
                        settingsRepository = repo
                    ).also { instance = it }
                }
            }
        }

        @VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
