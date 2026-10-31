package com.tennis.tracker.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.bugreport.BugReportCategory
import com.tennis.tracker.domain.model.bugreport.DiagnosticSnapshot
import com.tennis.tracker.domain.model.bugreport.MatchContextSnapshot
import com.tennis.tracker.ui.theme.CardBorder
import com.tennis.tracker.ui.theme.TennisCourtBlue
import com.tennis.tracker.ui.theme.TennisGold
import com.tennis.tracker.ui.theme.TennisGreen
import com.tennis.tracker.ui.theme.TennisNavy
import com.tennis.tracker.ui.theme.TennisNavyDark
import com.tennis.tracker.ui.theme.TennisTrackerTheme
import com.tennis.tracker.ui.theme.UsOpenCourtBlue
import com.tennis.tracker.ui.theme.UsOpenGold
import com.tennis.tracker.ui.theme.UsOpenNavy
import com.tennis.tracker.ui.theme.UsOpenNavyDark
import com.tennis.tracker.ui.viewmodel.BugReportUiState
import com.tennis.tracker.ui.viewmodel.BugReportViewModel
import com.tennis.tracker.ui.viewmodel.ScoreboardUiState

/**
 * Material 3 Modal Bottom Sheet for filing in-app bug reports with scrubbed diagnostic snapshots.
 *
 * Responsively centers at a maximum width of 640dp on large/laptop screens and supports
 * full keyboard ime padding, field validation, scrubbed diagnostic preview, and fallback actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportBottomSheet(
    uiState: BugReportUiState,
    onCategorySelected: (BugReportCategory) -> Unit,
    onSummaryChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onReproductionStepsChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCopyFallback: () -> Unit,
    onOpenBrowserFallback: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()
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
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header Bar
            val targetMatch = (uiState as? BugReportUiState.Ready)?.snapshot?.matchContext
            BugReportHeader(
                onDismiss = onDismiss,
                matchContext = targetMatch,
                isDark = isDark
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = cardBorderColor
            )

            // State-driven Content
            when (uiState) {
                is BugReportUiState.CollectingDiagnostics,
                BugReportUiState.Idle -> {
                    BugReportLoadingContent(
                        message = "Collecting diagnostic snapshot…",
                        isDark = isDark
                    )
                }

                is BugReportUiState.Ready -> {
                    BugReportFormContent(
                        state = uiState,
                        isSubmitting = false,
                        onCategorySelected = onCategorySelected,
                        onSummaryChanged = onSummaryChanged,
                        onDescriptionChanged = onDescriptionChanged,
                        onReproductionStepsChanged = onReproductionStepsChanged,
                        onSubmit = onSubmit,
                        isDark = isDark
                    )
                }

                is BugReportUiState.Submitting -> {
                    BugReportSubmittingContent(
                        message = uiState.progressMessage,
                        isDark = isDark
                    )
                }

                is BugReportUiState.Success -> {
                    BugReportSuccessContent(
                        issueNumber = uiState.issueNumber,
                        issueUrl = uiState.issueUrl,
                        onOpenIssue = onOpenBrowserFallback,
                        onDismiss = onDismiss,
                        isDark = isDark
                    )
                }

                is BugReportUiState.FallbackRequired -> {
                    BugReportFallbackContent(
                        reason = uiState.reason,
                        copiedToClipboard = uiState.copiedToClipboard,
                        onCopy = onCopyFallback,
                        onOpenBrowser = onOpenBrowserFallback,
                        onDismiss = onDismiss,
                        isDark = isDark
                    )
                }

                is BugReportUiState.Error -> {
                    BugReportErrorContent(
                        message = uiState.message,
                        onRetry = onSubmit,
                        onDismiss = onDismiss,
                        isDark = isDark
                    )
                }
            }
        }
    }
}

/**
 * Stateful overload integrating [BugReportViewModel].
 */
@Composable
fun BugReportBottomSheet(
    viewModel: BugReportViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    BugReportBottomSheet(
        uiState = uiState,
        onCategorySelected = viewModel::onCategorySelected,
        onSummaryChanged = viewModel::onSummaryChanged,
        onDescriptionChanged = viewModel::onDescriptionChanged,
        onReproductionStepsChanged = viewModel::onReproductionStepsChanged,
        onSubmit = { viewModel.submitReport(context = context) },
        onCopyFallback = { viewModel.onCopyFallbackClicked(context = context) },
        onOpenBrowserFallback = {
            if (uiState is BugReportUiState.Success) {
                viewModel.onOpenIssueUrlClicked(context = context)
            } else {
                viewModel.onOpenBrowserFallbackClicked(context = context)
            }
        },
        onDismiss = onDismiss,
        modifier = modifier
    )
}

/**
 * Contextual overload for direct use in [ScoreboardScreen].
 */
@Composable
fun BugReportBottomSheet(
    targetMatch: Match? = null,
    scoreboardState: ScoreboardUiState? = null,
    settings: AppSettings? = null,
    viewModel: BugReportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(targetMatch) {
        viewModel.startReport(
            scoreboardState = scoreboardState ?: ScoreboardUiState(),
            targetMatch = targetMatch,
            context = context
        )
    }

    BugReportBottomSheet(
        viewModel = viewModel,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

// =============================================================================
// Sub-Composables
// =============================================================================

@Composable
private fun BugReportHeader(
    onDismiss: () -> Unit,
    matchContext: MatchContextSnapshot? = null,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val titleColor = if (isDark) UsOpenGold else UsOpenNavy

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Report an Issue",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "File a GitHub issue with zero-PII diagnostic logs attached",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            if (matchContext != null) {
                Spacer(modifier = Modifier.height(6.dp))
                ContextualMatchBadge(matchContext, isDark)
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ContextualMatchBadge(
    match: MatchContextSnapshot,
    isDark: Boolean
) {
    val bg = if (isDark) UsOpenNavyDark else Color(0xFFF1F5F9)
    val border = if (isDark) UsOpenCourtBlue.copy(alpha = 0.4f) else UsOpenCourtBlue.copy(alpha = 0.3f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "🎾 Match: ${match.eventGrouping.ifBlank { match.tournamentName }} • ${match.effectiveTour}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDark) UsOpenGold else UsOpenCourtBlue
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BugReportCategoryChips(
    selectedCategory: BugReportCategory,
    onCategorySelected: (BugReportCategory) -> Unit,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    val activeBg = if (isDark) UsOpenGold else UsOpenNavy
    val activeText = if (isDark) UsOpenNavyDark else Color.White

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Category *",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (category in BugReportCategory.entries) {
                val isSelected = category == selectedCategory
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelected(category) },
                    label = {
                        Text(
                            text = "${category.icon} ${category.displayName}",
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = activeBg,
                        selectedLabelColor = activeText,
                        containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9),
                        labelColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = if (isDark) Color(0xFF334155) else CardBorder,
                        selectedBorderColor = Color.Transparent
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = selectedCategory.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            fontSize = 11.sp
        )
    }
}

@Composable
fun BugReportFormContent(
    state: BugReportUiState.Ready,
    isSubmitting: Boolean,
    onCategorySelected: (BugReportCategory) -> Unit,
    onSummaryChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onReproductionStepsChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Category Selector
        BugReportCategoryChips(
            selectedCategory = state.selectedCategory,
            onCategorySelected = onCategorySelected,
            isDark = isDark
        )

        // 2. Summary (Single line, 120 chars max)
        OutlinedTextField(
            value = state.summary,
            onValueChange = { input ->
                onSummaryChanged(input.take(120))
            },
            label = { Text("Summary *") },
            placeholder = { Text("e.g. Live score stopped updating for Alcaraz match") },
            singleLine = true,
            isError = state.summaryError != null,
            supportingText = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (state.summaryError != null) {
                        Text(
                            text = state.summaryError,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Text(
                        text = "${state.summary.length} / 120",
                        fontSize = 11.sp,
                        color = if (state.summary.length >= 110) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 3. Description (Multi line, minLines 3)
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChanged,
            label = { Text("Description *") },
            placeholder = { Text("Describe what happened, what you expected, and any relevant details…") },
            minLines = 3,
            maxLines = 6,
            isError = state.descriptionError != null,
            supportingText = {
                if (state.descriptionError != null) {
                    Text(
                        text = state.descriptionError,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                } else {
                    Text(
                        text = "Markdown supported. Zero-PII scrubbing will redact tokens and personal data.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 4. Optional Reproduction Steps
        OutlinedTextField(
            value = state.reproductionSteps,
            onValueChange = onReproductionStepsChanged,
            label = { Text("Reproduction Steps (Optional)") },
            placeholder = { Text("1. Go to WTA filter\n2. Open match stats\n3. Tap back") },
            minLines = 2,
            maxLines = 4,
            supportingText = {
                Text(
                    text = "Steps to help developers consistently reproduce the behavior.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 5. Expandable Diagnostic Preview Accordion
        BugReportDiagnosticAccordion(
            snapshot = state.snapshot,
            isDark = isDark
        )

        // 6. Submit Button
        Button(
            onClick = onSubmit,
            enabled = !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDark) UsOpenGold else UsOpenNavy,
                contentColor = if (isDark) UsOpenNavyDark else Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = if (isDark) UsOpenNavyDark else Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Submitting to GitHub…", fontWeight = FontWeight.Bold)
            } else {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Submit Bug Report", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BugReportDiagnosticAccordion(
    snapshot: DiagnosticSnapshot,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    val cardBg = if (isDark) Color(0xFF0F172A) else Color(0xFFF8FAFC)
    val cardBorder = if (isDark) Color(0xFF334155) else Color(0xFFE2E8F0)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Accordion Title Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = TennisGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Diagnostic Snapshot Preview",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Redacted hardware, scoreboard & breadcrumbs",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TennisGreen.copy(alpha = 0.15f))
                            .border(1.dp, TennisGreen.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Zero PII",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TennisGreen
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 12.dp)
                ) {
                    HorizontalDivider(color = cardBorder, modifier = Modifier.padding(bottom = 8.dp))

                    // View Toggle (Markdown vs JSON)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showRawJson) "Machine-Readable JSON" else "Formatted Markdown",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { showRawJson = false },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (!showRawJson) (if (isDark) UsOpenGold else UsOpenNavy) else Color.Gray
                                )
                            ) {
                                Text("Markdown", fontSize = 11.sp, fontWeight = if (!showRawJson) FontWeight.Bold else FontWeight.Normal)
                            }
                            TextButton(
                                onClick = { showRawJson = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (showRawJson) (if (isDark) UsOpenGold else UsOpenNavy) else Color.Gray
                                )
                            ) {
                                Text("JSON", fontSize = 11.sp, fontWeight = if (showRawJson) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    // Content Box
                    val contentText = remember(snapshot, showRawJson) {
                        if (showRawJson) {
                            runCatching { snapshot.toJson().toString(2) }.getOrDefault("{}")
                        } else {
                            snapshot.toFormattedMarkdown()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF030712) else Color(0xFFF1F5F9))
                            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                text = contentText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BugReportSuccessContent(
    issueNumber: Int,
    issueUrl: String,
    onOpenIssue: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF064E3B).copy(alpha = 0.35f) else Color(0xFFECFDF5)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDark) TennisGreen else Color(0xFFA7F3D0)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = TennisGreen,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Bug Report Created!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color(0xFF6EE7B7) else Color(0xFF065F46)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Issue #$issueNumber was opened on yurilo/tennis_tracker with your diagnostic bundle attached.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenIssue,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) UsOpenGold else UsOpenNavy
                    )
                ) {
                    Icon(Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View on GitHub")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
fun BugReportFallbackContent(
    reason: String,
    copiedToClipboard: Boolean,
    onCopy: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean = isSystemInDarkTheme(),
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF451A03).copy(alpha = 0.4f) else Color(0xFFFFFBEB)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDark) TennisGold else Color(0xFFFDE68A)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = TennisGold,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Manual Submission Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isDark) TennisGold else Color(0xFF92400E)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Direct relay submission was unavailable ($reason). You can copy the complete report and open GitHub to submit in your browser.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (copiedToClipboard) TennisGreen else (if (isDark) UsOpenGold else UsOpenNavy)
                    )
                ) {
                    Icon(
                        imageVector = if (copiedToClipboard) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (copiedToClipboard) "Copied to Clipboard! ✓" else "Copy Full Report to Clipboard",
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onOpenBrowser,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Pre-filled GitHub Issue in Browser")
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun BugReportLoadingContent(
    message: String,
    isDark: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = if (isDark) UsOpenGold else UsOpenNavy,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun BugReportSubmittingContent(
    message: String,
    isDark: Boolean
) {
    BugReportLoadingContent(message = message, isDark = isDark)
}

@Composable
private fun BugReportErrorContent(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Submission Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Retry")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

// =============================================================================
// Previews
// =============================================================================

@Preview(name = "Bug Report Sheet - Phone Light", showBackground = true, widthDp = 390)
@Composable
fun BugReportBottomSheetPhoneLightPreview() {
    TennisTrackerTheme(darkTheme = false) {
        BugReportBottomSheet(
            uiState = BugReportUiState.Ready(
                snapshot = DiagnosticSnapshot(),
                selectedCategory = BugReportCategory.SCORE_LIVE_DATA,
                summary = "Score stopped refreshing in 3rd set",
                description = "During the tiebreak, point updates ceased.",
                reproductionSteps = "1. Open match\n2. Wait for tiebreak"
            ),
            onCategorySelected = {},
            onSummaryChanged = {},
            onDescriptionChanged = {},
            onReproductionStepsChanged = {},
            onSubmit = {},
            onCopyFallback = {},
            onOpenBrowserFallback = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "Bug Report Sheet - Laptop Dark", showBackground = true, widthDp = 840)
@Composable
fun BugReportBottomSheetLaptopDarkPreview() {
    TennisTrackerTheme(darkTheme = true) {
        BugReportBottomSheet(
            uiState = BugReportUiState.Ready(
                snapshot = DiagnosticSnapshot(formFactor = "LAPTOP"),
                selectedCategory = BugReportCategory.UI_LAYOUT,
                summary = "Columns misaligned on 1440p monitor",
                description = "Cards wrap unexpectedly when window is maximized."
            ),
            onCategorySelected = {},
            onSummaryChanged = {},
            onDescriptionChanged = {},
            onReproductionStepsChanged = {},
            onSubmit = {},
            onCopyFallback = {},
            onOpenBrowserFallback = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "Bug Report Sheet - Success State", showBackground = true, widthDp = 400)
@Composable
fun BugReportBottomSheetSuccessPreview() {
    TennisTrackerTheme {
        BugReportSuccessContent(
            issueNumber = 42,
            issueUrl = "https://github.com/yurilo/tennis_tracker/issues/42",
            onOpenIssue = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "Bug Report Sheet - Fallback State", showBackground = true, widthDp = 400)
@Composable
fun BugReportBottomSheetFallbackPreview() {
    TennisTrackerTheme {
        BugReportFallbackContent(
            reason = "Device is offline",
            copiedToClipboard = true,
            onCopy = {},
            onOpenBrowser = {},
            onDismiss = {}
        )
    }
}
