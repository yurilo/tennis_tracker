package com.usopen.tracker.domain.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerPhotoProviderTest {

    @Before
    @After
    fun cleanUp() {
        PlayerPhotoProvider.clearDynamicCache()
    }

    @Test
    fun testCuratedAtpTopPlayersResolve() {
        val atpPlayers = listOf(
            "Carlos Alcaraz",
            "Jannik Sinner",
            "Novak Djokovic",
            "Alexander Zverev",
            "Daniil Medvedev",
            "Taylor Fritz",
            "Tommy Paul",
            "Frances Tiafoe",
            "Ben Shelton",
            "Casper Ruud",
            "Stefanos Tsitsipas",
            "Grigor Dimitrov",
            "Jack Draper",
            "Alex de Minaur",
            "Holger Rune"
        )

        for (player in atpPlayers) {
            val url = PlayerPhotoProvider.getPhotoUrl(player)
            assertNotNull("Photo URL should resolve for $player", url)
            assertTrue("URL should be from Wikimedia for $player", url!!.contains("wikimedia.org"))
        }
    }

    @Test
    fun testCuratedWtaTopPlayersResolve() {
        val wtaPlayers = listOf(
            "Aryna Sabalenka",
            "Iga Swiatek",
            "Coco Gauff",
            "Qinwen Zheng",
            "Jessica Pegula",
            "Elena Rybakina",
            "Jasmine Paolini",
            "Emma Navarro",
            "Mirra Andreeva",
            "Danielle Collins"
        )

        for (player in wtaPlayers) {
            val url = PlayerPhotoProvider.getPhotoUrl(player)
            assertNotNull("Photo URL should resolve for $player", url)
            assertTrue("URL should be from Wikimedia for $player", url!!.contains("wikimedia.org"))
        }
    }

    @Test
    fun testCuratedDoublesStarsResolve() {
        val doublesStars = listOf(
            "Rajeev Ram",
            "Joe Salisbury",
            "Matthew Ebden",
            "Rohan Bopanna",
            "Marcel Granollers",
            "Horacio Zeballos",
            "Gabriela Dabrowski",
            "Erin Routliffe",
            "Luisa Stefani"
        )

        for (player in doublesStars) {
            val url = PlayerPhotoProvider.getPhotoUrl(player)
            assertNotNull("Doubles photo URL should resolve for $player", url)
            assertTrue("URL should be from Wikimedia for $player", url!!.contains("wikimedia.org"))
        }
    }

    @Test
    fun testDiacriticsAndTransliterations() {
        // Iga Świątek with Polish diacritics
        val swiatek = PlayerPhotoProvider.getPhotoUrl("Iga Świątek")
        assertNotNull(swiatek)
        assertTrue(swiatek!!.contains("Iga_Swiatek"))

        // Novak Đoković with Serbian stroke D
        val djokovic = PlayerPhotoProvider.getPhotoUrl("Novak Đoković")
        assertNotNull(djokovic)
        assertTrue(djokovic!!.contains("Novak_Djokovic"))

        // Barbora Krejčíková with Czech carons/accents
        val krejcikova = PlayerPhotoProvider.getPhotoUrl("Barbora Krejčíková")
        assertNotNull(krejcikova)
        assertTrue(krejcikova!!.contains("Barbora_Krej"))

        // Kateřina Siniaková
        val siniakova = PlayerPhotoProvider.getPhotoUrl("Kateřina Siniaková")
        assertNotNull(siniakova)
        assertTrue(siniakova!!.contains("Siniakov"))

        // Karolína Muchová
        val muchova = PlayerPhotoProvider.getPhotoUrl("Karolína Muchová")
        assertNotNull(muchova)
        assertTrue(muchova!!.contains("Muchov"))

        // Donna Vekić
        val vekic = PlayerPhotoProvider.getPhotoUrl("Donna Vekić")
        assertNotNull(vekic)
        assertTrue(vekic!!.contains("Veki"))
    }

    @Test
    fun testNameInversionAndAliases() {
        // Qinwen Zheng vs Zheng Qinwen
        val qinwen = PlayerPhotoProvider.getPhotoUrl("Qinwen Zheng")
        val zheng = PlayerPhotoProvider.getPhotoUrl("Zheng Qinwen")
        assertNotNull(qinwen)
        assertNotNull(zheng)
        assertEquals(qinwen, zheng)

        // Aryna Sabalenka inverted
        val sabalenkaInverted = PlayerPhotoProvider.getPhotoUrl("Sabalenka Aryna")
        assertNotNull(sabalenkaInverted)
        assertTrue(sabalenkaInverted!!.contains("Aryna_Sabalenka"))
    }

    @Test
    fun testSurnameAndAbbreviatedLookups() {
        val alcaraz = PlayerPhotoProvider.getPhotoUrl("Alcaraz")
        assertNotNull(alcaraz)
        assertTrue(alcaraz!!.contains("Carlos_Alcaraz"))

        val cAlcaraz = PlayerPhotoProvider.getPhotoUrl("C. Alcaraz")
        assertNotNull(cAlcaraz)
        assertTrue(cAlcaraz!!.contains("Carlos_Alcaraz"))

        val sinner = PlayerPhotoProvider.getPhotoUrl("Sinner")
        assertNotNull(sinner)
        assertTrue(sinner!!.contains("Jannik_Sinner"))
    }

    @Test
    fun testDefunctEspnCombinerUrlSanitization() {
        val deadEspnUrl = "https://a.espncdn.com/combiner/i?img=/i/headshots/tennis/players/full/4094.png&w=350&h=254"
        val resolved = PlayerPhotoProvider.getPhotoUrl(name = "Carlos Alcaraz", existingUrl = deadEspnUrl)
        assertNotNull(resolved)
        assertTrue("Defunct ESPN URL must be replaced with curated Wikimedia URL", resolved!!.contains("wikimedia.org"))
    }

    @Test
    fun testValidExternalUrlPreservation() {
        val validUrl = "https://images.example.com/custom_avatar.png"
        val resolved = PlayerPhotoProvider.getPhotoUrl(name = "Custom Player", existingUrl = validUrl)
        assertEquals(validUrl, resolved)
    }

    @Test
    fun testCompetitorPhotoUrlResolution() {
        val comp = Competitor(id = "comp1", name = "Coco Gauff")
        val resolved = PlayerPhotoProvider.getPhotoUrl(comp)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains("Coco_Gauff"))
    }

    @Test
    fun testDynamicCacheDirectLookup() {
        val testName = "Unlisted Rookie Player"
        val normalized = PlayerPhotoProvider.normalizeName(testName)
        val mockThumbnail = "https://thumb.wikimedia.org/wikipedia/commons/thumb/test.jpg"

        PlayerPhotoProvider.dynamicPhotoCache[normalized] = mockThumbnail
        val resolved = PlayerPhotoProvider.getPhotoUrl(testName)
        assertEquals(mockThumbnail, resolved)
    }

    @Test
    fun testUnknownPlayerReturnsNullGracefully() {
        val unknown = PlayerPhotoProvider.getPhotoUrl("NonExistentPlayer1234567")
        assertNull(unknown)
    }
}
