package com.tennis.tracker.ui.viewmodel

import com.tennis.tracker.data.local.FakeSharedPreferences
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.domain.repository.ScoreboardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated integration test suite for [ScoreboardViewModel] settings integration.
 *
 * Verifies:
 * 1. Startup tournament tier initialization honoring saved [SettingsRepository] preferences
 *    (Grand Slams, ATP Tour, WTA Tour, All Tournaments).
 * 2. Dynamic auto-refresh interval updates adjusting or stopping/restarting periodic coroutine polling
 *    jobs using [TestDispatcher].
 * 3. ViewModel hoisted preference mutations updating the underlying repository and StateFlow.
 * 4. Proper coroutine lifecycle termination upon [ScoreboardViewModel.cleanup].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoreboardViewModelSettingsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var settingsRepository: SettingsRepositoryImpl
    private lateinit var fakeScoreboardRepo: FakeScoreboardRepository

    class FakeScoreboardRepository : ScoreboardRepository {
        private var activeTournament: Tournament = Tournaments.ALL
        val initialLoadCalls = AtomicInteger(0)
        val forceRefreshCalls = AtomicInteger(0)

        override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> {
            if (forceRefresh) {
                forceRefreshCalls.incrementAndGet()
            } else {
                initialLoadCalls.incrementAndGet()
            }
            return Result.success(emptyList())
        }

        override fun getOfflineScoreboard(): List<Match> = emptyList()

        override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> {
            return Result.failure(NoSuchElementException("No stats in test"))
        }

        override fun getActiveTournament(): Tournament = activeTournament

        override fun setActiveTournament(tournament: Tournament) {
            this.activeTournament = tournament
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        SettingsRepositoryImpl.resetInstanceForTesting()
        fakePrefs = FakeSharedPreferences()
        settingsRepository = SettingsRepositoryImpl(fakePrefs)
        fakeScoreboardRepo = FakeScoreboardRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SettingsRepositoryImpl.resetInstanceForTesting()
    }

    // =========================================================================
    // 1. Startup Tournament Tier Preference Integration
    // =========================================================================

    @Test
    fun testStartupTournamentTierDefaultsToAllTournaments() = runTest {
        // AppSettings default is StartupTournamentTier.ALL
        assertEquals(StartupTournamentTier.ALL, settingsRepository.getSettings().defaultTournament)

        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)
            assertEquals("all", vm.uiState.value.currentTournament.id.lowercase())
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testStartupTournamentTierAppliesGrandSlamsPreference() = runTest {
        settingsRepository.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
        assertEquals(StartupTournamentTier.GRAND_SLAMS, settingsRepository.getSettings().defaultTournament)

        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            assertEquals(TourTierFilter.GRAND_SLAMS, vm.uiState.value.selectedTierFilter)
            assertEquals(TournamentCategory.GRAND_SLAM, vm.uiState.value.currentTournament.category)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testStartupTournamentTierAppliesAtpTourPreference() = runTest {
        settingsRepository.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        assertEquals(StartupTournamentTier.ATP_TOUR, settingsRepository.getSettings().defaultTournament)

        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            assertEquals(TourTierFilter.ATP_TOUR, vm.uiState.value.selectedTierFilter)
            assertTrue(vm.uiState.value.currentTournament.tour.isAtp)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testStartupTournamentTierAppliesWtaTourPreference() = runTest {
        settingsRepository.updateDefaultTournament(StartupTournamentTier.WTA_TOUR)
        assertEquals(StartupTournamentTier.WTA_TOUR, settingsRepository.getSettings().defaultTournament)

        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            assertEquals(TourTierFilter.WTA_TOUR, vm.uiState.value.selectedTierFilter)
            assertTrue(vm.uiState.value.currentTournament.tour.isWta)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testStartupTournamentTierRestoredFromColdPersistence() = runTest {
        // Pre-configure preferences directly (simulating prior app session)
        fakePrefs.edit()
            .putString(SettingsRepositoryImpl.KEY_DEFAULT_TOURNAMENT, StartupTournamentTier.GRAND_SLAMS.name)
            .apply()

        val coldRepo = SettingsRepositoryImpl(fakePrefs)
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = coldRepo
        )
        try {
            testDispatcher.scheduler.runCurrent()
            assertEquals(TourTierFilter.GRAND_SLAMS, vm.uiState.value.selectedTierFilter)
        } finally {
            vm.cleanup()
        }
    }

    // =========================================================================
    // 2. Dynamic Auto-Refresh Interval Polling Reactivity
    // =========================================================================

    @Test
    fun testDefaultAutoRefreshPollsEvery60Seconds() = runTest {
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            // Initial non-force load executed on startup, which triggers 1 initial silent refresh
            testDispatcher.scheduler.runCurrent()
            assertEquals(1, fakeScoreboardRepo.initialLoadCalls.get())
            val baseline = fakeScoreboardRepo.forceRefreshCalls.get()

            // Advance by 59 seconds: polling cycle has not triggered yet
            testDispatcher.scheduler.advanceTimeBy(59_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline, fakeScoreboardRepo.forceRefreshCalls.get())

            // Advance by 1 more second (total 60s): exactly 1 silent refresh should trigger
            testDispatcher.scheduler.advanceTimeBy(1_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline + 1, fakeScoreboardRepo.forceRefreshCalls.get())

            // Advance another 60 seconds (total 120s): 2nd silent refresh
            testDispatcher.scheduler.advanceTimeBy(60_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline + 2, fakeScoreboardRepo.forceRefreshCalls.get())
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testDynamicAdjustmentTo30SecondsModulatesPollingFrequency() = runTest {
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            val baselineBeforeSwitch = fakeScoreboardRepo.forceRefreshCalls.get()

            // Dynamically change refresh interval to 30s
            settingsRepository.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
            testDispatcher.scheduler.runCurrent()

            // Advance 29s: no new refresh yet under 30s interval
            testDispatcher.scheduler.advanceTimeBy(29_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baselineBeforeSwitch, fakeScoreboardRepo.forceRefreshCalls.get())

            // Advance to 30s: 1st refresh under new interval
            testDispatcher.scheduler.advanceTimeBy(1_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baselineBeforeSwitch + 1, fakeScoreboardRepo.forceRefreshCalls.get())

            // Advance another 30s (total 60s from switch): 2nd refresh
            testDispatcher.scheduler.advanceTimeBy(30_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baselineBeforeSwitch + 2, fakeScoreboardRepo.forceRefreshCalls.get())
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testDynamicAdjustmentTo2MinutesModulatesPollingFrequency() = runTest {
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            val baseline = fakeScoreboardRepo.forceRefreshCalls.get()

            // Dynamically change refresh interval to 2 minutes
            settingsRepository.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)
            testDispatcher.scheduler.runCurrent()

            // Advance 60s: no refresh yet (under 2m policy)
            testDispatcher.scheduler.advanceTimeBy(60_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline, fakeScoreboardRepo.forceRefreshCalls.get())

            // Advance to 120s: 1st refresh triggers
            testDispatcher.scheduler.advanceTimeBy(60_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline + 1, fakeScoreboardRepo.forceRefreshCalls.get())
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testDynamicChangeToOffCancelsPollingJobEntirely() = runTest {
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            val baseline = fakeScoreboardRepo.forceRefreshCalls.get()

            // Run 1 cycle at 60s
            testDispatcher.scheduler.advanceTimeBy(60_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline + 1, fakeScoreboardRepo.forceRefreshCalls.get())

            // Turn auto-refresh OFF
            settingsRepository.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
            testDispatcher.scheduler.runCurrent()

            val callsBefore = fakeScoreboardRepo.forceRefreshCalls.get()

            // Advance virtual time by 10 full minutes
            testDispatcher.scheduler.advanceTimeBy(600_000L)
            testDispatcher.scheduler.runCurrent()

            // No additional polling calls must occur while OFF
            assertEquals(callsBefore, fakeScoreboardRepo.forceRefreshCalls.get())
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testDynamicResumptionFromOffToActiveRestartsPolling() = runTest {
        // Start initially with OFF
        settingsRepository.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            val baseline = fakeScoreboardRepo.forceRefreshCalls.get()

            testDispatcher.scheduler.advanceTimeBy(120_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline, fakeScoreboardRepo.forceRefreshCalls.get())

            // User turns auto-refresh back ON (to 30s)
            settingsRepository.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
            testDispatcher.scheduler.runCurrent()

            // Advance 30s: polling must resume!
            testDispatcher.scheduler.advanceTimeBy(30_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline + 1, fakeScoreboardRepo.forceRefreshCalls.get())

            // Advance another 30s: 2nd cycle
            testDispatcher.scheduler.advanceTimeBy(30_000L)
            testDispatcher.scheduler.runCurrent()
            assertEquals(baseline + 2, fakeScoreboardRepo.forceRefreshCalls.get())
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun testEnableAutoRefreshFalseInConstructorDoesNotPollEvenIfSettingEnabled() = runTest {
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            h2hRepository = com.tennis.tracker.data.repository.H2HRepository(),
            followManager = null,
            enableAutoRefresh = false,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()
            testDispatcher.scheduler.advanceTimeBy(300_000L)
            // No auto refresh calls should happen
            assertEquals(0, fakeScoreboardRepo.forceRefreshCalls.get())

            // Settings updates also do not activate polling if VM was constructed with enableAutoRefresh=false
            settingsRepository.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
            testDispatcher.scheduler.advanceTimeBy(300_000L)
            assertEquals(0, fakeScoreboardRepo.forceRefreshCalls.get())
        } finally {
            vm.cleanup()
        }
    }

    // =========================================================================
    // 3. ViewModel Preference Mutation Helpers
    // =========================================================================

    @Test
    fun testViewModelPreferenceMutationMethodsUpdateUnderlyingRepository() = runTest {
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        try {
            testDispatcher.scheduler.runCurrent()

            // AutoRefreshInterval
            vm.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
            testDispatcher.scheduler.runCurrent()
            assertEquals(AutoRefreshInterval.INTERVAL_30S, vm.settings.value.autoRefreshInterval)
            assertEquals(AutoRefreshInterval.INTERVAL_30S, settingsRepository.getSettings().autoRefreshInterval)

            // MatchRetention
            vm.updateMatchRetention(MatchRetentionPolicy.HOURS_48)
            testDispatcher.scheduler.runCurrent()
            assertEquals(MatchRetentionPolicy.HOURS_48, vm.settings.value.matchRetention)
            assertEquals(MatchRetentionPolicy.HOURS_48, settingsRepository.getSettings().matchRetention)

            // WidgetSyncMode
            vm.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
            testDispatcher.scheduler.runCurrent()
            assertEquals(WidgetSyncMode.INTERVAL_15M, vm.settings.value.widgetSyncMode)
            assertEquals(WidgetSyncMode.INTERVAL_15M, settingsRepository.getSettings().widgetSyncMode)

            // DefaultTournament
            vm.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
            testDispatcher.scheduler.runCurrent()
            assertEquals(StartupTournamentTier.GRAND_SLAMS, vm.settings.value.defaultTournament)
            assertEquals(StartupTournamentTier.GRAND_SLAMS, settingsRepository.getSettings().defaultTournament)

            // TelemetryEnabled
            vm.updateTelemetryEnabled(false)
            testDispatcher.scheduler.runCurrent()
            assertFalse(vm.settings.value.telemetryEnabled)
            assertFalse(settingsRepository.getSettings().telemetryEnabled)

            // TelemetryEndpoint
            val customEndpoint = "https://custom.telemetry.domain.io/ingest"
            vm.updateTelemetryEndpoint(customEndpoint)
            testDispatcher.scheduler.runCurrent()
            assertEquals(customEndpoint, vm.settings.value.telemetryEndpoint)
            assertEquals(customEndpoint, settingsRepository.getSettings().telemetryEndpoint)

            // Reset to Defaults
            vm.resetSettingsToDefaults()
            testDispatcher.scheduler.runCurrent()
            assertEquals(AppSettings.DEFAULT, vm.settings.value)
            assertEquals(AppSettings.DEFAULT, settingsRepository.getSettings())
        } finally {
            vm.cleanup()
        }
    }

    // =========================================================================
    // 4. Cleanup & Lifecycle Coroutine Tear Down
    // =========================================================================

    @Test
    fun testViewModelCleanupCancelsSettingsObserverAndAutoRefreshJobs() = runTest {
        val vm = ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            settingsRepository = settingsRepository
        )
        testDispatcher.scheduler.runCurrent()

        // Advance 60s -> 1 call
        testDispatcher.scheduler.advanceTimeBy(60_000L)
        assertEquals(1, fakeScoreboardRepo.forceRefreshCalls.get())

        // Call cleanup
        vm.cleanup()

        // Subsequent settings changes should no longer restart polling on this destroyed VM
        settingsRepository.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(300_000L)

        // Count should stay 1
        assertEquals(1, fakeScoreboardRepo.forceRefreshCalls.get())
    }
}
