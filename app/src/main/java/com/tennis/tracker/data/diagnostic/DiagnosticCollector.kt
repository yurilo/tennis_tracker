package com.tennis.tracker.data.diagnostic

import android.content.Context
import android.os.Build
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.bugreport.AppSettingsSnapshot
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.model.bugreport.MatchContextSnapshot
import com.tennis.tracker.domain.model.bugreport.ScoreboardDiagnosticState
import com.tennis.tracker.ui.util.AdaptiveLayoutUtils
import com.tennis.tracker.ui.viewmodel.ScoreboardUiState

/**
 * Diagnostic telemetry aggregator capturing hardware, application, scoreboard,
 * user settings, and contextual match state into a unified [DiagnosticSnapshot].
 *
 * Fully safe for headless unit tests without an active Android Context or Activity.
 */
class DiagnosticCollector(
    private val breadcrumbBuffer: DiagnosticBreadcrumbBuffer = DiagnosticBreadcrumbBuffer.getInstance()
) {

    /**
     * Captures a complete, privacy-safe diagnostic snapshot.
     *
     * @param context Optional Android Context for extracting package & screen metrics.
     * @param scoreboardState The current active ScoreboardUiState.
     * @param settings Snapshot of active user AppSettings.
     * @param targetMatch Optional Match metadata when reporting from a specific match card or sheet.
     * @param overrideWidthDp Optional test hook overriding screen width in dp.
     * @param overrideHeightDp Optional test hook overriding screen height in dp.
     * @return Immutable [DiagnosticSnapshot] ready for formatting and submission.
     */
    fun collect(
        context: Context?,
        scoreboardState: ScoreboardUiState,
        settings: AppSettings,
        targetMatch: Match? = null,
        overrideWidthDp: Int? = null,
        overrideHeightDp: Int? = null
    ): DiagnosticSnapshot {
        // 1. App Version & Build
        var appVersion = "1.0.25"
        var versionCode = 26
        if (context != null) {
            runCatching {
                val pm = context.packageManager
                if (pm != null) {
                    val pInfo = pm.getPackageInfo(context.packageName, 0)
                    if (pInfo != null) {
                        pInfo.versionName?.let { appVersion = it }
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }
                    }
                }
            }
        }

        // 2. Form Factor & Window Width Bucket
        val widthDp = overrideWidthDp
            ?: runCatching { context?.resources?.configuration?.screenWidthDp }.getOrNull()
            ?: 412
        val heightDp = overrideHeightDp
            ?: runCatching { context?.resources?.configuration?.screenHeightDp }.getOrNull()
            ?: 915

        val formFactor = AdaptiveLayoutUtils.resolveFormFactor(widthDp).name
        val screenWidthBucket = AdaptiveLayoutUtils.resolveWindowWidthSize(widthDp).name

        // 3. Hardware & OS metadata
        val osVersion = runCatching { Build.VERSION.RELEASE ?: "unknown" }.getOrDefault("unknown")
        val apiLevel = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)
        val deviceModel = runCatching { Build.MODEL ?: "unknown" }.getOrDefault("unknown")
        val deviceManufacturer = runCatching { Build.MANUFACTURER ?: "unknown" }.getOrDefault("unknown")

        // 4. Scoreboard State Snapshot
        val tournamentIdOrName = if (scoreboardState.currentTournament.id == "all") {
            "All Tournaments"
        } else {
            scoreboardState.currentTournament.id.ifBlank { scoreboardState.currentTournament.displayName }
        }

        val scoreboardDiagnostic = ScoreboardDiagnosticState(
            tourFilter = scoreboardState.selectedTierFilter.name,
            currentTournament = tournamentIdOrName,
            matchTypeFilter = scoreboardState.selectedMatchTypeFilter.name,
            matchFilter = scoreboardState.selectedFilter.name,
            isOffline = scoreboardState.isOffline,
            searchActive = scoreboardState.searchQuery.isNotBlank(),
            searchQuery = scoreboardState.searchQuery.takeIf { it.isNotBlank() },
            totalMatches = scoreboardState.allMatches.size,
            filteredMatches = scoreboardState.filteredMatches.size,
            liveMatches = scoreboardState.liveCount,
            followedMatches = scoreboardState.followedCount
        )

        // 5. AppSettings Snapshot
        val hasCustomEndpoint = settings.telemetryEndpoint.isNotBlank() &&
            settings.telemetryEndpoint != AppSettings.DEFAULT_TELEMETRY_ENDPOINT

        val settingsDiagnostic = AppSettingsSnapshot(
            autoRefreshInterval = settings.autoRefreshInterval.name,
            matchRetention = settings.matchRetention.name,
            widgetSyncMode = settings.widgetSyncMode.name,
            defaultTournament = settings.defaultTournament.name,
            telemetryEnabled = settings.telemetryEnabled,
            hasCustomEndpoint = hasCustomEndpoint
        )

        // 6. Contextual Match Metadata (if present)
        val matchSnapshot = targetMatch?.let { match ->
            MatchContextSnapshot(
                matchId = match.id,
                tournamentName = match.tournamentName,
                tournamentId = match.tournament?.id ?: if (match.tournamentName.isNotBlank()) {
                    match.tournamentName.lowercase().replace(" ", "_")
                } else null,
                eventGrouping = match.eventGrouping,
                effectiveTour = match.effectiveTour.name,
                effectiveCategory = match.effectiveCategory.name,
                state = match.state.name,
                court = match.displayCourt ?: match.court,
                venue = match.venue.takeIf { it.isNotBlank() },
                roundName = match.roundName.takeIf { it.isNotBlank() },
                statusDetail = match.statusDetail.takeIf { it.isNotBlank() }
            )
        }

        // 7. Recent Breadcrumbs
        val breadcrumbs = breadcrumbBuffer.getRecentBreadcrumbs()

        return DiagnosticSnapshot(
            appVersion = appVersion,
            versionCode = versionCode,
            formFactor = formFactor,
            screenWidthBucket = screenWidthBucket,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            osVersion = osVersion,
            apiLevel = apiLevel,
            deviceModel = deviceModel,
            deviceManufacturer = deviceManufacturer,
            scoreboardState = scoreboardDiagnostic,
            settingsSnapshot = settingsDiagnostic,
            matchContext = matchSnapshot,
            breadcrumbs = breadcrumbs,
            timestamp = System.currentTimeMillis()
        )
    }
}
