package com.usopen.tracker.domain

import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.usecase.DeepLinkGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkGeneratorTest {

    private fun buildSampleMatch(id: String, state: MatchState): Match {
        return Match(
            id = id,
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Semifinals",
            state = state,
            statusDetail = "Status",
            player1 = Competitor(id = "1", name = "Carlos Alcaraz", seed = 2),
            player2 = Competitor(id = "2", name = "Jannik Sinner", seed = 1),
            venue = "Arthur Ashe Stadium",
            espnEventId = id
        )
    }

    @Test
    fun `generate should construct direct watch player link when espnWatchId is present`() {
        val match = buildSampleMatch(id = "182716", state = MatchState.LIVE).copy(
            espnWatchId = "d174bd1f-98f7-490e-b285-d75124819741"
        )
        val deepLink = DeepLinkGenerator.generate(match)

        assertTrue("Should be marked as live broadcast", deepLink.isLiveBroadcast)
        assertEquals("Watch Live on ESPN", deepLink.buttonLabel)
        assertEquals("sportscenter://x-callback-url/showWatchStream?playID=d174bd1f-98f7-490e-b285-d75124819741", deepLink.appUri)
        assertEquals("https://www.espn.com/watch/player?id=d174bd1f-98f7-490e-b285-d75124819741", deepLink.webFallbackUrl)
    }

    @Test
    fun `generate should construct specific watch search for live matches without watchId`() {
        val match = buildSampleMatch(id = "401582001", state = MatchState.LIVE)
        val deepLink = DeepLinkGenerator.generate(match)

        assertTrue("Should be marked as live broadcast", deepLink.isLiveBroadcast)
        assertEquals("Watch Live on ESPN", deepLink.buttonLabel)
        assertEquals("sportscenter://x-callback-url/showSearch?query=Carlos+Alcaraz+vs+Jannik+Sinner+US+Open", deepLink.appUri)
        assertEquals("https://www.espn.com/search/_/q/Carlos+Alcaraz+vs+Jannik+Sinner+US+Open/o/watch", deepLink.webFallbackUrl)
    }

    @Test
    fun `generate should construct specific watch search for finished match without watchId`() {
        val match = buildSampleMatch(id = "401582006", state = MatchState.FINISHED)
        val deepLink = DeepLinkGenerator.generate(match)

        assertFalse("Finished match is not a live broadcast", deepLink.isLiveBroadcast)
        assertEquals("Watch Replay on ESPN", deepLink.buttonLabel)
        assertEquals("sportscenter://x-callback-url/showSearch?query=Carlos+Alcaraz+vs+Jannik+Sinner+US+Open", deepLink.appUri)
        assertEquals("https://www.espn.com/search/_/q/Carlos+Alcaraz+vs+Jannik+Sinner+US+Open/o/watch", deepLink.webFallbackUrl)
    }

    @Test
    fun `generate should construct specific watch search for upcoming match`() {
        val match = buildSampleMatch(id = "401582004", state = MatchState.UPCOMING)
        val deepLink = DeepLinkGenerator.generate(match)

        assertFalse(deepLink.isLiveBroadcast)
        assertEquals("Watch Coverage on ESPN", deepLink.buttonLabel)
        assertEquals("sportscenter://x-callback-url/showSearch?query=Carlos+Alcaraz+vs+Jannik+Sinner+US+Open", deepLink.appUri)
        assertEquals("https://www.espn.com/search/_/q/Carlos+Alcaraz+vs+Jannik+Sinner+US+Open/o/watch", deepLink.webFallbackUrl)
    }

    @Test
    fun `buildMatchSearchQuery handles various player and tournament permutations`() {
        val fullMatch = buildSampleMatch(id = "1", state = MatchState.LIVE)
        assertEquals("Carlos Alcaraz vs Jannik Sinner US Open", DeepLinkGenerator.buildMatchSearchQuery(fullMatch))

        val soloMatch = fullMatch.copy(player2 = Competitor(id = "2", name = ""))
        assertEquals("Carlos Alcaraz US Open", DeepLinkGenerator.buildMatchSearchQuery(soloMatch))

        val noPlayers = fullMatch.copy(
            player1 = Competitor(id = "1", name = ""),
            player2 = Competitor(id = "2", name = ""),
            tournamentName = "Wimbledon"
        )
        assertEquals("Wimbledon tennis", DeepLinkGenerator.buildMatchSearchQuery(noPlayers))
    }
}
