package com.tennis.tracker.data.bugreport

import com.tennis.tracker.data.local.FakeSharedPreferences
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import com.tennis.tracker.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Adversarial stress and edge-case test suite for AppSettings reactivity and persistence.
 * Verifies boundaries: blank endpoint fallback, non-https rejection, whitespace token trimming,
 * cold storage corruption resilience, and concurrent access safety.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsAdversarialStressTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        SettingsRepositoryImpl.resetInstanceForTesting()
        fakePrefs = FakeSharedPreferences()
        repository = SettingsRepositoryImpl(fakePrefs)
    }

    // =========================================================================
    // 1. Endpoint Edge Cases & Non-HTTPS Rejections
    // =========================================================================

    @Test
    fun testUpdateBugReportEndpoint_rejectsAllNonHttpsSchemes() = runTest {
        val invalidEndpoints = listOf(
            "http://insecure.tracker.app/bug-report",
            "HTTP://UPPERCASE-INSECURE.APP/BUG-REPORT",
            "ftp://files.tracker.app/upload",
            "file:///sdcard/Download/exploit.json",
            "javascript:alert(document.cookie)",
            "ws://live.tracker.app/socket",
            "wss://live.tracker.app/socket", // not starting with https://
            "//schemeless.example.com/api",
            "custom-scheme://endpoint",
            "http://127.0.0.1:8080/bug-report",
            "http://localhost:8080/bug-report"
        )

        for (endpoint in invalidEndpoints) {
            try {
                repository.updateBugReportEndpoint(endpoint)
                fail("Expected IllegalArgumentException for insecure/invalid endpoint: $endpoint")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "Error message should mention HTTPS requirement for: $endpoint",
                    e.message?.contains("https", ignoreCase = true) == true
                )
            }
        }

        // Verify repository state was NOT corrupted and remains default
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, repository.getSettings().bugReportEndpoint)
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, repository.settings.value.bugReportEndpoint)
    }

    @Test
    fun testUpdateBugReportEndpoint_acceptsValidHttpsVariationsAndTrims() = runTest {
        val testCases = listOf(
            "https://custom-relay.example.com/api" to "https://custom-relay.example.com/api",
            "HTTPS://UPPERCASE-HTTPS.EXAMPLE.COM/RELAY" to "HTTPS://UPPERCASE-HTTPS.EXAMPLE.COM/RELAY",
            "Https://MixedCase.Example.Com/report" to "Https://MixedCase.Example.Com/report",
            "   https://whitespace-padded.example.com/bugs   \t\n" to "https://whitespace-padded.example.com/bugs"
        )

        for ((input, expected) in testCases) {
            repository.updateBugReportEndpoint(input)
            assertEquals(expected, repository.getSettings().bugReportEndpoint)
            assertEquals(expected, repository.settings.value.bugReportEndpoint)
            assertEquals(expected, fakePrefs.getString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, null))
        }
    }

    @Test
    fun testUpdateBugReportEndpoint_blankFallbackVariations() = runTest {
        val blankInputs = listOf(
            "",
            " ",
            "   ",
            "\t",
            "\n",
            "\r\n",
            "   \t\n   "
        )

        for (blank in blankInputs) {
            // First set to custom endpoint
            repository.updateBugReportEndpoint("https://temp-endpoint.example.com/report")
            assertEquals("https://temp-endpoint.example.com/report", repository.getSettings().bugReportEndpoint)

            // Now reset with blank input
            repository.updateBugReportEndpoint(blank)
            assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, repository.getSettings().bugReportEndpoint)
            assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, repository.settings.value.bugReportEndpoint)
            // Stored key must be cleared when reverted to default
            assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, null))
        }
    }

    @Test
    fun testUpdateBugReportEndpoint_settingExactDefaultStringClearsPrefKey() = runTest {
        repository.updateBugReportEndpoint("https://custom.example.com/bugs")
        assertEquals("https://custom.example.com/bugs", fakePrefs.getString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, null))

        // Setting back to the exact default constant should clear the preference key to prevent stale shadowing
        repository.updateBugReportEndpoint(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT)
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, repository.getSettings().bugReportEndpoint)
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, null))
    }

    // =========================================================================
    // 2. GitHub Token Whitespace Trimming & Clearing
    // =========================================================================

    @Test
    fun testUpdateGithubToken_trimsWhitespaceAndStoresCleanToken() = runTest {
        val tokenInputs = listOf(
            "  ghp_1234567890abcdefghijklmnopqrstuvwxyz  " to "ghp_1234567890abcdefghijklmnopqrstuvwxyz",
            "\t\nghp_token_with_tabs\r\n" to "ghp_token_with_tabs",
            "github_pat_11AAAAAAA_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" to
                "github_pat_11AAAAAAA_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )

        for ((input, expected) in tokenInputs) {
            repository.updateGithubToken(input)
            assertEquals(expected, repository.getSettings().githubToken)
            assertEquals(expected, repository.settings.value.githubToken)
            assertEquals(expected, fakePrefs.getString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, null))
        }
    }

    @Test
    fun testUpdateGithubToken_allWhitespaceVariantsTrimToNull() = runTest {
        val emptyOrWhitespaceTokens = listOf(
            null,
            "",
            " ",
            "    ",
            "\t",
            "\n",
            "\r\n",
            "  \t \n \r  "
        )

        for (token in emptyOrWhitespaceTokens) {
            // First set a valid token
            repository.updateGithubToken("ghp_activeToken")
            assertEquals("ghp_activeToken", repository.getSettings().githubToken)

            // Now update with whitespace/null
            repository.updateGithubToken(token)
            assertNull("Token should be null for input: '$token'", repository.getSettings().githubToken)
            assertNull("StateFlow token should be null for input: '$token'", repository.settings.value.githubToken)
            assertNull("Preferences key should be removed for input: '$token'", fakePrefs.getString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, null))
        }
    }

    // =========================================================================
    // 3. Cold Storage Reload & Corrupted State Resilience
    // =========================================================================

    @Test
    fun testColdStorageReload_corruptedEndpointAndWhitespaceToken() {
        // Corrupt the underlying storage directly
        fakePrefs.edit()
            .putString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, "http://unencrypted.insecure.com/report")
            .putString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, "   \t\n  ")
            .apply()

        // Cold load new repository instance from the corrupted preferences
        val coldRepo = SettingsRepositoryImpl(fakePrefs)
        val loadedSettings = coldRepo.getSettings()

        // Insecure endpoint must have fallen back to default
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, loadedSettings.bugReportEndpoint)
        // Whitespace token must have fallen back to null
        assertNull(loadedSettings.githubToken)
    }

    @Test
    fun testColdStorageReload_validCustomEndpointAndToken() {
        fakePrefs.edit()
            .putString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, "https://my-proxy.org/bugs")
            .putString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, "ghp_persisted_token_999")
            .apply()

        val coldRepo = SettingsRepositoryImpl(fakePrefs)
        val loadedSettings = coldRepo.getSettings()

        assertEquals("https://my-proxy.org/bugs", loadedSettings.bugReportEndpoint)
        assertEquals("ghp_persisted_token_999", loadedSettings.githubToken)
    }

    // =========================================================================
    // 4. Concurrent Access Stress & Concurrency Safety
    // =========================================================================

    @Test
    fun testConcurrentSettingsUpdates_threadSafeAndConsistent() = runTest {
        // Fire 50 concurrent updates across both endpoint and token
        val jobs = (1..50).map { i ->
            async(Dispatchers.Default) {
                if (i % 2 == 0) {
                    repository.updateBugReportEndpoint("https://endpoint-$i.example.com/bugs")
                } else {
                    repository.updateGithubToken("ghp_token_$i")
                }
            }
        }
        jobs.awaitAll()

        // Final state must be well-formed and non-corrupted
        val finalSettings = repository.getSettings()
        assertTrue(finalSettings.bugReportEndpoint.startsWith("https://"))
        assertTrue(finalSettings.githubToken == null || finalSettings.githubToken!!.startsWith("ghp_token_"))
        assertEquals(finalSettings, repository.settings.value)
    }
}
