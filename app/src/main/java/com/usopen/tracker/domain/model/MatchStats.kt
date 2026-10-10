package com.usopen.tracker.domain.model

data class StatItem(
    val name: String,
    val homeDisplay: String,
    val awayDisplay: String,
    val homeRatio: Float = 0.5f // Fraction between 0f and 1f for the home player comparison bar
)

data class StatGroup(
    val groupName: String,
    val items: List<StatItem>
)

data class PeriodStats(
    val period: String,       // "ALL", "1ST", "2ND", "3RD", etc.
    val label: String,        // "Match", "Set 1", "Set 2", "Set 3", etc.
    val groups: List<StatGroup>
) {
    fun findItem(groupName: String, itemName: String): StatItem? {
        return groups.firstOrNull { it.groupName.equals(groupName, ignoreCase = true) }
            ?.items?.firstOrNull { it.name.equals(itemName, ignoreCase = true) }
    }
}

data class MatchStatistics(
    val matchId: String,
    val player1Name: String,
    val player2Name: String,
    val periods: List<PeriodStats>
)
