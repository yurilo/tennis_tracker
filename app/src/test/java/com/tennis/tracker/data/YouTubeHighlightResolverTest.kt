package com.tennis.tracker.data

import com.tennis.tracker.data.remote.YouTubeHighlightResolver
import com.tennis.tracker.data.remote.YouTubeVideo
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class YouTubeHighlightResolverTest {

    private lateinit var resolver: YouTubeHighlightResolver
    private lateinit var sampleFinishedMatch: Match
    private lateinit var sampleLiveMatch: Match

    @Before
    fun setup() {
        resolver = YouTubeHighlightResolver()
        val p1 = Competitor(id = "p1", name = "Jannik Sinner", country = "ITA", isWinner = true)
        val p2 = Competitor(id = "p2", name = "Taylor Fritz", country = "USA")

        sampleFinishedMatch = Match(
            id = "test-101",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = p1,
            player2 = p2
        )

        sampleLiveMatch = Match(
            id = "test-102",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = MatchState.LIVE,
            statusDetail = "In Progress",
            player1 = p1,
            player2 = p2
        )
    }

    @Test
    fun `buildSearchQuery targets official usopen channel`() {
        val query = YouTubeHighlightResolver.buildSearchQuery(sampleFinishedMatch)
        assertTrue("Query should contain player 1", query.contains("Jannik Sinner"))
        assertTrue("Query should contain player 2", query.contains("Taylor Fritz"))
        assertTrue("Query should contain US Open highlights", query.contains("US Open highlights"))
        assertTrue("Query should target @usopen official channel", query.contains("@usopen"))
    }

    @Test
    fun `buildSearchQuery targets official tournament channels for all Grand Slams`() {
        val wimbledonMatch = sampleFinishedMatch.copy(tournamentName = "Wimbledon")
        val wimbledonQuery = YouTubeHighlightResolver.buildSearchQuery(wimbledonMatch)
        assertTrue(wimbledonQuery.contains("@wimbledon"))

        val rolandGarrosMatch = sampleFinishedMatch.copy(tournamentName = "Roland Garros")
        val rolandGarrosQuery = YouTubeHighlightResolver.buildSearchQuery(rolandGarrosMatch)
        assertTrue(rolandGarrosQuery.contains("@rolandgarros"))

        val ausOpenMatch = sampleFinishedMatch.copy(tournamentName = "Australian Open")
        val ausOpenQuery = YouTubeHighlightResolver.buildSearchQuery(ausOpenMatch)
        assertTrue(ausOpenQuery.contains("@australianopen"))
    }

    @Test
    fun `buildVideoDeepLink creates proper vnd youtube app Uri and web fallback`() {
        val deepLink = YouTubeHighlightResolver.buildVideoDeepLink("lytcJs4KWSk")
        assertEquals("vnd.youtube:lytcJs4KWSk", deepLink.appUri)
        assertEquals("https://www.youtube.com/watch?v=lytcJs4KWSk", deepLink.webFallbackUrl)
        assertEquals("Highlights", deepLink.buttonLabel)
        assertFalse(deepLink.isLiveBroadcast)
    }

    @Test
    fun `buildSearchDeepLink encodes official search query`() {
        val deepLink = YouTubeHighlightResolver.buildSearchDeepLink(sampleFinishedMatch)
        assertTrue(deepLink.webFallbackUrl.startsWith("https://www.youtube.com/results?search_query="))
        assertTrue(deepLink.webFallbackUrl.contains("Jannik+Sinner") || deepLink.webFallbackUrl.contains("Jannik%20Sinner") || deepLink.webFallbackUrl.contains("Jannik"))
        assertEquals("Highlights", deepLink.buttonLabel)
    }

    @Test
    fun `generateDeepLink returns video link when videoId present`() {
        val matchWithVideo = sampleFinishedMatch.copy(youtubeVideoId = "abc123xyz89")
        val deepLink = YouTubeHighlightResolver.generateDeepLink(matchWithVideo)
        assertEquals("vnd.youtube:abc123xyz89", deepLink.appUri)
        assertEquals("https://www.youtube.com/watch?v=abc123xyz89", deepLink.webFallbackUrl)
    }

    @Test
    fun `generateDeepLink falls back to search link when videoId is null`() {
        val matchWithoutVideo = sampleFinishedMatch.copy(youtubeVideoId = null)
        val deepLink = YouTubeHighlightResolver.generateDeepLink(matchWithoutVideo)
        assertTrue(deepLink.webFallbackUrl.contains("search_query"))
    }

    @Test
    fun `findBestHighlightMatch selects official highlight matching both competitors`() {
        val videos = listOf(
            YouTubeVideo(
                videoId = "wrong1",
                title = "Random Fan Reaction | US Open 2024",
                channelTitle = "Tennis Fan Daily"
            ),
            YouTubeVideo(
                videoId = "best123",
                title = "Jannik Sinner vs. Taylor Fritz Match Highlights | 2024 US Open Final",
                channelTitle = "US Open Tennis Championships"
            ),
            YouTubeVideo(
                videoId = "other456",
                title = "Taylor Fritz vs. Frances Tiafoe Semifinal Highlights",
                channelTitle = "US Open Tennis Championships"
            )
        )

        val best = resolver.findBestHighlightMatch(sampleFinishedMatch, videos)
        assertNotNull(best)
        assertEquals("best123", best?.videoId)
        assertEquals("US Open Tennis Championships", best?.channelTitle)
    }

    @Test
    fun `findBestHighlightMatch matches composite surnames like van de Zandschulp`() {
        val p1 = Competitor(id = "p1", name = "Carlos Alcaraz")
        val p2 = Competitor(id = "p2", name = "Botic van de Zandschulp")
        val match = sampleFinishedMatch.copy(player1 = p1, player2 = p2)

        val videos = listOf(
            YouTubeVideo(
                videoId = "vid_alcaraz_zandschulp",
                title = "Carlos Alcaraz vs. Botic van de Zandschulp Highlights | 2024 US Open Round 2",
                channelTitle = "US Open Tennis Championships"
            )
        )

        val best = resolver.findBestHighlightMatch(match, videos)
        assertNotNull(best)
        assertEquals("vid_alcaraz_zandschulp", best?.videoId)
    }

    @Test
    fun `resolveHighlight returns null for non-finished matches`() = runBlocking {
        val result = resolver.resolveHighlight(sampleLiveMatch)
        assertNull("Highlights should not resolve for non-finished matches", result)
    }

    @Test
    fun `resolveHighlight returns pre-cached videoId immediately`() = runBlocking {
        val cachedMatch = sampleFinishedMatch.copy(youtubeVideoId = "cachedVid999")
        val result = resolver.resolveHighlight(cachedMatch)
        assertNotNull(result)
        assertEquals("cachedVid999", result?.videoId)
        assertEquals("vnd.youtube:cachedVid999", result?.deepLink?.appUri)
    }
}
