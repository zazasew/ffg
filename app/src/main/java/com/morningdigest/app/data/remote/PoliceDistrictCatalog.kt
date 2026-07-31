package com.morningdigest.app.data.remote

/**
 * A curated, offline reference of Norway's 12 police districts and the
 * municipalities each one covers - so the Settings municipality picker can
 * offer a district first, then a list of cities/municipalities inside it,
 * the same way the official Politiloggen app does.
 *
 * Why this exists at all: the Politiloggen API (see [PoliceIncidentFetcher])
 * has no municipality list endpoint, only a live `/districts` endpoint with
 * the 12 real district names/ids. Populating the second level purely from
 * "municipalities seen in recent incidents" silently excludes anywhere that
 * simply hasn't had a recent incident logged - which is exactly why e.g.
 * Finnmark and Kirkenes (Sør-Varanger) could go missing: Finnmark is a huge,
 * sparsely populated district, and small/quiet municipalities there can go
 * a while between log entries.
 *
 * This list is the fixed geography (compiled from Kartverket/SSB's public
 * county and police-district reference data) that guarantees every
 * municipality is always selectable regardless of how quiet its local log
 * has been lately. It's merged with live data in [PoliceIncidentFetcher] so
 * the picker still surfaces anything new that shows up in the feed.
 *
 * Norway's municipality boundaries do occasionally change (mergers/splits),
 * so this may need the odd update over time - the app still works fine even
 * if a brand new municipality name is missing here, since it will show up
 * automatically the moment it has a logged incident.
 */
object PoliceDistrictCatalog {

    data class DistrictEntry(val displayName: String, val municipalities: List<String>)

    /**
     * Keyed by a normalized district name (lowercase, "politidistrikt"
     * suffix stripped) so it can be matched against whatever exact string
     * the live `/districts` endpoint returns.
     */
    val DISTRICTS: List<DistrictEntry> = listOf(
        DistrictEntry(
            "Oslo",
            listOf("Oslo", "Bærum", "Asker")
        ),
        DistrictEntry(
            "Øst",
            listOf(
                "Fredrikstad", "Sarpsborg", "Moss", "Halden", "Indre Østfold", "Hvaler", "Råde", "Våler",
                "Skiptvet", "Rakkestad", "Marker", "Aremark",
                "Lillestrøm", "Ullensaker", "Nordre Follo", "Ås", "Frogn", "Vestby", "Nesodden",
                "Enebakk", "Lørenskog", "Rælingen", "Aurskog-Høland", "Nes", "Gjerdrum", "Nittedal",
                "Nannestad", "Eidsvoll", "Hurdal", "Lunner"
            )
        ),
        DistrictEntry(
            "Innlandet",
            listOf(
                "Hamar", "Lillehammer", "Gjøvik", "Kongsvinger", "Ringsaker", "Løten", "Stange",
                "Nord-Odal", "Sør-Odal", "Eidskog", "Grue", "Åsnes", "Våler (Innlandet)", "Elverum",
                "Trysil", "Åmot", "Stor-Elvdal", "Rendalen", "Engerdal", "Tolga", "Tynset", "Alvdal",
                "Folldal", "Os", "Dovre", "Lesja", "Skjåk", "Lom", "Vågå", "Nord-Fron", "Sel",
                "Sør-Fron", "Ringebu", "Øyer", "Gausdal", "Østre Toten", "Vestre Toten", "Gran",
                "Søndre Land", "Nordre Land", "Sør-Aurdal", "Etnedal", "Nord-Aurdal", "Vestre Slidre",
                "Øystre Slidre", "Vang"
            )
        ),
        DistrictEntry(
            "Sør-Øst",
            listOf(
                "Drammen", "Kongsberg", "Ringerike", "Hønefoss", "Hole", "Lier", "Øvre Eiker",
                "Modum", "Krødsherad", "Flå", "Nesbyen", "Gol", "Hemsedal", "Ål", "Hol", "Sigdal",
                "Flesberg", "Rollag", "Nore og Uvdal", "Jevnaker",
                "Horten", "Holmestrand", "Tønsberg", "Sandefjord", "Larvik", "Færder",
                "Porsgrunn", "Skien", "Notodden", "Siljan", "Bamble", "Kragerø", "Drangedal", "Nome",
                "Midt-Telemark", "Seljord", "Hjartdal", "Tinn", "Kviteseid", "Nissedal", "Fyresdal",
                "Tokke", "Vinje"
            )
        ),
        DistrictEntry(
            "Agder",
            listOf(
                "Kristiansand", "Arendal", "Grimstad", "Lindesnes", "Mandal", "Farsund", "Flekkefjord",
                "Risør", "Gjerstad", "Vegårshei", "Tvedestrand", "Froland", "Lillesand", "Birkenes",
                "Åmli", "Iveland", "Evje og Hornnes", "Bygland", "Valle", "Bykle", "Vennesla",
                "Åseral", "Lyngdal", "Hægebostad", "Kvinesdal"
            )
        ),
        DistrictEntry(
            "Sør-Vest",
            listOf(
                "Stavanger", "Sandnes", "Haugesund", "Eigersund", "Sokndal", "Lund", "Bjerkreim",
                "Hå", "Klepp", "Time", "Bryne", "Gjesdal", "Sola", "Randaberg", "Strand", "Hjelmeland",
                "Suldal", "Sauda", "Kvitsøy", "Bokn", "Tysvær", "Karmøy", "Utsira", "Vindafjord",
                "Sirdal", "Bømlo", "Stord", "Fitjar"
            )
        ),
        DistrictEntry(
            "Vest",
            listOf(
                "Bergen", "Askøy", "Øygarden", "Alver", "Osterøy", "Vaksdal", "Modalen", "Austrheim",
                "Fedje", "Masfjorden", "Gulen", "Solund", "Hyllestad", "Høyanger", "Vik", "Sogndal",
                "Aurland", "Lærdal", "Årdal", "Luster", "Askvoll", "Fjaler", "Sunnfjord", "Bremanger",
                "Stad", "Gloppen", "Stryn", "Kinn", "Etne", "Sveio", "Kvinnherad", "Ullensvang",
                "Eidfjord", "Ulvik", "Voss", "Kvam", "Samnanger", "Bjørnafjorden", "Austevoll", "Tysnes"
            )
        ),
        DistrictEntry(
            "Møre og Romsdal",
            listOf(
                "Ålesund", "Molde", "Kristiansund", "Vanylven", "Sande", "Herøy", "Ulstein", "Hareid",
                "Ørsta", "Volda", "Stranda", "Sykkylven", "Sula", "Giske", "Vestnes", "Rauma",
                "Aukra", "Averøy", "Gjemnes", "Tingvoll", "Sunndal", "Surnadal", "Smøla", "Aure",
                "Fjord", "Hustadvika", "Haram"
            )
        ),
        DistrictEntry(
            "Trøndelag",
            listOf(
                "Trondheim", "Steinkjer", "Namsos", "Stjørdal", "Orkland", "Orkanger", "Levanger",
                "Verdal", "Malvik", "Melhus", "Skaun", "Midtre Gauldal", "Selbu", "Tydal", "Klæbu",
                "Frosta", "Meråker", "Snåsa", "Lierne", "Røyrvik", "Namsskogan", "Grong", "Høylandet",
                "Overhalla", "Flatanger", "Leka", "Inderøy", "Indre Fosen", "Heim", "Hitra", "Frøya",
                "Ørland", "Åfjord", "Osen", "Rennebu", "Rindal", "Røros", "Holtålen", "Nærøysund",
                "Bindal"
            )
        ),
        DistrictEntry(
            "Nordland",
            listOf(
                "Bodø", "Narvik", "Sømna", "Brønnøy", "Brønnøysund", "Vega", "Vevelstad", "Herøy (Nordland)",
                "Alstahaug", "Leirfjord", "Vefsn", "Mosjøen", "Grane", "Hattfjelldal", "Dønna", "Nesna",
                "Hemnes", "Rana", "Mo i Rana", "Lurøy", "Træna", "Rødøy", "Meløy", "Gildeskål", "Beiarn",
                "Saltdal", "Fauske", "Sørfold", "Steigen", "Hamarøy", "Evenes", "Røst", "Værøy",
                "Flakstad", "Vestvågøy", "Vågan", "Svolvær", "Hadsel", "Bø (Vesterålen)", "Øksnes",
                "Sortland", "Andøy", "Moskenes", "Gratangen"
            )
        ),
        DistrictEntry(
            "Troms",
            listOf(
                "Tromsø", "Harstad", "Kvæfjord", "Tjeldsund", "Ibestad", "Lavangen", "Bardu",
                "Salangen", "Målselv", "Sørreisa", "Dyrøy", "Senja", "Finnsnes", "Balsfjord", "Karlsøy",
                "Lyngen", "Storfjord", "Kåfjord", "Skjervøy", "Nordreisa", "Storslett", "Kvænangen", "Skånland"
            )
        ),
        DistrictEntry(
            "Finnmark",
            listOf(
                "Alta", "Hammerfest", "Sør-Varanger", "Kirkenes", "Vadsø", "Vardø", "Karasjok",
                "Kautokeino", "Loppa", "Hasvik", "Måsøy", "Nordkapp", "Porsanger", "Lebesby", "Gamvik",
                "Tana", "Berlevåg", "Båtsfjord", "Nesseby"
            )
        )
    )

    /** Normalizes a district name for matching: lowercase, trimmed, "politidistrikt" suffix stripped. */
    fun normalize(name: String): String =
        name.trim().lowercase().removeSuffix("politidistrikt").trim()

    /** All municipality names across every district, for a flat fallback list. */
    val ALL_MUNICIPALITIES: List<String> by lazy {
        DISTRICTS.flatMap { it.municipalities }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun municipalitiesFor(districtDisplayName: String): List<String> {
        val target = normalize(districtDisplayName)
        return DISTRICTS.firstOrNull { normalize(it.displayName) == target }?.municipalities
            ?: emptyList()
    }

    /** Which of our 12 static districts a municipality belongs to, if known. */
    fun districtFor(municipality: String): String? {
        val target = municipality.trim().lowercase()
        return DISTRICTS.firstOrNull { entry -> entry.municipalities.any { it.lowercase() == target } }?.displayName
    }
}
