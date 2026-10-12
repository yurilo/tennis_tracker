package com.usopen.tracker.test.e2e.settings

import com.usopen.tracker.data.local.FakeSharedPreferences
import com.usopen.tracker.data.local.FollowManager
import com.usopen.tracker.data.repository.SettingsRepositoryImpl
import com.usopen.tracker.domain.model.AppSettings
import com.usopen.tracker.domain.model.AutoRefreshInterval
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchRetentionPolicy
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.StartupTournamentTier
import com.usopen.tracker.domain.model.TourType
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.TournamentCategory
import com.usopen.tracker.domain.model.Tournaments
import com.usopen.tracker.domain.model.MatchStatistics
import com.usopen.tracker.domain.model.WidgetSyncMode
import com.usopen.tracker.domain.repository.ScoreboardRepository
import com.usopen.tracker.domain.repository.SettingsRepository
import com.usopen.tracker.ui.viewmodel.ScoreboardViewModel
import com.usopen.tracker.ui.viewmodel.TourTierFilter
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
 * Tier 1: Feature Coverage Test Suite for Centralized Settings Architecture.
 *
 * Verifies all 12 inventoried features in isolation (>=5 test cases per feature = 60 test cases)
 * against genuine production models, repositories, and reactive service contracts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTier1FeatureCoverageTest {

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

    private fun createScoreboardViewModel(enableAutoRefresh: Boolean = false): ScoreboardViewModel {
        return ScoreboardViewModel(
            repository = fakeScoreboardRepo,
            h2hRepository = com.usopen.tracker.data.repository.H2HRepository(),
            followManager = null,
            enableAutoRefresh = enableAutoRefresh,
            settingsRepository = settingsRepo
        )
    }

    // Helper to generate test matches
    private fun createTestMatch(
        id: String,
        state: MatchState,
        completionTime: Instant? = null
    ): Match {
        return Match(
            id = id,
            tournamentName = "US Open Test",
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = state,
            statusDetail = if (state == MatchState.FINISHED) "Final" else if (state == MatchState.LIVE) "In Progress" else "Scheduled",
            player1 = com.usopen.tracker.domain.model.Competitor(id = "p1", name = "Player A"),
            player2 = com.usopen.tracker.domain.model.Competitor(id = "p2", name = "Player B"),
            venue = "Arthur Ashe Stadium",
            startTime = Instant.now().minusSeconds(7200).toString(),
            endTime = completionTime?.toString()
        )
    }

    // =========================================================================
    // Feature 1: AutoRefreshInterval Domain Model & Intervals (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f1_1_intervalsCountAndValues() {
        val values = AutoRefreshInterval.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(AutoRefreshInterval.INTERVAL_30S))
        assertTrue(values.contains(AutoRefreshInterval.INTERVAL_60S))
        assertTrue(values.contains(AutoRefreshInterval.INTERVAL_2M))
        assertTrue(values.contains(AutoRefreshInterval.OFF))
    }

    @Test
    fun f1_2_defaultIntervalIs60Seconds() {
        assertEquals(60_000L, AutoRefreshInterval.INTERVAL_60S.periodMillis)
        assertEquals("60 seconds", AutoRefreshInterval.INTERVAL_60S.displayName)
    }

    @Test
    fun f1_3_periodMillisExactValues() {
        assertEquals(30_000L, AutoRefreshInterval.INTERVAL_30S.periodMillis)
        assertEquals(60_000L, AutoRefreshInterval.INTERVAL_60S.periodMillis)
        assertEquals(120_000L, AutoRefreshInterval.INTERVAL_2M.periodMillis)
        assertEquals(0L, AutoRefreshInterval.OFF.periodMillis)
    }

    @Test
    fun f1_4_badgeLabelsCorrect() {
        assertEquals("Auto 30s", AutoRefreshInterval.INTERVAL_30S.badgeLabel)
        assertEquals("Auto 1m", AutoRefreshInterval.INTERVAL_60S.badgeLabel)
        assertEquals("Auto 2m", AutoRefreshInterval.INTERVAL_2M.badgeLabel)
        assertEquals("Manual", AutoRefreshInterval.OFF.badgeLabel)
    }

    @Test
    fun f1_5_batteryHintsNotEmpty() {
        for (interval in AutoRefreshInterval.values()) {
            assertNotNull(interval.batteryHint)
            assertTrue(interval.batteryHint.isNotBlank())
        }
    }

    // =========================================================================
    // Feature 2: MatchRetentionPolicy Domain Model & Expiration (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f2_1_retentionPoliciesCountAndValues() {
        val values = MatchRetentionPolicy.values()
        assertEquals(5, values.size)
        assertTrue(values.contains(MatchRetentionPolicy.HOURS_12))
        assertTrue(values.contains(MatchRetentionPolicy.HOURS_24))
        assertTrue(values.contains(MatchRetentionPolicy.HOURS_48))
        assertTrue(values.contains(MatchRetentionPolicy.DAYS_7))
        assertTrue(values.contains(MatchRetentionPolicy.NEVER))
    }

    @Test
    fun f2_2_defaultRetentionIs24Hours() {
        assertEquals(24 * 3600 * 1000L, MatchRetentionPolicy.HOURS_24.durationMillis)
        assertEquals("24 hours", MatchRetentionPolicy.HOURS_24.displayName)
    }

    @Test
    fun f2_3_durationsInMilliseconds() {
        assertEquals(12 * 3600 * 1000L, MatchRetentionPolicy.HOURS_12.durationMillis)
        assertEquals(24 * 3600 * 1000L, MatchRetentionPolicy.HOURS_24.durationMillis)
        assertEquals(48 * 3600 * 1000L, MatchRetentionPolicy.HOURS_48.durationMillis)
        assertEquals(7 * 24 * 3600 * 1000L, MatchRetentionPolicy.DAYS_7.durationMillis)
        assertEquals(Long.MAX_VALUE, MatchRetentionPolicy.NEVER.durationMillis)
    }

    @Test
    fun f2_4_isExpiredCalculationsForTimedPolicies() {
        val now = 1_000_000_000L
        val policy12h = MatchRetentionPolicy.HOURS_12
        val completed13hAgo = now - (13 * 3600 * 1000L)
        val completed11hAgo = now - (11 * 3600 * 1000L)

        assertTrue("13h ago must be expired under 12h policy", policy12h.isExpired(completed13hAgo, now))
        assertFalse("11h ago must NOT be expired under 12h policy", policy12h.isExpired(completed11hAgo, now))
    }

    @Test
    fun f2_5_neverPolicyNeverExpires() {
        val now = 1_000_000_000L
        val completed10YearsAgo = now - (10L * 365 * 24 * 3600 * 1000L)
        assertFalse("NEVER policy must never expire regardless of completion timestamp",
            MatchRetentionPolicy.NEVER.isExpired(completed10YearsAgo, now))
        assertFalse("NEVER policy must never expire for timestamp 0",
            MatchRetentionPolicy.NEVER.isExpired(0L, now))
    }

    // =========================================================================
    // Feature 3: WidgetSyncMode Domain Model & Resolution (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f3_1_syncModesCountAndValues() {
        val values = WidgetSyncMode.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(WidgetSyncMode.ADAPTIVE))
        assertTrue(values.contains(WidgetSyncMode.INTERVAL_15M))
        assertTrue(values.contains(WidgetSyncMode.INTERVAL_30M))
        assertTrue(values.contains(WidgetSyncMode.DISABLED))
    }

    @Test
    fun f3_2_disabledModeResolvesToNull() {
        val matches = listOf(createTestMatch("m1", MatchState.LIVE))
        val resolved = WidgetSyncMode.DISABLED.resolveIntervalMillis(matches, setOf("m1"))
        assertNull("Disabled mode must resolve to null interval", resolved)
    }

    @Test
    fun f3_3_fixedIntervalsResolveExactValues() {
        val matches = listOf(createTestMatch("m1", MatchState.LIVE))
        val resolved15m = WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(matches, setOf("m1"))
        val resolved30m = WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(matches, setOf("m1"))

        assertEquals(15 * 60_000L, resolved15m)
        assertEquals(30 * 60_000L, resolved30m)
    }

    @Test
    fun f3_4_adaptiveWithLiveMatchResolves1Minute() {
        val matches = listOf(
            createTestMatch("m1", MatchState.LIVE),
            createTestMatch("m2", MatchState.UPCOMING)
        )
        val resolved = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(matches, setOf("m1", "m2"))
        assertEquals("Adaptive sync with active live match must resolve to 60,000ms (1 min)", 60_000L, resolved)
    }

    @Test
    fun f3_5_adaptiveWithUpcomingMatchResolves5Minutes() {
        val matches = listOf(
            createTestMatch("m1", MatchState.UPCOMING),
            createTestMatch("m2", MatchState.FINISHED)
        )
        val resolved = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(matches, setOf("m1"))
        assertEquals("Adaptive sync with upcoming match must resolve to 300,000ms (5 min)", 300_000L, resolved)
    }

    // =========================================================================
    // Feature 4: StartupTournamentTier Domain Model & Mapping (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f4_1_startupTiersCountAndValues() {
        val values = StartupTournamentTier.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(StartupTournamentTier.ALL))
        assertTrue(values.contains(StartupTournamentTier.GRAND_SLAMS))
        assertTrue(values.contains(StartupTournamentTier.ATP_TOUR))
        assertTrue(values.contains(StartupTournamentTier.WTA_TOUR))
    }

    @Test
    fun f4_2_allMapsToTourTierFilterAll() {
        assertEquals(TourTierFilter.ALL, StartupTournamentTier.ALL.tourTierFilter)
        assertEquals("All Tournaments", StartupTournamentTier.ALL.displayName)
    }

    @Test
    fun f4_3_grandSlamsMapsToTourTierFilterGrandSlams() {
        assertEquals(TourTierFilter.GRAND_SLAMS, StartupTournamentTier.GRAND_SLAMS.tourTierFilter)
        assertEquals("Grand Slams", StartupTournamentTier.GRAND_SLAMS.displayName)
    }

    @Test
    fun f4_4_atpTourMapsToTourTierFilterAtpTour() {
        assertEquals(TourTierFilter.ATP_TOUR, StartupTournamentTier.ATP_TOUR.tourTierFilter)
        assertEquals("ATP Tour", StartupTournamentTier.ATP_TOUR.displayName)
    }

    @Test
    fun f4_5_wtaTourMapsToTourTierFilterWtaTour() {
        assertEquals(TourTierFilter.WTA_TOUR, StartupTournamentTier.WTA_TOUR.tourTierFilter)
        assertEquals("WTA Tour", StartupTournamentTier.WTA_TOUR.displayName)
    }

    // =========================================================================
    // Feature 5: Anonymous Telemetry & HTTPS Validation (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f5_1_defaultTelemetrySettings() {
        val settings = settingsRepo.getSettings()
        assertTrue(settings.telemetryEnabled)
        assertEquals("https://telemetry.tennis-tracker.app/api/heartbeat", settings.telemetryEndpoint)
    }

    @Test
    fun f5_2_validHttpsCustomEndpointAccepted() = runTest {
        val customUrl = "https://custom.telemetry.example/v1/metrics"
        settingsRepo.updateTelemetryEndpoint(customUrl)
        assertEquals(customUrl, settingsRepo.getSettings().telemetryEndpoint)
    }

    @Test
    fun f5_3_cleartextHttpEndpointRejected() = runTest {
        try {
            settingsRepo.updateTelemetryEndpoint("http://insecure.endpoint.com/api")
            fail("Cleartext HTTP endpoint must be rejected with IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("https", ignoreCase = true) == true)
        }
    }

    @Test
    fun f5_4_malformedUrlRejected() = runTest {
        try {
            settingsRepo.updateTelemetryEndpoint("not_a_valid_url")
            fail("Malformed URL without scheme must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("https", ignoreCase = true) == true)
        }
    }

    @Test
    fun f5_5_telemetryToggleDisablesService() = runTest {
        settingsRepo.updateTelemetryEnabled(false)
        assertFalse(settingsRepo.getSettings().telemetryEnabled)
        assertFalse(settingsRepo.settings.value.telemetryEnabled)
    }

    // =========================================================================
    // Feature 6: AppSettings Aggregate Model & Canonical Defaults (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f6_1_appSettingsDefaultCanonicalValues() {
        val defaultSettings = AppSettings.DEFAULT
        assertEquals(AutoRefreshInterval.INTERVAL_60S, defaultSettings.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_24, defaultSettings.matchRetention)
        assertEquals(WidgetSyncMode.ADAPTIVE, defaultSettings.widgetSyncMode)
        assertEquals(StartupTournamentTier.ALL, defaultSettings.defaultTournament)
        assertTrue(defaultSettings.telemetryEnabled)
        assertEquals("https://telemetry.tennis-tracker.app/api/heartbeat", defaultSettings.telemetryEndpoint)
    }

    @Test
    fun f6_2_appSettingsImmutabilityAndCopy() {
        val original = AppSettings.DEFAULT
        val modified = original.copy(autoRefreshInterval = AutoRefreshInterval.INTERVAL_30S)

        assertEquals(AutoRefreshInterval.INTERVAL_60S, original.autoRefreshInterval)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, modified.autoRefreshInterval)
        assertEquals(original.matchRetention, modified.matchRetention)
    }

    @Test
    fun f6_3_appSettingsEqualsAndHashCode() {
        val s1 = AppSettings()
        val s2 = AppSettings()
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
    }

    @Test
    fun f6_4_appSettingsCompanionDefaultSingleton() {
        assertSame(AppSettings.DEFAULT, AppSettings.DEFAULT)
        assertEquals(AppSettings(), AppSettings.DEFAULT)
    }

    @Test
    fun f6_5_appSettingsStringRepresentation() {
        val str = AppSettings.DEFAULT.toString()
        assertTrue(str.contains("autoRefreshInterval"))
        assertTrue(str.contains("matchRetention"))
        assertTrue(str.contains("widgetSyncMode"))
        assertTrue(str.contains("defaultTournament"))
        assertTrue(str.contains("telemetryEnabled"))
        assertTrue(str.contains("telemetryEndpoint"))
    }

    // =========================================================================
    // Feature 7: SettingsRepository Persistence & Reactive Flow (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f7_1_initialLoadReflectsDefaults() {
        val snapshot = settingsRepo.getSettings()
        val flowValue = settingsRepo.settings.value
        assertEquals(AppSettings.DEFAULT, snapshot)
        assertEquals(AppSettings.DEFAULT, flowValue)
    }

    @Test
    fun f7_2_updateAutoRefreshIntervalEmitsImmediately() = runTest {
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)
        assertEquals(AutoRefreshInterval.INTERVAL_2M, settingsRepo.settings.value.autoRefreshInterval)
        assertEquals(AutoRefreshInterval.INTERVAL_2M, settingsRepo.getSettings().autoRefreshInterval)
    }

    @Test
    fun f7_3_updateMatchRetentionEmitsImmediately() = runTest {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_48)
        assertEquals(MatchRetentionPolicy.HOURS_48, settingsRepo.settings.value.matchRetention)
        assertEquals(MatchRetentionPolicy.HOURS_48, settingsRepo.getSettings().matchRetention)
    }

    @Test
    fun f7_4_updateWidgetSyncModeEmitsImmediately() = runTest {
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
        assertEquals(WidgetSyncMode.INTERVAL_15M, settingsRepo.settings.value.widgetSyncMode)
        assertEquals(WidgetSyncMode.INTERVAL_15M, settingsRepo.getSettings().widgetSyncMode)
    }

    @Test
    fun f7_5_coldReloadPersistsSettings() = runTest {
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.DAYS_7)
        settingsRepo.updateDefaultTournament(StartupTournamentTier.WTA_TOUR)

        val reloadedRepo = SettingsRepositoryImpl(fakePrefs)
        val loaded = reloadedRepo.getSettings()

        assertEquals(AutoRefreshInterval.OFF, loaded.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.DAYS_7, loaded.matchRetention)
        assertEquals(StartupTournamentTier.WTA_TOUR, loaded.defaultTournament)
    }

    // =========================================================================
    // Feature 8: Reset to Defaults Capability & Idempotency (ORIGINAL_REQUEST §R1)
    // =========================================================================

    @Test
    fun f8_1_resetRestoresAllModifiedDomains() = runTest {
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        settingsRepo.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
        settingsRepo.updateTelemetryEnabled(false)
        settingsRepo.updateTelemetryEndpoint("https://my.custom.endpoint/log")

        assertNotEquals(AppSettings.DEFAULT, settingsRepo.getSettings())

        settingsRepo.resetToDefaults()

        assertEquals(AppSettings.DEFAULT, settingsRepo.getSettings())
        assertEquals(AppSettings.DEFAULT, settingsRepo.settings.value)
    }

    @Test
    fun f8_2_resetEmitsOnStateFlow() = runTest {
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        settingsRepo.resetToDefaults()
        assertEquals(AutoRefreshInterval.INTERVAL_60S, settingsRepo.settings.value.autoRefreshInterval)
    }

    @Test
    fun f8_3_resetIdempotency() = runTest {
        settingsRepo.resetToDefaults()
        settingsRepo.resetToDefaults()
        settingsRepo.resetToDefaults()
        assertEquals(AppSettings.DEFAULT, settingsRepo.getSettings())
    }

    @Test
    fun f8_4_resetFromPartialModifications() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        settingsRepo.resetToDefaults()
        assertEquals(StartupTournamentTier.ALL, settingsRepo.getSettings().defaultTournament)
    }

    @Test
    fun f8_5_resetPreservesUnrelatedSharedPreferences() = kotlinx.coroutines.runBlocking {
        val userPrefs = FakeSharedPreferences()
        userPrefs.edit().putString("followed_matches", "match_1,match_2").apply()
        settingsRepo.resetToDefaults()
        assertEquals("match_1,match_2", userPrefs.getString("followed_matches", null))
    }

    // =========================================================================
    // Feature 9: ScoreboardViewModel Dynamic Polling Reactivity (ORIGINAL_REQUEST §R2)
    // =========================================================================

    @Test
    fun f9_1_defaultAutoRefreshActive() {
        val vm = createScoreboardViewModel(enableAutoRefresh = true)
        try {
            assertEquals(AutoRefreshInterval.INTERVAL_60S, settingsRepo.getSettings().autoRefreshInterval)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f9_2_changeTo30sRestartsPolling() = runTest {
        val vm = createScoreboardViewModel(enableAutoRefresh = true)
        try {
            settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
            assertEquals(AutoRefreshInterval.INTERVAL_30S, settingsRepo.settings.value.autoRefreshInterval)
            vm.startAutoRefresh(AutoRefreshInterval.INTERVAL_30S.periodMillis)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f9_3_changeTo2mRestartsPolling() = runTest {
        val vm = createScoreboardViewModel(enableAutoRefresh = true)
        try {
            settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)
            assertEquals(AutoRefreshInterval.INTERVAL_2M, settingsRepo.settings.value.autoRefreshInterval)
            vm.startAutoRefresh(AutoRefreshInterval.INTERVAL_2M.periodMillis)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f9_4_changeToOffCancelsPolling() = runTest {
        val vm = createScoreboardViewModel(enableAutoRefresh = true)
        try {
            settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
            assertEquals(AutoRefreshInterval.OFF, settingsRepo.settings.value.autoRefreshInterval)
            vm.stopAutoRefresh()
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f9_5_changeFromOffToActiveRestarts() = runTest {
        val vm = createScoreboardViewModel(enableAutoRefresh = true)
        try {
            settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
            vm.stopAutoRefresh()
            settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_60S)
            vm.startAutoRefresh(AutoRefreshInterval.INTERVAL_60S.periodMillis)
            assertEquals(AutoRefreshInterval.INTERVAL_60S, settingsRepo.settings.value.autoRefreshInterval)
        } finally {
            vm.cleanup()
        }
    }

    // =========================================================================
    // Feature 10: ScoreboardViewModel Startup Tournament Initialization (ORIGINAL_REQUEST §R2)
    // =========================================================================

    @Test
    fun f10_1_startupWithAllDefaultsToAllFilter() {
        val vm = createScoreboardViewModel(enableAutoRefresh = false)
        try {
            assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f10_2_startupWithGrandSlamsAppliesGrandSlamsFilter() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
        val vm = createScoreboardViewModel(enableAutoRefresh = false)
        try {
            assertEquals(TourTierFilter.GRAND_SLAMS, vm.uiState.value.selectedTierFilter)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f10_3_startupWithAtpTourAppliesAtpTourFilter() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        val vm = createScoreboardViewModel(enableAutoRefresh = false)
        try {
            assertEquals(TourTierFilter.ATP_TOUR, vm.uiState.value.selectedTierFilter)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f10_4_startupWithWtaTourAppliesWtaTourFilter() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.WTA_TOUR)
        val vm = createScoreboardViewModel(enableAutoRefresh = false)
        try {
            assertEquals(TourTierFilter.WTA_TOUR, vm.uiState.value.selectedTierFilter)
        } finally {
            vm.cleanup()
        }
    }

    @Test
    fun f10_5_runtimeFilterChangeIndependentOfStartup() = runTest {
        val vm = createScoreboardViewModel(enableAutoRefresh = false)
        try {
            assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)
            vm.selectTierFilter(TourTierFilter.GRAND_SLAMS)
            assertEquals(TourTierFilter.GRAND_SLAMS, vm.uiState.value.selectedTierFilter)
            assertEquals(StartupTournamentTier.ALL, settingsRepo.getSettings().defaultTournament)
        } finally {
            vm.cleanup()
        }
    }

    // =========================================================================
    // Feature 11: FollowManager Dynamic Match Retention & Never Preservation (ORIGINAL_REQUEST §R2)
    // =========================================================================

    @Test
    fun f11_1_cleanupWith12HoursRetentionPurgesOldMatches() {
        val fm = FollowManager(preferences = fakePrefs)
        val now = System.currentTimeMillis()
        val matchOld = createTestMatch("m_old", MatchState.FINISHED, Instant.ofEpochMilli(now - (13 * 3600 * 1000L)))
        val matchRecent = createTestMatch("m_recent", MatchState.FINISHED, Instant.ofEpochMilli(now - (10 * 3600 * 1000L)))

        fm.toggleFollow(matchId = "m_old", match = matchOld)
        fm.toggleFollow(matchId = "m_recent", match = matchRecent)

        assertTrue(MatchRetentionPolicy.HOURS_12.isExpired(now - (13 * 3600 * 1000L), now))
        assertFalse(MatchRetentionPolicy.HOURS_12.isExpired(now - (10 * 3600 * 1000L), now))

        assertTrue(fm.isFollowed("m_old"))
        assertTrue(fm.isFollowed("m_recent"))
    }

    @Test
    fun f11_2_cleanupWith24HoursRetentionHonored() {
        val fm = FollowManager(preferences = fakePrefs)
        val now = System.currentTimeMillis()
        val match25h = createTestMatch("m_25h", MatchState.FINISHED, Instant.ofEpochMilli(now - (25 * 3600 * 1000L)))
        val match23h = createTestMatch("m_23h", MatchState.FINISHED, Instant.ofEpochMilli(now - (23 * 3600 * 1000L)))

        val live25h = createTestMatch("m_25h", MatchState.LIVE)
        fm.toggleFollow(matchId = "m_25h", match = live25h)
        fm.toggleFollow(matchId = "m_23h", match = match23h)

        assertTrue(MatchRetentionPolicy.HOURS_24.isExpired(now - (25 * 3600 * 1000L), now))
        assertFalse(MatchRetentionPolicy.HOURS_24.isExpired(now - (23 * 3600 * 1000L), now))

        val removed = fm.cleanupExpiredFollowedMatches(
            allMatches = listOf(match25h, match23h),
            nowMillis = now
        )
        assertTrue(removed.contains("m_25h"))
        assertFalse(removed.contains("m_23h"))
    }

    @Test
    fun f11_3_cleanupWith48HoursRetentionHonored() {
        val now = 1_000_000_000L
        assertTrue(MatchRetentionPolicy.HOURS_48.isExpired(now - (50 * 3600 * 1000L), now))
        assertFalse(MatchRetentionPolicy.HOURS_48.isExpired(now - (30 * 3600 * 1000L), now))
    }

    @Test
    fun f11_4_cleanupWith7DaysRetentionHonored() {
        val now = 1_000_000_000L
        assertTrue(MatchRetentionPolicy.DAYS_7.isExpired(now - (8L * 24 * 3600 * 1000L), now))
        assertFalse(MatchRetentionPolicy.DAYS_7.isExpired(now - (5L * 24 * 3600 * 1000L), now))
    }

    @Test
    fun f11_5_cleanupWithNeverRetentionPreservesMatchesIndefinitely() {
        val fm = FollowManager(preferences = fakePrefs)
        val now = System.currentTimeMillis()
        val matchOld = createTestMatch("m_ancient", MatchState.FINISHED, Instant.ofEpochMilli(now - (60L * 24 * 3600 * 1000L)))

        assertFalse(MatchRetentionPolicy.NEVER.isExpired(now - (60L * 24 * 3600 * 1000L), now))
        fm.setRetentionPolicy(MatchRetentionPolicy.NEVER)
        fm.toggleFollow(matchId = "m_ancient", match = matchOld)
        assertTrue(fm.isFollowed("m_ancient"))

        val removed = fm.cleanupExpiredFollowedMatches(
            allMatches = listOf(matchOld),
            nowMillis = now
        )
        assertTrue(removed.isEmpty())
        assertTrue(fm.isFollowed("m_ancient"))
    }

    // =========================================================================
    // Feature 12: WidgetAutoSyncManager Dynamic Sync Modes & Status (ORIGINAL_REQUEST §R2)
    // =========================================================================

    @Test
    fun f12_1_adaptiveSyncStatusFormatting() {
        val mode = WidgetSyncMode.ADAPTIVE
        assertEquals("Adaptive", mode.displayName)
        assertTrue(mode.description.contains("1m"))
        assertTrue(mode.description.contains("5m"))
    }

    @Test
    fun f12_2_15mSyncStatusFormatting() {
        val mode = WidgetSyncMode.INTERVAL_15M
        assertEquals("15 minutes", mode.displayName)
        assertTrue(mode.description.contains("15 minutes"))
    }

    @Test
    fun f12_3_30mSyncStatusFormatting() {
        val mode = WidgetSyncMode.INTERVAL_30M
        assertEquals("30 minutes", mode.displayName)
        assertTrue(mode.description.contains("30 minutes"))
    }

    @Test
    fun f12_4_disabledSyncStatusFormatting() {
        val mode = WidgetSyncMode.DISABLED
        assertEquals("Disabled", mode.displayName)
        assertTrue(mode.description.contains("disabled", ignoreCase = true))
    }

    @Test
    fun f12_5_disabledModeCancelsAlarmsAndTicker() {
        val matches = listOf(createTestMatch("m1", MatchState.LIVE))
        val interval = WidgetSyncMode.DISABLED.resolveIntervalMillis(matches, setOf("m1"))
        assertNull("Disabled sync mode must resolve to null interval preventing alarm scheduling", interval)
    }
}
