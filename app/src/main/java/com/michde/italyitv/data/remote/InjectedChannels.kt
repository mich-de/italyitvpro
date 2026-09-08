package com.michde.italyitv.data.remote

import com.michde.italyitv.data.model.ParsedChannel

/** Hand-curated extra channels (DAZN / Sky / Rai via dlive.sx + one huhu handle). */
object InjectedChannels {

    private const val DLIVE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val DLIVE_REF = "https://hamis.romponalis.st/"

    // id -> display name
    private val DLIVE = linkedMapOf(
        "877" to "DAZN ZONA",
        "878" to "Eurosport 1",
        "879" to "Eurosport 2",
        "854" to "Italia 1",
        "855" to "La7",
        "856" to "La7d HD+",
        "850" to "Rai 1",
        "851" to "Rai 2",
        "852" to "Rai 3",
        "853" to "Rai 4",
        "882" to "Rai Sport",
        "858" to "Rai Premium",
        "857" to "20 Mediaset",
        "881" to "Sky Uno",
        "880" to "Sky Serie",
        "859" to "Sky Cinema Collection",
        "860" to "Sky Cinema Uno",
        "861" to "Sky Cinema Action",
        "862" to "Sky Cinema Comedy",
        "863" to "Sky Cinema Uno +24",
        "864" to "Sky Cinema Romance",
        "865" to "Sky Cinema Family",
        "867" to "Sky Cinema Drama",
        "868" to "Sky Cinema Suspense",
        "869" to "Sky Sport 24",
        "870" to "Sky Sport Calcio",
        "871" to "Sky Calcio 1 (251)",
        "872" to "Sky Calcio 2 (252)",
        "873" to "Sky Calcio 3 (253)",
        "874" to "Sky Calcio 4 (254)",
        "875" to "Sky Sport Basket",
        "460" to "Sky Sport Max",
        "461" to "Sky Sport Uno",
        "462" to "Sky Sport Arena",
        "574" to "Sky Sport Golf",
        "575" to "Sky Sport MotoGP",
        "576" to "Sky Sport Tennis",
        "577" to "Sky Sport F1",
    )

    val list: List<ParsedChannel> by lazy {
        buildList {
            for ((id, name) in DLIVE) {
                add(
                    ParsedChannel(
                        key = "dlive/$id",
                        name = "$name (Daddy)",
                        url = "https://dlive.sx/watch.php?id=$id",
                        logo = null,
                        group = "Daddy Live",
                        tvgId = "Dlive.$id",
                        userAgent = DLIVE_UA,
                        referrer = DLIVE_REF,
                    )
                )
            }
        }
    }
}
