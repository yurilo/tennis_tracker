package com.usopen.tracker.ui

import com.usopen.tracker.data.local.MockStatsData
import com.usopen.tracker.data.local.MockTournamentData
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.MatchStatistics
import com.usopen.tracker.domain.repository.ScoreboardRepository
import com.usopen.tracker.ui.viewmodel.MatchFilter
import com.usopen.tracker.ui.viewmodel.ScoreboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardViewModelTest {

    @get:org.junit.Rule
    val globalTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(10)

    private val testDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<ScoreboardViewModel>()

    private val fakeRepository = object : ScoreboardRepository {
        override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> {
            return Result.success(MockTournamentData.matches)
        }
        override fun getOfflineScoreboard(): List<Match> = MockTournamentData.matches
        override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> {
            return Result.success(MockStatsData.generate(match))
        }
    }

    private fun createViewModel(
        repository: ScoreboardRepository = fakeRepository,
        h2hRepository: com.usopen.tracker.data.repository.H2HRepository = com.usopen.tracker.data.repository.H2HRepository(),
        followManager: com.usopen.tracker.data.local.FollowManager? = null,
        enableAutoRefresh: Boolean = false
    ): ScoreboardViewModel {
        val vm = ScoreboardViewModel(repository, h2hRepository, followManager, enableAutoRefresh)
        createdViewModels.add(vm)
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        for (vm in createdViewModels) {
            vm.cleanup()
        }
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `loadMatches updates state with matches and live count`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.allMatches.isEmpty())

        val expectedLive = MockTournamentData.matches.count { it.state == MatchState.LIVE }
        assertEquals(expectedLive, state.liveCount)
    }

    @Test
    fun `setFilter correctly filters matches`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Test LIVE filter
        viewModel.setFilter(MatchFilter.LIVE)
        var state = viewModel.uiState.value
        assertEquals(MatchFilter.LIVE, state.selectedFilter)
        assertTrue(state.filteredMatches.all { it.state == MatchState.LIVE })

        // Test UPCOMING filter
        viewModel.setFilter(MatchFilter.UPCOMING)
        state = viewModel.uiState.value
        assertEquals(MatchFilter.UPCOMING, state.selectedFilter)
        assertTrue(state.filteredMatches.all { it.state == MatchState.UPCOMING })

        // Test FINISHED filter
        viewModel.setFilter(MatchFilter.FINISHED)
        state = viewModel.uiState.value
        assertEquals(MatchFilter.FINISHED, state.selectedFilter)
        assertTrue(state.filteredMatches.all { it.state == MatchState.FINISHED })

        // Test ALL filter
        viewModel.setFilter(MatchFilter.ALL)
        state = viewModel.uiState.value
        assertEquals(state.allMatches.size, state.filteredMatches.size)
    }

    @Test
    fun `autoRefresh periodically reloads scoreboard every minute`() = runTest(testDispatcher) {
        var callCount = 0
        val countingRepo = object : ScoreboardRepository {
            override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> {
                callCount++
                return Result.success(MockTournamentData.matches)
            }
            override fun getOfflineScoreboard(): List<Match> = MockTournamentData.matches
            override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> {
                return Result.success(MockStatsData.generate(match))
            }
        }

        val viewModel = createViewModel(repository = countingRepo, enableAutoRefresh = true)
        try {
            testDispatcher.scheduler.advanceTimeBy(1)
            // initial loadMatches (1) + immediate background refreshSilently (2)
            assertEquals(2, callCount)

            // 1st 60s periodic tick
            testDispatcher.scheduler.advanceTimeBy(60_000)
            assertEquals(3, callCount)

            // 2nd 60s periodic tick
            testDispatcher.scheduler.advanceTimeBy(60_000)
            assertEquals(4, callCount)
        } finally {
            viewModel.stopAutoRefresh()
        }
    }

    @Test
    fun `onSearchQueryChange filters by player name case-insensitively`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Search for Navarro in lowercase
        viewModel.onSearchQueryChange("navarro")
        var state = viewModel.uiState.value
        assertEquals("navarro", state.searchQuery)
        assertTrue(state.filteredMatches.isNotEmpty())
        assertTrue(state.filteredMatches.all {
            it.player1.name.contains("Navarro", ignoreCase = true) ||
            it.player2.name.contains("Navarro", ignoreCase = true)
        })

        // Search for Muchova in uppercase
        viewModel.onSearchQueryChange("MUCHOVA")
        state = viewModel.uiState.value
        assertTrue(state.filteredMatches.isNotEmpty())
        assertTrue(state.filteredMatches.all {
            it.player1.name.contains("Muchova", ignoreCase = true) ||
            it.player2.name.contains("Muchova", ignoreCase = true)
        })
    }

    @Test
    fun `onSearchQueryChange filters by country code`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("CZE")
        val state = viewModel.uiState.value
        assertTrue(state.filteredMatches.isNotEmpty())
        assertTrue(state.filteredMatches.all {
            it.player1.country?.contains("CZE", ignoreCase = true) == true ||
            it.player2.country?.contains("CZE", ignoreCase = true) == true
        })
    }

    @Test
    fun `onSearchQueryChange filters by venue`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("Arthur Ashe")
        val state = viewModel.uiState.value
        assertTrue(state.filteredMatches.isNotEmpty())
        assertTrue(state.filteredMatches.all {
            it.venue.contains("Arthur Ashe", ignoreCase = true)
        })
    }

    @Test
    fun `onSearchQueryChange filters by doubles roster and event grouping`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Test doubles team search
        viewModel.onSearchQueryChange("Salisbury")
        var state = viewModel.uiState.value
        assertTrue(state.filteredMatches.isNotEmpty())
        assertTrue(state.filteredMatches.all {
            it.player1.name.contains("Salisbury", ignoreCase = true) ||
            it.player2.name.contains("Salisbury", ignoreCase = true)
        })

        // Test grouping search
        viewModel.onSearchQueryChange("Men's Doubles")
        state = viewModel.uiState.value
        assertTrue(state.filteredMatches.isNotEmpty())
        assertTrue(state.filteredMatches.all {
            it.eventGrouping.contains("Men's Doubles", ignoreCase = true)
        })
    }

    @Test
    fun `search query works in combination with match filter`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setFilter(MatchFilter.LIVE)
        viewModel.onSearchQueryChange("Navarro")
        val state = viewModel.uiState.value
        assertTrue(state.filteredMatches.isNotEmpty())
        assertTrue(state.filteredMatches.all {
            it.state == MatchState.LIVE &&
            (it.player1.name.contains("Navarro", ignoreCase = true) ||
             it.player2.name.contains("Navarro", ignoreCase = true))
        })

        // When searching for an upcoming match while LIVE filter is selected, result is empty
        viewModel.onSearchQueryChange("Tsitsipas")
        val emptyState = viewModel.uiState.value
        assertTrue(emptyState.filteredMatches.isEmpty())

        // Switching to UPCOMING filter reveals the match
        viewModel.setFilter(MatchFilter.UPCOMING)
        val upcomingState = viewModel.uiState.value
        assertTrue(upcomingState.filteredMatches.isNotEmpty())
    }

    @Test
    fun `clearSearchQuery resets query and restores filtered matches`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("Navarro")
        assertEquals("Navarro", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.filteredMatches.size < viewModel.uiState.value.allMatches.size)

        viewModel.clearSearchQuery()
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(viewModel.uiState.value.allMatches.size, viewModel.uiState.value.filteredMatches.size)
    }

    @Test
    fun `search with nonexistent query returns empty list`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSearchQueryChange("nonexistent_player_xyz_123")
        val state = viewModel.uiState.value
        assertTrue(state.filteredMatches.isEmpty())
        assertFalse(state.allMatches.isEmpty())
    }

    @Test
    fun `openMatchStats loads statistics and updates statsUiState`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val match = MockTournamentData.matches.first()
        viewModel.openMatchStats(match)

        // Selected match should immediately be set
        assertEquals(match, viewModel.selectedMatchForStats.value)

        // Advance dispatcher to complete async repository call
        testDispatcher.scheduler.advanceUntilIdle()

        val statsState = viewModel.statsUiState.value
        assertFalse(statsState.isLoading)
        assertNotNull("Stats should be loaded", statsState.stats)
        assertEquals(match.id, statsState.stats!!.matchId)
        assertNull(statsState.errorMessage)
        assertEquals(0, statsState.selectedPeriodIndex)
    }

    @Test
    fun `closeMatchStats clears selectedMatch and resets statsUiState`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val match = MockTournamentData.matches.first()
        viewModel.openMatchStats(match)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.selectedMatchForStats.value)
        assertNotNull(viewModel.statsUiState.value.stats)

        viewModel.closeMatchStats()
        assertNull(viewModel.selectedMatchForStats.value)
        assertNull(viewModel.statsUiState.value.stats)
        assertFalse(viewModel.statsUiState.value.isLoading)
    }

    @Test
    fun `selectStatsPeriod updates selectedPeriodIndex`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val match = MockTournamentData.matches.first()
        viewModel.openMatchStats(match)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectStatsPeriod(2)
        assertEquals(2, viewModel.statsUiState.value.selectedPeriodIndex)
    }

    @Test
    fun `refreshMatchStats reloads statistics for active match`() = runTest(testDispatcher) {
        var statCalls = 0
        val trackingRepo = object : ScoreboardRepository {
            override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> =
                Result.success(MockTournamentData.matches)
            override fun getOfflineScoreboard(): List<Match> = MockTournamentData.matches
            override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> {
                statCalls++
                return Result.success(MockStatsData.generate(match))
            }
        }

        val viewModel = createViewModel(repository = trackingRepo, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val match = MockTournamentData.matches.first()
        viewModel.openMatchStats(match)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, statCalls)

        viewModel.refreshMatchStats()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, statCalls)
    }

    @Test
    fun `setFollowedMatches updates state and filter`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val allMatches = MockTournamentData.matches
        val targetMatch = allMatches.first()

        assertFalse(viewModel.isMatchFollowed(targetMatch.id))
        assertEquals(0, viewModel.uiState.value.followedCount)

        viewModel.setFollowedMatches(setOf(targetMatch.id))
        assertTrue(viewModel.isMatchFollowed(targetMatch.id))
        assertEquals(1, viewModel.uiState.value.followedCount)

        // Select FOLLOWED filter
        viewModel.setFilter(MatchFilter.FOLLOWED)
        val state = viewModel.uiState.value
        assertEquals(1, state.filteredMatches.size)
        assertEquals(targetMatch.id, state.filteredMatches.first().id)

        // Unfollow match
        viewModel.setFollowedMatches(emptySet())
        assertFalse(viewModel.isMatchFollowed(targetMatch.id))
        assertEquals(0, viewModel.uiState.value.followedCount)
        assertTrue(viewModel.uiState.value.filteredMatches.isEmpty())
    }

    @Test
    fun `FOLLOWED filter with search query narrows results correctly`() = runTest(testDispatcher) {
        val viewModel = createViewModel(repository = fakeRepository, enableAutoRefresh = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val match1 = MockTournamentData.matches[0] // e.g. Alcaraz vs Sinner
        val match2 = MockTournamentData.matches[1]

        viewModel.setFollowedMatches(setOf(match1.id, match2.id))
        viewModel.setFilter(MatchFilter.FOLLOWED)

        assertEquals(2, viewModel.uiState.value.filteredMatches.size)

        // Search for player from match 1
        viewModel.onSearchQueryChange(match1.player1.name)
        val filtered = viewModel.uiState.value.filteredMatches
        assertEquals(1, filtered.size)
        assertEquals(match1.id, filtered.first().id)
    }

    @Test
    fun `loadMatches triggers cleanup of expired followed matches when followManager is present`() = runTest(testDispatcher) {
        val fakePrefs = com.usopen.tracker.data.local.FakeSharedPreferences()
        val followManager = com.usopen.tracker.data.local.FollowManager(context = null, preferences = fakePrefs)
        val finishedMatch = MockTournamentData.matches.first { it.state == MatchState.FINISHED }

        // Follow the finished match and mark it completed 25 hours ago
        followManager.followMatch(null, finishedMatch.id)
        val now = System.currentTimeMillis()
        fakePrefs.edit().putLong(com.usopen.tracker.data.local.FollowManager.KEY_COMPLETED_PREFIX + finishedMatch.id, now - (25 * 3600 * 1000L)).apply()
        assertTrue(followManager.isFollowed(finishedMatch.id))

        val viewModel = createViewModel(
            repository = fakeRepository,
            followManager = followManager,
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Match should be auto-expired and removed from followed set
        assertFalse(viewModel.isMatchFollowed(finishedMatch.id))
        assertFalse(followManager.isFollowed(finishedMatch.id))
    }
}

