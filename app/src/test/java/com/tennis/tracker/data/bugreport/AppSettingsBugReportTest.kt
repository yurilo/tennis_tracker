package com.tennis.tracker.data.bugreport

import com.tennis.tracker.data.local.FakeSharedPreferences
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import com.tennis.tracker.domain.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsBugReportTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        SettingsRepositoryImpl.resetInstanceForTesting()
        fakePrefs = FakeSharedPreferences()
        repository = SettingsRepositoryImpl(fakePrefs)
    }

    @Test
    fun testDefaultBugReportSettings() {
        val settings = repository.getSettings()
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, settings.bugReportEndpoint)
        assertEquals("https://telemetry.tennis-tracker.app/bug-report", settings.bugReportEndpoint)
        assertNull(settings.githubToken)
        assertEquals(AppSettings.DEFAULT, settings)
    }

    @Test
    fun testUpdateBugReportEndpoint_persistsAndEmitsHttpsUrl() = runTest {
        val customUrl = "https://custom-bug-relay.example.com/report"
        repository.updateBugReportEndpoint(customUrl)

        assertEquals(customUrl, repository.getSettings().bugReportEndpoint)
        assertEquals(customUrl, repository.settings.value.bugReportEndpoint)
        assertEquals(customUrl, fakePrefs.getString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, null))
    }

    @Test
    fun testUpdateBugReportEndpoint_blankRevertsToDefault() = runTest {
        repository.updateBugReportEndpoint("https://custom-bug-relay.example.com/report")
        assertEquals("https://custom-bug-relay.example.com/report", repository.getSettings().bugReportEndpoint)

        repository.updateBugReportEndpoint("   ")
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, repository.getSettings().bugReportEndpoint)
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, null))
    }

    @Test
    fun testUpdateBugReportEndpoint_rejectsHttpOrInsecureUrl() = runTest {
        try {
            repository.updateBugReportEndpoint("http://insecure-relay.example.com/report")
            fail("Cleartext HTTP endpoint must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("https", ignoreCase = true) == true)
        }

        try {
            repository.updateBugReportEndpoint("not-a-valid-url")
            fail("URL without scheme must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("https", ignoreCase = true) == true)
        }
    }

    @Test
    fun testUpdateGithubToken_persistsAndEmitsToken() = runTest {
        val rawToken = "  ghp_abcdef1234567890xyz  "
        repository.updateGithubToken(rawToken)

        val expected = "ghp_abcdef1234567890xyz"
        assertEquals(expected, repository.getSettings().githubToken)
        assertEquals(expected, repository.settings.value.githubToken)
        assertEquals(expected, fakePrefs.getString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, null))
    }

    @Test
    fun testUpdateGithubToken_blankOrNullClearsStorage() = runTest {
        repository.updateGithubToken("ghp_validToken123")
        assertEquals("ghp_validToken123", repository.getSettings().githubToken)

        repository.updateGithubToken("   ")
        assertNull(repository.getSettings().githubToken)
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, null))

        repository.updateGithubToken("ghp_validToken123")
        repository.updateGithubToken(null)
        assertNull(repository.getSettings().githubToken)
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, null))
    }

    @Test
    fun testResetToDefaults_clearsBugReportSettings() = runTest {
        repository.updateBugReportEndpoint("https://custom-bug-relay.example.com/report")
        repository.updateGithubToken("ghp_token123")

        repository.resetToDefaults()

        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, repository.getSettings().bugReportEndpoint)
        assertNull(repository.getSettings().githubToken)
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, null))
        assertNull(fakePrefs.getString(SettingsRepositoryImpl.KEY_GITHUB_TOKEN, null))
    }

    @Test
    fun testColdReloadPersistence() = runTest {
        repository.updateBugReportEndpoint("https://reloaded.example.com/bugs")
        repository.updateGithubToken("ghp_reloadedToken")

        // Simulate cold launch with same SharedPreferences
        val reloadedRepo = SettingsRepositoryImpl(fakePrefs)

        assertEquals("https://reloaded.example.com/bugs", reloadedRepo.getSettings().bugReportEndpoint)
        assertEquals("ghp_reloadedToken", reloadedRepo.getSettings().githubToken)
    }

    @Test
    fun testColdReloadWithCorruptEndpoint_fallsBackSafely() {
        fakePrefs.edit().putString(SettingsRepositoryImpl.KEY_BUG_REPORT_ENDPOINT, "http://bad-insecure.com").apply()

        val reloadedRepo = SettingsRepositoryImpl(fakePrefs)
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, reloadedRepo.getSettings().bugReportEndpoint)
    }
}
