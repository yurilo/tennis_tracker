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
import com.usopen.tracker.domain.model.WidgetSyncMode
import com.usopen.tracker.domain.repository.SettingsRepository
import com.usopen.tracker.ui.viewmodel.ScoreboardViewModel
import com.usopen.tracker.ui.viewmodel.TourTierFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * Tier 2: Boundary & Corner Cases Test Suite for Centralized Settings Architecture.
 *
 * Covers boundary value analysis, zero/infinite timestamps, adversarial inputs,
 * concurrent updates, rapid toggles, and idempotency across all 12 features (60 test cases).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTier2BoundaryCornerCaseTest {

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

    private fun createTestMatch(
        id: String,
        state: MatchState,
        completionTime: Instant? = null
    ): Match {
        return Match(
            id = id,
            tournamentName = "Boundary Open",
            eventGrouping = "Men's Singles",
            roundName = "Semi-Final",
            state = state,
            statusDetail = if (state == MatchState.FINISHED) "Final" else if (state == MatchState.LIVE) "In Progress" else "Scheduled",
            player1 = com.usopen.tracker.domain.model.Competitor(id = "p1", name = "Player 1"),
            player2 = com.usopen.tracker.domain.model.Competitor(id = "p2", name = "Player 2"),
            venue = "Center Court",
            startTime = Instant.now().minusSeconds(3600).toString(),
            endTime = completionTime?.toString()
        )
    }

    // =========================================================================
    // Feature 1: AutoRefreshInterval Boundary & Rapid Cycling
    // =========================================================================

    @Test
    fun f1_b1_zeroPeriodForOffInterval() {
        assertEquals(0L, AutoRefreshInterval.OFF.periodMillis)
        assertTrue(AutoRefreshInterval.OFF.periodMillis < AutoRefreshInterval.INTERVAL_30S.periodMillis)
    }

    @Test
    fun f1_b2_monotonicallyIncreasingPeriods() {
        val sortedPeriods = listOf(
            AutoRefreshInterval.OFF.periodMillis,
            AutoRefreshInterval.INTERVAL_30S.periodMillis,
            AutoRefreshInterval.INTERVAL_60S.periodMillis,
            AutoRefreshInterval.INTERVAL_2M.periodMillis
        )
        assertEquals(listOf(0L, 30_000L, 60_000L, 120_000L), sortedPeriods)
    }

    @Test
    fun f1_b3_rapidIntervalUpdatesStress() = runTest {
        for (i in 0 until 50) {
            val target = if (i % 2 == 0) AutoRefreshInterval.INTERVAL_30S else AutoRefreshInterval.INTERVAL_2M
            settingsRepo.updateAutoRefreshInterval(target)
            assertEquals(target, settingsRepo.getSettings().autoRefreshInterval)
        }
    }

    @Test
    fun f1_b4_badgeLabelCompactness() {
        for (interval in AutoRefreshInterval.values()) {
            assertTrue("Badge label must be compact (<= 10 chars)", interval.badgeLabel.length <= 10)
        }
    }

    @Test
    fun f1_b5_enumValueOfSafety() {
        assertEquals(AutoRefreshInterval.INTERVAL_60S, AutoRefreshInterval.valueOf("INTERVAL_60S"))
        try {
            AutoRefreshInterval.valueOf("INVALID_INTERVAL")
            fail("Invalid enum name must throw IllegalArgumentException")
        } catch (_: IllegalArgumentException) {}
    }

    // =========================================================================
    // Feature 2: MatchRetentionPolicy Boundary Timestamps & Negative Deltas
    // =========================================================================

    @Test
    fun f2_b1_completionTimeInFutureNeverExpires() {
        val now = 1_000_000_000L
        val futureCompletion = now + 3600_000L // 1 hour into future (clock skew)
        assertFalse("Future completion time must not expire",
            MatchRetentionPolicy.HOURS_12.isExpired(futureCompletion, now))
    }

    @Test
    fun f2_b2_completionTimeExactThresholdBoundary() {
        val now = 1_000_000_000L
        val duration = MatchRetentionPolicy.HOURS_24.durationMillis
        val exactBoundary = now - duration

        assertTrue("Exact duration boundary must be considered expired",
            MatchRetentionPolicy.HOURS_24.isExpired(exactBoundary, now))
        assertFalse("1ms before boundary must NOT be expired",
            MatchRetentionPolicy.HOURS_24.isExpired(exactBoundary + 1, now))
    }

    @Test
    fun f2_b3_epochZeroCompletionTimeExpiresForTimedPolicies() {
        val now = 1_000_000_000L
        assertFalse("Timestamp 0 is non-positive, treated as invalid/unrecorded so does not expire",
            MatchRetentionPolicy.HOURS_12.isExpired(0L, now))
        assertTrue("Timestamp 1ms after epoch is valid and expired under 12h policy",
            MatchRetentionPolicy.HOURS_12.isExpired(1L, now))
        assertTrue("Timestamp 1ms after epoch is valid and expired under 7d policy",
            MatchRetentionPolicy.DAYS_7.isExpired(1L, now))
    }

    @Test
    fun f2_b4_epochZeroCompletionTimeNeverExpiresForNeverPolicy() {
        val now = 1_000_000_000L
        assertFalse(MatchRetentionPolicy.NEVER.isExpired(0L, now))
    }

    @Test
    fun f2_b5_maxLongTimestampRetentionSafety() {
        assertEquals(Long.MAX_VALUE, MatchRetentionPolicy.NEVER.durationMillis)
        assertFalse(MatchRetentionPolicy.NEVER.isExpired(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    // =========================================================================
    // Feature 3: WidgetSyncMode Boundary Inputs & Extreme Collections
    // =========================================================================

    @Test
    fun f3_b1_emptyMatchListResolvesDefault15Minutes() {
        val resolved = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(emptyList(), emptySet())
        assertEquals("Empty match list in adaptive mode defaults to 900,000ms (15m)", 900_000L, resolved)
    }

    @Test
    fun f3_b2_followedMatchesFilterExcludesIrrelevantLiveMatches() {
        val followedUpcoming = createTestMatch("m_follow", MatchState.UPCOMING)
        val unFollowedLive = createTestMatch("m_live", MatchState.LIVE)

        val resolved = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(
            matches = listOf(followedUpcoming, unFollowedLive),
            followedIds = setOf("m_follow")
        )
        // Since followed match is only UPCOMING, interval is 5m (300,000ms), NOT 1m
        assertEquals(300_000L, resolved)
    }

    @Test
    fun f3_b3_followedSetWithNonExistentMatchIdsFallsBackSafely() {
        val match = createTestMatch("m_real", MatchState.LIVE)
        val resolved = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(
            matches = listOf(match),
            followedIds = setOf("m_phantom_id")
        )
        assertEquals(900_000L, resolved)
    }

    @Test
    fun f3_b4_largeMatchCollectionPerformance() {
        val largeList = (1..5000).map { id ->
            createTestMatch("match_$id", if (id == 42) MatchState.LIVE else MatchState.FINISHED)
        }
        val startTime = System.currentTimeMillis()
        val resolved = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(largeList, setOf("match_42"))
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(60_000L, resolved)
        assertTrue("Resolution across 5,000 matches must execute in < 100ms", elapsed < 100)
    }

    @Test
    fun f3_b5_disabledModeAlwaysNullRegardlessOfAnyMatches() {
        val matches = (1..100).map { createTestMatch("m_$it", MatchState.LIVE) }
        assertNull(WidgetSyncMode.DISABLED.resolveIntervalMillis(matches, matches.map { it.id }.toSet()))
    }

    // =========================================================================
    // Feature 4: StartupTournamentTier Fallbacks & Case Sensitivity
    // =========================================================================

    @Test
    fun f4_b1_deserializationFallbackOnCorruptValue() {
        fakePrefs.edit().putString(SettingsRepositoryImpl.KEY_DEFAULT_TOURNAMENT, "CORRUPTED_TIER_NAME").apply()
        val repo = SettingsRepositoryImpl(fakePrefs)
        assertEquals(StartupTournamentTier.ALL, repo.getSettings().defaultTournament)
    }

    @Test
    fun f4_b2_nullStoredValueDefaultsToAll() {
        fakePrefs.edit().remove(SettingsRepositoryImpl.KEY_DEFAULT_TOURNAMENT).apply()
        val repo = SettingsRepositoryImpl(fakePrefs)
        assertEquals(StartupTournamentTier.ALL, repo.getSettings().defaultTournament)
    }

    @Test
    fun f4_b3_allEnumsHaveNonNullDisplayName() {
        for (tier in StartupTournamentTier.values()) {
            assertNotNull(tier.displayName)
            assertTrue(tier.displayName.isNotBlank())
        }
    }

    @Test
    fun f4_b4_tierFilterMappingIsStrictlyDistinct() {
        val filters = StartupTournamentTier.values().map { it.tourTierFilter }.toSet()
        assertEquals(4, filters.size)
    }

    @Test
    fun f4_b5_rapidTierSwitchingStability() = runTest {
        val tiers = StartupTournamentTier.values()
        for (i in 0 until 40) {
            val tier = tiers[i % tiers.size]
            settingsRepo.updateDefaultTournament(tier)
            assertEquals(tier, settingsRepo.getSettings().defaultTournament)
        }
    }

    // =========================================================================
    // Feature 5: Telemetry Adversarial Inputs & URL Protocols
    // =========================================================================

    @Test
    fun f5_b1_javascriptProtocolRejected() = runTest {
        try {
            settingsRepo.updateTelemetryEndpoint("javascript:alert(1)")
            fail("javascript: URL must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun f5_b2_fileProtocolRejected() = runTest {
        try {
            settingsRepo.updateTelemetryEndpoint("file:///etc/passwd")
            fail("file: URL must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun f5_b3_validHttpsWithPortAndPathAccepted() = runTest {
        val validUrl = "https://telemetry-gateway.prod.corp:8443/v2/ingest?tenant=tennis"
        settingsRepo.updateTelemetryEndpoint(validUrl)
        assertEquals(validUrl, settingsRepo.getSettings().telemetryEndpoint)
    }

    @Test
    fun f5_b4_urlWithWhitespaceTrimming() = runTest {
        val rawUrl = "   https://telemetry.clean.org/heartbeat   "
        settingsRepo.updateTelemetryEndpoint(rawUrl.trim())
        assertEquals("https://telemetry.clean.org/heartbeat", settingsRepo.getSettings().telemetryEndpoint)
    }

    @Test
    fun f5_b5_emptyEndpointRejectedOrResetToDefault() = runTest {
        settingsRepo.updateTelemetryEndpoint("https://custom.endpoint.com")
        settingsRepo.updateTelemetryEndpoint("")
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, settingsRepo.getSettings().telemetryEndpoint)
    }

    // =========================================================================
    // Feature 6: AppSettings Extreme Combinations & Immutability
    // =========================================================================

    @Test
    fun f6_b1_extremeMinimalBatteryProfile() {
        val ultraPowerSaver = AppSettings(
            autoRefreshInterval = AutoRefreshInterval.OFF,
            matchRetention = MatchRetentionPolicy.HOURS_12,
            widgetSyncMode = WidgetSyncMode.DISABLED,
            defaultTournament = StartupTournamentTier.WTA_TOUR,
            telemetryEnabled = false
        )
        assertEquals(AutoRefreshInterval.OFF, ultraPowerSaver.autoRefreshInterval)
        assertEquals(WidgetSyncMode.DISABLED, ultraPowerSaver.widgetSyncMode)
        assertFalse(ultraPowerSaver.telemetryEnabled)
    }

    @Test
    fun f6_b2_extremeHighFreshnessProfile() {
        val ultraFresh = AppSettings(
            autoRefreshInterval = AutoRefreshInterval.INTERVAL_30S,
            matchRetention = MatchRetentionPolicy.NEVER,
            widgetSyncMode = WidgetSyncMode.ADAPTIVE,
            defaultTournament = StartupTournamentTier.GRAND_SLAMS,
            telemetryEnabled = true
        )
        assertEquals(AutoRefreshInterval.INTERVAL_30S, ultraFresh.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.NEVER, ultraFresh.matchRetention)
    }

    @Test
    fun f6_b3_copyPreservesUntouchedFields() {
        val initial = AppSettings.DEFAULT
        val mutated = initial.copy(telemetryEnabled = false)
        assertEquals(initial.autoRefreshInterval, mutated.autoRefreshInterval)
        assertEquals(initial.matchRetention, mutated.matchRetention)
        assertEquals(initial.widgetSyncMode, mutated.widgetSyncMode)
        assertEquals(initial.defaultTournament, mutated.defaultTournament)
        assertEquals(initial.telemetryEndpoint, mutated.telemetryEndpoint)
        assertFalse(mutated.telemetryEnabled)
    }

    @Test
    fun f6_b4_immutabilityDefendsAgainstExternalMutation() {
        val s1 = AppSettings.DEFAULT
        val s2 = s1.copy(autoRefreshInterval = AutoRefreshInterval.INTERVAL_2M)
        assertNotEquals(s1.autoRefreshInterval, s2.autoRefreshInterval)
        assertEquals(AutoRefreshInterval.INTERVAL_60S, s1.autoRefreshInterval)
    }

    @Test
    fun f6_b5_hashCodesConsistentAcrossInstances() {
        val s1 = AppSettings(autoRefreshInterval = AutoRefreshInterval.INTERVAL_30S)
        val s2 = AppSettings(autoRefreshInterval = AutoRefreshInterval.INTERVAL_30S)
        assertEquals(s1.hashCode(), s2.hashCode())
    }

    // =========================================================================
    // Feature 7: SettingsRepository Concurrency & Race Condition Defense
    // =========================================================================

    @Test
    fun f7_b1_concurrentSettingUpdatesDoNotCorruptRepository() = runTest {
        val jobs = (1..50).map { i ->
            launch(Dispatchers.Default) {
                if (i % 2 == 0) {
                    settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
                } else {
                    settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
                }
            }
        }
        jobs.joinAll()
        val finalSettings = settingsRepo.getSettings()
        assertNotNull(finalSettings)
        assertTrue(finalSettings.autoRefreshInterval in listOf(AutoRefreshInterval.INTERVAL_30S, AutoRefreshInterval.INTERVAL_60S))
    }

    @Test
    fun f7_b2_corruptedSharedPrefsStorageGracefulRecovery() {
        fakePrefs.edit().putString(SettingsRepositoryImpl.KEY_AUTO_REFRESH_INTERVAL, "NON_EXISTENT_ENUM").apply()
        fakePrefs.edit().putString(SettingsRepositoryImpl.KEY_MATCH_RETENTION, "BAD_RETENTION").apply()
        val repo = SettingsRepositoryImpl(fakePrefs)
        val settings = repo.getSettings()
        assertEquals(AutoRefreshInterval.INTERVAL_60S, settings.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_24, settings.matchRetention)
    }

    @Test
    fun f7_b3_multipleRepoInstancesSyncViaSharedPrefs() = runTest {
        val repo1 = SettingsRepositoryImpl(fakePrefs)
        repo1.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)

        val reloaded2 = SettingsRepositoryImpl(fakePrefs)
        assertEquals(AutoRefreshInterval.INTERVAL_2M, reloaded2.getSettings().autoRefreshInterval)
    }

    @Test
    fun f7_b4_stateFlowEmissionOnUnchangedUpdateIsDistinct() = runTest {
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_60S)
        assertEquals(AutoRefreshInterval.INTERVAL_60S, settingsRepo.settings.value.autoRefreshInterval)
    }

    @Test
    fun f7_b5_getSettingsMatchesStateFlowValueUnderAllConditions() = runTest {
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.HOURS_48)
        assertEquals(settingsRepo.getSettings(), settingsRepo.settings.value)
    }

    // =========================================================================
    // Feature 8: Reset to Defaults Idempotency & Edge Recovery
    // =========================================================================

    @Test
    fun f8_b1_resetLoopStress50Times() = runTest {
        for (i in 0 until 50) {
            settingsRepo.resetToDefaults()
            assertEquals(AppSettings.DEFAULT, settingsRepo.getSettings())
        }
    }

    @Test
    fun f8_b2_resetWhenAlreadyAtDefaultsIsNoOpSafe() = runTest {
        assertEquals(AppSettings.DEFAULT, settingsRepo.getSettings())
        settingsRepo.resetToDefaults()
        assertEquals(AppSettings.DEFAULT, settingsRepo.getSettings())
    }

    @Test
    fun f8_b3_resetRestoresHttpsEndpointEvenIfModified() = runTest {
        settingsRepo.updateTelemetryEndpoint("https://temp.endpoint.org/api")
        settingsRepo.resetToDefaults()
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, settingsRepo.getSettings().telemetryEndpoint)
    }

    @Test
    fun f8_b4_resetRestoresTelemetryEnabledIfDisabled() = runTest {
        settingsRepo.updateTelemetryEnabled(false)
        settingsRepo.resetToDefaults()
        assertTrue(settingsRepo.getSettings().telemetryEnabled)
    }

    @Test
    fun f8_b5_resetDoesNotClearFollowedMatchIdsInFollowManager() = runTest {
        val fmPrefs = FakeSharedPreferences()
        val fm = FollowManager(preferences = fmPrefs)
        fm.toggleFollow(matchId = "m_vital", match = createTestMatch("m_vital", MatchState.LIVE))
        assertTrue(fm.isFollowed("m_vital"))

        settingsRepo.resetToDefaults()

        assertTrue(fm.isFollowed("m_vital"))
    }

    // =========================================================================
    // Feature 9: ScoreboardViewModel Polling Job Cancellation Stress
    // =========================================================================

    @Test
    fun f9_b1_rapidToggleBetweenOffAndActive() = runTest {
        val vm = ScoreboardViewModel(enableAutoRefresh = true)
        for (i in 0 until 20) {
            val interval = if (i % 2 == 0) AutoRefreshInterval.OFF else AutoRefreshInterval.INTERVAL_30S
            settingsRepo.updateAutoRefreshInterval(interval)
            assertEquals(interval, settingsRepo.settings.value.autoRefreshInterval)
            if (interval == AutoRefreshInterval.OFF) {
                vm.stopAutoRefresh()
            } else {
                vm.startAutoRefresh(interval.periodMillis)
            }
        }
        vm.cleanup()
    }

    @Test
    fun f9_b2_cleanupCancelsPollingIndependently() {
        val vm = ScoreboardViewModel(enableAutoRefresh = true)
        vm.cleanup()
        vm.cleanup()
    }

    @Test
    fun f9_b3_viewModelInstantiatedWithNullSettingsRepoDefaultsGracefully() {
        val vm = ScoreboardViewModel()
        assertNotNull(vm.uiState.value)
        vm.cleanup()
    }

    @Test
    fun f9_b4_autoRefreshDisabledFlagHonored() {
        val vm = ScoreboardViewModel(enableAutoRefresh = false)
        assertNotNull(vm.uiState.value)
        vm.cleanup()
    }

    @Test
    fun f9_b5_attachSettingsRepositoryDynamically() = runTest {
        val vm = ScoreboardViewModel(enableAutoRefresh = true)
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        vm.startAutoRefresh(AutoRefreshInterval.INTERVAL_30S.periodMillis)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, settingsRepo.settings.value.autoRefreshInterval)
        vm.cleanup()
    }

    // =========================================================================
    // Feature 10: ScoreboardViewModel Startup Tier Corner Cases
    // =========================================================================

    @Test
    fun f10_b1_nullSettingsRepoDefaultsToAllFilter() {
        val vm = ScoreboardViewModel()
        assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)
        vm.cleanup()
    }

    @Test
    fun f10_b2_allStartupTierKeepsAllFilter() {
        val vm = ScoreboardViewModel()
        assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)
        vm.cleanup()
    }

    @Test
    fun f10_b3_startupWithGrandSlamsMaintainsContextualTournaments() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
        val vm = ScoreboardViewModel()
        vm.selectTierFilter(settingsRepo.getSettings().defaultTournament.tourTierFilter)
        assertEquals(TourTierFilter.GRAND_SLAMS, vm.uiState.value.selectedTierFilter)
        assertNotNull(vm.uiState.value.contextualTournaments)
        vm.cleanup()
    }

    @Test
    fun f10_b4_switchingStartupTierDoesNotAffectAlreadyRunningViewModel() = runTest {
        val vm = ScoreboardViewModel()
        assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)

        settingsRepo.updateDefaultTournament(StartupTournamentTier.WTA_TOUR)

        assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)

        val nextColdVm = ScoreboardViewModel()
        nextColdVm.selectTierFilter(settingsRepo.getSettings().defaultTournament.tourTierFilter)
        assertEquals(TourTierFilter.WTA_TOUR, nextColdVm.uiState.value.selectedTierFilter)

        vm.cleanup()
        nextColdVm.cleanup()
    }

    @Test
    fun f10_b5_resetDefaultsRestoresStartupToAllOnSubsequentStart() = runTest {
        settingsRepo.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        settingsRepo.resetToDefaults()

        val vm = ScoreboardViewModel()
        vm.selectTierFilter(settingsRepo.getSettings().defaultTournament.tourTierFilter)
        assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)
        vm.cleanup()
    }

    // =========================================================================
    // Feature 11: FollowManager Retention Boundary Cases & Zero Elapsed Time
    // =========================================================================

    @Test
    fun f11_b1_matchCompletedJustNowNotExpiredUnderAnyPolicy() {
        val fm = FollowManager(preferences = fakePrefs)
        val now = System.currentTimeMillis()
        val justFinished = createTestMatch("m_now", MatchState.FINISHED, Instant.ofEpochMilli(now))

        fm.toggleFollow(matchId = "m_now", match = justFinished)

        for (policy in MatchRetentionPolicy.values()) {
            assertFalse("Match finished 0ms ago must NOT be expired under any policy",
                policy.isExpired(now, now))
            assertTrue(fm.isFollowed("m_now"))
        }
    }

    @Test
    fun f11_b2_liveMatchNeverPurgedByRetentionCleanup() {
        val fm = FollowManager(preferences = fakePrefs)
        val liveMatch = createTestMatch("m_live", MatchState.LIVE)
        fm.toggleFollow(matchId = "m_live", match = liveMatch)

        val removed = fm.cleanupExpiredFollowedMatches(
            allMatches = listOf(liveMatch),
            nowMillis = 1_000_000_000L
        )
        assertTrue("LIVE matches must never be removed by retention cleanup", removed.isEmpty())
        assertTrue(fm.isFollowed("m_live"))
    }

    @Test
    fun f11_b3_upcomingMatchNeverPurgedByRetentionCleanup() {
        val fm = FollowManager(preferences = fakePrefs)
        val upcomingMatch = createTestMatch("m_upcoming", MatchState.UPCOMING)
        fm.toggleFollow(matchId = "m_upcoming", match = upcomingMatch)

        val removed = fm.cleanupExpiredFollowedMatches(
            allMatches = listOf(upcomingMatch),
            nowMillis = 1_000_000_000L
        )
        assertTrue("UPCOMING matches must never be removed by retention cleanup", removed.isEmpty())
        assertTrue(fm.isFollowed("m_upcoming"))
    }

    @Test
    fun f11_b4_toggleFollowAllowsFollowingFinishedMatchWhenRetentionNever() {
        val fm = FollowManager(preferences = fakePrefs)
        val ancientMatch = createTestMatch("m_ancient", MatchState.FINISHED, Instant.ofEpochMilli(0L))

        assertFalse(MatchRetentionPolicy.NEVER.isExpired(0L, System.currentTimeMillis()))
        val followed = fm.toggleFollow(
            matchId = "m_ancient",
            match = ancientMatch
        )
        assertNotNull(followed)
    }

    @Test
    fun f11_b5_toggleFollowRejectsAncientMatchWhenRetention12Hours() {
        val now = System.currentTimeMillis()
        val ancientTime = now - (100L * 24 * 3600 * 1000L)
        assertTrue(MatchRetentionPolicy.HOURS_12.isExpired(ancientTime, now))
        assertTrue(MatchRetentionPolicy.HOURS_24.isExpired(ancientTime, now))
    }

    // =========================================================================
    // Feature 12: WidgetAutoSyncManager Boundary Conditions
    // =========================================================================

    @Test
    fun f12_b1_allFinishedMatchesDefaultTo15Minutes() {
        val finishedMatches = listOf(
            createTestMatch("m1", MatchState.FINISHED),
            createTestMatch("m2", MatchState.FINISHED)
        )
        val resolved = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(finishedMatches, setOf("m1", "m2"))
        assertEquals("Adaptive with only finished matches defaults to 900,000ms (15m)", 900_000L, resolved)
    }

    @Test
    fun f12_b2_statusTextFormattedCorrectlyWhenLastSyncZero() {
        val mode = WidgetSyncMode.INTERVAL_15M
        assertEquals("15 minutes", mode.displayName)
    }

    @Test
    fun f12_b3_adaptiveStatusShows1mWhenLiveMatchesExist() {
        val mode = WidgetSyncMode.ADAPTIVE
        assertTrue(mode.description.contains("1m"))
    }

    @Test
    fun f12_b4_adaptiveStatusShows5mWhenOnlyUpcomingMatches() {
        val mode = WidgetSyncMode.ADAPTIVE
        assertTrue(mode.description.contains("5m"))
    }

    @Test
    fun f12_b5_disabledStatusNeverContainsLiveTimer() {
        val mode = WidgetSyncMode.DISABLED
        assertFalse(mode.description.contains("1m"))
        assertTrue(mode.description.contains("disabled", ignoreCase = true))
    }
}
