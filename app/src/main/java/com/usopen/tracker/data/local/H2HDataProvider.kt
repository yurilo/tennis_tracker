package com.usopen.tracker.data.local

import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.H2HSummary
import com.usopen.tracker.domain.model.HistoricalMatch
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState

object H2HDataProvider {

    fun generateH2H(
        match: Match,
        p1WinsOverride: Int? = null,
        p2WinsOverride: Int? = null
    ): H2HSummary {
        val p1Name = match.player1.name
        val p2Name = match.player2.name

        val baseMatches = getHistoricalMatches(p1Name, p2Name, p1WinsOverride, p2WinsOverride).toMutableList()

        // Include shown match only if it is finished
        if (match.state == MatchState.FINISHED) {
            val winnerName = if (match.player1.isWinner) p1Name else p2Name
            val scoreString = buildString {
                for (i in 0 until maxOf(match.player1.setScores.size, match.player2.setScores.size)) {
                    val s1 = match.player1.setScores.getOrNull(i) ?: 0
                    val s2 = match.player2.setScores.getOrNull(i) ?: 0
                    if (i > 0) append(", ")
                    append("$s1-$s2")
                    val tb = match.player1.tiebreakScores.getOrNull(i) ?: match.player2.tiebreakScores.getOrNull(i)
                    if (tb != null) {
                        append("($tb)")
                    }
                }
            }.ifEmpty { "Final" }

            val currentMatch = HistoricalMatch(
                id = "current_${match.id}",
                date = "2026-09-05",
                year = 2026,
                tournamentName = if (match.tournamentName.isNotBlank() && match.tournamentName.contains("2026")) {
                    match.tournamentName
                } else if (match.tournamentName.isNotBlank()) {
                    "${match.tournamentName} 2026"
                } else {
                    "US Open 2026"
                },
                venue = match.venue.ifEmpty { "Arthur Ashe Stadium" },
                city = "New York",
                round = match.roundName.ifEmpty { "Final" },
                surface = CourtSurface.HARD,
                player1Name = p1Name,
                player2Name = p2Name,
                winnerName = winnerName,
                score = scoreString,
                matchDuration = match.getFormattedDuration(),
                isCurrentMatch = true
            )

            // Prepend current match as the most recent match
            baseMatches.add(0, currentMatch)
        }

        // Order matches by date descending
        val sortedMatches = baseMatches.sortedByDescending { it.date }

        var p1Wins = 0
        var p2Wins = 0
        var hardP1 = 0
        var hardP2 = 0
        var clayP1 = 0
        var clayP2 = 0
        var grassP1 = 0
        var grassP2 = 0
        var gsP1 = 0
        var gsP2 = 0

        for (m in sortedMatches) {
            val isP1 = m.isWinner(p1Name)
            if (isP1) {
                p1Wins++
                when (m.surface) {
                    CourtSurface.HARD -> hardP1++
                    CourtSurface.CLAY -> clayP1++
                    CourtSurface.GRASS -> grassP1++
                    CourtSurface.CARPET -> hardP1++
                }
                if (isGrandSlam(m.tournamentName)) gsP1++
            } else {
                p2Wins++
                when (m.surface) {
                    CourtSurface.HARD -> hardP2++
                    CourtSurface.CLAY -> clayP2++
                    CourtSurface.GRASS -> grassP2++
                    CourtSurface.CARPET -> hardP2++
                }
                if (isGrandSlam(m.tournamentName)) gsP2++
            }
        }

        return H2HSummary(
            player1 = match.player1,
            player2 = match.player2,
            p1Wins = p1Wins,
            p2Wins = p2Wins,
            hardWinsP1 = hardP1,
            hardWinsP2 = hardP2,
            clayWinsP1 = clayP1,
            clayWinsP2 = clayP2,
            grassWinsP1 = grassP1,
            grassWinsP2 = grassP2,
            grandSlamWinsP1 = gsP1,
            grandSlamWinsP2 = gsP2,
            matches = sortedMatches
        )
    }

    private fun isGrandSlam(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("us open") || n.contains("wimbledon") ||
            n.contains("roland garros") || n.contains("french open") ||
            n.contains("australian open")
    }

    fun isCuratedRivalry(p1: String, p2: String): Boolean {
        val norm1 = p1.lowercase()
        val norm2 = p2.lowercase()
        return (norm1.contains("alcaraz") && norm2.contains("sinner")) ||
            (norm1.contains("sinner") && norm2.contains("alcaraz")) ||
            (norm1.contains("alcaraz") && norm2.contains("zverev")) ||
            (norm1.contains("zverev") && norm2.contains("alcaraz")) ||
            (norm1.contains("djokovic") && norm2.contains("alcaraz")) ||
            (norm1.contains("alcaraz") && norm2.contains("djokovic")) ||
            (norm1.contains("navarro") && norm2.contains("muchova")) ||
            (norm1.contains("muchova") && norm2.contains("navarro")) ||
            (norm1.contains("noskova") && norm2.contains("li")) ||
            (norm1.contains("li") && norm2.contains("noskova")) ||
            (norm1.contains("sabalenka") && norm2.contains("swiatek")) ||
            (norm1.contains("swiatek") && norm2.contains("sabalenka"))
    }

    private fun getHistoricalMatches(
        p1: String,
        p2: String,
        p1WinsOverride: Int? = null,
        p2WinsOverride: Int? = null
    ): List<HistoricalMatch> {
        val norm1 = p1.lowercase()
        val norm2 = p2.lowercase()

        return when {
            (norm1.contains("alcaraz") && norm2.contains("sinner")) ||
            (norm1.contains("sinner") && norm2.contains("alcaraz")) -> alcarazVsSinner()

            (norm1.contains("alcaraz") && norm2.contains("zverev")) ||
            (norm1.contains("zverev") && norm2.contains("alcaraz")) -> alcarazVsZverev()

            (norm1.contains("djokovic") && norm2.contains("alcaraz")) ||
            (norm1.contains("alcaraz") && norm2.contains("djokovic")) -> djokovicVsAlcaraz()

            (norm1.contains("navarro") && norm2.contains("muchova")) ||
            (norm1.contains("muchova") && norm2.contains("navarro")) -> navarroVsMuchova()

            (norm1.contains("noskova") && norm2.contains("li")) ||
            (norm1.contains("li") && norm2.contains("noskova")) -> noskovaVsLi()

            (norm1.contains("sabalenka") && norm2.contains("swiatek")) ||
            (norm1.contains("swiatek") && norm2.contains("sabalenka")) -> sabalenkaVsSwiatek()

            else -> generateGenericRivalry(p1, p2, p1WinsOverride, p2WinsOverride)
        }
    }

    private fun alcarazVsSinner(): List<HistoricalMatch> = listOf(
        HistoricalMatch(
            id = "as_1",
            date = "2024-10-02",
            year = 2024,
            tournamentName = "China Open",
            venue = "Lotus Court, National Tennis Center",
            city = "Beijing",
            round = "Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Carlos Alcaraz",
            score = "6-7(6), 6-4, 7-6(3)",
            matchDuration = "3h 21m"
        ),
        HistoricalMatch(
            id = "as_2",
            date = "2024-06-07",
            year = 2024,
            tournamentName = "Roland Garros",
            venue = "Court Philippe-Chatrier",
            city = "Paris",
            round = "Semi-Final",
            surface = CourtSurface.CLAY,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Carlos Alcaraz",
            score = "2-6, 6-3, 3-6, 6-4, 6-3",
            matchDuration = "4h 09m"
        ),
        HistoricalMatch(
            id = "as_3",
            date = "2024-03-16",
            year = 2024,
            tournamentName = "ATP Masters 1000 Indian Wells",
            venue = "Stadium 1, Indian Wells Tennis Garden",
            city = "Indian Wells",
            round = "Semi-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Carlos Alcaraz",
            score = "1-6, 6-3, 6-2",
            matchDuration = "2h 05m"
        ),
        HistoricalMatch(
            id = "as_4",
            date = "2023-10-03",
            year = 2023,
            tournamentName = "China Open",
            venue = "Diamond Court, National Tennis Center",
            city = "Beijing",
            round = "Semi-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Jannik Sinner",
            score = "7-6(4), 6-1",
            matchDuration = "1h 55m"
        ),
        HistoricalMatch(
            id = "as_5",
            date = "2023-03-31",
            year = 2023,
            tournamentName = "ATP Masters 1000 Miami",
            venue = "Hard Rock Stadium",
            city = "Miami",
            round = "Semi-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Jannik Sinner",
            score = "6-7(4), 6-4, 6-2",
            matchDuration = "3h 02m"
        ),
        HistoricalMatch(
            id = "as_6",
            date = "2023-03-18",
            year = 2023,
            tournamentName = "ATP Masters 1000 Indian Wells",
            venue = "Stadium 1, Indian Wells Tennis Garden",
            city = "Indian Wells",
            round = "Semi-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Carlos Alcaraz",
            score = "7-6(4), 6-3",
            matchDuration = "1h 52m"
        ),
        HistoricalMatch(
            id = "as_7",
            date = "2022-09-08",
            year = 2022,
            tournamentName = "US Open",
            venue = "Arthur Ashe Stadium",
            city = "New York",
            round = "Quarter-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Carlos Alcaraz",
            score = "6-3, 6-7(7), 6-7(0), 7-5, 6-3",
            matchDuration = "5h 15m"
        ),
        HistoricalMatch(
            id = "as_8",
            date = "2022-07-31",
            year = 2022,
            tournamentName = "Croatia Open Umag",
            venue = "Goran Ivanisevic Stadium",
            city = "Umag",
            round = "Final",
            surface = CourtSurface.CLAY,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Jannik Sinner",
            score = "6-7(5), 6-1, 6-1",
            matchDuration = "2h 26m"
        ),
        HistoricalMatch(
            id = "as_9",
            date = "2022-07-03",
            year = 2022,
            tournamentName = "Wimbledon",
            venue = "Centre Court, All England Club",
            city = "London",
            round = "Round of 16",
            surface = CourtSurface.GRASS,
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            winnerName = "Jannik Sinner",
            score = "6-1, 6-4, 6-7(8), 6-3",
            matchDuration = "3h 35m"
        )
    )

    private fun alcarazVsZverev(): List<HistoricalMatch> = listOf(
        HistoricalMatch(
            id = "az_1",
            date = "2024-06-09",
            year = 2024,
            tournamentName = "Roland Garros",
            venue = "Court Philippe-Chatrier",
            city = "Paris",
            round = "Final",
            surface = CourtSurface.CLAY,
            player1Name = "Carlos Alcaraz",
            player2Name = "Alexander Zverev",
            winnerName = "Carlos Alcaraz",
            score = "6-3, 2-6, 5-7, 6-1, 6-2",
            matchDuration = "4h 19m"
        ),
        HistoricalMatch(
            id = "az_2",
            date = "2024-03-14",
            year = 2024,
            tournamentName = "ATP Masters 1000 Indian Wells",
            venue = "Stadium 1, Indian Wells Tennis Garden",
            city = "Indian Wells",
            round = "Quarter-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Alexander Zverev",
            winnerName = "Carlos Alcaraz",
            score = "6-3, 6-1",
            matchDuration = "1h 29m"
        ),
        HistoricalMatch(
            id = "az_3",
            date = "2024-01-24",
            year = 2024,
            tournamentName = "Australian Open",
            venue = "Rod Laver Arena",
            city = "Melbourne",
            round = "Quarter-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Alexander Zverev",
            winnerName = "Alexander Zverev",
            score = "6-1, 6-3, 6-7(2), 6-4",
            matchDuration = "3h 05m"
        ),
        HistoricalMatch(
            id = "az_4",
            date = "2023-09-06",
            year = 2023,
            tournamentName = "US Open",
            venue = "Arthur Ashe Stadium",
            city = "New York",
            round = "Quarter-Final",
            surface = CourtSurface.HARD,
            player1Name = "Carlos Alcaraz",
            player2Name = "Alexander Zverev",
            winnerName = "Carlos Alcaraz",
            score = "6-3, 6-2, 6-4",
            matchDuration = "2h 30m"
        )
    )

    private fun djokovicVsAlcaraz(): List<HistoricalMatch> = listOf(
        HistoricalMatch(
            id = "da_1",
            date = "2024-08-04",
            year = 2024,
            tournamentName = "Olympic Games Paris",
            venue = "Court Philippe-Chatrier",
            city = "Paris",
            round = "Final",
            surface = CourtSurface.CLAY,
            player1Name = "Novak Djokovic",
            player2Name = "Carlos Alcaraz",
            winnerName = "Novak Djokovic",
            score = "7-6(3), 7-6(2)",
            matchDuration = "2h 50m"
        ),
        HistoricalMatch(
            id = "da_2",
            date = "2024-07-14",
            year = 2024,
            tournamentName = "Wimbledon",
            venue = "Centre Court, All England Club",
            city = "London",
            round = "Final",
            surface = CourtSurface.GRASS,
            player1Name = "Novak Djokovic",
            player2Name = "Carlos Alcaraz",
            winnerName = "Carlos Alcaraz",
            score = "6-2, 6-2, 7-6(4)",
            matchDuration = "2h 27m"
        ),
        HistoricalMatch(
            id = "da_3",
            date = "2023-08-20",
            year = 2023,
            tournamentName = "Cincinnati Masters",
            venue = "Center Court, Lindner Family Tennis Center",
            city = "Cincinnati",
            round = "Final",
            surface = CourtSurface.HARD,
            player1Name = "Novak Djokovic",
            player2Name = "Carlos Alcaraz",
            winnerName = "Novak Djokovic",
            score = "5-7, 7-6(7), 7-6(4)",
            matchDuration = "3h 49m"
        ),
        HistoricalMatch(
            id = "da_4",
            date = "2023-07-16",
            year = 2023,
            tournamentName = "Wimbledon",
            venue = "Centre Court, All England Club",
            city = "London",
            round = "Final",
            surface = CourtSurface.GRASS,
            player1Name = "Novak Djokovic",
            player2Name = "Carlos Alcaraz",
            winnerName = "Carlos Alcaraz",
            score = "1-6, 7-6(6), 6-1, 3-6, 6-4",
            matchDuration = "4h 42m"
        )
    )

    private fun navarroVsMuchova(): List<HistoricalMatch> = listOf(
        HistoricalMatch(
            id = "nm_1",
            date = "2024-09-04",
            year = 2024,
            tournamentName = "US Open",
            venue = "Arthur Ashe Stadium",
            city = "New York",
            round = "Quarter-Final",
            surface = CourtSurface.HARD,
            player1Name = "Emma Navarro",
            player2Name = "Karolina Muchova",
            winnerName = "Karolina Muchova",
            score = "6-3, 6-4",
            matchDuration = "1h 25m"
        ),
        HistoricalMatch(
            id = "nm_2",
            date = "2024-04-03",
            year = 2024,
            tournamentName = "Credit One Charleston Open",
            venue = "Credit One Stadium",
            city = "Charleston",
            round = "Round of 16",
            surface = CourtSurface.CLAY,
            player1Name = "Emma Navarro",
            player2Name = "Karolina Muchova",
            winnerName = "Emma Navarro",
            score = "6-4, 3-6, 6-3",
            matchDuration = "2h 18m"
        )
    )

    private fun noskovaVsLi(): List<HistoricalMatch> = listOf(
        HistoricalMatch(
            id = "nl_1",
            date = "2024-08-23",
            year = 2024,
            tournamentName = "Abierto GNP Seguros Monterrey",
            venue = "Club Sonoma",
            city = "Monterrey",
            round = "Semi-Final",
            surface = CourtSurface.HARD,
            player1Name = "Linda Noskova",
            player2Name = "Ann Li",
            winnerName = "Linda Noskova",
            score = "6-4, 6-4",
            matchDuration = "1h 22m"
        ),
        HistoricalMatch(
            id = "nl_2",
            date = "2023-01-11",
            year = 2023,
            tournamentName = "Australian Open",
            venue = "Melbourne Park",
            city = "Melbourne",
            round = "Qualifying R2",
            surface = CourtSurface.HARD,
            player1Name = "Linda Noskova",
            player2Name = "Ann Li",
            winnerName = "Linda Noskova",
            score = "6-3, 6-7(5), 6-2",
            matchDuration = "2h 10m"
        )
    )

    private fun sabalenkaVsSwiatek(): List<HistoricalMatch> = listOf(
        HistoricalMatch(
            id = "ss_1",
            date = "2024-08-18",
            year = 2024,
            tournamentName = "Cincinnati Open",
            venue = "Center Court, Lindner Family Tennis Center",
            city = "Cincinnati",
            round = "Semi-Final",
            surface = CourtSurface.HARD,
            player1Name = "Aryna Sabalenka",
            player2Name = "Iga Swiatek",
            winnerName = "Aryna Sabalenka",
            score = "6-3, 6-3",
            matchDuration = "1h 47m"
        ),
        HistoricalMatch(
            id = "ss_2",
            date = "2024-05-18",
            year = 2024,
            tournamentName = "Italian Open",
            venue = "Campo Centrale, Foro Italico",
            city = "Rome",
            round = "Final",
            surface = CourtSurface.CLAY,
            player1Name = "Aryna Sabalenka",
            player2Name = "Iga Swiatek",
            winnerName = "Iga Swiatek",
            score = "6-2, 6-3",
            matchDuration = "1h 29m"
        ),
        HistoricalMatch(
            id = "ss_3",
            date = "2024-05-04",
            year = 2024,
            tournamentName = "Madrid Open",
            venue = "Manolo Santana Stadium, Caja Mágica",
            city = "Madrid",
            round = "Final",
            surface = CourtSurface.CLAY,
            player1Name = "Aryna Sabalenka",
            player2Name = "Iga Swiatek",
            winnerName = "Iga Swiatek",
            score = "7-5, 4-6, 7-6(7)",
            matchDuration = "3h 11m"
        ),
        HistoricalMatch(
            id = "ss_4",
            date = "2023-05-06",
            year = 2023,
            tournamentName = "Madrid Open",
            venue = "Manolo Santana Stadium, Caja Mágica",
            city = "Madrid",
            round = "Final",
            surface = CourtSurface.CLAY,
            player1Name = "Aryna Sabalenka",
            player2Name = "Iga Swiatek",
            winnerName = "Aryna Sabalenka",
            score = "6-3, 3-6, 6-3",
            matchDuration = "2h 26m"
        )
    )

    private fun generateGenericRivalry(
        p1: String,
        p2: String,
        p1WinsOverride: Int? = null,
        p2WinsOverride: Int? = null
    ): List<HistoricalMatch> {
        val s1 = p1.hashCode().let { if (it < 0) -it else it }
        val s2 = p2.hashCode().let { if (it < 0) -it else it }
        val p1Favored = s1 > s2

        // If exact win overrides are provided (e.g. from live Sofascore teamDuel)
        if (p1WinsOverride != null && p2WinsOverride != null) {
            val total = p1WinsOverride + p2WinsOverride
            if (total == 0) return emptyList()

            val matches = mutableListOf<HistoricalMatch>()
            var p1Remaining = p1WinsOverride
            var p2Remaining = p2WinsOverride

            val templates = listOf(
                Triple("2024-08-14", "Cincinnati Masters", CourtSurface.HARD),
                Triple("2024-05-12", "Italian Open", CourtSurface.CLAY),
                Triple("2023-09-02", "US Open", CourtSurface.HARD),
                Triple("2023-07-06", "Wimbledon", CourtSurface.GRASS),
                Triple("2023-03-22", "Miami Open", CourtSurface.HARD),
                Triple("2022-10-18", "European Open", CourtSurface.HARD)
            )

            for (i in 0 until minOf(total, templates.size)) {
                val (date, tournament, surface) = templates[i]
                val winner = when {
                    p1Remaining > 0 && p2Remaining > 0 -> {
                        if ((i % 2 == 0 && p1Favored) || (i % 2 != 0 && !p1Favored)) {
                            p1Remaining--
                            p1
                        } else {
                            p2Remaining--
                            p2
                        }
                    }
                    p1Remaining > 0 -> {
                        p1Remaining--
                        p1
                    }
                    else -> {
                        p2Remaining--
                        p2
                    }
                }

                val score = if (i == 0) "6-4, 7-5" else if (i == 1) "4-6, 6-3, 6-4" else "7-6(4), 6-3"
                matches.add(
                    HistoricalMatch(
                        id = "gen_${p1.hashCode()}_${p2.hashCode()}_$i",
                        date = date,
                        year = date.substring(0, 4).toIntOrNull() ?: 2024,
                        tournamentName = tournament,
                        venue = if (surface == CourtSurface.HARD) "Center Court" else "Court 1",
                        city = if (tournament.contains("US Open")) "New York" else "International",
                        round = if (i == 0) "Quarter-Final" else "Round of 16",
                        surface = surface,
                        player1Name = p1,
                        player2Name = p2,
                        winnerName = winner,
                        score = score,
                        matchDuration = "2h 10m"
                    )
                )
            }
            return matches
        }

        // Default deterministic 3-match rivalry when offline or no API match
        return listOf(
            HistoricalMatch(
                id = "gen_1",
                date = "2024-08-14",
                year = 2024,
                tournamentName = "Cincinnati Masters",
                venue = "Center Court, Lindner Family Tennis Center",
                city = "Cincinnati",
                round = "Quarter-Final",
                surface = CourtSurface.HARD,
                player1Name = p1,
                player2Name = p2,
                winnerName = if (p1Favored) p1 else p2,
                score = "6-4, 7-5",
                matchDuration = "1h 48m"
            ),
            HistoricalMatch(
                id = "gen_2",
                date = "2024-05-12",
                year = 2024,
                tournamentName = "Italian Open",
                venue = "Campo Centrale, Foro Italico",
                city = "Rome",
                round = "Round of 16",
                surface = CourtSurface.CLAY,
                player1Name = p1,
                player2Name = p2,
                winnerName = if (!p1Favored) p1 else p2,
                score = "4-6, 6-3, 6-4",
                matchDuration = "2h 25m"
            ),
            HistoricalMatch(
                id = "gen_3",
                date = "2023-09-02",
                year = 2023,
                tournamentName = "US Open",
                venue = "Louis Armstrong Stadium",
                city = "New York",
                round = "Round 3",
                surface = CourtSurface.HARD,
                player1Name = p1,
                player2Name = p2,
                winnerName = if (p1Favored) p1 else p2,
                score = "7-6(4), 6-3, 6-4",
                matchDuration = "2h 38m"
            )
        )
    }
}
