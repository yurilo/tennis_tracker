package com.usopen.tracker.domain.model



data class HistoricalMatch(
    val id: String,
    val date: String,            // ISO date "2024-07-14" or formatted "Jul 14, 2024"
    val year: Int,
    val tournamentName: String,  // e.g. "Wimbledon", "US Open", "Roland Garros", "ATP Finals"
    val venue: String,           // e.g. "Centre Court", "Arthur Ashe Stadium"
    val city: String? = null,    // e.g. "London", "New York", "Paris"
    val round: String,           // e.g. "Final", "Semi-Final", "Quarter-Final"
    val surface: CourtSurface,
    val player1Name: String,
    val player2Name: String,
    val winnerName: String,
    val score: String,           // e.g. "6-3, 6-7(5), 7-6(2), 7-5"
    val matchDuration: String? = null,
    val isCurrentMatch: Boolean = false
) {
    fun isWinner(name: String): Boolean {
        val normalizedWinner = winnerName.trim().lowercase()
        val normalizedName = name.trim().lowercase()
        return normalizedWinner == normalizedName ||
            normalizedWinner.contains(normalizedName) ||
            normalizedName.contains(normalizedWinner)
    }
}

data class H2HSummary(
    val player1: Competitor,
    val player2: Competitor,
    val p1Wins: Int,
    val p2Wins: Int,
    val hardWinsP1: Int,
    val hardWinsP2: Int,
    val clayWinsP1: Int,
    val clayWinsP2: Int,
    val grassWinsP1: Int,
    val grassWinsP2: Int,
    val grandSlamWinsP1: Int,
    val grandSlamWinsP2: Int,
    val matches: List<HistoricalMatch>
) {
    val totalMatches: Int
        get() = if (matches.isNotEmpty()) matches.size else (p1Wins + p2Wins)

    val p1WinPercentage: Float
        get() = if (totalMatches > 0) p1Wins.toFloat() / totalMatches else 0.5f

    val p2WinPercentage: Float
        get() = if (totalMatches > 0) p2Wins.toFloat() / totalMatches else 0.5f

    val lastWinnerName: String?
        get() = matches.firstOrNull()?.winnerName
}

data class H2HUiState(
    val isLoading: Boolean = false,
    val summary: H2HSummary? = null,
    val selectedSurface: CourtSurface? = null, // null means "All Surfaces"
    val errorMessage: String? = null
) {
    val filteredMatches: List<HistoricalMatch>
        get() = if (selectedSurface == null) {
            summary?.matches ?: emptyList()
        } else {
            summary?.matches?.filter { it.surface == selectedSurface } ?: emptyList()
        }
}
