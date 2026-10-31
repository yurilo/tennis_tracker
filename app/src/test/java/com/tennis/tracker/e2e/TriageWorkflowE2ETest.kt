package com.tennis.tracker.e2e

import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReport
import com.tennis.tracker.e2e.harness.BugReportContractHarness.BugReportCategory
import com.tennis.tracker.e2e.harness.BugReportContractHarness.DiagnosticSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.ScoreboardStateSnapshot
import com.tennis.tracker.e2e.harness.BugReportContractHarness.TriageEngineHarness
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-End Test Suite for GitHub Actions Bug Triage Workflow & Correlation (R4).
 *
 * Verifies machine-readable diagnostic parsing, heuristic localization of suspect files,
 * commit/issue correlation, and generation of 5-section markdown triage comments with patches.
 */
class TriageWorkflowE2ETest {

    @Test
    fun testE2E_triageScript_extractsJsonBlockAccurately() {
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 25,
            formFactor = "LAPTOP",
            screenWidthBucket = "EXPANDED",
            scoreboardState = ScoreboardStateSnapshot(
                tourFilter = "GRAND_SLAMS",
                totalMatches = 50,
                filteredMatches = 10
            )
        )
        val report = BugReport(
            category = BugReportCategory.FILTER_TOUR_MISMATCH,
            summary = "Grand Slam matches missing",
            description = "Only 10 of 50 matches shown"
        )
        val issueBody = snapshot.toFullIssueBody(report)

        val extractedJson = TriageEngineHarness.extractDiagnosticJson(issueBody)
        assertNotNull("Diagnostic JSON block must be extracted", extractedJson)
        assertEquals("1.0.25", extractedJson!!.getString("app_version"))
        assertEquals("LAPTOP", extractedJson.getString("form_factor"))
        assertEquals(50, extractedJson.getJSONObject("scoreboard_state").getInt("total_matches"))
    }

    @Test
    fun testE2E_triageScript_handlesMissingJsonBlockGracefully() {
        val manualIssueBody = """
            ## Bug Report: Plain text issue
            User reported an issue manually via GitHub Web without diagnostic bundle.
        """.trimIndent()

        val extracted = TriageEngineHarness.extractDiagnosticJson(manualIssueBody)
        assertNull(extracted)

        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "Plain text issue",
            issueBody = manualIssueBody,
            categorySlug = "other"
        )
        assertNotNull(triage)
        assertTrue(triage.diagnosticSummary.contains("unknown"))
    }

    @Test
    fun testE2E_triageScript_correlatesFilterMismatchToTournamentFiltering() {
        val files = TriageEngineHarness.mapCategoryToSuspectedFiles("filter-tour")
        assertTrue(files.any { it.endsWith("TournamentFiltering.kt") })
        assertTrue(files.any { it.endsWith("TourTierFilter.kt") })
        assertTrue(files.any { it.endsWith("ScoreboardViewModel.kt") })
    }

    @Test
    fun testE2E_triageScript_correlatesScoreDataToScoreboardRepository() {
        val files = TriageEngineHarness.mapCategoryToSuspectedFiles("score-live-data")
        assertTrue(files.any { it.endsWith("ScoreboardRepositoryImpl.kt") })
        assertTrue(files.any { it.endsWith("EspnScoreboardApi.kt") })
    }

    @Test
    fun testE2E_triageScript_generatesFiveSectionMarkdownComment() {
        val report = BugReport(
            category = BugReportCategory.HOME_WIDGET,
            summary = "Widget fails to update live scores",
            description = "Widget displays old score"
        )
        val snapshot = DiagnosticSnapshot()
        val issueBody = snapshot.toFullIssueBody(report)

        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "[In-App Report] Widget fails to update live scores",
            issueBody = issueBody,
            categorySlug = "home-widget"
        )
        val comment = triage.toMarkdownComment()

        assertTrue(comment.contains("## 🤖 Automated Bug Triage & Correlation Report"))
        assertTrue(comment.contains("### 1. Diagnostic Summary"))
        assertTrue(comment.contains("### 2. Suspected Files & Symbols"))
        assertTrue(comment.contains("### 3. Related Commits / Issues"))
        assertTrue(comment.contains("### 4. Root-Cause Analysis"))
        assertTrue(comment.contains("### 5. Proposed Patch / Verification Steps"))
    }

    @Test
    fun testE2E_triageScript_outputsSyntacticallyValidDiffPatch() {
        val triage = TriageEngineHarness.triageIssue(
            issueTitle = "Layout overflow",
            issueBody = DiagnosticSnapshot().toFullIssueBody(
                BugReport(BugReportCategory.UI_LAYOUT, "Layout overflow", "Details")
            ),
            categorySlug = "ui-layout"
        )

        val patch = triage.proposedPatch
        assertTrue("Patch must contain diff block markers", patch.contains("```diff"))
        assertTrue("Patch must contain unified diff file headers", patch.contains("--- a/"))
        assertTrue("Patch must contain unified diff file headers", patch.contains("+++ b/"))
        assertTrue("Patch must close code block", patch.endsWith("```"))
    }
}
