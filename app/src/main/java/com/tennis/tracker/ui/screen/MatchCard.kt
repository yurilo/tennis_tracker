package com.tennis.tracker.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.DeepLink
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.ui.theme.CardBorder
import com.tennis.tracker.ui.theme.EspnRed
import com.tennis.tracker.ui.theme.LiveIndicatorRed
import com.tennis.tracker.ui.theme.UsOpenCourtBlue
import com.tennis.tracker.ui.theme.UsOpenGold
import com.tennis.tracker.ui.theme.UsOpenNavy
import com.tennis.tracker.ui.theme.UsOpenNavyDark
import com.tennis.tracker.ui.theme.UsOpenTennisGreen
import com.tennis.tracker.ui.theme.TennisVoltYellow
import com.tennis.tracker.util.ClipboardHelper
import com.tennis.tracker.util.CopyTextFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MatchCard(
    match: Match,
    onWatchClick: (DeepLink) -> Unit,
    onStatsClick: (Match) -> Unit,
    onH2HClick: ((Match) -> Unit)? = null,
    onHighlightsClick: ((Match) -> Unit)? = null,
    isFollowed: Boolean = false,
    onFollowClick: ((Match) -> Unit)? = null,
    onCopyClick: ((Match) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val statsButtonColor = if (isDark) UsOpenGold else UsOpenNavy
    val cardBorderColor = if (isDark) Color(0xFF334155).copy(alpha = 0.6f) else CardBorder
    var showInternalCopySheet by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { /* card click */ },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (onCopyClick != null) {
                        onCopyClick(match)
                    } else {
                        showInternalCopySheet = true
                    }
                }
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Grouping, Round, Venue, Timing, Copy, Follow Button & Status Badge
            val courtInfo = match.displayCourt
            val headerSubtext = buildString {
                append(match.eventGrouping)
                if (match.roundName.isNotEmpty() && match.roundName != match.statusDetail) {
                    append(" • ")
                    append(match.roundName)
                }
                if (match.state != MatchState.UPCOMING && match.formattedStartTime != null) {
                    append(" • ")
                    append(match.formattedStartTime)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerSubtext,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Copy / Share Button Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .combinedClickable(
                                onClick = {
                                    if (onCopyClick != null) {
                                        onCopyClick(match)
                                    } else {
                                        showInternalCopySheet = true
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    ClipboardHelper.copyToClipboard(
                                        context = context,
                                        label = "Match Summary",
                                        text = CopyTextFormatter.formatMatchSummary(match),
                                        toastMessage = "Copied match summary"
                                    )
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy or share match",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Copy",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                    }

                        if (onFollowClick != null) {
                            DisableSelection {
                                val followBg = if (isFollowed) {
                                    if (isDark) UsOpenGold.copy(alpha = 0.2f) else UsOpenGold.copy(alpha = 0.15f)
                                } else {
                                    Color.Transparent
                                }
                                val followBorder = if (isFollowed) UsOpenGold else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                val followColor = if (isFollowed) UsOpenGold else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(followBg)
                                        .border(1.dp, followBorder, RoundedCornerShape(12.dp))
                                        .clickable { onFollowClick(match) }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isFollowed) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = if (isFollowed) "Following" else "Follow",
                                            tint = followColor,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (isFollowed) "Following" else "Follow",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = followColor
                                        )
                                    }
                                }
                            }
                        }

                        MatchStatusBadge(match = match)
                    }
                }

                // Court / Stadium Badge (e.g. Arthur Ashe Stadium, Louis Armstrong Stadium, Court 7)
                if (courtInfo != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        courtInfo.contains("Ashe", ignoreCase = true) ->
                                            if (isDark) UsOpenGold.copy(alpha = 0.18f) else UsOpenNavy.copy(alpha = 0.08f)
                                        courtInfo.contains("Armstrong", ignoreCase = true) ->
                                            if (isDark) UsOpenCourtBlue.copy(alpha = 0.22f) else UsOpenCourtBlue.copy(alpha = 0.10f)
                                        courtInfo.contains("Grandstand", ignoreCase = true) ->
                                            if (isDark) UsOpenTennisGreen.copy(alpha = 0.22f) else UsOpenTennisGreen.copy(alpha = 0.10f)
                                        else ->
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                    }
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "🏟️",
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = courtInfo,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) UsOpenGold else UsOpenNavy
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Player 1 Row
                CompetitorRow(
                    competitor = match.player1,
                    opponent = match.player2,
                    isLive = match.state == MatchState.LIVE
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Player 2 Row
                CompetitorRow(
                    competitor = match.player2,
                    opponent = match.player1,
                    isLive = match.state == MatchState.LIVE
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons: H2H + Stats (on demand) + Watch on ESPN + Highlights (completed matches)
                val isFinished = match.state == MatchState.FINISHED
                DisableSelection {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                if (onH2HClick != null) {
                    OutlinedButton(
                        onClick = { onH2HClick(match) },
                        modifier = Modifier.weight(if (isFinished) 0.85f else 1f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, statsButtonColor.copy(alpha = 0.8f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = statsButtonColor
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "H2H",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = statsButtonColor
                        )
                    }
                }

                if (match.state != MatchState.UPCOMING) {
                    OutlinedButton(
                        onClick = { onStatsClick(match) },
                        modifier = Modifier.weight(if (isFinished) 0.95f else 1f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, statsButtonColor.copy(alpha = 0.8f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = statsButtonColor
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = statsButtonColor
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Stats",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = statsButtonColor
                        )
                    }
                }

                if (match.deepLink != null) {
                    val watchLabel = if (isFinished) "Replay" else match.deepLink.buttonLabel
                    Button(
                        onClick = { onWatchClick(match.deepLink) },
                        modifier = Modifier.weight(if (isFinished) 1.05f else 1.2f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                match.state == MatchState.LIVE -> EspnRed
                                isDark -> Color(0xFF1E293B)
                                else -> UsOpenNavy
                            }
                        )
                    ) {
                        Icon(
                            imageVector = if (match.state == MatchState.LIVE) Icons.Default.PlayArrow else Icons.Default.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = watchLabel,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isFinished && onHighlightsClick != null) {
                    Button(
                        onClick = { onHighlightsClick(match) },
                        modifier = Modifier.weight(1.15f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF0000)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Watch Highlights",
                            modifier = Modifier.size(15.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Highlights",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    }
                }
            }
        }
    }

    if (showInternalCopySheet) {
        MatchCopyBottomSheet(
            match = match,
            onDismiss = { showInternalCopySheet = false }
        )
    }
}

@Composable
private fun MatchStatusBadge(
    match: Match
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    val badgeText = match.getDisplayStatus(java.time.ZoneId.systemDefault(), is24Hour)

    val isDark = isSystemInDarkTheme()
    val (bgColor, textColor) = when (match.state) {
        MatchState.LIVE -> LiveIndicatorRed to Color.White
        MatchState.UPCOMING -> if (isDark) Color(0xFF1E293B) to UsOpenGold else UsOpenNavy to UsOpenGold
        MatchState.FINISHED -> if (isDark) Color(0xFF334155) to Color(0xFFF1F5F9) else Color(0xFFE2E8F0) to Color(0xFF334155)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (match.state == MatchState.LIVE) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = badgeText,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun isValidTennisScore(score: String?): Boolean {
    if (score.isNullOrBlank()) return false
    val trimmed = score.trim()
    return trimmed in listOf("0", "15", "30", "40", "A", "Adv", "ADV", "Ad", "AD", "Deuce", "DEUCE") ||
        trimmed.startsWith("TB", ignoreCase = true) ||
        (trimmed.all { it.isDigit() } && (trimmed.toIntOrNull() ?: -1) in 0..99)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompetitorRow(
    competitor: Competitor,
    opponent: Competitor,
    isLive: Boolean
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Player Name, Serve Indicator, Seed & Winner Checkmark
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(
                    onClick = {
                        Toast.makeText(
                            context,
                            "${competitor.displayNameWithSeed} (Hold to copy name)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        ClipboardHelper.copyToClipboard(
                            context = context,
                            label = "Player Name",
                            text = competitor.name,
                            toastMessage = "Copied '${competitor.name}'"
                        )
                    }
                )
                .padding(vertical = 3.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Service Indicator (tennis ball icon) for active server during live matches
            if (isLive && competitor.isServing) {
                Text(
                    text = "🎾",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            if (competitor.isWinner) {
                Text(
                    text = "✓",
                    color = UsOpenTennisGreen,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Text(
                text = competitor.displayNameWithSeed,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (competitor.isWinner || isLive) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    competitor.isWinner -> if (isDark) UsOpenGold else UsOpenNavy
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Set scores and Tiebreaks
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(
                    onClick = { /* score tap */ },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val setStr = competitor.setScores.joinToString(" ")
                        ClipboardHelper.copyToClipboard(
                            context = context,
                            label = "Set Scores",
                            text = setStr,
                            toastMessage = "Copied scores: $setStr"
                        )
                    }
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            for (i in competitor.setScores.indices) {
                val score = competitor.setScores[i]
                val oppScore = opponent.setScores.getOrNull(i) ?: 0
                val tb = competitor.tiebreakScores.getOrNull(i)
                val oppTb = opponent.tiebreakScores.getOrNull(i)

                // Professional ATP/WTA/SlamTracker convention:
                // 1. Live in-progress tiebreak (e.g. 6-6 in set): show each player's tiebreak points in superscript (e.g. 6⁶ and 6²)
                // 2. Finished set tiebreak (e.g. 7-6 or 6-7): show losing set score with loser's tiebreak points in superscript (e.g. 7 - 6⁴)
                val isLiveTiebreak = isLive && (score >= 6 && oppScore >= 6) && (tb != null || oppTb != null)
                val showTiebreak = when {
                    isLiveTiebreak -> tb != null && tb >= 0
                    score < oppScore && tb != null && tb >= 0 -> true
                    else -> false
                }

                val annotatedScore = buildAnnotatedString {
                    append("$score")
                    if (showTiebreak) {
                        withStyle(
                            SpanStyle(
                                baselineShift = BaselineShift.Superscript,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                        ) {
                            append("$tb")
                        }
                    }
                }

                Text(
                    text = annotatedScore,
                    fontSize = 14.sp,
                    fontWeight = if (competitor.isWinner || score > oppScore) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.defaultMinSize(minWidth = 20.dp),
                    textAlign = TextAlign.Center
                )
            }

            // Current Game score if live (e.g. 40, Adv, or tiebreak points like TB 6, TB 2)
            val displayGameScore = when {
                !isLive -> null
                competitor.currentGameScore != null && isValidTennisScore(competitor.currentGameScore) -> {
                    val raw = competitor.currentGameScore.trim()
                    when {
                        raw.equals("A", ignoreCase = true) || raw.equals("Ad", ignoreCase = true) -> "Adv"
                        raw.equals("Deuce", ignoreCase = true) -> "40"
                        else -> raw
                    }
                }
                competitor.setScores.isNotEmpty() -> {
                    val lastIdx = competitor.setScores.size - 1
                    val myTb = competitor.tiebreakScores.getOrNull(lastIdx)
                    val oppTb = opponent.tiebreakScores.getOrNull(lastIdx)
                    val mySet = competitor.setScores[lastIdx]
                    val oppSet = opponent.setScores.getOrNull(lastIdx) ?: 0
                    if (myTb != null || oppTb != null || (mySet >= 6 && oppSet >= 6)) {
                        "TB ${myTb ?: 0}"
                    } else if (competitor.isServing) {
                        "40"
                    } else {
                        "30"
                    }
                }
                else -> if (competitor.isServing) "40" else "30"
            }

            if (isLive && displayGameScore != null) {
                val isAdv = displayGameScore.equals("adv", ignoreCase = true)
                val isTb = displayGameScore.startsWith("TB", ignoreCase = true)
                val isServing = competitor.isServing

                val pillBg = when {
                    isAdv -> if (isDark) UsOpenGold.copy(alpha = 0.35f) else UsOpenGold.copy(alpha = 0.22f)
                    isServing -> if (isDark) TennisVoltYellow.copy(alpha = 0.22f) else TennisVoltYellow.copy(alpha = 0.18f)
                    isTb -> if (isDark) Color(0xFF1E3A8A).copy(alpha = 0.6f) else UsOpenCourtBlue.copy(alpha = 0.15f)
                    else -> if (isDark) Color(0xFF1E3A8A).copy(alpha = 0.5f) else UsOpenCourtBlue.copy(alpha = 0.12f)
                }

                val pillTextColor = when {
                    isAdv -> if (isDark) UsOpenGold else UsOpenNavyDark
                    isServing -> if (isDark) TennisVoltYellow else Color(0xFF3F6212)
                    isTb -> if (isDark) Color(0xFF93C5FD) else UsOpenCourtBlue
                    else -> if (isDark) Color(0xFF93C5FD) else UsOpenCourtBlue
                }

                val pillBorderModifier = if (isServing) {
                    Modifier.border(
                        1.dp,
                        if (isDark) TennisVoltYellow.copy(alpha = 0.85f) else Color(0xFF65A30D),
                        RoundedCornerShape(6.dp)
                    )
                } else Modifier

                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(pillBorderModifier)
                        .background(pillBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isServing) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(pillTextColor)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text = displayGameScore,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = pillTextColor,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
