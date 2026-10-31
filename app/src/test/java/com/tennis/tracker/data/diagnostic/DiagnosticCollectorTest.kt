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
import com.tennis.tracker.ui.viewmodel.MatchFilter
import com.tennis.tracker.ui.viewmodel.MatchTypeFilter
import com.tennis.tracker.ui.viewmodel.ScoreboardUiState
import com.tennis.tracker.ui.viewmodel.TourTierFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticCollectorTest {

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

    @Test
    fun testCollectSnapshot_withDefaultStateAndSettings() {
        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        assertEquals("1.0.25", snapshot.appVersion)
        assertEquals(26, snapshot.versionCode)
        assertEquals("PHONE", snapshot.formFactor)
        assertEquals("COMPACT", snapshot.screenWidthBucket)
        assertEquals(412, snapshot.screenWidthDp)
        assertEquals(915, snapshot.screenHeightDp)
        assertNull(snapshot.matchContext)
        assertTrue(snapshot.breadcrumbs.isEmpty())
        assertFalse(snapshot.scoreboardState.isOffline)
        assertFalse(snapshot.scoreboardState.searchActive)
        assertEquals("ALL", snapshot.scoreboardState.tourFilter)
        assertEquals("All Tournaments", snapshot.scoreboardState.currentTournament)
        assertEquals("INTERVAL_60S", snapshot.settingsSnapshot.autoRefreshInterval)
        assertFalse(snapshot.settingsSnapshot.hasCustomEndpoint)
    }

    @Test
    fun testCollectSnapshot_withCustomScoreboardStateAndFilters() {
        val dummyMatch = Match(
            id = "m1",
            tournamentName = "US Open",
            state = MatchState.LIVE,
            statusDetail = "Final",
            player1 = Competitor(id = "p1", name = "Player 1"),
            player2 = Competitor(id = "p2", name = "Player 2")
        )
        val customTournament = Tournament(id = "us_open", displayName = "US Open")
        val state = ScoreboardUiState(
            allMatches = listOf(dummyMatch, dummyMatch),
            filteredMatches = listOf(dummyMatch),
            selectedTierFilter = TourTierFilter.GRAND_SLAMS,
            selectedMatchTypeFilter = MatchTypeFilter.SINGLES,
            selectedFilter = MatchFilter.LIVE,
            searchQuery = "Sinner",
            isOffline = true,
            liveCount = 3,
            followedCount = 2,
            currentTournament = customTournament
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = state,
            settings = AppSettings.DEFAULT
        )

        val scoreboard = snapshot.scoreboardState
        assertEquals("GRAND_SLAMS", scoreboard.tourFilter)
        assertEquals("us_open", scoreboard.currentTournament)
        assertEquals("SINGLES", scoreboard.matchTypeFilter)
        assertEquals("LIVE", scoreboard.matchFilter)
        assertTrue(scoreboard.isOffline)
        assertTrue(scoreboard.searchActive)
        assertEquals("Sinner", scoreboard.searchQuery)
        assertEquals(2, scoreboard.totalMatches)
        assertEquals(1, scoreboard.filteredMatches)
        assertEquals(3, scoreboard.liveMatches)
        assertEquals(2, scoreboard.followedMatches)
    }

    @Test
    fun testCollectSnapshot_withCustomAppSettings() {
        val customSettings = AppSettings(
            autoRefreshInterval = AutoRefreshInterval.INTERVAL_30S,
            matchRetention = MatchRetentionPolicy.DAYS_7,
            widgetSyncMode = WidgetSyncMode.INTERVAL_15M,
            defaultTournament = StartupTournamentTier.ATP_TOUR,
            telemetryEnabled = false,
            telemetryEndpoint = "https://custom.telemetry.example/heartbeat"
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = customSettings
        )

        val settingsSnapshot = snapshot.settingsSnapshot
        assertEquals("INTERVAL_30S", settingsSnapshot.autoRefreshInterval)
        assertEquals("DAYS_7", settingsSnapshot.matchRetention)
        assertEquals("INTERVAL_15M", settingsSnapshot.widgetSyncMode)
        assertEquals("ATP_TOUR", settingsSnapshot.defaultTournament)
        assertFalse(settingsSnapshot.telemetryEnabled)
        assertTrue(settingsSnapshot.hasCustomEndpoint)
    }

    @Test
    fun testCollectSnapshot_withTargetMatchMetadata() {
        val tournament = Tournament(
            id = "us_open",
            displayName = "US Open",
            isGrandSlam = true,
            tour = TourType.COMBINED,
            category = TournamentCategory.GRAND_SLAM
        )
        val targetMatch = Match(
            id = "match_usopen_001",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Semifinals",
            state = MatchState.LIVE,
            statusDetail = "In Progress - Set 3",
            player1 = Competitor(id = "p1", name = "Carlos Alcaraz"),
            player2 = Competitor(id = "p2", name = "Novak Djokovic"),
            court = "Arthur Ashe Stadium",
            venue = "USTA Billie Jean King National Tennis Center",
            tournament = tournament,
            tour = TourType.ATP,
            category = TournamentCategory.GRAND_SLAM
        )

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            targetMatch = targetMatch
        )

        assertNotNull(snapshot.matchContext)
        val context = snapshot.matchContext!!
        assertEquals("match_usopen_001", context.matchId)
        assertEquals("US Open", context.tournamentName)
        assertEquals("us_open", context.tournamentId)
        assertEquals("Men's Singles", context.eventGrouping)
        assertEquals("ATP", context.effectiveTour)
        assertEquals("GRAND_SLAM", context.effectiveCategory)
        assertEquals("LIVE", context.state)
        assertEquals("Arthur Ashe Stadium", context.court)
        assertEquals("USTA Billie Jean King National Tennis Center", context.venue)
        assertEquals("Semifinals", context.roundName)
        assertEquals("In Progress - Set 3", context.statusDetail)
    }

    @Test
    fun testCollectSnapshot_withNullTargetMatch_omitsMatchContext() {
        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            targetMatch = null
        )
        assertNull(snapshot.matchContext)
    }

    @Test
    fun testCollectSnapshot_includesBreadcrumbsFromBuffer() {
        breadcrumbBuffer.log("Repo", "Loaded offline cache")
        breadcrumbBuffer.log("Network", "Polling scores")
        breadcrumbBuffer.log("UI", "User toggled filter")

        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )

        assertEquals(3, snapshot.breadcrumbs.size)
        assertEquals("Repo", snapshot.breadcrumbs[0].tag)
        assertEquals("Network", snapshot.breadcrumbs[1].tag)
        assertEquals("UI", snapshot.breadcrumbs[2].tag)
    }

    @Test
    fun testCollectSnapshot_formFactorResolution_phoneVsLaptop() {
        // Compact phone
        val phoneSnapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = 412,
            overrideHeightDp = 915
        )
        assertEquals("PHONE", phoneSnapshot.formFactor)
        assertEquals("COMPACT", phoneSnapshot.screenWidthBucket)

        // Medium foldable / tablet
        val mediumSnapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = 720,
            overrideHeightDp = 900
        )
        assertEquals("LAPTOP", mediumSnapshot.formFactor)
        assertEquals("MEDIUM", mediumSnapshot.screenWidthBucket)

        // Expanded laptop
        val laptopSnapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT,
            overrideWidthDp = 1024,
            overrideHeightDp = 768
        )
        assertEquals("LAPTOP", laptopSnapshot.formFactor)
        assertEquals("EXPANDED", laptopSnapshot.screenWidthBucket)
    }

    @Test
    fun testCollectSnapshot_resilientToContextPackageManagerFailures() {
        // Even when context is null or mocks fail, collector gracefully returns defaults
        val snapshot = collector.collect(
            context = null,
            scoreboardState = ScoreboardUiState(),
            settings = AppSettings.DEFAULT
        )
        assertEquals("1.0.25", snapshot.appVersion)
        assertEquals(26, snapshot.versionCode)
    }
}
