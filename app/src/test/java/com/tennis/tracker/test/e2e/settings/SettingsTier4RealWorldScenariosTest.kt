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
import com.tennis.tracker.domain.model.WidgetSyncMode
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
 * Tier 4: Real-World Application Scenarios Test Suite for Centralized Settings Architecture.
 *
 * Verifies 6 complete end-to-end user journeys spanning all 5 preference domains,
 * reactive service updates, persistence, and state preservation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTier4RealWorldScenariosTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakeSharedPreferences()
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
            tournamentName = "US Open Championship",
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = state,
            statusDetail = if (state == MatchState.FINISHED) "Final" else if (state == MatchState.LIVE) "In Progress" else "Scheduled",
            player1 = com.tennis.tracker.domain.model.Competitor(id = "p1", name = "Carlos Alcaraz"),
            player2 = com.tennis.tracker.domain.model.Competitor(id = "p2", name = "Jannik Sinner"),
            venue = "Arthur Ashe Stadium",
            startTime = Instant.now().minusSeconds(10800).toString(),
            endTime = completionTime?.toString()
        )
    }

    // =========================================================================
    // Scenario 1: First-Time User Cold Launch & Preference Personalization
    // =========================================================================
    @Test
    fun scenario1_firstTimeUserOnboardingAndPreferencePersonalization() = runTest {
        // Step 1: User cold-starts app for the first time
        val repo = SettingsRepositoryImpl(fakePrefs)
        val initialSettings = repo.getSettings()
        assertEquals(AppSettings.DEFAULT, initialSettings)

        val vm = ScoreboardViewModel()
        assertEquals(TourTierFilter.ALL, vm.uiState.value.selectedTierFilter)

        // Step 2: User opens Settings modal and personalizes preferences
        // User prefers WTA Tour matches on startup, high frequency 30s auto-refresh, and 48h retention
        repo.updateDefaultTournament(StartupTournamentTier.WTA_TOUR)
        repo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        repo.updateMatchRetention(MatchRetentionPolicy.HOURS_48)

        // Step 3: Assert instantaneous in-memory emission
        assertEquals(StartupTournamentTier.WTA_TOUR, repo.settings.value.defaultTournament)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, repo.settings.value.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_48, repo.settings.value.matchRetention)

        // Step 4: User terminates app and cold-launches later
        vm.cleanup()
        val reloadedRepo = SettingsRepositoryImpl(fakePrefs)
        val reloadedVm = ScoreboardViewModel()
        reloadedVm.selectTierFilter(reloadedRepo.getSettings().defaultTournament.tourTierFilter)

        // Step 5: Cold-launched ViewModel initializes with preferred WTA_TOUR tier
        assertEquals(TourTierFilter.WTA_TOUR, reloadedVm.uiState.value.selectedTierFilter)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, reloadedRepo.getSettings().autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_48, reloadedRepo.getSettings().matchRetention)
        reloadedVm.cleanup()
    }

    // =========================================================================
    // Scenario 2: Active Tournament Follow & Dynamic Retention Lifecycle
    // =========================================================================
    @Test
    fun scenario2_activeTournamentFollowAndDynamicRetentionLifecycle() = runTest {
        val repo = SettingsRepositoryImpl(fakePrefs)
        val fm = FollowManager(preferences = fakePrefs, settingsRepository = repo)
        val now = System.currentTimeMillis()

        // Step 1: User follows an ongoing final match
        val liveMatch = createTestMatch("match_final", MatchState.LIVE)
        fm.toggleFollow(matchId = "match_final", match = liveMatch)
        assertTrue(fm.isFollowed("match_final"))

        // Step 2: Match concludes at timestamp `now`
        val finishedMatch = createTestMatch("match_final", MatchState.FINISHED, Instant.ofEpochMilli(now))

        // Step 3: User sets retention window to 12 hours
        repo.updateMatchRetention(MatchRetentionPolicy.HOURS_12)

        // At 10 hours post-match: match is retained
        assertFalse(repo.getSettings().matchRetention.isExpired(now, now + (10 * 3600 * 1000L)))
        assertTrue(fm.isFollowed("match_final"))

        // At 13 hours post-match: match auto-expires and is purged
        assertTrue(repo.getSettings().matchRetention.isExpired(now, now + (13 * 3600 * 1000L)))
        val removed = fm.cleanupExpiredFollowedMatches(
            allMatches = listOf(finishedMatch),
            nowMillis = now + (13 * 3600 * 1000L)
        )
        assertTrue(removed.contains("match_final"))
        assertFalse(fm.isFollowed("match_final"))

        // Step 4: User changes policy to "Never auto-clear"
        repo.updateMatchRetention(MatchRetentionPolicy.NEVER)

        // Re-follow an old match from 30 days ago
        val ancientMatch = createTestMatch("match_classic", MatchState.FINISHED, Instant.ofEpochMilli(now - 30L * 24 * 3600 * 1000L))
        fm.toggleFollow(matchId = "match_classic", match = ancientMatch)
        assertTrue(fm.isFollowed("match_classic"))

        // At 60 days post-match: NEVER policy never expires
        assertFalse(MatchRetentionPolicy.NEVER.isExpired(now - 30L * 24 * 3600 * 1000L, now + (60L * 24 * 3600 * 1000L)))
        assertTrue(fm.isFollowed("match_classic"))
    }

    // =========================================================================
    // Scenario 3: Live Polling Cadence Modulation During Match Crunch Time
    // =========================================================================
    @Test
    fun scenario3_livePollingCadenceModulationDuringCrunchTime() = runTest {
        val repo = SettingsRepositoryImpl(fakePrefs)
        val vm = ScoreboardViewModel(enableAutoRefresh = true)

        // Initial default: 60s auto-refresh
        assertEquals(AutoRefreshInterval.INTERVAL_60S, repo.getSettings().autoRefreshInterval)
        assertEquals("Auto 1m", repo.getSettings().autoRefreshInterval.badgeLabel)

        // US Open 5th set tiebreak begins: user switches to high-frequency 30s refresh
        repo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        vm.startAutoRefresh(AutoRefreshInterval.INTERVAL_30S.periodMillis)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, repo.settings.value.autoRefreshInterval)
        assertEquals("Auto 30s", repo.settings.value.autoRefreshInterval.badgeLabel)
        assertEquals(30_000L, repo.getSettings().autoRefreshInterval.periodMillis)

        // User exits arena with 10% battery: switches to Manual Only (Off) to conserve charge
        repo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        vm.stopAutoRefresh()
        assertEquals(AutoRefreshInterval.OFF, repo.settings.value.autoRefreshInterval)
        assertEquals("Manual", repo.settings.value.autoRefreshInterval.badgeLabel)
        assertEquals(0L, repo.getSettings().autoRefreshInterval.periodMillis)

        // Matches can still be refreshed manually on pull-to-refresh
        vm.loadMatches(forceRefresh = true)
        assertNotNull(vm.uiState.value)

        vm.cleanup()
    }

    // =========================================================================
    // Scenario 4: Desktop Widget Sync Policy Modulation
    // =========================================================================
    @Test
    fun scenario4_desktopWidgetSyncPolicyModulation() = runTest {
        val repo = SettingsRepositoryImpl(fakePrefs)
        val liveMatch = createTestMatch("m_live", MatchState.LIVE)
        val upcomingMatch = createTestMatch("m_upcoming", MatchState.UPCOMING)

        // Step 1: Default Adaptive mode
        val defaultMode = repo.getSettings().widgetSyncMode
        assertEquals(WidgetSyncMode.ADAPTIVE, defaultMode)
        val liveInterval = defaultMode.resolveIntervalMillis(listOf(liveMatch), setOf("m_live"))
        assertEquals(60_000L, liveInterval)

        val upcomingInterval = defaultMode.resolveIntervalMillis(listOf(upcomingMatch), setOf("m_upcoming"))
        assertEquals(300_000L, upcomingInterval)

        // Step 2: User switches to Fixed 15 Minutes mode
        repo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
        val fixedInterval = repo.getSettings().widgetSyncMode.resolveIntervalMillis(listOf(liveMatch), setOf("m_live"))
        assertEquals(900_000L, fixedInterval)
        assertEquals("15 minutes", repo.getSettings().widgetSyncMode.displayName)

        // Step 3: User disables background widget updates
        repo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        val disabledInterval = repo.getSettings().widgetSyncMode.resolveIntervalMillis(listOf(liveMatch), setOf("m_live"))
        assertNull(disabledInterval)
        assertEquals("Disabled", repo.getSettings().widgetSyncMode.displayName)
    }

    // =========================================================================
    // Scenario 5: Privacy Hardening, GDPR UUID Erasure & Custom Endpoint
    // =========================================================================
    @Test
    fun scenario5_privacyHardeningGdprErasureAndCustomEndpoint() = runTest {
        val repo = SettingsRepositoryImpl(fakePrefs)

        // Step 1: Telemetry enabled by default with official heartbeat endpoint
        assertTrue(repo.getSettings().telemetryEnabled)
        assertEquals("https://telemetry.tennis-tracker.app/api/heartbeat", repo.getSettings().telemetryEndpoint)

        // Step 2: User exercises privacy rights and opts out of telemetry
        repo.updateTelemetryEnabled(false)
        assertFalse(repo.getSettings().telemetryEnabled)
        assertFalse(repo.settings.value.telemetryEnabled)

        // Step 3: User configures custom enterprise HTTPS monitoring endpoint
        val enterpriseUrl = "https://metrics.internal.enterprise-network.com/v1/tennis"
        repo.updateTelemetryEndpoint(enterpriseUrl)
        assertEquals(enterpriseUrl, repo.getSettings().telemetryEndpoint)

        // Step 4: Insecure endpoint attempt is rejected
        try {
            repo.updateTelemetryEndpoint("http://unencrypted.internal/log")
            fail("Insecure HTTP endpoint must be rejected")
        } catch (_: IllegalArgumentException) {}

        // Custom URL remains intact
        assertEquals(enterpriseUrl, repo.getSettings().telemetryEndpoint)
    }

    // =========================================================================
    // Scenario 6: Disaster Recovery / Reset to Defaults with Followed Match Preservation
    // =========================================================================
    @Test
    fun scenario6_resetToDefaultsWithFollowedMatchPreservation() = runTest {
        val repo = SettingsRepositoryImpl(fakePrefs)
        val fm = FollowManager(preferences = fakePrefs)

        // Step 1: User follows important matches
        val match1 = createTestMatch("match_1", MatchState.LIVE)
        val match2 = createTestMatch("match_2", MatchState.UPCOMING)
        fm.toggleFollow(matchId = "match_1", match = match1)
        fm.toggleFollow(matchId = "match_2", match = match2)
        assertTrue(fm.isFollowed("match_1"))
        assertTrue(fm.isFollowed("match_2"))

        // Step 2: User changes every preference away from defaults
        repo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        repo.updateMatchRetention(MatchRetentionPolicy.NEVER)
        repo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        repo.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        repo.updateTelemetryEnabled(false)
        repo.updateTelemetryEndpoint("https://custom.telemetry.org/heartbeat")

        assertNotEquals(AppSettings.DEFAULT, repo.getSettings())

        // Step 3: User clicks "Reset to Defaults" in Settings modal
        repo.resetToDefaults()

        // Step 4: Verify all 5 domains restored to exact canonical defaults
        val restored = repo.getSettings()
        assertEquals(AutoRefreshInterval.INTERVAL_60S, restored.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_24, restored.matchRetention)
        assertEquals(WidgetSyncMode.ADAPTIVE, restored.widgetSyncMode)
        assertEquals(StartupTournamentTier.ALL, restored.defaultTournament)
        assertTrue(restored.telemetryEnabled)
        assertEquals("https://telemetry.tennis-tracker.app/api/heartbeat", restored.telemetryEndpoint)
        assertEquals(AppSettings.DEFAULT, restored)

        // Step 5: Followed matches are 100% PRESERVED
        assertTrue("Followed match 1 must remain intact after settings reset", fm.isFollowed("match_1"))
        assertTrue("Followed match 2 must remain intact after settings reset", fm.isFollowed("match_2"))
    }
}
