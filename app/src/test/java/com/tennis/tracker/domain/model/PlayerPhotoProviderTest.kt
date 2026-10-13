package com.tennis.tracker.domain.model

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun testResolveAtpPlayersDynamically() = runBlocking {
        val alcaraz = PlayerPhotoProvider.resolvePhotoUrl("Carlos Alcaraz", TennisTour.ATP)
        assertNotNull("Alcaraz photo should resolve dynamically", alcaraz)
        assertTrue(alcaraz!!.contains("wikimedia.org") || alcaraz.contains("Alcaraz"))

        val sinner = PlayerPhotoProvider.resolvePhotoUrl("Jannik Sinner", TennisTour.ATP)
        assertNotNull("Sinner photo should resolve dynamically", sinner)
        assertTrue(sinner!!.contains("wikimedia.org") || sinner.contains("Sinner"))
    }

    @Test
    fun testResolveWtaPlayersDynamicallyFromWtaApi() = runBlocking {
        val swiatek = PlayerPhotoProvider.resolvePhotoUrl("Iga Swiatek", TennisTour.WTA)
        assertNotNull("Swiatek photo should resolve dynamically", swiatek)
        assertTrue("WTA player should resolve to official WTA blob CDN or Wikipedia",
            swiatek!!.contains("wtafiles.blob.core.windows.net") || swiatek.contains("wikimedia.org"))

        val sabalenka = PlayerPhotoProvider.resolvePhotoUrl("Aryna Sabalenka", TennisTour.WTA)
        assertNotNull("Sabalenka photo should resolve dynamically", sabalenka)
        assertTrue(sabalenka!!.contains("wtafiles.blob.core.windows.net") || sabalenka.contains("wikimedia.org"))

        val gauff = PlayerPhotoProvider.resolvePhotoUrl("Coco Gauff", TennisTour.WTA)
        assertNotNull("Gauff photo should resolve dynamically", gauff)
        assertTrue(gauff!!.contains("wtafiles.blob.core.windows.net") || gauff.contains("wikimedia.org"))
    }

    @Test
    fun testResolveDoublesStarsDynamically() = runBlocking {
        val routliffe = PlayerPhotoProvider.resolvePhotoUrl("Erin Routliffe", TennisTour.WTA)
        assertNotNull("WTA doubles star should resolve dynamically", routliffe)
        assertTrue(routliffe!!.contains("wtafiles.blob.core.windows.net") || routliffe.contains("wikimedia.org"))

        val ram = PlayerPhotoProvider.resolvePhotoUrl("Rajeev Ram", TennisTour.ATP)
        assertNotNull("ATP doubles star should resolve dynamically", ram)
        assertTrue(ram!!.contains("wikimedia.org") || ram.contains("Ram"))
    }

    @Test
    fun testDisambiguationHandling() = runBlocking {
        // Tommy Paul is a Wikipedia disambiguation page ("Tommy Paul" -> priest, etc.),
        // the dynamic resolver should automatically resolve to "Tommy Paul (tennis)".
        val paul = PlayerPhotoProvider.resolvePhotoUrl("Tommy Paul", TennisTour.ATP)
        assertNotNull("Tommy Paul should resolve despite disambiguation page", paul)
        assertTrue(paul!!.contains("Tommy_Paul") || paul.contains("wikimedia.org"))
    }

    @Test
    fun testDiacriticsNormalization() {
        assertEquals("novak djokovic", PlayerPhotoProvider.normalizeName("Novak Đoković"))
        assertEquals("iga swiatek", PlayerPhotoProvider.normalizeName("Iga Świątek"))
        assertEquals("barbora krejcikova", PlayerPhotoProvider.normalizeName("Barbora Krejčíková"))
        assertEquals("katerina siniakova", PlayerPhotoProvider.normalizeName("Kateřina Siniaková"))
        assertEquals("karolina muchova", PlayerPhotoProvider.normalizeName("Karolína Muchová"))
        assertEquals("donna vekic", PlayerPhotoProvider.normalizeName("Donna Vekić"))
    }

    @Test
    fun testDefunctEspnCombinerUrlSanitization() {
        val deadEspnUrl = "https://a.espncdn.com/combiner/i?img=/i/headshots/tennis/players/full/4094.png&w=350&h=254"
        val resolved = PlayerPhotoProvider.getPhotoUrl(name = "Carlos Alcaraz", existingUrl = deadEspnUrl)
        assertNotEquals("Defunct ESPN combiner URL must never be returned", deadEspnUrl, resolved)
    }

    @Test
    fun testValidExternalUrlPreservation() {
        val validUrl = "https://images.example.com/custom_avatar.png"
        val resolved = PlayerPhotoProvider.getPhotoUrl(name = "Custom Player", existingUrl = validUrl)
        assertEquals(validUrl, resolved)
    }

    @Test
    fun testMemoryCacheDirectRetrieval() {
        val testName = "Test Custom Player"
        val normalized = PlayerPhotoProvider.normalizeName(testName)
        val mockThumbnail = "https://wtafiles.blob.core.windows.net/images/headshots/99999.jpg"

        PlayerPhotoProvider.dynamicPhotoCache[normalized] = mockThumbnail
        val resolved = PlayerPhotoProvider.getPhotoUrl(testName)
        assertEquals(mockThumbnail, resolved)
    }

    @Test
    fun testNegativeCachingForUnknownPlayer() = runBlocking {
        val unknown = PlayerPhotoProvider.resolvePhotoUrl("NonExistentPlayerXYZ987654321")
        assertNull(unknown)

        // After failing once, negative cache entry "" is stored so we do not hammer the network
        val normalized = PlayerPhotoProvider.normalizeName("NonExistentPlayerXYZ987654321")
        assertEquals("", PlayerPhotoProvider.dynamicPhotoCache[normalized])
    }

    @Test
    fun testCompetitorPhotoUrlResolution() = runBlocking {
        val comp = Competitor(id = "comp1", name = "Aryna Sabalenka")
        val resolved = PlayerPhotoProvider.resolvePhotoUrl(comp)
        assertNotNull(resolved)
        assertTrue(resolved!!.contains("wtafiles.blob.core.windows.net") || resolved.contains("wikimedia.org"))
    }
}
