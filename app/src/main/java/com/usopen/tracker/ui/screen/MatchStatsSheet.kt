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
import com.usopen.tracker.ui.component.PlayerAvatar
import com.usopen.tracker.util.IntentLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.PeriodStats
import com.usopen.tracker.domain.model.StatGroup
import com.usopen.tracker.domain.model.StatItem
import com.usopen.tracker.ui.theme.CardBorder
import com.usopen.tracker.ui.theme.LiveIndicatorRed
import com.usopen.tracker.ui.theme.UsOpenCourtBlue
import com.usopen.tracker.ui.theme.UsOpenGold
import com.usopen.tracker.ui.theme.UsOpenNavy
import com.usopen.tracker.ui.theme.UsOpenNavyDark
import com.usopen.tracker.ui.theme.TennisVoltYellow
import com.usopen.tracker.ui.viewmodel.MatchStatsUiState

private data class StatsColors(
    val player1Color: Color,
    val player2Color: Color,
    val headerTitleColor: Color,
    val groupHeaderColor: Color,
    val trackBgColor: Color,
    val dividerColor: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchStatsSheet(
    match: Match,
    statsState: MatchStatsUiState,
    onDismiss: () -> Unit,
    onPeriodSelected: (Int) -> Unit,
    onRefresh: () -> Unit,
    onH2HClick: ((Match) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()

    val statsColors = StatsColors(
        player1Color = if (isDark) Color(0xFF60A5FA) else UsOpenCourtBlue,
        player2Color = if (isDark) Color(0xFFFBBF24) else UsOpenNavy,
        headerTitleColor = if (isDark) UsOpenGold else UsOpenNavy,
        groupHeaderColor = if (isDark) Color(0xFF93C5FD) else UsOpenNavy,
        trackBgColor = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0),
        dividerColor = if (isDark) Color(0xFF334155) else CardBorder
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
                .padding(bottom = 32.dp)
        ) {
            // Sheet Header with Match Title and Dismiss Button
            StatsSheetHeader(
                match = match,
                onDismiss = onDismiss,
                onRefresh = onRefresh,
                isLoading = statsState.isLoading,
                colors = statsColors,
                onH2HClick = onH2HClick
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(statsColors.dividerColor)
            )

            // Dynamic Content
            when {
                statsState.isLoading && statsState.stats == null -> {
                    LoadingView(loadingColor = statsColors.headerTitleColor)
                }

                statsState.errorMessage != null && statsState.stats == null -> {
                    ErrorView(
                        errorMessage = statsState.errorMessage,
                        onRetry = onRefresh,
                        buttonColor = statsColors.headerTitleColor
                    )
                }

                statsState.stats != null -> {
                    val periods = statsState.stats.periods
                    val selectedIndex = statsState.selectedPeriodIndex.coerceIn(0, (periods.size - 1).coerceAtLeast(0))

                    if (periods.size > 1) {
                        PeriodTabs(
                            periods = periods,
                            selectedIndex = selectedIndex,
                            onPeriodSelected = onPeriodSelected,
                            colors = statsColors
                        )
                    }

                    val activePeriod = periods.getOrNull(selectedIndex)
                    if (activePeriod != null) {
                        PeriodStatsContent(
                            period = activePeriod,
                            match = match,
                            colors = statsColors
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSheetHeader(
    match: Match,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    colors: StatsColors,
    onH2HClick: ((Match) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "MATCH STATS",
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    color = colors.headerTitleColor
                )
                if (match.state == MatchState.LIVE) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(LiveIndicatorRed, RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LIVE",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
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
                            contentDescription = "Refresh Stats",
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

        val courtText = match.displayCourt ?: match.venue.takeIf { !it.equals("New York, USA", ignoreCase = true) }
        val venueSubtext = buildString {
            append(match.eventGrouping)
            if (match.roundName.isNotEmpty()) {
                append(" • ")
                append(match.roundName)
            }
            if (!courtText.isNullOrBlank()) {
                append(" • ")
                append(courtText)
            }
        }
        Text(
            text = venueSubtext,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val durationStr = match.getFormattedDuration()
        val durationText = when (match.state) {
            MatchState.LIVE -> durationStr?.let { "Elapsed Time: $it" }
            MatchState.FINISHED -> durationStr?.let { "Match Duration: $it" }
            MatchState.UPCOMING -> match.formattedStartTime?.let { "Scheduled: $it" }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (durationText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.trackBgColor.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Match Time",
                        modifier = Modifier.size(13.dp),
                        tint = colors.headerTitleColor
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = durationText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.headerTitleColor
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (onH2HClick != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.headerTitleColor.copy(alpha = 0.15f))
                        .border(1.dp, colors.headerTitleColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable { onH2HClick(match) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "⚡ H2H Rivalry",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.headerTitleColor
                    )
                }
            }
        }

        val isDark = isSystemInDarkTheme()
        val context = LocalContext.current
        val p1Tour = TennisProfileProvider.resolveTour(match.player1, match)
        val p2Tour = TennisProfileProvider.resolveTour(match.player2, match)
        var selectedDoublesCompetitor by remember { mutableStateOf<Competitor?>(null) }

        if (selectedDoublesCompetitor != null) {
            DoublesProfilePickerDialog(
                competitor = selectedDoublesCompetitor!!,
                match = match,
                onDismiss = { selectedDoublesCompetitor = null }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Players Showcase Card with Profile Photos, Country, Seed & Scores
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC))
                .border(1.dp, if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Player 1 Showcase (Left)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerAvatar(
                        competitor = match.player1,
                        size = 52.dp,
                        accentColor = colors.player1Color,
                        showTourBadge = true,
                        tour = p1Tour,
                        onClick = {
                            if (match.player1.isDoubles) {
                                selectedDoublesCompetitor = match.player1
                            } else {
                                Toast.makeText(
                                    context,
                                    "Opening ${match.player1.name} ${p1Tour.displayName} Profile...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                IntentLauncher.launchPlayerProfile(context, match.player1, match)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (match.player1.isDoubles) {
                            val ind1 = match.player1.individualPlayers
                            val p1a = ind1.getOrNull(0)?.name ?: match.player1.name
                            val p1b = ind1.getOrNull(1)?.name ?: ""
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (match.player1.seed != null && match.player1.seed > 0) {
                                    Text(
                                        text = "(${match.player1.seed})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.player1Color
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                }
                                Text(
                                    text = p1a,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = colors.player1Color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (match.state == MatchState.LIVE && match.player1.isServing) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "🎾",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (p1b.isNotEmpty()) {
                                Text(
                                    text = p1b,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = colors.player1Color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = match.player1.displayNameWithSeed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = colors.player1Color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (match.state == MatchState.LIVE && match.player1.isServing) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "🎾",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val flag1 = match.player1.countryFlag
                            val country1 = match.player1.country
                            if (flag1 != null) {
                                Text(
                                    text = flag1,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            if (country1 != null && !match.player1.isDoubles) {
                                Text(
                                    text = country1,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (match.player1.setScores.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = match.player1.setScores.joinToString("  "),
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                // VS Divider / Center Scoreboard
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "VS",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            color = colors.headerTitleColor,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Player 2 Showcase (Right)
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        if (match.player2.isDoubles) {
                            val ind2 = match.player2.individualPlayers
                            val p2a = ind2.getOrNull(0)?.name ?: match.player2.name
                            val p2b = ind2.getOrNull(1)?.name ?: ""
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (match.state == MatchState.LIVE && match.player2.isServing) {
                                    Text(
                                        text = "🎾",
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                if (match.player2.seed != null && match.player2.seed > 0) {
                                    Text(
                                        text = "(${match.player2.seed})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.player2Color
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                }
                                Text(
                                    text = p2a,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = colors.player2Color,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (p2b.isNotEmpty()) {
                                Text(
                                    text = p2b,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = colors.player2Color,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (match.state == MatchState.LIVE && match.player2.isServing) {
                                    Text(
                                        text = "🎾",
                                        fontSize = 12.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = match.player2.displayNameWithSeed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = colors.player2Color,
                                    textAlign = TextAlign.End,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            val flag2 = match.player2.countryFlag
                            val country2 = match.player2.country
                            if (country2 != null && !match.player2.isDoubles) {
                                Text(
                                    text = country2,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            if (flag2 != null) {
                                Text(
                                    text = flag2,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        if (match.player2.setScores.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = match.player2.setScores.joinToString("  "),
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    PlayerAvatar(
                        competitor = match.player2,
                        size = 52.dp,
                        accentColor = colors.player2Color,
                        showTourBadge = true,
                        tour = p2Tour,
                        onClick = {
                            if (match.player2.isDoubles) {
                                selectedDoublesCompetitor = match.player2
                            } else {
                                Toast.makeText(
                                    context,
                                    "Opening ${match.player2.name} ${p2Tour.displayName} Profile...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                IntentLauncher.launchPlayerProfile(context, match.player2, match)
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            val isDoublesMatch = match.player1.isDoubles || match.player2.isDoubles
            val hintText = if (isDoublesMatch) {
                "Tap avatar to view official player profiles ↗"
            } else {
                "Tap avatar to view official ${p1Tour.displayName} / ${p2Tour.displayName} profile ↗"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
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
private fun PeriodTabs(
    periods: List<PeriodStats>,
    selectedIndex: Int,
    onPeriodSelected: (Int) -> Unit,
    colors: StatsColors
) {
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 16.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = colors.headerTitleColor,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = colors.headerTitleColor,
                height = 3.dp
            )
        },
        divider = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.dividerColor)
            )
        }
    ) {
        periods.forEachIndexed { index, period ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onPeriodSelected(index) },
                text = {
                    Text(
                        text = period.label,
                        fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        color = if (selectedIndex == index) colors.headerTitleColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            )
        }
    }
}

@Composable
private fun PeriodStatsContent(
    period: PeriodStats,
    match: Match,
    colors: StatsColors
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Overview Comparison Bars (Key Highlights)
        val keyStats = listOfNotNull(
            period.findItem("Service", "1st Serve Percentage")
                ?: period.findItem("Service", "1st serve %"),
            period.findItem("Winners", "Total Winners")
                ?: period.findItem("Service", "Aces"),
            period.findItem("Unforced Errors", "Total Unforced Errors")
                ?: period.findItem("Service", "Double faults"),
            period.findItem("Return", "Break Points Converted")
                ?: period.findItem("Return", "Break points won"),
            period.findItem("Points", "Total Points Won")
                ?: period.findItem("Points", "Total points")
        )

        if (keyStats.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.dividerColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val context = LocalContext.current
                    val p1Tour = TennisProfileProvider.resolveTour(match.player1, match)
                    val p2Tour = TennisProfileProvider.resolveTour(match.player2, match)
                    var selectedKeyDoublesCompetitor by remember { mutableStateOf<Competitor?>(null) }

                    if (selectedKeyDoublesCompetitor != null) {
                        DoublesProfilePickerDialog(
                            competitor = selectedKeyDoublesCompetitor!!,
                            match = match,
                            onDismiss = { selectedKeyDoublesCompetitor = null }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlayerAvatar(
                                competitor = match.player1,
                                size = if (match.player1.isDoubles) 30.dp else 26.dp,
                                accentColor = colors.player1Color,
                                showBadges = false,
                                onClick = {
                                    if (match.player1.isDoubles) {
                                        selectedKeyDoublesCompetitor = match.player1
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Opening ${match.player1.name} ${p1Tour.displayName} Profile...",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        IntentLauncher.launchPlayerProfile(context, match.player1, match)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = match.player1.lastNameOrShortName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = colors.player1Color
                            )
                        }

                        Text(
                            text = "KEY COMPARISONS",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            color = colors.headerTitleColor.copy(alpha = 0.85f)
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = match.player2.lastNameOrShortName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = colors.player2Color
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            PlayerAvatar(
                                competitor = match.player2,
                                size = if (match.player2.isDoubles) 30.dp else 26.dp,
                                accentColor = colors.player2Color,
                                showBadges = false,
                                onClick = {
                                    if (match.player2.isDoubles) {
                                        selectedKeyDoublesCompetitor = match.player2
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Opening ${match.player2.name} ${p2Tour.displayName} Profile...",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        IntentLauncher.launchPlayerProfile(context, match.player2, match)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    keyStats.forEach { stat ->
                        StatComparisonRow(stat = stat, colors = colors)
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Granular Breakdown Groups
        period.groups.forEach { group ->
            StatGroupCard(group = group, colors = colors)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Data source footnote
        Text(
            text = "Official live analytics powered by US Open Tracker & Sofascore",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun StatGroupCard(
    group: StatGroup,
    colors: StatsColors
) {
    val groupIcon = when {
        group.groupName.contains("Service", ignoreCase = true) -> "🎾"
        group.groupName.contains("Winner", ignoreCase = true) -> "⚡"
        group.groupName.contains("Unforced", ignoreCase = true) -> "⚠️"
        group.groupName.contains("Return", ignoreCase = true) -> "↩️"
        group.groupName.contains("Point", ignoreCase = true) -> "🎯"
        else -> "📊"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.dividerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = groupIcon,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = group.groupName.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = colors.groupHeaderColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            group.items.forEachIndexed { index, item ->
                StatItemRow(item = item, colors = colors)
                if (index < group.items.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .padding(vertical = 6.dp)
                            .background(colors.dividerColor.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItemRow(
    item: StatItem,
    colors: StatsColors
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.homeDisplay,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = colors.player1Color,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.Start
        )

        Text(
            text = item.name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            maxLines = 2
        )

        // Right side (Away / Player 2)
        Text(
            text = item.awayDisplay,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = colors.player2Color,
            modifier = Modifier.width(60.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun StatComparisonRow(
    stat: StatItem,
    colors: StatsColors
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stat.homeDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = colors.player1Color
            )

            Text(
                text = stat.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )

            // Right side (Away / Player 2)
            Text(
                text = stat.awayDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = colors.player2Color
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Dual Visual Comparison Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.trackBgColor)
        ) {
            val clampedRatio = stat.homeRatio.coerceIn(0f, 1f)
            if (clampedRatio > 0f) {
                Box(
                    modifier = Modifier
                        .weight(clampedRatio.coerceAtLeast(0.01f))
                        .height(6.dp)
                        .background(colors.player1Color)
                )
            }
            if (clampedRatio < 1f) {
                Box(
                    modifier = Modifier
                        .weight((1f - clampedRatio).coerceAtLeast(0.01f))
                        .height(6.dp)
                        .background(colors.player2Color)
                )
            }
        }
    }
}

@Composable
private fun LoadingView(loadingColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = loadingColor)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Fetching live match statistics…",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Connecting to US Open & Sofascore data feeds",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ErrorView(
    errorMessage: String,
    onRetry: () -> Unit,
    buttonColor: Color
) {
    val isDark = isSystemInDarkTheme()
    val isNotAvailable = errorMessage.contains("not available", ignoreCase = true) ||
        errorMessage.contains("once the match begins", ignoreCase = true)
    val title = if (isNotAvailable) "Statistics Not Available Yet" else "Unable to load statistics"
    val description = if (isNotAvailable) {
        "Official court telemetry and match statistics have not been recorded or published for this match yet."
    } else {
        errorMessage
    }

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
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = if (isDark) UsOpenNavyDark else Color.White
                )
            ) {
                Text("Retry")
            }
        }
    }
}
