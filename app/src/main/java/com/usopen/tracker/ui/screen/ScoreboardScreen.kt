package com.usopen.tracker.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usopen.tracker.domain.model.DeepLink
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.ui.theme.EspnRed
import com.usopen.tracker.ui.theme.UsOpenGold
import com.usopen.tracker.ui.theme.UsOpenNavy
import com.usopen.tracker.ui.theme.UsOpenNavyDark
import com.usopen.tracker.ui.viewmodel.MatchFilter
import com.usopen.tracker.ui.viewmodel.ScoreboardViewModel
import com.usopen.tracker.ui.widget.FollowedMatchesWidgetProvider
import com.usopen.tracker.util.IntentLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreboardScreen(
    viewModel: ScoreboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val selectedMatchForStats by viewModel.selectedMatchForStats.collectAsState()
    val statsState by viewModel.statsUiState.collectAsState()
    val selectedMatchForH2H by viewModel.selectedMatchForH2H.collectAsState()
    val h2hUiState by viewModel.h2hUiState.collectAsState()
    val context = LocalContext.current

    var showAboutDialog by remember { mutableStateOf(false) }

    val packageInfo = remember(context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Throwable) {
            null
        }
    }
    val appVersion = packageInfo?.versionName ?: "1.0.3"
    val appBuild = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        packageInfo?.longVersionCode?.toString() ?: "4"
    } else {
        @Suppress("DEPRECATION")
        packageInfo?.versionCode?.toString() ?: "4"
    }

    LaunchedEffect(Unit) {
        viewModel.initFollowManager(context)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "US OPEN",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TRACKER",
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp,
                            color = UsOpenGold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable { showAboutDialog = true }
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v$appVersion",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.95f)
                            )
                        }
                        if (state.liveCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(EspnRed, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${state.liveCount} LIVE",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAboutDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "About US Open Tracker",
                            tint = UsOpenGold
                        )
                    }
                    IconButton(
                        onClick = {
                            val requested = FollowedMatchesWidgetProvider.requestPinWidget(context)
                            if (!requested) {
                                Toast.makeText(
                                    context,
                                    "To add the widget, touch & hold your home screen and choose 'US Open Watchlist'",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = "Pin Widget to Desktop",
                            tint = UsOpenGold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(UsOpenGold, androidx.compose.foundation.shape.CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Auto 1m",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(onClick = { viewModel.loadMatches(forceRefresh = true) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = UsOpenNavy
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Offline Indicator Banner if applicable
            if (state.isOffline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3CD))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF856404),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Offline Mode — Showing cached tournament schedule and scores",
                        fontSize = 12.sp,
                        color = Color(0xFF856404),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Search Bar Section
            SearchBarSection(
                query = state.searchQuery,
                onQueryChange = { viewModel.onSearchQueryChange(it) },
                onClearQuery = { viewModel.clearSearchQuery() },
                resultCount = state.filteredMatches.size
            )

            // Filter Chips Row
            FilterChipsRow(
                selectedFilter = state.selectedFilter,
                allCount = state.allMatches.size,
                liveCount = state.allMatches.count { it.state == MatchState.LIVE },
                followedCount = state.followedCount,
                upcomingCount = state.allMatches.count { it.state == MatchState.UPCOMING },
                finishedCount = state.allMatches.count { it.state == MatchState.FINISHED },
                onFilterSelected = { viewModel.setFilter(it) }
            )

            // Content Area: Loading, Empty, or Match List
            if (state.isLoading && state.allMatches.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = UsOpenNavy)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading US Open matches…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (state.filteredMatches.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        if (state.searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No matches found for \"${state.searchQuery}\"",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Try searching by another player, country, or stadium",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.clearSearchQuery() },
                                colors = ButtonDefaults.buttonColors(containerColor = UsOpenNavy)
                            ) {
                                Text("Clear Search")
                            }
                        } else if (state.selectedFilter == MatchFilter.FOLLOWED) {
                            Text(
                                text = "⭐",
                                fontSize = 36.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No Followed Matches Yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap the ⭐ Follow button on any match to track it here and on your home screen widget.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.setFilter(MatchFilter.ALL) },
                                colors = ButtonDefaults.buttonColors(containerColor = UsOpenNavy)
                            ) {
                                Text("Browse All Matches", color = Color.White)
                            }
                        } else {
                            Text(
                                text = "No matches found in this category.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
                ) {
                    items(
                        items = state.filteredMatches,
                        key = { it.id }
                    ) { match ->
                        MatchCard(
                            match = match,
                            isFollowed = state.followedMatchIds.contains(match.id),
                            onFollowClick = { matchToToggle ->
                                viewModel.toggleFollow(context, matchToToggle.id)
                            },
                            onWatchClick = { _ ->
                                viewModel.launchWatch(context, match)
                            },
                            onHighlightsClick = { matchToHighlight ->
                                viewModel.launchHighlights(context, matchToHighlight)
                            },
                            onStatsClick = { matchToInspect ->
                                viewModel.openMatchStats(matchToInspect)
                            },
                            onH2HClick = { matchToInspect ->
                                viewModel.openH2H(matchToInspect)
                            }
                        )
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet for Live Statistics (shown on-demand only)
    val matchForStats = selectedMatchForStats
    if (matchForStats != null) {
        MatchStatsSheet(
            match = matchForStats,
            statsState = statsState,
            onDismiss = { viewModel.closeMatchStats() },
            onPeriodSelected = { periodIndex -> viewModel.selectStatsPeriod(periodIndex) },
            onRefresh = { viewModel.refreshMatchStats() },
            onH2HClick = { matchToInspect ->
                viewModel.openH2H(matchToInspect)
            }
        )
    }

    // Modal Bottom Sheet for Head-to-Head (H2H) History (shown on-demand only)
    val matchForH2H = selectedMatchForH2H
    if (matchForH2H != null) {
        H2HBottomSheet(
            match = matchForH2H,
            uiState = h2hUiState,
            onDismiss = { viewModel.closeH2H() },
            onRefresh = { viewModel.refreshH2H() },
            onSurfaceSelected = { surface -> viewModel.selectH2HSurface(surface) }
        )
    }

    if (showAboutDialog) {
        val isDark = isSystemInDarkTheme()
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🎾",
                        fontSize = 22.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "US Open Tracker",
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) UsOpenGold else UsOpenNavy
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "App Version",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "v$appVersion (Build $appBuild)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) UsOpenGold else UsOpenNavy
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Real-Time Grand Slam Experience",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• 🎾 Live Serving Ball indicator beside active server\n• ⚡ Point-by-point live game scores (0, 15, 30, 40, Adv, TB)\n• 📊 On-demand Head-to-Head (H2H) & Match Statistics\n• 📱 Desktop Watchlist Widget with 1-min live sync\n• 📺 Direct ESPN Watch & Replay stream launching\n• ▶️ Official YouTube Match Highlights for completed matches",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UsOpenNavy
                    )
                ) {
                    Text("Close", color = Color.White)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SearchBarSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    resultCount: Int,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val brandColor = if (isDark) UsOpenGold else UsOpenNavy

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            placeholder = {
                Text(
                    text = "Search players, countries, rounds, venues...",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = brandColor,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = brandColor,
                unfocusedIndicatorColor = if (isDark) Color(0xFF334155) else Color(0xFFCBD5E1)
            )
        )

        if (query.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (resultCount == 1) "1 match found" else "$resultCount matches found",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = brandColor,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun FilterChipsRow(
    selectedFilter: MatchFilter,
    allCount: Int,
    liveCount: Int,
    followedCount: Int,
    upcomingCount: Int,
    finishedCount: Int,
    onFilterSelected: (MatchFilter) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val selectedBg = if (isDark) UsOpenGold else UsOpenNavy
    val selectedText = if (isDark) UsOpenNavyDark else Color.White

    val filters = listOf(
        MatchFilter.ALL to allCount,
        MatchFilter.LIVE to liveCount,
        MatchFilter.FOLLOWED to followedCount,
        MatchFilter.UPCOMING to upcomingCount,
        MatchFilter.FINISHED to finishedCount
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((filter, count) in filters) {
            val isSelected = selectedFilter == filter
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        text = "${filter.label} ($count)",
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = selectedBg,
                    selectedLabelColor = selectedText
                )
            )
        }
    }
}
