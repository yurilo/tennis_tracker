package com.tennis.tracker.ui.viewmodel

import android.content.Context
import com.tennis.tracker.data.bugreport.BugReportSubmissionServiceContract
import com.tennis.tracker.data.bugreport.SubmissionResult
import com.tennis.tracker.data.diagnostic.DiagnosticCollector
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive unit test suite for [BugReportViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BugReportViewModelTest {

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

    class FakeSettingsRepository(
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

    class FakeSubmissionService : BugReportSubmissionServiceContract {
        var cannedResult: SubmissionResult = SubmissionResult.Success(101, "https://github.com/yurilo/tennis_tracker/issues/101")
        var suspendDeferred: CompletableDeferred<SubmissionResult>? = null
        val callCount = AtomicInteger(0)
        var lastReport: BugReport? = null
        var lastSnapshot: DiagnosticSnapshot? = null
        var lastEndpoint: String? = null
        var lastCustomToken: String? = null
        var lastIsOnline: Boolean = true

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
            lastEndpoint = endpoint
            lastCustomToken = customToken
            lastIsOnline = isOnline

            suspendDeferred?.let { return it.await() }
            return cannedResult
        }
    }

    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var submissionService: FakeSubmissionService
    private lateinit var diagnosticCollector: DiagnosticCollector
    private lateinit var viewModel: BugReportViewModel

    private val sampleMatch = Match(
        id = "match_usopen_01",
        tournamentName = "US Open 2026",
        roundName = "Quarterfinal",
        court = "Arthur Ashe Stadium",
        state = MatchState.LIVE,
        statusDetail = "In Progress - Set 3",
        player1 = Competitor(id = "p1", name = "Carlos Alcaraz", isWinner = false),
        player2 = Competitor(id = "p2", name = "Jannik Sinner", isWinner = false)
    )

    private val sampleScoreboardState = ScoreboardUiState(
        isOffline = false,
        allMatches = listOf(sampleMatch),
        filteredMatches = listOf(sampleMatch),
        liveCount = 1
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeClipboard.clear()
        launchedUrls.clear()

        settingsRepo = FakeSettingsRepository()
        submissionService = FakeSubmissionService()
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
    }

    @Test
    fun `initial state is Idle`() = runTest(testDispatcher) {
        assertEquals(BugReportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `startReport transitions to CollectingDiagnostics then Ready with snapshot`() = runTest(testDispatcher) {
        viewModel.startReport(scoreboardState = sampleScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected Ready state but got $state", state is BugReportUiState.Ready)
        val ready = state as BugReportUiState.Ready
        assertEquals(BugReportCategory.OTHER, ready.selectedCategory)
        assertEquals("", ready.summary)
        assertEquals("", ready.description)
        assertNull(ready.summaryError)
        assertNull(ready.descriptionError)
        assertEquals("1.0.25", ready.snapshot.appVersion)
    }

    @Test
    fun `startReport with targetMatch sets SCORE_LIVE_DATA and matchContext`() = runTest(testDispatcher) {
        viewModel.startReport(scoreboardState = sampleScoreboardState, targetMatch = sampleMatch)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals(BugReportCategory.SCORE_LIVE_DATA, state.selectedCategory)
        assertNotNull(state.snapshot.matchContext)
        assertEquals("match_usopen_01", state.snapshot.matchContext?.matchId)
        assertEquals("US Open 2026", state.snapshot.matchContext?.tournamentName)
    }

    @Test
    fun `onCategorySelected updates category chip`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onCategorySelected(BugReportCategory.WATCH_HIGHLIGHTS)

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals(BugReportCategory.WATCH_HIGHLIGHTS, state.selectedCategory)
    }

    @Test
    fun `submitReport rejects blank summary and description`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals("Summary cannot be blank", state.summaryError)
        assertEquals("Description cannot be blank", state.descriptionError)
        assertEquals(0, submissionService.callCount.get())
    }

    @Test
    fun `submitReport rejects blank description with non-blank summary`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Score mismatch in set 2")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertNull(state.summaryError)
        assertEquals("Description cannot be blank", state.descriptionError)
        assertEquals(0, submissionService.callCount.get())
    }

    @Test
    fun `submitReport rejects blank summary with non-blank description`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onDescriptionChanged("Detailed steps to reproduce...")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals("Summary cannot be blank", state.summaryError)
        assertNull(state.descriptionError)
        assertEquals(0, submissionService.callCount.get())
    }

    @Test
    fun `successful Worker relay submission emits Success`() = runTest(testDispatcher) {
        submissionService.cannedResult = SubmissionResult.Success(
            issueNumber = 77,
            issueUrl = "https://github.com/yurilo/tennis_tracker/issues/77"
        )

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onCategorySelected(BugReportCategory.FILTER_TOUR_MISMATCH)
        viewModel.onSummaryChanged("Filter reset bug")
        viewModel.onDescriptionChanged("Selecting ATP filter doesn't persist")
        viewModel.submitReport(isOnline = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected Success state, got $state", state is BugReportUiState.Success)
        val success = state as BugReportUiState.Success
        assertEquals(77, success.issueNumber)
        assertEquals("https://github.com/yurilo/tennis_tracker/issues/77", success.issueUrl)

        assertEquals(1, submissionService.callCount.get())
        assertNull("Relay submission should not provide a customToken", submissionService.lastCustomToken)
        assertEquals(AppSettings.DEFAULT_BUG_REPORT_ENDPOINT, submissionService.lastEndpoint)
        assertEquals("Filter reset bug", submissionService.lastReport?.summary)
    }

    @Test
    fun `successful direct GitHub API submission with custom token emits Success`() = runTest(testDispatcher) {
        settingsRepo.updateGithubToken("ghp_userCustomPat1234567890")
        submissionService.cannedResult = SubmissionResult.Success(
            issueNumber = 88,
            issueUrl = "https://github.com/yurilo/tennis_tracker/issues/88"
        )

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Direct GitHub Token Test")
        viewModel.onDescriptionChanged("Testing authenticated direct issue creation")
        viewModel.submitReport(isOnline = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Success
        assertEquals(88, state.issueNumber)
        assertEquals("ghp_userCustomPat1234567890", submissionService.lastCustomToken)
    }

    @Test
    fun `offline submission emits FallbackRequired with automatic clipboard copy`() = runTest(testDispatcher) {
        submissionService.cannedResult = SubmissionResult.FallbackRequired(
            fallbackUrl = "https://github.com/yurilo/tennis_tracker/issues/new?title=test",
            fullMarkdownBody = "# Markdown Report Body",
            reason = "Device is offline"
        )

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Offline Report")
        viewModel.onDescriptionChanged("No connection available")
        viewModel.submitReport(isOnline = false)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.FallbackRequired
        assertEquals("Device is offline", state.reason)
        assertTrue(state.copiedToClipboard)
        assertEquals(1, fakeClipboard.size)
        assertEquals("# Markdown Report Body", fakeClipboard.first())
    }

    @Test
    fun `relay unconfigured emits FallbackRequired and manual onCopyFallbackClicked copies`() = runTest(testDispatcher) {
        submissionService.cannedResult = SubmissionResult.FallbackRequired(
            fallbackUrl = "https://github.com/yurilo/tennis_tracker/issues/new?title=fallback",
            fullMarkdownBody = "# Diagnostic Payload 503",
            reason = "Relay token unconfigured (HTTP 503)"
        )

        var allowCopy = false
        val noAutoCopyVm = BugReportViewModel(
            diagnosticCollector = diagnosticCollector,
            submissionService = submissionService,
            settingsRepository = settingsRepo,
            clipboardDelegate = { _, _, text ->
                if (allowCopy) {
                    fakeClipboard.add(text)
                    true
                } else false
            },
            browserLauncherDelegate = fakeBrowserDelegate
        )

        noAutoCopyVm.startReportWithSnapshot(DiagnosticSnapshot())
        noAutoCopyVm.onSummaryChanged("503 Test")
        noAutoCopyVm.onDescriptionChanged("Testing manual clipboard copy")
        noAutoCopyVm.submitReport(context = null, isOnline = true)
        testDispatcher.scheduler.advanceUntilIdle()

        val state1 = noAutoCopyVm.uiState.value as BugReportUiState.FallbackRequired
        assertFalse(state1.copiedToClipboard)
        assertEquals(0, fakeClipboard.size)

        // Manual copy action
        allowCopy = true
        val copied = noAutoCopyVm.onCopyFallbackClicked(null)
        assertTrue(copied)
        val state2 = noAutoCopyVm.uiState.value as BugReportUiState.FallbackRequired
        assertTrue(state2.copiedToClipboard)
        assertEquals(1, fakeClipboard.size)
        assertEquals("# Diagnostic Payload 503", fakeClipboard.first())
    }

    @Test
    fun `onOpenBrowserFallbackClicked launches browser with fallback URL`() = runTest(testDispatcher) {
        submissionService.cannedResult = SubmissionResult.FallbackRequired(
            fallbackUrl = "https://github.com/yurilo/tennis_tracker/issues/new?title=browse",
            fullMarkdownBody = "# Report",
            reason = "Fallback"
        )

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Browser test")
        viewModel.onDescriptionChanged("Testing browser opening")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val launched = viewModel.onOpenBrowserFallbackClicked()
        assertTrue(launched)
        assertEquals(1, launchedUrls.size)
        assertEquals("https://github.com/yurilo/tennis_tracker/issues/new?title=browse", launchedUrls.first())
    }

    @Test
    fun `coroutine cancellation during submission cleans up cleanly`() = runTest(testDispatcher) {
        val suspendGate = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = suspendGate

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Cancellation test")
        viewModel.onDescriptionChanged("Will cancel mid flight")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        viewModel.cancelSubmission()
        testDispatcher.scheduler.advanceUntilIdle()

        // Unblock deferred so test doesn't leak
        suspendGate.cancel()
    }

    @Test
    fun `reset returns state to Idle`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        assertTrue(viewModel.uiState.value is BugReportUiState.Ready)

        viewModel.reset()
        assertEquals(BugReportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `startReport while already in Ready preserves user input and selected category`() = runTest(testDispatcher) {
        // Initial report initialization
        viewModel.startReport(scoreboardState = sampleScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        // User enters draft information
        viewModel.onCategorySelected(BugReportCategory.HOME_WIDGET)
        viewModel.onSummaryChanged("Widget score sync delay")
        viewModel.onDescriptionChanged("Widget shows old score from 15 minutes ago")
        viewModel.onReproductionStepsChanged("1. Follow match\n2. Add widget\n3. Wait")

        val stateBefore = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals("Widget score sync delay", stateBefore.summary)

        // Subsequent startReport triggered (e.g. by background scoreboard update)
        val updatedScoreboardState = sampleScoreboardState.copy(liveCount = 5)
        viewModel.startReport(scoreboardState = updatedScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        val stateAfter = viewModel.uiState.value
        assertTrue("State must remain Ready", stateAfter is BugReportUiState.Ready)
        val readyAfter = stateAfter as BugReportUiState.Ready

        // Verify user inputs are fully preserved
        assertEquals("Widget score sync delay", readyAfter.summary)
        assertEquals("Widget shows old score from 15 minutes ago", readyAfter.description)
        assertEquals("1. Follow match\n2. Add widget\n3. Wait", readyAfter.reproductionSteps)
        assertEquals(BugReportCategory.HOME_WIDGET, readyAfter.selectedCategory)
        // Verify snapshot was refreshed
        assertEquals(5, readyAfter.snapshot.scoreboardState.liveMatches)
    }

    @Test
    fun `onSummaryChanged clamps input to 120 characters`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        val longSummary = "A".repeat(250)
        viewModel.onSummaryChanged(longSummary)

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals(120, state.summary.length)
        assertEquals("A".repeat(120), state.summary)
    }

    @Test
    fun `startReport while in Submitting does not disturb in-flight submission`() = runTest(testDispatcher) {
        val suspendGate = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = suspendGate

        viewModel.startReport(scoreboardState = sampleScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSummaryChanged("Bug during submission")
        viewModel.onDescriptionChanged("Submission in progress")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        // Polling tick or secondary startReport occurs while Submitting
        viewModel.startReport(scoreboardState = sampleScoreboardState.copy(liveCount = 99))
        testDispatcher.scheduler.runCurrent()

        // State must remain Submitting, not reset to Ready or CollectingDiagnostics
        assertTrue("State must remain Submitting", viewModel.uiState.value is BugReportUiState.Submitting)

        // Complete submission
        suspendGate.complete(SubmissionResult.Success(123, "https://github.com/yurilo/tennis_tracker/issues/123"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("State transitions cleanly to Success", viewModel.uiState.value is BugReportUiState.Success)
    }

    @Test
    fun `startReportWithSnapshot cancels active submissionJob preventing state overwrite`() = runTest(testDispatcher) {
        val slowSubmission = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = slowSubmission

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Lagging submission")
        viewModel.onDescriptionChanged("Description")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        // User starts new report session with snapshot
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.Ready)

        // Previous submission completes late
        slowSubmission.complete(SubmissionResult.Success(99, "https://github.com/yurilo/tennis_tracker/issues/99"))
        testDispatcher.scheduler.advanceUntilIdle()

        // State must NOT be overwritten with Success
        assertTrue("State must remain Ready, not overwritten by cancelled submission", viewModel.uiState.value is BugReportUiState.Ready)
    }

    @Test
    fun `reset on sheet dismissal cleans up active jobs and returns state to Idle`() = runTest(testDispatcher) {
        val slowSubmission = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = slowSubmission

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Pending report")
        viewModel.onDescriptionChanged("Testing reset on dismiss")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        // Sheet dismissal calls reset()
        viewModel.reset()
        assertEquals(BugReportUiState.Idle, viewModel.uiState.value)

        // Late response finishes after reset
        slowSubmission.complete(SubmissionResult.Success(456, "https://github.com/yurilo/tennis_tracker/issues/456"))
        testDispatcher.scheduler.advanceUntilIdle()

        // State must remain Idle
        assertEquals(BugReportUiState.Idle, viewModel.uiState.value)
    }
}
