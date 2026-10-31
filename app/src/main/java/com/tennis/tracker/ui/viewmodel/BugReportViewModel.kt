package com.tennis.tracker.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tennis.tracker.data.bugreport.BugReportSubmissionService
import com.tennis.tracker.data.bugreport.BugReportSubmissionServiceContract
import com.tennis.tracker.data.bugreport.SubmissionResult
import com.tennis.tracker.data.diagnostic.DiagnosticCollector
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.bugreport.BugReport
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.repository.SettingsRepository
import com.tennis.tracker.util.ClipboardHelper
import com.tennis.tracker.util.IntentLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Finite state machine representing the lifecycle of an in-app bug report session.
 */
sealed class BugReportUiState {

    /**
     * Resting state when the bug report dialog is closed or cleared.
     */
    data object Idle : BugReportUiState()

    /**
     * Telemetry collection in progress (gathering system properties, scoreboard snapshot, breadcrumbs).
     */
    data object CollectingDiagnostics : BugReportUiState()

    /**
     * Bug report form is ready for user input and inspection.
     */
    data class Ready(
        val snapshot: DiagnosticSnapshot,
        val selectedCategory: BugReportCategory = BugReportCategory.SCORE_LIVE_DATA,
        val summary: String = "",
        val description: String = "",
        val reproductionSteps: String = "",
        val summaryError: String? = null,
        val descriptionError: String? = null
    ) : BugReportUiState() {
        val isValid: Boolean
            get() = summary.trim().isNotBlank() && description.trim().isNotBlank()
    }

    /**
     * Network dispatch in progress (Worker relay or direct GitHub REST API).
     */
    data class Submitting(
        val progressMessage: String = "Submitting bug report..."
    ) : BugReportUiState()

    /**
     * Report successfully created as an issue on GitHub.
     */
    data class Success(
        val issueNumber: Int,
        val issueUrl: String
    ) : BugReportUiState()

    /**
     * Network dispatch failed or offline; user should copy full Markdown body and open fallback URL in browser.
     */
    data class FallbackRequired(
        val fallbackUrl: String,
        val fullMarkdownBody: String,
        val reason: String,
        val copiedToClipboard: Boolean = false
    ) : BugReportUiState()

    /**
     * Unrecoverable error during initialization or unhandled failure.
     */
    data class Error(
        val message: String
    ) : BugReportUiState()
}

/**
 * ViewModel managing the lifecycle, validation, diagnostic aggregation, and network/fallback
 * dispatch for the in-app bug reporting workflow.
 */
class BugReportViewModel(
    private val diagnosticCollector: DiagnosticCollector = DiagnosticCollector(),
    private val submissionService: BugReportSubmissionServiceContract = BugReportSubmissionService(),
    private val settingsRepository: SettingsRepository = ScoreboardViewModel.defaultSettingsRepository(),
    private val clipboardHelper: ClipboardHelper = ClipboardHelper,
    private val intentLauncher: IntentLauncher = IntentLauncher,
    private val clipboardDelegate: ((Context?, String, String) -> Boolean)? = null,
    private val browserLauncherDelegate: ((Context?, String) -> Boolean)? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<BugReportUiState>(BugReportUiState.Idle)
    val uiState: StateFlow<BugReportUiState> = _uiState.asStateFlow()

    private var diagnosticJob: Job? = null
    private var submissionJob: Job? = null

    /**
     * Initializes the bug report session by gathering a fresh [DiagnosticSnapshot].
     *
     * @param scoreboardState Active scoreboard UI state.
     * @param targetMatch Optional match metadata if triggered contextually.
     * @param context Optional Android context for screen and version metadata.
     * @param initialCategory Optional category override.
     */
    fun startReport(
        scoreboardState: ScoreboardUiState,
        targetMatch: Match? = null,
        context: Context? = null,
        initialCategory: BugReportCategory? = null
    ) {
        val currentState = _uiState.value
        // Guard in-flight submission: do not disturb active submission coroutines
        if (currentState is BugReportUiState.Submitting) {
            return
        }

        diagnosticJob?.cancel()
        submissionJob?.cancel()
        submissionJob = null

        val existingReady = currentState as? BugReportUiState.Ready
        // Only show loading screen if transitioning from uninitialized state (e.g. Idle, Error, Success)
        if (existingReady == null) {
            _uiState.value = BugReportUiState.CollectingDiagnostics
        }

        diagnosticJob = viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.getSettings()
                val snapshot = diagnosticCollector.collect(
                    context = context,
                    scoreboardState = scoreboardState,
                    settings = currentSettings,
                    targetMatch = targetMatch
                )

                val latestState = _uiState.value
                // Guard: If submission started while diagnostics were being collected, do not overwrite
                if (latestState is BugReportUiState.Submitting) {
                    return@launch
                }

                if (latestState is BugReportUiState.Ready) {
                    // Preserve existing user inputs and category while updating the diagnostic snapshot
                    _uiState.value = latestState.copy(
                        snapshot = snapshot,
                        selectedCategory = initialCategory ?: latestState.selectedCategory
                    )
                } else {
                    val category = initialCategory
                        ?: if (targetMatch != null) BugReportCategory.SCORE_LIVE_DATA else BugReportCategory.OTHER

                    _uiState.value = BugReportUiState.Ready(
                        snapshot = snapshot,
                        selectedCategory = category,
                        summary = "",
                        description = "",
                        reproductionSteps = "",
                        summaryError = null,
                        descriptionError = null
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.value = BugReportUiState.Error(
                    message = t.message ?: "Failed to collect diagnostic snapshot"
                )
            }
        }
    }

    /**
     * Convenience entry point to initiate a bug report.
     */
    fun initiateReport(
        scoreboardState: ScoreboardUiState = ScoreboardUiState(),
        targetMatch: Match? = null,
        context: Context? = null,
        initialCategory: BugReportCategory? = null
    ) {
        startReport(scoreboardState, targetMatch, context, initialCategory)
    }

    /**
     * Direct initialization with a pre-computed [DiagnosticSnapshot], useful for testing
     * or immediate pre-bundled reports.
     */
    fun startReportWithSnapshot(
        snapshot: DiagnosticSnapshot,
        initialCategory: BugReportCategory = BugReportCategory.SCORE_LIVE_DATA
    ) {
        diagnosticJob?.cancel()
        submissionJob?.cancel()
        submissionJob = null
        _uiState.value = BugReportUiState.Ready(
            snapshot = snapshot,
            selectedCategory = initialCategory,
            summary = "",
            description = "",
            reproductionSteps = "",
            summaryError = null,
            descriptionError = null
        )
    }

    /**
     * Updates the user-selected issue category chip.
     */
    fun onCategorySelected(category: BugReportCategory) {
        val current = _uiState.value
        if (current is BugReportUiState.Ready) {
            _uiState.value = current.copy(selectedCategory = category)
        }
    }

    /**
     * Updates the summary field and clears validation errors once non-blank.
     */
    fun onSummaryChanged(summary: String) {
        val current = _uiState.value
        if (current is BugReportUiState.Ready) {
            val clamped = summary.take(120)
            val error = if (clamped.isNotBlank()) null else current.summaryError
            _uiState.value = current.copy(summary = clamped, summaryError = error)
        }
    }

    /**
     * Updates the description field and clears validation errors once non-blank.
     */
    fun onDescriptionChanged(description: String) {
        val current = _uiState.value
        if (current is BugReportUiState.Ready) {
            val error = if (description.isNotBlank()) null else current.descriptionError
            _uiState.value = current.copy(description = description, descriptionError = error)
        }
    }

    /**
     * Updates the optional reproduction steps field.
     */
    fun onReproductionStepsChanged(steps: String) {
        val current = _uiState.value
        if (current is BugReportUiState.Ready) {
            _uiState.value = current.copy(reproductionSteps = steps)
        }
    }

    /**
     * Submits the bug report. Validates required fields, then attempts dispatch via Worker relay
     * or direct GitHub REST API. Gracefully transitions to [BugReportUiState.FallbackRequired]
     * if offline, rate limited, or unconfigured.
     */
    fun submitReport(
        context: Context? = null,
        isOnline: Boolean = true
    ) {
        val current = _uiState.value
        if (current !is BugReportUiState.Ready) return

        val summaryTrimmed = current.summary.trim()
        val descriptionTrimmed = current.description.trim()

        val summaryBlank = summaryTrimmed.isBlank()
        val descriptionBlank = descriptionTrimmed.isBlank()

        if (summaryBlank || descriptionBlank) {
            _uiState.value = current.copy(
                summaryError = if (summaryBlank) "Summary cannot be blank" else null,
                descriptionError = if (descriptionBlank) "Description cannot be blank" else null
            )
            return
        }

        _uiState.value = BugReportUiState.Submitting(progressMessage = "Submitting bug report...")

        val report = BugReport(
            category = current.selectedCategory,
            summary = summaryTrimmed,
            description = descriptionTrimmed,
            reproductionSteps = current.reproductionSteps.trim().takeIf { it.isNotBlank() }
        )

        submissionJob?.cancel()
        submissionJob = viewModelScope.launch {
            try {
                val settings = settingsRepository.getSettings()
                val result = submissionService.submitReport(
                    report = report,
                    snapshot = current.snapshot,
                    endpoint = settings.bugReportEndpoint.takeIf { it.isNotBlank() },
                    customToken = settings.githubToken?.takeIf { it.isNotBlank() },
                    isOnline = isOnline
                )

                when (result) {
                    is SubmissionResult.Success -> {
                        _uiState.value = BugReportUiState.Success(
                            issueNumber = result.issueNumber,
                            issueUrl = result.issueUrl
                        )
                    }
                    is SubmissionResult.FallbackRequired -> {
                        val autoCopied = copyToClipboardInternal(context, result.fullMarkdownBody)
                        _uiState.value = BugReportUiState.FallbackRequired(
                            fallbackUrl = result.fallbackUrl,
                            fullMarkdownBody = result.fullMarkdownBody,
                            reason = result.reason,
                            copiedToClipboard = autoCopied
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _uiState.value = BugReportUiState.Error(
                    message = t.message ?: "Unexpected submission failure"
                )
            }
        }
    }

    /**
     * User action to manually copy full Markdown diagnostic bundle when in [BugReportUiState.FallbackRequired].
     */
    fun onCopyFallbackClicked(context: Context? = null): Boolean {
        val current = _uiState.value
        if (current is BugReportUiState.FallbackRequired) {
            val copied = copyToClipboardInternal(context, current.fullMarkdownBody)
            if (copied) {
                _uiState.value = current.copy(copiedToClipboard = true)
            }
            return copied
        }
        return false
    }

    /**
     * User action to open the pre-filled GitHub issue creation URL in a verified browser.
     */
    fun onOpenBrowserFallbackClicked(context: Context? = null): Boolean {
        val current = _uiState.value
        if (current is BugReportUiState.FallbackRequired) {
            return launchBrowserInternal(context, current.fallbackUrl)
        }
        return false
    }

    /**
     * User action to view the created GitHub issue in browser upon successful creation.
     */
    fun onOpenIssueUrlClicked(context: Context? = null): Boolean {
        val current = _uiState.value
        if (current is BugReportUiState.Success) {
            return launchBrowserInternal(context, current.issueUrl)
        }
        return false
    }

    /**
     * Cancels any active submission coroutine.
     */
    fun cancelSubmission() {
        submissionJob?.cancel()
        submissionJob = null
    }

    /**
     * Resets the ViewModel back to resting [BugReportUiState.Idle] state.
     */
    fun reset() {
        diagnosticJob?.cancel()
        diagnosticJob = null
        submissionJob?.cancel()
        submissionJob = null
        _uiState.value = BugReportUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        diagnosticJob?.cancel()
        submissionJob?.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    private fun copyToClipboardInternal(context: Context?, text: String): Boolean {
        if (clipboardDelegate != null) {
            return clipboardDelegate.invoke(context, "Tennis Tracker Bug Report", text)
        }
        if (context != null) {
            return clipboardHelper.copyToClipboard(
                context = context,
                label = "Tennis Tracker Bug Report",
                text = text,
                toastMessage = "Diagnostic bug report copied to clipboard"
            )
        }
        return false
    }

    private fun launchBrowserInternal(context: Context?, url: String): Boolean {
        if (browserLauncherDelegate != null) {
            return browserLauncherDelegate.invoke(context, url)
        }
        if (context != null) {
            val res = intentLauncher.launchWebUrl(context, url)
            return res !is com.tennis.tracker.util.IntentLaunchResult.Failed
        }
        return false
    }
}
