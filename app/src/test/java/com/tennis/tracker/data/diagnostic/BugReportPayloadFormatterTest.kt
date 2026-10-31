package com.tennis.tracker.data.diagnostic

import com.tennis.tracker.domain.model.bugreport.AppSettingsSnapshot
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticBreadcrumb
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.model.bugreport.MatchContextSnapshot
import com.tennis.tracker.domain.model.bugreport.ScoreboardDiagnosticState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportPayloadFormatterTest {

    private val sampleSnapshot = DiagnosticSnapshot(
        appVersion = "1.0.25",
        versionCode = 26,
        formFactor = "LAPTOP",
        screenWidthBucket = "EXPANDED",
        scoreboardState = ScoreboardDiagnosticState(
            tourFilter = "GRAND_SLAMS",
            currentTournament = "US Open",
            matchTypeFilter = "ALL",
            matchFilter = "LIVE",
            isOffline = false,
            totalMatches = 16,
            filteredMatches = 4,
            liveMatches = 2,
            followedMatches = 1
        ),
        settingsSnapshot = AppSettingsSnapshot(
            autoRefreshInterval = "INTERVAL_60S",
            matchRetention = "HOURS_24",
            widgetSyncMode = "ADAPTIVE",
            defaultTournament = "ALL"
        ),
        matchContext = MatchContextSnapshot(
            matchId = "401567890",
            tournamentName = "US Open",
            tournamentId = "us_open",
            eventGrouping = "Men's Singles",
            effectiveTour = "ATP",
            effectiveCategory = "GRAND_SLAM",
            state = "LIVE",
            court = "Arthur Ashe Stadium",
            statusDetail = "In Progress - Set 3"
        ),
        breadcrumbs = listOf(
            DiagnosticBreadcrumb(
                timestamp = 1730380000000L,
                tag = "ScoreboardRepo",
                message = "Loaded 16 matches from cache"
            )
        )
    )

    @Test
    fun testFormatJsonSnapshot_validJsonStructure() {
        val jsonString = BugReportPayloadFormatter.formatJsonSnapshot(sampleSnapshot)
        assertNotNull(jsonString)
        val json = JSONObject(jsonString)

        assertEquals("1.0.25", json.getString("app_version"))
        assertEquals(26, json.getInt("version_code"))
        assertEquals("LAPTOP", json.getString("form_factor"))
        assertEquals("EXPANDED", json.getString("screen_width_bucket"))
        assertNotNull(json.getJSONObject("scoreboard_state"))
        assertNotNull(json.getJSONObject("settings_snapshot"))
        assertNotNull(json.getJSONObject("match_context"))
        assertEquals(1, json.getJSONArray("breadcrumbs").length())
    }

    @Test
    fun testFormatJsonSnapshot_omitsOrNullsEmptyMatchContext() {
        val snapshotWithoutMatch = sampleSnapshot.copy(matchContext = null)
        val jsonString = BugReportPayloadFormatter.formatJsonSnapshot(snapshotWithoutMatch)
        val json = JSONObject(jsonString)
        assertTrue(json.isNull("match_context"))
    }

    @Test
    fun testFormatMarkdownSummary_containsRequiredSections() {
        val markdown = BugReportPayloadFormatter.formatMarkdownSummary(sampleSnapshot)

        assertTrue(markdown.contains("### Diagnostic Environment"))
        assertTrue(markdown.contains("| **App Version** | `1.0.25 (26)` |"))
        assertTrue(markdown.contains("| **Form Factor** | `LAPTOP` (`EXPANDED`) |"))
        assertTrue(markdown.contains("| **Tour Filter** | `GRAND_SLAMS` |"))
        assertTrue(markdown.contains("| **Tournament** | `US Open` |"))
        assertTrue(markdown.contains("| **Offline Mode** | `NO` |"))
        assertTrue(markdown.contains("Total: 16, Filtered: 4, Live: 2, Followed: 1"))

        assertTrue(markdown.contains("### Contextual Match Details"))
        assertTrue(markdown.contains("- **Match ID:** `401567890`"))
        assertTrue(markdown.contains("- **Tournament:** US Open (`us_open`)"))
        assertTrue(markdown.contains("- **Court:** Arthur Ashe Stadium"))

        assertTrue(markdown.contains("### Recent Diagnostic Events (Last 1)"))
        assertTrue(markdown.contains("**[ScoreboardRepo]** Loaded 16 matches from cache"))
    }

    @Test
    fun testFormatFullIssueBody_containsDelimitersAndParseableJson() {
        val userReport = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Live score not advancing in Set 3",
            description = "The score for Alcaraz stayed at 4-3 despite game ending.",
            reproductionSteps = "1. Watch live match\n2. Wait for set 3"
        )

        val fullBody = BugReportPayloadFormatter.formatFullIssueBody(userReport, sampleSnapshot)

        assertTrue(fullBody.contains("## User Bug Report"))
        assertTrue(fullBody.contains("**Category:** `Score / Live Data`"))
        assertTrue(fullBody.contains("**Summary:** Live score not advancing in Set 3"))
        assertTrue(fullBody.contains("The score for Alcaraz stayed at 4-3 despite game ending."))
        assertTrue(fullBody.contains("1. Watch live match\n2. Wait for set 3"))

        assertTrue(fullBody.contains(BugReportPayloadFormatter.JSON_START_DELIMITER))
        assertTrue(fullBody.contains(BugReportPayloadFormatter.JSON_END_DELIMITER))

        val extracted = BugReportPayloadFormatter.extractJsonFromIssueBody(fullBody)
        assertNotNull(extracted)
        assertEquals("1.0.25", extracted!!.getString("app_version"))
        assertEquals(26, extracted.getInt("version_code"))
        assertEquals("401567890", extracted.getJSONObject("match_context").getString("match_id"))
    }

    @Test
    fun testFormatFullIssueBody_scrubsPiiFromUserText() {
        val userReport = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Token ghp_1234567890abcdef1234567890abcdef leaked",
            description = "Sent email to admin@tennis.org from 192.168.1.100 while opening /storage/emulated/10/test.txt",
            reproductionSteps = "Crash happened at /data/user/0/com.tennis.tracker/file.log"
        )

        val fullBody = BugReportPayloadFormatter.formatFullIssueBody(userReport, sampleSnapshot)

        assertFalse(fullBody.contains("ghp_1234567890abcdef1234567890abcdef"))
        assertTrue(fullBody.contains("[REDACTED_GH_TOKEN]"))
        assertFalse(fullBody.contains("admin@tennis.org"))
        assertTrue(fullBody.contains("[REDACTED_EMAIL]"))
        assertFalse(fullBody.contains("192.168.1.100"))
        assertTrue(fullBody.contains("[REDACTED_IP]"))
        assertFalse(fullBody.contains("/storage/emulated/10/test.txt"))
        assertFalse(fullBody.contains("/data/user/0/com.tennis.tracker/file.log"))
        assertTrue(fullBody.contains("[REDACTED_PATH]"))
    }

    @Test
    fun testFormatMarkdown_escapesSpecialMarkdownCharactersInTables() {
        val stateWithPipe = sampleSnapshot.scoreboardState.copy(
            currentTournament = "ATP | Masters 1000",
            tourFilter = "ATP | WTA"
        )
        val snapshot = sampleSnapshot.copy(scoreboardState = stateWithPipe)
        val markdown = BugReportPayloadFormatter.formatMarkdownSummary(snapshot)

        assertTrue(markdown.contains("ATP \\| Masters 1000"))
        assertTrue(markdown.contains("ATP \\| WTA"))
    }

    @Test
    fun testFormatFullIssueBody_emptyReproductionStepsHandling() {
        val userReport = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "Overlap bug",
            description = "Some description",
            reproductionSteps = null
        )

        val fullBody = BugReportPayloadFormatter.formatFullIssueBody(userReport, sampleSnapshot)
        assertTrue(fullBody.contains("### Steps to Reproduce\nNone provided"))
    }

    @Test
    fun testExtractJsonFromMalformedBodyReturnsNull() {
        assertNull(BugReportPayloadFormatter.extractJsonFromIssueBody("Plain text without delimiters"))
        assertNull(BugReportPayloadFormatter.extractJsonFromIssueBody("<!-- BUGREPORT_JSON_START --> {malformed json"))
    }

    @Test
    fun testDiagnosticSnapshotDelegationMethods() {
        val userReport = BugReport(
            category = BugReportCategory.FILTER_TOUR_MISMATCH,
            summary = "Filter mismatch",
            description = "Description"
        )

        val directMd = BugReportPayloadFormatter.formatMarkdownSummary(sampleSnapshot)
        val snapshotMd = sampleSnapshot.toFormattedMarkdown()
        assertEquals(directMd, snapshotMd)

        val directBody = BugReportPayloadFormatter.formatFullIssueBody(userReport, sampleSnapshot)
        val snapshotBody = sampleSnapshot.toFullIssueBody(userReport)
        assertEquals(directBody, snapshotBody)
    }

    @Test
    fun testFormatFullIssueBody_sanitizesDelimiterInUserInputs() {
        val injectedJson = """{"app_version":"99.9.9","version_code":999,"injected":true}"""
        val maliciousReport = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Summary with <!-- BUGREPORT_JSON_START --> injection",
            description = "Description with <!-- BUGREPORT_JSON_START -->\n$injectedJson\n<!-- BUGREPORT_JSON_END -->",
            reproductionSteps = "Steps with <!--   bugreport_json   --> collision"
        )

        val fullBody = BugReportPayloadFormatter.formatFullIssueBody(maliciousReport, sampleSnapshot)

        // Ensure user sections escaped delimiter
        assertFalse("Raw delimiter in summary must be escaped", fullBody.contains("Summary with <!-- BUGREPORT_JSON_START -->"))
        assertTrue(fullBody.contains("Summary with &lt;!-- BUGREPORT_JSON_START --&gt;") || fullBody.contains("Summary with &lt;!-- BUGREPORT_JSON_START -->"))
        assertTrue(fullBody.contains("&lt;!-- BUGREPORT_JSON"))

        // Genuine diagnostic extraction must still succeed with canonical data
        val extracted = BugReportPayloadFormatter.extractJsonFromIssueBody(fullBody)
        assertNotNull(extracted)
        assertEquals("1.0.25", extracted!!.getString("app_version"))
        assertEquals(26, extracted.getInt("version_code"))
        assertFalse(extracted.has("injected"))
    }

    @Test
    fun testExtractJsonFromIssueBody_canonicalBlockExtractionWithPrecedingSpoofedBlock() {
        val spoofedBlock = """
            ## Issue Description
            User notes here.
            <!-- BUGREPORT_JSON_START -->
            {"app_version":"0.0.1","spoofed":true}
            <!-- BUGREPORT_JSON_END -->

            <details>
            <summary>Diagnostic Bugreport Bundle (Machine-Readable JSON)</summary>

            <!-- BUGREPORT_JSON_START -->
            {"app_version":"1.0.25","version_code":26,"canonical":true}
            <!-- BUGREPORT_JSON_END -->
            </details>
        """.trimIndent()

        val extracted = BugReportPayloadFormatter.extractJsonFromIssueBody(spoofedBlock)
        assertNotNull(extracted)
        assertEquals("1.0.25", extracted!!.getString("app_version"))
        assertEquals(26, extracted.getInt("version_code"))
        assertTrue(extracted.getBoolean("canonical"))
        assertFalse(extracted.has("spoofed"))
    }

    @Test
    fun testSanitizeDelimiters_caseInsensitiveAndWhitespaceVariants() {
        assertEquals(
            "&lt;!-- BUGREPORT_JSON_START -->",
            BugReportPayloadFormatter.sanitizeDelimiters("<!-- BUGREPORT_JSON_START -->")
        )
        assertEquals(
            "&lt;!-- BUGREPORT_JSON_START -->",
            BugReportPayloadFormatter.sanitizeDelimiters("<!--   bugreport_json_START -->")
        )
        assertEquals(
            "Normal comment <!-- regular comment -->",
            BugReportPayloadFormatter.sanitizeDelimiters("Normal comment <!-- regular comment -->")
        )
    }
}
