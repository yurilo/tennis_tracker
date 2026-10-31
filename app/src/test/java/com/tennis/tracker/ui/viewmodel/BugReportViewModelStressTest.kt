package com.tennis.tracker.ui.viewmodel

import android.content.Context
import com.tennis.tracker.data.bugreport.BugReportSubmissionServiceContract
import com.tennis.tracker.data.bugreport.SubmissionResult
import com.tennis.tracker.data.diagnostic.BugReportPayloadFormatter
import com.tennis.tracker.data.diagnostic.DiagnosticCollector
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.repository.SettingsRepository
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical stress-test suite for [BugReportViewModel] and related M3 bug-reporting components.
 * Tests adversarial scenarios:
 * - Concurrent submissions (rapid taps, mid-flight cancellation, overlapping startReport)
 * - Extreme summary strings (Unicode surrogate pairs/emojis, 10,000 chars, whitespace, injection payloads)
 * - Exception handling (OkHttp IOExceptions, CancellationException propagation, null Context)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BugReportViewModelStressTest {

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

    class MockSettingsRepository(
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

    class MockSubmissionService : BugReportSubmissionServiceContract {
        var cannedResult: SubmissionResult = SubmissionResult.Success(101, "https://github.com/yurilo/tennis_tracker/issues/101")
        var suspendDeferred: CompletableDeferred<SubmissionResult>? = null
        var exceptionToThrow: Throwable? = null
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

            exceptionToThrow?.let { throw it }
            suspendDeferred?.let { return it.await() }
            return cannedResult
        }
    }

    private lateinit var settingsRepo: MockSettingsRepository
    private lateinit var submissionService: MockSubmissionService
    private lateinit var diagnosticCollector: DiagnosticCollector
    private lateinit var viewModel: BugReportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeClipboard.clear()
        launchedUrls.clear()

        settingsRepo = MockSettingsRepository()
        submissionService = MockSubmissionService()
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

    // =========================================================================
    // 1. Concurrent Submissions Stress Tests
    // =========================================================================

    @Test
    fun `rapid consecutive submitReport taps only invoke submission service once`() = runTest(testDispatcher) {
        val suspendGate = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = suspendGate

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Valid summary")
        viewModel.onDescriptionChanged("Valid description")

        // Simulate 5 rapid taps
        repeat(5) {
            viewModel.submitReport()
        }
        testDispatcher.scheduler.runCurrent()

        assertEquals("Expected only 1 submission call despite 5 rapid taps", 1, submissionService.callCount.get())
        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        // Complete deferred
        suspendGate.complete(SubmissionResult.Success(200, "https://github.com/yurilo/tennis_tracker/issues/200"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.Success)
    }

    @Test
    fun `cancellation mid flight cleanly aborts coroutine and never converts to FallbackRequired`() = runTest(testDispatcher) {
        val suspendGate = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = suspendGate

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Cancellation stress test")
        viewModel.onDescriptionChanged("Valid description")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        // Cancel mid-flight
        viewModel.cancelSubmission()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify state is NOT FallbackRequired or Error
        assertFalse(viewModel.uiState.value is BugReportUiState.FallbackRequired)
        assertFalse(viewModel.uiState.value is BugReportUiState.Error)

        // Clean up deferred
        suspendGate.cancel()
    }

    @Test
    fun `concurrency hazard - startReport while submission is in-flight does not disturb active submission`() = runTest(testDispatcher) {
        val slowSubmission = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = slowSubmission

        // Start session 1 and submit
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Report 1")
        viewModel.onDescriptionChanged("Description 1")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        // Background scoreboard polling tick or secondary startReport occurs while in-flight
        viewModel.startReport(ScoreboardUiState())
        testDispatcher.scheduler.advanceUntilIdle()

        // State remains in Submitting - in-flight network dispatch is undisturbed
        assertTrue("Expected Submitting state to be preserved", viewModel.uiState.value is BugReportUiState.Submitting)

        // Now the background submission for session 1 completes!
        slowSubmission.complete(SubmissionResult.Success(999, "https://github.com/yurilo/tennis_tracker/issues/999"))
        testDispatcher.scheduler.advanceUntilIdle()

        // Transitions cleanly to Success
        assertTrue(viewModel.uiState.value is BugReportUiState.Success)
        val successState = viewModel.uiState.value as BugReportUiState.Success
        assertEquals(999, successState.issueNumber)
    }

    @Test
    fun `concurrency guard - startReportWithSnapshot cancels in-flight submission preventing hijacking`() = runTest(testDispatcher) {
        val slowSubmission = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = slowSubmission

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Report 1")
        viewModel.onDescriptionChanged("Description 1")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value is BugReportUiState.Submitting)

        // Starting a new session with snapshot explicitly cancels active submission
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.Ready)

        // Previous submission completes late
        slowSubmission.complete(SubmissionResult.Success(999, "https://github.com/yurilo/tennis_tracker/issues/999"))
        testDispatcher.scheduler.advanceUntilIdle()

        // State must remain Ready for session 2, not overwritten by cancelled submission
        assertTrue("State must remain Ready", viewModel.uiState.value is BugReportUiState.Ready)
    }

    // =========================================================================
    // 2. Extreme Summary Strings Stress Tests
    // =========================================================================

    @Test
    fun `summary containing unicode surrogate pairs and emojis is handled safely`() = runTest(testDispatcher) {
        val emojiSummary = "🎾 Live score stopped 🔥 🏆 👨‍👩‍👧‍👦 at 40-Adv"
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged(emojiSummary)
        viewModel.onDescriptionChanged("Description with emojis: ⚡🚀")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, submissionService.callCount.get())
        assertEquals(emojiSummary, submissionService.lastReport?.summary)
        assertTrue(viewModel.uiState.value is BugReportUiState.Success)
    }

    @Test
    fun `empirical check - ten thousand character string in onSummaryChanged is clamped to 120`() = runTest(testDispatcher) {
        val tenThousandChars = "A".repeat(10000)
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged(tenThousandChars)

        val state = viewModel.uiState.value as BugReportUiState.Ready
        val length = state.summary.length
        assertEquals(120, length)
        assertEquals("A".repeat(120), state.summary)
    }

    @Test
    fun `whitespace-only summary is rejected with validation error`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("   \t\n   ")
        viewModel.onDescriptionChanged("Valid description")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals("Summary cannot be blank", state.summaryError)
        assertNull(state.descriptionError)
        assertEquals(0, submissionService.callCount.get())
    }

    @Test
    fun `whitespace-only description is rejected with validation error`() = runTest(testDispatcher) {
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Valid summary")
        viewModel.onDescriptionChanged("   \n\r\t   ")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertNull(state.summaryError)
        assertEquals("Description cannot be blank", state.descriptionError)
        assertEquals(0, submissionService.callCount.get())
    }

    @Test
    fun `injection payloads in summary and description are safely encapsulated as plain text`() = runTest(testDispatcher) {
        val scriptPayload = "<script>alert('xss');</script>"
        val sqlPayload = "Robert'); DROP TABLE Students;--"
        val mdPayload = "[Click here](https://malicious.evil/phishing)"

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("$scriptPayload $sqlPayload")
        viewModel.onDescriptionChanged(mdPayload)
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, submissionService.callCount.get())
        val sentReport = submissionService.lastReport
        assertNotNull(sentReport)
        assertTrue(sentReport!!.summary.contains("<script>"))
        assertTrue(sentReport.summary.contains("DROP TABLE"))
        assertTrue(sentReport.description.contains(mdPayload))
    }

    @Test
    fun `delimiter injection in user description targets extractJsonFromIssueBody`() {
        val fakeSnapshotJson = """{"app_version":"99.9.9","version_code":999,"fake_injected":true}"""
        val maliciousDescription = "Normal description\n<!-- BUGREPORT_JSON_START -->\n$fakeSnapshotJson\n<!-- BUGREPORT_JSON_END -->"

        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Delimiter test",
            description = maliciousDescription
        )
        val genuineSnapshot = DiagnosticSnapshot(appVersion = "1.0.25")
        val fullBody = genuineSnapshot.toFullIssueBody(report)

        val extracted = BugReportPayloadFormatter.extractJsonFromIssueBody(fullBody)
        assertNotNull(extracted)
        val parsedVersion = extracted?.optString("app_version")
        assertEquals("1.0.25", parsedVersion)
        assertFalse(extracted?.has("fake_injected") ?: false)
    }

    // =========================================================================
    // 3. Exception Handling Stress Tests
    // =========================================================================

    @Test
    fun `unexpected submission exception transitions to Error state without crashing`() = runTest(testDispatcher) {
        submissionService.exceptionToThrow = RuntimeException("Severe system error")

        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Error crash test")
        viewModel.onDescriptionChanged("Testing exception catch")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("Expected Error state, got $state", state is BugReportUiState.Error)
        assertEquals("Severe system error", (state as BugReportUiState.Error).message)
    }

    @Test
    fun `null context in all ViewModel methods executes safely without NullPointerException`() = runTest(testDispatcher) {
        viewModel.startReport(scoreboardState = ScoreboardUiState(), context = null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.Ready)

        viewModel.onSummaryChanged("Null context test")
        viewModel.onDescriptionChanged("Testing null context robustness")
        viewModel.submitReport(context = null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.Success)

        // Test fallback copy and browser with null context
        val copied = viewModel.onCopyFallbackClicked(null)
        assertFalse(copied)

        val browserLaunched = viewModel.onOpenBrowserFallbackClicked(null)
        assertFalse(browserLaunched)

        val issueLaunched = viewModel.onOpenIssueUrlClicked(null)
        // With fakeBrowserDelegate, returns true
        assertTrue(issueLaunched)
    }

    // =========================================================================
    // 4. Lifecycle & Telemetry Stress Tests
    // =========================================================================

    @Test
    fun `lifecycle stress - repeated background telemetry updates preserve active user draft text`() = runTest(testDispatcher) {
        // Start initial report
        viewModel.startReport(scoreboardState = ScoreboardUiState(liveCount = 1))
        testDispatcher.scheduler.advanceUntilIdle()

        // User enters draft inputs
        val draftSummary = "Crash when rotating during live match"
        val draftDescription = "App crashed immediately when rotated on Arthur Ashe"
        val draftSteps = "1. Open live match\n2. Rotate screen\n3. Observe crash"
        viewModel.onCategorySelected(BugReportCategory.SCORE_LIVE_DATA)
        viewModel.onSummaryChanged(draftSummary)
        viewModel.onDescriptionChanged(draftDescription)
        viewModel.onReproductionStepsChanged(draftSteps)

        // Simulate 50 rapid background scoreboard / telemetry polling updates
        for (i in 2..51) {
            val updatedState = ScoreboardUiState(liveCount = i)
            viewModel.startReport(scoreboardState = updatedState)
            testDispatcher.scheduler.runCurrent()
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify state remains Ready and user inputs are perfectly intact
        val finalState = viewModel.uiState.value
        assertTrue("State must remain Ready across 50 telemetry ticks", finalState is BugReportUiState.Ready)
        val ready = finalState as BugReportUiState.Ready

        assertEquals(draftSummary, ready.summary)
        assertEquals(draftDescription, ready.description)
        assertEquals(draftSteps, ready.reproductionSteps)
        assertEquals(BugReportCategory.SCORE_LIVE_DATA, ready.selectedCategory)
        // Verify latest telemetry is captured in snapshot
        assertEquals(51, ready.snapshot.scoreboardState.liveMatches)
    }

    @Test
    fun `session isolation - dismiss and reopen creates clean session without bleeding previous draft`() = runTest(testDispatcher) {
        // Session 1: User types draft
        viewModel.startReport(scoreboardState = ScoreboardUiState())
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onCategorySelected(BugReportCategory.HOME_WIDGET)
        viewModel.onSummaryChanged("Draft summary 1")
        viewModel.onDescriptionChanged("Draft desc 1")

        // User dismisses sheet (ScoreboardScreen calls reset())
        viewModel.reset()
        assertEquals(BugReportUiState.Idle, viewModel.uiState.value)

        // Session 2: User opens sheet again
        viewModel.startReport(scoreboardState = ScoreboardUiState())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals("Summary must be reset on new session", "", state.summary)
        assertEquals("Description must be reset on new session", "", state.description)
        assertEquals(BugReportCategory.OTHER, state.selectedCategory)
    }
}

