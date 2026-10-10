package com.usopen.tracker.domain

import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.PlayerPhotoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAvatarTest {

    @Test
    fun testPlayerInitials() {
        val p1 = Competitor(id = "1", name = "Carlos Alcaraz")
        assertEquals("CA", p1.initials)

        val p2 = Competitor(id = "2", name = "Jannik Sinner")
        assertEquals("JS", p2.initials)

        val p3 = Competitor(id = "3", name = "Alex de Minaur")
        assertEquals("AM", p3.initials)

        val p4 = Competitor(id = "4", name = "Zheng")
        assertEquals("ZH", p4.initials)
    }

    @Test
    fun testCountryFlags() {
        val p1 = Competitor(id = "1", name = "Alcaraz", country = "ESP")
        assertEquals("🇪🇸", p1.countryFlag)

        val p2 = Competitor(id = "2", name = "Sinner", country = "ITA")
        assertEquals("🇮🇹", p2.countryFlag)

        val p3 = Competitor(id = "3", name = "Navarro", country = "USA")
        assertEquals("🇺🇸", p3.countryFlag)

        val p4 = Competitor(id = "4", name = "Djokovic", country = "SRB")
        assertEquals("🇷🇸", p4.countryFlag)

        val p5 = Competitor(id = "5", name = "Muchova", country = "CZE")
        assertEquals("🇨🇿", p5.countryFlag)

        val pUnknown = Competitor(id = "6", name = "Other", country = "XYZ")
        assertNull(pUnknown.countryFlag)
    }

    @Test
    fun testPlayerPhotoProvider_resolvesCuratedUrls() {
        val alcarazUrl = PlayerPhotoProvider.getPhotoUrl("Carlos Alcaraz")
        assertNotNull(alcarazUrl)
        assertTrue(alcarazUrl!!.contains("4094.png"))

        val sinnerUrl = PlayerPhotoProvider.getPhotoUrl("Jannik Sinner")
        assertNotNull(sinnerUrl)
        assertTrue(sinnerUrl!!.contains("4096.png"))

        val sabalenkaUrl = PlayerPhotoProvider.getPhotoUrl("Aryna Sabalenka")
        assertNotNull(sabalenkaUrl)
        assertTrue(sabalenkaUrl!!.contains("3406.png"))
    }

    @Test
    fun testPlayerPhotoProvider_preservesExistingUrl() {
        val customUrl = "https://example.com/player.png"
        val resolved = PlayerPhotoProvider.getPhotoUrl(name = "Unknown Player", existingUrl = customUrl)
        assertEquals(customUrl, resolved)
    }

    @Test
    fun testPlayerPhotoProvider_resolvesFromCompetitor() {
        val competitor = Competitor(id = "1", name = "Coco Gauff")
        val resolved = PlayerPhotoProvider.getPhotoUrl(competitor)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains("4119.png"))
    }
}
