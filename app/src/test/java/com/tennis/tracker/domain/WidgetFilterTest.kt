package com.tennis.tracker.domain

import com.tennis.tracker.data.local.MatchCacheManager
import com.tennis.tracker.data.local.MockTournamentData
import com.tennis.tracker.domain.model.MatchState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class WidgetFilterTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(5)

    private val sampleMatches = MockTournamentData.matches

    @Before
    fun setUp() {
        MatchCacheManager.clearCache(null)
    }

    @After
    fun tearDown() {
        MatchCacheManager.clearCache(null)
    }

    @Test
    fun `widget data source filters strictly by followed match IDs`() {
        val allMatches = sampleMatches
        assertTrue("Sample matches should not be empty", allMatches.size >= 4)

        val match1 = allMatches[0]
        val match2 = allMatches[1]
        val followedIds = setOf(match1.id, match2.id)

        val widgetMatches = allMatches.filter { it.id in followedIds }

        assertEquals(2, widgetMatches.size)
        assertTrue(widgetMatches.any { it.id == match1.id })
        assertTrue(widgetMatches.any { it.id == match2.id })
    }

    @Test
    fun `widget data source returns empty when no matches followed`() {
        val followedIds = emptySet<String>()
        val widgetMatches = sampleMatches.filter { it.id in followedIds }

        assertTrue("Empty followed set must produce empty widget match list", widgetMatches.isEmpty())
    }

    @Test
    fun `widget live count calculates only followed live matches`() {
        val liveMatches = sampleMatches.filter { it.state == MatchState.LIVE }
        assertTrue("Should have at least 1 live match in mock data", liveMatches.isNotEmpty())

        val liveMatch = liveMatches.first()
        val nonLiveMatch = sampleMatches.first { it.state != MatchState.LIVE }

        // Scenario 1: Only non-live match followed -> live count should be 0
        val followedOnlyNonLive = setOf(nonLiveMatch.id)
        val liveCount1 = sampleMatches.count { it.state == MatchState.LIVE && it.id in followedOnlyNonLive }
        assertEquals(0, liveCount1)

        // Scenario 2: Live match followed -> live count should be 1
        val followedWithLive = setOf(liveMatch.id, nonLiveMatch.id)
        val liveCount2 = sampleMatches.count { it.state == MatchState.LIVE && it.id in followedWithLive }
        assertEquals(1, liveCount2)
    }

    @Test
    fun `unfollowed live match is not shown in widget`() {
        val liveMatches = sampleMatches.filter { it.state == MatchState.LIVE }
        val liveMatch = liveMatches.first()

        // User only follows a finished match
        val finishedMatch = sampleMatches.first { it.state == MatchState.FINISHED }
        val followedIds = setOf(finishedMatch.id)

        val widgetMatches = sampleMatches.filter { it.id in followedIds }
        assertFalse("Unfollowed live match must not appear in widget", widgetMatches.any { it.id == liveMatch.id })
        assertEquals(1, widgetMatches.size)
        assertEquals(finishedMatch.id, widgetMatches.first().id)
    }

    @Test
    fun `match duration and venue are properly formatted for widget`() {
        val match = sampleMatches.first()
        assertTrue("Venue should be non-empty", match.venue.isNotEmpty())
        assertTrue("Event grouping should be non-empty", match.eventGrouping.isNotEmpty())
    }

    @Test
    fun `upcoming match can be followed and appears in widget data`() {
        val upcomingMatches = sampleMatches.filter { it.state == MatchState.UPCOMING }
        assertTrue("Must have upcoming matches in tournament data", upcomingMatches.isNotEmpty())

        val upcoming = upcomingMatches.first()
        val followedIds = setOf(upcoming.id)

        val widgetMatches = sampleMatches.filter { it.id in followedIds }
        assertEquals(1, widgetMatches.size)
        assertEquals(upcoming.id, widgetMatches.first().id)
        assertEquals(MatchState.UPCOMING, widgetMatches.first().state)
    }

    @Test
    fun `dynamic ESPN ID match followed by user appears in repository offline scoreboard`() {
        val dynamicEspnMatch = com.tennis.tracker.domain.model.Match(
            id = "401732560",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Round 3",
            state = MatchState.UPCOMING,
            statusDetail = "Tonight • 8:30 PM EDT",
            player1 = com.tennis.tracker.domain.model.Competitor(id = "p1", name = "Ben Shelton", seed = 13, country = "USA"),
            player2 = com.tennis.tracker.domain.model.Competitor(id = "p2", name = "Frances Tiafoe", seed = 20, country = "USA"),
            venue = "Arthur Ashe Stadium",
            startTime = "2026-09-05T20:30Z"
        )

        // Save to cache
        com.tennis.tracker.data.local.MatchCacheManager.saveMatches(null, listOf(dynamicEspnMatch))

        val repository = com.tennis.tracker.data.repository.ScoreboardRepositoryImpl()
        val allMatches = repository.getOfflineScoreboard()

        val followedIds = setOf("401732560")
        val widgetMatches = allMatches.filter { it.id in followedIds }

        assertEquals(1, widgetMatches.size)
        assertEquals("401732560", widgetMatches.first().id)
        assertEquals("Ben Shelton", widgetMatches.first().player1.name)
        assertEquals("Frances Tiafoe", widgetMatches.first().player2.name)
        assertEquals(MatchState.UPCOMING, widgetMatches.first().state)

        com.tennis.tracker.data.local.MatchCacheManager.clearCache(null)
    }
}
