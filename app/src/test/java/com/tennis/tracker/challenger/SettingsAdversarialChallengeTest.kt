package com.tennis.tracker.challenger

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tennis.tracker.data.local.FakeSharedPreferences
import com.tennis.tracker.data.local.FollowManager
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import com.tennis.tracker.data.telemetry.AnonymousTelemetryService
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.domain.repository.ScoreboardRepository
import com.tennis.tracker.ui.viewmodel.ScoreboardViewModel
import com.tennis.tracker.ui.viewmodel.TourTierFilter
import com.tennis.tracker.ui.widget.WidgetAutoSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2 Adversarial Stress Test Suite:
 * Focus Areas:
 * 1. UI State & Reactivity:
 *    - Stateless bottom sheet contracts
 *    - Immediate emissions on every control without Save button
 *    - AlertDialog confirmation on Reset to Defaults and preservation of user watchlist
 *    - Strict HTTPS validation & whitespace trimming in custom endpoint input
 * 2. TopAppBar Actions & Reactivity:
 *    - Settings gear icon visibility and navigation
 *    - Dynamic auto-refresh pill badge rendering and tap navigation
 *    - Immediate polling timer modulation upon interval changes
 * 3. WidgetAutoSyncManager Dynamic Interval Resolution:
 *    - ADAPTIVE, 15m, 30m, and DISABLED resolution across all match permutations
 *    - Header status string formatting
 *    - High-concurrency query safety
 * 4. Telemetry & GDPR Right to Erasure:
 *    - Bidirectional toggle synchronization with SettingsRepository
 *    - Immediate purging of device UUID and heartbeat timestamp upon opt-out
 *    - Decooupled UUID regeneration upon opt-in
 *    - Suppression of network transmissions when opted out
 *    - High-concurrency stress test with zero race conditions
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAdversarialChallengeTest {

    private lateinit var settingsPrefs: FakeSharedPreferences
    private lateinit var widgetPrefs: FakeSharedPreferences
    private lateinit var telemetryPrefs: FakeSharedPreferences
    private lateinit var followPrefs: FakeSharedPreferences
    private lateinit var settingsRepo: SettingsRepositoryImpl
    private lateinit var testContext: Context
    private val testDispatcher = StandardTestDispatcher()

    private class ChallengerTestContext(
        private val prefsMap: Map<String, SharedPreferences>
    ) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsMap[name] ?: prefsMap.values.first()
        }
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.tennis.tracker"
    }

    private fun createTestMatch(
        id: String,
        state: MatchState,
        tournamentName: String = "US Open"
    ): Match {
        return Match(
            id = id,
            tournamentName = tournamentName,
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = state,
            statusDetail = state.name,
            player1 = Competitor(id = "${id}_p1", name = "Carlos Alcaraz", isServing = true),
            player2 = Competitor(id = "${id}_p2", name = "Jannik Sinner", isServing = false),
            venue = "Arthur Ashe Stadium"
        )
    }

    private fun createMockOkHttpClient(statusCode: Int = 200, callCounter: AtomicInteger? = null): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCounter?.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body("""{"status":"ok"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsPrefs = FakeSharedPreferences()
        widgetPrefs = FakeSharedPreferences()
        telemetryPrefs = FakeSharedPreferences()
        followPrefs = FakeSharedPreferences()

        val prefsMap = mapOf(
            "tennis_tracker_app_settings" to settingsPrefs,
            WidgetAutoSyncManager.PREFS_NAME to widgetPrefs,
            "tennis_anonymous_telemetry_prefs" to telemetryPrefs,
            "tennis_tracker_prefs" to followPrefs
        )
        testContext = ChallengerTestContext(prefsMap)

        SettingsRepositoryImpl.resetInstanceForTesting()
        AnonymousTelemetryService.resetInstanceForTesting()
        settingsRepo = SettingsRepositoryImpl(settingsPrefs)
        WidgetAutoSyncManager.attachSettingsRepository(settingsRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AnonymousTelemetryService.resetInstanceForTesting()
        SettingsRepositoryImpl.resetInstanceForTesting()
    }

    // =========================================================================
    // FOCUS AREA 1: UI State & Reactivity & Reset Confirmation
    // =========================================================================

    @Test
    fun challenge1_statelessUiAndImmediateEmissionsWithoutSaveButton() = runBlocking {
        // Contract: Every setting change emits immediately through its dedicated callback
        val emittedInterval = mutableListOf<AutoRefreshInterval>()
        val emittedRetention = mutableListOf<MatchRetentionPolicy>()
        val emittedWidgetMode = mutableListOf<WidgetSyncMode>()
        val emittedStartupTier = mutableListOf<StartupTournamentTier>()
        val emittedTelemetry = mutableListOf<Boolean>()
        val emittedEndpoint = mutableListOf<String>()

        // Simulate SettingsBottomSheet hoisted callbacks
        val onIntervalChanged: (AutoRefreshInterval) -> Unit = {
            emittedInterval.add(it)
            runBlocking { settingsRepo.updateAutoRefreshInterval(it) }
        }
        val onRetentionChanged: (MatchRetentionPolicy) -> Unit = {
            emittedRetention.add(it)
            runBlocking { settingsRepo.updateMatchRetention(it) }
        }
        val onWidgetModeChanged: (WidgetSyncMode) -> Unit = {
            emittedWidgetMode.add(it)
            runBlocking { settingsRepo.updateWidgetSyncMode(it) }
        }
        val onStartupTierChanged: (StartupTournamentTier) -> Unit = {
            emittedStartupTier.add(it)
            runBlocking { settingsRepo.updateDefaultTournament(it) }
        }
        val onTelemetryToggled: (Boolean) -> Unit = {
            emittedTelemetry.add(it)
            runBlocking { settingsRepo.updateTelemetryEnabled(it) }
        }
        val onEndpointChanged: (String) -> Unit = {
            emittedEndpoint.add(it)
            runBlocking { settingsRepo.updateTelemetryEndpoint(it) }
        }

        // 1. Changing interval directly emits and updates StateFlow without intermediate save
        onIntervalChanged(AutoRefreshInterval.INTERVAL_30S)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, settingsRepo.settings.value.autoRefreshInterval)
        assertEquals(listOf(AutoRefreshInterval.INTERVAL_30S), emittedInterval)

        // 2. Changing retention directly emits
        onRetentionChanged(MatchRetentionPolicy.NEVER)
        assertEquals(MatchRetentionPolicy.NEVER, settingsRepo.settings.value.matchRetention)
        assertEquals(listOf(MatchRetentionPolicy.NEVER), emittedRetention)

        // 3. Changing widget mode directly emits
        onWidgetModeChanged(WidgetSyncMode.DISABLED)
        assertEquals(WidgetSyncMode.DISABLED, settingsRepo.settings.value.widgetSyncMode)
        assertEquals(listOf(WidgetSyncMode.DISABLED), emittedWidgetMode)

        // 4. Changing startup tier directly emits
        onStartupTierChanged(StartupTournamentTier.WTA_TOUR)
        assertEquals(StartupTournamentTier.WTA_TOUR, settingsRepo.settings.value.defaultTournament)
        assertEquals(listOf(StartupTournamentTier.WTA_TOUR), emittedStartupTier)

        // 5. Changing telemetry directly emits
        onTelemetryToggled(false)
        assertFalse(settingsRepo.settings.value.telemetryEnabled)
        assertEquals(listOf(false), emittedTelemetry)

        // 6. Changing endpoint directly emits
        val customUrl = "https://custom.telemetry.org/heartbeat"
        onEndpointChanged(customUrl)
        assertEquals(customUrl, settingsRepo.settings.value.telemetryEndpoint)
        assertEquals(listOf(customUrl), emittedEndpoint)
    }

    @Test
    fun challenge2_resetConfirmationRestoresDefaultsWhilePreservingWatchlist() = runBlocking {
        val followManager = FollowManager(preferences = followPrefs)

        // User follows 2 matches
        followManager.followMatch(testContext, "match_1")
        followManager.followMatch(testContext, "match_2")
        assertEquals(setOf("match_1", "match_2"), followManager.getFollowedMatchIds())

        // User modifies all 5 settings
        settingsRepo.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        settingsRepo.updateMatchRetention(MatchRetentionPolicy.DAYS_7)
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
        settingsRepo.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
        settingsRepo.updateTelemetryEnabled(false)
        settingsRepo.updateTelemetryEndpoint("https://telemetry.custom-enterprise.com/api")

        val modified = settingsRepo.getSettings()
        assertNotEquals(AppSettings.DEFAULT, modified)

        // Reset dialog simulation:
        // Clicking Cancel maintains modified state
        var dialogConfirmed = false
        val onResetCancelled: () -> Unit = { /* do nothing */ }
        onResetCancelled()
        assertEquals(modified, settingsRepo.getSettings())

        // Clicking Confirm executes resetToDefaults
        val onResetConfirmed: () -> Unit = {
            dialogConfirmed = true
            runBlocking { settingsRepo.resetToDefaults() }
        }
        onResetConfirmed()
        assertTrue(dialogConfirmed)

        // All 5 settings must be restored to canonical defaults
        val resetSettings = settingsRepo.getSettings()
        assertEquals(AppSettings.DEFAULT, resetSettings)
        assertEquals(AutoRefreshInterval.INTERVAL_60S, resetSettings.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_24, resetSettings.matchRetention)
        assertEquals(WidgetSyncMode.ADAPTIVE, resetSettings.widgetSyncMode)
        assertEquals(StartupTournamentTier.ALL, resetSettings.defaultTournament)
        assertTrue(resetSettings.telemetryEnabled)
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, resetSettings.telemetryEndpoint)

        // Followed matches must be 100% PRESERVED
        assertEquals(setOf("match_1", "match_2"), followManager.getFollowedMatchIds())
    }

    @Test
    fun challenge3_endpointTextFieldInputSanitizationAndTrailingReset() = runBlocking {
        // UI validation logic simulation: only emit when trimmed value starts with https://
        val emittedEndpoints = mutableListOf<String>()
        val handleInput: (String) -> Unit = { input ->
            val trimmed = input.trim()
            if (trimmed.startsWith("https://", ignoreCase = true)) {
                emittedEndpoints.add(trimmed)
                runBlocking { settingsRepo.updateTelemetryEndpoint(trimmed) }
            }
        }

        // 1. Insecure URLs must not emit
        handleInput("http://insecure.example.com")
        handleInput("ftp://server.example.com")
        handleInput("javascript:alert(1)")
        handleInput("file:///etc/passwd")
        handleInput("")
        handleInput("custom-scheme://endpoint")
        assertTrue("Insecure URLs must not emit updates", emittedEndpoints.isEmpty())
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, settingsRepo.getSettings().telemetryEndpoint)

        // 2. Whitespace-padded HTTPS URL is trimmed and accepted
        handleInput("   https://telemetry.clean.io/api   ")
        assertEquals(listOf("https://telemetry.clean.io/api"), emittedEndpoints)
        assertEquals("https://telemetry.clean.io/api", settingsRepo.getSettings().telemetryEndpoint)

        // 3. Mixed-case HTTPS is accepted
        handleInput("HTTPS://SECURE.DOMAIN.COM/HEARTBEAT")
        assertEquals(2, emittedEndpoints.size)
        assertEquals("HTTPS://SECURE.DOMAIN.COM/HEARTBEAT", settingsRepo.getSettings().telemetryEndpoint)

        // 4. Trailing reset icon action restores default endpoint immediately
        val onResetTrailingIconClicked: () -> Unit = {
            runBlocking { settingsRepo.updateTelemetryEndpoint(AppSettings.DEFAULT_TELEMETRY_ENDPOINT) }
        }
        onResetTrailingIconClicked()
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, settingsRepo.getSettings().telemetryEndpoint)
    }

    // =========================================================================
    // FOCUS AREA 2: TopAppBar Actions & Reactivity
    // =========================================================================

    @Test
    fun challenge4_topAppBarBadgeLabelsAndDotIndicatorStatus() {
        // Test AutoRefreshInterval badgeLabel, periodMillis, and isEnabled
        // 30s: "Auto 30s", enabled = true
        assertEquals("Auto 30s", AutoRefreshInterval.INTERVAL_30S.badgeLabel)
        assertEquals(30_000L, AutoRefreshInterval.INTERVAL_30S.periodMillis)
        assertTrue(AutoRefreshInterval.INTERVAL_30S.isEnabled)

        // 60s: "Auto 1m", enabled = true
        assertEquals("Auto 1m", AutoRefreshInterval.INTERVAL_60S.badgeLabel)
        assertEquals(60_000L, AutoRefreshInterval.INTERVAL_60S.periodMillis)
        assertTrue(AutoRefreshInterval.INTERVAL_60S.isEnabled)

        // 2m: "Auto 2m", enabled = true
        assertEquals("Auto 2m", AutoRefreshInterval.INTERVAL_2M.badgeLabel)
        assertEquals(120_000L, AutoRefreshInterval.INTERVAL_2M.periodMillis)
        assertTrue(AutoRefreshInterval.INTERVAL_2M.isEnabled)

        // OFF: "Manual", enabled = false
        assertEquals("Manual", AutoRefreshInterval.OFF.badgeLabel)
        assertEquals(0L, AutoRefreshInterval.OFF.periodMillis)
        assertFalse(AutoRefreshInterval.OFF.isEnabled)

        // Deserialization fallbacks
        assertEquals(AutoRefreshInterval.INTERVAL_60S, AutoRefreshInterval.fromNameOrDefault(null))
        assertEquals(AutoRefreshInterval.INTERVAL_60S, AutoRefreshInterval.fromNameOrDefault("INVALID_NAME"))
        assertEquals(AutoRefreshInterval.INTERVAL_30S, AutoRefreshInterval.fromNameOrDefault("INTERVAL_30S"))
        assertEquals(AutoRefreshInterval.OFF, AutoRefreshInterval.fromNameOrDefault("OFF"))
    }

    @Test
    fun challenge5_viewModelSettingsReactivityAndCadenceModulation() = runBlocking {
        val fakeRepo = object : ScoreboardRepository {
            private var currentTour: Tournament = Tournaments.ALL
            override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> = Result.success(emptyList())
            override fun getOfflineScoreboard(): List<Match> = emptyList()
            override suspend fun getScoreboardForTournament(tournament: Tournament, forceRefresh: Boolean): Result<List<Match>> = Result.success(emptyList())
            override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> = Result.failure(NoSuchElementException())
            override fun getActiveTournament(): Tournament = currentTour
            override fun setActiveTournament(tournament: Tournament) { currentTour = tournament }
        }
        val viewModel = ScoreboardViewModel(repository = fakeRepo, settingsRepository = settingsRepo)
        try {
            // 1. Initial State matches repository default (Auto 1m)
            assertEquals(AutoRefreshInterval.INTERVAL_60S, viewModel.settings.value.autoRefreshInterval)
            assertEquals("Auto 1m", viewModel.settings.value.autoRefreshInterval.badgeLabel)

            // 2. Modifying interval to 30s immediately updates ViewModel StateFlow
            viewModel.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
            testDispatcher.scheduler.runCurrent()
            assertEquals(AutoRefreshInterval.INTERVAL_30S, viewModel.settings.value.autoRefreshInterval)
            assertEquals("Auto 30s", viewModel.settings.value.autoRefreshInterval.badgeLabel)

            // 3. Modifying interval to OFF stops auto-refresh and updates badge to Manual
            viewModel.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
            testDispatcher.scheduler.runCurrent()
            assertEquals(AutoRefreshInterval.OFF, viewModel.settings.value.autoRefreshInterval)
            assertEquals("Manual", viewModel.settings.value.autoRefreshInterval.badgeLabel)
            assertFalse(viewModel.settings.value.autoRefreshInterval.isEnabled)

            // 4. Modifying interval back to 2m resumes auto-refresh
            viewModel.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_2M)
            testDispatcher.scheduler.runCurrent()
            assertEquals(AutoRefreshInterval.INTERVAL_2M, viewModel.settings.value.autoRefreshInterval)
            assertEquals("Auto 2m", viewModel.settings.value.autoRefreshInterval.badgeLabel)
            assertTrue(viewModel.settings.value.autoRefreshInterval.isEnabled)
        } finally {
            viewModel.cleanup()
        }
    }

    // =========================================================================
    // FOCUS AREA 3: WidgetAutoSyncManager Dynamic Interval Resolution
    // =========================================================================

    @Test
    fun challenge6_widgetSyncModeDynamicIntervalResolutionUnderAllPermutations() {
        val liveMatch = createTestMatch("m_live", MatchState.LIVE)
        val upcomingMatch = createTestMatch("m_up", MatchState.UPCOMING)
        val finishedMatch = createTestMatch("m_fin", MatchState.FINISHED)

        // 1. ADAPTIVE mode permutations:
        // Followed match is LIVE -> 1m (60_000L)
        assertEquals(60_000L, WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(listOf(liveMatch, upcomingMatch), setOf("m_live")))
        assertEquals(60_000L, WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(listOf(liveMatch), emptySet()))

        // Followed match is UPCOMING -> 5m (300_000L)
        assertEquals(300_000L, WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(listOf(liveMatch, upcomingMatch), setOf("m_up")))
        assertEquals(300_000L, WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(listOf(upcomingMatch, finishedMatch), emptySet()))

        // Followed matches are FINISHED only -> 15m (900_000L)
        assertEquals(900_000L, WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(listOf(liveMatch, finishedMatch), setOf("m_fin")))
        assertEquals(900_000L, WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(listOf(finishedMatch), emptySet()))

        // Empty matches -> 15m (900_000L)
        assertEquals(900_000L, WidgetSyncMode.ADAPTIVE.resolveIntervalMillis(emptyList(), emptySet()))

        // 2. INTERVAL_15M mode permutations (must be constant 15m regardless of match states)
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(listOf(liveMatch), setOf("m_live")))
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(listOf(upcomingMatch), setOf("m_up")))
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(listOf(finishedMatch), setOf("m_fin")))
        assertEquals(900_000L, WidgetSyncMode.INTERVAL_15M.resolveIntervalMillis(emptyList(), emptySet()))

        // 3. INTERVAL_30M mode permutations (must be constant 30m regardless of match states)
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(listOf(liveMatch), setOf("m_live")))
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(listOf(upcomingMatch), setOf("m_up")))
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(listOf(finishedMatch), setOf("m_fin")))
        assertEquals(1_800_000L, WidgetSyncMode.INTERVAL_30M.resolveIntervalMillis(emptyList(), emptySet()))

        // 4. DISABLED mode permutations (must be null)
        assertNull(WidgetSyncMode.DISABLED.resolveIntervalMillis(listOf(liveMatch), setOf("m_live")))
        assertNull(WidgetSyncMode.DISABLED.resolveIntervalMillis(listOf(upcomingMatch), setOf("m_up")))
        assertNull(WidgetSyncMode.DISABLED.resolveIntervalMillis(listOf(finishedMatch), setOf("m_fin")))
        assertNull(WidgetSyncMode.DISABLED.resolveIntervalMillis(emptyList(), emptySet()))
    }

    @Test
    fun challenge7_widgetSyncManagerHeaderStatusFormatting() = runBlocking {
        // Disabled mode -> "Auto: OFF"
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        val disabledText = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = true)
        assertTrue("Disabled text must contain 'Auto: OFF', got: $disabledText", disabledText.contains("Auto: OFF"))

        // 15m mode -> "(15m)"
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
        val text15m = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = true)
        assertTrue("15m text must contain '(15m)', got: $text15m", text15m.contains("(15m)"))

        // 30m mode -> "(30m)"
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_30M)
        val text30m = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("30m text must contain '(30m)', got: $text30m", text30m.contains("(30m)"))

        // Adaptive with live matches -> "(1m)"
        settingsRepo.updateWidgetSyncMode(WidgetSyncMode.ADAPTIVE)
        val textAdaptiveLive = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = true)
        assertTrue("Adaptive live text must contain '(1m)', got: $textAdaptiveLive", textAdaptiveLive.contains("(1m)"))

        // Adaptive without live matches respects auto-refresh setting (1m)
        val textAdaptiveUpcoming = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = false)
        assertTrue("Adaptive non-live text must contain '(1m)', got: $textAdaptiveUpcoming", textAdaptiveUpcoming.contains("(1m)"))
    }

    @Test
    fun challenge8_widgetAutoSyncManagerConcurrencySafety() {
        val executor = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(100)
        val exceptions = mutableListOf<Throwable>()
        val modes = listOf(WidgetSyncMode.ADAPTIVE, WidgetSyncMode.INTERVAL_15M, WidgetSyncMode.INTERVAL_30M, WidgetSyncMode.DISABLED)
        val matches = listOf(createTestMatch("m1", MatchState.LIVE), createTestMatch("m2", MatchState.UPCOMING))

        for (i in 0 until 100) {
            val mode = modes[i % modes.size]
            executor.submit {
                try {
                    val interval = WidgetAutoSyncManager.calculateInterval(matches, setOf("m1"), mode)
                    assertTrue(interval > 0L)
                    val status = WidgetAutoSyncManager.getSyncStatusText(testContext, hasLiveMatches = (i % 2 == 0), mode = mode)
                    assertTrue(status.isNotEmpty())
                } catch (t: Throwable) {
                    synchronized(exceptions) { exceptions.add(t) }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Expected 0 concurrency exceptions, found: ${exceptions.size}", exceptions.isEmpty())
    }

    // =========================================================================
    // FOCUS AREA 4: Telemetry & GDPR Right to Erasure
    // =========================================================================

    @Test
    fun challenge9_telemetryServiceToggleSyncAndImmediateGdprErasure() = runBlocking {
        val mockHttpClient = createMockOkHttpClient(200)
        val service = AnonymousTelemetryService(
            context = testContext,
            client = mockHttpClient,
            settingsRepository = settingsRepo
        )

        // 1. Initial State: Enabled
        assertTrue(service.isTelemetryEnabled())
        assertTrue(settingsRepo.getSettings().telemetryEnabled)

        // 2. Generate Anonymous UUID and verify persistence
        val initialUuid = service.getAnonymousId()
        assertNotNull(initialUuid)
        assertTrue(initialUuid.isNotEmpty())
        assertEquals(initialUuid, telemetryPrefs.getString("anonymous_device_uuid", null))

        // Record a heartbeat timestamp in prefs
        val heartbeatTime = System.currentTimeMillis()
        telemetryPrefs.edit().putLong("last_heartbeat_ms", heartbeatTime).apply()
        assertEquals(heartbeatTime, telemetryPrefs.getLong("last_heartbeat_ms", 0L))

        // 3. User opts out: setTelemetryEnabled(false)
        service.setTelemetryEnabled(false)
        assertFalse("DEFECT: service.isTelemetryEnabled() must return false immediately after setTelemetryEnabled(false), but returns true due to async repository delegation", service.isTelemetryEnabled())

        // 4. GDPR Right to Erasure: Stored UUID and heartbeat timestamp MUST be purged immediately
        assertNull("Stored anonymous UUID must be purged upon opt-out", telemetryPrefs.getString("anonymous_device_uuid", null))
        assertEquals("Stored heartbeat timestamp must be purged upon opt-out", 0L, telemetryPrefs.getLong("last_heartbeat_ms", 0L))

        // 5. Verification: When opted out, sendStartupHeartbeat returns false immediately without network call
        val networkCallCount = AtomicInteger(0)
        val countingClient = createMockOkHttpClient(200, networkCallCount)
        val guardedService = AnonymousTelemetryService(
            context = testContext,
            client = countingClient,
            settingsRepository = settingsRepo
        )
        assertFalse(guardedService.isTelemetryEnabled())
        val heartbeatResult = guardedService.sendStartupHeartbeat(force = true)
        assertFalse("Heartbeat transmission must be blocked when opted out", heartbeatResult)
        assertEquals("Zero network calls must be executed when opted out", 0, networkCallCount.get())

        // 6. User opts back in: A completely decoupled, fresh UUID must be generated
        guardedService.setTelemetryEnabled(true)
        assertTrue(guardedService.isTelemetryEnabled())
        val newUuid = guardedService.getAnonymousId()
        assertNotNull(newUuid)
        assertNotEquals("New UUID upon re-enabling must be completely decoupled from previous UUID (unlinkability)", initialUuid, newUuid)
    }

    @Test
    fun challenge10_telemetrySyncFromSettingsRepositoryStateFlowPurgesUuid() = runBlocking {
        val service = AnonymousTelemetryService(
            context = testContext,
            client = createMockOkHttpClient(200),
            settingsRepository = settingsRepo
        )

        // User generates UUID while enabled
        val uuid = service.getAnonymousId()
        assertNotNull(uuid)
        assertEquals(uuid, telemetryPrefs.getString("anonymous_device_uuid", null))

        // Opt out via SettingsRepository
        settingsRepo.updateTelemetryEnabled(false)

        // Wait a brief moment for StateFlow collection
        var purged = false
        for (i in 0 until 20) {
            if (!service.isTelemetryEnabled() && !telemetryPrefs.contains("anonymous_device_uuid")) {
                purged = true
                break
            }
            kotlinx.coroutines.delay(20)
        }

        assertTrue("SettingsRepository update must trigger immediate UUID purge in AnonymousTelemetryService", purged)
        assertNull(telemetryPrefs.getString("anonymous_device_uuid", null))
        assertFalse(service.isTelemetryEnabled())
    }

    @Test
    fun challenge11_highConcurrencyTelemetryOptOutStressHarness() {
        val service = AnonymousTelemetryService(
            context = testContext,
            client = createMockOkHttpClient(200),
            settingsRepository = settingsRepo
        )

        val executor = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(100)
        val errors = mutableListOf<String>()

        for (i in 0 until 100) {
            val shouldEnable = (i % 2 == 0)
            executor.submit {
                try {
                    service.setTelemetryEnabled(shouldEnable)
                    if (!shouldEnable) {
                        // When disabled, verify that no thread reads a stale UUID from prefs while in disabled state
                        if (!service.isTelemetryEnabled()) {
                            val storedUuid = telemetryPrefs.getString("anonymous_device_uuid", null)
                            if (storedUuid != null && !service.isTelemetryEnabled()) {
                                synchronized(errors) {
                                    errors.add("Found lingering UUID '$storedUuid' after opt-out at iteration $i")
                                }
                            }
                        }
                    } else {
                        val id = service.getAnonymousId()
                        if (id.isEmpty()) {
                            synchronized(errors) {
                                errors.add("Empty UUID generated at iteration $i")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add("Exception: ${t.message}") }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("Telemetry opt-out stress harness detected errors:\n${errors.joinToString("\n")}", errors.isEmpty())
    }
}
