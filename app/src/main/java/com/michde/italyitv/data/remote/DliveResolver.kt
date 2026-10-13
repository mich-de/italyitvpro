package com.michde.italyitv.data.remote

import android.util.Base64
import android.util.Log
import com.michde.italyitv.data.model.ResolvedStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Resolves a dlive.sx / "Daddy Live" watch url.
 *
 * Two products:
 *  - [playerPageUrl] — the real player page (`…/premiumtv/daddyN.php?id=<id>`).
 *    This is what actually plays: its Clappr + hls.js + P2P stack is the only
 *    client the CDN's media segments (Cloudflare-bot-gated `*.workers.dev`
 *    chunks disguised as `.zst`/`.pdf`) will serve. We load it in a WebView.
 *  - [resolve] — the raw HLS url + headers, for the ExoPlayer path. Works for
 *    the playlists but the segments 403 outside a browser, so this is fallback
 *    / diagnostics only.
 *
 * The premium endpoint path differs per channel and rotates (877→daddy.php,
 * 855→daddy2.php, 882→daddy5.php…), so we never guess it: dlive's own
 * `stream/stream-<id>.php` page embeds an `<iframe src>` with the exact current
 * host+path. A brute-force sweep is the last resort.
 */
object DliveResolver {

    private const val TAG = "DliveResolver"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val WATCH_HOSTS = listOf("dlive.sx", "dlhd.dad", "dlive.to")
    private const val FALLBACK_PREMIUM_HOST = "hamis.romponalis.st"

    private val IFRAME = Pattern.compile(
        """<iframe[^>]+src=["']([^"']+premiumtv/[^"']+)["']""", Pattern.CASE_INSENSITIVE
    )
    private val ANY_IFRAME = Pattern.compile(
        """<iframe[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE
    )
    private val ATOB = Pattern.compile("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
    /** the watch page's player-selector buttons: data-url="https://dlive.sx/<seg>/stream-<id>.php" */
    private val PLAYER_URL = Pattern.compile(
        """data-url=["'](https?://[^"']+/stream-\d+\.php)["']""", Pattern.CASE_INSENSITIVE
    )
    /** fallback player-page path segments, in the order dlive lists "Player 1..7". */
    private val PLAYER_SEGMENTS = listOf("stream", "cast", "watch", "plus", "casting", "player", "hub")
    private const val TIMEOUT = 4_000

    fun isDlive(url: String): Boolean =
        url.contains("dlive.", ignoreCase = true) ||
            url.contains("dlhd.", ignoreCase = true) ||
            url.contains("/watch.php?id=", ignoreCase = true)

    fun channelId(watchUrl: String): String {
        val at = watchUrl.indexOf("id=")
        if (at < 0) return watchUrl.substringAfterLast('/').substringBefore('.')
        return watchUrl.substring(at + 3).takeWhile { it.isDigit() }
    }

    /**
     * The player page to load in a WebView — the bare `…/premiumtv/daddyN.php`
     * endpoint or embedded player wrapper.
     */
    fun playerPageUrl(watchUrl: String?): String? = playerPageUrls(watchUrl).firstOrNull()

    /**
     * The distinct player backends behind dlive's "Player 1..7" selector, in order:
     * Player 1: stream/stream-<id>.php
     * Player 2: cast/stream-<id>.php
     * Player 3: watch/stream-<id>.php
     * Player 4: plus/stream-<id>.php
     * Player 5: casting/stream-<id>.php
     * Player 6: player/stream-<id>.php
     *
     * These official wrappers embed the respective player iframe with all
     * required origins and headers intact.
     */
    fun playerPageUrls(watchUrl: String?): List<String> {
        if (watchUrl.isNullOrEmpty()) return emptyList()
        val id = channelId(watchUrl)

        // 1. Fetch wrappers dynamically from watch.php
        for (host in WATCH_HOSTS) {
            val html = httpGet("https://$host/watch.php?id=$id", referer = "https://$host/") ?: continue
            val m = PLAYER_URL.matcher(html)
            val urls = LinkedHashSet<String>()
            while (m.find()) {
                val u = m.group(1)!!
                // Keep only /stream/ and /cast/ (Player 1 & 2 -> assetrage & tiestep).
                // Other wrappers (/watch/, /plus/, /player/) are either dead or redirect to popcdn.dad ad networks.
                if (u.contains("/stream/") || u.contains("/cast/")) {
                    urls.add(u)
                }
            }
            if (urls.isNotEmpty()) {
                Log.i(TAG, "playerPages id=$id -> ${urls.size} from $host: $urls")
                return urls.toList()
            }
        }
        val host = WATCH_HOSTS.firstOrNull { httpHead("https://$it/watch.php?id=$id") } ?: WATCH_HOSTS[0]
        val fallback = listOf("https://$host/stream/stream-$id.php", "https://$host/cast/stream-$id.php")
        Log.i(TAG, "playerPages id=$id -> fallback wrappers: $fallback")
        return fallback
    }

    /** Referer the player page and its stream demand. */
    const val PLAYBACK_REFERER = "https://dlive.sx/"

    data class DliveStream(
        val streamUrl: String,
        val iframeUrl: String,
        val playerIdx: Int,
    )

    /**
     * Directly resolves the high-speed CDN stream URL by extracting the player iframe
     * and decoding the obfuscated _econfig in Kotlin. This bypasses all website ad scripts,
     * devtools blockers, sandbox detectors, and P2P Swarmcloud overhead.
     */
    suspend fun resolveStreamUrl(watchUrl: String?, playerIndex: Int = 0): DliveStream? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (watchUrl.isNullOrEmpty()) return@withContext null
            val id = channelId(watchUrl)
            val segments = if (playerIndex == 1) listOf("cast", "stream", "watch", "player") else listOf("stream", "cast", "watch", "player")
            for (seg in segments) {
                val streamPageUrl = "https://dlive.sx/$seg/stream-$id.php"
                val html = httpGet(streamPageUrl, referer = "https://dlive.sx/watch.php?id=$id") ?: continue
                val m = ANY_IFRAME.matcher(html)
                var iframeSrc: String? = null
                while (m.find()) {
                    val src = m.group(1).orEmpty()
                    if (src.contains("/e/") || src.contains("assetrage") || src.contains("tiestep") ||
                        src.contains("epiembeds") || src.contains("dembed") || src.contains("premiumtv")
                    ) {
                        iframeSrc = src
                        break
                    }
                }
                if (iframeSrc == null) continue
                val fullIframeUrl = absolutize(iframeSrc, "dlive.sx")
                val iframeHtml = httpGet(fullIframeUrl, referer = "https://dlive.sx/") ?: continue
                val directUrl = extractStreamUrl(iframeHtml)
                if (directUrl != null && directUrl.startsWith("http")) {
                    if (isStreamLive(directUrl)) {
                        val actualIdx = if (seg == "cast") 1 else 0
                        Log.i(TAG, "Directly resolved id=$id ($seg) -> $directUrl")
                        return@withContext DliveStream(directUrl, fullIframeUrl, actualIdx)
                    } else {
                        Log.w(TAG, "Stream $directUrl not live for seg=$seg, trying next")
                    }
                }
            }
            null
        }

    private fun isStreamLive(url: String): Boolean = try {
        val referer = when {
            url.contains("cowedd") || url.contains("dembed") -> "https://dembed.st/"
            else -> "https://assetrage.net/"
        }
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .head()
            .build()
        com.michde.italyitv.core.Http.client.newCall(req).execute().use { res ->
            res.isSuccessful
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Extracts the direct CDN HLS stream URL from the obfuscated window._econfig string or
     * direct SRC assignment in Daddy player pages.
     */
    fun extractStreamUrl(html: String): String? {
        val srcPattern = Pattern.compile("""(?:const|var|let)\s+SRC\s*=\s*['"]([^'"]+)['"]""")
        val srcMatcher = srcPattern.matcher(html)
        if (srcMatcher.find()) {
            val src = srcMatcher.group(1)?.trim()
            if (!src.isNullOrEmpty() && src.startsWith("http")) return src
        }

        val pattern = Pattern.compile("""window\._econfig\s*=\s*['"]([^'"]+)['"]""")
        val matcher = pattern.matcher(html)
        if (!matcher.find()) return null
        val rawB64 = matcher.group(1) ?: return null

        return try {
            val arrOrder = intArrayOf(2, 0, 3, 1)
            val raw = Base64.decode(rawB64, Base64.DEFAULT)
            val q = (raw.size + 3) / 4
            val chunks = Array(4) { i ->
                val start = i * q
                val end = (start + q).coerceAtMost(raw.size)
                if (start < raw.size) raw.copyOfRange(start, end) else ByteArray(0)
            }
            val reordered = Array(4) { ByteArray(0) }
            for (i in 0 until 4) {
                val chunkStr = String(chunks[i], Charsets.ISO_8859_1)
                val trimmed = if (chunkStr.length > 4) chunkStr.substring(0, 3) + chunkStr.substring(4) else chunkStr
                val decodedChunk = Base64.decode(trimmed.toByteArray(Charsets.ISO_8859_1), Base64.DEFAULT)
                reordered[arrOrder[i]] = decodedChunk
            }
            val combined = reordered.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            val finalJson = String(Base64.decode(combined, Base64.DEFAULT), Charsets.UTF_8)
            val json = org.json.JSONObject(finalJson)
            val nop2p = json.optString("stream_url_nop2p").trim()
            val stream = json.optString("stream_url").trim()
            val chosen = if (nop2p.startsWith("http")) nop2p else stream
            if (chosen.startsWith("http")) chosen else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting stream_url from _econfig: ${e.message}")
            null
        }
    }

    /** Raw HLS url + the headers its CDN wants. */
    fun resolve(watchUrl: String?): ResolvedStream? {
        if (watchUrl.isNullOrEmpty()) return null
        val id = channelId(watchUrl)

        // Try direct stream extraction first
        val iframe = findIframe(id)
        if (iframe != null) {
            val iframeHtml = httpGet(iframe, referer = "https://dlive.sx/")
            if (iframeHtml != null) {
                val direct = extractStreamUrl(iframeHtml)
                if (direct != null && direct.startsWith("http")) {
                    val origin = "https://${hostOf(iframe)}"
                    return ResolvedStream(direct, "$origin/", origin, UA)
                }
            }
        }

        val candidates = buildList {
            iframe?.let { add(it) }
            for (n in listOf("", "2", "3", "4", "5")) {
                add("https://$FALLBACK_PREMIUM_HOST/premiumtv/daddy$n.php?id=$id")
            }
        }.distinct()

        for (ep in candidates) {
            val body = httpGet(ep, referer = "https://${hostOf(ep)}/") ?: continue
            val m = ATOB.matcher(body)
            while (m.find()) {
                val decoded = runCatching {
                    String(Base64.decode(m.group(1), Base64.DEFAULT), Charsets.UTF_8).trim()
                }.getOrNull() ?: continue
                if (decoded.startsWith("http")) {
                    val origin = "https://${hostOf(ep)}"
                    Log.i(TAG, "resolved id=$id -> $decoded (referer $origin/)")
                    return ResolvedStream(decoded, "$origin/", origin, UA)
                }
            }
        }
        Log.e(TAG, "resolve failed for id=$id")
        return null
    }

    // --- internals -------------------------------------------------------------

    private fun findIframe(id: String): String? {
        for (host in WATCH_HOSTS) {
            val html = httpGet("https://$host/stream/stream-$id.php", referer = "https://$host/")
                ?: continue
            val m = IFRAME.matcher(html)
            if (m.find()) return absolutize(m.group(1)!!, host)
            Log.w(TAG, "no iframe on $host/stream/stream-$id.php (${html.length}b)")
        }
        return null
    }

    private fun httpGet(url: String, referer: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
        }
        if (c.responseCode != 200) {
            Log.w(TAG, "$url -> HTTP ${c.responseCode}")
            c.disconnect(); null
        } else {
            BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                buildString { r.forEachLine { append(it).append('\n') } }
            }.also { c.disconnect() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "$url failed: ${e.message}"); null
    }

    private fun httpHead(url: String): Boolean = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://dlive.sx/")
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
        }
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    private fun absolutize(src: String, host: String): String = when {
        src.startsWith("http") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> "https://$host$src"
        else -> "https://$host/$src"
    }

    private fun hostOf(url: String): String = URL(url).host
}
