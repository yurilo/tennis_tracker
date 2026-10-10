package com.usopen.tracker.domain.model

object PlayerPhotoProvider {

    private const val ESPN_HEADSHOT_BASE = "https://a.espncdn.com/combiner/i?img=/i/headshots/tennis/players/full"
    private const val ESPN_PARAMS = "w=350&h=254"

    private val playerToEspnId = mapOf(
        "carlos alcaraz" to "4094",
        "jannik sinner" to "4096",
        "novak djokovic" to "296",
        "alexander zverev" to "3057",
        "daniil medvedev" to "3532",
        "taylor fritz" to "3223",
        "tommy paul" to "3228",
        "frances tiafoe" to "3224",
        "ben shelton" to "4567",
        "casper ruud" to "3494",
        "andrey rublev" to "3058",
        "stefanos tsitsipas" to "3371",
        "grigor dimitrov" to "1676",
        "jack draper" to "4204",
        "alex de minaur" to "3588",
        "holger rune" to "4141",
        "sebastian korda" to "3592",
        "lorenzo musetti" to "4201",
        "aryna sabalenka" to "3406",
        "iga swiatek" to "3526",
        "coco gauff" to "4119",
        "qinwen zheng" to "4287",
        "zheng qinwen" to "4287",
        "emma navarro" to "4132",
        "jessica pegula" to "1866",
        "karolina muchova" to "3421",
        "linda noskova" to "4458",
        "ann li" to "3790",
        "donna vekic" to "1963",
        "jasmine paolini" to "2866",
        "elena rybakina" to "3550",
        "mirra andreeva" to "4832",
        "rajeev ram" to "1103",
        "joe salisbury" to "3425",
        "elise mertens" to "2787",
        "diana shnaider" to "4693",
        "katerina siniakova" to "2790",
        "barbora krejcikova" to "2788",
        "taylor townsend" to "2552",
        "luisa stefani" to "2732",
        "gabriela dabrowski" to "1863",
        "erin routliffe" to "3572",
        "dalayna hewitt" to "5086",
        "alana smith" to "4720",
        "irene burillo" to "4546",
        "leyre romero gormaz" to "10676",
        "lucia bronzetti" to "4666"
    )

    fun getPhotoUrl(name: String, athleteId: String? = null, existingUrl: String? = null): String? {
        if (!existingUrl.isNullOrBlank()) return existingUrl

        val normalized = name.trim().lowercase()
        val espnId = playerToEspnId[normalized]
            ?: playerToEspnId.entries.firstOrNull { normalized.contains(it.key) || it.key.contains(normalized) }?.value
            ?: athleteId?.takeIf { it.toIntOrNull() != null && it.toInt() > 100 }

        return if (espnId != null) {
            "$ESPN_HEADSHOT_BASE/$espnId.png&$ESPN_PARAMS"
        } else {
            null
        }
    }

    fun getPhotoUrl(competitor: Competitor): String? {
        return getPhotoUrl(
            name = competitor.name,
            athleteId = competitor.id,
            existingUrl = competitor.photoUrl
        )
    }
}
