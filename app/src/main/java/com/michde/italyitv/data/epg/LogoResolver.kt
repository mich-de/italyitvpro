package com.michde.italyitv.data.epg

import com.michde.italyitv.core.Http
import com.michde.italyitv.core.NameTools
import org.json.JSONArray

/**
 * Multi-source channel-logo lookup:
 *  1. EPG <icon> (passed in)
 *  2. Free-TV/IPTV playlist tvg-logo  (imgur, reliable)
 *  3. tv-logo/tv-logos GitHub repo    (~300 Italian logos)
 */
class LogoResolver private constructor(
    private val map: Map<String, String>,
) {
    val size get() = map.size

    fun resolve(name: String, epgIcon: String?): String? {
        if (!epgIcon.isNullOrBlank()) return Http.preferHttp(epgIcon)
        val k0 = NameTools.matchKey(name)
        if (k0.length < 3) return null
        for (k in keysFor(k0)) map[k]?.let { return it }
        // fuzzy: prefix match, but only when the two keys are close in length
        map.entries
            .filter { it.key.length >= 4 && (it.key.startsWith(k0) || k0.startsWith(it.key)) }
            .minByOrNull { kotlin.math.abs(it.key.length - k0.length) }
            ?.takeIf { kotlin.math.abs(it.key.length - k0.length) <= 3 }
            ?.let { return it.value }
        return null
    }

    private fun keysFor(base: String): List<String> {
        val noDigits = base.trimEnd { it.isDigit() }
        return listOf(base, noDigits, base.removeSuffix("tv"), base.removeSuffix("channel"))
            .filter { it.length >= 3 }
            .distinct()
    }

    companion object {
        private const val FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
        private const val TV_LOGOS_API =
            "https://api.github.com/repos/tv-logo/tv-logos/contents/countries/italy"
        private val EXTINF_RE =
            Regex("""#EXTINF:[^\n]*?tvg-logo="([^"]+)"[^\n]*?,\s*([^\n]+)""")
        private val TVG_NAME_RE = Regex("""tvg-name="([^"]+)"""")

        val EMPTY = LogoResolver(emptyMap())

        fun load(): LogoResolver {
            val m = HashMap<String, String>()
            runCatching { loadTvLogos(m) }
            runCatching { loadFreeTv(m) }   // Free-TV wins ties (imgur reliability)
            return LogoResolver(m)
        }

        private fun put(m: MutableMap<String, String>, rawKey: String, url: String) {
            val k = NameTools.matchKey(rawKey)
            if (k.length >= 3) m[k] = url
            val kNoDigits = k.trimEnd { it.isDigit() }
            if (kNoDigits.length >= 3 && kNoDigits !in m) m[kNoDigits] = url
        }

        private fun loadFreeTv(m: MutableMap<String, String>) {
            val text = Http.getText(FREE_TV, Http.OKHTTP_UA)
            for (block in text.split("#EXTINF:")) {
                if (block.isBlank()) continue
                val logo = Regex("""tvg-logo="([^"]+)"""").find(block)?.groupValues?.get(1) ?: continue
                val tvgName = TVG_NAME_RE.find(block)?.groupValues?.get(1)
                val display = block.substringAfter(',', "").substringBefore('\n').trim()
                for (n in listOfNotNull(tvgName, display).filter { it.isNotBlank() }) put(m, n, logo)
            }
        }

        private fun loadTvLogos(m: MutableMap<String, String>) {
            val json = JSONArray(Http.getText(TV_LOGOS_API, Http.OKHTTP_UA))
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                val fn = o.optString("name")
                val url = o.optString("download_url")
                if (!fn.endsWith(".png") || url.isBlank()) continue
                var base = fn.removeSuffix(".png").removeSuffix("-it")
                base = base.replace(Regex("""-\d+$"""), "").ifBlank { fn.removeSuffix(".png") }
                put(m, base.replace('-', ' '), url)
            }
        }
    }
}
