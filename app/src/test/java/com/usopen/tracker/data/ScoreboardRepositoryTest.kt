package com.usopen.tracker.data

import com.usopen.tracker.data.local.MatchCacheManager
import com.usopen.tracker.data.local.MockTournamentData
import com.usopen.tracker.data.remote.EspnScoreboardApi
import com.usopen.tracker.data.remote.EspnWatchApi
import com.usopen.tracker.data.remote.EspnWatchStream
import com.usopen.tracker.data.remote.TennisStatsApi
import com.usopen.tracker.data.repository.ScoreboardRepositoryImpl
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.MatchStatistics
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class ScoreboardRepositoryTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private fun createRepository(): ScoreboardRepositoryImpl {
        val dummyApi = object : EspnScoreboardApi() {
            override fun fetchScoreboard(url: String): Result<List<Match>> =
                Result.failure(java.io.IOException("Network calls disabled in unit test"))
        }
        val dummyStatsApi = object : TennisStatsApi() {
            override fun fetchStatistics(match: Match): Result<MatchStatistics> {
                if (match.state == MatchState.UPCOMING) {
                    return Result.failure(NoSuchElementException("Match statistics will become available once the match begins."))
                }
                return Result.failure(java.io.IOException("Network calls disabled in unit test"))
            }
        }
        val dummyWatchApi = object : EspnWatchApi() {
            override fun fetchWatchStreams(url: String): Result<List<EspnWatchStream>> =
                Result.success(emptyList())
        }
        return ScoreboardRepositoryImpl(
            context = null,
            api = dummyApi,
            statsApi = dummyStatsApi,
            watchApi = dummyWatchApi
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

    @Test
    fun `getOfflineScoreboard should return cached tournament data when cache is populated`() {
        MatchCacheManager.saveMatches(null, MockTournamentData.matches)
        val repository = createRepository()
        val matches = repository.getOfflineScoreboard()

        assertFalse("Matches list should not be empty", matches.isEmpty())

        val liveMatches = matches.filter { it.state == MatchState.LIVE }
        val upcomingMatches = matches.filter { it.state == MatchState.UPCOMING }
        val finishedMatches = matches.filter { it.state == MatchState.FINISHED }

        assertTrue("Should contain live matches", liveMatches.isNotEmpty())
        assertTrue("Should contain upcoming matches", upcomingMatches.isNotEmpty())
        assertTrue("Should contain finished matches", finishedMatches.isNotEmpty())

        for (match in matches) {
            assertNotNull("Each match must have player1", match.player1)
            assertNotNull("Each match must have player2", match.player2)
            assertNotNull("Each match must have a DeepLink", match.deepLink)
            assertTrue("DeepLink must have a valid app URI", match.deepLink!!.appUri.isNotEmpty())
            assertTrue("DeepLink must have a valid web fallback", match.deepLink!!.webFallbackUrl.isNotEmpty())
        }
    }

    @Test
    fun `getScoreboard should return cached matches when available without network forceRefresh`() = runBlocking {
        MatchCacheManager.saveMatches(null, MockTournamentData.matches)
        val repository = createRepository()
        val result = repository.getScoreboard(forceRefresh = false)

        assertTrue("Result should be successful (with cache)", result.isSuccess)
        val matches = result.getOrNull()
        assertNotNull(matches)
        assertFalse(matches!!.isEmpty())
    }

    @Test
    fun `getMatchStatistics should fail gracefully for upcoming match without returning fake stats`() = runBlocking {
        val repository = createRepository()
        val upcomingMatch = MockTournamentData.matches.first { it.state == MatchState.UPCOMING }
        val result = repository.getMatchStatistics(upcomingMatch)

        assertTrue("Result should fail for upcoming match without stats", result.isFailure)
    }
}
