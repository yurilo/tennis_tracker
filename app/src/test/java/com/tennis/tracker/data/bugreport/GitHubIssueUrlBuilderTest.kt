package com.tennis.tracker.data.bugreport

import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIssueUrlBuilderTest {

    @Test
    fun testUrlEncoding_spacesAndSpecialCharactersProperlyPercentEncoded() {
        val title = "Crash on Roland-Garros & Wimbledon: 100% bug!"
        val body = "Line 1\nLine 2 with spaces & \"quotes\" + plus"
        val labels = listOf("bug", "in-app-report", "filter-tour")

        val url = GitHubIssueUrlBuilder.buildNewIssueUrl(title, body, labels)

        assertTrue(url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new?"))
        assertTrue("Spaces must be encoded as %20 rather than +", url.contains("%20"))
        assertFalse("Spaces must not be encoded as unescaped + signs in query", url.contains("title=Crash+on"))
        assertTrue(url.contains("labels=bug%2Cin-app-report%2Cfilter-tour"))
    }

    @Test
    fun testLabelsFormatting_formatsCommaSeparatedList() {
        val labels = listOf("bug", "in-app-report", "score-live-data")
        val url = GitHubIssueUrlBuilder.buildNewIssueUrl("Title", "Body", labels)

        assertTrue(url.contains("labels=bug%2Cin-app-report%2Cscore-live-data"))
    }

    @Test
    fun testLengthProtection_shortBody_doesNotTruncate() {
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Minor typo",
            description = "Settings screen says Teck instead of Tech"
        )
        // Minimal snapshot that fits well within 5000 chars if not truncated, but let's test with a high maxUrlLength
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 26,
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT"
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot, maxUrlLength = 8000)

        assertFalse("Short URL should not be marked as truncated", result.wasTruncated)
        assertTrue(result.url.length <= 8000)
        assertEquals(snapshot.toFullIssueBody(report), result.fullMarkdownBody)
        assertTrue(result.clipboardInstructions.contains("pre-filled"))
    }

    @Test
    fun testLengthProtection_excessiveBody_strictlyEnforcesUnder2000Chars() {
        val largeDescription = "A".repeat(1500)
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Live score frozen",
            description = largeDescription,
            reproductionSteps = "Step 1\nStep 2\nStep 3"
        )
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 26,
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT"
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot, maxUrlLength = 2000)

        assertTrue("Excessive report must be marked as truncated", result.wasTruncated)
        assertTrue("URL must not exceed 2000 chars: length=${result.url.length}", result.url.length <= 2000)
        assertTrue("URL must contain clipboard paste notice", result.url.contains("clipboard") || result.url.contains("Notice"))
        assertTrue("Full markdown body must preserve untruncated content", result.fullMarkdownBody.contains(largeDescription))
        assertTrue("Full markdown body must contain machine-readable JSON block", result.fullMarkdownBody.contains("<!-- BUGREPORT_JSON_START -->"))
        assertTrue(result.clipboardInstructions.contains("clipboard"))
    }

    @Test
    fun testLengthProtection_extremeUserDescription_hardClampedUnder2000Chars() {
        val extremeDescription = "X".repeat(5000)
        val report = BugReport(
            category = BugReportCategory.HOME_WIDGET,
            summary = "Widget crash",
            description = extremeDescription
        )
        val snapshot = DiagnosticSnapshot()

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot, maxUrlLength = 2000)

        assertTrue(result.wasTruncated)
        assertTrue("Even extreme payload must strictly satisfy <= 2000 chars: length=${result.url.length}", result.url.length <= 2000)
        assertTrue(result.fullMarkdownBody.contains(extremeDescription))
    }

    @Test
    fun testCustomRepoCoordinates_formatsExpectedUrl() {
        val customBase = "https://github.com/custom_org/custom_repo/issues/new"
        val url = GitHubIssueUrlBuilder.buildNewIssueUrl("Bug", "Body", listOf("bug"), customBase)

        assertTrue(url.startsWith(customBase))
    }

    @Test
    fun testFallbackUrl_piiTokensInSummary_strictlyRedactedInQueryUrl() {
        val secretToken = "ghp_1234567890abcdef1234567890abcdef"
        val userEmail = "developer@tennis-tracker.app"
        val report = BugReport(
            category = BugReportCategory.OTHER,
            summary = "Crash with token $secretToken and email $userEmail",
            description = "Token details: $secretToken"
        )
        val snapshot = DiagnosticSnapshot(
            appVersion = "1.0.25",
            versionCode = 26,
            formFactor = "PHONE",
            screenWidthBucket = "COMPACT"
        )

        val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot)

        // URL query parameter 'title' must NOT contain secretToken or userEmail
        assertFalse("URL must not contain raw GitHub token", result.url.contains(secretToken))
        assertFalse("URL must not contain raw user email", result.url.contains(userEmail))
        assertFalse("URL must not contain ghp_ prefix", result.url.contains("ghp_"))
        assertTrue(
            "URL must contain redacted token indicator",
            result.url.contains("[REDACTED_GH_TOKEN]") || result.url.contains("%5BREDACTED_GH_TOKEN%5D")
        )
        assertTrue(
            "URL must contain redacted email indicator",
            result.url.contains("[REDACTED_EMAIL]") || result.url.contains("%5BREDACTED_EMAIL%5D")
        )
        assertFalse("Full markdown body must not contain raw token", result.fullMarkdownBody.contains(secretToken))
    }

    @Test
    fun testFallbackUrl_customSmallMaxUrlLength_strictlyEnforced() {
        val report = BugReport(
            category = BugReportCategory.SCORE_LIVE_DATA,
            summary = "Scores frozen in US Open final " + "MatchDetails ".repeat(50),
            description = "Detailed log: " + "ErrorData ".repeat(100)
        )
        val snapshot = DiagnosticSnapshot()

        for (maxLen in listOf(150, 200, 250, 500, 750)) {
            val result = GitHubIssueUrlBuilder.buildSafeFallbackUrl(report, snapshot, maxUrlLength = maxLen)
            assertTrue(
                "URL length (${result.url.length}) must strictly satisfy <= $maxLen",
                result.url.length <= maxLen
            )
            assertTrue("Result must be marked truncated for small maxUrlLength", result.wasTruncated)
            assertTrue(result.url.startsWith("https://github.com/yurilo/tennis_tracker/issues/new"))
        }
    }

    @Test
    fun testClampToEncodedBudget_handlesSurrogatePairsWithoutSplitting() {
        val surrogateText = "[Other] " + "\uD83D\uDE00".repeat(20)
        val clamped = GitHubIssueUrlBuilder.clampToEncodedBudget(surrogateText, 40)
        assertTrue(GitHubIssueUrlBuilder.encodeParam(clamped).length <= 40)
        // Verify no unpaired surrogates
        for (i in clamped.indices) {
            if (Character.isHighSurrogate(clamped[i])) {
                assertTrue("High surrogate must be followed by low surrogate", i + 1 < clamped.length && Character.isLowSurrogate(clamped[i + 1]))
            }
        }
    }
}
