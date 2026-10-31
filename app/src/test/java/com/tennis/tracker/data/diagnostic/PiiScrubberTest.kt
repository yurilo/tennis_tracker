package com.tennis.tracker.data.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiScrubberTest {

    @Test
    fun testScrubClassicGitHubToken() {
        val input = "Error using token ghp_1234567890abcdef1234567890abcdef for sync"
        val result = PiiScrubber.scrub(input)
        assertEquals("Error using token [REDACTED_GH_TOKEN] for sync", result)
    }

    @Test
    fun testScrubFineGrainedGitHubToken() {
        val input = "Token: github_pat_11ABCD1234efgh5678ijkl90mnop_1234567890abcdefghijklmnopqrstuvwxyz1234567890"
        val result = PiiScrubber.scrub(input)
        assertEquals("Token: [REDACTED_GH_TOKEN]", result)
    }

    @Test
    fun testScrubSpecializedGitHubTokens() {
        val oAuth = "OAuth token: gho_1234567890abcdef1234567890abcdef"
        val userServer = "User token: ghu_1234567890abcdef1234567890abcdef"
        val serverServer = "Server token: ghs_1234567890abcdef1234567890abcdef"
        val refresh = "Refresh token: ghr_1234567890abcdef1234567890abcdef"

        assertEquals("OAuth token: [REDACTED_GH_TOKEN]", PiiScrubber.scrub(oAuth))
        assertEquals("User token: [REDACTED_GH_TOKEN]", PiiScrubber.scrub(userServer))
        assertEquals("Server token: [REDACTED_GH_TOKEN]", PiiScrubber.scrub(serverServer))
        assertEquals("Refresh token: [REDACTED_GH_TOKEN]", PiiScrubber.scrub(refresh))
    }

    @Test
    fun testScrubBearerToken() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.signature"
        val result = PiiScrubber.scrub(input)
        assertEquals("Authorization: Bearer [REDACTED_TOKEN]", result)

        val lowercaseBearer = "authorization: bearer my_long_token_string_123456"
        assertEquals("authorization: Bearer [REDACTED_TOKEN]", PiiScrubber.scrub(lowercaseBearer))
    }

    @Test
    fun testScrubUrlEmbeddedCredentials() {
        val input = "Connecting to https://admin:mySecretPass123@api.tennis-tracker.app/v1/scores"
        val result = PiiScrubber.scrub(input)
        assertEquals("Connecting to https://admin:[REDACTED_PASSWORD]@api.tennis-tracker.app/v1/scores", result)
    }

    @Test
    fun testScrubSensitiveQueryParameters() {
        val input = "GET https://api.tennis.com/scores?api_key=secret_12345678&format=json"
        val result = PiiScrubber.scrub(input)
        assertEquals("GET https://api.tennis.com/scores?api_key=[REDACTED_SECRET]&format=json", result)

        val tokenInput = "https://example.com/sync?access_token=abcdef123456&client=android"
        assertEquals("https://example.com/sync?access_token=[REDACTED_SECRET]&client=android", PiiScrubber.scrub(tokenInput))
    }

    @Test
    fun testScrubKeyValueSecrets() {
        val input1 = "Config dump -> api_key: \"secret_key_12345678\""
        assertEquals("Config dump -> api_key: [REDACTED_SECRET]", PiiScrubber.scrub(input1))

        val input2 = "secret: top_secret_token_123"
        assertEquals("secret: [REDACTED_SECRET]", PiiScrubber.scrub(input2))
    }

    @Test
    fun testScrubEmailAddress() {
        val input = "Contact user at player.support+tennis@example.com for help"
        val result = PiiScrubber.scrub(input)
        assertEquals("Contact user at [REDACTED_EMAIL] for help", result)
    }

    @Test
    fun testScrubIPv4Address() {
        val input = "Connection failed to 192.168.1.100 port 8080 (also tried 10.0.0.1)"
        val result = PiiScrubber.scrub(input)
        assertEquals("Connection failed to [REDACTED_IP] port 8080 (also tried [REDACTED_IP])", result)
    }

    @Test
    fun testPreserveAppVersion() {
        val input = "Tennis Tracker version 1.0.25 (versionCode 26) on Android 14"
        val result = PiiScrubber.scrub(input)
        assertEquals(input, result)
        assertTrue(result.contains("1.0.25"))
        assertFalse(result.contains("[REDACTED_IP]"))
    }

    @Test
    fun testPreserveIsoTimestamps() {
        val input = "Captured event at 2026-10-31T13:08:54Z in match round"
        val result = PiiScrubber.scrub(input)
        assertEquals(input, result)
        assertTrue(result.contains("2026-10-31T13:08:54Z"))
        assertFalse(result.contains("[REDACTED_IP]"))
    }

    @Test
    fun testPreserveTennisScoresAndStats() {
        val input = "Novak Djokovic won 6-4, 3-6, 7-6(5) against Carlos Alcaraz on Court 1"
        val result = PiiScrubber.scrub(input)
        assertEquals(input, result)
    }

    @Test
    fun testScrubIPv6Address() {
        val full = "Connecting to 2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        assertEquals("Connecting to [REDACTED_IP]", PiiScrubber.scrub(full))

        val compressed = "Host fe80::1 unreachable"
        assertEquals("Host [REDACTED_IP] unreachable", PiiScrubber.scrub(compressed))

        val loopback = "Ping ::1 failed"
        assertEquals("Ping [REDACTED_IP] failed", PiiScrubber.scrub(loopback))
    }

    @Test
    fun testScrubLocalFilesystemPaths() {
        val emulated = "File written to /storage/emulated/10/agy/us_open_tracker/log.txt"
        assertEquals("File written to [REDACTED_PATH]", PiiScrubber.scrub(emulated))

        val appData = "Database opened at /data/user/0/com.tennis.tracker/databases/tennis.db"
        assertEquals("Database opened at [REDACTED_PATH]", PiiScrubber.scrub(appData))

        val userHome = "Config loaded from /home/runner/work/tennis_tracker/config.json"
        assertEquals("Config loaded from [REDACTED_PATH]", PiiScrubber.scrub(userHome))

        val macUsers = "Saved to /Users/developer/Desktop/output.csv"
        assertEquals("Saved to [REDACTED_PATH]", PiiScrubber.scrub(macUsers))
    }

    @Test
    fun testCompoundPiiString() {
        val compound = "User test@domain.org logged in from 172.16.254.1 with ghp_1234567890abcdef1234567890abcdef on /storage/emulated/10/test.log"
        val result = PiiScrubber.scrub(compound)
        assertEquals("User [REDACTED_EMAIL] logged in from [REDACTED_IP] with [REDACTED_GH_TOKEN] on [REDACTED_PATH]", result)
    }

    @Test
    fun testScrubMapAndList() {
        val map = mapOf(
            "email" to "admin@tennis.org",
            "token" to "ghp_1234567890abcdef1234567890abcdef",
            "version" to "1.0.25"
        )
        val scrubbedMap = PiiScrubber.scrub(map)
        assertEquals("[REDACTED_EMAIL]", scrubbedMap["email"])
        assertEquals("[REDACTED_GH_TOKEN]", scrubbedMap["token"])
        assertEquals("1.0.25", scrubbedMap["version"])

        val list = listOf("10.0.0.1", "Normal text", "/home/user/file.txt")
        val scrubbedList = PiiScrubber.scrub(list)
        assertEquals(listOf("[REDACTED_IP]", "Normal text", "[REDACTED_PATH]"), scrubbedList)
    }

    @Test
    fun testIdempotency() {
        val raw = "Error: Bearer eyJ1234567890abcdef and ghp_1234567890abcdef1234567890abcdef at 192.168.0.1"
        val pass1 = PiiScrubber.scrub(raw)
        val pass2 = PiiScrubber.scrub(pass1)
        assertEquals(pass1, pass2)
    }

    @Test
    fun testNullAndEmptyHandling() {
        assertEquals("", PiiScrubber.scrub(null))
        assertEquals("", PiiScrubber.scrub(""))
    }

    @Test
    fun testScrubJsonFormattedCredentials_singleAndMultipleKeys() {
        val jsonPayload = """{"api_key": "secret_api_key_12345", "password": "super_secret_password_123", "token": "session_token_abc12345"}"""
        val scrubbed = PiiScrubber.scrub(jsonPayload)

        assertFalse("API key leaked in JSON: $scrubbed", scrubbed.contains("secret_api_key_12345"))
        assertFalse("Password leaked in JSON: $scrubbed", scrubbed.contains("super_secret_password_123"))
        assertFalse("Token leaked in JSON: $scrubbed", scrubbed.contains("session_token_abc12345"))
        assertEquals("""{"api_key": [REDACTED_SECRET], "password": [REDACTED_SECRET], "token": [REDACTED_SECRET]}""", scrubbed)
    }

    @Test
    fun testScrubJsonFormattedCredentials_variationsAndQuotes() {
        // Single quoted JSON-like string
        val singleQuoted = "{'secret': 'superSecretToken999', 'user': 'player1'}"
        val scrubbedSingle = PiiScrubber.scrub(singleQuoted)
        assertFalse(scrubbedSingle.contains("superSecretToken999"))
        assertEquals("{'secret': [REDACTED_SECRET], 'user': 'player1'}", scrubbedSingle)

        // Nested JSON in log output
        val logLine = "Response: {\"status\": 200, \"auth\": \"bearer_auth_secret_token_1234\", \"private_key\": \"abc123456789xyz\"}"
        val scrubbedLog = PiiScrubber.scrub(logLine)
        assertFalse(scrubbedLog.contains("bearer_auth_secret_token_1234"))
        assertFalse(scrubbedLog.contains("abc123456789xyz"))
        assertTrue(scrubbedLog.contains("\"auth\": [REDACTED_SECRET]"))
        assertTrue(scrubbedLog.contains("\"private_key\": [REDACTED_SECRET]"))
    }

    @Test
    fun testScrubIPv6Addresses_multiGroupCompressed() {
        // Standard multi-group compressed IPv6 address
        val addr1 = "fe80::1ff:fe23:4567:890a"
        assertEquals("[REDACTED_IP]", PiiScrubber.scrub(addr1))

        // Multiple groups before :: and multiple groups after ::
        val addr2 = "2001:db8::1:2:3:4"
        assertEquals("[REDACTED_IP]", PiiScrubber.scrub(addr2))

        val addr3 = "2001:db8:85a3::8a2e:370:7334"
        assertEquals("[REDACTED_IP]", PiiScrubber.scrub(addr3))

        // In network error log context
        val logLine = "Network failure: unreachable gateway fe80::1ff:fe23:4567:890a on interface wlan0"
        val scrubbedLog = PiiScrubber.scrub(logLine)
        assertEquals("Network failure: unreachable gateway [REDACTED_IP] on interface wlan0", scrubbedLog)
        assertFalse("Trailing IPv6 segments must not leak", scrubbedLog.contains(":fe23:4567:890a"))
    }
}
