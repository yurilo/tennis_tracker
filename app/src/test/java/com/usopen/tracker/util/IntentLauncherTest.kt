package com.usopen.tracker.util

import com.usopen.tracker.domain.model.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentLauncherTest {

    @Test
    fun `known browser packages should include Chrome and standard browsers`() {
        assertTrue(IntentLauncher.KNOWN_BROWSER_PACKAGES.contains("com.android.chrome"))
        assertTrue(IntentLauncher.KNOWN_BROWSER_PACKAGES.contains("org.mozilla.firefox"))
    }

    @Test
    fun `known browser packages should never include file managers or documentsui`() {
        for (pkg in IntentLauncher.KNOWN_BROWSER_PACKAGES) {
            assertTrue(!pkg.contains("documentsui", ignoreCase = true))
            assertTrue(!pkg.contains("file", ignoreCase = true))
            assertTrue(!pkg.contains("download", ignoreCase = true))
            assertTrue(!pkg.contains("folder", ignoreCase = true))
        }
    }

    @Test
    fun `espn package candidates should include standard ESPN app package`() {
        assertEquals("com.espn.score_center", IntentLauncher.ESPN_PACKAGE_NAME)
        assertTrue(IntentLauncher.ESPN_PACKAGE_CANDIDATES.contains("com.espn.score_center"))
        assertTrue(IntentLauncher.ESPN_PACKAGE_CANDIDATES.contains("com.espn.sportscenter"))
    }

    @Test
    fun `youtube package candidates should include standard YouTube app package`() {
        assertEquals("com.google.android.youtube", IntentLauncher.YOUTUBE_PACKAGE_NAME)
        assertTrue(IntentLauncher.YOUTUBE_PACKAGE_CANDIDATES.contains("com.google.android.youtube"))
        assertTrue(IntentLauncher.YOUTUBE_PACKAGE_CANDIDATES.contains("com.google.android.youtube.tv"))
    }

    @Test
    fun `launchPlayerProfile resolves valid web URL for competitor`() {
        val alcaraz = com.usopen.tracker.domain.model.Competitor(id = "1", name = "Carlos Alcaraz")
        val url = com.usopen.tracker.domain.model.TennisProfileProvider.getProfileUrl(alcaraz)
        assertTrue(url.contains("atptour.com"))
        assertTrue(url.contains("carlos-alcaraz"))
    }
}
