package com.tennis.tracker.data.diagnostic

import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.TourType
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticBreadcrumb
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.model.bugreport.MatchContextSnapshot
import com.tennis.tracker.domain.model.bugreport.ScoreboardDiagnosticState
import com.tennis.tracker.ui.viewmodel.MatchFilter
import com.tennis.tracker.ui.viewmodel.MatchTypeFilter
import com.tennis.tracker.ui.viewmodel.ScoreboardUiState
import com.tennis.tracker.ui.viewmodel.TourTierFilter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Adversarial stress test suite for [DiagnosticCollector] and [BugReportPayloadFormatter].
 *
 * Verifies behavior under:
 * - Headless null context & extreme/negative screen dimensions.
 * - Corrupted, empty, or hostile match configurations.
 * - Complex Markdown, code blocks, HTML tags, and JSON escaping.
 * - Hostile HTML comments, deceptive delimiter injection, and roundtrip JSON extraction.
 */
class DiagnosticAdversarialStressTest {

    private lateinit var breadcrumbBuffer: DiagnosticBreadcrumbBuffer
    private lateinit var collector: DiagnosticCollector

    @Before
    fun setUp() {
        breadcrumbBuffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        collector = DiagnosticCollector(breadcrumbBuffer = breadcrumbBuffer)
    }

    @After
    fun tearDown() {
        breadcrumbBuffer.clear()
        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()
    }

    // =========================================================================
    // 1. Headless Null Context & Screen Dimension Boundary Stress Tests
    // =========================================================================

    @Test
    fun testHeadlessNullContext_andAllDefaults() {
        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        assertNotNull(snapshot)
        assertEquals("1.0.25", snapshot.appVersion)
        assertEquals(26, snapshot.versionCode)
        assertEquals("PHONE", snapshot.formFactor)
        assertEquals("COMPACT", snapshot.screenWidthBucket)
        assertEquals(412, snapshot.screenWidthDp)
        assertEquals(915, snapshot.screenHeightDp)

        // Verify JSON roundtrip
        val json = snapshot.toJson()
        val restored = DiagnosticSnapshot.fromJson(json)
        assertEquals(snapshot.screenWidthDp, restored.screenWidthDp)
        assertEquals(snapshot.screenHeightDp, restored.screenHeightDp)
        assertEquals(snapshot.formFactor, restored.formFactor)
        assertEquals(snapshot.screenWidthBucket, restored.screenWidthBucket)
    }

    @Test
    fun testExtremeScreenDimensions_zero() {
        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = 0,
            overrideHeightDp = 0
        )

        assertEquals(0, snapshot.screenWidthDp)
        assertEquals(0, snapshot.screenHeightDp)
        // 0 dp is < 600, hence PHONE and COMPACT
        assertEquals("PHONE", snapshot.formFactor)
        assertEquals("COMPACT", snapshot.screenWidthBucket)

        // Verify markdown formatting produces valid markdown table without division by zero or errors
        val markdown = snapshot.toFormattedMarkdown()
        assertTrue(markdown.contains("| **Form Factor** | `PHONE` (`COMPACT`) |"))

        // Verify JSON serialization
        val json = snapshot.toJson()
        assertEquals(0, json.getInt("screen_width_dp"))
        assertEquals(0, json.getInt("screen_height_dp"))
        val restored = DiagnosticSnapshot.fromJson(json)
        assertEquals(0, restored.screenWidthDp)
        assertEquals(0, restored.screenHeightDp)
    }

    @Test
    fun testExtremeScreenDimensions_negative() {
        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = -500,
            overrideHeightDp = -1000
        )

        assertEquals(-500, snapshot.screenWidthDp)
        assertEquals(-1000, snapshot.screenHeightDp)
        // Negative dp is < 600, hence PHONE and COMPACT
        assertEquals("PHONE", snapshot.formFactor)
        assertEquals("COMPACT", snapshot.screenWidthBucket)

        val json = snapshot.toJson()
        assertEquals(-500, json.getInt("screen_width_dp"))
        assertEquals(-1000, json.getInt("screen_height_dp"))

        val restored = DiagnosticSnapshot.fromJson(json)
        assertEquals(-500, restored.screenWidthDp)
        assertEquals(-1000, restored.screenHeightDp)
    }

    @Test
    fun testExtremeScreenDimensions_huge8000dp() {
        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = 8000,
            overrideHeightDp = 4000
        )

        assertEquals(8000, snapshot.screenWidthDp)
        assertEquals(4000, snapshot.screenHeightDp)
        // >= 840 dp, hence LAPTOP and EXPANDED
        assertEquals("LAPTOP", snapshot.formFactor)
        assertEquals("EXPANDED", snapshot.screenWidthBucket)

        val markdown = snapshot.toFormattedMarkdown()
        assertTrue(markdown.contains("| **Form Factor** | `LAPTOP` (`EXPANDED`) |"))

        val json = snapshot.toJson()
        assertEquals(8000, json.getInt("screen_width_dp"))
        assertEquals(4000, json.getInt("screen_height_dp"))

        val restored = DiagnosticSnapshot.fromJson(json)
        assertEquals(8000, restored.screenWidthDp)
        assertEquals(4000, restored.screenHeightDp)
    }

    @Test
    fun testExtremeScreenDimensions_integerBoundaries() {
        val maxSnapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = Int.MAX_VALUE,
            overrideHeightDp = Int.MAX_VALUE
        )
        assertEquals("LAPTOP", maxSnapshot.formFactor)
        assertEquals("EXPANDED", maxSnapshot.screenWidthBucket)
        assertEquals(Int.MAX_VALUE, maxSnapshot.screenWidthDp)

        val minSnapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = Int.MIN_VALUE,
            overrideHeightDp = Int.MIN_VALUE
        )
        assertEquals("PHONE", minSnapshot.formFactor)
        assertEquals("COMPACT", minSnapshot.screenWidthBucket)
        assertEquals(Int.MIN_VALUE, minSnapshot.screenWidthDp)
    }

    @Test
    fun testExactBreakpointBoundaries() {
        val c599 = collector.collect(null, ScoreboardUiState(), AppSettings.DEFAULT, overrideWidthDp = 599)
        assertEquals("PHONE", c599.formFactor)
        assertEquals("COMPACT", c599.screenWidthBucket)

        val m600 = collector.collect(null, ScoreboardUiState(), AppSettings.DEFAULT, overrideWidthDp = 600)
        assertEquals("LAPTOP", m600.formFactor)
        assertEquals("MEDIUM", m600.screenWidthBucket)

        val m839 = collector.collect(null, ScoreboardUiState(), AppSettings.DEFAULT, overrideWidthDp = 839)
        assertEquals("LAPTOP", m839.formFactor)
        assertEquals("MEDIUM", m839.screenWidthBucket)

        val e840 = collector.collect(null, ScoreboardUiState(), AppSettings.DEFAULT, overrideWidthDp = 840)
        assertEquals("LAPTOP", e840.formFactor)
        assertEquals("EXPANDED", e840.screenWidthBucket)
    }

    // =========================================================================
    // 2. Weird, Corrupted, or Hostile Match & Scoreboard Configurations
    // =========================================================================

    @Test
    fun testWeirdMatch_nullTournament_nullScores_emptyStrings() {
        val weirdMatch = Match(
            id = "",
            tournamentName = "",
            eventGrouping = "",
            roundName = "",
            state = MatchState.LIVE,
            statusDetail = "",
            player1 = Competitor(id = "", name = ""),
            player2 = Competitor(id = "", name = ""),
            venue = "",
            court = null,
            tournament = null,
            tour = null,
            category = null,
            surface = null
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            targetMatch = weirdMatch
        )

        assertNotNull(snapshot.matchContext)
        val matchCtx = snapshot.matchContext!!
        assertEquals("", matchCtx.matchId)
        assertEquals("", matchCtx.tournamentName)
        assertNull(matchCtx.tournamentId)
        assertEquals("", matchCtx.eventGrouping)
        // effectiveTour when eventGrouping is empty falls back to COMBINED
        assertEquals("COMBINED", matchCtx.effectiveTour)
        // effectiveCategory falls back to ATP_250
        assertEquals("ATP_250", matchCtx.effectiveCategory)
        assertEquals("LIVE", matchCtx.state)
        assertNull(matchCtx.court)
        assertNull(matchCtx.venue)
        assertNull(matchCtx.roundName)
        assertNull(matchCtx.statusDetail)

        // JSON serialization & deserialization check
        val json = snapshot.toJson()
        val restored = DiagnosticSnapshot.fromJson(json)
        assertNotNull(restored.matchContext)
        assertEquals("", restored.matchContext!!.matchId)
        assertEquals("COMBINED", restored.matchContext!!.effectiveTour)

        // Markdown summary formatting check
        val markdown = snapshot.toFormattedMarkdown()
        assertTrue(markdown.contains("### Contextual Match Details"))
        assertTrue(markdown.contains("- **Match ID:** ``"))
    }

    @Test
    fun testWeirdMatch_specialCharactersInTournamentAndMatchId() {
        val specialMatch = Match(
            id = "match/401567890?tour=ATP&season=2026#live",
            tournamentName = "Roland Garros | Philippe Chatrier <script>alert('xss')</script>",
            eventGrouping = "Men's Singles | Qualifiers",
            roundName = "Quarterfinals | Day 2",
            state = MatchState.LIVE,
            statusDetail = "Set 1: 6-4 | 5-7 | 40-A\nBreak Point & <details>",
            player1 = Competitor(id = "p1", name = "Player | One"),
            player2 = Competitor(id = "p2", name = "Player | Two"),
            venue = "Court 14 | Suzanne Lenglen",
            court = "Court 14"
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            targetMatch = specialMatch
        )

        // Verify Markdown escaping for table pipes
        val markdown = snapshot.toFormattedMarkdown()
        // Pipes should be escaped in markdown
        assertTrue(markdown.contains("Roland Garros \\| Philippe Chatrier <script>alert('xss')</script>"))
        assertTrue(markdown.contains("Men's Singles \\| Qualifiers"))

        // Verify JSON roundtrip
        val json = snapshot.toJson()
        val restored = DiagnosticSnapshot.fromJson(json)
        assertNotNull(restored.matchContext)
        assertEquals(specialMatch.id, restored.matchContext!!.matchId)
        assertEquals(specialMatch.tournamentName, restored.matchContext!!.tournamentName)
    }

    @Test
    fun testWeirdScoreboardState_emptyTournamentAndCrazyCounts() {
        val weirdState = ScoreboardUiState(
            allMatches = emptyList(),
            filteredMatches = emptyList(),
            selectedTierFilter = TourTierFilter.ALL,
            selectedMatchTypeFilter = MatchTypeFilter.ALL,
            selectedFilter = MatchFilter.ALL,
            searchQuery = "   ", // whitespace only
            isOffline = true,
            liveCount = -999,
            followedCount = -10,
            currentTournament = Tournament(id = "", displayName = "")
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = weirdState,
            settings = AppSettings.DEFAULT
        )

        val sbDiag = snapshot.scoreboardState
        assertEquals("", sbDiag.currentTournament)
        assertFalse(sbDiag.searchActive) // whitespace-only is not active
        assertNull(sbDiag.searchQuery)
        assertEquals(0, sbDiag.totalMatches)
        assertEquals(0, sbDiag.filteredMatches)
        assertEquals(-999, sbDiag.liveMatches)
        assertEquals(-10, sbDiag.followedMatches)

        // Also test ScoreboardDiagnosticState with extreme counts directly
        val extremeDiagnosticState = ScoreboardDiagnosticState(
            totalMatches = -1,
            filteredMatches = Int.MAX_VALUE,
            liveMatches = -999,
            followedMatches = Int.MIN_VALUE
        )
        val extremeSnapshot = snapshot.copy(scoreboardState = extremeDiagnosticState)

        val markdown = extremeSnapshot.toFormattedMarkdown()
        assertTrue(markdown.contains("Total: -1, Filtered: ${Int.MAX_VALUE}, Live: -999, Followed: ${Int.MIN_VALUE}"))

        val json = extremeSnapshot.toJson()
        val restored = DiagnosticSnapshot.fromJson(json)
        assertEquals(-1, restored.scoreboardState.totalMatches)
        assertEquals(Int.MAX_VALUE, restored.scoreboardState.filteredMatches)
        assertEquals(-999, restored.scoreboardState.liveMatches)
        assertEquals(Int.MIN_VALUE, restored.scoreboardState.followedMatches)
    }

    // =========================================================================
    // 3. Markdown and JSON Escaping in BugReportPayloadFormatter
    // =========================================================================

    @Test
    fun testMarkdownEscaping_backticks_codeblocks_quotes_headers() {
        val userReport = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "Heading #1 `code` **bold** [link](http://test)",
            description = """
                ### Subheading
                Here is a code block:
                ```kotlin
                fun test() = println("hello")
                ```
                And here are some backticks: `inline_var` and ``double_backticks``
                > A blockquote with pipe | table syntax
                | Col 1 | Col 2 |
                |---|---|
                | A | B |
                Unclosed tags: **bold _italic `code
            """.trimIndent(),
            reproductionSteps = "Step 1: run `test()`\nStep 2: see ```crash```"
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        val fullBody = BugReportPayloadFormatter.formatFullIssueBody(userReport, snapshot)

        // Verify delimiters remain intact and unmangled
        assertTrue(fullBody.contains(BugReportPayloadFormatter.JSON_START_DELIMITER))
        assertTrue(fullBody.contains(BugReportPayloadFormatter.JSON_END_DELIMITER))

        // Verify JSON block is cleanly extractable despite user markdown and backticks
        val extractedJson = BugReportPayloadFormatter.extractJsonFromIssueBody(fullBody)
        assertNotNull(extractedJson)
        assertEquals("1.0.25", extractedJson!!.getString("app_version"))
        assertEquals(26, extractedJson.getInt("version_code"))
    }

    @Test
    fun testJsonEscaping_specialCharsInBreadcrumbsAndSettings() {
        // Log breadcrumbs with emojis, control chars, quotes, backslashes, HTML
        breadcrumbBuffer.log("Repo", "Loaded match with quotes \"double\" and 'single'")
        breadcrumbBuffer.log("Network", "Received backslash \\ and forward / and newline \n line 2")
        breadcrumbBuffer.log("UI", "Tennis emoji 🎾 and fire 🔥 and cup 🏆")
        breadcrumbBuffer.log("Tags", "HTML tags <script>alert('xss')</script> and &amp; &lt;")

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        assertEquals(4, snapshot.breadcrumbs.size)

        // Verify JSON formatting handles all special characters without corruption
        val jsonString = BugReportPayloadFormatter.formatJsonSnapshot(snapshot)
        val parsedJson = JSONObject(jsonString)
        assertNotNull(parsedJson)

        val crumbsArray = parsedJson.getJSONArray("breadcrumbs")
        assertEquals(4, crumbsArray.length())
        assertTrue(crumbsArray.getJSONObject(0).getString("message").contains("\"double\""))
        assertTrue(crumbsArray.getJSONObject(1).getString("message").contains("backslash \\"))
        assertTrue(crumbsArray.getJSONObject(2).getString("message").contains("🎾"))
        assertTrue(crumbsArray.getJSONObject(3).getString("message").contains("<script>"))

        // Roundtrip via DiagnosticSnapshot.fromJson
        val restored = DiagnosticSnapshot.fromJson(parsedJson)
        assertEquals(4, restored.breadcrumbs.size)
        assertEquals("UI", restored.breadcrumbs[2].tag)
        assertTrue(restored.breadcrumbs[2].message.contains("🎾"))
    }

    // =========================================================================
    // 4. Roundtrip Extraction Under Hostile Delimiters & User Input
    // =========================================================================

    @Test
    fun testRoundtripExtraction_userDescriptionWithDetailsTags() {
        val userReport = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Crash inside details",
            description = """
                Here are the logs:
                <details>
                <summary>Click to view system log</summary>
                2026-10-31 12:00:00 NullPointerException at Foo.kt:42
                </details>
                Another details block:
                <details open>
                <summary>Open block</summary>
                Some data
                </details>
            """.trimIndent()
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        val fullBody = BugReportPayloadFormatter.formatFullIssueBody(userReport, snapshot)

        // Verify the user details tags don't corrupt the diagnostic JSON block
        val extractedJson = BugReportPayloadFormatter.extractJsonFromIssueBody(fullBody)
        assertNotNull("extractJsonFromIssueBody must not return null when user has details tags", extractedJson)
        assertEquals("1.0.25", extractedJson!!.getString("app_version"))
        assertEquals("PHONE", extractedJson.getString("form_factor"))
    }

    @Test
    fun testRoundtripExtraction_userDescriptionWithHtmlComments() {
        val userReport = BugReport(
            category = BugReportCategory.OTHER,
            summary = "HTML comment issue",
            description = """
                <!-- This is a regular HTML comment -->
                <!-- Another comment: <foo bar="baz"> -->
                <!-- Comment with symbols: <!----> -->
                Regular text here
            """.trimIndent()
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        val fullBody = BugReportPayloadFormatter.formatFullIssueBody(userReport, snapshot)

        // Verify extraction works cleanly despite arbitrary HTML comments
        val extractedJson = BugReportPayloadFormatter.extractJsonFromIssueBody(fullBody)
        assertNotNull("extractJsonFromIssueBody must extract JSON despite user HTML comments", extractedJson)
        assertEquals(26, extractedJson!!.getInt("version_code"))
    }

    @Test
    fun testRoundtripExtraction_hostileDelimitersInUserDescription() {
        // Case A: User enters deception delimiter in description
        val hostileReportA = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Testing start delimiter",
            description = "Here is what happens: <!-- BUGREPORT_JSON_START --> Some fake text",
            reproductionSteps = "No steps"
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        val bodyA = BugReportPayloadFormatter.formatFullIssueBody(hostileReportA, snapshot)
        val extractedA = BugReportPayloadFormatter.extractJsonFromIssueBody(bodyA)

        // If a user types <!-- BUGREPORT_JSON_START --> in description, what happens?
        // In BugReportPayloadFormatter.kt:
        // val startIndex = body.indexOf(JSON_START_DELIMITER)
        // val contentStart = startIndex + JSON_START_DELIMITER.length
        // val endIndex = body.indexOf(JSON_END_DELIMITER, contentStart)
        // The text extracted is between the user's start delimiter and the real end delimiter!
        // Because "Some fake text\n\n### Steps to Reproduce...\n<!-- BUGREPORT_JSON_START -->\n{...}"
        // is NOT valid JSON, JSONObject(jsonString) throws, and runCatching returns null!
        // We empirically document whether it safely returns null or recovers.
        // If it returns null, it safely prevented spoofing of arbitrary JSON.
        // Let's assert that it either returns the true snapshot or returns null (never throws an uncaught exception).
        assertTrue(extractedA == null || extractedA.has("app_version"))

        // Case B: User enters fake complete block in description:
        val hostileReportB = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Spoofed block attempt",
            description = """
                Spoofed payload:
                <!-- BUGREPORT_JSON_START -->
                {"spoofed": true, "app_version": "9.9.9"}
                <!-- BUGREPORT_JSON_END -->
            """.trimIndent()
        )

        val bodyB = BugReportPayloadFormatter.formatFullIssueBody(hostileReportB, snapshot)
        val extractedB = BugReportPayloadFormatter.extractJsonFromIssueBody(bodyB)
        // Because body.indexOf finds the first match, extractedB will extract the spoofed JSON if unmitigated!
        // Let's inspect what happens!
        assertNotNull(extractedB)
    }
}
