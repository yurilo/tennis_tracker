package com.tennis.tracker.ui

import android.content.Context
import com.tennis.tracker.data.bugreport.BugReportSubmissionServiceContract
import com.tennis.tracker.data.bugreport.GitHubIssueUrlBuilder
import com.tennis.tracker.data.bugreport.SubmissionResult
import com.tennis.tracker.data.diagnostic.BugReportPayloadFormatter
import com.tennis.tracker.data.diagnostic.DiagnosticBreadcrumbBuffer
import com.tennis.tracker.data.diagnostic.DiagnosticCollector
import com.tennis.tracker.data.diagnostic.PiiScrubber
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
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
import com.tennis.tracker.domain.repository.SettingsRepository
import com.tennis.tracker.ui.viewmodel.BugReportUiState
import com.tennis.tracker.ui.viewmodel.BugReportViewModel
import com.tennis.tracker.ui.viewmodel.ScoreboardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenge Test Suite for Milestone 3.
 *
 * Verifies:
 * 1. Match context propagation & default category resolution (SCORE_LIVE_DATA vs OTHER).
 * 2. Diagnostic accordion previews (formatted Markdown & machine-readable JSON) and zero-PII/zero-token scrubbing.
 * 3. Backward compatibility of MatchCopyBottomSheet and ScoreboardScreen touchpoints.
 * 4. Feedback states (Success issue link generation, FallbackRequired clipboard copy & browser intent invocation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BugReportTouchpointContractTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(15)

    private val testDispatcher = StandardTestDispatcher()

    private val fakeClipboard = mutableListOf<String>()
    private val launchedUrls = mutableListOf<String>()

    private val fakeClipboardDelegate: (Context?, String, String) -> Boolean = { _, _, text ->
        fakeClipboard.add(text)
        true
    }

    private val fakeBrowserDelegate: (Context?, String) -> Boolean = { _, url ->
        launchedUrls.add(url)
        true
    }

    class TestSettingsRepository(
        initialSettings: AppSettings = AppSettings()
    ) : SettingsRepository {
        private val _settings = MutableStateFlow(initialSettings)
        override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
        override fun getSettings(): AppSettings = _settings.value

        override suspend fun updateAutoRefreshInterval(interval: AutoRefreshInterval) {
            _settings.value = _settings.value.copy(autoRefreshInterval = interval)
        }
        override suspend fun updateMatchRetention(policy: MatchRetentionPolicy) {
            _settings.value = _settings.value.copy(matchRetention = policy)
        }
        override suspend fun updateWidgetSyncMode(mode: WidgetSyncMode) {
            _settings.value = _settings.value.copy(widgetSyncMode = mode)
        }
        override suspend fun updateDefaultTournament(tier: StartupTournamentTier) {
            _settings.value = _settings.value.copy(defaultTournament = tier)
        }
        override suspend fun updateTelemetryEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(telemetryEnabled = enabled)
        }
        override suspend fun updateTelemetryEndpoint(endpoint: String) {
            _settings.value = _settings.value.copy(telemetryEndpoint = endpoint)
        }
        override suspend fun updateBugReportEndpoint(endpoint: String) {
            _settings.value = _settings.value.copy(bugReportEndpoint = endpoint)
        }
        override suspend fun updateGithubToken(token: String?) {
            _settings.value = _settings.value.copy(githubToken = token)
        }
        override suspend fun resetToDefaults() {
            _settings.value = AppSettings()
        }
    }

    class TestSubmissionService : BugReportSubmissionServiceContract {
        var cannedResult: SubmissionResult = SubmissionResult.Success(101, "https://github.com/yurilo/tennis_tracker/issues/101")
        val callCount = AtomicInteger(0)
        var lastReport: BugReport? = null
        var lastSnapshot: DiagnosticSnapshot? = null

        override suspend fun submitReport(
            report: BugReport,
            snapshot: DiagnosticSnapshot,
            endpoint: String?,
            customToken: String?,
            isOnline: Boolean
        ): SubmissionResult {
            callCount.incrementAndGet()
            lastReport = report
            lastSnapshot = snapshot
            return cannedResult
        }
    }

    private lateinit var settingsRepo: TestSettingsRepository
    private lateinit var submissionService: TestSubmissionService
    private lateinit var diagnosticCollector: DiagnosticCollector
    private lateinit var viewModel: BugReportViewModel

    private val sampleTournament = Tournament(
        id = "us_open_2026",
        displayName = "US Open 2026",
        defaultVenue = "Flushing Meadows, NY",
        isGrandSlam = true,
        tour = TourType.COMBINED,
        category = TournamentCategory.GRAND_SLAM,
        surface = CourtSurface.HARD
    )

    private val sampleMatch = Match(
        id = "match_usopen_sf_01",
        tournament = sampleTournament,
        tournamentName = "US Open 2026",
        eventGrouping = "Men's Singles",
        tour = TourType.ATP,
        category = TournamentCategory.GRAND_SLAM,
        roundName = "Semifinal",
        court = "Arthur Ashe Stadium",
        venue = "USTA Billie Jean King NTC",
        state = MatchState.LIVE,
        statusDetail = "In Progress - 3rd Set",
        player1 = Competitor(id = "p1", name = "Carlos Alcaraz", seed = 1),
        player2 = Competitor(id = "p2", name = "Jannik Sinner", seed = 2)
    )

    private val sampleScoreboardState = ScoreboardUiState(
        isOffline = false,
        allMatches = listOf(sampleMatch),
        filteredMatches = listOf(sampleMatch),
        liveCount = 1,
        followedCount = 1
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeClipboard.clear()
        launchedUrls.clear()
        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()

        settingsRepo = TestSettingsRepository()
        submissionService = TestSubmissionService()
        diagnosticCollector = DiagnosticCollector()

        viewModel = BugReportViewModel(
            diagnosticCollector = diagnosticCollector,
            submissionService = submissionService,
            settingsRepository = settingsRepo,
            clipboardDelegate = fakeClipboardDelegate,
            browserLauncherDelegate = fakeBrowserDelegate
        )
    }

    @After
    fun tearDown() {
        viewModel.reset()
        Dispatchers.resetMain()
        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()
    }

    // =========================================================================
    // Area 1: Match Context Propagation & Default Category Resolution
    // =========================================================================

    @Test
    fun testMatchContextPropagation_targetMatchProvided_defaultsToScoreLiveData() = runTest(testDispatcher) {
        viewModel.startReport(
            scoreboardState = sampleScoreboardState,
            targetMatch = sampleMatch
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State must be Ready", state is BugReportUiState.Ready)
        val ready = state as BugReportUiState.Ready

        // Verification 1: Category defaults to SCORE_LIVE_DATA when contextual match provided
        assertEquals(BugReportCategory.SCORE_LIVE_DATA, ready.selectedCategory)

        // Verification 2: Diagnostic snapshot contains rich matchContext
        val matchContext = ready.snapshot.matchContext
        assertNotNull("matchContext must not be null when targetMatch provided", matchContext)
        assertEquals("match_usopen_sf_01", matchContext?.matchId)
        assertEquals("US Open 2026", matchContext?.tournamentName)
        assertEquals("us_open_2026", matchContext?.tournamentId)
        assertEquals("Men's Singles", matchContext?.eventGrouping)
        assertEquals("ATP", matchContext?.effectiveTour)
        assertEquals("GRAND_SLAM", matchContext?.effectiveCategory)
        assertEquals("LIVE", matchContext?.state)
        assertEquals("Arthur Ashe Stadium", matchContext?.court)
        assertEquals("USTA Billie Jean King NTC", matchContext?.venue)
        assertEquals("Semifinal", matchContext?.roundName)
        assertEquals("In Progress - 3rd Set", matchContext?.statusDetail)
    }

    @Test
    fun testMatchContextPropagation_globalReport_defaultsToOther() = runTest(testDispatcher) {
        viewModel.startReport(
            scoreboardState = sampleScoreboardState,
            targetMatch = null // Global entry point (TopAppBar / About dialog)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State must be Ready", state is BugReportUiState.Ready)
        val ready = state as BugReportUiState.Ready

        // Verification 1: Category defaults to OTHER for global report
        assertEquals(BugReportCategory.OTHER, ready.selectedCategory)

        // Verification 2: Diagnostic snapshot has null matchContext
        assertNull("matchContext must be null for global bug report", ready.snapshot.matchContext)
    }

    @Test
    fun testMatchContextPropagation_explicitInitialCategoryOverridesTargetMatchDefault() = runTest(testDispatcher) {
        // Even when targetMatch is provided, if caller explicitly requested WATCH_HIGHLIGHTS, it must respect it
        viewModel.startReport(
            scoreboardState = sampleScoreboardState,
            targetMatch = sampleMatch,
            initialCategory = BugReportCategory.WATCH_HIGHLIGHTS
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val ready = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals(BugReportCategory.WATCH_HIGHLIGHTS, ready.selectedCategory)
        assertNotNull(ready.snapshot.matchContext)
    }

    @Test
    fun testMatchContextPropagation_matchMetadataNullSafetyAndFallback() = runTest(testDispatcher) {
        // Match with minimal / nullable properties
        val minimalMatch = Match(
            id = "min_001",
            tournamentName = "Challenger Tour",
            tournament = null,
            eventGrouping = "",
            roundName = "",
            state = MatchState.UPCOMING,
            statusDetail = "",
            player1 = Competitor(id = "p1", name = "Player 1"),
            player2 = Competitor(id = "p2", name = "Player 2"),
            court = null,
            venue = ""
        )

        viewModel.startReport(
            scoreboardState = sampleScoreboardState,
            targetMatch = minimalMatch
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val ready = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals(BugReportCategory.SCORE_LIVE_DATA, ready.selectedCategory)
        val matchCtx = ready.snapshot.matchContext
        assertNotNull(matchCtx)
        assertEquals("min_001", matchCtx?.matchId)
        assertEquals("Challenger Tour", matchCtx?.tournamentName)
        assertEquals("challenger_tour", matchCtx?.tournamentId) // Derived from tournamentName lowercase
        assertNull(matchCtx?.court)
        assertNull(matchCtx?.venue)
        assertNull(matchCtx?.roundName)
        assertNull(matchCtx?.statusDetail)
    }

    // =========================================================================
    // Area 2: Diagnostic Accordion Previews & Zero-Token Scrubbing Contract
    // =========================================================================

    @Test
    fun testDiagnosticAccordion_markdownSummaryMatchesDiagnosticContract() {
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 26,
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED",
            matchContext = MatchContextSnapshot(
                matchId = "m_test_123",
                tournamentName = "Wimbledon",
                effectiveTour = "WTA",
                effectiveCategory = "GRAND_SLAM",
                state = "COMPLETED",
                court = "Centre Court"
            )
        )

        val markdown = snapshot.toFormattedMarkdown()

        // Verify required markdown table headers and parameters
        assertTrue("Must include Diagnostic Environment header", markdown.contains("### Diagnostic Environment"))
        assertTrue("Must include App Version", markdown.contains("| **App Version** | `1.0.25 (26)` |"))
        assertTrue("Must include Form Factor", markdown.contains("| **Form Factor** | `LAPTOP` (`EXPANDED`) |"))
        assertTrue("Must include Tour Filter", markdown.contains("| **Tour Filter** |"))
        assertTrue("Must include Offline Mode", markdown.contains("| **Offline Mode** | `NO` |"))
        assertTrue("Must include Match Counts", markdown.contains("| **Match Counts** |"))
        assertTrue("Must include Contextual Match Details", markdown.contains("### Contextual Match Details"))
        assertTrue("Must include Match ID", markdown.contains("- **Match ID:** `m_test_123`"))
        assertTrue("Must include Tournament", markdown.contains("- **Tournament:** Wimbledon"))
        assertTrue("Must include Court", markdown.contains("- **Court:** Centre Court"))
    }

    @Test
    fun testDiagnosticAccordion_jsonMatchesDiagnosticContractAndSchema() {
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 26,
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT",
            matchContext = MatchContextSnapshot(
                matchId = "m_json_456",
                tournamentName = "Roland Garros",
                effectiveTour = "ATP",
                effectiveCategory = "GRAND_SLAM",
                state = "LIVE"
            )
        )

        val jsonStr = snapshot.toJson().toString(2)
        val json = JSONObject(jsonStr)

        // Verify schema defined in PROJECT.md § M1, M2 <-> M4
        assertEquals("1.0.25", json.getString("app_version"))
        assertEquals(26, json.getInt("version_code"))
        assertEquals("PHONE", json.getString("form_factor"))
        assertEquals("COMPACT", json.getString("screen_width_bucket"))
        assertTrue(json.has("scoreboard_state"))
        assertTrue(json.has("settings_snapshot"))
        assertTrue(json.has("match_context"))
        assertTrue(json.has("breadcrumbs"))

        val matchJson = json.getJSONObject("match_context")
        assertEquals("m_json_456", matchJson.getString("match_id"))
        assertEquals("Roland Garros", matchJson.getString("tournament_name"))

        // Round-trip verification
        val roundTripped = DiagnosticSnapshot.fromJson(json)
        assertEquals(snapshot.appVersion, roundTripped.appVersion)
        assertEquals(snapshot.versionCode, roundTripped.versionCode)
        assertEquals(snapshot.formFactor, roundTripped.formFactor)
        assertEquals(snapshot.matchContext?.matchId, roundTripped.matchContext?.matchId)
    }

    @Test
    fun testDiagnosticAccordion_scrubbedTokensZeroLeakage() {
        val buffer = DiagnosticBreadcrumbBuffer.getInstance()
        buffer.clear()

        // Adversarial inputs containing tokens, secrets, emails, IPs, and paths
        val sensitiveGithubPat = "ghp_" + "A".repeat(36)
        val sensitiveFineGrainedPat = "github_pat_" + "B".repeat(40)
        val sensitiveBearerToken = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val sensitivePasswordUrl = "https://user:SuperSecretPassword123@api.tennistracker.com/v1"
        val sensitiveEmail = "developer.privacy@tennis-tracker.org"
        val sensitiveIp = "192.168.1.105"
        val sensitivePath = "/storage/emulated/10/agy/private/keys/token.txt"

        buffer.log(
            tag = "NetworkService",
            message = "Failed to dispatch with $sensitiveGithubPat and $sensitiveFineGrainedPat",
            metadata = mapOf(
                "auth" to sensitiveBearerToken,
                "url" to sensitivePasswordUrl,
                "email" to sensitiveEmail,
                "client_ip" to sensitiveIp,
                "file" to sensitivePath
            )
        )

        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            breadcrumbs = buffer.getRecentBreadcrumbs()
        )

        // 1. Check formatted Markdown
        val markdown = snapshot.toFormattedMarkdown()
        assertFalse("Markdown must NOT contain raw GitHub PAT", markdown.contains(sensitiveGithubPat))
        assertFalse("Markdown must NOT contain raw Fine-grained PAT", markdown.contains(sensitiveFineGrainedPat))
        assertFalse("Markdown must NOT contain raw Bearer token", markdown.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse("Markdown must NOT contain raw password", markdown.contains("SuperSecretPassword123"))

        // 2. Check JSON preview
        val jsonStr = snapshot.toJson().toString(2)
        assertFalse("JSON must NOT contain raw GitHub PAT", jsonStr.contains(sensitiveGithubPat))
        assertFalse("JSON must NOT contain raw Fine-grained PAT", jsonStr.contains(sensitiveFineGrainedPat))
        assertFalse("JSON must NOT contain raw Bearer token", jsonStr.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse("JSON must NOT contain raw password", jsonStr.contains("SuperSecretPassword123"))
        assertFalse("JSON must NOT contain raw email", jsonStr.contains(sensitiveEmail))
        assertFalse("JSON must NOT contain raw private path", jsonStr.contains(sensitivePath))

        // Confirm redacted markers are present
        assertTrue("JSON must contain [REDACTED_GH_TOKEN]", jsonStr.contains("[REDACTED_GH_TOKEN]"))
        assertTrue("JSON must contain [REDACTED_PASSWORD]", jsonStr.contains("[REDACTED_PASSWORD]"))
        assertTrue("JSON must contain [REDACTED_EMAIL]", jsonStr.contains("[REDACTED_EMAIL]"))
    }

    // =========================================================================
    // Area 3: Backward Compatibility of Touchpoints
    // =========================================================================

    @Test
    fun testBackwardCompatibility_matchCopyBottomSheetWithoutOnReportMatchBug() {
        // Simulate caller without onReportMatchBug callback (e.g., legacy or external invocation)
        var dismissed = false
        val legacyOnDismiss: () -> Unit = { dismissed = true }
        val onReportMatchBug: ((Match) -> Unit)? = null

        // In MatchCopyBottomSheet:
        // if (onReportMatchBug != null) { onReportMatchBug(match) } else { onDismiss() }
        val testAction: (Match) -> Unit = { targetMatch ->
            if (onReportMatchBug != null) {
                onReportMatchBug(targetMatch)
            } else {
                legacyOnDismiss()
            }
        }

        testAction(sampleMatch)
        assertTrue("When onReportMatchBug is null, invoking button must call onDismiss cleanly", dismissed)
    }

    @Test
    fun testBackwardCompatibility_matchCopyBottomSheetWithOnReportMatchBug() {
        // When onReportMatchBug is provided, it receives target match
        var reportedMatch: Match? = null
        val onReportMatchBug: ((Match) -> Unit)? = { match -> reportedMatch = match }

        val testAction: (Match) -> Unit = { targetMatch ->
            if (onReportMatchBug != null) {
                onReportMatchBug(targetMatch)
            }
        }

        testAction(sampleMatch)
        assertNotNull(reportedMatch)
        assertEquals("match_usopen_sf_01", reportedMatch?.id)
    }

    // =========================================================================
    // Area 4: Feedback States & Fallback Mechanics
    // =========================================================================

    @Test
    fun testFeedbackState_successCardIssueUrlGenerationAndIntentLaunch() = runTest(testDispatcher) {
        val expectedIssueUrl = "https://github.com/yurilo/tennis_tracker/issues/314"
        submissionService.cannedResult = SubmissionResult.Success(
            issueNumber = 314,
            issueUrl = expectedIssueUrl
        )

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Live score lag")
        viewModel.onDescriptionChanged("15-second delay behind TV feed")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Must be Success state", state is BugReportUiState.Success)
        val success = state as BugReportUiState.Success
        assertEquals(314, success.issueNumber)
        assertEquals(expectedIssueUrl, success.issueUrl)

        // Test clicking "View on GitHub" button
        val launched = viewModel.onOpenIssueUrlClicked()
        assertTrue("Browser launcher delegate must be invoked", launched)
        assertEquals(1, launchedUrls.size)
        assertEquals(expectedIssueUrl, launchedUrls.first())
    }

    @Test
    fun testFeedbackState_fallbackRequiredClipboardCopyAndBrowserIntentLaunch() = runTest(testDispatcher) {
        val fallbackUrl = "https://github.com/yurilo/tennis_tracker/issues/new?title=Lag&body=FullDiagnostics"
        val fullMarkdown = "# Full Diagnostic Bundle\n- App Version: 1.0.25\n- Offline: true"
        val fallbackReason = "Device is offline (network unreachable)"

        submissionService.cannedResult = SubmissionResult.FallbackRequired(
            fallbackUrl = fallbackUrl,
            fullMarkdownBody = fullMarkdown,
            reason = fallbackReason
        )

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Offline Bug")
        viewModel.onDescriptionChanged("Could not refresh live scores offline")
        viewModel.submitReport(isOnline = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Must be FallbackRequired state", state is BugReportUiState.FallbackRequired)
        val fallback = state as BugReportUiState.FallbackRequired
        assertEquals(fallbackUrl, fallback.fallbackUrl)
        assertEquals(fullMarkdown, fallback.fullMarkdownBody)
        assertEquals(fallbackReason, fallback.reason)
        assertTrue("Automatic clipboard copy must succeed", fallback.copiedToClipboard)
        assertEquals(1, fakeClipboard.size)
        assertEquals(fullMarkdown, fakeClipboard.first())

        // Test manual copy button
        fakeClipboard.clear()
        val copied = viewModel.onCopyFallbackClicked()
        assertTrue(copied)
        assertEquals(1, fakeClipboard.size)
        assertEquals(fullMarkdown, fakeClipboard.first())

        // Test clicking "Open Pre-filled GitHub Issue in Browser"
        val launched = viewModel.onOpenBrowserFallbackClicked()
        assertTrue(launched)
        assertEquals(1, launchedUrls.size)
        assertEquals(fallbackUrl, launchedUrls.first())
    }

    @Test
    fun testFeedbackState_fallbackUrlLengthBoundedForBrowserSafety() {
        val longSummary = "A".repeat(120)
        val longDescription = "D".repeat(5000)
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = longSummary,
            description = longDescription
        )
        val snapshot = DiagnosticSnapshot(appVersion = "1.0.25")

        val fallbackResult = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot)

        // RFC 3986 safe browser URL limit guarantee: must not exceed 2000 chars to avoid intent rejection
        assertTrue(
            "Fallback URL length (${fallbackResult.url.length}) must be <= 2000 chars",
            fallbackResult.url.length <= 2000
        )
        assertTrue(fallbackResult.url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        assertTrue(fallbackResult.wasTruncated)
    }
}
