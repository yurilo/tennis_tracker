package com.tennis.tracker.domain

import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.TennisProfileProvider
import com.tennis.tracker.domain.model.TennisTour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TennisProfileProviderTest {

    private fun createDummyMatch(eventGrouping: String): Match {
        return Match(
            id = "test-1",
            tournamentName = "US Open",
            eventGrouping = eventGrouping,
            roundName = "Quarterfinals",
            state = MatchState.LIVE,
            statusDetail = "In Progress",
            player1 = Competitor(id = "1", name = "Player One"),
            player2 = Competitor(id = "2", name = "Player Two")
        )
    }

    @Test
    fun testResolveTour_fromEventGrouping() {
        val menMatch = createDummyMatch("Men's Singles")
        val womenMatch = createDummyMatch("Women's Singles")
        val menDoubles = createDummyMatch("Men's Doubles")
        val womenDoubles = createDummyMatch("Women's Doubles")

        val p1 = Competitor(id = "1", name = "Generic Player")

        assertEquals(TennisTour.ATP, TennisProfileProvider.resolveTour(p1, menMatch))
        assertEquals(TennisTour.WTA, TennisProfileProvider.resolveTour(p1, womenMatch))
        assertEquals(TennisTour.ATP, TennisProfileProvider.resolveTour(p1, menDoubles))
        assertEquals(TennisTour.WTA, TennisProfileProvider.resolveTour(p1, womenDoubles))
    }

    @Test
    fun testResolveTour_fromPlayerNameWhenMatchNull() {
        val alcaraz = Competitor(id = "1", name = "Carlos Alcaraz")
        val djokovic = Competitor(id = "2", name = "Novak Djokovic")
        val sabalenka = Competitor(id = "3", name = "Aryna Sabalenka")
        val swiatek = Competitor(id = "4", name = "Iga Swiatek")

        assertEquals(TennisTour.ATP, TennisProfileProvider.resolveTour(alcaraz, null))
        assertEquals(TennisTour.ATP, TennisProfileProvider.resolveTour(djokovic, null))
        assertEquals(TennisTour.WTA, TennisProfileProvider.resolveTour(sabalenka, null))
        assertEquals(TennisTour.WTA, TennisProfileProvider.resolveTour(swiatek, null))
    }

    @Test
    fun testAtpProfileUrl_resolvesCanonical() {
        val alcaraz = Competitor(id = "1", name = "Carlos Alcaraz")
        val sinner = Competitor(id = "2", name = "Jannik Sinner")
        val djokovic = Competitor(id = "3", name = "Novak Djokovic")
        val fritz = Competitor(id = "4", name = "Taylor Fritz")
        val tiafoe = Competitor(id = "5", name = "Frances Tiafoe")
        val menMatch = createDummyMatch("Men's Singles")

        val alcarazUrl = TennisProfileProvider.getProfileUrl(alcaraz, menMatch)
        assertEquals("https://www.atptour.com/en/players/carlos-alcaraz/a0e2/overview", alcarazUrl)

        val sinnerUrl = TennisProfileProvider.getProfileUrl(sinner, menMatch)
        assertEquals("https://www.atptour.com/en/players/jannik-sinner/s0ag/overview", sinnerUrl)

        val djokovicUrl = TennisProfileProvider.getProfileUrl(djokovic, menMatch)
        assertEquals("https://www.atptour.com/en/players/novak-djokovic/d643/overview", djokovicUrl)

        val fritzUrl = TennisProfileProvider.getProfileUrl(fritz, menMatch)
        assertEquals("https://www.atptour.com/en/players/taylor-fritz/fdf6/overview", fritzUrl)

        val tiafoeUrl = TennisProfileProvider.getProfileUrl(tiafoe, menMatch)
        assertEquals("https://www.atptour.com/en/players/frances-tiafoe/t789/overview", tiafoeUrl)
    }

    @Test
    fun testWtaProfileUrl_resolvesVerifiedOfficialIds() {
        val womenMatch = createDummyMatch("Women's Singles")

        // 1. Emma Navarro - verified 325410
        val navarro = Competitor(id = "1", name = "Emma Navarro")
        val navarroUrl = TennisProfileProvider.getProfileUrl(navarro, womenMatch)
        assertEquals("https://www.wtatennis.com/players/325410/emma-navarro", navarroUrl)

        // 2. Karolina Muchova - verified 322191
        val muchova = Competitor(id = "2", name = "Karolina Muchova")
        val muchovaUrl = TennisProfileProvider.getProfileUrl(muchova, womenMatch)
        assertEquals("https://www.wtatennis.com/players/322191/karolina-muchova", muchovaUrl)

        // 3. Linda Noskova - verified 329668
        val noskova = Competitor(id = "3", name = "Linda Noskova")
        val noskovaUrl = TennisProfileProvider.getProfileUrl(noskova, womenMatch)
        assertEquals("https://www.wtatennis.com/players/329668/linda-noskova", noskovaUrl)

        // 4. Ann Li - verified 326096
        val annLi = Competitor(id = "4", name = "Ann Li")
        val annLiUrl = TennisProfileProvider.getProfileUrl(annLi, womenMatch)
        assertEquals("https://www.wtatennis.com/players/326096/ann-li", annLiUrl)

        // 5. Aryna Sabalenka - verified 327241
        val sabalenka = Competitor(id = "5", name = "Aryna Sabalenka")
        val sabalenkaUrl = TennisProfileProvider.getProfileUrl(sabalenka, womenMatch)
        assertEquals("https://www.wtatennis.com/players/327241/aryna-sabalenka", sabalenkaUrl)

        // 6. Iga Swiatek - verified 327371
        val swiatek = Competitor(id = "6", name = "Iga Swiatek")
        val swiatekUrl = TennisProfileProvider.getProfileUrl(swiatek, womenMatch)
        assertEquals("https://www.wtatennis.com/players/327371/iga-swiatek", swiatekUrl)

        // 7. Coco Gauff - verified 328560
        val gauff = Competitor(id = "7", name = "Coco Gauff")
        val gauffUrl = TennisProfileProvider.getProfileUrl(gauff, womenMatch)
        assertEquals("https://www.wtatennis.com/players/328560/coco-gauff", gauffUrl)

        // 8. Qinwen Zheng - verified 328120
        val zheng = Competitor(id = "8", name = "Qinwen Zheng")
        val zhengUrl = TennisProfileProvider.getProfileUrl(zheng, womenMatch)
        assertEquals("https://www.wtatennis.com/players/328120/qinwen-zheng", zhengUrl)
    }

    @Test
    fun testWtaProfileUrl_handlesDiacriticsAndInvertedOrder() {
        val womenMatch = createDummyMatch("Women's Singles")

        // Diacritics
        val swiatekWithAccents = Competitor(id = "1", name = "Iga Świątek")
        val swiatekUrl = TennisProfileProvider.getProfileUrl(swiatekWithAccents, womenMatch)
        assertEquals("https://www.wtatennis.com/players/327371/iga-swiatek", swiatekUrl)

        val muchovaWithAccents = Competitor(id = "2", name = "Karolína Muchová")
        val muchovaUrl = TennisProfileProvider.getProfileUrl(muchovaWithAccents, womenMatch)
        assertEquals("https://www.wtatennis.com/players/322191/karolina-muchova", muchovaUrl)

        // Inverted order
        val zhengInverted = Competitor(id = "3", name = "Zheng Qinwen")
        val zhengUrl = TennisProfileProvider.getProfileUrl(zhengInverted, womenMatch)
        assertEquals("https://www.wtatennis.com/players/328120/zheng-qinwen", zhengUrl)
    }

    @Test
    fun testProfileUrl_fallbackPrecisionSearchQuery() {
        val unknownPlayer = Competitor(id = "999", name = "Unknown Qualifier")
        val menMatch = createDummyMatch("Men's Singles")
        val womenMatch = createDummyMatch("Women's Singles")

        val atpFallback = TennisProfileProvider.getProfileUrl(unknownPlayer, menMatch)
        assertTrue(atpFallback.startsWith("https://www.google.com/search?q="))
        assertTrue(atpFallback.contains("site%3Aatptour.com%2Fen%2Fplayers"))

        val wtaFallback = TennisProfileProvider.getProfileUrl(unknownPlayer, womenMatch)
        assertTrue(wtaFallback.startsWith("https://www.google.com/search?q="))
        assertTrue(wtaFallback.contains("site%3Awtatennis.com%2Fplayers"))
    }

    @Test
    fun testGenerateSlug() {
        assertEquals("carlos-alcaraz", TennisProfileProvider.generateSlug("Carlos Alcaraz"))
        assertEquals("alex-de-minaur", TennisProfileProvider.generateSlug("Alex de Minaur"))
        assertEquals("felix-auger-aliassime", TennisProfileProvider.generateSlug("Felix Auger-Aliassime"))
    }

    @Test
    fun testDoublesProfileUrls_atp() {
        val doublesMatch = createDummyMatch("Men's Doubles")
        val ramSalisbury = Competitor(id = "1", name = "Rajeev Ram / Joe Salisbury")
        val urls = TennisProfileProvider.getProfileUrls(ramSalisbury, doublesMatch)

        assertEquals(2, urls.size)
        assertEquals("Rajeev Ram", urls[0].first.name)
        assertEquals("https://www.atptour.com/en/players/rajeev-ram/r548/overview", urls[0].second)

        assertEquals("Joe Salisbury", urls[1].first.name)
        assertEquals("https://www.atptour.com/en/players/joe-salisbury/so34/overview", urls[1].second)
    }

    @Test
    fun testDoublesProfileUrls_wta() {
        val doublesMatch = createDummyMatch("Women's Doubles")
        val mertensShnaider = Competitor(id = "2", name = "Elise Mertens / Diana Shnaider")
        val urls = TennisProfileProvider.getProfileUrls(mertensShnaider, doublesMatch)

        assertEquals(2, urls.size)
        assertEquals("Elise Mertens", urls[0].first.name)
        assertEquals("https://www.wtatennis.com/players/319698/elise-mertens", urls[0].second)

        assertEquals("Diana Shnaider", urls[1].first.name)
        assertEquals("https://www.wtatennis.com/players/330482/diana-shnaider", urls[1].second)
    }

    @Test
    fun testDoublesProfileUrl_fallbackNeverContainsSlash() {
        val doublesMatch = createDummyMatch("Women's Doubles")
        val burilloRomero = Competitor(id = "3", name = "Irene Burillo / Leyre Romero Gormaz")
        val singleUrl = TennisProfileProvider.getProfileUrl(burilloRomero, doublesMatch)

        assertEquals("https://www.wtatennis.com/players/322894/irene-burillo", singleUrl)
    }
}
