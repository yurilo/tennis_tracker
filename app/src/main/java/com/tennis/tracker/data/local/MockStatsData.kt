package com.tennis.tracker.data.local

import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.PeriodStats
import com.tennis.tracker.domain.model.StatGroup
import com.tennis.tracker.domain.model.StatItem

object MockStatsData {

    fun generate(match: Match): MatchStatistics {
        val p1 = match.player1
        val p2 = match.player2

        val p1Wins = when {
            p1.isWinner -> true
            p2.isWinner -> false
            else -> p1.setScores.sum() >= p2.setScores.sum()
        }

        val hash = (match.id.hashCode() and 0x7fffffff)
        val p1AceBonus = (hash % 5)
        val p2AceBonus = ((hash / 5) % 5)

        val p1AceCount = if (p1Wins) 7 + p1AceBonus else 4 + (p1AceBonus % 3)
        val p2AceCount = if (p1Wins) 4 + (p2AceBonus % 3) else 7 + p2AceBonus
        val p1DfCount = if (p1Wins) 1 + (hash % 3) else 3 + (hash % 3)
        val p2DfCount = if (p1Wins) 3 + ((hash / 3) % 3) else 1 + ((hash / 3) % 3)

        val allPeriod = PeriodStats(
            period = "ALL",
            label = "Match",
            groups = listOf(
                StatGroup(
                    groupName = "Service",
                    items = listOf(
                        createItem("Aces", "$p1AceCount", "$p2AceCount"),
                        createItem("Double faults", "$p1DfCount", "$p2DfCount"),
                        createItem("First serve", if (p1Wins) "48/68 (71%)" else "42/65 (65%)", if (p1Wins) "44/70 (63%)" else "49/69 (71%)", if (p1Wins) 0.52f else 0.48f),
                        createItem("First serve points", if (p1Wins) "37/48 (77%)" else "29/42 (69%)", if (p1Wins) "30/44 (68%)" else "38/49 (78%)", if (p1Wins) 0.53f else 0.47f),
                        createItem("Second serve points", if (p1Wins) "12/20 (60%)" else "11/23 (48%)", if (p1Wins) "13/26 (50%)" else "12/20 (60%)", if (p1Wins) 0.55f else 0.45f),
                        createItem("Break points saved", if (p1Wins) "4/5 (80%)" else "2/4 (50%)", if (p1Wins) "3/6 (50%)" else "5/6 (83%)", if (p1Wins) 0.60f else 0.40f)
                    )
                ),
                StatGroup(
                    groupName = "Winners",
                    items = listOf(
                        createItem("Total", if (p1Wins) "28" else "18", if (p1Wins) "20" else "26"),
                        createItem("Forehand", if (p1Wins) "15" else "9", if (p1Wins) "11" else "14"),
                        createItem("Backhand", if (p1Wins) "7" else "5", if (p1Wins) "6" else "8"),
                        createItem("Volley", if (p1Wins) "4" else "2", if (p1Wins) "2" else "3"),
                        createItem("Groundstroke", if (p1Wins) "22" else "14", if (p1Wins) "17" else "22")
                    )
                ),
                StatGroup(
                    groupName = "Unforced errors",
                    items = listOf(
                        createItem("Total", if (p1Wins) "16" else "24", if (p1Wins) "25" else "18"),
                        createItem("Forehand", if (p1Wins) "9" else "13", if (p1Wins) "14" else "10"),
                        createItem("Backhand", if (p1Wins) "5" else "8", if (p1Wins) "9" else "6"),
                        createItem("Volley", if (p1Wins) "2" else "3", if (p1Wins) "2" else "2")
                    )
                ),
                StatGroup(
                    groupName = "Return",
                    items = listOf(
                        createItem("First serve return points", if (p1Wins) "14/44 (32%)" else "11/48 (23%)", if (p1Wins) "11/48 (23%)" else "13/42 (31%)", if (p1Wins) 0.58f else 0.42f),
                        createItem("Second serve return points", if (p1Wins) "13/26 (50%)" else "8/20 (40%)", if (p1Wins) "8/20 (40%)" else "12/23 (52%)", if (p1Wins) 0.56f else 0.44f),
                        createItem("Break points converted", if (p1Wins) "3/6 (50%)" else "1/4 (25%)", if (p1Wins) "1/5 (20%)" else "3/5 (60%)", if (p1Wins) 0.65f else 0.35f)
                    )
                ),
                StatGroup(
                    groupName = "Points",
                    items = listOf(
                        createItem("Total", if (p1Wins) "85" else "71", if (p1Wins) "73" else "83"),
                        createItem("Service points won", if (p1Wins) "49/68 (72%)" else "40/65 (62%)", if (p1Wins) "43/70 (61%)" else "50/69 (72%)", if (p1Wins) 0.53f else 0.47f),
                        createItem("Receiver points won", if (p1Wins) "27/70 (39%)" else "19/69 (28%)", if (p1Wins) "19/68 (28%)" else "25/65 (38%)", if (p1Wins) 0.59f else 0.41f)
                    )
                )
            )
        )

        val periodsList = mutableListOf(allPeriod)

        val setScoresCount = maxOf(p1.setScores.size, p2.setScores.size)
        val effectiveSetCount = if (setScoresCount > 0) {
            setScoresCount
        } else if (match.state == MatchState.FINISHED) {
            2
        } else if (match.state == MatchState.LIVE) {
            1
        } else {
            0
        }

        for (idx in 0 until effectiveSetCount) {
            val setNumber = idx + 1
            val p1SetScore = p1.setScores.getOrNull(idx) ?: if (p1Wins) 6 else 4
            val p2SetScore = p2.setScores.getOrNull(idx) ?: if (p1Wins) 4 else 6
            val setWinner = p1SetScore > p2SetScore

            val periodCode = when (setNumber) {
                1 -> "1ST"
                2 -> "2ND"
                3 -> "3RD"
                4 -> "4TH"
                5 -> "5TH"
                else -> "${setNumber}TH"
            }

            periodsList.add(
                PeriodStats(
                    period = periodCode,
                    label = "Set $setNumber",
                    groups = listOf(
                        StatGroup(
                            groupName = "Service",
                            items = listOf(
                                createItem("Aces", if (setWinner) "3" else "1", if (setWinner) "2" else "3"),
                                createItem("Double faults", if (setWinner) "0" else "2", if (setWinner) "1" else "0"),
                                createItem("First serve", if (setWinner) "18/24 (75%)" else "15/23 (65%)", if (setWinner) "16/25 (64%)" else "19/26 (73%)", if (setWinner) 0.53f else 0.47f),
                                createItem("Break points saved", if (setWinner) "2/2 (100%)" else "1/2 (50%)", if (setWinner) "1/2 (50%)" else "2/2 (100%)", if (setWinner) 0.67f else 0.33f)
                            )
                        ),
                        StatGroup(
                            groupName = "Winners",
                            items = listOf(
                                createItem("Total", if (setWinner) "10" else "6", if (setWinner) "7" else "9"),
                                createItem("Forehand", if (setWinner) "6" else "3", if (setWinner) "4" else "5"),
                                createItem("Backhand", if (setWinner) "3" else "2", if (setWinner) "2" else "3")
                            )
                        ),
                        StatGroup(
                            groupName = "Unforced errors",
                            items = listOf(
                                createItem("Total", if (setWinner) "5" else "9", if (setWinner) "8" else "6"),
                                createItem("Forehand", if (setWinner) "3" else "5", if (setWinner) "4" else "3")
                            )
                        ),
                        StatGroup(
                            groupName = "Return",
                            items = listOf(
                                createItem("Break points converted", if (setWinner) "1/2 (50%)" else "0/2 (0%)", if (setWinner) "0/2 (0%)" else "1/2 (50%)", if (setWinner) 0.75f else 0.25f)
                            )
                        ),
                        StatGroup(
                            groupName = "Points",
                            items = listOf(
                                createItem("Total", if (setWinner) "${28 + p1SetScore}" else "${22 + p1SetScore}", if (setWinner) "${22 + p2SetScore}" else "${28 + p2SetScore}"),
                                createItem("Service points won", if (setWinner) "18/24 (75%)" else "14/23 (61%)", if (setWinner) "15/25 (60%)" else "18/26 (69%)", if (setWinner) 0.54f else 0.46f),
                                createItem("Receiver points won", if (setWinner) "10/25 (40%)" else "7/26 (27%)", if (setWinner) "6/24 (25%)" else "9/23 (39%)", if (setWinner) 0.60f else 0.40f)
                            )
                        )
                    )
                )
            )
        }

        return MatchStatistics(
            matchId = match.id,
            player1Name = p1.name,
            player2Name = p2.name,
            periods = periodsList
        )
    }

    private fun createItem(name: String, home: String, away: String, forcedRatio: Float? = null): StatItem {
        val ratio = forcedRatio ?: run {
            val hVal = home.filter { it.isDigit() }.toIntOrNull()?.toFloat() ?: 0f
            val aVal = away.filter { it.isDigit() }.toIntOrNull()?.toFloat() ?: 0f
            if (hVal + aVal > 0f) hVal / (hVal + aVal) else 0.5f
        }
        return StatItem(
            name = name,
            homeDisplay = home,
            awayDisplay = away,
            homeRatio = ratio.coerceIn(0.05f, 0.95f)
        )
    }
}
