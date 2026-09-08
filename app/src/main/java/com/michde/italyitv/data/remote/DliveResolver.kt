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

    private val WATCH_HOSTS = listOf("dlive.sx", "dlive.stream")
    private const val FALLBACK_PREMIUM_HOST = "hamis.romponalis.st"

    private val IFRAME = Pattern.compile(
        """<iframe[^>]+src=["']([^"']+premiumtv/[^"']+)["']""", Pattern.CASE_INSENSITIVE
    )
    private val ATOB = Pattern.compile("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
    private const val TIMEOUT = 10_000

    fun isDlive(url: String): Boolean =
        url.contains("dlive.", ignoreCase = true) || url.contains("/watch.php?id=", ignoreCase = true)

    private fun channelId(watchUrl: String): String {
        val at = watchUrl.indexOf("id=")
        if (at < 0) return "877"
        var s = watchUrl.substring(at + 3)
        val cut = s.indexOfFirst { it == '&' || it == '#' }
        if (cut != -1) s = s.substring(0, cut)
        return s.ifBlank { "877" }
    }

    /**
     * The player page to load in a WebView — the bare `…/premiumtv/daddyN.php`
     * endpoint (clean Clappr + hls.js, no anti-bot blob). It 403s
     * ("Access Denied - not available on your domain") unless the request carries
     * `Referer: https://dlive.sx/`, so the WebView must send that header.
     * Path differs per channel and rotates, so we scrape it, never guess.
     */
    fun playerPageUrl(watchUrl: String?): String? {
        if (watchUrl.isNullOrEmpty()) return null
        val id = channelId(watchUrl)

        findIframe(id)?.let {
            Log.i(TAG, "playerPage id=$id -> $it")
            return it
        }
        for (n in listOf("", "2", "3", "4", "5", "6", "7", "8", "9", "1")) {
            val ep = "https://$FALLBACK_PREMIUM_HOST/premiumtv/daddy$n.php?id=$id"
            if (httpHead(ep)) {
                Log.i(TAG, "playerPage id=$id -> $ep (brute)")
                return ep
            }
        }
        Log.w(TAG, "playerPage id=$id -> falling back to $watchUrl")
        return watchUrl
    }

    /** Referer the player page and its stream demand. */
    const val PLAYBACK_REFERER = "https://dlive.sx/"

    /** Raw HLS url + the headers its CDN wants. ExoPlayer path (fallback). */
    fun resolve(watchUrl: String?): ResolvedStream? {
        if (watchUrl.isNullOrEmpty()) return null
        val id = channelId(watchUrl)

        val iframe = findIframe(id)
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
