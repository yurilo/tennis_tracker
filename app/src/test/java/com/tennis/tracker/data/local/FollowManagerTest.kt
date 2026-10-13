package com.tennis.tracker.data.local

import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.time.Instant

class FollowManagerTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(5)

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var followManager: FollowManager

    private fun createMatch(
        id: String,
        state: MatchState,
        startTime: String? = null,
        endTime: String? = null,
        durationMinutes: Int? = null
    ): Match {
        return Match(
            id = id,
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Quarterfinals",
            state = state,
            statusDetail = state.name,
            player1 = Competitor(id = "p1", name = "Player One"),
            player2 = Competitor(id = "p2", name = "Player Two"),
            venue = "Arthur Ashe Stadium",
            court = "Arthur Ashe Stadium",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = durationMinutes
        )
    }

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        followManager = FollowManager(context = null, preferences = fakePrefs)
    }

    @Test
    fun `toggleFollow adds and removes match ID and updates followedMatchIds flow`() {
        assertFalse(followManager.isFollowed("match-1"))

        val followed = followManager.toggleFollow(null, "match-1")
        assertTrue(followed)
        assertTrue(followManager.isFollowed("match-1"))
        assertTrue(followManager.followedMatchIds.value.contains("match-1"))

        val unfollowed = followManager.toggleFollow(null, "match-1")
        assertFalse(unfollowed)
        assertFalse(followManager.isFollowed("match-1"))
        assertFalse(followManager.followedMatchIds.value.contains("match-1"))
    }

    @Test
    fun `followMatch and unfollowMatch properly manage followed matches`() {
        followManager.followMatch(null, "m-100")
        followManager.followMatch(null, "m-200")
        assertEquals(setOf("m-100", "m-200"), followManager.getFollowedMatchIds())

        followManager.unfollowMatch(null, "m-100")
        assertEquals(setOf("m-200"), followManager.getFollowedMatchIds())
    }

    @Test
    fun `getCompletionTimestamp parses endTime when available`() {
        val isoEndTime = "2026-09-05T20:00:00Z"
        val expectedEpoch = Instant.parse(isoEndTime).toEpochMilli()
        val match = createMatch("m-end", MatchState.FINISHED, endTime = isoEndTime)

        val timestamp = followManager.getCompletionTimestamp(match, nowMillis = expectedEpoch + 5000)
        assertEquals(expectedEpoch, timestamp)
        // Verify stored in prefs
        assertEquals(expectedEpoch, fakePrefs.getLong(FollowManager.KEY_COMPLETED_PREFIX + "m-end", -1L))
    }

    @Test
    fun `getCompletionTimestamp calculates startTime plus duration when endTime is missing`() {
        val isoStartTime = "2026-09-05T18:00:00Z"
        val startInstant = Instant.parse(isoStartTime)
        val duration = 150 // 2.5 hours
        val expectedEpoch = startInstant.plusSeconds(150 * 60L).toEpochMilli()
        val match = createMatch(
            id = "m-duration",
            state = MatchState.FINISHED,
            startTime = isoStartTime,
            durationMinutes = duration
        )

        val timestamp = followManager.getCompletionTimestamp(match, nowMillis = expectedEpoch + 10000)
        assertEquals(expectedEpoch, timestamp)
        assertEquals(expectedEpoch, fakePrefs.getLong(FollowManager.KEY_COMPLETED_PREFIX + "m-duration", -1L))
    }

    @Test
    fun `cleanupExpiredFollowedMatches retains match completed 23 hours ago`() {
        val nowMillis = 1788629400000L
        val completed23hAgo = nowMillis - (23 * 60 * 60 * 1000L) // 23h ago

        val match = createMatch("match-recent-finish", MatchState.FINISHED)
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + match.id, completed23hAgo).apply()
        followManager.followMatch(null, match.id)

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match),
            nowMillis = nowMillis
        )

        assertTrue(removed.isEmpty())
        assertTrue(followManager.isFollowed(match.id))
    }

    @Test
    fun `cleanupExpiredFollowedMatches removes match completed 25 hours ago`() {
        val nowMillis = 1788629400000L
        val completed25hAgo = nowMillis - (25 * 60 * 60 * 1000L) // 25h ago

        val match = createMatch("match-old-finish", MatchState.FINISHED)
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + match.id, completed25hAgo).apply()
        followManager.followMatch(null, match.id)

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match),
            nowMillis = nowMillis
        )

        assertEquals(setOf("match-old-finish"), removed)
        assertFalse(followManager.isFollowed(match.id))
        assertFalse(followManager.followedMatchIds.value.contains(match.id))
        // Verify preference timestamp was also cleared
        assertEquals(-1L, fakePrefs.getLong(FollowManager.KEY_COMPLETED_PREFIX + match.id, -1L))
    }

    @Test
    fun `cleanupExpiredFollowedMatches never removes LIVE or UPCOMING matches regardless of age`() {
        val nowMillis = 1788629400000L
        val liveMatch = createMatch("match-live", MatchState.LIVE)
        val upcomingMatch = createMatch("match-upcoming", MatchState.UPCOMING)

        followManager.followMatch(null, liveMatch.id)
        followManager.followMatch(null, upcomingMatch.id)

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(liveMatch, upcomingMatch),
            nowMillis = nowMillis
        )

        assertTrue(removed.isEmpty())
        assertTrue(followManager.isFollowed(liveMatch.id))
        assertTrue(followManager.isFollowed(upcomingMatch.id))
    }

    @Test
    fun `unfollowing a match cleans up stored completion timestamp`() {
        val match = createMatch("m-unfollow", MatchState.FINISHED)
        followManager.followMatch(null, match.id)
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + match.id, 123456789L).apply()

        followManager.unfollowMatch(null, match.id)
        assertEquals(-1L, fakePrefs.getLong(FollowManager.KEY_COMPLETED_PREFIX + match.id, -1L))
    }

    @Test
    fun `cleanupExpiredFollowedMatches removes finished match when startTime is 26h ago without prior stored timestamp`() {
        val nowInstant = Instant.parse("2026-09-07T16:00:00Z")
        val nowMillis = nowInstant.toEpochMilli()
        val start26hAgo = nowInstant.minusSeconds(26 * 3600L).toString() // 26h ago

        val match = createMatch(
            id = "m-start-26h",
            state = MatchState.FINISHED,
            startTime = start26hAgo,
            durationMinutes = 90
        )
        followManager.followMatch(null, match.id)

        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = listOf(match),
            nowMillis = nowMillis
        )

        assertEquals(setOf("m-start-26h"), removed)
        assertFalse(followManager.isFollowed(match.id))
    }

    @Test
    fun `cleanupExpiredFollowedMatches removes dropped match with expired completion timestamp`() {
        val nowMillis = 1788629400000L
        val completed25hAgo = nowMillis - (25 * 60 * 60 * 1000L)

        val droppedMatchId = "dropped-match-999"
        fakePrefs.edit().putLong(FollowManager.KEY_COMPLETED_PREFIX + droppedMatchId, completed25hAgo).apply()
        followManager.followMatch(null, droppedMatchId)

        // allMatches is empty (match dropped off live tournament schedule)
        val removed = followManager.cleanupExpiredFollowedMatches(
            context = null,
            allMatches = emptyList(),
            nowMillis = nowMillis
        )

        assertEquals(setOf(droppedMatchId), removed)
        assertFalse(followManager.isFollowed(droppedMatchId))
    }

    @Test
    fun `toggleFollow prevents following match finished 25 hours ago`() {
        val nowInstant = Instant.now()
        val start28hAgo = nowInstant.minusSeconds(28 * 3600L).toString()

        val expiredMatch = createMatch(
            id = "m-already-expired",
            state = MatchState.FINISHED,
            startTime = start28hAgo,
            durationMinutes = 90
        )

        val isFollowed = followManager.toggleFollow(null, expiredMatch.id, expiredMatch)
        assertFalse("Should not allow following an expired match", isFollowed)
        assertFalse(followManager.isFollowed(expiredMatch.id))
    }

    @Test
    fun `toggleFollow allows following match finished 2 hours ago`() {
        val nowInstant = Instant.now()
        val start4hAgo = nowInstant.minusSeconds(4 * 3600L).toString()

        val recentMatch = createMatch(
            id = "m-recently-finished",
            state = MatchState.FINISHED,
            startTime = start4hAgo,
            durationMinutes = 90
        )

        val isFollowed = followManager.toggleFollow(null, recentMatch.id, recentMatch)
        assertTrue("Should allow following a recently finished match", isFollowed)
        assertTrue(followManager.isFollowed(recentMatch.id))
    }
}
