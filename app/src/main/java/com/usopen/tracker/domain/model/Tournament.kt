package com.usopen.tracker.domain.model

/**
 * Domain abstraction representing a tennis tournament with rich tour, category, and surface metadata.
 */
data class Tournament(
    val id: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
    val defaultVenue: String = "Center Court",
    val isGrandSlam: Boolean = false,
    val tour: TourType = TourType.COMBINED,
    val category: TournamentCategory = if (isGrandSlam) TournamentCategory.GRAND_SLAM else TournamentCategory.ATP_250,
    val surface: CourtSurface = CourtSurface.HARD,
    val city: String? = null,
    val country: String? = null
) {
    /**
     * Secondary constructor preserving 100% binary backward compatibility with pre-compiled modules.
     */
    constructor(
        id: String,
        displayName: String,
        aliases: Set<String> = emptySet(),
        defaultVenue: String = "Center Court",
        isGrandSlam: Boolean = false
    ) : this(
        id = id,
        displayName = displayName,
        aliases = aliases,
        defaultVenue = defaultVenue,
        isGrandSlam = isGrandSlam,
        tour = TourType.COMBINED,
        category = if (isGrandSlam) TournamentCategory.GRAND_SLAM else TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = null,
        country = null
    )

    /**
     * Checks whether an event or competition tournament string matches this tournament.

     * Matches against displayName and known aliases (case-insensitive, trimmed).
     * If tournament is 'ALL', matches any non-empty tournament.
     */
    fun matches(rawTournamentName: String?): Boolean {
        if (rawTournamentName.isNullOrBlank()) return false
        if (this.id.equals("all", ignoreCase = true) || this.id.startsWith("all_")) return true
        val clean = rawTournamentName.trim().lowercase()
        val displayClean = displayName.lowercase()
        if (clean == displayClean || clean.contains(displayClean) || Regex("\\b" + Regex.escape(clean) + "\\b").containsMatchIn(displayClean)) {
            return true
        }
        return aliases.any { alias ->
            val aliasClean = alias.trim().lowercase()
            if (aliasClean.length < 4) {
                clean == aliasClean
            } else {
                clean == aliasClean || clean.contains(aliasClean) || Regex("\\b" + Regex.escape(clean) + "\\b").containsMatchIn(aliasClean)
            }
        }
    }
}

/**
 * Standard tournament definitions and comprehensive official ATP & WTA canonical registry.
 */
object Tournaments {
    val ALL = Tournament(
        id = "all",
        displayName = "All Tournaments",
        aliases = setOf("All", "Any"),
        defaultVenue = "Center Court",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD
    )

    // ==========================================
    // Grand Slams (Major Championships)
    // ==========================================
    val US_OPEN = Tournament(
        id = "us_open",
        displayName = "US Open",
        aliases = setOf("US Open", "U.S. Open", "United States Open", "US Open Tennis", "Flushing Meadows"),
        defaultVenue = "Arthur Ashe Stadium",
        isGrandSlam = true,
        tour = TourType.COMBINED,
        category = TournamentCategory.GRAND_SLAM,
        surface = CourtSurface.HARD,
        city = "New York",
        country = "USA"
    )

    val AUSTRALIAN_OPEN = Tournament(
        id = "australian_open",
        displayName = "Australian Open",
        aliases = setOf("Australian Open", "Aus Open", "AO"),
        defaultVenue = "Rod Laver Arena",
        isGrandSlam = true,
        tour = TourType.COMBINED,
        category = TournamentCategory.GRAND_SLAM,
        surface = CourtSurface.HARD,
        city = "Melbourne",
        country = "Australia"
    )

    val ROLAND_GARROS = Tournament(
        id = "roland_garros",
        displayName = "Roland Garros",
        aliases = setOf("Roland Garros", "French Open", "Roland-Garros", "RG", "Internationaux de France"),
        defaultVenue = "Court Philippe-Chatrier",
        isGrandSlam = true,
        tour = TourType.COMBINED,
        category = TournamentCategory.GRAND_SLAM,
        surface = CourtSurface.CLAY,
        city = "Paris",
        country = "France"
    )

    val WIMBLEDON = Tournament(
        id = "wimbledon",
        displayName = "Wimbledon",
        aliases = setOf("Wimbledon", "The Championships, Wimbledon", "The Championships", "SW19"),
        defaultVenue = "Centre Court",
        isGrandSlam = true,
        tour = TourType.COMBINED,
        category = TournamentCategory.GRAND_SLAM,
        surface = CourtSurface.GRASS,
        city = "London",
        country = "United Kingdom"
    )

    // ==========================================
    // Masters 1000 & WTA 1000 Tournaments
    // ==========================================
    val INDIAN_WELLS = Tournament(
        id = "indian_wells",
        displayName = "Indian Wells",
        aliases = setOf("Indian Wells", "BNP Paribas Open", "Indian Wells Masters"),
        defaultVenue = "Stadium 1",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.HARD,
        city = "Indian Wells",
        country = "USA"
    )

    val MIAMI_OPEN = Tournament(
        id = "miami_open",
        displayName = "Miami Open",
        aliases = setOf("Miami Open", "Miami", "Miami Masters"),
        defaultVenue = "Stadium Court",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.HARD,
        city = "Miami",
        country = "USA"
    )

    val MONTE_CARLO_MASTERS = Tournament(
        id = "monte_carlo",
        displayName = "Monte-Carlo Masters",
        aliases = setOf("Monte-Carlo Masters", "Monte Carlo Masters", "Rolex Monte-Carlo Masters", "Monte-Carlo", "Monte Carlo"),
        defaultVenue = "Court Rainier III",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.CLAY,
        city = "Monte Carlo",
        country = "Monaco"
    )

    val MADRID_OPEN = Tournament(
        id = "madrid_open",
        displayName = "Madrid Open",
        aliases = setOf("Madrid Open", "Mutua Madrid Open", "Madrid"),
        defaultVenue = "Manolo Santana Stadium",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.CLAY,
        city = "Madrid",
        country = "Spain"
    )

    val ITALIAN_OPEN = Tournament(
        id = "italian_open",
        displayName = "Italian Open",
        aliases = setOf("Italian Open", "Rome", "Internazionali BNL d'Italia", "Rome Masters"),
        defaultVenue = "Campo Centrale",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.CLAY,
        city = "Rome",
        country = "Italy"
    )

    val CANADIAN_OPEN = Tournament(
        id = "canadian_open",
        displayName = "Canadian Open",
        aliases = setOf("Canadian Open", "National Bank Open", "Omnium Banque Nationale", "Toronto Masters", "Montreal Masters", "Toronto", "Montreal"),
        defaultVenue = "Center Court",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.HARD,
        city = "Montreal/Toronto",
        country = "Canada"
    )

    val CINCINNATI_OPEN = Tournament(
        id = "cincinnati_open",
        displayName = "Cincinnati Open",
        aliases = setOf("Cincinnati Open", "Western & Southern Open", "Cincinnati Masters", "Cincinnati"),
        defaultVenue = "Center Court",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.HARD,
        city = "Cincinnati",
        country = "USA"
    )

    val SHANGHAI_MASTERS = Tournament(
        id = "shanghai_masters",
        displayName = "Shanghai Masters",
        aliases = setOf("Shanghai Masters", "Rolex Shanghai Masters", "Shanghai"),
        defaultVenue = "Stadium Court",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.HARD,
        city = "Shanghai",
        country = "China"
    )

    val PARIS_MASTERS = Tournament(
        id = "paris_masters",
        displayName = "Paris Masters",
        aliases = setOf("Paris Masters", "Rolex Paris Masters", "Paris Indoor", "Bercy"),
        defaultVenue = "Court Central",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.HARD,
        city = "Paris",
        country = "France"
    )

    val CHINA_OPEN = Tournament(
        id = "china_open",
        displayName = "China Open",
        aliases = setOf("China Open", "Beijing"),
        defaultVenue = "Diamond Court",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.WTA_1000,
        surface = CourtSurface.HARD,
        city = "Beijing",
        country = "China"
    )

    val WUHAN_OPEN = Tournament(
        id = "wuhan_open",
        displayName = "Wuhan Open",
        aliases = setOf("Wuhan Open", "Dongfeng Voyah Wuhan Open", "Wuhan"),
        defaultVenue = "Central Court",
        isGrandSlam = false,
        tour = TourType.WTA,
        category = TournamentCategory.WTA_1000,
        surface = CourtSurface.HARD,
        city = "Wuhan",
        country = "China"
    )

    val DUBAI_CHAMPIONSHIPS = Tournament(
        id = "dubai_tennis_championships",
        displayName = "Dubai Tennis Championships",
        aliases = setOf("Dubai Tennis Championships", "Dubai Duty Free Tennis Championships", "Dubai Open", "Dubai"),
        defaultVenue = "Centre Court",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.WTA_1000,
        surface = CourtSurface.HARD,
        city = "Dubai",
        country = "UAE"
    )

    val DOHA_OPEN = Tournament(
        id = "doha_open",
        displayName = "Qatar Open",
        aliases = setOf("Qatar TotalEnergies Open", "Qatar ExxonMobil Open", "Qatar Open", "Doha Open", "Doha"),
        defaultVenue = "Khalifa International Tennis Complex",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.WTA_1000,
        surface = CourtSurface.HARD,
        city = "Doha",
        country = "Qatar"
    )

    // ==========================================
    // Year-End Finals
    // ==========================================
    val ATP_FINALS = Tournament(
        id = "atp_finals",
        displayName = "ATP Finals",
        aliases = setOf("ATP Finals", "Nitto ATP Finals", "ATP Tour Finals"),
        defaultVenue = "Pala Alpitour",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.FINALS,
        surface = CourtSurface.HARD,
        city = "Turin",
        country = "Italy"
    )

    val WTA_FINALS = Tournament(
        id = "wta_finals",
        displayName = "WTA Finals",
        aliases = setOf("WTA Finals", "WTA Tour Finals"),
        defaultVenue = "King Saud University Arena",
        isGrandSlam = false,
        tour = TourType.WTA,
        category = TournamentCategory.FINALS,
        surface = CourtSurface.HARD,
        city = "Riyadh",
        country = "Saudi Arabia"
    )

    // ==========================================
    // ATP 500 & WTA 500 Tournaments
    // ==========================================
    val ROTTERDAM_OPEN = Tournament(
        id = "rotterdam_open",
        displayName = "Rotterdam Open",
        aliases = setOf("ABN AMRO Open", "Rotterdam"),
        defaultVenue = "Rotterdam Ahoy",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.HARD,
        city = "Rotterdam",
        country = "Netherlands"
    )

    val RIO_OPEN = Tournament(
        id = "rio_open",
        displayName = "Rio Open",
        aliases = setOf("Rio Open", "Rio"),
        defaultVenue = "Quadra Guga Kuerten",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.CLAY,
        city = "Rio de Janeiro",
        country = "Brazil"
    )

    val ACAPULCO_OPEN = Tournament(
        id = "acapulco_open",
        displayName = "Mexican Open",
        aliases = setOf("Abierto Mexicano Telcel", "Acapulco"),
        defaultVenue = "Estadio Arena GNP Seguros",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.HARD,
        city = "Acapulco",
        country = "Mexico"
    )

    val BARCELONA_OPEN = Tournament(
        id = "barcelona_open",
        displayName = "Barcelona Open",
        aliases = setOf("Barcelona Open Banc Sabadell", "Barcelona", "Trofeo Conde de Godó"),
        defaultVenue = "Pista Rafa Nadal",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.CLAY,
        city = "Barcelona",
        country = "Spain"
    )

    val QUEENS_CLUB = Tournament(
        id = "queens_club",
        displayName = "Queen's Club Championships",
        aliases = setOf("cinch Championships", "Queen's Club", "Queens Club", "Queen's"),
        defaultVenue = "Centre Court",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.GRASS,
        city = "London",
        country = "United Kingdom"
    )

    val HALLE_OPEN = Tournament(
        id = "halle_open",
        displayName = "Halle Open",
        aliases = setOf("Terra Wortmann Open", "Halle Open", "Halle"),
        defaultVenue = "OWL Arena",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.GRASS,
        city = "Halle",
        country = "Germany"
    )

    val WASHINGTON_OPEN = Tournament(
        id = "washington_open",
        displayName = "Washington Open",
        aliases = setOf("Mubadala Citi DC Open", "Citi Open", "Washington"),
        defaultVenue = "Stadium Court",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.HARD,
        city = "Washington, D.C.",
        country = "USA"
    )

    val TOKYO_OPEN = Tournament(
        id = "tokyo_open",
        displayName = "Japan Open",
        aliases = setOf("Kinoshita Group Japan Open", "Japan Open", "Tokyo"),
        defaultVenue = "Ariake Colosseum",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.HARD,
        city = "Tokyo",
        country = "Japan"
    )

    val VIENNA_OPEN = Tournament(
        id = "vienna_open",
        displayName = "Vienna Open",
        aliases = setOf("Erste Bank Open", "Vienna"),
        defaultVenue = "Wiener Stadthalle",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.HARD,
        city = "Vienna",
        country = "Austria"
    )

    val BASEL_OPEN = Tournament(
        id = "basel_open",
        displayName = "Swiss Indoors",
        aliases = setOf("Swiss Indoors Basel", "Basel"),
        defaultVenue = "St. Jakobshalle",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.HARD,
        city = "Basel",
        country = "Switzerland"
    )

    val CHARLESTON_OPEN = Tournament(
        id = "charleston_open",
        displayName = "Charleston Open",
        aliases = setOf("Credit One Charleston Open", "Charleston"),
        defaultVenue = "Credit One Stadium",
        isGrandSlam = false,
        tour = TourType.WTA,
        category = TournamentCategory.WTA_500,
        surface = CourtSurface.CLAY,
        city = "Charleston",
        country = "USA"
    )

    val STUTTGART_OPEN = Tournament(
        id = "stuttgart_open",
        displayName = "Stuttgart Open",
        aliases = setOf("Porsche Tennis Grand Prix", "Stuttgart WTA", "Stuttgart"),
        defaultVenue = "Porsche-Arena",
        isGrandSlam = false,
        tour = TourType.WTA,
        category = TournamentCategory.WTA_500,
        surface = CourtSurface.CLAY,
        city = "Stuttgart",
        country = "Germany"
    )

    val BERLIN_OPEN = Tournament(
        id = "berlin_open",
        displayName = "Berlin Ladies Open",
        aliases = setOf("ecotrans Ladies Open", "Berlin Open", "Berlin"),
        defaultVenue = "Steffi Graf Stadion",
        isGrandSlam = false,
        tour = TourType.WTA,
        category = TournamentCategory.WTA_500,
        surface = CourtSurface.GRASS,
        city = "Berlin",
        country = "Germany"
    )

    val SAN_DIEGO_OPEN = Tournament(
        id = "san_diego_open",
        displayName = "San Diego Open",
        aliases = setOf("Cymbiotika San Diego Open", "San Diego"),
        defaultVenue = "Barnes Tennis Center",
        isGrandSlam = false,
        tour = TourType.WTA,
        category = TournamentCategory.WTA_500,
        surface = CourtSurface.HARD,
        city = "San Diego",
        country = "USA"
    )

    // ==========================================
    // ATP 250 & WTA 250 Tournaments
    // ==========================================
    val BRISBANE_INTERNATIONAL = Tournament(
        id = "brisbane_international",
        displayName = "Brisbane International",
        aliases = setOf("Brisbane International", "Brisbane"),
        defaultVenue = "Pat Rafter Arena",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Brisbane",
        country = "Australia"
    )

    val ADELAIDE_INTERNATIONAL = Tournament(
        id = "adelaide_international",
        displayName = "Adelaide International",
        aliases = setOf("Adelaide International", "Adelaide"),
        defaultVenue = "The Drive",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Adelaide",
        country = "Australia"
    )

    val AUCKLAND_OPEN = Tournament(
        id = "auckland_open",
        displayName = "ASB Classic",
        aliases = setOf("ASB Classic", "Auckland Open", "Auckland"),
        defaultVenue = "ASB Tennis Centre",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Auckland",
        country = "New Zealand"
    )

    val DELRAY_BEACH_OPEN = Tournament(
        id = "delray_beach_open",
        displayName = "Delray Beach Open",
        aliases = setOf("Delray Beach Open", "Delray Beach"),
        defaultVenue = "Delray Beach Tennis Center",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Delray Beach",
        country = "USA"
    )

    val ESTORIL_OPEN = Tournament(
        id = "estoril_open",
        displayName = "Estoril Open",
        aliases = setOf("Millennium Estoril Open", "Estoril"),
        defaultVenue = "Clube de Ténis do Estoril",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.CLAY,
        city = "Estoril",
        country = "Portugal"
    )

    val GENEVA_OPEN = Tournament(
        id = "geneva_open",
        displayName = "Geneva Open",
        aliases = setOf("Gonet Geneva Open", "Geneva"),
        defaultVenue = "Tennis Club de Genève",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.CLAY,
        city = "Geneva",
        country = "Switzerland"
    )

    val STUTTGART_BOSS_OPEN = Tournament(
        id = "boss_open",
        displayName = "BOSS OPEN",
        aliases = setOf("BOSS OPEN", "Stuttgart ATP"),
        defaultVenue = "Tennis Club Weissenhof",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.GRASS,
        city = "Stuttgart",
        country = "Germany"
    )

    val EASTBOURNE_INTERNATIONAL = Tournament(
        id = "eastbourne_international",
        displayName = "Eastbourne International",
        aliases = setOf("Rothesay International", "Eastbourne"),
        defaultVenue = "Devonshire Park Lawn Tennis Club",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.GRASS,
        city = "Eastbourne",
        country = "United Kingdom"
    )

    val BASTAD_OPEN = Tournament(
        id = "bastad_open",
        displayName = "Nordea Open",
        aliases = setOf("Nordea Open", "Swedish Open", "Bastad", "Båstad"),
        defaultVenue = "Båstad Tennis Stadium",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.CLAY,
        city = "Båstad",
        country = "Sweden"
    )

    val GSTAAD_OPEN = Tournament(
        id = "gstaad_open",
        displayName = "Swiss Open Gstaad",
        aliases = setOf("Swiss Open Gstaad", "Gstaad"),
        defaultVenue = "Roy Emerson Arena",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.CLAY,
        city = "Gstaad",
        country = "Switzerland"
    )

    val KITZBUHEL_OPEN = Tournament(
        id = "kitzbuhel_open",
        displayName = "Austrian Open Kitzbühel",
        aliases = setOf("Generali Open Kitzbühel", "Kitzbühel", "Kitzbuhel"),
        defaultVenue = "Kitzbühel Tennis Stadium",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.CLAY,
        city = "Kitzbühel",
        country = "Austria"
    )

    val WINSTON_SALEM_OPEN = Tournament(
        id = "winston_salem_open",
        displayName = "Winston-Salem Open",
        aliases = setOf("Winston-Salem Open", "Winston-Salem"),
        defaultVenue = "Wake Forest Tennis Complex",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Winston-Salem",
        country = "USA"
    )

    val CHENGDU_OPEN = Tournament(
        id = "chengdu_open",
        displayName = "Chengdu Open",
        aliases = setOf("Chengdu Open", "Chengdu"),
        defaultVenue = "Sichuan International Tennis Center",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Chengdu",
        country = "China"
    )

    val ZHUHAI_CHAMPIONSHIPS = Tournament(
        id = "zhuhai_championships",
        displayName = "Zhuhai Championships",
        aliases = setOf("Huafa Properties Zhuhai Championships", "Zhuhai"),
        defaultVenue = "Hengqin International Tennis Center",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Zhuhai",
        country = "China"
    )

    val STOCKHOLM_OPEN = Tournament(
        id = "stockholm_open",
        displayName = "Stockholm Open",
        aliases = setOf("BNP Paribas Nordic Open", "Stockholm Open", "Stockholm"),
        defaultVenue = "Kungliga tennishallen",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Stockholm",
        country = "Sweden"
    )

    val ANTWERP_OPEN = Tournament(
        id = "antwerp_open",
        displayName = "European Open",
        aliases = setOf("European Open", "Antwerp"),
        defaultVenue = "Lotto Arena",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD,
        city = "Antwerp",
        country = "Belgium"
    )

    // ==========================================
    // Curated Lists
    // ==========================================
    val GRAND_SLAMS: List<Tournament> = listOf(
        US_OPEN,
        AUSTRALIAN_OPEN,
        ROLAND_GARROS,
        WIMBLEDON
    )

    val MASTERS_1000_LIST: List<Tournament> = listOf(
        INDIAN_WELLS,
        MIAMI_OPEN,
        MONTE_CARLO_MASTERS,
        MADRID_OPEN,
        ITALIAN_OPEN,
        CANADIAN_OPEN,
        CINCINNATI_OPEN,
        SHANGHAI_MASTERS,
        PARIS_MASTERS,
        CHINA_OPEN,
        WUHAN_OPEN,
        DUBAI_CHAMPIONSHIPS,
        DOHA_OPEN
    )

    val FINALS_LIST: List<Tournament> = listOf(
        ATP_FINALS,
        WTA_FINALS
    )

    val ATP_500_LIST: List<Tournament> = listOf(
        ROTTERDAM_OPEN,
        RIO_OPEN,
        ACAPULCO_OPEN,
        BARCELONA_OPEN,
        QUEENS_CLUB,
        HALLE_OPEN,
        WASHINGTON_OPEN,
        TOKYO_OPEN,
        VIENNA_OPEN,
        BASEL_OPEN
    )

    val WTA_500_LIST: List<Tournament> = listOf(
        CHARLESTON_OPEN,
        STUTTGART_OPEN,
        BERLIN_OPEN,
        SAN_DIEGO_OPEN
    )

    val ATP_250_LIST: List<Tournament> = listOf(
        BRISBANE_INTERNATIONAL,
        ADELAIDE_INTERNATIONAL,
        AUCKLAND_OPEN,
        DELRAY_BEACH_OPEN,
        ESTORIL_OPEN,
        GENEVA_OPEN,
        STUTTGART_BOSS_OPEN,
        EASTBOURNE_INTERNATIONAL,
        BASTAD_OPEN,
        GSTAAD_OPEN,
        KITZBUHEL_OPEN,
        WINSTON_SALEM_OPEN,
        CHENGDU_OPEN,
        ZHUHAI_CHAMPIONSHIPS,
        STOCKHOLM_OPEN,
        ANTWERP_OPEN
    )

    /**
     * Preserves exact backwards compatibility with original 9 popular tournaments.
     */
    val POPULAR_TOURNAMENTS: List<Tournament> = listOf(
        ALL,
        US_OPEN,
        AUSTRALIAN_OPEN,
        ROLAND_GARROS,
        WIMBLEDON,
        INDIAN_WELLS,
        MIAMI_OPEN,
        MADRID_OPEN,
        ITALIAN_OPEN
    )

    /**
     * Complete canonical registry of official tournaments.
     */
    val ALL_CANONICAL_TOURNAMENTS: List<Tournament> = listOf(
        US_OPEN,
        AUSTRALIAN_OPEN,
        ROLAND_GARROS,
        WIMBLEDON,
        INDIAN_WELLS,
        MIAMI_OPEN,
        MONTE_CARLO_MASTERS,
        MADRID_OPEN,
        ITALIAN_OPEN,
        CANADIAN_OPEN,
        CINCINNATI_OPEN,
        SHANGHAI_MASTERS,
        PARIS_MASTERS,
        CHINA_OPEN,
        WUHAN_OPEN,
        DUBAI_CHAMPIONSHIPS,
        DOHA_OPEN,
        ATP_FINALS,
        WTA_FINALS,
        ROTTERDAM_OPEN,
        RIO_OPEN,
        ACAPULCO_OPEN,
        BARCELONA_OPEN,
        QUEENS_CLUB,
        HALLE_OPEN,
        WASHINGTON_OPEN,
        TOKYO_OPEN,
        VIENNA_OPEN,
        BASEL_OPEN,
        CHARLESTON_OPEN,
        STUTTGART_OPEN,
        BERLIN_OPEN,
        SAN_DIEGO_OPEN,
        BRISBANE_INTERNATIONAL,
        ADELAIDE_INTERNATIONAL,
        AUCKLAND_OPEN,
        DELRAY_BEACH_OPEN,
        ESTORIL_OPEN,
        GENEVA_OPEN,
        STUTTGART_BOSS_OPEN,
        EASTBOURNE_INTERNATIONAL,
        BASTAD_OPEN,
        GSTAAD_OPEN,
        KITZBUHEL_OPEN,
        WINSTON_SALEM_OPEN,
        CHENGDU_OPEN,
        ZHUHAI_CHAMPIONSHIPS,
        STOCKHOLM_OPEN,
        ANTWERP_OPEN
    )

    /**
     * Finds a tournament by ID, searching all canonical tournaments and defaulting to ALL if not found.
     */
    fun fromId(id: String): Tournament {
        if (id.equals("all", ignoreCase = true)) return ALL
        return ALL_CANONICAL_TOURNAMENTS.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALL
    }
}
