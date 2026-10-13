package com.tennis.tracker.domain

import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.PlayerPhotoProvider
import com.tennis.tracker.domain.model.TennisTour
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerAvatarTest {

    @Before
    @After
    fun cleanUp() {
        PlayerPhotoProvider.clearDynamicCache()
    }

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
    fun testPlayerPhotoProvider_preservesExistingUrl() {
        val customUrl = "https://example.com/player.png"
        val resolved = PlayerPhotoProvider.getPhotoUrl(name = "Unknown Player", existingUrl = customUrl)
        assertEquals(customUrl, resolved)
    }

    @Test
    fun testPlayerPhotoProvider_discardsDefunctEspnCombinerUrl() {
        val deadEspnUrl = "https://a.espncdn.com/combiner/i?img=/i/headshots/tennis/players/full/4094.png&w=350&h=254"
        val resolved = PlayerPhotoProvider.getPhotoUrl(name = "Carlos Alcaraz", existingUrl = deadEspnUrl)
        // Must never return dead ESPN URL
        assertNotEquals(deadEspnUrl, resolved)
    }

    @Test
    fun testPlayerPhotoProvider_resolvesDynamicallyAndCaches() = runBlocking {
        // Initially cold
        assertNull(PlayerPhotoProvider.getPhotoUrl("Carlos Alcaraz"))

        // Dynamic resolution
        val resolved = PlayerPhotoProvider.resolvePhotoUrl("Carlos Alcaraz", TennisTour.ATP)
        assertNotNull("Alcaraz photo should resolve dynamically", resolved)
        assertTrue(resolved!!.contains("wikimedia.org") || resolved.contains("Alcaraz"))

        // After resolution, synchronous getPhotoUrl returns the cached URL
        val cached = PlayerPhotoProvider.getPhotoUrl("Carlos Alcaraz")
        assertEquals(resolved, cached)
    }

    @Test
    fun testPlayerPhotoProvider_resolvesWtaDynamically() = runBlocking {
        val wtaUrl = PlayerPhotoProvider.resolvePhotoUrl("Iga Swiatek", TennisTour.WTA)
        assertNotNull("WTA player should resolve dynamically", wtaUrl)
        assertTrue("WTA photo should be from official WTA blob CDN or Wikipedia",
            wtaUrl!!.contains("wtafiles.blob.core.windows.net") || wtaUrl.contains("wikimedia.org"))

        // Synchronous getter returns cached URL
        assertEquals(wtaUrl, PlayerPhotoProvider.getPhotoUrl("Iga Swiatek"))
    }

    @Test
    fun testPlayerPhotoProvider_normalizesDiacritics() = runBlocking {
        val djokovicUrl = PlayerPhotoProvider.resolvePhotoUrl("Novak Đoković", TennisTour.ATP)
        assertNotNull(djokovicUrl)
        assertTrue(djokovicUrl!!.contains("Djokovic") || djokovicUrl.contains("wikimedia.org"))
    }
}
