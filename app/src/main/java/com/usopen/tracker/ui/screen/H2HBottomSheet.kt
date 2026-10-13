package com.usopen.tracker.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.TennisProfileProvider
import com.usopen.tracker.ui.component.DoublesProfilePickerDialog
import com.usopen.tracker.util.IntentLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.H2HSummary
import com.usopen.tracker.domain.model.H2HUiState
import com.usopen.tracker.domain.model.HistoricalMatch
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.ui.component.PlayerAvatar
import com.usopen.tracker.ui.theme.UsOpenCourtBlue
import com.usopen.tracker.ui.theme.UsOpenGold
import com.usopen.tracker.ui.theme.UsOpenNavy
import com.usopen.tracker.util.ClipboardHelper
import com.usopen.tracker.util.CopyTextFormatter

private data class H2HColors(
    val player1Color: Color,
    val player2Color: Color,
    val headerTitleColor: Color,
    val cardBgColor: Color,
    val cardBorderColor: Color,
    val dividerColor: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H2HBottomSheet(
    match: Match,
    uiState: H2HUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSurfaceSelected: (CourtSurface?) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()

    val colors = H2HColors(
        player1Color = if (isDark) Color(0xFF60A5FA) else UsOpenCourtBlue,
        player2Color = if (isDark) Color(0xFFFBBF24) else UsOpenNavy,
        headerTitleColor = if (isDark) UsOpenGold else UsOpenNavy,
        cardBgColor = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC),
        cardBorderColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
        dividerColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            H2HHeader(
                match = match,
                summary = uiState.summary,
                selectedSurface = uiState.selectedSurface,
                onDismiss = onDismiss,
                onRefresh = onRefresh,
                isLoading = uiState.isLoading,
                colors = colors
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.dividerColor)
            )

            // Content
            when {
                uiState.isLoading && uiState.summary == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = colors.headerTitleColor)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Loading head-to-head records...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                uiState.errorMessage != null && uiState.summary == null -> {
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = "No Head-to-Head Records Available",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = uiState.errorMessage,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                DisableSelection {
                                    androidx.compose.material3.Button(
                                        onClick = onRefresh,
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = colors.headerTitleColor,
                                            contentColor = if (isDark) UsOpenNavy else Color.White
                                        )
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                }

                uiState.summary != null -> {
                    val summary = uiState.summary
                    val filteredMatches = uiState.filteredMatches

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. Tale of the Tape Hero Card
                        item {
                            TaleOfTheTapeCard(
                                summary = summary,
                                match = match,
                                colors = colors,
                                isDark = isDark
                            )
                        }

                        // 2. Surface Breakdown Matrix
                        item {
                            SurfaceBreakdownRow(summary = summary, colors = colors, isDark = isDark)
                        }

                        // 3. Surface Filter Chips
                        item {
                            SurfaceFilterRow(
                                summary = summary,
                                selectedSurface = uiState.selectedSurface,
                                onSurfaceSelected = onSurfaceSelected,
                                colors = colors,
                                isDark = isDark
                            )
                        }

                        // 4. Section Title
                        item {
                            SelectionContainer {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (uiState.selectedSurface != null) {
                                            "${uiState.selectedSurface.displayName.uppercase()} ENCOUNTERS (${filteredMatches.size})"
                                        } else {
                                            "MATCH TIMELINE (${filteredMatches.size})"
                                        },
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.sp,
                                        color = colors.headerTitleColor.copy(alpha = 0.85f)
                                    )
                                    Text(
                                        text = "Most recent first",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }

                        // 5. Match Timeline Cards
                        if (filteredMatches.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No encounters found on this surface.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        } else {
                            items(filteredMatches, key = { it.id }) { historicalMatch ->
                                HistoricalMatchCard(
                                    match = historicalMatch,
                                    p1Name = summary.player1.name,
                                    p2Name = summary.player2.name,
                                    colors = colors,
                                    isDark = isDark
                                )
                            }
                        }

                        // Footnote
                        item {
                            Text(
                                text = "Official ATP/WTA Head-to-Head records • Tennis Tracker",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun H2HHeader(
    match: Match,
    summary: H2HSummary? = null,
    selectedSurface: CourtSurface? = null,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    colors: H2HColors
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    SelectionContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "HEAD-TO-HEAD",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp,
                        color = colors.headerTitleColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.cardBorderColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "H2H",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.headerTitleColor
                        )
                    }
                }
                val courtText = match.displayCourt ?: match.venue.takeIf { !it.equals("New York, USA", ignoreCase = true) }
                val h2hSubtext = if (!courtText.isNullOrBlank()) {
                    "${match.eventGrouping} • $courtText • Career Rivalry History"
                } else {
                    "${match.eventGrouping} • Career Rivalry History"
                }
                Text(
                    text = h2hSubtext,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }

            DisableSelection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            ClipboardHelper.copyToClipboard(
                                context = context,
                                label = "H2H Summary",
                                text = CopyTextFormatter.formatH2HSummary(match, summary, selectedSurface),
                                toastMessage = "Copied Head-to-Head summary"
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Head-to-Head Summary",
                            tint = colors.headerTitleColor
                        )
                    }
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val text = CopyTextFormatter.formatH2HSummary(match, summary, selectedSurface)
                            val title = "Head-to-Head: ${match.player1.name} vs ${match.player2.name}"
                            ClipboardHelper.shareText(context = context, title = title, text = text)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Head-to-Head Summary",
                            tint = colors.headerTitleColor
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.headerTitleColor
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh H2H",
                                tint = colors.headerTitleColor
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaleOfTheTapeCard(
    summary: H2HSummary,
    match: Match,
    colors: H2HColors,
    isDark: Boolean
) {
    val context = LocalContext.current
    val p1Tour = TennisProfileProvider.resolveTour(summary.player1, match)
    val p2Tour = TennisProfileProvider.resolveTour(summary.player2, match)
    var selectedDoublesCompetitor by remember { mutableStateOf<Competitor?>(null) }

    if (selectedDoublesCompetitor != null) {
        DoublesProfilePickerDialog(
            competitor = selectedDoublesCompetitor!!,
            match = match,
            onDismiss = { selectedDoublesCompetitor = null }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorderColor)
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                // Player 1 Side
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PlayerAvatar(
                        competitor = summary.player1,
                        size = 52.dp,
                        accentColor = colors.player1Color,
                        showTourBadge = true,
                        tour = p1Tour,
                        onClick = {
                            if (summary.player1.isDoubles) {
                                selectedDoublesCompetitor = summary.player1
                            } else {
                                Toast.makeText(
                                    context,
                                    "Opening ${summary.player1.name} ${p1Tour.displayName} Profile...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                IntentLauncher.launchPlayerProfile(context, summary.player1, match)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (summary.player1.isDoubles) {
                        val ind = summary.player1.individualPlayers
                        val p1a = ind.getOrNull(0)?.name ?: summary.player1.name
                        val p1b = ind.getOrNull(1)?.name ?: ""
                        if (summary.player1.seed != null && summary.player1.seed > 0) {
                            Text(
                                text = "(${summary.player1.seed})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.player1Color
                            )
                        }
                        Text(
                            text = p1a,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = colors.player1Color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        if (p1b.isNotEmpty()) {
                            Text(
                                text = p1b,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = colors.player1Color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = summary.player1.displayNameWithSeed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = colors.player1Color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                    val flag1 = summary.player1.countryFlag
                    if (flag1 != null) {
                        Text(
                            text = if (summary.player1.isDoubles) flag1 else "$flag1 ${summary.player1.country ?: ""}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                // Center Score
                Column(
                    modifier = Modifier.weight(1.2f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${summary.p1Wins}",
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            color = colors.player1Color
                        )
                        Text(
                            text = "  -  ",
                            fontWeight = FontWeight.Light,
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "${summary.p2Wins}",
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            color = colors.player2Color
                        )
                    }
                    Text(
                        text = "TOTAL MEETINGS: ${summary.totalMatches}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = colors.headerTitleColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Ratio Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.cardBorderColor)
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            if (summary.p1WinPercentage > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(summary.p1WinPercentage.coerceAtLeast(0.01f))
                                        .fillMaxHeight()
                                        .background(colors.player1Color)
                                )
                            }
                            if (summary.p2WinPercentage > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(summary.p2WinPercentage.coerceAtLeast(0.01f))
                                        .fillMaxHeight()
                                        .background(colors.player2Color)
                                )
                            }
                        }
                    }
                }

                // Player 2 Side
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PlayerAvatar(
                        competitor = summary.player2,
                        size = 52.dp,
                        accentColor = colors.player2Color,
                        showTourBadge = true,
                        tour = p2Tour,
                        onClick = {
                            if (summary.player2.isDoubles) {
                                selectedDoublesCompetitor = summary.player2
                            } else {
                                Toast.makeText(
                                    context,
                                    "Opening ${summary.player2.name} ${p2Tour.displayName} Profile...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                IntentLauncher.launchPlayerProfile(context, summary.player2, match)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (summary.player2.isDoubles) {
                        val ind = summary.player2.individualPlayers
                        val p2a = ind.getOrNull(0)?.name ?: summary.player2.name
                        val p2b = ind.getOrNull(1)?.name ?: ""
                        if (summary.player2.seed != null && summary.player2.seed > 0) {
                            Text(
                                text = "(${summary.player2.seed})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.player2Color
                            )
                        }
                        Text(
                            text = p2a,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = colors.player2Color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        if (p2b.isNotEmpty()) {
                            Text(
                                text = p2b,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = colors.player2Color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Text(
                            text = summary.player2.displayNameWithSeed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = colors.player2Color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                    val flag2 = summary.player2.countryFlag
                    if (flag2 != null) {
                        Text(
                            text = if (summary.player2.isDoubles) flag2 else "${summary.player2.country ?: ""} $flag2",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            val isDoublesMatch = summary.player1.isDoubles || summary.player2.isDoubles
            val hintText = if (isDoublesMatch) {
                "Tap avatar to view official player profiles ↗"
            } else {
                "Tap avatar to view official ${p1Tour.displayName} / ${p2Tour.displayName} profile ↗"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.cardBorderColor.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = hintText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
            }
        }
    }
}

@Composable
private fun SurfaceBreakdownRow(
    summary: H2HSummary,
    colors: H2HColors,
    isDark: Boolean
) {
    SelectionContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SurfaceStatCard(
                label = "Hard",
                icon = "🟦",
                p1Score = summary.hardWinsP1,
                p2Score = summary.hardWinsP2,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
            SurfaceStatCard(
                label = "Clay",
                icon = "🟧",
                p1Score = summary.clayWinsP1,
                p2Score = summary.clayWinsP2,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
            SurfaceStatCard(
                label = "Grass",
                icon = "🟩",
                p1Score = summary.grassWinsP1,
                p2Score = summary.grassWinsP2,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
            SurfaceStatCard(
                label = "Majors",
                icon = "🏆",
                p1Score = summary.grandSlamWinsP1,
                p2Score = summary.grandSlamWinsP2,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SurfaceStatCard(
    label: String,
    icon: String,
    p1Score: Int,
    p2Score: Int,
    colors: H2HColors,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$p1Score - $p2Score",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.headerTitleColor
            )
        }
    }
}

@Composable
private fun SurfaceFilterRow(
    summary: H2HSummary,
    selectedSurface: CourtSurface?,
    onSurfaceSelected: (CourtSurface?) -> Unit,
    colors: H2HColors,
    isDark: Boolean
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SurfaceFilterChip(
                label = "All (${summary.totalMatches})",
                selected = selectedSurface == null,
                onClick = { onSurfaceSelected(null) },
                colors = colors
            )
        }
        item {
            val hardCount = summary.matches.count { it.surface == CourtSurface.HARD }
            SurfaceFilterChip(
                label = "🟦 Hard ($hardCount)",
                selected = selectedSurface == CourtSurface.HARD,
                onClick = { onSurfaceSelected(CourtSurface.HARD) },
                colors = colors
            )
        }
        item {
            val clayCount = summary.matches.count { it.surface == CourtSurface.CLAY }
            SurfaceFilterChip(
                label = "🟧 Clay ($clayCount)",
                selected = selectedSurface == CourtSurface.CLAY,
                onClick = { onSurfaceSelected(CourtSurface.CLAY) },
                colors = colors
            )
        }
        item {
            val grassCount = summary.matches.count { it.surface == CourtSurface.GRASS }
            SurfaceFilterChip(
                label = "🟩 Grass ($grassCount)",
                selected = selectedSurface == CourtSurface.GRASS,
                onClick = { onSurfaceSelected(CourtSurface.GRASS) },
                colors = colors
            )
        }
    }
}

@Composable
private fun SurfaceFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: H2HColors
) {
    val bgColor = if (selected) colors.headerTitleColor else colors.cardBgColor
    val textColor = if (selected) {
        if (isSystemInDarkTheme()) Color(0xFF0A2540) else Color.White
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, if (selected) colors.headerTitleColor else colors.cardBorderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoricalMatchCard(
    match: HistoricalMatch,
    p1Name: String,
    p2Name: String,
    colors: H2HColors,
    isDark: Boolean
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isP1Winner = match.isWinner(p1Name)
    val cardBorder = if (match.isCurrentMatch) {
        androidx.compose.foundation.BorderStroke(1.5.dp, UsOpenGold)
    } else {
        androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorderColor)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { /* card click */ },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    ClipboardHelper.copyToClipboard(
                        context = context,
                        label = "Historical Match",
                        text = CopyTextFormatter.formatHistoricalMatch(match),
                        toastMessage = "Copied historical match result"
                    )
                }
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBgColor),
        border = cardBorder
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Current Match Banner
                if (match.isCurrentMatch) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(UsOpenGold.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = UsOpenGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "CURRENT MATCH • ${match.tournamentName.ifEmpty { "TENNIS" }.uppercase()}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = colors.headerTitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Top Row: Date, Surface Chip, Duration & Copy Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${match.round} • ${match.year}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = colors.headerTitleColor
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Court Surface Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(match.surface.color.copy(alpha = 0.2f))
                                .border(1.dp, match.surface.color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${match.surface.icon} ${match.surface.displayName}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = match.surface.color
                            )
                        }

                        if (match.matchDuration != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = match.matchDuration,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))
                        DisableSelection {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        ClipboardHelper.copyToClipboard(
                                            context = context,
                                            label = "Historical Match",
                                            text = CopyTextFormatter.formatHistoricalMatch(match),
                                            toastMessage = "Copied historical match result"
                                        )
                                    }
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Historical Match Result",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Tournament Name & Venue
                Text(
                    text = match.tournamentName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (match.city != null) "${match.venue}, ${match.city}" else match.venue,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Match Scores Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Winner: ",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = match.winnerName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isP1Winner) colors.player1Color else colors.player2Color
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "🏆", fontSize = 12.sp)
                    }

                    Text(
                        text = match.score,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
