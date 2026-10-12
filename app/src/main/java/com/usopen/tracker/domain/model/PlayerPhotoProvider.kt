package com.usopen.tracker.domain.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PlayerPhotoProvider {

    private val DIACRITICS_REGEX = Regex("\\p{M}")
    private const val USER_AGENT = "TennisTracker/1.0 (Android; dev@tennis.tracker)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // In-memory cache for dynamically resolved Wikipedia photos (normalized name -> thumbnail URL)
    internal val dynamicPhotoCache = ConcurrentHashMap<String, String>()

    /**
     * Curated, pre-verified high-quality Wikimedia Commons headshot thumbnails
     * for top ATP & WTA singles and doubles stars.
     */
    private val curatedPlayerPhotos = mapOf(
        // Top ATP Singles
        "carlos alcaraz" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d4/25th_Laureus_World_Sports_Awards_-_Red_Carpet_-_Carlos_Alcaraz_-_240422_192324_%28cropped%29.jpg/330px-25th_Laureus_World_Sports_Awards_-_Red_Carpet_-_Carlos_Alcaraz_-_240422_192324_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "jannik sinner" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/64/Jannik_Sinner_2025_US_Open.jpg/330px-Jannik_Sinner_2025_US_Open.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "novak djokovic" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d1/Novak_Djokovic_Paris_2024_Olympic_Games_%28cropped%29.jpg/330px-Novak_Djokovic_Paris_2024_Olympic_Games_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "novak dokovic" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d1/Novak_Djokovic_Paris_2024_Olympic_Games_%28cropped%29.jpg/330px-Novak_Djokovic_Paris_2024_Olympic_Games_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "alexander zverev" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/ca/Alexander_Zverev_at_the_2024_French_Open_%28cropped%29.jpg/330px-Alexander_Zverev_at_the_2024_French_Open_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "daniil medvedev" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/4b/Daniil_Medvedev_at_2025_Miami_Open_01_%28cropped%29.jpg/330px-Daniil_Medvedev_at_2025_Miami_Open_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "taylor fritz" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e6/Taylor_Fritz_%282023_DC_Open%29_02_%28cropped%29.jpg/330px-Taylor_Fritz_%282023_DC_Open%29_02_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "tommy paul" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a4/Tommy_Paul_at_the_2026_Delray_Beach_Open.jpg/330px-Tommy_Paul_at_the_2026_Delray_Beach_Open.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "frances tiafoe" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a3/FrancesTiafo.jpg/330px-FrancesTiafo.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "ben shelton" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1b/Ben_Shelton%2C_media_conference%2C_Swiss_Indoors_Basel_2025_%28cropped%29.jpg/330px-Ben_Shelton%2C_media_conference%2C_Swiss_Indoors_Basel_2025_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "casper ruud" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/26/Ruud_RG22_%2858%29_%2852144535415%29.jpg/330px-Ruud_RG22_%2858%29_%2852144535415%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "andrey rublev" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/4/40/Andrey_Rublev_%282023_US_Open%29_02_%28cropped%29.jpg/330px-Andrey_Rublev_%282023_US_Open%29_02_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "stefanos tsitsipas" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a2/Tsitsipas_S._MCM22_%28cropped%29.jpg/330px-Tsitsipas_S._MCM22_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "grigor dimitrov" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/18/Grigor_Dimitrov_%282023_DC_Open%29_01.jpg/330px-Grigor_Dimitrov_%282023_DC_Open%29_01.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "jack draper" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/70/Draper_MCM23_%2855%29_%2852883527225%29.jpg/330px-Draper_MCM23_%2855%29_%2852883527225%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "alex de minaur" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/13/Alex_de_Minaur_2024.jpg/330px-Alex_de_Minaur_2024.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "holger rune" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3e/Rune_RG22_%284%29_%2852144534990%29.jpg/330px-Rune_RG22_%284%29_%2852144534990%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "sebastian korda" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/66/Sebastian_Korda_%282023_DC_Open%29_02.jpg/330px-Sebastian_Korda_%282023_DC_Open%29_02.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "lorenzo musetti" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/64/Musetti_MCM23_%2811%29_%2852883593753%29.jpg/330px-Musetti_MCM23_%2811%29_%2852883593753%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "hubert hurkacz" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fe/Hurkacz_RG19_%2810%29_%2848199235282%29.jpg/330px-Hurkacz_RG19_%2810%29_%2848199235282%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "felix auger-aliassime" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2c/F%C3%A9lix_Auger-Aliassime_%282023_DC_Open%29_01_%28cropped%29.jpg/330px-F%C3%A9lix_Auger-Aliassime_%282023_DC_Open%29_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "felix auger aliassime" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2c/F%C3%A9lix_Auger-Aliassime_%282023_DC_Open%29_01_%28cropped%29.jpg/330px-F%C3%A9lix_Auger-Aliassime_%282023_DC_Open%29_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "arthur fils" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/89/Fils_BOR22_%2852083970501%29.jpg/330px-Fils_BOR22_%2852083970501%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "flavio cobolli" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/53/Cobolli_RGQ23_%2852944096963%29.jpg/330px-Cobolli_RGQ23_%2852944096963%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "ugo humbert" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7e/Ugo_Humbert%2C_Swiss_Indoors_Basel_%28cropped%29.jpg/330px-Ugo_Humbert%2C_Swiss_Indoors_Basel_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "karen khachanov" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/df/Karen_Khachanov_%282024_DC_Open%29_02.jpg/330px-Karen_Khachanov_%282024_DC_Open%29_02.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "jordan thompson" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5c/Thompson_RG21_%285%29_%2851376814569%29.jpg/330px-Thompson_RG21_%285%29_%2851376814569%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "alexei popyrin" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/Popyrin_RG19_%2846%29_%2848199101562%29.jpg/330px-Popyrin_RG19_%2846%29_%2848199101562%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "brandon nakashima" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/be/Brandon_Nakashima_%282024_DC_Open%29_01_%28cropped%29.jpg/330px-Brandon_Nakashima_%282024_DC_Open%29_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "matteo berrettini" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0d/Matteo_Berrettini_at_2025_Miami_Open_01_%28cropped%29_%28cropped%29.jpg/330px-Matteo_Berrettini_at_2025_Miami_Open_01_%28cropped%29_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "gael monfils" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a0/Ga%C3%ABl_Monfils_at_Sports_Festival_2026.jpg/330px-Ga%C3%ABl_Monfils_at_Sports_Festival_2026.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "nick kyrgios" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f6/Kyrgios_WM19_%2875%29_%2848521949772%29.jpg/330px-Kyrgios_WM19_%2875%29_%2848521949772%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "stan wawrinka" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d9/Wawrinka_RG19_%2816%29_%2848199015787%29.jpg/330px-Wawrinka_RG19_%2816%29_%2848199015787%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "rafael nadal" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/71/Rafael_Nadal_en_2024_%28cropped%29.jpg/330px-Rafael_Nadal_en_2024_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",

        // Top WTA Singles
        "aryna sabalenka" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/57/Aryna_Sabalenka_Miami_Open_Final.jpg/330px-Aryna_Sabalenka_Miami_Open_Final.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "iga swiatek" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/98/Iga_Swiatek_2023_Cropped_%2B_Retouched.jpg/330px-Iga_Swiatek_2023_Cropped_%2B_Retouched.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "coco gauff" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bb/Coco_Gauff_Miami_Open.jpg/330px-Coco_Gauff_Miami_Open.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "qinwen zheng" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0d/Zheng_Qinwen_%282024_US_Open%29_01_%28cropped%29.jpg/330px-Zheng_Qinwen_%282024_US_Open%29_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "zheng qinwen" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0d/Zheng_Qinwen_%282024_US_Open%29_01_%28cropped%29.jpg/330px-Zheng_Qinwen_%282024_US_Open%29_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "jessica pegula" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f1/Jessica_Pegula_%282025_DC_Open%29_01_%28cropped%29.jpg/330px-Jessica_Pegula_%282025_DC_Open%29_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "elena rybakina" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Elena_Rybakina_%282025_DC_Open%29_11_%28cropped%29.jpg/330px-Elena_Rybakina_%282025_DC_Open%29_11_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "jasmine paolini" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c1/Jasmine_Paolini_%282023_US_Open%29_01_%28cropped%29.jpg/330px-Jasmine_Paolini_%282023_US_Open%29_01_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "emma navarro" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b5/EmmaNavarro.jpg/330px-EmmaNavarro.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "mirra andreeva" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6d/Mirra_Andreeva_WMQ23_%28cropped%29.jpg/330px-Mirra_Andreeva_WMQ23_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "danielle collins" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/50/Danielle_Collins_%282025_DC_Open%29_02_%28cropped%29.jpg/330px-Danielle_Collins_%282025_DC_Open%29_02_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "barbora krejcikova" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/90/Barbora_Krej%C4%8D%C3%ADkov%C3%A1_%282023_US_Open%29_03_%28cropped%29.jpg/330px-Barbora_Krej%C4%8D%C3%ADkov%C3%A1_%282023_US_Open%29_03_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "daria kasatkina" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/dc/Daria_Kasatkina_%282024_DC_Open%29_07.jpg/330px-Daria_Kasatkina_%282024_DC_Open%29_07.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "donna vekic" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/11/Donna_Veki%C4%87_%2852171230817%29_%28cropped%29.jpg/330px-Donna_Veki%C4%87_%2852171230817%29_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "karolina muchova" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/57/Karol%C3%ADna_Muchov%C3%A1_%282023_US_Open%29_25_%28cropped2%29.jpg/330px-Karol%C3%ADna_Muchov%C3%A1_%282023_US_Open%29_25_%28cropped2%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "diana shnaider" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d4/Diana_Shnaider_%282023_US_Open%29_05_%28cropped%29.jpg/330px-Diana_Shnaider_%282023_US_Open%29_05_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "marta kostyuk" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/af/Marta_Kostyuk_%282023_US_Open%29_20_%28cropped%29.jpg/330px-Marta_Kostyuk_%282023_US_Open%29_20_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "paula badosa" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1d/Badosa_RG21_%2814%29_%2851376409698%29_%28cropped%29.jpg/330px-Badosa_RG21_%2814%29_%2851376409698%29_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "victoria azarenka" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6c/Victoria_Azarenka_%282024_DC_Open%29_02_%28cropped%29.jpg/330px-Victoria_Azarenka_%282024_DC_Open%29_02_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "maria sakkari" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bf/Maria_Sakkari_%282025_DC_Open%29_02_%28cropped%29.jpg/330px-Maria_Sakkari_%282025_DC_Open%29_02_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "ons jabeur" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/6/67/Ons_Jabeur_%282024_DC_Open%29_08_%28cropped%29.jpg/330px-Ons_Jabeur_%282024_DC_Open%29_08_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "elina svitolina" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/1/19/Elina_Svitolina_at_Sports_Festival_2026.jpg/330px-Elina_Svitolina_at_Sports_Festival_2026.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "leylah fernandez" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/af/Leylah_Fernandez_%282025_DC_Open%29_03_%28cropped%29.jpg/330px-Leylah_Fernandez_%282025_DC_Open%29_03_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "linda noskova" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9b/Linda_Noskov%C3%A1_%282023_US_Open%29_01_%28cropped2%29.jpg/330px-Linda_Noskov%C3%A1_%282023_US_Open%29_01_%28cropped2%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "katerina siniakova" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/53/2023-06_Kate%C5%99ina_Siniakov%C3%A1_autograph_%28cropped%29.jpg/330px-2023-06_Kate%C5%99ina_Siniakov%C3%A1_autograph_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "elise mertens" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/2c/Elise_Mertens_%282024_DC_Open%29_06_%28cropped%29.jpg/330px-Elise_Mertens_%282024_DC_Open%29_06_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "taylor townsend" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/84/Taylor_Townsend_%282025_DC_Open%29_10_%28cropped%29.jpg/330px-Taylor_Townsend_%282025_DC_Open%29_10_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "naomi osaka" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d6/NaomiOsaka-smile-2020_%28cropped_tight%29.png/330px-NaomiOsaka-smile-2020_%28cropped_tight%29.png?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "emma raducanu" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7a/Transylvania_Open_2026_-_Emma_Raducanu_vs_Greet_Minnen_6-0%2C_6-4_-_01.02.2026_%2855076013277%29_%28cropped%29.jpg/330px-Transylvania_Open_2026_-_Emma_Raducanu_vs_Greet_Minnen_6-0%2C_6-4_-_01.02.2026_%2855076013277%29_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",

        // Doubles Stars
        "rajeev ram" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f0/Ram_RG22_%2814%29_%2852144533385%29.jpg/330px-Ram_RG22_%2814%29_%2852144533385%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "joe salisbury" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8c/Salisbury_RG22_%281%29_%2852144292229%29.jpg/330px-Salisbury_RG22_%281%29_%2852144292229%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "matthew ebden" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Ebden_RG22_%2854%29_%2852144570795%29.jpg/330px-Ebden_RG22_%2854%29_%2852144570795%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "rohan bopanna" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/89/Bopanna_WM19_%287%29_%2848521871331%29.jpg/330px-Bopanna_WM19_%287%29_%2848521871331%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "marcel granollers" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d9/Granollers_RG21_%285%29_%2851375380137%29.jpg/330px-Granollers_RG21_%285%29_%2851375380137%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "horacio zeballos" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/8/83/Horacio_Zeballos_%2819826056265%29.jpg/330px-Horacio_Zeballos_%2819826056265%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "gabriela dabrowski" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f4/Dabrowski_RG19_%2848%29_%2848199313486%29.jpg/330px-Dabrowski_RG19_%2848%29_%2848199313486%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "erin routliffe" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ee/Erin_Routliffe_2024_%28cropped%29.jpg/330px-Erin_Routliffe_2024_%28cropped%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail",
        "luisa stefani" to "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0d/TENFEM_WR_01.08.21-3244_%28cropped_Stefani%29.jpg/330px-TENFEM_WR_01.08.21-3244_%28cropped_Stefani%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"
    )

    /**
     * Normalizes a player name by stripping accents/diacritics, transliterating special letters,
     * converting to lowercase, and collapsing whitespace.
     */
    fun normalizeName(name: String): String {
        val replacedSpecial = name.trim()
            .replace("đ", "dj")
            .replace("Đ", "dj")
            .replace('ł', 'l')
            .replace('Ł', 'l')
            .replace('ø', 'o')
            .replace('Ø', 'o')
            .replace("ß", "ss")
        val decomposed = Normalizer.normalize(replacedSpecial, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Synchronously resolves a player photo URL.
     * Rejects defunct espncdn.com headshot URLs and looks up in curated list or dynamic cache.
     */
    fun getPhotoUrl(name: String, @Suppress("UNUSED_PARAMETER") athleteId: String? = null, existingUrl: String? = null): String? {
        if (!existingUrl.isNullOrBlank() && !existingUrl.contains("espncdn.com")) {
            return existingUrl
        }

        val normalized = normalizeName(name)
        if (normalized.isEmpty()) return null

        // 1. Check curated exact and inverted matches
        val curated = findCuratedUrl(normalized)
        if (curated != null) return curated

        // 2. Check dynamic cache (if previously resolved from Wikipedia)
        val dynamic = dynamicPhotoCache[normalized]
        if (!dynamic.isNullOrBlank()) return dynamic

        return null
    }

    fun getPhotoUrl(competitor: Competitor): String? {
        return getPhotoUrl(
            name = competitor.name,
            athleteId = competitor.id,
            existingUrl = competitor.photoUrl
        )
    }

    /**
     * Asynchronously resolves a player photo from curated entries or by querying the
     * Wikipedia summary API for unlisted players.
     */
    suspend fun resolvePhotoUrl(name: String): String? {
        val immediate = getPhotoUrl(name)
        if (immediate != null) return immediate

        val normalized = normalizeName(name)
        if (normalized.isEmpty()) return null

        // If previously queried and determined not found (negative cache)
        if (dynamicPhotoCache[normalized] == "") return null

        return withContext(Dispatchers.IO) {
            try {
                val rawCandidates = listOf(
                    name.trim(),
                    "${name.trim()} (tennis)"
                )

                for (cand in rawCandidates) {
                    val encoded = URLEncoder.encode(cand.replace(' ', '_'), "UTF-8")
                    val apiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
                    val request = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()

                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyStr = resp.body?.string()
                            if (!bodyStr.isNullOrBlank()) {
                                val json = JSONObject(bodyStr)
                                val pageType = json.optString("type")
                                if (pageType != "disambiguation") {
                                    val thumb = json.optJSONObject("thumbnail")?.optString("source")
                                    if (!thumb.isNullOrBlank()) {
                                        dynamicPhotoCache[normalized] = thumb
                                        return@withContext thumb
                                    }
                                }
                            }
                        }
                    }
                }

                // Negative cache to prevent redundant queries
                dynamicPhotoCache[normalized] = ""
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    suspend fun resolvePhotoUrl(competitor: Competitor): String? {
        val direct = competitor.photoUrl?.takeIf { !it.contains("espncdn.com") }
        if (direct != null) return direct
        return resolvePhotoUrl(competitor.name)
    }

    private fun findCuratedUrl(normalized: String): String? {
        // Direct match
        curatedPlayerPhotos[normalized]?.let { return it }

        // Inverted first/last name (e.g. "zheng qinwen" vs "qinwen zheng")
        val parts = normalized.split(" ")
        if (parts.size == 2) {
            val inverted = "${parts[1]} ${parts[0]}"
            curatedPlayerPhotos[inverted]?.let { return it }

            // Initial + Surname (e.g. 'c alcaraz')
            if (parts[0].length == 1) {
                for ((k, v) in curatedPlayerPhotos) {
                    if (k.startsWith(parts[0]) && k.endsWith(" ${parts[1]}")) {
                        return v
                    }
                }
            }
            // Surname + Initial (e.g. 'alcaraz c')
            if (parts[1].length == 1) {
                for ((k, v) in curatedPlayerPhotos) {
                    if (k.startsWith(parts[1]) && k.endsWith(" ${parts[0]}")) {
                        return v
                    }
                }
            }
        }

        // Surname-only match (e.g. "alcaraz" or "sinner")
        if (parts.size == 1) {
            for ((k, v) in curatedPlayerPhotos) {
                if (k.endsWith(" $normalized") || k == normalized) {
                    return v
                }
            }
        }

        // General substring match
        return curatedPlayerPhotos.entries.firstOrNull { (k, _) ->
            normalized == k ||
            normalized.contains(k) ||
            k.contains(normalized)
        }?.value
    }

    fun clearDynamicCache() {
        dynamicPhotoCache.clear()
    }
}
