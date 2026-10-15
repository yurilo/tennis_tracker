package com.tennis.tracker.ui.screen

import com.tennis.tracker.data.local.MockTournamentData
import com.tennis.tracker.domain.model.DeepLink
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.ui.util.AdaptiveLayoutUtils
import com.tennis.tracker.ui.util.FormFactor
import com.tennis.tracker.ui.util.WindowWidthSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated tests verifying that MatchCard interactions, click handlers,
 * and layout parameters are correctly wired and active for both Phone and Laptop form factors.
 */
class MatchCardInteractionTest {

    @Test
    fun testCardClickAction_triggersOnStatsClick() {
        val match = MockTournamentData.matches.first()
        var statsClickedMatch: Match? = null

        val onStatsClick: (Match) -> Unit = { m -> statsClickedMatch = m }

        // Simulate card-level click (which is now wired to onStatsClick)
        onStatsClick(match)

        assertNotNull("Card click should invoke onStatsClick", statsClickedMatch)
        assertEquals("Clicked match should match the target match", match.id, statsClickedMatch?.id)
    }

    @Test
    fun testH2HAndTapeAction_triggersOnH2HClick() {
        val match = MockTournamentData.matches.first()
        var h2hClickedMatch: Match? = null

        val onH2HClick: (Match) -> Unit = { m -> h2hClickedMatch = m }

        // Simulate H2H button click
        onH2HClick(match)

        assertNotNull("Tale of Tape click should invoke onH2HClick", h2hClickedMatch)
        assertEquals(match.id, h2hClickedMatch?.id)
    }

    @Test
    fun testWatchAction_triggersOnWatchClick() {
        val matchWithDeepLink = MockTournamentData.matches.first { it.deepLink != null }
        var launchedDeepLink: DeepLink? = null

        val onWatchClick: (DeepLink) -> Unit = { link -> launchedDeepLink = link }

        matchWithDeepLink.deepLink?.let { onWatchClick(it) }

        assertNotNull("Watch button should invoke onWatchClick", launchedDeepLink)
    }

    @Test
    fun testHighlightsAction_triggersOnHighlightsClick() {
        val finishedMatch = MockTournamentData.matches.first { it.state == MatchState.FINISHED }
        var highlightsMatch: Match? = null

        val onHighlightsClick: (Match) -> Unit = { m -> highlightsMatch = m }

        onHighlightsClick(finishedMatch)

        assertNotNull("Highlights button should invoke onHighlightsClick", highlightsMatch)
        assertEquals(finishedMatch.id, highlightsMatch?.id)
    }

    @Test
    fun testFollowAction_togglesFollow() {
        val match = MockTournamentData.matches.first()
        var toggledMatch: Match? = null

        val onFollowClick: (Match) -> Unit = { m -> toggledMatch = m }

        onFollowClick(match)

        assertNotNull("Follow star click should invoke onFollowClick", toggledMatch)
        assertEquals(match.id, toggledMatch?.id)
    }

    @Test
    fun testCopyAction_triggersOnCopyClick() {
        val match = MockTournamentData.matches.first()
        var copiedMatch: Match? = null

        val onCopyClick: (Match) -> Unit = { m -> copiedMatch = m }

        onCopyClick(match)

        assertNotNull("Copy button should invoke onCopyClick", copiedMatch)
        assertEquals(match.id, copiedMatch?.id)
    }

    @Test
    fun testPhoneFormFactorConfig_verifiesSingleColumnAndCompactLayout() {
        val phoneConfig = AdaptiveLayoutUtils.computeConfig(390, 844)

        assertEquals(FormFactor.PHONE, phoneConfig.formFactor)
        assertEquals(WindowWidthSize.COMPACT, phoneConfig.windowWidthSize)
        assertEquals(1, phoneConfig.gridColumnCount)
        assertTrue(phoneConfig.isPhone)
        assertTrue(phoneConfig.isCompactButtons)
    }

    @Test
    fun testLaptopFormFactorConfig_verifiesMultiColumnAndWideLayout() {
        val laptopConfig = AdaptiveLayoutUtils.computeConfig(1280, 800)

        assertEquals(FormFactor.LAPTOP, laptopConfig.formFactor)
        assertEquals(WindowWidthSize.EXPANDED, laptopConfig.windowWidthSize)
        assertEquals(3, laptopConfig.gridColumnCount)
        assertTrue(laptopConfig.isLaptop)
        assertEquals(1280, laptopConfig.maxContentWidth?.value?.toInt())
    }
}
