package com.tennis.tracker.e2e

import android.content.Context
import com.tennis.tracker.data.bugreport.BugReportSubmissionServiceContract
import com.tennis.tracker.data.bugreport.GitHubIssueUrlBuilder
import com.tennis.tracker.data.bugreport.SubmissionResult
import com.tennis.tracker.data.diagnostic.DiagnosticBreadcrumbBuffer
import com.tennis.tracker.data.diagnostic.DiagnosticCollector
import com.tennis.tracker.data.diagnostic.PiiScrubber
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.TourType
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticBreadcrumb
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.repository.SettingsRepository
import com.tennis.tracker.ui.viewmodel.BugReportUiState
import com.tennis.tracker.ui.viewmodel.BugReportViewModel
import com.tennis.tracker.ui.viewmodel.ScoreboardUiState
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone 5 Phase 2 — Tier 5 White-Box Adversarial Coverage Hardening Test Suite.
 *
 * Stress-tests the four core bug-reporting components against pathological conditions:
 * 1. Zero-PII sanitization on complex multi-pattern text (composite credentials, boundary tokens, non-PII preservation, ReDoS resistance).
 * 2. Breadcrumb ring buffer concurrency (25-item capacity, FIFO eviction, multithreaded logging, ingest scrubbing).
 * 3. URL length enforcement (<= 2000 chars safe fallback URL, surrogate pair safety, RFC 3986 parameter escaping).
 * 4. ViewModel draft preservation across state re-collection and clean state reset on sheet dismissal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BugReportTier5AdversarialTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()

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
        liveCount = 1
    )

    // Test fakes
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

    class FakeSettingsRepo(
        initial: AppSettings = AppSettings()
    ) : SettingsRepository {
        private val _flow = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = _flow.asStateFlow()
        override fun getSettings(): AppSettings = _flow.value
        override suspend fun updateAutoRefreshInterval(interval: com.tennis.tracker.domain.model.AutoRefreshInterval) {}
        override suspend fun updateMatchRetention(policy: com.tennis.tracker.domain.model.MatchRetentionPolicy) {}
        override suspend fun updateWidgetSyncMode(mode: com.tennis.tracker.domain.model.WidgetSyncMode) {}
        override suspend fun updateDefaultTournament(tier: com.tennis.tracker.domain.model.StartupTournamentTier) {}
        override suspend fun updateTelemetryEnabled(enabled: Boolean) {}
        override suspend fun updateTelemetryEndpoint(endpoint: String) {}
        override suspend fun updateBugReportEndpoint(endpoint: String) {}
        override suspend fun updateGithubToken(token: String?) {}
        override suspend fun resetToDefaults() {}
    }

    class FakeSubmissionService : BugReportSubmissionServiceContract {
        val callCount = AtomicInteger(0)
        var cannedResult: SubmissionResult = SubmissionResult.Success(
            issueNumber = 100,
            issueUrl = "https://github.com/yurilo/tennis_tracker/issues/100"
        )
        var suspendDeferred: CompletableDeferred<SubmissionResult>? = null

        override suspend fun submitReport(
            report: BugReport,
            snapshot: DiagnosticSnapshot,
            endpoint: String?,
            customToken: String?,
            isOnline: Boolean
        ): SubmissionResult {
            callCount.incrementAndGet()
            suspendDeferred?.let { return it.await() }
            return cannedResult
        }
    }

    private lateinit var settingsRepo: FakeSettingsRepo
    private lateinit var submissionService: FakeSubmissionService
    private lateinit var diagnosticCollector: DiagnosticCollector
    private lateinit var viewModel: BugReportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()
        fakeClipboard.clear()
        launchedUrls.clear()

        settingsRepo = FakeSettingsRepo()
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
        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()
    }

    // =========================================================================
    // 1. ZERO-PII SANITIZATION ADVERSARIAL CHALLENGES
    // =========================================================================

    @Test
    fun testPiiScrubber_complexMultiPatternComposition_allPiiScrubbed() {
        val compositeHostileLog = """
            User bug report from reporter.dev+testing@mail.us-open-tennis.org on client 192.168.10.45:
            IPv6 client: 2001:0db8:85a3:0000:0000:8a2e:0370:7334
            Tokens:
              GitHub Classic: ghp_111122223333444455556666777788889999
              GitHub FineGrained: github_pat_11AAAA22BBBB33CCCC44DDDD55EEEE66FFFF77GGGG88HHHH99IIII
              Bearer Token: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.t-ID
            Endpoints & Parameters:
              Remote API: https://svc_bot:superSecretPass456!@stats.tennis.com/api/v1
              Query Token: https://api.tennis.com/v2/live?access_token=abcdef0123456789&match=123
              JSON Payload: {"secret": "ultra_confidential_key_1234", "password": "MySuperSecretPassword999!"}
            Local Filesystem:
              Dumped state to /data/user/0/com.tennis.tracker/databases/tennis.db and /storage/emulated/0/Download/dump.log
        """.trimIndent()

        val scrubbed = PiiScrubber.scrub(compositeHostileLog)

        // Verify presence of all expected redaction tokens
        assertTrue("Must contain [REDACTED_GH_TOKEN]", scrubbed.contains("[REDACTED_GH_TOKEN]"))
        assertTrue("Must contain Bearer [REDACTED_TOKEN]", scrubbed.contains("Bearer [REDACTED_TOKEN]"))
        assertTrue("Must contain [REDACTED_EMAIL]", scrubbed.contains("[REDACTED_EMAIL]"))
        assertTrue("Must contain [REDACTED_IP]", scrubbed.contains("[REDACTED_IP]"))
        assertTrue("Must contain [REDACTED_PASSWORD]", scrubbed.contains("[REDACTED_PASSWORD]"))
        assertTrue("Must contain [REDACTED_SECRET]", scrubbed.contains("[REDACTED_SECRET]"))
        assertTrue("Must contain [REDACTED_PATH]", scrubbed.contains("[REDACTED_PATH]"))

        // Verify absolute absence of all raw confidential secrets
        assertFalse("Raw email leaked", scrubbed.contains("reporter.dev+testing@mail.us-open-tennis.org"))
        assertFalse("Raw IPv4 leaked", scrubbed.contains("192.168.10.45"))
        assertFalse("Raw IPv6 leaked", scrubbed.contains("2001:0db8"))
        assertFalse("Raw Classic PAT leaked", scrubbed.contains("ghp_111122223333444455556666777788889999"))
        assertFalse("Raw Fine-Grained PAT leaked", scrubbed.contains("github_pat_11AAAA22BBBB33CCCC44DDDD55EEEE66FFFF77GGGG88HHHH99IIII"))
        assertFalse("Raw Bearer JWT leaked", scrubbed.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse("Raw URL password leaked", scrubbed.contains("superSecretPass456!"))
        assertFalse("Raw query param secret leaked", scrubbed.contains("abcdef0123456789"))
        assertFalse("Raw JSON secret leaked", scrubbed.contains("ultra_confidential_key_1234"))
        assertFalse("Raw JSON password leaked", scrubbed.contains("MySuperSecretPassword999!"))
        assertFalse("Raw Android app data path leaked", scrubbed.contains("/data/user/0/com.tennis.tracker/databases/tennis.db"))
        assertFalse("Raw Android storage path leaked", scrubbed.contains("/storage/emulated/0/Download/dump.log"))
    }

    @Test
    fun testPiiScrubber_boundaryTokenLengths() {
        // Exact 20-char PAT
        val pat20 = "ghp_" + "0".repeat(20)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(pat20))

        // 19-char (below threshold) -> not a standard GH PAT
        val pat19 = "ghp_" + "0".repeat(19)
        assertFalse(PiiScrubber.scrub(pat19).contains("[REDACTED_GH_TOKEN]"))

        // Exact 80-char PAT
        val pat80 = "ghp_" + "1".repeat(80)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(pat80))

        // Fine-grained PAT min 30 chars
        val fg30 = "github_pat_" + "x".repeat(30)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(fg30))

        // Fine-grained PAT max 120 chars
        val fg120 = "github_pat_" + "y".repeat(120)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(fg120))
    }

    @Test
    fun testPiiScrubber_preservesNonPiiTennisDomainData() {
        val nonPiiText = """
            Tennis Tracker v1.0.25 (versionCode 26) on PHONE (COMPACT).
            Match: Carlos Alcaraz 🇪🇸 vs Jannik Sinner 🇮🇹 at US Open 2026.
            Score: 6-4, 7-6(5), 4-6, 40-15, 30-30, Deuce, Ad-In, Tiebreak 7-5.
            Category: filter-tour / score-live-data / watch-highlights / home-widget / ui-layout.
            Event timestamp: 2026-10-31T18:45:00.123Z.
        """.trimIndent()

        val scrubbed = PiiScrubber.scrub(nonPiiText)

        // Non-PII domain strings must NOT be modified
        assertTrue("App version must be preserved", scrubbed.contains("v1.0.25"))
        assertTrue("Player 1 with flag must be preserved", scrubbed.contains("Carlos Alcaraz 🇪🇸"))
        assertTrue("Player 2 with flag must be preserved", scrubbed.contains("Jannik Sinner 🇮🇹"))
        assertTrue("Tournament name must be preserved", scrubbed.contains("US Open 2026"))
        assertTrue("Tennis set scores must be preserved", scrubbed.contains("6-4, 7-6(5), 4-6, 40-15, 30-30, Deuce, Ad-In, Tiebreak 7-5"))
        assertTrue("Category slugs must be preserved", scrubbed.contains("score-live-data"))
        assertTrue("ISO timestamp must be preserved", scrubbed.contains("2026-10-31T18:45:00.123Z"))
    }

    @Test
    fun testPiiScrubber_pathologicalAndReDosStress() {
        // Construct 50,000-character pathological input with repeated colons, slashes, and emails
        val pathological = buildString {
            append("https://user:")
            append("a".repeat(10000))
            append("@host.com/path/")
            append("b/".repeat(5000))
            append("?token=")
            append("c".repeat(5000))
        }

        val startTime = System.currentTimeMillis()
        val scrubbed = PiiScrubber.scrub(pathological)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Execution must complete in < 1500ms (ReDoS resistant)", elapsed < 1500)
        assertNotNull(scrubbed)
        assertFalse("10,000-char password must be redacted", scrubbed.contains("a".repeat(100)))
        assertTrue("Password must be replaced with [REDACTED_PASSWORD]", scrubbed.contains("[REDACTED_PASSWORD]"))
        assertFalse("5,000-char query token must be redacted", scrubbed.contains("c".repeat(100)))
        assertTrue("Query token must be replaced with [REDACTED_SECRET]", scrubbed.contains("[REDACTED_SECRET]"))
        assertTrue("Host and path structure must be preserved", scrubbed.contains("@host.com/path/"))
    }

    // =========================================================================
    // 2. BREADCRUMB RING BUFFER CONCURRENCY & FIFO EVICTION
    // =========================================================================

    @Test
    fun testBreadcrumbBuffer_highConcurrencyMultithreadedLoggingAndReading() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        val threadCount = 32
        val itemsPerThread = 100
        val totalExpectedLogs = threadCount * itemsPerThread
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val readerThreads = 4
        val readerDoneLatch = CountDownLatch(readerThreads)
        val running = AtomicBoolean(true)
        val errorDetected = AtomicBoolean(false)

        val executor = Executors.newFixedThreadPool(threadCount + readerThreads + 1)

        // 1. Launch 32 writer threads
        for (t in 0 until threadCount) {
            val threadId = t
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until itemsPerThread) {
                        buffer.log(
                            tag = "Thread-$threadId",
                            message = "Event-$i from thread $threadId",
                            metadata = mapOf("thread" to "$threadId", "seq" to "$i")
                        )
                    }
                } catch (t: Throwable) {
                    errorDetected.set(true)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 2. Launch 4 concurrent reader threads continuously inspecting the buffer
        for (r in 0 until readerThreads) {
            executor.submit {
                try {
                    startLatch.await()
                    while (running.get()) {
                        val crumbs = buffer.getRecentBreadcrumbs()
                        assertTrue("Recent breadcrumbs size must never exceed 25", crumbs.size <= 25)
                        val json = buffer.toJson()
                        assertTrue("JSONArray length must never exceed 25", json.length() <= 25)
                    }
                } catch (t: Throwable) {
                    errorDetected.set(true)
                } finally {
                    readerDoneLatch.countDown()
                }
            }
        }

        // 3. Launch 1 maintenance thread periodically clearing
        executor.submit {
            startLatch.await()
            for (c in 0 until 5) {
                Thread.sleep(5)
                buffer.clear()
            }
        }

        // Start all threads simultaneously
        startLatch.countDown()
        val writersFinished = doneLatch.await(10, TimeUnit.SECONDS)
        running.set(false)
        val readersFinished = readerDoneLatch.await(5, TimeUnit.SECONDS)

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertTrue("Writers must complete within timeout", writersFinished)
        assertTrue("Readers must complete within timeout", readersFinished)
        assertFalse("No concurrency exceptions permitted", errorDetected.get())
        assertTrue("Final buffer size must be <= 25", buffer.size <= 25)
    }

    @Test
    fun testBreadcrumbBuffer_strictFifoEvictionOrder() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)

        // Insert 50 sequential items
        for (i in 0 until 50) {
            buffer.log(tag = "TAG", message = "event-%02d".format(i))
        }

        assertEquals("Capacity bounded at 25", 25, buffer.size)

        val crumbs = buffer.getRecentBreadcrumbs()
        assertEquals(25, crumbs.size)

        // Events 00 through 24 must be evicted; events 25 through 49 remain in exact order
        for (idx in 0 until 25) {
            val expectedEventIndex = 25 + idx
            val expectedMessage = "event-%02d".format(expectedEventIndex)
            assertEquals("Chronological order mismatch at index $idx", expectedMessage, crumbs[idx].message)
        }
    }

    @Test
    fun testBreadcrumbBuffer_ingestSanitizationIntegrity() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)

        buffer.log(
            tag = "admin@tennis.org",
            message = "Authorization: Bearer mySecretToken123456789 on /data/user/0/app",
            metadata = mapOf("client_ip" to "192.168.1.100")
        )

        val recent = buffer.getRecentBreadcrumbs()
        assertEquals(1, recent.size)
        val crumb = recent.first()

        assertEquals("[REDACTED_EMAIL]", crumb.tag)
        assertTrue(crumb.message.contains("Bearer [REDACTED_TOKEN]"))
        assertTrue(crumb.message.contains("[REDACTED_PATH]"))
        assertEquals("[REDACTED_IP]", crumb.metadata["client_ip"])

        val json = buffer.toJson().getJSONObject(0)
        assertEquals("[REDACTED_EMAIL]", json.getString("tag"))
    }

    // =========================================================================
    // 3. SAFE FALLBACK URL LENGTH ENFORCEMENT & RFC 3986 ESCAPING
    // =========================================================================

    @Test
    fun testGitHubIssueUrlBuilder_hardLengthConstraintEnforcement_acrossBudgets() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        for (i in 0 until 25) {
            buffer.log("Sync", "Repository sync item $i processed successfully")
        }

        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 26,
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED",
            breadcrumbs = buffer.getRecentBreadcrumbs()
        )

        // Massive multi-byte user bug report
        val longSummary = "Alcaraz vs Sinner tiebreak freeze 🎾🔥🏆 " + "X".repeat(70)
        val massiveDescription = buildString {
            append("Encountered frozen UI during semifinal tiebreak.\n\n")
            append("Multilingual text: Ελληνικά, Русский, 中文, 日本語, العربية.\n\n")
            append("Padded lines:\n")
            for (line in 1..200) {
                append("Detailed diagnostic trace line #$line: values and memory state.\n")
            }
        }

        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = longSummary.take(120),
            description = massiveDescription,
            reproductionSteps = "Step 1\nStep 2\n".repeat(50)
        )

        // 1. Default safe budget: MAX_SAFE_URL_LENGTH (2000 chars)
        val defaultFallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot)
        assertTrue("URL must strictly be <= 2000 chars (was ${defaultFallback.url.length})", defaultFallback.url.length <= 2000)
        assertTrue("Was truncated flag must be true", defaultFallback.wasTruncated)
        assertTrue("Full report body must be preserved for clipboard", defaultFallback.fullMarkdownBody.length > 2000)

        // 2. Tightly constrained budgets: 1500, 1000, 500, 250, 100, 60
        val budgets = listOf(1500, 1000, 500, 250, 100, 60)
        for (budget in budgets) {
            val clampedFallback = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot, maxUrlLength = budget)
            assertTrue(
                "URL must strictly satisfy <= $budget chars (actual: ${clampedFallback.url.length})",
                clampedFallback.url.length <= budget
            )
        }
    }

    @Test
    fun testGitHubIssueUrlBuilder_surrogatePairPreservation() {
        val emojis = "Carlos Alcaraz 🇪🇸 🎾 🏆 🔥 ⚡ "
        val repeatedEmojis = emojis.repeat(50)

        for (targetBudget in listOf(5, 10, 15, 23, 47, 89, 120, 250)) {
            val clamped = GitHubIssueUrlBuilder.clampToEncodedBudget(repeatedEmojis, targetBudget)
            val encoded = GitHubIssueUrlBuilder.encodeParam(clamped)
            assertTrue("Encoded length must be <= $targetBudget", encoded.length <= targetBudget)

            // Verify no dangling high surrogate at boundary
            if (clamped.isNotEmpty()) {
                val lastChar = clamped.last()
                assertFalse("Must never end on an orphaned high surrogate", Character.isHighSurrogate(lastChar))
            }

            // Verify clean UTF-8 roundtrip without replacement character (\uFFFD)
            val decoded = URLDecoder.decode(encoded.replace("%20", "+"), StandardCharsets.UTF_8.toString())
            assertFalse("Decoded text must not contain Unicode replacement char \\uFFFD", decoded.contains("\uFFFD"))
        }
    }

    @Test
    fun testGitHubIssueUrlBuilder_rfc3986PercentEncodingIntegrity() {
        val title = "Issue #42: Score + Tiebreak [Alcaraz vs Sinner] & Highlights 100%!"
        val body = "Line 1 with spaces\nLine 2 with /slash, &ampersand, =equals, ?question, #hash"
        val labels = listOf("bug", "in-app-report", "score-live-data")

        val generatedUrl = GitHubIssueUrlBuilder.buildNewIssueUrl(title, body, labels)

        // Space must be encoded as %20 (RFC 3986), NOT as '+'
        assertFalse("Space must not be '+'", generatedUrl.contains("title=Issue+"))
        assertTrue("Space must be %20", generatedUrl.contains("Issue%20%2342"))
        assertTrue("Plus sign must be %2B", generatedUrl.contains("%2B%20Tiebreak"))
        assertTrue("Ampersand in body must be %26", generatedUrl.contains("%26ampersand"))

        // Roundtrip decoding verification
        val titleQueryParam = generatedUrl.substringAfter("title=").substringBefore("&body=")
        val decodedTitle = URLDecoder.decode(titleQueryParam.replace("%20", "+"), StandardCharsets.UTF_8.toString())
        assertEquals("Title must roundtrip cleanly through URL encoding", title, decodedTitle)
    }

    // =========================================================================
    // 4. VIEWMODEL DRAFT PRESERVATION & STATE RESET ON SHEET DISMISSAL
    // =========================================================================

    @Test
    fun testViewModel_draftPreservedAcrossScoreboardRefreshAndReCollection() = runTest(testDispatcher) {
        // User opens bug report sheet
        viewModel.startReport(scoreboardState = sampleScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.Ready)

        // User enters draft information
        viewModel.onCategorySelected(BugReportCategory.WATCH_HIGHLIGHTS)
        viewModel.onSummaryChanged("ESPN replay stream broken")
        viewModel.onDescriptionChanged("Playback stalls after opening link")
        viewModel.onReproductionStepsChanged("1. Tap watch\n2. Open ESPN")

        val stateBefore = viewModel.uiState.value as BugReportUiState.Ready
        assertEquals("ESPN replay stream broken", stateBefore.summary)

        // Live scoreboard tick occurs in background while sheet is open
        val updatedScoreboard = sampleScoreboardState.copy(liveCount = 7)
        viewModel.startReport(scoreboardState = updatedScoreboard)
        testDispatcher.scheduler.advanceUntilIdle()

        val stateAfter = viewModel.uiState.value
        assertTrue("State must remain Ready without losing draft", stateAfter is BugReportUiState.Ready)
        val readyAfter = stateAfter as BugReportUiState.Ready

        // Verify draft fields are perfectly preserved
        assertEquals("ESPN replay stream broken", readyAfter.summary)
        assertEquals("Playback stalls after opening link", readyAfter.description)
        assertEquals("1. Tap watch\n2. Open ESPN", readyAfter.reproductionSteps)
        assertEquals(BugReportCategory.WATCH_HIGHLIGHTS, readyAfter.selectedCategory)

        // Verify snapshot was updated with new scoreboard state
        assertEquals(7, readyAfter.snapshot.scoreboardState.liveMatches)
    }

    @Test
    fun testViewModel_sheetDismissalResetFlow_completeLifecycle() = runTest(testDispatcher) {
        // User opens sheet and enters draft
        viewModel.startReport(scoreboardState = sampleScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onCategorySelected(BugReportCategory.HOME_WIDGET)
        viewModel.onSummaryChanged("Draft summary to discard")
        viewModel.onDescriptionChanged("Draft description to discard")

        assertTrue(viewModel.uiState.value is BugReportUiState.Ready)

        // User dismisses sheet (Sheet onDismiss invokes viewModel.reset())
        viewModel.reset()

        assertEquals("State must be reset to Idle on dismissal", BugReportUiState.Idle, viewModel.uiState.value)

        // User re-opens bug report sheet later
        viewModel.startReport(scoreboardState = sampleScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        val newState = viewModel.uiState.value as BugReportUiState.Ready
        // Verify fresh clean session (previous draft discarded after explicit dismissal reset)
        assertEquals("", newState.summary)
        assertEquals("", newState.description)
        assertEquals("", newState.reproductionSteps)
    }

    @Test
    fun testViewModel_inFlightSubmissionGuardAndCancelOnReset() = runTest(testDispatcher) {
        val slowNetwork = CompletableDeferred<SubmissionResult>()
        submissionService.suspendDeferred = slowNetwork

        viewModel.startReport(scoreboardState = sampleScoreboardState)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onSummaryChanged("Lagging submission")
        viewModel.onDescriptionChanged("Testing submission guard")
        viewModel.submitReport()
        testDispatcher.scheduler.runCurrent()

        assertTrue("State must transition to Submitting", viewModel.uiState.value is BugReportUiState.Submitting)

        // Background update attempt while Submitting must be rejected/guarded
        viewModel.startReport(scoreboardState = sampleScoreboardState.copy(liveCount = 99))
        testDispatcher.scheduler.runCurrent()

        assertTrue("State must remain Submitting, not disturbed by startReport", viewModel.uiState.value is BugReportUiState.Submitting)

        // Sheet dismissal while Submitting cancels in-flight job and resets to Idle
        viewModel.reset()
        assertEquals(BugReportUiState.Idle, viewModel.uiState.value)

        // Late network completion after reset must not clobber Idle state
        slowNetwork.complete(SubmissionResult.Success(999, "https://github.com/yurilo/tennis_tracker/issues/999"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("State must remain Idle even if network completes late", BugReportUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun testViewModel_submissionSuccessAndFallbackBrowserLaunch() = runTest(testDispatcher) {
        // 1. Success scenario
        submissionService.cannedResult = SubmissionResult.Success(
            issueNumber = 77,
            issueUrl = "https://github.com/yurilo/tennis_tracker/issues/77"
        )
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Success report")
        viewModel.onDescriptionChanged("Valid description")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.Success)
        val opened = viewModel.onOpenIssueUrlClicked()
        assertTrue(opened)
        assertEquals("https://github.com/yurilo/tennis_tracker/issues/77", launchedUrls.last())

        // 2. Fallback scenario
        submissionService.cannedResult = SubmissionResult.FallbackRequired(
            fallbackUrl = "https://github.com/yurilo/tennis_tracker/issues/new?title=fallback",
            fullMarkdownBody = "# Full Diagnostic Bundle",
            reason = "Device is offline"
        )
        viewModel.startReportWithSnapshot(DiagnosticSnapshot())
        viewModel.onSummaryChanged("Fallback report")
        viewModel.onDescriptionChanged("Valid description")
        viewModel.submitReport()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BugReportUiState.FallbackRequired)
        val fallbackState = viewModel.uiState.value as BugReportUiState.FallbackRequired
        assertTrue(fallbackState.copiedToClipboard)
        assertEquals("# Full Diagnostic Bundle", fakeClipboard.last())

        val openedFallback = viewModel.onOpenBrowserFallbackClicked()
        assertTrue(openedFallback)
        assertEquals("https://github.com/yurilo/tennis_tracker/issues/new?title=fallback", launchedUrls.last())
    }
}
