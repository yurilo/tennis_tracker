package com.usopen.tracker.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.H2HSummary
import com.usopen.tracker.domain.model.HistoricalMatch
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.PeriodStats
import com.usopen.tracker.domain.model.StatItem

/**
 * Pure formatting utility that converts domain models (Match, MatchStatistics, H2HSummary, StatItem)
 * into clean, human-readable text snippets suitable for clipboard copy and sharing.
 */
object CopyTextFormatter {

    private val SUPERSCRIPT_DIGITS = charArrayOf('⁰', '¹', '²', '³', '⁴', '⁵', '⁶', '⁷', '⁸', '⁹')

    fun toSuperscript(number: Int): String {
        if (number < 0) return ""
        return number.toString().map { ch ->
            if (ch in '0'..'9') SUPERSCRIPT_DIGITS[ch - '0'] else ch
        }.joinToString("")
    }

    /**
     * Formats a single competitor's name, seed, winner/serving indicator, and set/game scores.
     */
    fun formatCompetitorScoreLine(
        competitor: Competitor,
        opponent: Competitor,
        isLive: Boolean
    ): String {
        val prefix = buildString {
            if (isLive && competitor.isServing) append("🎾 ")
            if (competitor.isWinner) append("✓ ")
        }
        val nameWithCountry = buildString {
            append(competitor.displayNameWithSeed)
            if (!competitor.country.isNullOrBlank()) {
                append(" [")
                append(competitor.country)
                append("]")
            }
        }

        val setScoresFormatted = competitor.setScores.indices.map { i ->
            val score = competitor.setScores[i]
            val oppScore = opponent.setScores.getOrNull(i) ?: 0
            val tb = competitor.tiebreakScores.getOrNull(i)
            val oppTb = opponent.tiebreakScores.getOrNull(i)
            val isLiveTiebreak = isLive && (score >= 6 && oppScore >= 6) && (tb != null || oppTb != null)
            val showTiebreak = when {
                isLiveTiebreak -> tb != null && tb >= 0
                score < oppScore && tb != null && tb >= 0 -> true
                else -> false
            }
            if (showTiebreak && tb != null) {
                "$score${toSuperscript(tb)}"
            } else {
                "$score"
            }
        }

        val scorePart = if (setScoresFormatted.isNotEmpty()) {
            val setsStr = setScoresFormatted.joinToString("  ")
            val liveGame = if (isLive && !competitor.currentGameScore.isNullOrBlank()) {
                " (${competitor.currentGameScore.trim()})"
            } else {
                ""
            }
            ": $setsStr$liveGame"
        } else {
            ""
        }

        return "$prefix$nameWithCountry$scorePart".trim()
    }

    /**
     * Formats a complete MatchCard summary for copying or sharing.
     */
    fun formatMatchSummary(match: Match): String {
        val headerParts = mutableListOf<String>()
        if (match.eventGrouping.isNotBlank()) {
            headerParts.add(match.eventGrouping)
        } else if (match.tournamentName.isNotBlank()) {
            headerParts.add(match.tournamentName)
        }
        if (match.roundName.isNotBlank()) {
            headerParts.add(match.roundName)
        }
        match.displayCourt?.takeIf { it.isNotBlank() }?.let {
            headerParts.add(it)
        }

        val status = match.displayStatus.takeIf { it.isNotBlank() } ?: match.statusDetail
        val isLive = match.state == MatchState.LIVE
        val p1Line = formatCompetitorScoreLine(match.player1, match.player2, isLive)
        val p2Line = formatCompetitorScoreLine(match.player2, match.player1, isLive)

        return buildString {
            if (headerParts.isNotEmpty()) {
                append(headerParts.joinToString(" • "))
                if (status.isNotBlank()) {
                    append(" (")
                    append(status)
                    append(")")
                }
                appendLine()
            } else if (status.isNotBlank()) {
                appendLine(status)
            }
            appendLine(p1Line)
            append(p2Line)
        }
    }

    /**
     * Formats a single StatItem comparison row for quick clipboard copying.
     */
    fun formatStatComparison(
        player1Name: String,
        player2Name: String,
        item: StatItem
    ): String {
        return "${item.name}: $player1Name ${item.homeDisplay} — ${item.awayDisplay} $player2Name"
    }

    /**
     * Formats the full MatchStatsSheet for the currently selected period.
     */
    fun formatMatchStats(match: Match, period: PeriodStats?): String {
        return buildString {
            val periodLabel = period?.label ?: "Match"
            appendLine("MATCH STATS ($periodLabel)")
            appendLine(formatMatchSummary(match))
            if (period != null && period.groups.isNotEmpty()) {
                for (group in period.groups) {
                    appendLine()
                    appendLine("[${group.groupName.uppercase()}]")
                    for (item in group.items) {
                        appendLine("• ${item.name}: ${match.player1.lastNameOrShortName} ${item.homeDisplay} | ${item.awayDisplay} ${match.player2.lastNameOrShortName}")
                    }
                }
            }
        }.trimEnd()
    }

    /**
     * Formats a single HistoricalMatch encounter from the H2H timeline.
     */
    fun formatHistoricalMatch(match: HistoricalMatch): String {
        val venuePart = if (!match.city.isNullOrBlank()) "${match.venue}, ${match.city}" else match.venue
        val durationPart = match.matchDuration?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""
        return buildString {
            append("${match.year} ${match.tournamentName} (${match.round} • ${match.surface.displayName}$durationPart)")
            if (venuePart.isNotBlank()) {
                append(" — $venuePart")
            }
            appendLine()
            append("Winner: ${match.winnerName} (${match.score})")
        }
    }

    /**
     * Formats the Head-to-Head rivalry summary and filtered match timeline.
     */
    fun formatH2HSummary(
        match: Match,
        summary: H2HSummary?,
        selectedSurface: CourtSurface? = null
    ): String {
        if (summary == null) {
            return "Head-to-Head: ${match.player1.displayNameWithSeed} vs ${match.player2.displayNameWithSeed}"
        }
        val p1 = summary.player1.displayNameWithSeed
        val p2 = summary.player2.displayNameWithSeed
        val matchesToInclude = if (selectedSurface == null) {
            summary.matches
        } else {
            summary.matches.filter { it.surface == selectedSurface }
        }

        return buildString {
            appendLine("HEAD-TO-HEAD: $p1 ${summary.p1Wins} - ${summary.p2Wins} $p2 (Total Meetings: ${summary.totalMatches})")
            appendLine(
                "Surfaces — Hard: ${summary.hardWinsP1}-${summary.hardWinsP2} | " +
                    "Clay: ${summary.clayWinsP1}-${summary.clayWinsP2} | " +
                    "Grass: ${summary.grassWinsP1}-${summary.grassWinsP2} | " +
                    "Majors: ${summary.grandSlamWinsP1}-${summary.grandSlamWinsP2}"
            )
            if (matchesToInclude.isNotEmpty()) {
                appendLine()
                val header = if (selectedSurface != null) {
                    "${selectedSurface.displayName.uppercase()} ENCOUNTERS (${matchesToInclude.size}):"
                } else {
                    "MATCH TIMELINE (${matchesToInclude.size}):"
                }
                appendLine(header)
                for (hm in matchesToInclude) {
                    appendLine("• ${hm.year} ${hm.tournamentName} (${hm.round}, ${hm.surface.displayName}): ${hm.winnerName} won ${hm.score}")
                }
            }
        }.trimEnd()
    }

    /**
     * Formats only the scoreline of a match (e.g. "6-4, 6-7(4), 6-3" or "6-4, 3-2 (30-15)").
     */
    fun formatScorelineOnly(match: Match): String {
        val p1Sets = match.player1.setScores
        val p2Sets = match.player2.setScores
        val maxSets = maxOf(p1Sets.size, p2Sets.size)
        if (maxSets == 0) return ""
        val setParts = (0 until maxSets).map { i ->
            val s1 = p1Sets.getOrNull(i) ?: 0
            val s2 = p2Sets.getOrNull(i) ?: 0
            val tb1 = match.player1.tiebreakScores.getOrNull(i)
            val tb2 = match.player2.tiebreakScores.getOrNull(i)
            val tbStr = when {
                tb1 != null && tb2 != null -> "(${minOf(tb1, tb2)})"
                tb1 != null -> "($tb1)"
                tb2 != null -> "($tb2)"
                else -> ""
            }
            "$s1-$s2$tbStr"
        }
        val scoreline = setParts.joinToString(", ")
        val isLive = match.state == MatchState.LIVE
        val g1 = match.player1.currentGameScore?.trim()
        val g2 = match.player2.currentGameScore?.trim()
        return if (isLive && (!g1.isNullOrBlank() || !g2.isNullOrBlank())) {
            "$scoreline (${g1 ?: "0"}-${g2 ?: "0"})"
        } else {
            scoreline
        }
    }
}

/**
 * Android Clipboard helper providing tactile + visual confirmation when copying text.
 */
object ClipboardHelper {

    fun copyToClipboard(
        context: Context,
        label: String,
        text: String,
        toastMessage: String = "Copied to clipboard"
    ): Boolean {
        if (text.isBlank()) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, toastMessage, Toast.LENGTH_SHORT).show()
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Launches the system Android Share Sheet (ACTION_SEND).
     */
    fun shareText(
        context: Context,
        title: String,
        text: String
    ): Boolean {
        if (text.isBlank()) return false
        return try {
            val sendIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_TITLE, title)
                putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                type = "text/plain"
            }
            val chooser = android.content.Intent.createChooser(sendIntent, title).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
