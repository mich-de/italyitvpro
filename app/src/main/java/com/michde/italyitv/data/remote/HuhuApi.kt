package com.michde.italyitv.data.remote

import com.michde.italyitv.core.Http
import com.michde.italyitv.data.model.ParsedChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** huhu.to IPTV catalog + on-demand stream resolver. */
object HuhuApi {
    private const val CATALOG_URL = "https://huhu.to/mediaurl-catalog.json"
    private const val RESOLVE_URL = "https://huhu.to/mediaurl-resolve.json"
    const val PLAY_PREFIX = "https://huhu.to/huhu-iptv/play/"
    private const val UA = Http.OKHTTP_UA
    private const val MAX_PAGES = 40
    const val GROUP = "Italy"

    fun isPlayHandle(url: String) = url.startsWith(PLAY_PREFIX)

    @Throws(IOException::class)
    fun loadItalianChannels(): List<ParsedChannel> {
        val out = ArrayList<ParsedChannel>()
        val usedKeys = HashSet<String>()
        var cursor: Int? = 0
        var page = 0
        while (cursor != null && page < MAX_PAGES) {
            page++
            val body = JSONObject()
                .put("catalogId", "iptv").put("id", "").put("adult", false)
                .put("search", "").put("sort", "trending")
                .put("filter", JSONObject().put("group", GROUP))
                .put("cursor", cursor).put("language", "it").put("region", "IT")
                .toString()
            val json = try {
                JSONObject(Http.postJson(CATALOG_URL, body, UA))
            } catch (t: Throwable) {
                throw IOException("Risposta non valida da huhu.to", t)
            }
            parsePage(json, usedKeys, out)
            val nc = if (json.isNull("nextCursor")) null else json.optInt("nextCursor")
            cursor = if (nc == null || nc == cursor) null else nc
        }
        return out
    }

    private fun parsePage(page: JSONObject, usedKeys: MutableSet<String>, out: MutableList<ParsedChannel>) {
        val items = page.optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            if (!it.optString("group").equals(GROUP, true)) continue
            val id = it.optJSONObject("ids")?.optString("id")?.takeIf { s -> s.isNotBlank() } ?: continue
            val name = it.optString("name").trim().ifBlank { "Canale $id" }
            var url = it.optString("url").takeIf { s -> s.isNotBlank() } ?: (PLAY_PREFIX + id)
            if (!isPlayHandle(url)) url = Http.preferHttp(url)
            val logo = it.optString("logo").takeIf { s -> s.isNotBlank() }?.let(Http::preferHttp)
            out.add(
                ParsedChannel(
                    key = uniqueKey(name, usedKeys),
                    name = name,
                    url = url,
                    logo = logo,
                    group = "Italia",
                    userAgent = UA,
                )
            )
        }
    }

    /** Resolves a play handle to a real stream URL (http:// to dodge expired certs). */
    @Throws(IOException::class)
    fun resolve(playUrl: String, userAgent: String = UA): String {
        val body = JSONObject().put("url", playUrl).put("language", "it").put("region", "IT").toString()
        val text = Http.postJson(RESOLVE_URL, body, userAgent)
        val arr = try { JSONArray(text) } catch (t: Throwable) { null }
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() }
                if (u != null) return Http.preferHttp(u)
            }
        }
        throw IOException("huhu.to non ha restituito uno stream")
    }

    private fun uniqueKey(name: String, used: MutableSet<String>): String {
        val base = "huhu/" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "x" }
        if (used.add(base)) return base
        var n = 2
        while (!used.add("$base-$n")) n++
        return "$base-$n"
    }
}
