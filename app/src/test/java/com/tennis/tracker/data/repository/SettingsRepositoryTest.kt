package com.tennis.tracker.data.repository

import com.tennis.tracker.data.local.FakeSharedPreferences
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.ui.viewmodel.TourTierFilter
import com.tennis.tracker.domain.model.WidgetSyncMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Dedicated unit test suite for [SettingsRepository] and [SettingsRepositoryImpl].
 *
 * Verifies default preference values, updates across all 5 preference domains,
 * immediate reactive emissions via [StateFlow], cold reload persistence with [FakeSharedPreferences],
 * strict HTTPS validation for telemetry endpoints, and canonical "Reset to Defaults" capability.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        SettingsRepositoryImpl.resetInstanceForTesting()
        fakePrefs = FakeSharedPreferences()
        repository = SettingsRepositoryImpl(fakePrefs)
    }

    // =========================================================================
    // 1. Default Values & Canonical AppSettings.DEFAULT
    // =========================================================================

    @Test
    fun testDefaultSettingsMatchCanonicalAppSettingsDefault() {
        val initialSettings = repository.getSettings()
        assertEquals(AppSettings.DEFAULT, initialSettings)
        assertEquals(AppSettings.DEFAULT, repository.settings.value)

        // 1. Auto-Refresh Interval default: 60 seconds
        assertEquals(AutoRefreshInterval.INTERVAL_60S, initialSettings.autoRefreshInterval)
        assertEquals(60_000L, initialSettings.autoRefreshInterval.periodMillis)
        assertEquals("60 seconds", initialSettings.autoRefreshInterval.displayName)
        assertEquals("Auto 1m", initialSettings.autoRefreshInterval.badgeLabel)
        assertTrue(initialSettings.autoRefreshInterval.isEnabled)

        // 2. Followed Match Retention default: 24 hours
        assertEquals(MatchRetentionPolicy.HOURS_24, initialSettings.matchRetention)
        assertEquals(86_400_000L, initialSettings.matchRetention.durationMillis)
        assertEquals("24 hours", initialSettings.matchRetention.displayName)

        // 3. Widget Background Sync default: Adaptive
        assertEquals(WidgetSyncMode.ADAPTIVE, initialSettings.widgetSyncMode)
        assertEquals("Adaptive", initialSettings.widgetSyncMode.displayName)

        // 4. Default Startup Tournament default: All Tournaments
        assertEquals(StartupTournamentTier.ALL, initialSettings.defaultTournament)
        assertEquals("All Tournaments", initialSettings.defaultTournament.displayName)
        assertEquals(TourTierFilter.ALL, initialSettings.defaultTournament.tourTierFilter)

        // 5. Telemetry & Privacy default: Enabled with canonical HTTPS endpoint
        assertTrue(initialSettings.telemetryEnabled)
        assertEquals("https://telemetry.tennis-tracker.app/api/heartbeat", initialSettings.telemetryEndpoint)
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, initialSettings.telemetryEndpoint)
    }

    // =========================================================================
    // 2. Updating Preference Domains (In-Memory & SharedPreferences Persistence)
    // =========================================================================

    @Test
    fun testUpdateAutoRefreshIntervalPersistsAndUpdatesFlow() = runBlocking {
        val intervals = listOf(
            AutoRefreshInterval.INTERVAL_30S,
            AutoRefreshInterval.INTERVAL_2M,
            AutoRefreshInterval.OFF,
            AutoRefreshInterval.INTERVAL_60S
        )

        for (interval in intervals) {
            repository.updateAutoRefreshInterval(interval)

            // Verify in-memory snapshot and hot StateFlow
            assertEquals(interval, repository.getSettings().autoRefreshInterval)
            assertEquals(interval, repository.settings.value.autoRefreshInterval)

            // Verify SharedPreferences persistence
            assertEquals(
                interval.name,
                fakePrefs.getString(SettingsRepositoryImpl.KEY_AUTO_REFRESH_INTERVAL, null)
            )
        }
    }

    @Test
    fun testUpdateMatchRetentionPersistsAndUpdatesFlow() = runBlocking {
        val policies = listOf(
            MatchRetentionPolicy.HOURS_12,
            MatchRetentionPolicy.HOURS_48,
            MatchRetentionPolicy.DAYS_7,
            MatchRetentionPolicy.NEVER,
            MatchRetentionPolicy.HOURS_24
        )

        for (policy in policies) {
            repository.updateMatchRetention(policy)

            // Verify in-memory snapshot and hot StateFlow
            assertEquals(policy, repository.getSettings().matchRetention)
            assertEquals(policy, repository.settings.value.matchRetention)

            // Verify SharedPreferences persistence
            assertEquals(
                policy.name,
                fakePrefs.getString(SettingsRepositoryImpl.KEY_MATCH_RETENTION, null)
            )
        }
    }

    @Test
    fun testUpdateWidgetSyncModePersistsAndUpdatesFlow() = runBlocking {
        val modes = listOf(
            WidgetSyncMode.INTERVAL_15M,
            WidgetSyncMode.INTERVAL_30M,
            WidgetSyncMode.DISABLED,
            WidgetSyncMode.ADAPTIVE
        )

        for (mode in modes) {
            repository.updateWidgetSyncMode(mode)

            // Verify in-memory snapshot and hot StateFlow
            assertEquals(mode, repository.getSettings().widgetSyncMode)
            assertEquals(mode, repository.settings.value.widgetSyncMode)

            // Verify SharedPreferences persistence
            assertEquals(
                mode.name,
                fakePrefs.getString(SettingsRepositoryImpl.KEY_WIDGET_SYNC_MODE, null)
            )
        }
    }

    @Test
    fun testUpdateDefaultTournamentPersistsAndUpdatesFlow() = runBlocking {
        val tiers = listOf(
            StartupTournamentTier.GRAND_SLAMS,
            StartupTournamentTier.ATP_TOUR,
            StartupTournamentTier.WTA_TOUR,
            StartupTournamentTier.ALL
        )

        for (tier in tiers) {
            repository.updateDefaultTournament(tier)

            // Verify in-memory snapshot and hot StateFlow
            assertEquals(tier, repository.getSettings().defaultTournament)
            assertEquals(tier, repository.settings.value.defaultTournament)

            // Verify SharedPreferences persistence
            assertEquals(
                tier.name,
                fakePrefs.getString(SettingsRepositoryImpl.KEY_DEFAULT_TOURNAMENT, null)
            )
        }
    }

    @Test
    fun testUpdateTelemetryEnabledPersistsAndUpdatesFlow() = runBlocking {
        // Toggle to false
        repository.updateTelemetryEnabled(false)
        assertFalse(repository.getSettings().telemetryEnabled)
        assertFalse(repository.settings.value.telemetryEnabled)
        assertFalse(fakePrefs.getBoolean(SettingsRepositoryImpl.KEY_TELEMETRY_ENABLED, true))

        // Toggle back to true
        repository.updateTelemetryEnabled(true)
        assertTrue(repository.getSettings().telemetryEnabled)
        assertTrue(repository.settings.value.telemetryEnabled)
        assertTrue(fakePrefs.getBoolean(SettingsRepositoryImpl.KEY_TELEMETRY_ENABLED, false))
    }

    // =========================================================================
    // 3. Telemetry Endpoint Validation & Normalization
    // =========================================================================

    @Test
    fun testUpdateTelemetryEndpointValidHttpsAndNormalization() = runBlocking {
        val customUrl = "https://custom.telemetry.example.org/api/v2/pulse"
        repository.updateTelemetryEndpoint(customUrl)

        assertEquals(customUrl, repository.getSettings().telemetryEndpoint)
        assertEquals(customUrl, repository.settings.value.telemetryEndpoint)
        assertEquals(customUrl, fakePrefs.getString(SettingsRepositoryImpl.KEY_TELEMETRY_ENDPOINT, null))

        // Whitespace trimming
        val urlWithWhitespace = "   https://telemetry.example.com/heartbeat   "
        repository.updateTelemetryEndpoint(urlWithWhitespace)
        assertEquals("https://telemetry.example.com/heartbeat", repository.getSettings().telemetryEndpoint)
        assertEquals("https://telemetry.example.com/heartbeat", fakePrefs.getString(SettingsRepositoryImpl.KEY_TELEMETRY_ENDPOINT, null))

        // Case-insensitive HTTPS prefix check
        val uppercaseHttps = "HTTPS://ANALYTICS.PROD.TENNIS.APP/INGEST"
        repository.updateTelemetryEndpoint(uppercaseHttps)
        assertEquals(uppercaseHttps, repository.getSettings().telemetryEndpoint)
    }

    @Test
    fun testUpdateTelemetryEndpointBlankOrEmptyRevertsToDefault() = runBlocking {
        // First set a custom endpoint
        repository.updateTelemetryEndpoint("https://my-temp.org/pulse")
        assertEquals("https://my-temp.org/pulse", repository.getSettings().telemetryEndpoint)

        // Pass empty string -> reverts to canonical default
        repository.updateTelemetryEndpoint("")
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, repository.getSettings().telemetryEndpoint)
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, repository.settings.value.telemetryEndpoint)
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_TELEMETRY_ENDPOINT, null))

        // Pass whitespace-only string -> reverts to canonical default
        repository.updateTelemetryEndpoint("https://another.org/endpoint")
        repository.updateTelemetryEndpoint("     ")
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, repository.getSettings().telemetryEndpoint)
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_TELEMETRY_ENDPOINT, null))
    }

    @Test
    fun testUpdateTelemetryEndpointRejectsInsecureProtocols() = runBlocking {
        val insecureEndpoints = listOf(
            "http://insecure.telemetry.example.com",
            "ftp://files.telemetry.example.com",
            "javascript:void(0)",
            "file:///etc/hosts",
            "custom://app/telemetry"
        )

        for (insecure in insecureEndpoints) {
            try {
                repository.updateTelemetryEndpoint(insecure)
                fail("Expected IllegalArgumentException for insecure endpoint: $insecure")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("HTTPS") == true)
            }
        }

        // Ensure previous setting was not modified or corrupted
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, repository.getSettings().telemetryEndpoint)
    }

    // =========================================================================
    // 4. Reactive StateFlow Emissions
    // =========================================================================

    @Test
    fun testImmediateReactiveEmissionsViaStateFlow() = runBlocking {
        val emissions = mutableListOf<AppSettings>()

        val collectJob = launch(Dispatchers.Unconfined) {
            repository.settings.collect { emissions.add(it) }
        }

        assertEquals(1, emissions.size)
        assertEquals(AppSettings.DEFAULT, emissions.first())

        repository.updateAutoRefreshInterval(AutoRefreshInterval.INTERVAL_30S)
        assertEquals(2, emissions.size)
        assertEquals(AutoRefreshInterval.INTERVAL_30S, emissions.last().autoRefreshInterval)

        repository.updateMatchRetention(MatchRetentionPolicy.HOURS_12)
        assertEquals(3, emissions.size)
        assertEquals(MatchRetentionPolicy.HOURS_12, emissions.last().matchRetention)

        repository.updateWidgetSyncMode(WidgetSyncMode.INTERVAL_15M)
        assertEquals(4, emissions.size)
        assertEquals(WidgetSyncMode.INTERVAL_15M, emissions.last().widgetSyncMode)

        repository.updateDefaultTournament(StartupTournamentTier.GRAND_SLAMS)
        assertEquals(5, emissions.size)
        assertEquals(StartupTournamentTier.GRAND_SLAMS, emissions.last().defaultTournament)

        repository.updateTelemetryEnabled(false)
        assertEquals(6, emissions.size)
        assertFalse(emissions.last().telemetryEnabled)

        collectJob.cancel()
    }

    // =========================================================================
    // 5. Cold Reload Persistence & Corrupt Value Fault Tolerance
    // =========================================================================

    @Test
    fun testColdReloadPersistenceFromExistingSharedPreferences() {
        // Populate preferences simulating prior user session
        fakePrefs.edit()
            .putString(SettingsRepositoryImpl.KEY_AUTO_REFRESH_INTERVAL, AutoRefreshInterval.INTERVAL_2M.name)
            .putString(SettingsRepositoryImpl.KEY_MATCH_RETENTION, MatchRetentionPolicy.DAYS_7.name)
            .putString(SettingsRepositoryImpl.KEY_WIDGET_SYNC_MODE, WidgetSyncMode.INTERVAL_30M.name)
            .putString(SettingsRepositoryImpl.KEY_DEFAULT_TOURNAMENT, StartupTournamentTier.WTA_TOUR.name)
            .putBoolean(SettingsRepositoryImpl.KEY_TELEMETRY_ENABLED, false)
            .putString(SettingsRepositoryImpl.KEY_TELEMETRY_ENDPOINT, "https://cold-reload.example.com/api")
            .apply()

        // Create fresh repository instance simulating app cold startup
        val coldRepository = SettingsRepositoryImpl(fakePrefs)
        val loaded = coldRepository.getSettings()

        assertEquals(AutoRefreshInterval.INTERVAL_2M, loaded.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.DAYS_7, loaded.matchRetention)
        assertEquals(WidgetSyncMode.INTERVAL_30M, loaded.widgetSyncMode)
        assertEquals(StartupTournamentTier.WTA_TOUR, loaded.defaultTournament)
        assertFalse(loaded.telemetryEnabled)
        assertEquals("https://cold-reload.example.com/api", loaded.telemetryEndpoint)
        assertEquals(loaded, coldRepository.settings.value)
    }

    @Test
    fun testColdReloadWithCorruptOrUnknownEnumValuesFallbacksSafely() {
        // Corrupt preference entries
        fakePrefs.edit()
            .putString(SettingsRepositoryImpl.KEY_AUTO_REFRESH_INTERVAL, "INVALID_INTERVAL_UNKNOWN")
            .putString(SettingsRepositoryImpl.KEY_MATCH_RETENTION, "CORRUPT_RETENTION_VALUE")
            .putString(SettingsRepositoryImpl.KEY_WIDGET_SYNC_MODE, "NON_EXISTENT_WIDGET_MODE")
            .putString(SettingsRepositoryImpl.KEY_DEFAULT_TOURNAMENT, "UNKNOWN_TIER_ENUM")
            .putString(SettingsRepositoryImpl.KEY_TELEMETRY_ENDPOINT, "http://insecure-in-disk.org")
            .apply()

        // Constructor must not throw IllegalArgumentException or crash on unknown enum names
        val safeRepository = SettingsRepositoryImpl(fakePrefs)
        val fallbackSettings = safeRepository.getSettings()

        assertEquals(AutoRefreshInterval.INTERVAL_60S, fallbackSettings.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.HOURS_24, fallbackSettings.matchRetention)
        assertEquals(WidgetSyncMode.ADAPTIVE, fallbackSettings.widgetSyncMode)
        assertEquals(StartupTournamentTier.ALL, fallbackSettings.defaultTournament)
        assertTrue(fallbackSettings.telemetryEnabled)
        assertEquals(AppSettings.DEFAULT_TELEMETRY_ENDPOINT, fallbackSettings.telemetryEndpoint)
    }

    // =========================================================================
    // 6. Reset to Defaults Capability
    // =========================================================================

    @Test
    fun testResetToDefaultsRestoresAllCanonicalValuesAndClearsStorage() = runBlocking {
        // Mutate all preferences
        repository.updateAutoRefreshInterval(AutoRefreshInterval.OFF)
        repository.updateMatchRetention(MatchRetentionPolicy.NEVER)
        repository.updateWidgetSyncMode(WidgetSyncMode.DISABLED)
        repository.updateDefaultTournament(StartupTournamentTier.ATP_TOUR)
        repository.updateTelemetryEnabled(false)
        repository.updateTelemetryEndpoint("https://custom.telemetry.org/heartbeat")

        // Precondition check
        val customSettings = repository.getSettings()
        assertEquals(AutoRefreshInterval.OFF, customSettings.autoRefreshInterval)
        assertEquals(MatchRetentionPolicy.NEVER, customSettings.matchRetention)
        assertEquals(WidgetSyncMode.DISABLED, customSettings.widgetSyncMode)
        assertEquals(StartupTournamentTier.ATP_TOUR, customSettings.defaultTournament)
        assertFalse(customSettings.telemetryEnabled)
        assertEquals("https://custom.telemetry.org/heartbeat", customSettings.telemetryEndpoint)

        // Perform Reset
        repository.resetToDefaults()

        // Postcondition verification
        assertEquals(AppSettings.DEFAULT, repository.getSettings())
        assertEquals(AppSettings.DEFAULT, repository.settings.value)
        assertEquals(0, fakePrefs.all.size)

        // Verify cold boot after reset also maintains defaults
        val postResetRepo = SettingsRepositoryImpl(fakePrefs)
        assertEquals(AppSettings.DEFAULT, postResetRepo.getSettings())
    }

    // =========================================================================
    // 7. Singleton Instance Management & Reset
    // =========================================================================

    @Test
    fun testSingletonInstanceCreationAndReset() {
        val instance1 = SettingsRepositoryImpl.getInstance(fakePrefs)
        val instance2 = SettingsRepositoryImpl.getInstance(fakePrefs)
        assertEquals(instance1, instance2)

        SettingsRepositoryImpl.resetInstanceForTesting()
        val instance3 = SettingsRepositoryImpl.getInstance(fakePrefs)
        // New instance after reset
        assertTrue(instance1 !== instance3)
    }
}
