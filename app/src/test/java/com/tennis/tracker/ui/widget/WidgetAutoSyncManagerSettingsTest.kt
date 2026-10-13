package com.tennis.tracker.ui.widget

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tennis.tracker.data.local.FakeSharedPreferences
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.WidgetSyncMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dedicated unit test suite for [WidgetAutoSyncManager] settings and sync mode intervals.
 *
 * Verifies:
 * 1. Background sync intervals under all sync modes:
 *    - Adaptive: 1m live, 5m upcoming, 15m idle / results
 *    - 15 minutes: constant 900,000ms
 *    - 30 minutes: constant 1,800,000ms
 *    - Disabled: null interval resolution, disabling alarms
 * 2. SettingsRepository integration with [WidgetAutoSyncManager]:
 *    - Dynamic sync mode resolution from attached repository
 *    - Immediate disablement of auto-sync when mode is [WidgetSyncMode.DISABLED]
 * 3. Status text formatting in widget headers reflecting active sync modes and match states.
 * 4. User auto-sync toggles and outcome recording.
 */
class WidgetAutoSyncManagerSettingsTest {

    private lateinit var widgetPrefs: FakeSharedPreferences
    private lateinit var settingsPrefs: FakeSharedPreferences
    private lateinit var settingsRepo: SettingsRepositoryImpl
    private lateinit var testContext: Context

    private class WidgetTestContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.tennis.tracker"
    }

    private fun createMatch(id: String, state: MatchState): Match {
        return Match(
            id = id,
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Quarterfinals",
            state = state,
            statusDetail = state.name,
            player1 = Competitor(id = "p1", name = "Carlos Alcaraz"),
            player2 = Competitor(id = "p2", name = "Jannik Sinner"),
            venue = "Arthur Ashe Stadium"
        )
    }

    @Before
    fun setUp() {
        widgetPrefs = FakeSharedPreferences()
        settingsPrefs = FakeSharedPreferences()
        settingsRepo = SettingsRepositoryImpl(settingsPrefs)
        testContext = WidgetTestContext(widgetPrefs)
        WidgetAutoSyncManager.attachSettingsRepository(settingsRepo)
    }

    // =========================================================================
    // 1. Sync Mode: ADAPTIVE Intervals
    // =========================================================================

    @Test
    fun testAdaptiveModeResolves1MinuteWhenFollowedMatchIsLive() {
        val matches = listOf(
            createMatch("m-live", MatchState.LIVE),
            createMatch("m-upcoming", MatchState.UPCOMING),
            createMatch("m-finished", MatchState.FINISHED)
        )
        val followedIds = setOf("m-live", "m-upcoming")

        val interval = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(matches, followedIds)
        assertEquals(WidgetAutoSyncManager.INTERVAL_LIVE_MILLIS, interval)
        assertEquals(60_000L, interval)

        val calculated = WidgetAutoSyncManager.calculateInterval(matches, followedIds, WidgetSyncMode.ADAPTIVE)
        assertEquals(60_000L, calculated)
    }

    @Test
    fun testAdaptiveModeResolves5MinutesWhenFollowedMatchIsUpcoming() {
        val matches = listOf(
            createMatch("m-live-not-followed", MatchState.LIVE),
            createMatch("m-upcoming-followed", MatchState.UPCOMING),
            createMatch("m-finished-followed", MatchState.FINISHED)
        )
        val followedIds = setOf("m-upcoming-followed", "m-finished-followed")

        val interval = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(matches, followedIds)
        assertEquals(WidgetAutoSyncManager.INTERVAL_UPCOMING_MILLIS, interval)
        assertEquals(300_000L, interval)

        val calculated = WidgetAutoSyncManager.calculateInterval(matches, followedIds, WidgetSyncMode.ADAPTIVE)
        assertEquals(300_000L, calculated)
    }

    @Test
    fun testAdaptiveModeResolves15MinutesWhenAllFollowedMatchesFinished() {
        val matches = listOf(
            createMatch("m1", MatchState.FINISHED),
            createMatch("m2", MatchState.FINISHED)
        )
        val followedIds = setOf("m1", "m2")

        val interval = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(matches, followedIds)
        assertEquals(WidgetAutoSyncManager.INTERVAL_DEFAULT_MILLIS, interval)
        assertEquals(900_000L, interval)

        val calculated = WidgetAutoSyncManager.calculateInterval(matches, followedIds, WidgetSyncMode.ADAPTIVE)
        assertEquals(900_000L, calculated)
    }

    @Test
    fun testAdaptiveModeDefaultsTo15MinutesWhenMatchListIsEmpty() {
        val interval = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(emptyList(), emptySet())
        assertEquals(WidgetAutoSyncManager.INTERVAL_DEFAULT_MILLIS, interval)
        assertEquals(900_000L, interval)

        val calculated = WidgetAutoSyncManager.calculateInterval(emptyList(), emptySet(), WidgetSyncMode.ADAPTIVE)
        assertEquals(900_000L, calculated)
    }

    @Test
    fun testAdaptiveModeUsesLiveRateWhenFollowedEmptyButLiveMatchesExist() {
        val matches = listOf(
            createMatch("m-live-open", MatchState.LIVE)
        )
        val interval = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(matches, emptySet())
        assertEquals(WidgetAutoSyncManager.INTERVAL_LIVE_MILLIS, interval)
        assertEquals(60_000L, interval)
    }

    // =========================================================================
    // 2. Sync Mode: Fixed 15 Minutes (INTERVAL_15M)
    // =========================================================================

    @Test
    fun testInterval15MResolves900000msRegardlessOfMatchStates() {
        val liveMatches = listOf(createMatch("m-live", MatchState.LIVE))
        val upcomingMatches = listOf(createMatch("m-up", MatchState.UPCOMING))
        val finishedMatches = listOf(createMatch("m-fin", MatchState.FINISHED))

        // Fixed 15m must resolve to 900,000L under any condition
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(liveMatches, setOf("m-live")))
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(upcomingMatches, setOf("m-up")))
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(finishedMatches, setOf("m-fin")))
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(emptyList(), emptySet()))

        assertEquals(900_000L, WidgetAutoSyncManager.calculateInterval(liveMatches, setOf("m-live"), WidgetSyncMode.INTERVAL_15M))
    }

    // =========================================================================
    // 3. Sync Mode: Fixed 30 Minutes (INTERVAL_30M)
    // =========================================================================

    @Test
    fun testInterval30MResolves1800000msRegardlessOfMatchStates() {
        val liveMatches = listOf(createMatch("m-live", MatchState.LIVE))
        val upcomingMatches = listOf(createMatch("m-up", MatchState.UPCOMING))
        val finishedMatches = listOf(createMatch("m-fin", MatchState.FINISHED))

        // Fixed 30m must resolve to 1,800,000L under any condition
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(liveMatches, setOf("m-live")))
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(upcomingMatches, setOf("m-up")))
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(finishedMatches, setOf("m-fin")))
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(emptyList(), emptySet()))

        assertEquals(1_800_000L, WidgetAutoSyncManager.calculateInterval(liveMatches, setOf("m-live"), WidgetSyncMode.INTERVAL_30M))
    }

    // =========================================================================
    // 4. Sync Mode: DISABLED
    // =========================================================================

    @Test
    fun testDisabledModeResolvesNullAndDisablesAutoSync() = runBlocking {
        // resolveIntervalMillis must return null for DISABLED
        assertNull(WidgetSyncMode.DISABLED.resolveIntervalMillis(listOf(createMatch("m", MatchState.LIVE)), setOf("m")))
        assertNull(WidgetSyncMode.DISABLED.resolveIntervalMillis(emptyList(), emptySet()))

        // When settings repository is configured with DISABLED
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        assertEquals(WidgetSyncMode.DISABLED, WidgetAutoSyncManager.getSyncMode(testContext))

        // isAutoSyncEnabled must return false when sync mode is DISABLED
        assertFalse(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))
    }

    // =========================================================================
    // 5. SettingsRepository Integration with WidgetAutoSyncManager
    // =========================================================================

    @Test
    fun testSettingsRepositoryIntegrationAndReactivity() = runBlocking {
        // Initial default: ADAPTIVE
        assertEquals(WidgetSyncMode.ADAPTIVE, WidgetAutoSyncManager.getSyncMode(testContext))
        assertTrue(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))

        // Update to 15m
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
        assertEquals(WidgetSyncMode.INTERVAL_15M, WidgetAutoSyncManager.getSyncMode(testContext))
        assertTrue(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))

        // Update to 30m
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_30M)
        assertEquals(WidgetSyncMode.INTERVAL_30M, WidgetAutoSyncManager.getSyncMode(testContext))
        assertTrue(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))

        // Update to DISABLED
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        assertEquals(WidgetSyncMode.DISABLED, WidgetAutoSyncManager.getSyncMode(testContext))
        assertFalse(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))

        // Switch back to ADAPTIVE
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.ADAPTIVE)
        assertEquals(WidgetSyncMode.ADAPTIVE, WidgetAutoSyncManager.getSyncMode(testContext))
        assertTrue(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))
    }

    // =========================================================================
    // 6. Header Status Text Formatting
    // =========================================================================

    @Test
    fun testGetSyncStatusTextFormattingUnderDifferentModes() = runBlocking {
        // 1. DISABLED Mode
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        val textDisabled = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Expected 'Auto: OFF', got: $textDisabled", textDisabled.contains("Auto: OFF"))

        // 2. 15 Minutes Mode
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
        val text15m = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Expected '(15m)', got: $text15m", text15m.contains("(15m)"))

        // 3. 30 Minutes Mode
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_30M)
        val text30m = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Expected '(30m)', got: $text30m", text30m.contains("(30m)"))

        // 4. ADAPTIVE Mode respects AutoRefreshInterval (default 60s -> 1m)
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.ADAPTIVE)
        val textAdaptiveLive = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = true)
        assertTrue("Expected '(1m)', got: $textAdaptiveLive", textAdaptiveLive.contains("(1m)"))

        // 5. ADAPTIVE Mode without live matches respects the same auto-refresh setting (1m, not 5m!)
        val textAdaptiveNoLive = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Expected '(1m)', got: $textAdaptiveNoLive", textAdaptiveNoLive.contains("(1m)"))

        // 6. ADAPTIVE Mode with custom 30s auto-refresh setting
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        val textAdaptive30s = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Expected '(30s)', got: $textAdaptive30s", textAdaptive30s.contains("(30s)"))

        // 7. ADAPTIVE Mode with custom 2m auto-refresh setting
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)
        val textAdaptive2m = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Expected '(2m)', got: $textAdaptive2m", textAdaptive2m.contains("(2m)"))

        // 8. Auto-refresh OFF disables widget auto sync
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        val textAutoOff = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Expected 'Auto: OFF', got: $textAutoOff", textAutoOff.contains("Auto: OFF"))
    }

    // =========================================================================
    // 7. AutoSync Toggle & Sync Result Recording
    // =========================================================================

    @Test
    fun testAutoSyncTogglingAndSyncResultRecording() = runBlocking {
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.ADAPTIVE)

        // Initial default enabled
        assertTrue(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))

        // Toggle auto-sync OFF
        val toggled1 = WidgetAutoSyncManager.toggleAutoSync(testContext)
        assertFalse(toggled1)
        assertFalse(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))

        // Toggle auto-sync ON
        val toggled2 = WidgetAutoSyncManager.toggleAutoSync(testContext)
        assertTrue(toggled2)
        assertTrue(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))

        // Record sync outcome
        assertEquals(0L, WidgetAutoSyncManager.getLastSyncTime(testContext))
        WidgetAutoSyncManager.recordSyncResult(testContext, success = true)
        assertTrue(WidgetAutoSyncManager.getLastSyncTime(testContext) > 0L)
    }

    // =========================================================================
    // 8. Enum Properties and Safe Deserialization
    // =========================================================================

    @Test
    fun testWidgetSyncModeEnumPropertiesAndSafeFallback() {
        assertEquals("Adaptive", WidgetSyncMode.ADAPTIVE.displayName)
        assertEquals("15 minutes", WidgetSyncMode.INTERVAL_15M.displayName)
        assertEquals("30 minutes", WidgetSyncMode.INTERVAL_30M.displayName)
        assertEquals("Disabled", WidgetSyncMode.DISABLED.displayName)

        // Safe fallback on unknown name
        assertEquals(WidgetSyncMode.ADAPTIVE, WidgetSyncMode.fromNameOrDefault(null))
        assertEquals(WidgetSyncMode.ADAPTIVE, WidgetSyncMode.fromNameOrDefault("UNKNOWN_NAME"))
        assertEquals(WidgetSyncMode.INTERVAL_15M, WidgetSyncMode.fromNameOrDefault("INTERVAL_15M"))
        assertEquals(WidgetSyncMode.DISABLED, WidgetSyncMode.fromNameOrDefault("DISABLED"))
    }

    // =========================================================================
    // 9. Respecting App Auto-Refresh Interval (Fix for 5-minute hardcode)
    // =========================================================================

    @Test
    fun testAdaptiveModeRespectsAutoRefreshSettingForUpcomingMatches() {
        val matches = listOf(
            createMatch("m-upcoming-followed", MatchState.UPCOMING),
            createMatch("m-finished-followed", MatchState.FINISHED)
        )
        val followedIds = setOf("m-upcoming-followed", "m-finished-followed")

        // 1. With default 60s auto-refresh setting -> 60,000ms (1 minute, NOT 5 minutes!)
        val interval60s = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(
            matches, followedIds, AutoRefreshInterval.INTERVAL_60S
        )
        assertEquals(60_000L, interval60s)
        val calc60s = WidgetAutoSyncManager.calculateInterval(
            matches, followedIds, WidgetSyncMode.ADAPTIVE, AutoRefreshInterval.INTERVAL_60S
        )
        assertEquals(60_000L, calc60s)

        // 2. With 30s auto-refresh setting -> 30,000ms
        val interval30s = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(
            matches, followedIds, AutoRefreshInterval.INTERVAL_30S
        )
        assertEquals(30_000L, interval30s)
        val calc30s = WidgetAutoSyncManager.calculateInterval(
            matches, followedIds, WidgetSyncMode.ADAPTIVE, AutoRefreshInterval.INTERVAL_30S
        )
        assertEquals(30_000L, calc30s)

        // 3. With 2m auto-refresh setting -> 120,000ms
        val interval2m = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(
            matches, followedIds, AutoRefreshInterval.INTERVAL_2M
        )
        assertEquals(120_000L, interval2m)
        val calc2m = WidgetAutoSyncManager.calculateInterval(
            matches, followedIds, WidgetSyncMode.ADAPTIVE, AutoRefreshInterval.INTERVAL_2M
        )
        assertEquals(120_000L, calc2m)

        // 4. With OFF auto-refresh setting -> null (disabled)
        val intervalOff = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(
            matches, followedIds, AutoRefreshInterval.OFF
        )
        org.junit.Assert.assertNull(intervalOff)

        // 5. Without autoRefreshInterval parameter (backward compatibility) -> 300,000ms
        val intervalLegacy = WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(matches, followedIds, null)
        assertEquals(300_000L, intervalLegacy)
    }

    @Test
    fun testWidgetAutoSyncManagerDynamicAutoRefreshSettingUpdates() = runBlocking {
        // Initial default: INTERVAL_60S -> status contains (1m)
        assertEquals(AutoRefreshInterval.INTERVAL_60S, WidgetAutoSyncManager.getAutoRefreshInterval(testContext))
        val textDefault = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue(textDefault.contains("(1m)"))

        // Update to 30s
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, WidgetAutoSyncManager.getAutoRefreshInterval(testContext))
        val text30s = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue(text30s.contains("(30s)"))

        // Update to 2m
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)
        assertEquals(AutoRefreshInterval.INTERVAL_2M, WidgetAutoSyncManager.getAutoRefreshInterval(testContext))
        val text2m = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue(text2m.contains("(2m)"))

        // Update to OFF
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        assertEquals(AutoRefreshInterval.OFF, WidgetAutoSyncManager.getAutoRefreshInterval(testContext))
        assertFalse(WidgetAutoSyncManager.isAutoSyncEnabled(testContext))
        val textOff = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue(textOff.contains("Auto: OFF"))
    }
}
