package com.michde.italyitv.core

import com.michde.italyitv.data.model.Category

/** Channel-name cleaning, matching keys and category classification. */
object NameTools {

    private val TRAILING_TAG = Regex("""\s*[|/]\s*[A-Za-z0-9]{1,4}\s*$""")
    private val MULTISPACE = Regex("""\s{2,}""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val WEIRD_SPACE = Regex("[\\u00A0\\u2000-\\u200D\\u202F\\uFEFF]")

    /** "SKY ARTE |E" -> "Sky Arte", "20 MEDIASET  |H" -> "20 Mediaset". */
    fun clean(raw: String): String {
        var s = WEIRD_SPACE.replace(raw, " ").trim()
        var prev: String
        do {
            prev = s
            s = s.replace(TRAILING_TAG, "").trim()
        } while (s != prev && s.isNotEmpty())
        s = MULTISPACE.replace(s, " ").trim(' ', '|', '-')
        if (s.isBlank()) return raw.trim()
        return smartCase(s)
    }

    private fun smartCase(s: String): String =
        s.split(' ').joinToString(" ") { w ->
            when {
                w.isEmpty() -> w
                w.length <= 3 && w.all { it.isLetterOrDigit() } -> w.uppercase()
                w.any { it.isDigit() } -> w.uppercase()
                w.uppercase() == w -> w.lowercase().replaceFirstChar { it.uppercase() }
                else -> w
            }
        }

    private val PARENS = Regex("""\s*\((?:daddy|huhu|backup|new|old|alt|\d+)\)\s*""", RegexOption.IGNORE_CASE)

    /** Normalized key for fuzzy matching against EPG display-names. */
    fun matchKey(name: String): String {
        val stripped = PARENS.replace(clean(name), " ").trim()
        var k = NON_ALNUM.replace(stripped.lowercase(), "")
        for (suffix in listOf("hd", "sd", "fhd", "4k", "it", "italia", "italy")) {
            if (k.endsWith(suffix) && k.length > suffix.length + 2) k = k.dropLast(suffix.length)
        }
        return k
    }

    // --- category classification -------------------------------------------------

    private data class Rule(val cat: Category, val words: List<String>)

    private val RULES = listOf(
        Rule(Category.SPORT, listOf("sport", "dazn", "eurosport", "calcio", "tennis", "motogp", " f1",
            "golf", "basket", "supertennis", "sportitalia", "solocalcio", "milan tv", "inter tv",
            "juventus", "lazio style", "roma tv", "napoli", "premium sport")),
        Rule(Category.CINEMA, listOf("cinema", "movie", "film", "primafila", "iris", "cine34", "cine 34",
            "twentyseven", "warner tv", "paramount", "premium cinema", "rai movie", "rai 4",
            "la7 cinema", "la 7 cinema")),
        Rule(Category.SERIE, listOf("serie", "sky atlantic", "sky uno", "sky serie", "fox",
            "comedy central", "top crime", "giallo", "real time", "la5", "la 5", "tv8", "tv 8", "nove",
            "twenty seven", "spike", "mediaset extra", "italia 2", "rakuten", "pluto tv", "samsung tv",
            "atlantic")),
        Rule(Category.NEWS, listOf("news", " tg", "tgcom", "sky tg", "rai news", "rainews", "canale 24",
            "class cnbc", "meteo", "parlament", "camera dei deputati", "senato", "notizie")),
        Rule(Category.BAMBINI, listOf("kids", "junior", "cartoon", "boing", "cartoonito", "rai gulp",
            "rai yoyo", "yoyo", "gulp", "nick", "frisbee", " k2", "super!", "baby tv", "pokemon",
            "dea kids", "bim bum")),
        Rule(Category.DOCUMENTARI, listOf("discovery", "nat geo", "national geographic", "history",
            "documentar", "dmax", "focus", "geo tv", "rai storia", "rai scuola", "blaze",
            "crime + inv", "investigation", "sky nature", "sky documentaries", "sky arte", " arte",
            "classica")),
        Rule(Category.MUSICA, listOf("music", "musica", "mtv", "radio", "vh1", "deejay", "rtl 102",
            "kiss kiss", "m2o", "virgin radio", " rds", "70-80", "80-90", "hit tv", "video italia")),
        Rule(Category.GENERALISTI, listOf("rai 1", "rai 2", "rai 3", "rai1", "rai2", "rai3",
            "rai premium", "canale 5", "canale5", "italia 1", "italia1", "rete 4", "rete4", "la7",
            "la 7", "20 mediaset", "cielo", "tv2000", "tv 2000", "rsi la", "rtsi")),
    )

    fun classify(name: String, group: String? = null): Category {
        val n = " " + clean(name).lowercase() + " "
        val g = group?.lowercase().orEmpty()
        if (g.contains("local") || g.contains("region")) return Category.LOCALI
        for (r in RULES) if (r.words.any { n.contains(it) }) return r.cat
        if (Regex("""\b(tele|canale \d|antenna \d|radio ?tele)\b""").containsMatchIn(n) &&
            !n.contains("sky") && !n.contains("rai ") && !n.contains("mediaset")
        ) return Category.LOCALI
        return Category.ALTRO
    }
}
