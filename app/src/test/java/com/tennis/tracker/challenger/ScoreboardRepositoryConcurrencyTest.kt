package com.tennis.tracker.challenger

import com.tennis.tracker.data.local.MatchCacheManager
import com.tennis.tracker.data.remote.EspnScoreboardApi
import com.tennis.tracker.data.remote.TennisStatsApi
import com.tennis.tracker.data.repository.ScoreboardRepositoryImpl
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.TourType
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.Tournaments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2 Concurrency Stress Test Suite.
 *
 * Stresses ScoreboardRepositoryImpl and MatchCacheManager under heavy multi-threaded
 * coroutine workloads (50+ coroutines simultaneously), checking:
 * 1. Thread safety and zero exceptions under 50 simultaneous calls.
 * 2. Cross-tournament cache clobbering behavior when different tournaments are fetched concurrently.
 * 3. Concurrent read/write race conditions on MatchCacheManager.
 * 4. Active tournament state consistency under concurrent updates.
 */
class ScoreboardRepositoryConcurrencyTest {

    private val testTournaments = listOf(
        Tournaments.US_OPEN,
        Tournaments.AUSTRALIAN_OPEN,
        Tournaments.INDIAN_WELLS,
        Tournaments.ROLAND_GARROS,
        Tournaments.WIMBLEDON
    )

    private fun createMockMatch(id: String, tournament: Tournament, state: MatchState = MatchState.LIVE): Match {
        return Match(
            id = id,
            tournamentName = tournament.displayName,
            eventGrouping = "Men's Singles",
            roundName = "Round 1",
            state = state,
            statusDetail = "In Progress",
            player1 = Competitor(id = "${id}_p1", name = "Player One", isServing = true, setScores = listOf(6, 4)),
            player2 = Competitor(id = "${id}_p2", name = "Player Two", isServing = false, setScores = listOf(3, 6)),
            venue = tournament.defaultVenue,
            tournament = tournament,
            tour = tournament.tour,
            category = tournament.category,
            surface = tournament.surface
        )
    }

    private class MockEspnApi(private val matchesByTournament: Map<Tournament, List<Match>>) : EspnScoreboardApi() {
        val callCount = AtomicInteger(0)

        override fun fetchScoreboard(url: String, tournament: Tournament?): Result<List<Match>> {
            callCount.incrementAndGet()
            Thread.sleep(5) // Simulate realistic I/O latency to maximize race condition window
            if (tournament == null || tournament == Tournaments.ALL) {
                return Result.success(matchesByTournament.values.flatten())
            }
            val matches = matchesByTournament[tournament] ?: emptyList()
            return Result.success(matches)
        }
    }

    private class MockStatsApi : TennisStatsApi() {
        override fun fetchLiveGameScores(matches: List<Match>): Map<String, Pair<String, String>> {
            Thread.sleep(2)
            return matches.associate { it.id to Pair("40", "30") }
        }
    }

    private lateinit var mockDataset: Map<Tournament, List<Match>>
    private lateinit var mockApi: MockEspnApi
    private lateinit var mockStatsApi: MockStatsApi
    private lateinit var repository: ScoreboardRepositoryImpl

    @Before
    fun setUp() {
        MatchCacheManager.clearCache(null)
        mockDataset = testTournaments.associateWith { tourney ->
            (1..10).map { i -> createMockMatch("${tourney.id}_m$i", tourney) }
        }
        mockApi = MockEspnApi(mockDataset)
        mockStatsApi = MockStatsApi()
        repository = ScoreboardRepositoryImpl(
            context = null,
            api = mockApi,
            statsApi = mockStatsApi
        )
    }

    @After
    fun tearDown() {
        MatchCacheManager.clearCache(null)
    }

    // =========================================================================
    // Test 1: 50 Simultaneous Coroutines on Same Tournament
    // =========================================================================
    @Test
    fun testConcurrency_50SimultaneousCoroutines_sameTournament() = runBlocking(Dispatchers.IO) {
        val concurrency = 50
        val targetTournament = Tournaments.US_OPEN

        val deferredResults = (1..concurrency).map {
            async {
                repository.getScoreboardForTournament(targetTournament, forceRefresh = true)
            }
        }

        val results = deferredResults.awaitAll()
        assertEquals(50, results.size)

        for (res in results) {
            assertTrue("Every coroutine must return success", res.isSuccess)
            val matches = res.getOrNull()
            assertNotNull(matches)
            assertEquals(10, matches!!.size)
            assertTrue(matches.all { it.tournamentName == targetTournament.displayName })
        }
    }

    // =========================================================================
    // Test 2: 50 Simultaneous Coroutines across 5 Different Tournaments
    // =========================================================================
    @Test
    fun testConcurrency_50SimultaneousCoroutines_mixedTournaments() = runBlocking(Dispatchers.IO) {
        val concurrency = 50
        val deferredResults = (0 until concurrency).map { idx ->
            val tourney = testTournaments[idx % testTournaments.size]
            async {
                val res = repository.getScoreboardForTournament(tourney, forceRefresh = true)
                Pair(tourney, res)
            }
        }

        val results = deferredResults.awaitAll()
        assertEquals(50, results.size)

        for ((expectedTourney, res) in results) {
            assertTrue("Coroutine for ${expectedTourney.displayName} must succeed", res.isSuccess)
            val matches = res.getOrNull()
            assertNotNull(matches)
            assertEquals(10, matches!!.size)
            assertTrue(
                "Matches must belong to ${expectedTourney.displayName}",
                matches.all { it.tournamentName == expectedTourney.displayName }
            )
        }
    }

    // =========================================================================
    // Test 3: Cache Clobbering Empirical Vulnerability Probe
    // When multiple tournaments are fetched, does MatchCacheManager clobber the
    // global cache because saveMatches() only receives scoped matches?
    // =========================================================================
    @Test
    fun testCacheClobbering_scopedSavesOverwriteGlobalCache() = runBlocking(Dispatchers.IO) {
        // Step 1: Fetch US Open matches
        val usOpenResult = repository.getScoreboardForTournament(Tournaments.US_OPEN, forceRefresh = true)
        assertTrue(usOpenResult.isSuccess)

        val cachedAfterUsOpen = MatchCacheManager.loadMatches(null)
        assertEquals(10, cachedAfterUsOpen.size)
        assertTrue(cachedAfterUsOpen.all { it.tournamentName == Tournaments.US_OPEN.displayName })

        // Step 2: Fetch Australian Open matches
        val aoResult = repository.getScoreboardForTournament(Tournaments.AUSTRALIAN_OPEN, forceRefresh = true)
        assertTrue(aoResult.isSuccess)

        // Step 3: Probe MatchCacheManager: What is in the cache now?
        val cachedAfterAo = MatchCacheManager.loadMatches(null)

        // EMPIRICAL OBSERVATION: Does the cache have 20 matches (US Open + AO) or only 10 (AO)?
        val hasUsOpen = cachedAfterAo.any { it.tournamentName == Tournaments.US_OPEN.displayName }
        val hasAo = cachedAfterAo.any { it.tournamentName == Tournaments.AUSTRALIAN_OPEN.displayName }

        println("CACHE CLOBBERING PROBE: Cache size = ${cachedAfterAo.size}, hasUsOpen = $hasUsOpen, hasAo = $hasAo")

        // Offline scoreboard for US Open should ideally be available:
        val offlineUsOpen = repository.getOfflineScoreboardForTournament(Tournaments.US_OPEN)
        println("OFFLINE US OPEN COUNT: ${offlineUsOpen.size}")
    }

    // =========================================================================
    // Test 4: Concurrent Active Tournament Mutation and Scoreboard Fetch
    // =========================================================================
    @Test
    fun testConcurrency_activeTournamentMutationDuringFetch() = runBlocking(Dispatchers.IO) {
        val jobs = (1..30).map { idx ->
            async {
                val tourney = testTournaments[idx % testTournaments.size]
                repository.setActiveTournament(tourney)
                assertEquals(tourney, repository.getActiveTournament())
                repository.getScoreboard(forceRefresh = true)
            }
        }
        val results = jobs.awaitAll()
        assertEquals(30, results.size)
        assertTrue(results.all { it.isSuccess })
    }
}
