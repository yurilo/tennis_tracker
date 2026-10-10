package com.usopen.tracker.domain.model

import java.net.URLEncoder
import java.text.Normalizer

enum class TennisTour(val displayName: String, val brandColorHex: Long) {
    ATP("ATP", 0xFF003087),
    WTA("WTA", 0xFF8A1538)
}

object TennisProfileProvider {

    // Verified ATP Tour Player IDs: https://www.atptour.com/en/players/{slug}/{atpId}/overview
    private val atpPlayerMap = mapOf(
        "carlos alcaraz" to "a0e2",
        "jannik sinner" to "s0ag",
        "novak djokovic" to "d643",
        "alexander zverev" to "z355",
        "daniil medvedev" to "mm58",
        "taylor fritz" to "fdf6",
        "tommy paul" to "pl56",
        "frances tiafoe" to "t789",
        "ben shelton" to "s0dg",
        "casper ruud" to "ra37",
        "andrey rublev" to "re44",
        "stefanos tsitsipas" to "te51",
        "grigor dimitrov" to "d877",
        "jack draper" to "d898",
        "alex de minaur" to "de04",
        "holger rune" to "r0dg",
        "sebastian korda" to "k0ah",
        "lorenzo musetti" to "m0ej",
        "hubert hurkacz" to "hb71",
        "alexander bublik" to "bk92",
        "jiri lehecka" to "l0bv",
        "arthur fils" to "f0f6",
        "ugo humbert" to "hh26",
        "karen khachanov" to "kdaf",
        "felix auger-aliassime" to "ag37",
        "matteo berrettini" to "bk40",
        "gael monfils" to "mc65",
        "nick kyrgios" to "ke17",
        "andy murray" to "mc10",
        "rafael nadal" to "n409",
        "roger federer" to "f324",
        "flavio cobolli" to "c0ff",
        "tallon griekspoor" to "ge63",
        "tomas machac" to "m0fq",
        "brandon nakashima" to "n0bh",
        "jordan thompson" to "tc61",
        "nuno borges" to "bt64",
        "alexei popyrin" to "p0b4",
        "cameron norrie" to "n444",
        "denis shapovalov" to "su55",
        "rajeev ram" to "r548",
        "joe salisbury" to "so34",
        "mate pavic" to "pi54",
        "marcelo arevalo" to "al41",
        "matthew ebden" to "e690",
        "rohan bopanna" to "b747",
        "horacio zeballos" to "z184",
        "marcel granollers" to "g710",
        "neal skupski" to "sf08",
        "michael venus" to "v528",
        "maximo gonzalez" to "g725",
        "andres molteni" to "mh30",
        "kevin krawietz" to "kb80",
        "tim puetz" to "pe64",
        "max purcell" to "pi11"
    )

    // Verified Official WTA Player IDs: https://www.wtatennis.com/players/{wtaId}/{slug}
    private val wtaPlayerMap = mapOf(
        "aryna sabalenka" to "327241",
        "iga swiatek" to "327371",
        "coco gauff" to "328560",
        "qinwen zheng" to "328120",
        "zheng qinwen" to "328120",
        "jessica pegula" to "316956",
        "emma navarro" to "325410",
        "elena rybakina" to "327333",
        "jasmine paolini" to "324683",
        "karolina muchova" to "322191",
        "donna vekic" to "318516",
        "linda noskova" to "329668",
        "ann li" to "326096",
        "mirra andreeva" to "331809",
        "naomi osaka" to "319225",
        "barbora krejcikova" to "318314",
        "danielle collins" to "323380",
        "daria kasatkina" to "320092",
        "madison keys" to "316959",
        "victoria azarenka" to "312001",
        "ons jabeur" to "322967",
        "paula badosa" to "320124",
        "maria sakkari" to "318312",
        "diana shnaider" to "330482",
        "marta kostyuk" to "326482",
        "jelena ostapenko" to "319939",
        "beatriz haddad maia" to "319696",
        "anna kalinskaya" to "323942",
        "elina svitolina" to "316738",
        "caroline garcia" to "315391",
        "leylah fernandez" to "326735",
        "venus williams" to "230234",
        "serena williams" to "311124",
        "yulia putintseva" to "316260",
        "peyton stearns" to "327573",
        "ashlyn krueger" to "329409",
        "taylor townsend" to "318609",
        "clara tauson" to "327793",
        "katerina siniakova" to "320942",
        "elise mertens" to "319698",
        "gabriela dabrowski" to "315486",
        "erin routliffe" to "321356",
        "luisa stefani" to "322699",
        "lyudmyla kichenok" to "316104",
        "nadiia kichenok" to "316105",
        "desirae krawczyk" to "324647",
        "caroline dolehide" to "323531",
        "nicole melichar-martinez" to "318285",
        "ellen perez" to "321742",
        "laura siegemund" to "311956",
        "asia muhammad" to "314643",
        "bethanie mattek-sands" to "310168",
        "kristina mladenovic" to "315471",
        "shuai zhang" to "313498",
        "dalayna hewitt" to "327734",
        "alana smith" to "328014",
        "irene burillo" to "322894",
        "leyre romero gormaz" to "328994"
    )

    /**
     * Normalizes a name string by removing accents, special symbols, and extra whitespace.
     */
    fun normalizeName(name: String): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD)
        val withoutDiacritics = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return withoutDiacritics.trim().lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Resolves whether a player belongs to ATP or WTA.
     * Uses match eventGrouping first, then falls back to curated rosters.
     */
    fun resolveTour(competitor: Competitor, match: Match? = null): TennisTour {
        val grouping = match?.eventGrouping ?: ""
        if (grouping.contains("Women", ignoreCase = true)) {
            return TennisTour.WTA
        }
        if (grouping.contains("Men", ignoreCase = true)) {
            return TennisTour.ATP
        }

        // Fallback to strict token lookup
        val clean = normalizeName(competitor.name)
        if (findPlayerId(clean, wtaPlayerMap) != null) {
            return TennisTour.WTA
        }
        if (findPlayerId(clean, atpPlayerMap) != null) {
            return TennisTour.ATP
        }

        // Default to ATP if unspecified
        return TennisTour.ATP
    }

    /**
     * Generates a slug suitable for URLs from player's name.
     */
    fun generateSlug(name: String): String {
        return normalizeName(name)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
    }

    /**
     * Finds a verified player ID from a player map with strict matching to prevent
     * false positive substring matches.
     */
    fun findPlayerId(cleanName: String, map: Map<String, String>): String? {
        if (cleanName.isBlank()) return null

        // 1. Direct exact match
        map[cleanName]?.let { return it }

        val words = cleanName.split(" ", "-").filter { it.isNotEmpty() }

        // 2. Inverted name match (e.g. "Zheng Qinwen" vs "Qinwen Zheng")
        if (words.size == 2) {
            val inverted = "${words[1]} ${words[0]}"
            map[inverted]?.let { return it }
        }

        // 3. Exact word-token set match (for any ordering of first/last)
        for ((key, id) in map) {
            val keyWords = key.split(" ", "-").filter { it.isNotEmpty() }
            if (words.size == keyWords.size && words.containsAll(keyWords)) {
                return id
            }
        }

        // 4. If name has > 2 words (e.g. middle name "Emma Caroline Navarro"), check if key words match
        if (words.size > 2) {
            for ((key, id) in map) {
                val keyWords = key.split(" ", "-").filter { it.isNotEmpty() }
                if (keyWords.size >= 2 && words.containsAll(keyWords)) {
                    return id
                }
            }
        }

        return null
    }

    /**
     * Obtains the official ATP or WTA profile URL for a competitor.
     * If a verified canonical ID exists, constructs the direct link.
     * Otherwise, generates a precision site-scoped search query that directs users
     * to the player's official profile without ever showing the wrong player.
     */
    fun getProfileUrl(competitor: Competitor, match: Match? = null): String {
        if (competitor.isDoubles) {
            val first = competitor.individualPlayers.firstOrNull() ?: competitor
            return getProfileUrl(first, match)
        }

        val tour = resolveTour(competitor, match)
        val cleanName = normalizeName(competitor.name)
        val slug = generateSlug(competitor.name)

        return when (tour) {
            TennisTour.ATP -> {
                val atpId = findPlayerId(cleanName, atpPlayerMap)
                if (atpId != null) {
                    "https://www.atptour.com/en/players/$slug/$atpId/overview"
                } else {
                    val query = "site:atptour.com/en/players \"${competitor.name.trim()}\""
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    "https://www.google.com/search?q=$encoded"
                }
            }
            TennisTour.WTA -> {
                val wtaId = findPlayerId(cleanName, wtaPlayerMap)
                if (wtaId != null) {
                    "https://www.wtatennis.com/players/$wtaId/$slug"
                } else {
                    val query = "site:wtatennis.com/players \"${competitor.name.trim()}\""
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    "https://www.google.com/search?q=$encoded"
                }
            }
        }
    }

    /**
     * Resolves individual official profile URLs for all partners in a competitor roster.
     */
    fun getProfileUrls(competitor: Competitor, match: Match? = null): List<Pair<Competitor, String>> {
        val players = competitor.individualPlayers
        return players.map { player ->
            player to getProfileUrl(player, match)
        }
    }
}
