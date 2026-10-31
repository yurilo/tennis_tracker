package com.tennis.tracker.e2e

import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReport
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportCategory
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticBreadcrumb
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.GitHubIssueUrlBuilder
import com.tennis.tracker.e2e.harness.BugReportContractHarness.JSON_MARKER_END
import com.tennis.tracker.e2e.harness.BugReportContractHarness.JSON_MARKER_START
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MatchContextSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.MockWorkerRelay
import com.tennis.tracker.e2e.harness.BugReportContractHarness.PiiScrubber
import com.tennis.tracker.e2e.harness.BugReportContractHarness.ScoreboardStateSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.SettingsSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.TriageEngineHarness
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature Coverage Test Suite for In-App Bug Reporting & Automated Triage.
 *
 * Verifies all 8 core feature areas (F1-F8) in isolation with >=5 test cases per feature.
 */
class BugReportTier1FeatureCoverageTest {

    // =========================================================================
    // Feature 1: Bug Reporting Payload & Models (5 tests)
    // =========================================================================

    @Test
    fun test_f1_1_bugReport_validCreation_storesAllFields() {
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Live score discrepancy in Set 2",
            description = "Player 1 won point but score did not update from 15-30 to 30-30",
            reproductionSteps = "1. Open live match\n2. Wait for point\n3. Observe score",
            timestamp = 1698765432000L
        )

        assertEquals(BugReportCategory.SCORE_LIVE_DATA, report.category)
        assertEquals("Live score discrepancy in Set 2", report.summary)
        assertEquals("Player 1 won point but score did not update from 15-30 to 30-30", report.description)
        assertEquals("1. Open live match\n2. Wait for point\n3. Observe score", report.reproductionSteps)
        assertEquals(1698765432000L, report.timestamp)
    }

    @Test
    fun test_f1_2_bugReport_summaryCannotBeEmpty() {
        try {
            BugReport(
                category = BugReportCategory.UI_LAYOUT,
                summary = "   ",
                description = "Valid description"
            )
            fail("Expected IllegalArgumentException for blank summary")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("summary cannot be empty", ignoreCase = true))
        }
    }

    @Test
    fun test_f1_3_bugReport_descriptionCannotBeEmpty() {
        try {
            BugReport(
                category = BugReportCategory.HOME_WIDGET,
                summary = "Widget stuck",
                description = ""
            )
            fail("Expected IllegalArgumentException for blank description")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("description cannot be empty", ignoreCase = true))
        }
    }

    @Test
    fun test_f1_4_bugReport_optionalReproductionSteps() {
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "General feedback",
            description = "App is running smoothly"
        )
        assertNull(report.reproductionSteps)
    }

    @Test
    fun test_f1_5_bugReport_timestampDefaultsToCurrentTime() {
        val before = System.currentTimeMillis()
        val report = BugReport(
            category = BugReportCategory.WATCH_HIGHLIGHTS,
            summary = "Stream not loading",
            description = "Clicking watch icon does nothing"
        )
        val after = System.currentTimeMillis()
        assertTrue("Timestamp should be within test window", report.timestamp in before..after)
    }

    // =========================================================================
    // Feature 2: Category Taxonomy & GitHub Slugs (5 tests)
    // =========================================================================

    @Test
    fun test_f2_1_allSixRequiredCategoriesPresent() {
        val categories = BugReportCategory.values().map { it.name }
        assertTrue(categories.contains("FILTER_TOUR_MISMATCH"))
        assertTrue(categories.contains("SCORE_LIVE_DATA"))
        assertTrue(categories.contains("WATCH_HIGHLIGHTS"))
        assertTrue(categories.contains("HOME_WIDGET"))
        assertTrue(categories.contains("UI_LAYOUT"))
        assertTrue(categories.contains("OTHER"))
        assertEquals(6, categories.size)
    }

    @Test
    fun test_f2_2_categoryDisplayNamesMatchRequirement() {
        assertEquals("Filter / Tour Mismatch", BugReportCategory.FILTER_TOUR_MISMATCH.displayName)
        assertEquals("Score / Live Data", BugReportCategory.SCORE_LIVE_DATA.displayName)
        assertEquals("Watch / Highlights", BugReportCategory.WATCH_HIGHLIGHTS.displayName)
        assertEquals("Home Widget", BugReportCategory.HOME_WIDGET.displayName)
        assertEquals("UI / Layout", BugReportCategory.UI_LAYOUT.displayName)
        assertEquals("Other", BugReportCategory.OTHER.displayName)
    }

    @Test
    fun test_f2_3_categorySlugsMatchRequirement() {
        assertEquals("filter-tour", BugReportCategory.FILTER_TOUR_MISMATCH.slug)
        assertEquals("score-live-data", BugReportCategory.SCORE_LIVE_DATA.slug)
        assertEquals("watch-highlights", BugReportCategory.WATCH_HIGHLIGHTS.slug)
        assertEquals("home-widget", BugReportCategory.HOME_WIDGET.slug)
        assertEquals("ui-layout", BugReportCategory.UI_LAYOUT.slug)
        assertEquals("other", BugReportCategory.OTHER.slug)
    }

    @Test
    fun test_f2_4_categoryFromSlugLookup_exactMatch() {
        assertEquals(BugReportCategory.FILTER_TOUR_MISMATCH, BugReportCategory.fromSlug("filter-tour"))
        assertEquals(BugReportCategory.HOME_WIDGET, BugReportCategory.fromSlug("home-widget"))
        assertEquals(BugReportCategory.UI_LAYOUT, BugReportCategory.fromSlug("ui-layout"))
    }

    @Test
    fun test_f2_5_categoryFromSlugLookup_caseInsensitiveAndInvalid() {
        assertEquals(BugReportCategory.SCORE_LIVE_DATA, BugReportCategory.fromSlug("SCORE-LIVE-DATA"))
        assertEquals(BugReportCategory.WATCH_HIGHLIGHTS, BugReportCategory.fromSlug("Watch-Highlights"))
        assertNull(BugReportCategory.fromSlug("non-existent-category"))
        assertNull(BugReportCategory.fromSlug(""))
    }

    // =========================================================================
    // Feature 3: Diagnostic Snapshot Collection & Device Telemetry (5 tests)
    // =========================================================================

    @Test
    fun test_f3_1_deviceInfoCapturedCorrectly() {
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 25,
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED"
        )
        val json = snapshot.toJson()
        assertEquals("1.0.25", json.getString("app_version"))
        assertEquals(25, json.getInt("version_code"))
        assertEquals("LAPTOP", json.getString("form_factor"))
        assertEquals("EXPANDED", json.getString("screen_width_bucket"))
    }

    @Test
    fun test_f3_2_scoreboardStateCapturedCorrectly() {
        val state = ScoreboardStateSnapshot(
            tourFilter = "ATP_TOUR",
            currentTournament = "Rolex Paris Masters",
            matchTypeFilter = "LIVE",
            matchFilter = "ALL",
            searchActive = true,
            isOffline = false,
            totalMatches = 32,
            filteredMatches = 4,
            liveMatches = 2,
            followedMatches = 1
        )
        val snapshot = DiagnosticSnapshot(scoreboardState = state)
        val json = snapshot.toJson().getJSONObject("scoreboard_state")

        assertEquals("ATP_TOUR", json.getString("tour_filter"))
        assertEquals("Rolex Paris Masters", json.getString("current_tournament"))
        assertEquals("LIVE", json.getString("match_type_filter"))
        assertTrue(json.getBoolean("search_active"))
        assertFalse(json.getBoolean("is_offline"))
        assertEquals(32, json.getInt("total_matches"))
        assertEquals(4, json.getInt("filtered_matches"))
        assertEquals(2, json.getInt("live_matches"))
        assertEquals(1, json.getInt("followed_matches"))
    }

    @Test
    fun test_f3_3_settingsSnapshotCapturedCorrectly() {
        val settings = SettingsSnapshot(
            autoRefreshInterval = "60s",
            matchRetention = "48h",
            widgetSyncMode = "DISABLED",
            defaultTournament = "GRAND_SLAMS",
            telemetryEnabled = false
        )
        val snapshot = DiagnosticSnapshot(settingsSnapshot = settings)
        val json = snapshot.toJson().getJSONObject("settings_snapshot")

        assertEquals("60s", json.getString("auto_refresh_interval"))
        assertEquals("48h", json.getString("match_retention"))
        assertEquals("DISABLED", json.getString("widget_sync_mode"))
        assertEquals("GRAND_SLAMS", json.getString("default_tournament"))
        assertFalse(json.getBoolean("telemetry_enabled"))
    }

    @Test
    fun test_f3_4_contextualMatchSnapshotCapturedWhenProvided() {
        val matchCtx = MatchContextSnapshot(
            matchId = "40153321",
            tournamentName = "US Open",
            tournamentId = "us-open-2026",
            eventGrouping = "Men's Singles",
            effectiveTour = "ATP",
            effectiveCategory = "GRAND_SLAM",
            state = "LIVE",
            court = "Arthur Ashe Stadium"
        )
        val snapshot = DiagnosticSnapshot(matchContext = matchCtx)
        val json = snapshot.toJson()

        assertTrue(json.has("match_context"))
        val matchJson = json.getJSONObject("match_context")
        assertEquals("40153321", matchJson.getString("match_id"))
        assertEquals("US Open", matchJson.getString("tournament_name"))
        assertEquals("Arthur Ashe Stadium", matchJson.getString("court"))
    }

    @Test
    fun test_f3_5_nullMatchSnapshotWhenGlobalReport() {
        val snapshot = DiagnosticSnapshot(matchContext = null)
        val json = snapshot.toJson()
        assertFalse(json.has("match_context"))
    }

    // =========================================================================
    // Feature 4: Zero-PII Redaction Engine (6 tests)
    // =========================================================================

    @Test
    fun test_f4_1_scrubsGitHubPersonalAccessTokens() {
        val input = "Crash trace: token=ghp_ABC1234567890abcdefghijklmnopqrstuvwXYZ and auth=github_pat_11AAAAAAA01234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        val scrubbed = PiiScrubber.scrub(input)

        assertFalse("Raw ghp token must not be present", scrubbed.contains("ghp_ABC123"))
        assertFalse("Raw pat token must not be present", scrubbed.contains("github_pat_11AAAA"))
        assertTrue("Scrubbed text must indicate redaction", scrubbed.contains("[REDACTED_GITHUB_TOKEN]"))
    }

    @Test
    fun test_f4_2_scrubsBearerTokensAndApiKeys() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 and GoogleKey AIzaSyDummyKey_1234567890abcdefghijk"
        val scrubbed = PiiScrubber.scrub(input)

        assertFalse("Bearer token must not leak", scrubbed.contains("eyJhbGciOi"))
        assertFalse("Google API key must not leak", scrubbed.contains("AIzaSyDummyKey"))
        assertTrue(scrubbed.contains("[REDACTED_BEARER_TOKEN]"))
        assertTrue(scrubbed.contains("[REDACTED_API_KEY]"))
    }

    @Test
    fun test_f4_3_scrubsPasswordsAndCredentials() {
        val input = "User login error with password=SuperSecretPassword123! in connection string"
        val scrubbed = PiiScrubber.scrub(input)

        assertFalse("Password must not be present", scrubbed.contains("SuperSecretPassword123!"))
        assertTrue(scrubbed.contains("[REDACTED_PASSWORD]"))
    }

    @Test
    fun test_f4_4_scrubsIpv4Addresses() {
        val input = "Connecting to telemetry server at 192.168.1.105:8080 and DNS 8.8.8.8"
        val scrubbed = PiiScrubber.scrub(input)

        assertFalse("Local IP must not leak", scrubbed.contains("192.168.1.105"))
        assertFalse("DNS IP must not leak", scrubbed.contains("8.8.8.8"))
        assertTrue(scrubbed.contains("[REDACTED_IP]"))
    }

    @Test
    fun test_f4_5_scrubsEmailAddresses() {
        val input = "Submitted by developer user.name+debug@example-domain.org regarding court 4"
        val scrubbed = PiiScrubber.scrub(input)

        assertFalse("Email must not leak", scrubbed.contains("user.name+debug@example-domain.org"))
        assertTrue(scrubbed.contains("[REDACTED_EMAIL]"))
    }

    @Test
    fun test_f4_6_scrubsLocalFilesystemPaths() {
        val input = "Database opened at /storage/emulated/10/agy/.gemini/antigravity/scratch/db.sqlite on Android"
        val scrubbed = PiiScrubber.scrub(input)

        assertFalse("Local file path must not leak", scrubbed.contains("/storage/emulated/10"))
        assertTrue(scrubbed.contains("[REDACTED_PATH]"))
    }

    // =========================================================================
    // Feature 5: JSON Block & Markdown Formatter (5 tests)
    // =========================================================================

    @Test
    fun test_f5_1_jsonMarkersEmbeddedCorrectly() {
        val report = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "Tablet two-pane layout overlap",
            description = "Scoreboard columns collide on 1280dp display"
        )
        val snapshot = DiagnosticSnapshot(formFactor = "LAPTOP")
        val fullBody = snapshot.toFullIssueBody(report)

        assertTrue(fullBody.contains(JSON_MARKER_START))
        assertTrue(fullBody.contains(JSON_MARKER_END))
        val startIndex = fullBody.indexOf(JSON_MARKER_START)
        val endIndex = fullBody.indexOf(JSON_MARKER_END)
        assertTrue("Start marker must precede end marker", startIndex < endIndex)
    }

    @Test
    fun test_f5_2_embeddedJsonIsValidAndMatchesSchema() {
        val report = BugReport(
            category = BugReportCategory.WATCH_HIGHLIGHTS,
            summary = "Highlights button disabled",
            description = "Even when replay is ready"
        )
        val snapshot = DiagnosticSnapshot(appVersion = "1.0.25")
        val fullBody = snapshot.toFullIssueBody(report)

        val jsonStr = fullBody.substringAfter(JSON_MARKER_START).substringBefore(JSON_MARKER_END).trim()
        val parsedJson = JSONObject(jsonStr)

        assertEquals("1.0.25", parsedJson.getString("app_version"))
        assertTrue(parsedJson.has("scoreboard_state"))
        assertTrue(parsedJson.has("settings_snapshot"))
        assertTrue(parsedJson.has("breadcrumbs"))
    }

    @Test
    fun test_f5_3_markdownContainsHumanReadableSummary() {
        val report = BugReport(
            category = BugReportCategory.FILTER_TOUR_MISMATCH,
            summary = "WTA match listed under ATP filter",
            description = "Sabalenka vs Gauff shown in ATP tab"
        )
        val snapshot = DiagnosticSnapshot()
        val fullBody = snapshot.toFullIssueBody(report)

        assertTrue(fullBody.contains("## Bug Report: WTA match listed under ATP filter"))
        assertTrue(fullBody.contains("**Category**: Filter / Tour Mismatch (`filter-tour`)"))
        assertTrue(fullBody.contains("### Description"))
        assertTrue(fullBody.contains("### Diagnostic Snapshot"))
    }

    @Test
    fun test_f5_4_markdownDetailsBlockStructure() {
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Crash on startup",
            description = "App crashed immediately after splash"
        )
        val snapshot = DiagnosticSnapshot()
        val fullBody = snapshot.toFullIssueBody(report)

        assertTrue(fullBody.contains("<details>"))
        assertTrue(fullBody.contains("<summary>Machine-Readable Diagnostic Bundle</summary>"))
        assertTrue(fullBody.contains("</details>"))
    }

    @Test
    fun test_f5_5_formatterScrubsUserInputsBeforeEmbedding() {
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Leaked token in summary ghp_111122223333444455556666777788889999",
            description = "Secret key=SecretApiKey12345678901234567890 and email admin@tennis.org"
        )
        val snapshot = DiagnosticSnapshot()
        val fullBody = snapshot.toFullIssueBody(report)

        assertFalse(fullBody.contains("ghp_111122223333444455556666777788889999"))
        assertFalse(fullBody.contains("SecretApiKey12345678901234567890"))
        assertFalse(fullBody.contains("admin@tennis.org"))
        assertTrue(fullBody.contains("[REDACTED_GITHUB_TOKEN]"))
        assertTrue(fullBody.contains("[REDACTED_API_KEY]"))
        assertTrue(fullBody.contains("[REDACTED_EMAIL]"))
    }

    // =========================================================================
    // Feature 6: GitHub Issue URL & Fallback Builder (5 tests)
    // =========================================================================

    @Test
    fun test_f6_1_buildsValidGitHubNewIssueUrl() {
        val url = GitHubIssueUrlBuilder.buildNewIssueUrl(
            title = "[In-App Report] Score issue",
            body = "Description body",
            labels = listOf("bug", "in-app-report", "score-live-data")
        )
        assertTrue(url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        assertTrue(url.contains("title=%5BIn-App%20Report%5D%20Score%20issue"))
        assertTrue(url.contains("labels=bug%2Cin-app-report%2Cscore-live-data"))
    }

    @Test
    fun test_f6_2_encodesSpecialCharactersInTitleAndBody() {
        val title = "Scores & Stats: 6-4, 3-6 (Final) > Live"
        val body = "Line 1\nLine 2 = 100% & special #1?"
        val url = GitHubIssueUrlBuilder.buildNewIssueUrl(title, body, listOf("bug"))

        assertFalse("Raw ampersand should not appear unescaped in query values", url.contains(" & "))
        assertFalse("Raw newlines should not be in URL", url.contains("\n"))
        assertTrue(url.contains("6-4%2C%203-6"))
    }

    @Test
    fun test_f6_3_attachesExpectedLabels() {
        val report = BugReport(
            category = BugReportCategory.HOME_WIDGET,
            summary = "Widget stuck",
            description = "Does not refresh"
        )
        val snapshot = DiagnosticSnapshot()
        val fallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot)

        assertTrue(fallback.url.contains("labels=bug%2Cin-app-report%2Chome-widget"))
    }

    @Test
    fun test_f6_4_truncatesBodyWhenUrlExceedsSafeLimit() {
        val longText = "Very long description line. ".repeat(300)
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Long bug report",
            description = longText
        )
        val snapshot = DiagnosticSnapshot()
        val fallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot, maxUrlLength = 2000)

        assertTrue("Fallback URL should be kept within safe limit", fallback.url.length <= 2000)
        assertTrue(fallback.wasTruncated)
        assertTrue(fallback.clipboardInstructions.contains("clipboard", ignoreCase = true))
    }

    @Test
    fun test_f6_5_includesClipboardInstructionsOnTruncation() {
        val report = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "Layout issue",
            description = "A".repeat(5000)
        )
        val snapshot = DiagnosticSnapshot()
        val fallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot, maxUrlLength = 1500)

        assertTrue(fallback.wasTruncated)
        assertTrue(fallback.fullMarkdownBody.length > 5000)
    }

    // =========================================================================
    // Feature 7: Cloudflare Worker `/bug-report` Relay Schema (5 tests)
    // =========================================================================

    @Test
    fun test_f7_1_validPayloadReturns201CreatedWithIssueUrl() {
        val relay = MockWorkerRelay()
        val payload = JSONObject().apply {
            put("category", "filter-tour")
            put("summary", "Filter issue")
            put("description", "Steps to reproduce")
            put("diagnostic_snapshot", DiagnosticSnapshot().toJson())
        }

        val (status, response) = relay.handleRequest("POST", "192.168.1.1", payload)
        assertEquals(201, status)
        assertTrue(response.getBoolean("success"))
        assertTrue(response.getInt("issue_number") > 0)
        assertTrue(response.getString("issue_url").contains("yurilo/tennis_tracker/issues/"))
    }

    @Test
    fun test_f7_2_missingSummaryOrDescriptionReturns400BadRequest() {
        val relay = MockWorkerRelay()
        val badPayload = JSONObject().apply {
            put("category", "filter-tour")
            put("summary", "") // empty summary
            put("description", "Valid description")
            put("diagnostic_snapshot", DiagnosticSnapshot().toJson())
        }

        val (status, response) = relay.handleRequest("POST", "192.168.1.1", badPayload)
        assertEquals(400, status)
        assertTrue(response.has("error"))
    }

    @Test
    fun test_f7_3_invalidCategoryReturns400BadRequest() {
        val relay = MockWorkerRelay()
        val badPayload = JSONObject().apply {
            put("category", "completely-bogus-category")
            put("summary", "Valid summary")
            put("description", "Valid description")
            put("diagnostic_snapshot", DiagnosticSnapshot().toJson())
        }

        val (status, response) = relay.handleRequest("POST", "192.168.1.1", badPayload)
        assertEquals(400, status)
        assertTrue(response.getString("error").contains("Invalid category"))
    }

    @Test
    fun test_f7_4_optionsRequestReturns204WithCorsHeaders() {
        val relay = MockWorkerRelay()
        val (status, response) = relay.handleRequest("OPTIONS", "192.168.1.1", null)
        assertEquals(204, status)
        assertTrue(response.getBoolean("cors"))
    }

    @Test
    fun test_f7_5_unconfiguredTokenReturns503ServiceUnavailable() {
        val relay = MockWorkerRelay(configuredGithubToken = null)
        val payload = JSONObject().apply {
            put("category", "filter-tour")
            put("summary", "Valid summary")
            put("description", "Valid description")
            put("diagnostic_snapshot", DiagnosticSnapshot().toJson())
        }

        val (status, response) = relay.handleRequest("POST", "192.168.1.1", payload)
        assertEquals(503, status)
        assertTrue(response.getString("error").contains("token not configured", ignoreCase = true))
    }

    // =========================================================================
    // Feature 8: Automated Triage Script Parsing & Diagnostic Extraction (5 tests)
    // =========================================================================

    @Test
    fun test_f8_1_extractsDiagnosticJsonFromIssueBody() {
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Live score mismatch",
            description = "Score paused at 40-40"
        )
        val snapshot = DiagnosticSnapshot(appVersion = "1.0.25", versionCode = 25)
        val body = snapshot.toFullIssueBody(report)

        val extracted = TriageEngineHarness.extractDiagnosticJson(body)
        assertNotNull(extracted)
        assertEquals("1.0.25", extracted!!.getString("app_version"))
        assertEquals(25, extracted.getInt("version_code"))
    }

    @Test
    fun test_f8_2_returnsNullWhenMarkersAreMissing() {
        val bodyWithoutMarkers = "Just a plain markdown text without any json markers"
        val extracted = TriageEngineHarness.extractDiagnosticJson(bodyWithoutMarkers)
        assertNull(extracted)
    }

    @Test
    fun test_f8_3_mapsCategorySlugsToExpectedKotlinModules() {
        val filterFiles = TriageEngineHarness.mapCategoryToSuspectedFiles("filter-tour")
        assertTrue(filterFiles.any { it.contains("TournamentFiltering.kt") })
        assertTrue(filterFiles.any { it.contains("TourTierFilter.kt") })

        val scoreFiles = TriageEngineHarness.mapCategoryToSuspectedFiles("score-live-data")
        assertTrue(scoreFiles.any { it.contains("ScoreboardRepositoryImpl.kt") })
        assertTrue(scoreFiles.any { it.contains("EspnScoreboardApi.kt") })

        val widgetFiles = TriageEngineHarness.mapCategoryToSuspectedFiles("home-widget")
        assertTrue(widgetFiles.any { it.contains("WidgetAutoSyncManager.kt") })
    }

    @Test
    fun test_f8_4_generatesStructuredFiveSectionTriageReport() {
        val report = BugReport(
            category = BugReportCategory.FILTER_TOUR_MISMATCH,
            summary = "Tour tier filter discrepancy",
            description = "WTA filter shows ATP matches"
        )
        val snapshot = DiagnosticSnapshot()
        val body = snapshot.toFullIssueBody(report)

        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "[In-App Report] Tour tier filter discrepancy",
            issueBody = body,
            categorySlug = "filter-tour"
        )
        val comment = triage.toMarkdownComment()

        assertTrue(comment.contains("### 1. Diagnostic Summary"))
        assertTrue(comment.contains("### 2. Suspected Files & Symbols"))
        assertTrue(comment.contains("### 3. Related Commits / Issues"))
        assertTrue(comment.contains("### 4. Root-Cause Analysis"))
        assertTrue(comment.contains("### 5. Proposed Patch / Verification Steps"))
    }

    @Test
    fun test_f8_5_producesDeterministicPatchCandidate() {
        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "Widget sync lag",
            issueBody = DiagnosticSnapshot().toFullIssueBody(
                BugReport(BugReportCategory.HOME_WIDGET, "Widget sync lag", "Lag observed")
            ),
            categorySlug = "home-widget"
        )

        assertTrue(triage.proposedPatch.contains("```diff"))
        assertTrue(triage.proposedPatch.contains("--- a/"))
        assertTrue(triage.proposedPatch.contains("+++ b/"))
    }
}
