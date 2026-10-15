package com.tennis.tracker.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * High-level target form factor representing either mobile phone or laptop/desktop/tablet.
 */
enum class FormFactor {
    PHONE,
    LAPTOP
}

/**
 * Standard Material 3 window width size classes based on screen width.
 * - COMPACT: < 600 dp (typical phones in portrait)
 * - MEDIUM: 600 dp .. 839 dp (small tablets, foldables unfolded, small laptop windows)
 * - EXPANDED: >= 840 dp (laptops, Chromebooks, desktop monitors, large tablets)
 */
enum class WindowWidthSize {
    COMPACT,
    MEDIUM,
    EXPANDED
}

/**
 * Resolved layout configuration parameters tailored for the active form factor.
 */
data class FormFactorLayoutConfig(
    val formFactor: FormFactor,
    val windowWidthSize: WindowWidthSize,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val gridColumnCount: Int,
    val horizontalScreenPadding: Dp,
    val cardSpacing: Dp,
    val maxContentWidth: Dp?,
    val maxSheetContentWidth: Dp,
    val isCompactButtons: Boolean
) {
    val isPhone: Boolean get() = formFactor == FormFactor.PHONE
    val isLaptop: Boolean get() = formFactor == FormFactor.LAPTOP
}

object AdaptiveLayoutUtils {
    /**
     * Breakpoints according to Material 3 Design Guidelines.
     */
    const val COMPACT_MAX_WIDTH_DP = 599
    const val MEDIUM_MAX_WIDTH_DP = 839
    const val WIDE_DESKTOP_MIN_WIDTH_DP = 1200

    /**
     * Resolves the [WindowWidthSize] based on screen width in dp.
     */
    fun resolveWindowWidthSize(widthDp: Int): WindowWidthSize = when {
        widthDp < 600 -> WindowWidthSize.COMPACT
        widthDp < 840 -> WindowWidthSize.MEDIUM
        else -> WindowWidthSize.EXPANDED
    }

    /**
     * Resolves the primary [FormFactor] based on screen width in dp.
     */
    fun resolveFormFactor(widthDp: Int): FormFactor = when {
        widthDp < 600 -> FormFactor.PHONE
        else -> FormFactor.LAPTOP
    }

    /**
     * Computes the complete [FormFactorLayoutConfig] from screen dimensions.
     * Pure function, easily unit-testable without Android instrumented runners.
     */
    fun computeConfig(widthDp: Int, heightDp: Int): FormFactorLayoutConfig {
        val safeWidth = widthDp.coerceAtLeast(1)
        val safeHeight = heightDp.coerceAtLeast(1)
        val sizeClass = resolveWindowWidthSize(safeWidth)
        val formFactor = resolveFormFactor(safeWidth)

        val columns = when {
            formFactor == FormFactor.PHONE -> 1
            safeWidth >= WIDE_DESKTOP_MIN_WIDTH_DP -> 3
            else -> 2
        }

        val horizontalPadding = if (formFactor == FormFactor.PHONE) 12.dp else 24.dp
        val cardSpacing = if (formFactor == FormFactor.PHONE) 8.dp else 16.dp
        val maxContentWidth = if (formFactor == FormFactor.PHONE) null else 1280.dp
        val maxSheetContentWidth = 640.dp
        val isCompactButtons = formFactor == FormFactor.PHONE || safeWidth < 700

        return FormFactorLayoutConfig(
            formFactor = formFactor,
            windowWidthSize = sizeClass,
            screenWidthDp = safeWidth,
            screenHeightDp = safeHeight,
            gridColumnCount = columns,
            horizontalScreenPadding = horizontalPadding,
            cardSpacing = cardSpacing,
            maxContentWidth = maxContentWidth,
            maxSheetContentWidth = maxSheetContentWidth,
            isCompactButtons = isCompactButtons
        )
    }
}

/**
 * Composable helper returning the current active [FormFactorLayoutConfig] reactively.
 */
@Composable
fun rememberFormFactorLayoutConfig(): FormFactorLayoutConfig {
    val configuration = LocalConfiguration.current
    return AdaptiveLayoutUtils.computeConfig(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp
    )
}
