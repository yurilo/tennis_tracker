package com.tennis.tracker.data

import com.tennis.tracker.data.local.MatchCacheManager
import com.tennis.tracker.data.local.MockTournamentData
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MatchCacheManagerTest {

    @Before
    fun setup() {
        MatchCacheManager.clearCache(null)
    }

    @After
    fun tearDown() {
        MatchCacheManager.clearCache(null)
    }

    @Test
    fun `serialize and deserialize upcoming match with ESPN ID preserves all fields`() {
        val upcomingMatch = Match(
            id = "espn-401567890",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Quarterfinals",
            state = MatchState.UPCOMING,
            statusDetail = "Tonight • 9:00 PM EDT",
            player1 = Competitor(
                id = "p1",
                name = "Carlos Alcaraz",
                seed = 2,
                country = "ESP",
                isWinner = false,
                setScores = emptyList(),
                tiebreakScores = emptyList(),
                currentGameScore = null,
                isServing = false,
                photoUrl = "https://example.com/alcaraz.png"
            ),
            player2 = Competitor(
                id = "p2",
                name = "Jannik Sinner",
                seed = 1,
                country = "ITA",
                isWinner = false,
                setScores = emptyList(),
                tiebreakScores = emptyList(),
                currentGameScore = null,
                isServing = false,
                photoUrl = "https://example.com/sinner.png"
            ),
            venue = "Arthur Ashe Stadium",
            court = "Arthur Ashe Stadium",
            startTime = "2026-09-05T21:00Z",
            espnEventId = "401567890",
            espnWatchId = "d174bd1f-98f7-490e-b285-d75124819741"
        )

        val json = MatchCacheManager.matchToJson(upcomingMatch)
        val restored = MatchCacheManager.jsonToMatch(json)

        assertEquals("espn-401567890", restored.id)
        assertEquals(MatchState.UPCOMING, restored.state)
        assertEquals("Tonight • 9:00 PM EDT", restored.statusDetail)
        assertEquals("Arthur Ashe Stadium", restored.venue)
        assertEquals("Arthur Ashe Stadium", restored.court)
        assertEquals("Arthur Ashe Stadium", restored.displayCourt)
        assertEquals("2026-09-05T21:00Z", restored.startTime)
        assertEquals("401567890", restored.espnEventId)
        assertEquals("d174bd1f-98f7-490e-b285-d75124819741", restored.espnWatchId)
        assertEquals("sportscenter://x-callback-url/showWatchStream?playID=d174bd1f-98f7-490e-b285-d75124819741", restored.deepLink?.appUri)
        assertEquals("https://www.espn.com/watch/player?id=d174bd1f-98f7-490e-b285-d75124819741", restored.deepLink?.webFallbackUrl)

        // Player 1
        assertEquals("Carlos Alcaraz", restored.player1.name)
        assertEquals(2, restored.player1.seed)
        assertEquals("ESP", restored.player1.country)
        assertEquals("🇪🇸", restored.player1.countryFlag)
        assertTrue(restored.player1.setScores.isEmpty())
        assertNull(restored.player1.currentGameScore)
        assertFalse(restored.player1.isServing)

        // Player 2
        assertEquals("Jannik Sinner", restored.player2.name)
        assertEquals(1, restored.player2.seed)
        assertEquals("ITA", restored.player2.country)
        assertEquals("🇮🇹", restored.player2.countryFlag)

        // DeepLink
        assertNotNull(restored.deepLink)
        assertTrue(restored.deepLink!!.webFallbackUrl.isNotEmpty())
    }

    @Test
    fun `serialize and deserialize live match with tiebreak points preserves scores`() {
        val liveMatch = Match(
            id = "espn-live-101",
            tournamentName = "US Open",
            eventGrouping = "Women's Singles",
            roundName = "Semifinals",
            state = MatchState.LIVE,
            statusDetail = "In Progress • 3rd Set",
            player1 = Competitor(
                id = "p1",
                name = "Coco Gauff",
                seed = 3,
                country = "USA",
                isWinner = false,
                setScores = listOf(6, 6, 4),
                tiebreakScores = listOf(null, 7, null),
                currentGameScore = "40",
                isServing = true
            ),
            player2 = Competitor(
                id = "p2",
                name = "Iga Swiatek",
                seed = 1,
                country = "POL",
                isWinner = false,
                setScores = listOf(4, 7, 3),
                tiebreakScores = listOf(null, 5, null),
                currentGameScore = "15",
                isServing = false
            ),
            venue = "Arthur Ashe Stadium",
            startTime = "2026-09-05T19:00Z",
            durationMinutes = 115
        )

        val json = MatchCacheManager.matchToJson(liveMatch)
        val restored = MatchCacheManager.jsonToMatch(json)

        assertEquals(MatchState.LIVE, restored.state)
        assertEquals(listOf(6, 6, 4), restored.player1.setScores)
        assertEquals(listOf(null, 7, null), restored.player1.tiebreakScores)
        assertEquals("40", restored.player1.currentGameScore)
        assertTrue(restored.player1.isServing)

        assertEquals(listOf(4, 7, 3), restored.player2.setScores)
        assertEquals(listOf(null, 5, null), restored.player2.tiebreakScores)
        assertEquals("15", restored.player2.currentGameScore)
        assertFalse(restored.player2.isServing)
    }

    @Test
    fun `getAllKnownMatches returns cached matches without losing followed IDs`() {
        val customLiveMatch = Match(
            id = "espn-live-custom-999",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = MatchState.UPCOMING,
            statusDetail = "Sunday • 2:00 PM EDT",
            player1 = Competitor(id = "p1", name = "Player A"),
            player2 = Competitor(id = "p2", name = "Player B"),
            venue = "Arthur Ashe Stadium"
        )

        // Save custom match to cache
        MatchCacheManager.saveMatches(null, listOf(customLiveMatch))

        val allKnown = MatchCacheManager.getAllKnownMatches(null)

        // Must contain custom match
        assertTrue("Must contain newly cached ESPN match", allKnown.any { it.id == "espn-live-custom-999" })
        assertEquals(1, allKnown.size)

        // Following custom ESPN match must find it
        val followedIds = setOf("espn-live-custom-999")
        val followed = allKnown.filter { it.id in followedIds }
        assertEquals(1, followed.size)
        assertEquals("espn-live-custom-999", followed.first().id)
        assertEquals(MatchState.UPCOMING, followed.first().state)
    }

    @Test
    fun `serialize and deserialize completed match preserves youtubeVideoId and generates youtubeDeepLink`() {
        val finishedMatch = Match(
            id = "finished-yt-123",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = Competitor(id = "p1", name = "Jannik Sinner", isWinner = true),
            player2 = Competitor(id = "p2", name = "Taylor Fritz"),
            youtubeVideoId = "lytcJs4KWSk"
        )

        val json = MatchCacheManager.matchToJson(finishedMatch)
        val restored = MatchCacheManager.jsonToMatch(json)

        assertEquals("lytcJs4KWSk", restored.youtubeVideoId)
        assertNotNull(restored.youtubeDeepLink)
        assertEquals("vnd.youtube:lytcJs4KWSk", restored.youtubeDeepLink?.appUri)
        assertEquals("https://www.youtube.com/watch?v=lytcJs4KWSk", restored.youtubeDeepLink?.webFallbackUrl)
    }
}
