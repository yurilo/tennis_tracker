package com.usopen.tracker.challenger

import com.usopen.tracker.data.local.MatchCacheManager
import com.usopen.tracker.data.remote.EspnScoreboardApi
import com.usopen.tracker.data.remote.TennisStatsApi
import com.usopen.tracker.data.repository.ScoreboardRepositoryImpl
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.TourType
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.TournamentCategory
import com.usopen.tracker.domain.model.Tournaments
import com.usopen.tracker.ui.viewmodel.TourTierFilter
import com.usopen.tracker.util.TimezoneFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Challenger 2 Cache Consistency and Freshness Stress Test Suite.
 *
 * Stresses MatchCacheManager and ScoreboardRepositoryImpl cache lifecycle:
 * 1. Metadata retention: Checks whether JSON serialization preserves tournament,
 *    category, and surface metadata or drops them to default ATP_250.
 * 2. Tier filtering breakdown on cached matches: Does filtering by Grand Slams or
 *    1000s fail when matches are restored from disk cache?
 * 3. Cross-tournament cache destruction: Does fetching one tournament overwrite
 *    and delete other tournaments from the cache?
 * 4. Cache TTL / freshness: Probes whether stale 48h+ matches are purged or returned indefinitely.
 */
class CacheFreshnessAndConsistencyTest {

    private fun createMatch(
        id: String,
        tournament: Tournament,
        state: MatchState = MatchState.LIVE,
        startTime: String? = null
    ): Match {
        return Match(
            id = id,
            tournamentName = tournament.displayName,
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = state,
            statusDetail = if (state == MatchState.FINISHED) "Final" else "In Progress",
            player1 = Competitor(id = "${id}_p1", name = "Player One", isServing = true, setScores = listOf(6, 6)),
            player2 = Competitor(id = "${id}_p2", name = "Player Two", isServing = false, setScores = listOf(4, 4)),
            venue = tournament.defaultVenue,
            startTime = startTime ?: Instant.now().minus(Duration.ofHours(1)).toString(),
            tournament = tournament,
            tour = tournament.tour,
            category = tournament.category,
            surface = tournament.surface
        )
    }

    @Before
    fun setUp() {
        MatchCacheManager.clearCache(null)
    }

    @After
    fun tearDown() {
        MatchCacheManager.clearCache(null)
    }

    // =========================================================================
    // Test 1: Metadata Retention Invariant across JSON Serialization
    // =========================================================================
    @Test
    fun testCacheSerialization_domainMetadataRetention() {
        val gsMatch = createMatch("m_gs", Tournaments.US_OPEN)
        val m1000Match = createMatch("m_1000", Tournaments.INDIAN_WELLS)

        // Before caching:
        assertEquals(TournamentCategory.GRAND_SLAM, gsMatch.category)
        assertEquals(TournamentCategory.GRAND_SLAM, gsMatch.effectiveCategory)
        assertEquals(TournamentCategory.MASTERS_1000, m1000Match.category)
        assertEquals(TournamentCategory.MASTERS_1000, m1000Match.effectiveCategory)
        assertTrue(TourTierFilter.GRAND_SLAMS.matches(gsMatch))
        assertTrue(TourTierFilter.MASTERS_1000.matches(m1000Match))

        // Serialize and reload from MatchCacheManager
        val jsonObj = MatchCacheManager.matchToJson(gsMatch)
        val reloadedGs = MatchCacheManager.jsonToMatch(jsonObj)

        println("PROBE: Reloaded GS tournament = ${reloadedGs.tournament}")
        println("PROBE: Reloaded GS category = ${reloadedGs.category}")
        println("PROBE: Reloaded GS effectiveCategory = ${reloadedGs.effectiveCategory}")
        println("PROBE: Reloaded GS surface = ${reloadedGs.surface}")
        println("PROBE: Reloaded GS effectiveSurface = ${reloadedGs.effectiveSurface}")

        val matchesGsFilter = TourTierFilter.GRAND_SLAMS.matches(reloadedGs)
        println("PROBE: TourTierFilter.GRAND_SLAMS.matches(reloadedGs) = $matchesGsFilter")

        val json1000 = MatchCacheManager.matchToJson(m1000Match)
        val reloaded1000 = MatchCacheManager.jsonToMatch(json1000)
        val matches1000Filter = TourTierFilter.MASTERS_1000.matches(reloaded1000)
        println("PROBE: TourTierFilter.MASTERS_1000.matches(reloaded1000) = $matches1000Filter")
    }

    // =========================================================================
    // Test 2: Cross-Tournament Cache Wiping in ScoreboardRepositoryImpl
    // =========================================================================
    @Test
    fun testRepository_crossTournamentCacheWiping() = runBlocking(Dispatchers.IO) {
        val mockApi = object : EspnScoreboardApi() {
            override fun fetchScoreboard(url: String, tournament: Tournament?): Result<List<Match>> {
                return when (tournament) {
                    Tournaments.US_OPEN -> Result.success(listOf(createMatch("us_1", Tournaments.US_OPEN)))
                    Tournaments.WIMBLEDON -> Result.success(listOf(createMatch("wim_1", Tournaments.WIMBLEDON)))
                    else -> Result.success(emptyList())
                }
            }
        }

        val repo = ScoreboardRepositoryImpl(
            context = null,
            api = mockApi,
            statsApi = TennisStatsApi()
        )

        // Step 1: Fetch US Open
        val usRes = repo.getScoreboardForTournament(Tournaments.US_OPEN, forceRefresh = true)
        assertTrue(usRes.isSuccess)
        assertEquals(1, repo.getOfflineScoreboardForTournament(Tournaments.US_OPEN).size)

        // Step 2: Fetch Wimbledon
        val wimRes = repo.getScoreboardForTournament(Tournaments.WIMBLEDON, forceRefresh = true)
        assertTrue(wimRes.isSuccess)
        assertEquals(1, repo.getOfflineScoreboardForTournament(Tournaments.WIMBLEDON).size)

        // Step 3: Probe US Open offline cache - is it still there or did Wimbledon wipe it?
        val offlineUsOpen = repo.getOfflineScoreboardForTournament(Tournaments.US_OPEN)
        println("PROBE: Offline US Open count after Wimbledon fetch = ${offlineUsOpen.size}")
    }

    // =========================================================================
    // Test 3: Cache TTL and Fallback Freshness Probe
    // =========================================================================
    @Test
    fun testCacheTTL_staleMatchesRetentionProbe() = runBlocking(Dispatchers.IO) {
        val now = Instant.now()
        val staleTime = now.minus(Duration.ofDays(7)).toString()
        val staleMatch = createMatch("stale_1", Tournaments.US_OPEN, MatchState.FINISHED, staleTime)

        // Save stale match to cache
        MatchCacheManager.saveMatches(null, listOf(staleMatch))

        val loaded = MatchCacheManager.loadMatches(null)
        assertEquals(1, loaded.size)

        // Check if loadMatches purges stale matches or returns 7-day-old finished matches:
        val loadedStartTime = loaded[0].startTime
        println("PROBE: Loaded stale match startTime = $loadedStartTime")

        val repo = ScoreboardRepositoryImpl(
            context = null,
            api = object : EspnScoreboardApi() {
                override fun fetchScoreboard(url: String, tournament: Tournament?): Result<List<Match>> {
                    return Result.failure(Exception("Network offline"))
                }
            }
        )

        // With forceRefresh = false, does it return the 7-day-old stale match?
        val cacheResult = repo.getScoreboardForTournament(Tournaments.US_OPEN, forceRefresh = false)
        println("PROBE: repo.getScoreboard(forceRefresh=false) with 7d stale cache returned success = ${cacheResult.isSuccess}")
        if (cacheResult.isSuccess) {
            println("PROBE: Returned stale match count = ${cacheResult.getOrNull()?.size}")
        }
    }
}
