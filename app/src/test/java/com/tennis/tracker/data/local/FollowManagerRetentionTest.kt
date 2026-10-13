package com.tennis.tracker.data.local

import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.MatchState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Dedicated unit testing suite for [FollowManager] match retention cleanup policies.
 *
 * Verifies following permissions and cleanup behavior under all 5 retention policies:
 * - 12 hours ([MatchRetentionPolicy.HOURS_12])
 * - 24 hours ([MatchRetentionPolicy.HOURS_24] - default)
 * - 48 hours ([MatchRetentionPolicy.HOURS_48])
 * - 7 days ([MatchRetentionPolicy.DAYS_7])
 * - Never auto-clear ([MatchRetentionPolicy.NEVER])
 *
 * Also verifies live settings repository reactivity, preservation of live/upcoming matches,
 * and persistent storage cleanup upon match expiration.
 */
class FollowManagerRetentionTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var settingsPrefs: FakeSharedPreferences
    private lateinit var settingsRepo: SettingsRepositoryImpl
    private lateinit var followManager: FollowManager

    private fun createMatch(
        id: String,
        state: MatchState,
        completionTimeMillis: Long? = null,
        startTimeMillis: Long? = null,
        durationMinutes: Int? = null
    ): Match {
        val isoEndTime = completionTimeMillis?.let { Instant.ofEpochMilli(it).toString() }
        val isoStartTime = startTimeMillis?.let { Instant.ofEpochMilli(it).toString() }
        return Match(
            id = id,
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Quarterfinals",
            state = state,
            statusDetail = state.name,
            player1 = Competitor(id = "p1", name = "Carlos Alcaraz"),
            player2 = Competitor(id = "p2", name = "Jannik Sinner"),
            venue = "Arthur Ashe Stadium",
            court = "Arthur Ashe Stadium",
            startTime = isoStartTime,
            endTime = isoEndTime,
            durationMinutes = durationMinutes
        )
    }

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        settingsPrefs = FakeSharedPreferences()
        settingsRepo = SettingsRepositoryImpl(settingsPrefs)
        followManager = FollowManager(
            context = null,
            preferences = fakePrefs,
            settingsRepository = settingsRepo
        )
    }

    // =========================================================================
    // 1. Policy: 12 Hours Retention (HOURS_12)
    // =========================================================================

    @Test
    fun testRetention12HoursAllowsMatchWithin12HoursAndPurgesAfter12Hours() = runBlocking {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        assertEquals(MatchRetentionPolicy.HOURS_12, followManager.getActiveRetentionPolicy())

        val now = 1_800_000_000_000L
        val match11hAgo = createMatch("m-11h", MatchState.FINISHED, completionTimeMillis = now - (11 * 3600 * 1000L))
        val match13hAgo = createMatch("m-13h", MatchState.FINISHED, completionTimeMillis = now - (13 * 3600 * 1000L))

        // Toggle follow check at deterministic timestamp
        val canFollow11h = followManager.toggleFollow(null, match11hAgo.id, match11hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue("Match finished 11h ago should be allowed under 12h policy", canFollow11h)
        assertTrue(followManager.isFollowed("m-11h"))

        val canFollow13h = followManager.toggleFollow(null, match13hAgo.id, match13hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertFalse("Match finished 13h ago should NOT be allowed under 12h policy", canFollow13h)
        assertFalse(followManager.isFollowed("m-13h"))

        // Pre-follow match13h by forcing into preferences, then test cleanup
        followManager.followMatch(null, match13hAgo.id)
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + match13hAgo.id, now - (13 * 3600 * 1000L)).apply()
        assertTrue(followManager.isFollowed("m-13h"))

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match11hAgo, match13hAgo),
            nowMillis = now, retentionPolicyOverride = null
        )

        assertEquals(setOf("m-13h"), removed)
        assertTrue(followManager.isFollowed("m-11h"))
        assertFalse(followManager.isFollowed("m-13h"))
    }

    // =========================================================================
    // 2. Policy: 24 Hours Retention (HOURS_24 - Canonical Default)
    // =========================================================================

    @Test
    fun testRetention24HoursAllowsMatchWithin24HoursAndPurgesAfter24Hours() = runBlocking {
        assertEquals(MatchRetentionPolicy.HOURS_24, followManager.getActiveRetentionPolicy())

        val now = 1_800_000_000_000L
        val match23hAgo = createMatch("m-23h", MatchState.FINISHED, completionTimeMillis = now - (23 * 3600 * 1000L))
        val match25hAgo = createMatch("m-25h", MatchState.FINISHED, completionTimeMillis = now - (25 * 3600 * 1000L))

        val canFollow23h = followManager.toggleFollow(null, match23hAgo.id, match23hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue("Match finished 23h ago should be followable under 24h retention", canFollow23h)
        assertTrue(followManager.isFollowed("m-23h"))

        val canFollow25h = followManager.toggleFollow(null, match25hAgo.id, match25hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertFalse("Match finished 25h ago should NOT be followable under 24h retention", canFollow25h)

        // Pre-follow match25h manually to test cleanup
        followManager.followMatch(null, match25hAgo.id)
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + match25hAgo.id, now - (25 * 3600 * 1000L)).apply()

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match23hAgo, match25hAgo),
            nowMillis = now, retentionPolicyOverride = null
        )

        assertEquals(setOf("m-25h"), removed)
        assertTrue(followManager.isFollowed("m-23h"))
        assertFalse(followManager.isFollowed("m-25h"))
    }

    // =========================================================================
    // 3. Policy: 48 Hours Retention (HOURS_48)
    // =========================================================================

    @Test
    fun testRetention48HoursAllowsMatchWithin48HoursAndPurgesAfter48Hours() = runBlocking {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_48)
        assertEquals(MatchRetentionPolicy.HOURS_48, followManager.getActiveRetentionPolicy())

        val now = 1_800_000_000_000L
        val match47hAgo = createMatch("m-47h", MatchState.FINISHED, completionTimeMillis = now - (47 * 3600 * 1000L))
        val match49hAgo = createMatch("m-49h", MatchState.FINISHED, completionTimeMillis = now - (49 * 3600 * 1000L))

        val canFollow47h = followManager.toggleFollow(null, match47hAgo.id, match47hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue("Match finished 47h ago should be followable under 48h retention", canFollow47h)
        assertTrue(followManager.isFollowed("m-47h"))

        val canFollow49h = followManager.toggleFollow(null, match49hAgo.id, match49hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertFalse("Match finished 49h ago should NOT be followable under 48h retention", canFollow49h)

        // Pre-follow match49h
        followManager.followMatch(null, match49hAgo.id)
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + match49hAgo.id, now - (49 * 3600 * 1000L)).apply()

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match47hAgo, match49hAgo),
            nowMillis = now, retentionPolicyOverride = null
        )

        assertEquals(setOf("m-49h"), removed)
        assertTrue(followManager.isFollowed("m-47h"))
        assertFalse(followManager.isFollowed("m-49h"))
    }

    // =========================================================================
    // 4. Policy: 7 Days Retention (DAYS_7)
    // =========================================================================

    @Test
    fun testRetention7DaysAllowsMatchWithin7DaysAndPurgesAfter7Days() = runBlocking {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.DAYS_7)
        assertEquals(MatchRetentionPolicy.DAYS_7, followManager.getActiveRetentionPolicy())

        val now = 1_800_000_000_000L
        val sixDaysMillis = 6L * 24 * 3600 * 1000L
        val eightDaysMillis = 8L * 24 * 3600 * 1000L

        val match6dAgo = createMatch("m-6d", MatchState.FINISHED, completionTimeMillis = now - sixDaysMillis)
        val match8dAgo = createMatch("m-8d", MatchState.FINISHED, completionTimeMillis = now - eightDaysMillis)

        val canFollow6d = followManager.toggleFollow(null, match6dAgo.id, match6dAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue("Match finished 6 days ago should be followable under 7d retention", canFollow6d)
        assertTrue(followManager.isFollowed("m-6d"))

        val canFollow8d = followManager.toggleFollow(null, match8dAgo.id, match8dAgo, nowMillis = now, retentionPolicyOverride = null)
        assertFalse("Match finished 8 days ago should NOT be followable under 7d retention", canFollow8d)

        // Pre-follow match8d
        followManager.followMatch(null, match8dAgo.id)
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + match8dAgo.id, now - eightDaysMillis).apply()

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match6dAgo, match8dAgo),
            nowMillis = now, retentionPolicyOverride = null
        )

        assertEquals(setOf("m-8d"), removed)
        assertTrue(followManager.isFollowed("m-6d"))
        assertFalse(followManager.isFollowed("m-8d"))
    }

    // =========================================================================
    // 5. Policy: Never Auto-Clear (NEVER)
    // =========================================================================

    @Test
    fun testRetentionNeverPreservesAncientMatchesAndAllowsFollowing() = runBlocking {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.NEVER)
        assertEquals(MatchRetentionPolicy.NEVER, followManager.getActiveRetentionPolicy())

        val now = 1_800_000_000_000L
        val match30dAgo = createMatch("m-30d", MatchState.FINISHED, completionTimeMillis = now - (30L * 24 * 3600 * 1000L))
        val match365dAgo = createMatch("m-1year", MatchState.FINISHED, completionTimeMillis = now - (365L * 24 * 3600 * 1000L))

        // Following ancient completed matches is allowed under NEVER policy
        val canFollow30d = followManager.toggleFollow(null, match30dAgo.id, match30dAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue(canFollow30d)
        assertTrue(followManager.isFollowed("m-30d"))

        val canFollow365d = followManager.toggleFollow(null, match365dAgo.id, match365dAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue(canFollow365d)
        assertTrue(followManager.isFollowed("m-1year"))

        // Cleanup must return emptySet() and purge zero matches
        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match30dAgo, match365dAgo),
            nowMillis = now, retentionPolicyOverride = null
        )

        assertTrue("NEVER retention policy must not remove any followed matches", removed.isEmpty())
        assertTrue(followManager.isFollowed("m-30d"))
        assertTrue(followManager.isFollowed("m-1year"))
    }

    // =========================================================================
    // 6. Dynamic Retention Policy Updates via SettingsRepository
    // =========================================================================

    @Test
    fun testDynamicRetentionPolicySwitchingInSettingsRepository() = runBlocking {
        val now = 1_800_000_000_000L
        val match16hAgo = createMatch("m-16h", MatchState.FINISHED, completionTimeMillis = now - (16 * 3600 * 1000L))

        // Initially 24h retention: match 16h ago is followed
        val followedUnder24h = followManager.toggleFollow(null, match16hAgo.id, match16hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue(followedUnder24h)
        assertTrue(followManager.isFollowed("m-16h"))

        // Cleanup under 24h retains the match
        var removed = followManager.cleanupExpiredFollowedMatches(null, listOf(match16hAgo), nowMillis = now, retentionPolicyOverride = null)
        assertTrue(removed.isEmpty())
        assertTrue(followManager.isFollowed("m-16h"))

        // User changes retention policy to 12h via SettingsRepository
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        assertEquals(MatchRetentionPolicy.HOURS_12, followManager.getActiveRetentionPolicy())

        // Next cleanup immediately recognizes 12h policy and purges the 16h match
        removed = followManager.cleanupExpiredFollowedMatches(null, listOf(match16hAgo), nowMillis = now, retentionPolicyOverride = null)
        assertEquals(setOf("m-16h"), removed)
        assertFalse(followManager.isFollowed("m-16h"))

        // Switch to NEVER retention
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.NEVER)
        assertEquals(MatchRetentionPolicy.NEVER, followManager.getActiveRetentionPolicy())

        // Now the 16h match can be re-followed without rejection
        val reFollowed = followManager.toggleFollow(null, match16hAgo.id, match16hAgo, nowMillis = now, retentionPolicyOverride = null)
        assertTrue(reFollowed)
        assertTrue(followManager.isFollowed("m-16h"))
    }

    // =========================================================================
    // 7. Live & Upcoming Matches Are Never Purged Under Any Policy
    // =========================================================================

    @Test
    fun testLiveAndUpcomingMatchesAreNeverPurgedUnderAnyPolicy() = runBlocking {
        val policies = MatchRetentionPolicy.values()
        val now = 1_800_000_000_000L

        for (policy in policies) {
            settingsRepo.updateMatchRetention(policy)

            // A live match and an upcoming match scheduled long ago
            val liveMatch = createMatch("live-${policy.name}", MatchState.LIVE, startTimeMillis = now - (48 * 3600 * 1000L))
            val upcomingMatch = createMatch("upcoming-${policy.name}", MatchState.UPCOMING, startTimeMillis = now + (12 * 3600 * 1000L))

            followManager.followMatch(null, liveMatch.id)
            followManager.followMatch(null, upcomingMatch.id)

            val removed = followManager.cleanupExpiredFollowedMatches(
                context = null,
                allMatches = listOf(liveMatch, upcomingMatch),
                nowMillis = now
            )

            assertTrue("Policy ${policy.name} must never remove LIVE or UPCOMING matches", removed.isEmpty())
            assertTrue(followManager.isFollowed(liveMatch.id))
            assertTrue(followManager.isFollowed(upcomingMatch.id))
        }
    }

    // =========================================================================
    // 8. Persistent SharedPreferences Timestamp Cleanup
    // =========================================================================

    @Test
    fun testRetentionCleanupClearsStoredCompletionTimestampFromPreferences() = runBlocking {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        val now = 1_800_000_000_000L

        val match = createMatch("m-expire-prefs", MatchState.FINISHED, completionTimeMillis = now - (15 * 3600 * 1000L))
        followManager.followMatch(null, match.id)
        val timestampKey = FollowManager.KEY_COMPLETED_PREFIX + match.id
        fakePrefs.edit().putLong(timestampKey, now - (15 * 3600 * 1000L)).apply()

        assertEquals(now - (15 * 3600 * 1000L), fakePrefs.getLong(timestampKey, -1L))

        val removed = followManager.cleanupExpiredFollowedMatches(null, listOf(match), nowMillis = now)
        assertEquals(setOf("m-expire-prefs"), removed)
        assertFalse(followManager.isFollowed("m-expire-prefs"))

        // Verify stored completion timestamp key in preferences was removed (-1L default)
        assertEquals(-1L, fakePrefs.getLong(timestampKey, -1L))
    }

    // =========================================================================
    // 9. Dropped Schedule Matches Expire Based on Stored Completion Timestamps
    // =========================================================================

    @Test
    fun testDroppedScheduleMatchesEvaluatedAgainstActiveRetention() = runBlocking {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        val now = 1_800_000_000_000L

        // Match dropped from tournament schedule feed, but stored timestamp exists in prefs
        val droppedMatchId = "dropped-archived-1"
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + droppedMatchId, now - (14 * 3600 * 1000L)).apply()
        followManager.followMatch(null, droppedMatchId)

        // allMatches is empty (dropped feed)
        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = emptyList(),
            nowMillis = now
        )

        assertEquals(setOf(droppedMatchId), removed)
        assertFalse(followManager.isFollowed(droppedMatchId))
        assertEquals(-1L, fakePrefs.getLong(FollowManager.KEY_COMPLETED_PREFIX + droppedMatchId, -1L))
    }
}
