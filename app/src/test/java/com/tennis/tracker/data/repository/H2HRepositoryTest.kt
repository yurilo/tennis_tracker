package com.tennis.tracker.data.repository

import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

class H2HRepositoryTest {

    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(10)

    private val alcaraz = Competitor(
        id = "4094",
        name = "Carlos Alcaraz",
        seed = 2,
        country = "ESP",
        setScores = listOf(6, 4, 6),
        isWinner = true
    )

    private val sinner = Competitor(
        id = "4096",
        name = "Jannik Sinner",
        seed = 1,
        country = "ITA",
        setScores = listOf(4, 6, 3),
        isWinner = false
    )

    private val shelton = Competitor(
        id = "5001",
        name = "Ben Shelton",
        seed = 13,
        country = "USA"
    )

    private val tsitsipas = Competitor(
        id = "5002",
        name = "Stefanos Tsitsipas",
        seed = 11,
        country = "GRE"
    )

    @Test
    fun testUpcomingMatchReturnsValidH2HSummary() = runBlocking {
        val repo = H2HRepository()
        val upcomingMatch = Match(
            id = "up_1",
            tournamentName = "US Open",
            roundName = "Quarter-Final",
            state = MatchState.UPCOMING,
            statusDetail = "Tomorrow • 7:00 PM",
            player1 = shelton,
            player2 = tsitsipas,
            venue = "Arthur Ashe Stadium"
        )

        val result = repo.getH2HSummary(upcomingMatch)
        assertTrue("Repository should succeed for upcoming matches", result.isSuccess)
        val summary = result.getOrNull()
        assertNotNull(summary)
        assertTrue("Matches should not be empty", summary!!.matches.isNotEmpty())
        assertFalse("Upcoming match must not be marked as current", summary.matches.any { it.isCurrentMatch })
    }

    @Test
    fun testLiveMatchExcludesCurrentMatch() = runBlocking {
        val repo = H2HRepository()
        val liveMatch = Match(
            id = "live_1",
            tournamentName = "US Open",
            roundName = "Semi-Final",
            state = MatchState.LIVE,
            statusDetail = "Set 2 • 3-2",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val result = repo.getH2HSummary(liveMatch)
        assertTrue(result.isSuccess)
        val summary = result.getOrNull()!!
        assertFalse("Live match must not be marked as current", summary.matches.any { it.isCurrentMatch })
        assertTrue("Alcaraz vs Sinner must have at least 9 career encounters", summary.matches.size >= 9)
    }

    @Test
    fun testFinishedMatchIncludesCurrentMatchAtTop() = runBlocking {
        val repo = H2HRepository()
        val liveMatch = Match(
            id = "live_1",
            tournamentName = "US Open",
            roundName = "Semi-Final",
            state = MatchState.LIVE,
            statusDetail = "Set 2 • 3-2",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )
        val liveCount = repo.getH2HSummary(liveMatch).getOrNull()!!.matches.size

        val finishedMatch = Match(
            id = "fin_1",
            tournamentName = "US Open",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val result = repo.getH2HSummary(finishedMatch)
        assertTrue(result.isSuccess)
        val summary = result.getOrNull()!!
        assertTrue("First match must be the current finished match", summary.matches.first().isCurrentMatch)
        assertEquals("Carlos Alcaraz", summary.matches.first().winnerName)
        assertTrue("Finished match summary must include historical matches plus current match", summary.matches.size >= liveCount)
    }

    @Test
    fun testSurfaceTotalsMatchIndividualWins() = runBlocking {
        val repo = H2HRepository()
        val match = Match(
            id = "tot_1",
            tournamentName = "US Open",
            roundName = "Final",
            state = MatchState.UPCOMING,
            statusDetail = "Tonight",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val result = repo.getH2HSummary(match)
        assertTrue(result.isSuccess)
        val summary = result.getOrNull()!!

        val totalP1 = summary.hardWinsP1 + summary.clayWinsP1 + summary.grassWinsP1
        val totalP2 = summary.hardWinsP2 + summary.clayWinsP2 + summary.grassWinsP2
        assertEquals(summary.p1Wins, totalP1)
        assertEquals(summary.p2Wins, totalP2)
    }

    @Test
    fun testNetworkFailureGracefulFallback() = runBlocking {
        // Provide client configured with immediate timeout
        val brokenClient = OkHttpClient.Builder()
            .connectTimeout(50, TimeUnit.MILLISECONDS)
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .build()
        val repo = H2HRepository(client = brokenClient)

        val match = Match(
            id = "offline_1",
            tournamentName = "US Open",
            roundName = "Round 4",
            state = MatchState.UPCOMING,
            statusDetail = "Scheduled",
            player1 = Competitor(id = "p1", name = "Test Player A"),
            player2 = Competitor(id = "p2", name = "Test Player B")
        )

        val result = repo.getH2HSummary(match)
        assertTrue("Repository must succeed even if network fails", result.isSuccess)
        val summary = result.getOrNull()
        assertNotNull(summary)
        assertTrue("Fallback matches should be provided", summary!!.matches.isNotEmpty())
    }

    @Test
    fun testParseFlashscoreH2HParsesRealEncountersAndSurfaceCounts() {
        val repo = H2HRepository()
        val match = Match(
            id = "zverev_deminaur",
            tournamentName = "Erste Bank Open",
            roundName = "Quarter-Final",
            state = MatchState.UPCOMING,
            statusDetail = "Scheduled",
            player1 = Competitor(id = "z1", name = "Alexander Zverev"),
            player2 = Competitor(id = "d1", name = "Alex de Minaur")
        )
        val sampleH2HFeed = buildString {
            append("SA÷2¬~KA÷All surfaces¬IS÷¬~KB÷Last matches: Zverev A.¬~KB÷Head-to-head matches¬")
            append("~KC÷1792059000¬KP÷ERN0r0Qp¬KD÷hard¬KE÷hard¬KF÷Shanghai¬KH÷China¬KJ÷*Zverev A.¬FH÷Zverev A.¬KK÷De Minaur A.¬FK÷De Minaur A.¬KL÷2:0¬")
            append("~KC÷1717612200¬KP÷ncEBhG3n¬KD÷clay¬KE÷clay¬KF÷French Open¬KH÷France¬KJ÷*Zverev A.¬FH÷Zverev A.¬KK÷De Minaur A.¬FK÷De Minaur A.¬KL÷3:0¬")
            append("~KC÷1704520000¬KP÷uCup1234¬KD÷hard¬KE÷hard¬KF÷United Cup¬KH÷Australia¬KJ÷Zverev A.¬FH÷Zverev A.¬KK÷*De Minaur A.¬FK÷De Minaur A.¬KL÷1:2¬")
            append("~KA÷Clay¬IS÷¬")
        }

        val summary = repo.parseFlashscoreH2H(sampleH2HFeed, match)
        assertNotNull(summary)
        assertEquals(3, summary!!.matches.size)
        assertEquals(2, summary.p1Wins)
        assertEquals(1, summary.p2Wins)
        assertEquals(1, summary.hardWinsP1)
        assertEquals(1, summary.hardWinsP2)
        assertEquals(1, summary.clayWinsP1)
        assertEquals(1, summary.grandSlamWinsP1)
    }
}

