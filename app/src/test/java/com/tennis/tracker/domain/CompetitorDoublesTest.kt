package com.tennis.tracker.domain

import com.tennis.tracker.domain.model.Competitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitorDoublesTest {

    @Test
    fun testIsDoubles_detection() {
        val singles = Competitor(id = "1", name = "Carlos Alcaraz")
        val doublesSlashed = Competitor(id = "2", name = "Rajeev Ram / Joe Salisbury")
        val doublesExplicit = Competitor(
            id = "3",
            name = "Team USA/GBR",
            players = listOf(
                Competitor(id = "3a", name = "Rajeev Ram"),
                Competitor(id = "3b", name = "Joe Salisbury")
            )
        )

        assertFalse(singles.isDoubles)
        assertTrue(doublesSlashed.isDoubles)
        assertTrue(doublesExplicit.isDoubles)
    }

    @Test
    fun testIndividualPlayers_extraction() {
        val slashed = Competitor(id = "1", name = "Irene Burillo / Leyre Romero Gormaz", country = "ESP")
        val partners = slashed.individualPlayers

        assertEquals(2, partners.size)
        assertEquals("Irene Burillo", partners[0].name)
        assertEquals("Leyre Romero Gormaz", partners[1].name)
        assertEquals("ESP", partners[0].country)
        assertEquals("ESP", partners[1].country)
    }

    @Test
    fun testIndividualPlayers_separateCountries() {
        val slashed = Competitor(id = "1", name = "Ram / Salisbury", country = "USA/GBR")
        val partners = slashed.individualPlayers

        assertEquals(2, partners.size)
        assertEquals("USA", partners[0].country)
        assertEquals("GBR", partners[1].country)
    }

    @Test
    fun testInitials_forDoublesAndSingles() {
        val singles = Competitor(id = "1", name = "Carlos Alcaraz")
        assertEquals("CA", singles.initials)

        val doubles = Competitor(id = "2", name = "Rajeev Ram / Joe Salisbury")
        assertEquals("R/S", doubles.initials)

        val doublesLong = Competitor(id = "3", name = "Irene Burillo / Leyre Romero Gormaz")
        assertEquals("B/G", doublesLong.initials)
    }

    @Test
    fun testCountryFlag_doubles() {
        val singles = Competitor(id = "1", name = "Carlos Alcaraz", country = "ESP")
        assertEquals("🇪🇸", singles.countryFlag)

        val doublesSame = Competitor(id = "2", name = "Burillo / Romero", country = "ESP/ESP")
        assertEquals("🇪🇸", doublesSame.countryFlag)

        val doublesMixed = Competitor(id = "3", name = "Ram / Salisbury", country = "USA/GBR")
        assertEquals("🇺🇸 🇬🇧", doublesMixed.countryFlag)
    }

    @Test
    fun testLastNameOrShortName() {
        val singles = Competitor(id = "1", name = "Carlos Alcaraz")
        assertEquals("Alcaraz", singles.lastNameOrShortName)

        val doublesShort = Competitor(id = "2", name = "Ram / Salisbury")
        assertEquals("Ram / Salisbury", doublesShort.lastNameOrShortName)

        val doublesFull = Competitor(id = "3", name = "Irene Burillo / Leyre Romero Gormaz")
        assertEquals("Burillo / Gormaz", doublesFull.lastNameOrShortName)
    }
}
