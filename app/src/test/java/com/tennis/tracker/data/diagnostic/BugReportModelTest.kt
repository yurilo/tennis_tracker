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

class BugReportModelTest {

    @Test
    fun testBugReportCategoryProperties() {
        val categories = BugReportCategory.entries
        assertEquals(6, categories.size)

        val tourMismatch = BugReportCategory.FILTER_TOUR_MISMATCH
        assertEquals("Filter / Tour Mismatch", tourMismatch.displayName)
        assertEquals("filter-tour", tourMismatch.labelSlug)
        assertEquals("⚡", tourMismatch.icon)

        val scoreData = BugReportCategory.SCORE_LIVE_DATA
        assertEquals("Score / Live Data", scoreData.displayName)
        assertEquals("score-live-data", scoreData.labelSlug)
        assertEquals("🎾", scoreData.icon)

        val watchHighlights = BugReportCategory.WATCH_HIGHLIGHTS
        assertEquals("Watch / Highlights", watchHighlights.displayName)
        assertEquals("watch-highlights", watchHighlights.labelSlug)
        assertEquals("📺", watchHighlights.icon)

        val homeWidget = BugReportCategory.HOME_WIDGET
        assertEquals("Home Widget", homeWidget.displayName)
        assertEquals("home-widget", homeWidget.labelSlug)
        assertEquals("📱", homeWidget.icon)

        val uiLayout = BugReportCategory.UI_LAYOUT
        assertEquals("UI / Layout", uiLayout.displayName)
        assertEquals("ui-layout", uiLayout.labelSlug)
        assertEquals("📐", uiLayout.icon)

        val other = BugReportCategory.OTHER
        assertEquals("Other", other.displayName)
        assertEquals("other", other.labelSlug)
        assertEquals("❓", other.icon)
    }

    @Test
    fun testBugReportCategoryLookups() {
        assertEquals(BugReportCategory.FILTER_TOUR_MISMATCH, BugReportCategory.fromLabelSlug("filter-tour"))
        assertEquals(BugReportCategory.FILTER_TOUR_MISMATCH, BugReportCategory.fromLabelSlug("FILTER-TOUR"))
        assertEquals(BugReportCategory.SCORE_LIVE_DATA, BugReportCategory.fromLabelSlug("score-live-data"))
        assertEquals(BugReportCategory.UI_LAYOUT, BugReportCategory.fromLabelSlug("UI_LAYOUT"))
        assertEquals(BugReportCategory.OTHER, BugReportCategory.fromLabelSlug("non-existent-slug"))
        assertEquals(BugReportCategory.OTHER, BugReportCategory.fromLabelSlug(null))
        assertEquals(BugReportCategory.OTHER, BugReportCategory.fromLabelSlug("   "))

        assertEquals(BugReportCategory.WATCH_HIGHLIGHTS, BugReportCategory.fromDisplayName("Watch / Highlights"))
        assertEquals(BugReportCategory.WATCH_HIGHLIGHTS, BugReportCategory.fromDisplayName("watch / highlights"))
        assertEquals(BugReportCategory.HOME_WIDGET, BugReportCategory.fromDisplayName("Home Widget"))
        assertEquals(BugReportCategory.OTHER, BugReportCategory.fromDisplayName("Random Category"))
        assertEquals(BugReportCategory.OTHER, BugReportCategory.fromDisplayName(null))
    }

    @Test
    fun testBugReportValidation() {
        val validReport = BugReport(
            category = BugReportCategory.UI_LAYOUT,
            summary = "Card overflow on rotate",
            description = "Rotating the screen causes cards to overlap"
        )
        assertTrue(validReport.isValid)
        assertEquals("[UI / Layout] Card overflow on rotate", validReport.formattedTitle)

        val emptySummary = BugReport(summary = "   ", description = "Valid description")
        assertFalse(emptySummary.isValid)

        val emptyDescription = BugReport(summary = "Valid summary", description = "")
        assertFalse(emptyDescription.isValid)

        val withRepro = BugReport(
            summary = "S",
            description = "D",
            reproductionSteps = " 1. Step one\n2. Step two "
        )
        assertEquals("1. Step one\n2. Step two", withRepro.cleanedReproductionSteps)

        val blankRepro = BugReport(summary = "S", description = "D", reproductionSteps = "   ")
        assertNull(blankRepro.cleanedReproductionSteps)
    }

    @Test
    fun testDiagnosticBreadcrumbJsonRoundtrip() {
        val original = DiagnosticBreadcrumb(
            timestamp = 1730380000000L,
            tag = "NetworkRepository",
            message = "Scoreboard cache updated",
            metadata = mapOf("status" to "200", "source" to "ESPN")
        )

        val json = original.toJson()
        assertEquals(1730380000000L, json.getLong("timestamp"))
        assertEquals("NetworkRepository", json.getString("tag"))
        assertEquals("Scoreboard cache updated", json.getString("message"))
        assertEquals("200", json.getJSONObject("metadata").getString("status"))
        assertEquals("ESPN", json.getJSONObject("metadata").getString("source"))

        val parsed = DiagnosticBreadcrumb.fromJson(json)
        assertEquals(original.timestamp, parsed.timestamp)
        assertEquals(original.tag, parsed.tag)
        assertEquals(original.message, parsed.message)
        assertEquals(original.metadata, parsed.metadata)
    }

    @Test
    fun testScoreboardDiagnosticStateJsonRoundtrip() {
        val state = ScoreboardDiagnosticState(
            tourFilter = "GRAND_SLAMS",
            currentTournament = "us_open",
            matchTypeFilter = "SINGLES",
            matchFilter = "LIVE",
            isOffline = false,
            searchActive = true,
            searchQuery = "Alcaraz",
            totalMatches = 32,
            filteredMatches = 4,
            liveMatches = 2,
            followedMatches = 1
        )

        val json = state.toJson()
        assertEquals("GRAND_SLAMS", json.getString("tour_filter"))
        assertEquals("us_open", json.getString("current_tournament"))
        assertEquals("SINGLES", json.getString("match_type_filter"))
        assertEquals("LIVE", json.getString("match_filter"))
        assertFalse(json.getBoolean("is_offline"))
        assertTrue(json.getBoolean("search_active"))
        assertEquals("Alcaraz", json.getString("search_query"))
        assertEquals(32, json.getInt("total_matches"))
        assertEquals(4, json.getInt("filtered_matches"))
        assertEquals(2, json.getInt("live_matches"))
        assertEquals(1, json.getInt("followed_matches"))

        val parsed = ScoreboardDiagnosticState.fromJson(json)
        assertEquals(state, parsed)
    }

    @Test
    fun testAppSettingsSnapshotJsonRoundtrip() {
        val settings = AppSettingsSnapshot(
            autoRefreshInterval = "INTERVAL_30S",
            matchRetention = "DAYS_7",
            widgetSyncMode = "REALTIME_ONLY",
            defaultTournament = "GRAND_SLAMS",
            telemetryEnabled = false,
            hasCustomEndpoint = true
        )

        val json = settings.toJson()
        val parsed = AppSettingsSnapshot.fromJson(json)
        assertEquals(settings, parsed)
    }

    @Test
    fun testMatchContextSnapshotJsonRoundtrip() {
        val match = MatchContextSnapshot(
            matchId = "match_101",
            tournamentName = "US Open",
            tournamentId = "us_open",
            eventGrouping = "Men's Singles",
            effectiveTour = "ATP",
            effectiveCategory = "GRAND_SLAM",
            state = "LIVE",
            court = "Arthur Ashe Stadium",
            venue = "USTA Billie Jean King National Tennis Center",
            roundName = "Quarterfinals",
            statusDetail = "In Progress - Set 4"
        )

        val json = match.toJson()
        assertEquals("match_101", json.getString("match_id"))
        assertEquals("US Open", json.getString("tournament_name"))
        assertEquals("us_open", json.getString("tournament_id"))
        assertEquals("Arthur Ashe Stadium", json.getString("court"))

        val parsed = MatchContextSnapshot.fromJson(json)
        assertEquals(match, parsed)
    }

    @Test
    fun testDiagnosticSnapshotFullJsonRoundtrip() {
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 26,
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED",
            screenWidthDp = 1024,
            screenHeightDp = 768,
            osVersion = "14",
            apiLevel = 34,
            deviceModel = "Pixel Tablet",
            deviceManufacturer = "Google",
            scoreboardState = ScoreboardDiagnosticState(totalMatches = 10),
            settingsSnapshot = AppSettingsSnapshot(autoRefreshInterval = "INTERVAL_60S"),
            matchContext = MatchContextSnapshot(matchId = "401", tournamentName = "US Open"),
            breadcrumbs = listOf(
                DiagnosticBreadcrumb(tag = "Repo", message = "Sync completed")
            ),
            timestamp = 1730380005000L
        )

        val json = snapshot.toJson()
        assertEquals("1.0.25", json.getString("app_version"))
        assertEquals(26, json.getInt("version_code"))
        assertEquals("LAPTOP", json.getString("form_factor"))
        assertEquals("EXPANDED", json.getString("screen_width_bucket"))
        assertEquals(1024, json.getInt("screen_width_dp"))
        assertEquals(1, json.getJSONArray("breadcrumbs").length())
        assertNotNull(json.getJSONObject("match_context"))

        val parsed = DiagnosticSnapshot.fromJson(json)
        assertEquals(snapshot.appVersion, parsed.appVersion)
        assertEquals(snapshot.versionCode, parsed.versionCode)
        assertEquals(snapshot.formFactor, parsed.formFactor)
        assertEquals(snapshot.screenWidthBucket, parsed.screenWidthBucket)
        assertEquals(snapshot.screenWidthDp, parsed.screenWidthDp)
        assertEquals(snapshot.scoreboardState.totalMatches, parsed.scoreboardState.totalMatches)
        assertEquals(snapshot.settingsSnapshot.autoRefreshInterval, parsed.settingsSnapshot.autoRefreshInterval)
        assertEquals(snapshot.matchContext?.matchId, parsed.matchContext?.matchId)
        assertEquals(1, parsed.breadcrumbs.size)
        assertEquals("Repo", parsed.breadcrumbs[0].tag)
    }

    @Test
    fun testDiagnosticSnapshotWithNullMatchContext() {
        val snapshot = DiagnosticSnapshot(matchContext = null)
        val json = snapshot.toJson()
        assertTrue(json.isNull("match_context"))

        val parsed = DiagnosticSnapshot.fromJson(json)
        assertNull(parsed.matchContext)
    }
}
