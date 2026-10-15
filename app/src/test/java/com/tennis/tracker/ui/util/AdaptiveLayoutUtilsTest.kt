package com.tennis.tracker.ui.util

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying responsive layout logic and window size class adaptation
 * across Phone and Laptop form factors.
 */
class AdaptiveLayoutUtilsTest {

    @Test
    fun testPhoneFormFactor_standardDevices() {
        // Standard Android phone resolutions in dp
        val phoneScreens = listOf(
            320 to 480, // Compact older/small phone
            360 to 740, // Standard Galaxy / budget phone
            393 to 852, // Galaxy S23 / iPhone standard
            412 to 915, // Google Pixel 7/8/9
            599 to 900  // Maximum Compact boundary
        )

        for ((width, height) in phoneScreens) {
            val config = AdaptiveLayoutUtils.computeConfig(width, height)

            assertEquals("Width $width should be PHONE", FormFactor.PHONE, config.formFactor)
            assertTrue("Width $width should report isPhone true", config.isPhone)
            assertFalse("Width $width should report isLaptop false", config.isLaptop)
            assertEquals("Width $width should be COMPACT", WindowWidthSize.COMPACT, config.windowWidthSize)
            assertEquals("Phone should have 1 column for width $width", 1, config.gridColumnCount)
            assertEquals("Phone horizontal padding should be 12dp", 12.dp, config.horizontalScreenPadding)
            assertEquals("Phone card spacing should be 8dp", 8.dp, config.cardSpacing)
            assertNull("Phone max content width should be null (full width)", config.maxContentWidth)
            assertTrue("Phone buttons should be compact", config.isCompactButtons)
        }
    }

    @Test
    fun testLaptopFormFactor_mediumScreens() {
        // Foldable unfolded, tablet portrait, small laptop windows
        val mediumScreens = listOf(
            600 to 960,  // Exact boundary 600dp
            700 to 1000, // Small tablet
            768 to 1024, // iPad portrait / foldable
            839 to 1100  // Upper Medium boundary
        )

        for ((width, height) in mediumScreens) {
            val config = AdaptiveLayoutUtils.computeConfig(width, height)

            assertEquals("Width $width should be LAPTOP", FormFactor.LAPTOP, config.formFactor)
            assertFalse("Width $width should report isPhone false", config.isPhone)
            assertTrue("Width $width should report isLaptop true", config.isLaptop)
            assertEquals("Width $width should be MEDIUM", WindowWidthSize.MEDIUM, config.windowWidthSize)
            assertEquals("Medium screen should have 2 columns for width $width", 2, config.gridColumnCount)
            assertEquals("Laptop horizontal padding should be 24dp", 24.dp, config.horizontalScreenPadding)
            assertEquals("Laptop card spacing should be 16dp", 16.dp, config.cardSpacing)
            assertEquals("Laptop max content width should be 1280dp", 1280.dp, config.maxContentWidth)
            assertEquals("Laptop max sheet width should be 640dp", 640.dp, config.maxSheetContentWidth)
        }
    }

    @Test
    fun testLaptopFormFactor_expandedScreens() {
        // Standard laptop, desktop, large tablet landscape
        val expandedScreens = listOf(
            840 to 600,   // Exact boundary 840dp
            1024 to 768,  // Netbook / standard tablet landscape
            1199 to 800   // 2-column upper bound
        )

        for ((width, height) in expandedScreens) {
            val config = AdaptiveLayoutUtils.computeConfig(width, height)

            assertEquals("Width $width should be LAPTOP", FormFactor.LAPTOP, config.formFactor)
            assertTrue("Width $width should report isLaptop true", config.isLaptop)
            assertEquals("Width $width should be EXPANDED", WindowWidthSize.EXPANDED, config.windowWidthSize)
            assertEquals("Expanded screen < 1200dp should have 2 columns", 2, config.gridColumnCount)
            assertEquals(1280.dp, config.maxContentWidth)
            assertEquals(640.dp, config.maxSheetContentWidth)
            assertFalse("Laptop width >= 700dp should not use compact buttons", config.isCompactButtons)
        }
    }

    @Test
    fun testLaptopFormFactor_wideDesktopScreens() {
        // Wide laptop displays and external monitors >= 1200dp
        val wideScreens = listOf(
            1200 to 800,  // Exact 3-column boundary
            1440 to 900,  // MacBook Pro / standard laptop
            1920 to 1080, // Full HD desktop/laptop
            2560 to 1440  // QHD monitor
        )

        for ((width, height) in wideScreens) {
            val config = AdaptiveLayoutUtils.computeConfig(width, height)

            assertEquals("Width $width should be LAPTOP", FormFactor.LAPTOP, config.formFactor)
            assertTrue("Width $width should report isLaptop true", config.isLaptop)
            assertEquals("Width $width should be EXPANDED", WindowWidthSize.EXPANDED, config.windowWidthSize)
            assertEquals("Wide display >= 1200dp should have 3 columns", 3, config.gridColumnCount)
            assertEquals(1280.dp, config.maxContentWidth)
            assertEquals(640.dp, config.maxSheetContentWidth)
            assertFalse(config.isCompactButtons)
        }
    }

    @Test
    fun testBoundaryTransitions() {
        // 599 (Phone) vs 600 (Laptop)
        val phoneBoundary = AdaptiveLayoutUtils.computeConfig(599, 800)
        val laptopBoundary = AdaptiveLayoutUtils.computeConfig(600, 800)
        assertEquals(FormFactor.PHONE, phoneBoundary.formFactor)
        assertEquals(FormFactor.LAPTOP, laptopBoundary.formFactor)
        assertEquals(1, phoneBoundary.gridColumnCount)
        assertEquals(2, laptopBoundary.gridColumnCount)

        // 839 (Medium) vs 840 (Expanded)
        val mediumBoundary = AdaptiveLayoutUtils.computeConfig(839, 800)
        val expandedBoundary = AdaptiveLayoutUtils.computeConfig(840, 800)
        assertEquals(WindowWidthSize.MEDIUM, mediumBoundary.windowWidthSize)
        assertEquals(WindowWidthSize.EXPANDED, expandedBoundary.windowWidthSize)

        // 1199 (2 cols) vs 1200 (3 cols)
        val twoColBoundary = AdaptiveLayoutUtils.computeConfig(1199, 800)
        val threeColBoundary = AdaptiveLayoutUtils.computeConfig(1200, 800)
        assertEquals(2, twoColBoundary.gridColumnCount)
        assertEquals(3, threeColBoundary.gridColumnCount)
    }

    @Test
    fun testZeroAndNegativeEdgeCases() {
        val zeroConfig = AdaptiveLayoutUtils.computeConfig(0, 0)
        assertEquals(1, zeroConfig.screenWidthDp)
        assertEquals(1, zeroConfig.screenHeightDp)
        assertEquals(FormFactor.PHONE, zeroConfig.formFactor)
        assertEquals(WindowWidthSize.COMPACT, zeroConfig.windowWidthSize)

        val negativeConfig = AdaptiveLayoutUtils.computeConfig(-100, -200)
        assertEquals(1, negativeConfig.screenWidthDp)
        assertEquals(1, negativeConfig.screenHeightDp)
        assertEquals(FormFactor.PHONE, negativeConfig.formFactor)
    }
}
