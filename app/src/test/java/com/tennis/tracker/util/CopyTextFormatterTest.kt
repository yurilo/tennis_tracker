package com.tennis.tracker.util

import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.H2HSummary
import com.tennis.tracker.domain.model.HistoricalMatch
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.PeriodStats
import com.tennis.tracker.domain.model.StatGroup
import com.tennis.tracker.domain.model.StatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyTextFormatterTest {

    @Test
    fun `toSuperscript converts digits accurately`() {
        assertEquals("⁴", CopyTextFormatter.toSuperscript(4))
        assertEquals("¹²", CopyTextFormatter.toSuperscript(12))
        assertEquals("⁰", CopyTextFormatter.toSuperscript(0))
        assertEquals("", CopyTextFormatter.toSuperscript(-1))
    }

    @Test
    fun `formatMatchSummary formats finished match with tiebreak and winner checkmark`() {
        val p1 = Competitor(
            id = "p1",
            name = "Jannik Sinner",
            seed = 1,
            country = "ITA",
            isWinner = true,
            setScores = listOf(6, 6, 6),
            tiebreakScores = listOf(null, 4, null)
        )
        val p2 = Competitor(
            id = "p2",
            name = "Carlos Alcaraz",
            seed = 2,
            country = "ESP",
            isWinner = false,
            setScores = listOf(4, 7, 3),
            tiebreakScores = listOf(null, 7, null)
        )
        val match = Match(
            id = "m1",
            tournamentName = "US Open",
            eventGrouping = "US Open - Men's Singles",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = p1,
            player2 = p2,
            court = "Arthur Ashe Stadium"
        )

        val formatted = CopyTextFormatter.formatMatchSummary(match)
        assertTrue("Actual: $formatted", formatted.contains("US Open - Men's Singles • Final • Arthur Ashe Stadium (FINAL"))
        assertTrue("Actual: $formatted", formatted.contains("✓ (1) Jannik Sinner [ITA]: 6  6⁴  6"))
        assertTrue("Actual: $formatted", formatted.contains("(2) Carlos Alcaraz [ESP]: 4  7  3"))
    }

    @Test
    fun `formatMatchSummary formats live match with serving indicator and game score`() {
        val p1 = Competitor(
            id = "p1",
            name = "Iga Swiatek",
            seed = 1,
            country = "POL",
            isServing = true,
            setScores = listOf(6, 3),
            currentGameScore = "40"
        )
        val p2 = Competitor(
            id = "p2",
            name = "Aryna Sabalenka",
            seed = 2,
            country = "BLR",
            isServing = false,
            setScores = listOf(4, 2),
            currentGameScore = "15"
        )
        val match = Match(
            id = "m2",
            eventGrouping = "WTA Finals - Women's Singles",
            roundName = "Semifinals",
            state = MatchState.LIVE,
            statusDetail = "Set 2",
            player1 = p1,
            player2 = p2,
            court = "Center Court"
        )

        val formatted = CopyTextFormatter.formatMatchSummary(match)
        assertTrue(formatted.contains("🎾 (1) Iga Swiatek [POL]: 6  3 (40)"))
        assertTrue(formatted.contains("(2) Aryna Sabalenka [BLR]: 4  2 (15)"))
    }

    @Test
    fun `formatMatchStats includes period label, match summary, and stat groups`() {
        val p1 = Competitor(id = "1", name = "Jannik Sinner", seed = 1, setScores = listOf(6, 6))
        val p2 = Competitor(id = "2", name = "Daniil Medvedev", seed = 5, setScores = listOf(3, 4))
        val match = Match(
            id = "m3",
            eventGrouping = "Miami Open",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = p1,
            player2 = p2
        )
        val period = PeriodStats(
            period = "ALL",
            label = "Match",
            groups = listOf(
                StatGroup(
                    groupName = "Service",
                    items = listOf(
                        StatItem("Aces", "11", "7", 0.61f),
                        StatItem("Double faults", "1", "4", 0.2f)
                    )
                )
            )
        )

        val text = CopyTextFormatter.formatMatchStats(match, period)
        assertTrue(text.startsWith("MATCH STATS (Match)"))
        assertTrue(text.contains("[SERVICE]"))
        assertTrue(text.contains("• Aces: Sinner 11 | 7 Medvedev"))
        assertTrue(text.contains("• Double faults: Sinner 1 | 4 Medvedev"))
    }

    @Test
    fun `formatH2HSummary and formatHistoricalMatch produce clean rivalry output`() {
        val p1 = Competitor(id = "1", name = "Novak Djokovic", seed = 1)
        val p2 = Competitor(id = "2", name = "Carlos Alcaraz", seed = 2)
        val hm1 = HistoricalMatch(
            id = "h1",
            date = "2024-08-04",
            year = 2024,
            tournamentName = "Paris Olympics",
            venue = "Court Philippe-Chatrier",
            city = "Paris",
            round = "Final",
            surface = CourtSurface.CLAY,
            player1Name = "Novak Djokovic",
            player2Name = "Carlos Alcaraz",
            winnerName = "Novak Djokovic",
            score = "7-6(3), 7-6(2)",
            matchDuration = "2h 50m"
        )
        val summary = H2HSummary(
            player1 = p1,
            player2 = p2,
            p1Wins = 4,
            p2Wins = 3,
            hardWinsP1 = 2,
            hardWinsP2 = 0,
            clayWinsP1 = 2,
            clayWinsP2 = 1,
            grassWinsP1 = 0,
            grassWinsP2 = 2,
            grandSlamWinsP1 = 1,
            grandSlamWinsP2 = 2,
            matches = listOf(hm1)
        )
        val match = Match(
            id = "m4",
            state = MatchState.UPCOMING,
            statusDetail = "Scheduled",
            player1 = p1,
            player2 = p2
        )

        val h2hText = CopyTextFormatter.formatH2HSummary(match, summary, null)
        assertTrue(h2hText.contains("HEAD-TO-HEAD: (1) Novak Djokovic 4 - 3 (2) Carlos Alcaraz (Total Meetings: 1)"))
        assertTrue(h2hText.contains("Surfaces — Hard: 2-0 | Clay: 2-1 | Grass: 0-2 | Majors: 1-2"))
        assertTrue(h2hText.contains("• 2024 Paris Olympics (Final, Clay Court): Novak Djokovic won 7-6(3), 7-6(2)"))

        val singleHmText = CopyTextFormatter.formatHistoricalMatch(hm1)
        assertTrue(singleHmText.contains("2024 Paris Olympics (Final • Clay Court • 2h 50m) — Court Philippe-Chatrier, Paris"))
        assertTrue(singleHmText.contains("Winner: Novak Djokovic (7-6(3), 7-6(2))"))
    }

    @Test
    fun `formatScorelineOnly formats sets and tiebreak scores correctly`() {
        val p1 = Competitor(
            id = "p1",
            name = "Jannik Sinner",
            setScores = listOf(6, 6, 6),
            tiebreakScores = listOf(null, 4, null)
        )
        val p2 = Competitor(
            id = "p2",
            name = "Carlos Alcaraz",
            setScores = listOf(4, 7, 3),
            tiebreakScores = listOf(null, 7, null)
        )
        val match = Match(
            id = "m1",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = p1,
            player2 = p2
        )

        val scoreline = CopyTextFormatter.formatScorelineOnly(match)
        assertEquals("6-4, 6-7(4), 6-3", scoreline)
    }

    @Test
    fun `formatScorelineOnly formats live match with current game points`() {
        val p1 = Competitor(
            id = "p1",
            name = "Iga Swiatek",
            setScores = listOf(6, 3),
            currentGameScore = "40"
        )
        val p2 = Competitor(
            id = "p2",
            name = "Aryna Sabalenka",
            setScores = listOf(4, 2),
            currentGameScore = "15"
        )
        val match = Match(
            id = "m2",
            state = MatchState.LIVE,
            statusDetail = "Set 2",
            player1 = p1,
            player2 = p2
        )

        val scoreline = CopyTextFormatter.formatScorelineOnly(match)
        assertEquals("6-4, 3-2 (40-15)", scoreline)
    }
}
