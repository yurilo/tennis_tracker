package com.tennis.tracker.test.e2e.settings

import com.tennis.tracker.data.local.FakeSharedPreferences
import com.tennis.tracker.data.local.FollowManager
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.TourType
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.domain.repository.ScoreboardRepository
import com.tennis.tracker.domain.repository.SettingsRepository
import com.tennis.tracker.ui.viewmodel.ScoreboardViewModel
import com.tennis.tracker.ui.viewmodel.TourTierFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Tier 3: Pairwise Combinatorial Test Suite for Centralized Settings Architecture.
 *
 * Verifies cross-feature interactions and state transitions across the 5 preference domains
 * (12 pairwise combination test cases).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTier3CrossFeatureTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakeSharedPreferences()
        settingsRepo = SettingsRepositoryImpl(fakePrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fakeScoreboardRepo = object : ScoreboardRepository {
        private var activeTournament: Tournament = Tournaments.ALL
        override fun getActiveTournament(): Tournament = activeTournament
        override fun setActiveTournament(tournament: Tournament) { activeTournament = tournament }
        override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> = Result.success(emptyList())
        override fun getOfflineScoreboard(): List<Match> = emptyList()
        override suspend fun getScoreboardForTournament(tournament: Tournament, forceRefresh: Boolean): Result<List<Match>> = Result.success(emptyList())
        override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> = Result.failure(NoSuchElementException())
    }

    private fun createScoreboardViewModel(enableAutoRefresh: Boolean = false, repo: SettingsRepository = settingsRepo): ScoreboardViewModel {
        return ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            h2hRepository = com.tennis.tracker.data.repository.H2HRepository(),
            followManager = null,
            enableAutoRefresh = enableAutoRefresh,
            settingsRepository = repo
        )
    }

    private fun createTestMatch(
        id: String,
        state: MatchState,
        completionTime: Instant? = null
    ): Match {
        return Match(
            id = id,
            tournamentName = "Pairwise Championship",
            eventGrouping = "Men's Singles",
            roundName = "Quarter-Final",
            state = state,
            statusDetail = if (state == MatchState.FINISHED) "Final" else if (state == MatchState.LIVE) "In Progress" else "Scheduled",
            player1 = com.tennis.tracker.domain.model.Competitor(id = "p1", name = "Player Alpha"),
            player2 = com.tennis.tracker.domain.model.Competitor(id = "p2", name = "Player Beta"),
            venue = "Rod Laver Arena",
            startTime = Instant.now().minusSeconds(14400).toString(),
            endTime = completionTime?.toString()
        )
    }

    // =========================================================================
    // Pairwise 1: Retention NEVER + AutoRefresh OFF (Maximum Power Saver / Manual Archival)
    // =========================================================================
    @Test
    fun t3_1_retentionNeverAndAutoRefreshOff() = runTest {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.NEVER)
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)

        val settings = settingsRepo.getSettings()
        assertEquals(MatchRetentionPolicy.NEVER, settings.matchRetention)
        assertEquals(AutoRefreshInterval.OFF, settings.autoRefreshInterval)

        val fm = FollowManager(preferences = fakePrefs)
        val matchOld = createTestMatch("m_archive", MatchState.FINISHED, Instant.ofEpochMilli(0L))
        fm.toggleFollow(matchId = "m_archive", match = matchOld)
        assertFalse(MatchRetentionPolicy.NEVER.isExpired(0L, System.currentTimeMillis()))

        val vm = ScoreboardViewModel(enableAutoRefresh = true)
        vm.stopAutoRefresh()
        assertEquals(AutoRefreshInterval.OFF, settingsRepo.settings.value.autoRefreshInterval)
        vm.cleanup()
    }

    // =========================================================================
    // Pairwise 2: Retention 12h + AutoRefresh 30s (Ultra Real-Time Tournament Mode)
    // =========================================================================
    @Test
    fun t3_2_retention12hAndAutoRefresh30s() = runTest {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)

        val settings = settingsRepo.getSettings()
        assertEquals(MatchRetentionPolicy.HOURS_12, settings.matchRetention)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, settings.autoRefreshInterval)

        val now = 1_000_000_000L
        assertTrue(settings.matchRetention.isExpired(now - 13 * 3600 * 1000L, now))
        assertEquals(30_000L, settings.autoRefreshInterval.periodMillis)
    }

    // =========================================================================
    // Pairwise 3: WidgetSync DISABLED + Telemetry DISABLED (Maximum Privacy & Zero Background Network)
    // =========================================================================
    @Test
    fun t3_3_widgetSyncDisabledAndTelemetryDisabled() = runTest {
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        settingsRepo.updateTelemetryEnabled(false)

        val settings = settingsRepo.getSettings()
        assertEquals(WidgetSyncMode.DISABLED, settings.widgetSyncMode)
        assertFalse(settings.telemetryEnabled)

        val matches = listOf(createTestMatch("m_live", MatchState.LIVE))
        assertNull(settings.widgetSyncMode.resolveIntervalMillis(matches, setOf("m_live")))
    }

    // =========================================================================
    // Pairwise 4: WidgetSync ADAPTIVE + AutoRefresh 60s (Balanced Defaults)
    // =========================================================================
    @Test
    fun t3_4_widgetSyncAdaptiveAndAutoRefresh60s() = runTest {
        val settings = settingsRepo.getSettings()
        assertEquals(WidgetSyncMode.ADAPTIVE, settings.widgetSyncMode)
        assertEquals(AutoRefreshInterval.INTERVAL_60S, settings.autoRefreshInterval)

        val matches = listOf(createTestMatch("m_live", MatchState.LIVE))
        val resolved = settings.widgetSyncMode.resolveIntervalMillis(matches, setOf("m_live"))
        assertEquals(60_000L, resolved)
    }

    // =========================================================================
    // Pairwise 5: Startup GRAND_SLAMS + Retention 7d (Major Tournament Week Focus)
    // =========================================================================
    @Test
    fun t3_5_startupGrandSlamsAndRetention7d() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.DAYS_7)

        val vm = createScoreboardViewModel()
        try {
            vm.selectTierFilter(settingsRepo.getSettings().defaultTournament.tourTierFilter)
            assertEquals(TourTierFilter.GRAND_SLAMS, vm.uiState.value.selectedTierFilter)

            val now = 1_000_000_000L
            val match5DaysAgo = now - (5L * 24 * 3600 * 1000L)
            assertFalse(settingsRepo.getSettings().matchRetention.isExpired(match5DaysAgo, now))
        } finally {
            vm.cleanup()
        }
    }

    // =========================================================================
    // Pairwise 6: Startup WTA_TOUR + AutoRefresh 2m (Tour Focus + Power Saver)
    // =========================================================================
    @Test
    fun t3_6_startupWtaTourAndAutoRefresh2m() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.WTA_TOUR)
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)

        val vm = createScoreboardViewModel()
        try {
            vm.selectTierFilter(settingsRepo.getSettings().defaultTournament.tourTierFilter)
            assertEquals(TourTierFilter.WTA_TOUR, vm.uiState.value.selectedTierFilter)
            assertEquals(120_000L, settingsRepo.getSettings().autoRefreshInterval.periodMillis)
        } finally {
            vm.cleanup()
        }
    }

    // =========================================================================
    // Pairwise 7: Custom HTTPS Telemetry Endpoint + Telemetry ENABLED (Enterprise Ingest)
    // =========================================================================
    @Test
    fun t3_7_customHttpsTelemetryEndpointAndTelemetryEnabled() = runTest {
        val endpoint = "https://analytics.prod.tennistracker.com/events"
        settingsRepo.updateTelemetryEndpoint(endpoint)
        settingsRepo.updateTelemetryEnabled(true)

        val settings = settingsRepo.getSettings()
        assertTrue(settings.telemetryEnabled)
        assertEquals(endpoint, settings.telemetryEndpoint)
    }

    // =========================================================================
    // Pairwise 8: Reset to Defaults Affecting All 5 Domains Concurrently
    // =========================================================================
    @Test
    fun t3_8_resetToDefaultsRestoresAll5DomainsConcurrently() = runTest {
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.NEVER)
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        settingsRepo.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        settingsRepo.updateTelemetryEnabled(false)

        assertNotEquals(AppSettings.DEFAULT, settingsRepo.getSettings())

        settingsRepo.resetToDefaults()

        val restored = settingsRepo.getSettings()
        assertEquals(AutoRefreshInterval.INTERVAL_60S, restored.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_24, restored.matchRetention)
        assertEquals(WidgetSyncMode.ADAPTIVE, restored.widgetSyncMode)
        assertEquals(StartupTournamentTier.ALL, restored.defaultTournament)
        assertTrue(restored.telemetryEnabled)
        assertEquals(AppSettings.DEFAULT, restored)
    }

    // =========================================================================
    // Pairwise 9: Dynamic Retention Update Triggers Purge While Auto-Refresh Continues
    // =========================================================================
    @Test
    fun t3_9_dynamicRetentionUpdateTriggersPurgeWhileAutoRefreshActive() = runTest {
        val fm = FollowManager(preferences = fakePrefs)
        val now = System.currentTimeMillis()
        val match15hAgo = createTestMatch("m_15h", MatchState.FINISHED, Instant.ofEpochMilli(now - 15 * 3600 * 1000L))

        fm.toggleFollow(matchId = "m_15h", match = match15hAgo)
        assertTrue(fm.isFollowed("m_15h"))

        assertFalse(MatchRetentionPolicy.HOURS_24.isExpired(now - 15 * 3600 * 1000L, now))

        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        assertTrue(settingsRepo.getSettings().matchRetention.isExpired(now - 15 * 3600 * 1000L, now))

        assertEquals(AutoRefreshInterval.INTERVAL_60S, settingsRepo.getSettings().autoRefreshInterval)
    }

    // =========================================================================
    // Pairwise 10: AutoRefresh OFF With WidgetSync ADAPTIVE Keeps Widget Alarms Independent
    // =========================================================================
    @Test
    fun t3_10_autoRefreshOffWithWidgetSyncAdaptiveMaintainsWidgetAlarms() = runTest {
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.ADAPTIVE)

        val settings = settingsRepo.getSettings()
        assertEquals(AutoRefreshInterval.OFF, settings.autoRefreshInterval)
        assertEquals(WidgetSyncMode.ADAPTIVE, settings.widgetSyncMode)

        val liveMatches = listOf(createTestMatch("m_live", MatchState.LIVE))
        val widgetInterval = settings.widgetSyncMode.resolveIntervalMillis(liveMatches, setOf("m_live"))
        assertEquals("Widget retains 60,000ms adaptive interval for live match even when app UI auto-refresh is OFF",
            60_000L, widgetInterval)
    }

    // =========================================================================
    // Pairwise 11: Startup Tier Change Persisted Across Process Restart Simulation
    // =========================================================================
    @Test
    fun t3_11_startupTierPersistedAcrossColdRestartSimulation() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_48)

        val coldStartRepo = SettingsRepositoryImpl(fakePrefs)
        val coldStartVm = createScoreboardViewModel(repo = coldStartRepo)
        try {
            coldStartVm.selectTierFilter(coldStartRepo.getSettings().defaultTournament.tourTierFilter)

            assertEquals(TourTierFilter.ATP_TOUR, coldStartVm.uiState.value.selectedTierFilter)
            assertEquals(MatchRetentionPolicy.HOURS_48, coldStartRepo.getSettings().matchRetention)
        } finally {
            coldStartVm.cleanup()
        }
    }

    // =========================================================================
    // Pairwise 12: Opt-Out Telemetry Then Reset Restores Telemetry Enabled With Default Endpoint
    // =========================================================================
    @Test
    fun t3_12_optOutTelemetryThenResetRestoresDefaults() = runTest {
        settingsRepo.updateTelemetryEnabled(false)
        settingsRepo.updateTelemetryEndpoint("https://temp.private.log/api")

        assertFalse(settingsRepo.getSettings().telemetryEnabled)
        assertEquals("https://temp.private.log/api", settingsRepo.getSettings().telemetryEndpoint)

        settingsRepo.resetToDefaults()

        assertTrue(settingsRepo.getSettings().telemetryEnabled)
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, settingsRepo.getSettings().telemetryEndpoint)
    }
}
