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
        assertTrue(alcarazUrl!!.contains("wikimedia.org") && alcarazUrl.contains("Carlos_Alcaraz"))

        val sinnerUrl = PlayerPhotoProvider.getPhotoUrl("Jannik Sinner")
        assertNotNull(sinnerUrl)
        assertTrue(sinnerUrl!!.contains("wikimedia.org") && sinnerUrl.contains("Jannik_Sinner"))

        val sabalenkaUrl = PlayerPhotoProvider.getPhotoUrl("Aryna Sabalenka")
        assertNotNull(sabalenkaUrl)
        assertTrue(sabalenkaUrl!!.contains("wikimedia.org") && sabalenkaUrl.contains("Aryna_Sabalenka"))
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
        assertNotNull(resolved)
        assertTrue(resolved!!.contains("wikimedia.org") && resolved.contains("Carlos_Alcaraz"))
    }

    @Test
    fun testPlayerPhotoProvider_resolvesFromCompetitor() {
        val competitor = Competitor(id = "1", name = "Coco Gauff")
        val resolved = PlayerPhotoProvider.getPhotoUrl(competitor)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains("wikimedia.org") && resolved.contains("Coco_Gauff"))
    }

    @Test
    fun testPlayerPhotoProvider_normalizesDiacritics() {
        val swiatekUrl = PlayerPhotoProvider.getPhotoUrl("Iga Świątek")
        assertNotNull(swiatekUrl)
        assertTrue(swiatekUrl!!.contains("wikimedia.org") && swiatekUrl.contains("Iga_Swiatek"))

        val djokovicUrl = PlayerPhotoProvider.getPhotoUrl("Novak Đoković")
        assertNotNull(djokovicUrl)
        assertTrue(djokovicUrl!!.contains("wikimedia.org") && djokovicUrl.contains("Novak_Djokovic"))
    }
}
