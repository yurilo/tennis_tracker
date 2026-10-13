package com.tennis.tracker.ui.screen

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.ui.theme.CardBorder
import com.tennis.tracker.ui.theme.EspnRed
import com.tennis.tracker.ui.theme.TextPrimary
import com.tennis.tracker.ui.theme.TextSecondary
import com.tennis.tracker.ui.theme.TennisCourtBlue
import com.tennis.tracker.ui.theme.TennisGold
import com.tennis.tracker.ui.theme.TennisNavy
import com.tennis.tracker.ui.theme.TennisGreen
import com.tennis.tracker.ui.widget.FollowedMatchesWidgetProvider
import com.tennis.tracker.ui.widget.WidgetAutoSyncManager

/**
 * Material 3 Modal Settings Bottom Sheet.
 *
 * Stateless Composable encapsulating the 6 centralized application preference domains:
 * 1. Scoreboard & Live Refresh (frequency & battery impact)
 * 2. Followed Matches Retention (auto-cleanup policy)
 * 3. Home Screen Widget Sync (sync policy & desktop pinning)
 * 4. Startup View (default tournament category)
 * 5. Privacy & Telemetry (zero-PII toggle & HTTPS endpoint configuration)
 * 6. Reset to Defaults (destructive action guarded by confirmation dialog)
 *
 * Emits user modifications immediately through hoisted event lambdas for instant persistence (zero Save button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    settings: AppSettings,
    onAutoRefreshIntervalChanged: (AutoRefreshInterval) -> Unit,
    onFollowedRetentionChanged: (MatchRetentionPolicy) -> Unit,
    onWidgetSyncModeChanged: (WidgetSyncMode) -> Unit,
    onDefaultTournamentTierChanged: (StartupTournamentTier) -> Unit,
    onTelemetryToggled: (Boolean) -> Unit,
    onTelemetryEndpointChanged: (String) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val primaryAccent = if (isDark) TennisGold else TennisNavy
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.White
    val cardBorderColor = if (isDark) Color(0xFF334155) else CardBorder

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Settings & Preferences",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = primaryAccent
                    )
                    Text(
                        text = "Customize live refresh, retention, widget & privacy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = cardBorderColor
            )

            // =================================================================
            // 1. Scoreboard & Live Refresh
            // =================================================================
            SettingsSectionHeader(
                icon = Icons.Default.Timer,
                title = "Scoreboard & Live Refresh",
                subtitle = "Controls how frequently live scores, games, and match statuses refresh.",
                accentColor = TennisCourtBlue
            )

            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AutoRefreshOptionCard(
                    title = "30 seconds",
                    badge = "🔥 Real-Time",
                    description = "High frequency point-by-point tracking. Higher battery and network usage.",
                    selected = settings.autoRefreshInterval == AutoRefreshInterval.INTERVAL_30S,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onAutoRefreshIntervalChanged(AutoRefreshInterval.INTERVAL_30S) }
                )
                AutoRefreshOptionCard(
                    title = "60 seconds (1 min)",
                    badge = "⭐ Recommended",
                    description = "Ideal balance between score freshness and battery conservation. (Default)",
                    selected = settings.autoRefreshInterval == AutoRefreshInterval.INTERVAL_60S,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onAutoRefreshIntervalChanged(AutoRefreshInterval.INTERVAL_60S) }
                )
                AutoRefreshOptionCard(
                    title = "2 minutes",
                    badge = "⚡ Battery Saver",
                    description = "Conserves battery; best for metered or low-power connections.",
                    selected = settings.autoRefreshInterval == AutoRefreshInterval.INTERVAL_2M,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onAutoRefreshIntervalChanged(AutoRefreshInterval.INTERVAL_2M) }
                )
                AutoRefreshOptionCard(
                    title = "Manual only (Off)",
                    badge = "✋ Manual",
                    description = "No background polling. Refresh manually via the top refresh button.",
                    selected = settings.autoRefreshInterval == AutoRefreshInterval.OFF,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onAutoRefreshIntervalChanged(AutoRefreshInterval.OFF) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = cardBorderColor
            )

            // =================================================================
            // 2. Followed Matches Retention
            // =================================================================
            SettingsSectionHeader(
                icon = Icons.Default.Star,
                title = "Followed Match Retention",
                subtitle = "How long completed matches stay in your Following tab and widget.",
                accentColor = TennisGold
            )

            // Informative Auto-Cleanup Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TennisCourtBlue.copy(alpha = if (isDark) 0.15f else 0.08f))
                    .border(1.dp, TennisCourtBlue.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = TennisCourtBlue,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Finished matches remain visible in your Following ⭐ tab and home screen widget for this duration, then cleanly auto-expire to keep your watchlist current and uncluttered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }

            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RetentionOptionCard(
                    title = "12 hours",
                    description = "Fast auto-cleanup shortly after match conclusion.",
                    selected = settings.matchRetention == MatchRetentionPolicy.HOURS_12,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onFollowedRetentionChanged(MatchRetentionPolicy.HOURS_12) }
                )
                RetentionOptionCard(
                    title = "24 hours (1 day)",
                    badge = "Default",
                    description = "Review final stats and highlights through the next morning.",
                    selected = settings.matchRetention == MatchRetentionPolicy.HOURS_24,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onFollowedRetentionChanged(MatchRetentionPolicy.HOURS_24) }
                )
                RetentionOptionCard(
                    title = "48 hours (2 days)",
                    description = "Extended retention. Keep weekend tournament results across multiple days.",
                    selected = settings.matchRetention == MatchRetentionPolicy.HOURS_48,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onFollowedRetentionChanged(MatchRetentionPolicy.HOURS_48) }
                )
                RetentionOptionCard(
                    title = "7 days (1 week)",
                    description = "Full tournament week. Retain all followed matches throughout the entire tournament.",
                    selected = settings.matchRetention == MatchRetentionPolicy.DAYS_7,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onFollowedRetentionChanged(MatchRetentionPolicy.DAYS_7) }
                )
                RetentionOptionCard(
                    title = "Never auto-clear",
                    badge = "Manual Only",
                    description = "Completed matches remain in your watchlist indefinitely until manually removed.",
                    selected = settings.matchRetention == MatchRetentionPolicy.NEVER,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onFollowedRetentionChanged(MatchRetentionPolicy.NEVER) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = cardBorderColor
            )

            // =================================================================
            // 3. Home Screen Widget Sync
            // =================================================================
            SettingsSectionHeader(
                icon = Icons.Default.Widgets,
                title = "Home Screen Widget Sync",
                subtitle = "Background synchronization policy for the Android Desktop Watchlist widget.",
                accentColor = TennisGreen
            )

            // Widget Pin / Status Banner
            val isWidgetPinned = remember(context) { WidgetAutoSyncManager.hasActiveWidgets(context) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cardBg)
                    .border(1.dp, cardBorderColor, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isWidgetPinned) TennisGreen else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isWidgetPinned) "Widget Active on Desktop" else "No Desktop Widget Detected",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Pin the Tennis Watchlist widget to monitor scores from your home screen.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val requested = FollowedMatchesWidgetProvider.requestPinWidget(context)
                            if (!requested) {
                                Toast.makeText(
                                    context,
                                    "To add widget, long-press home screen and choose 'Tennis Watchlist'",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = primaryAccent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Pin Widget", fontSize = 12.sp)
                    }
                }
            }

            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WidgetSyncOptionCard(
                    title = "Adaptive",
                    badge = "Recommended",
                    description = "Dynamic polling: respects app auto-refresh setting for active matches, 15m when idle/results.",
                    selected = settings.widgetSyncMode == WidgetSyncMode.ADAPTIVE,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onWidgetSyncModeChanged(WidgetSyncMode.ADAPTIVE) }
                )
                WidgetSyncOptionCard(
                    title = "15 minutes",
                    description = "Constant periodic sync every 15 minutes regardless of match state.",
                    selected = settings.widgetSyncMode == WidgetSyncMode.INTERVAL_15M,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onWidgetSyncModeChanged(WidgetSyncMode.INTERVAL_15M) }
                )
                WidgetSyncOptionCard(
                    title = "30 minutes",
                    description = "Low-power sync every 30 minutes to minimize background wakeups.",
                    selected = settings.widgetSyncMode == WidgetSyncMode.INTERVAL_30M,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onWidgetSyncModeChanged(WidgetSyncMode.INTERVAL_30M) }
                )
                WidgetSyncOptionCard(
                    title = "Disabled",
                    description = "Widget updates only when the main app is open or manually refreshed.",
                    selected = settings.widgetSyncMode == WidgetSyncMode.DISABLED,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onWidgetSyncModeChanged(WidgetSyncMode.DISABLED) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = cardBorderColor
            )

            // =================================================================
            // 4. Startup View (Tournament Tier Filter)
            // =================================================================
            SettingsSectionHeader(
                icon = Icons.Default.SportsTennis,
                title = "Startup View",
                subtitle = "Preferred tournament category automatically selected when Tennis Tracker opens.",
                accentColor = TennisNavy
            )

            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StartupTierOptionCard(
                    icon = "🎾",
                    title = "All Tournaments",
                    badge = "Default",
                    description = "Show all active Grand Slam, ATP, and WTA matches across tournaments.",
                    selected = settings.defaultTournament == StartupTournamentTier.ALL,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onDefaultTournamentTierChanged(StartupTournamentTier.ALL) }
                )
                StartupTierOptionCard(
                    icon = "🏆",
                    title = "Grand Slams",
                    description = "Filter exclusively to Major Grand Slam tournaments (US Open, Wimbledon, etc.).",
                    selected = settings.defaultTournament == StartupTournamentTier.GRAND_SLAMS,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onDefaultTournamentTierChanged(StartupTournamentTier.GRAND_SLAMS) }
                )
                StartupTierOptionCard(
                    icon = "🔷",
                    title = "ATP Tour",
                    description = "Focus on men's professional tour tournaments.",
                    selected = settings.defaultTournament == StartupTournamentTier.ATP_TOUR,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onDefaultTournamentTierChanged(StartupTournamentTier.ATP_TOUR) }
                )
                StartupTierOptionCard(
                    icon = "🟣",
                    title = "WTA Tour",
                    description = "Focus on women's professional tour tournaments.",
                    selected = settings.defaultTournament == StartupTournamentTier.WTA_TOUR,
                    isDark = isDark,
                    cardBorderColor = cardBorderColor,
                    onClick = { onDefaultTournamentTierChanged(StartupTournamentTier.WTA_TOUR) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = cardBorderColor
            )

            // =================================================================
            // 5. Privacy & Telemetry
            // =================================================================
            SettingsSectionHeader(
                icon = Icons.Default.Security,
                title = "Privacy & Telemetry",
                subtitle = "Zero-PII usage diagnostics and transparency.",
                accentColor = TennisCourtBlue
            )

            // Telemetry Switch Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cardBg)
                    .border(1.dp, cardBorderColor, RoundedCornerShape(10.dp))
                    .clickable { onTelemetryToggled(!settings.telemetryEnabled) }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Anonymous Active Session Heartbeat",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Helps maintain active user counts for the open status dashboard.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = settings.telemetryEnabled,
                        onCheckedChange = { onTelemetryToggled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TennisNavy,
                            checkedTrackColor = TennisGold
                        )
                    )
                }
            }

            // Transparency Disclosures Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.3f else 0.4f))
                    .border(1.dp, cardBorderColor, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Privacy Guarantees",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                    )
                    Text(
                        text = "• Zero personal data: No names, emails, Google accounts, or device identifiers collected.\n• Rate-limited: Sends at most 1 heartbeat every 30 minutes.\n• GDPR & CCPA compliant: Opting out immediately purges local anonymous UUID and halts all network transmissions.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 15.sp
                    )
                }
            }

            // Custom HTTPS Telemetry Endpoint TextField
            var endpointInput by remember(settings.telemetryEndpoint) { mutableStateOf(settings.telemetryEndpoint) }
            val isValidHttps = endpointInput.trim().startsWith("https://", ignoreCase = true)
            val isCustomEndpoint = endpointInput.trim() != AppSettings.DEFAULT_TELEMETRY_ENDPOINT

            OutlinedTextField(
                value = endpointInput,
                onValueChange = { newValue ->
                    endpointInput = newValue
                    if (newValue.trim().startsWith("https://", ignoreCase = true)) {
                        onTelemetryEndpointChanged(newValue.trim())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                label = { Text("Custom Telemetry Endpoint URL") },
                singleLine = true,
                isError = endpointInput.isNotBlank() && !isValidHttps,
                supportingText = {
                    if (endpointInput.isNotBlank() && !isValidHttps) {
                        Text("Only secure HTTPS URLs (https://) are accepted", color = EspnRed)
                    } else {
                        Text("Only HTTPS URLs are allowed. Leave as default for community dashboard.")
                    }
                },
                trailingIcon = {
                    if (isCustomEndpoint) {
                        IconButton(
                            onClick = {
                                endpointInput = AppSettings.DEFAULT_TELEMETRY_ENDPOINT
                                onTelemetryEndpointChanged(AppSettings.DEFAULT_TELEMETRY_ENDPOINT)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset Endpoint URL",
                                tint = primaryAccent
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = cardBorderColor
            )

            // =================================================================
            // 6. Reset to Defaults
            // =================================================================
            SettingsSectionHeader(
                icon = Icons.Default.Restore,
                title = "Reset All Preferences",
                subtitle = "Restore all settings to their canonical initial defaults.",
                accentColor = EspnRed
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cardBg)
                    .border(1.dp, cardBorderColor, RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Factory Defaults",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Your saved watchlist and followed matches will NOT be deleted.",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { showResetConfirmDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = EspnRed
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(EspnRed.copy(alpha = 0.6f))
                        )
                    ) {
                        Text("Reset Defaults", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // Confirmation AlertDialog for Reset Action
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = EspnRed
                )
            },
            title = {
                Text(
                    text = "Reset All Preferences?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will restore live refresh (60s), match retention (24h), widget sync (Adaptive), startup view (All Tournaments), and telemetry (Enabled) to factory defaults.\n\nYour followed matches and watchlist will remain intact."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetToDefaults()
                        showResetConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EspnRed)
                ) {
                    Text("Reset to Defaults", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Reusable section header with icon, title, and descriptive subtitle.
 */
@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            lineHeight = 16.sp
        )
    }
}

/**
 * Selectable card for Live Auto-Refresh options.
 */
@Composable
private fun AutoRefreshOptionCard(
    title: String,
    badge: String,
    description: String,
    selected: Boolean,
    isDark: Boolean,
    cardBorderColor: Color,
    onClick: () -> Unit
) {
    val activeBorder = if (isDark) TennisGold else TennisCourtBlue
    val cardBg = if (selected) {
        if (isDark) TennisGold.copy(alpha = 0.12f) else TennisCourtBlue.copy(alpha = 0.08f)
    } else {
        if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) activeBorder else cardBorderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = if (isDark) TennisGold else TennisNavy
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.12f) else TennisNavy.copy(alpha = 0.08f)
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Selectable card for Followed Match Retention options.
 */
@Composable
private fun RetentionOptionCard(
    title: String,
    badge: String? = null,
    description: String,
    selected: Boolean,
    isDark: Boolean,
    cardBorderColor: Color,
    onClick: () -> Unit
) {
    val activeBorder = if (isDark) TennisGold else TennisCourtBlue
    val cardBg = if (selected) {
        if (isDark) TennisGold.copy(alpha = 0.12f) else TennisCourtBlue.copy(alpha = 0.08f)
    } else {
        if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) activeBorder else cardBorderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = if (isDark) TennisGold else TennisNavy
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.12f) else TennisNavy.copy(alpha = 0.08f)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = badge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Selectable card for Home Screen Widget Sync options.
 */
@Composable
private fun WidgetSyncOptionCard(
    title: String,
    badge: String? = null,
    description: String,
    selected: Boolean,
    isDark: Boolean,
    cardBorderColor: Color,
    onClick: () -> Unit
) {
    val activeBorder = if (isDark) TennisGold else TennisCourtBlue
    val cardBg = if (selected) {
        if (isDark) TennisGold.copy(alpha = 0.12f) else TennisCourtBlue.copy(alpha = 0.08f)
    } else {
        if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) activeBorder else cardBorderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = if (isDark) TennisGold else TennisNavy
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.12f) else TennisNavy.copy(alpha = 0.08f)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = badge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Selectable card for Startup Tournament Tier options.
 */
@Composable
private fun StartupTierOptionCard(
    icon: String,
    title: String,
    badge: String? = null,
    description: String,
    selected: Boolean,
    isDark: Boolean,
    cardBorderColor: Color,
    onClick: () -> Unit
) {
    val activeBorder = if (isDark) TennisGold else TennisCourtBlue
    val cardBg = if (selected) {
        if (isDark) TennisGold.copy(alpha = 0.12f) else TennisCourtBlue.copy(alpha = 0.08f)
    } else {
        if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) activeBorder else cardBorderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = if (isDark) TennisGold else TennisNavy
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$icon $title",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.12f) else TennisNavy.copy(alpha = 0.08f)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = badge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
