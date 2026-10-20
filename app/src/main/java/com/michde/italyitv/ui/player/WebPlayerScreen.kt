package com.michde.italyitv.ui.player

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URL
import java.util.regex.Pattern
import android.util.Log
import org.json.JSONObject
import com.michde.italyitv.core.Http
import okhttp3.Request
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.michde.italyitv.data.model.Channel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michde.italyitv.data.remote.DliveResolver
import com.michde.italyitv.ui.AppViewModel
import com.michde.italyitv.ui.tvFocusable

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

private val AD_HOSTS = listOf(
    "spikertrepan", "effectivecpmnetwork", "waust", "hillsakra", "propellerads",
    "onclickalgo", "hilltopads", "popads", "poptm", "adsco.re", "quillsulfa",
    "doubleclick", "googlesyndication", "chatango", "disqus", "greatdexchange",
    "cleverwebserver", "adsboosters", "kzt2afc1rp52", "piousshiners",
    "burstyflavia", "llvpn", "aclib", "histats", "intellipopup", "xadsmart",
    "2734478852", "rdoblrtquasu", "vckixvxvnsubnx", "xstats.st", "plausible",
    "alwingulla", "foulkroggins", "adsterra", "monetag", "trafficjunky",
    "criteo", "outbrain", "taboola", "adnxs", "revcontent", "clickadu",
    "admaven", "tsyndicate", "exoclick", "juicyads", "popcash",
    "yadro.ru", "adservice", "pagead", "google-analytics", "googletagmanager",
    "clypeiescapes", "popcdn",
)

private fun isAdUrl(url: String): Boolean {
    val host = runCatching { URL(url).host?.lowercase().orEmpty() }.getOrDefault("")
    if (AD_HOSTS.any { it in host }) return true
    val path = runCatching { URL(url).path?.lowercase().orEmpty() }.getOrDefault("")
    if (path.contains("/popunder") ||
        path.contains("/pop.php") ||
        path.contains("/ad.js") ||
        path.contains("/ads.js") ||
        path.contains("/banner.js") ||
        path.contains("/aclib")
    ) return true
    val query = runCatching { URL(url).query?.lowercase().orEmpty() }.getOrDefault("")
    if (query.contains("zoneid=") || query.contains("/jump/next.php") || query.contains("adscoreloader") || query.contains("clevercoreloader")) return true
    return false
}

/** Navigations permitted (main-frame and subframes). */
private val ALLOWED_NAV = listOf(
    "dlive.sx", "dlive.stream", "dlive.to", "dlhd.dad", "dlhd.so", "romponalis.st",
    "phantemlis.top", "dembed.st", "cowedd4855ws.sbs", "tiktokcdn-us.com",
    "missflight.net", "7odxv0l067ka.net", "assetrage.net", "tiestep.top",
    "epiembeds.online", "wideiptv.top", "welovetocare.shop", "ddyplayer.cfd",
    "cdn-lab.shop", "jsdelivr.net", "jwplayer.com",
)

private const val MAX_AUTO_TRIES = 4

/**
 * Plays dlive.sx / "Daddy Live" channels inside a high-performance Chromium WebView.
 *
 * Media segments are Cloudflare-bot-gated and disguised as WebP/PNG/gzip chunks.
 * We sanitize the wrapper and player pages (stripping all ads, CPU-hogging crypto scripts,
 * and popunders), expand the Hls.js buffer to 60s, and route native HTTP/2 requests directly.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayerScreen(
    vm: AppViewModel,
    channelKey: String,
    onBack: () -> Unit,
    onSwitchChannel: (String) -> Unit = {},
    onSetAutoPip: (Boolean) -> Unit,
    onEnterPipNow: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val channel = remember(channelKey) { vm.channel(channelKey) }
    val altChannel = remember(channelKey) { vm.findAlternative(channelKey) }
    val nowNextMap by vm.nowNext.collectAsStateWithLifecycle()
    val nowNext = nowNextMap[channelKey]
    var attempt by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(vm.settings.showStats) }

    var osdVisible by remember { mutableStateOf(true) }
    var osdNonce by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val firstBtnFocus = remember { FocusRequester() }
    fun wake() { osdVisible = true; osdNonce++ }

    val players by produceState<List<String>>(emptyList(), channelKey, attempt) {
        val ch = channel
        value = if (ch == null) emptyList()
        else runCatching { vm.repo.dlivePlayerPages(ch) }.getOrDefault(emptyList())
            .ifEmpty { listOf(ch.url) }
    }
    val nPlayers = players.size.coerceAtLeast(1)
    var playerIdx by remember(channelKey, attempt) { mutableIntStateOf(0) }
    val pageUrl = players.getOrNull(playerIdx)

    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    var autoTries by remember { mutableIntStateOf(0) }
    var lastFatalAt by remember { mutableLongStateOf(0L) }
    val webHolder = remember { WebHolder() }

    fun toPlayer(idx: Int) {
        playerIdx = idx
        loading = true
        failed = false
        webHolder.loaded = null
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun report(state: String) {
                webHolder.web?.post {
                    when (state) {
                        "playing" -> {
                            loading = false
                            failed = false
                            retrying = false
                            autoTries = 0
                        }
                        "fatal" -> {
                            val now = System.currentTimeMillis()
                            if (now - lastFatalAt < 3_000) return@post
                            lastFatalAt = now
                            if (playerIdx < players.lastIndex) {
                                toPlayer(playerIdx + 1)
                            } else if (autoTries < MAX_AUTO_TRIES) {
                                autoTries++
                                retrying = true
                            } else {
                                loading = false
                                failed = true
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(pageUrl) {
        val web = webHolder.web ?: return@LaunchedEffect
        val url = pageUrl ?: return@LaunchedEffect
        if (url != webHolder.loaded) {
            webHolder.loaded = url
            loading = true
            failed = false
            web.loadUrl(url, mapOf("Referer" to DliveResolver.PLAYBACK_REFERER))
        }
    }

    LaunchedEffect(channelKey) { autoTries = 0; retrying = false; failed = false; playerIdx = 0 }
    LaunchedEffect(osdNonce, osdVisible) {
        if (osdVisible) { kotlinx.coroutines.delay(4_000); osdVisible = false }
    }
    LaunchedEffect(osdVisible) {
        if (osdVisible) {
            repeat(4) { kotlinx.coroutines.delay(50); if (runCatching { firstBtnFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(retrying) {
        if (retrying) {
            kotlinx.coroutines.delay(12_000)
            retrying = false
            playerIdx = 0
            loading = true
            webHolder.loaded = null
            attempt++
        }
    }

    DisposableEffect(Unit) {
        onSetAutoPip(true)
        onDispose {
            onSetAutoPip(false)
            webHolder.web?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
            webHolder.web = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val ignored = code == AndroidKeyEvent.KEYCODE_BACK ||
                    code == AndroidKeyEvent.KEYCODE_VOLUME_UP ||
                    code == AndroidKeyEvent.KEYCODE_VOLUME_DOWN ||
                    code == AndroidKeyEvent.KEYCODE_VOLUME_MUTE
                val isUp = code == AndroidKeyEvent.KEYCODE_DPAD_UP ||
                    code == AndroidKeyEvent.KEYCODE_CHANNEL_UP ||
                    code == AndroidKeyEvent.KEYCODE_PAGE_UP ||
                    code == AndroidKeyEvent.KEYCODE_INFO
                val isDown = code == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
                    code == AndroidKeyEvent.KEYCODE_CHANNEL_DOWN ||
                    code == AndroidKeyEvent.KEYCODE_PAGE_DOWN
                when {
                    ignored -> false
                    e.type != KeyEventType.KeyDown -> false
                    isUp -> {
                        showStats = !showStats
                        vm.settings.showStats = showStats
                        wake()
                        true
                    }
                    isDown && showStats -> {
                        showStats = false
                        vm.settings.showStats = false
                        wake()
                        true
                    }
                    !osdVisible -> { wake(); true }
                    else -> { osdNonce++; false }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { if (osdVisible) osdVisible = false else wake() }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                val root = FrameLayout(ctx).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                WebView.setWebContentsDebuggingEnabled(false)
                val web = WebView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(AndroidColor.BLACK)
                    configure()
                    addJavascriptInterface(bridge, "AndroidPlayer")
                    webChromeClient = FullscreenChrome(this, root) { on -> visibility = if (on) View.GONE else View.VISIBLE }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                            val url = req?.url ?: return true
                            val urlStr = url.toString()
                            val host = url.host?.lowercase().orEmpty()
                            val scheme = url.scheme?.lowercase().orEmpty()
                            if (scheme != "http" && scheme != "https") return true
                            if (isAdUrl(urlStr)) return true
                            if (req.isForMainFrame) {
                                val ok = ALLOWED_NAV.any { host == it || host.endsWith(".$it") }
                                return !ok
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(v: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                            val url = req?.url ?: return null
                            val urlStr = url.toString()
                            val host = (url.host ?: "").lowercase()

                            if (isAdUrl(urlStr)) {
                                return WebResourceResponse(
                                    "text/plain", "utf-8", 200, "OK",
                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            val isWrapper = (host.contains("dlive.") || host.contains("dlhd.") || host.contains("dlive.to")) &&
                                (url.path?.contains("/stream-") == true || url.path?.contains("/watch.php") == true)
                            val isDembed = host.contains("dembed.st") || url.path?.contains("daddy.php") == true
                            val isIframePlayer = isDembed || host.contains("missflight") || host.contains("assetrage") ||
                                host.contains("tiestep") || url.path?.contains("/e/") == true

                            if (isWrapper || isIframePlayer) {
                                return proxyAndSanitizeRequest(req)
                            }
                            return null
                        }

                        override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                            v?.evaluateJavascript(HLS_TUNE_JS, null)
                            v?.evaluateJavascript(TIDY_JS, null)
                        }

                        override fun onPageFinished(v: WebView?, u: String?) {
                            v?.evaluateJavascript(HLS_TUNE_JS, null)
                            v?.evaluateJavascript(TIDY_JS, null)
                        }
                    }
                }
                webHolder.web = web
                val currentTarget = pageUrl
                if (currentTarget != null) {
                    webHolder.loaded = currentTarget
                    web.loadUrl(currentTarget, mapOf("Referer" to DliveResolver.PLAYBACK_REFERER))
                }
                root.addView(web)
                root
            },
            update = {},
            modifier = Modifier.fillMaxSize(),
        )

        if (failed || retrying || loading) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        when {
            retrying -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nessun player disponibile — nuovo giro tra 12 s… ($autoTries/$MAX_AUTO_TRIES)",
                    color = Color(0xFFAAAAAA), fontSize = 12.sp,
                )
            }
            failed -> Column(
                Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sorgente Daddy non disponibile", color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nessuno dei $nPlayers player risponde in questo momento. " +
                        if (altChannel != null) "Puoi passare alla versione ${altChannel.name}." else "Riprova tra qualche minuto.",
                    color = Color(0xFFBBBBBB), fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { autoTries = 0; failed = false; playerIdx = 0; webHolder.loaded = null; attempt++ }) {
                        Icon(Icons.Filled.Refresh, "Riprova", tint = Color.White)
                    }
                    if (altChannel != null) {
                        Button(
                            onClick = { onSwitchChannel(altChannel.key) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.tvFocusable(RoundedCornerShape(20.dp)),
                        ) {
                            Icon(Icons.Filled.SwapHoriz, null, tint = Color.Black)
                            Spacer(Modifier.width(6.dp))
                            Text("Passa a ${altChannel.name}", color = Color.Black)
                        }
                    }
                }
            }
            loading -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                if (nPlayers > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("Player ${playerIdx + 1}/$nPlayers", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
                if (altChannel != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Disponibile alternativa: ${altChannel.name}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
            }
        }

        AnimatedVisibility(
            visible = osdVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(top = 24.dp, bottom = 8.dp, start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.focusRequester(firstBtnFocus).tvFocusable(CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White)
                }
                Text(
                    (channel?.name ?: "") + if (nPlayers > 1) "  · Player ${playerIdx + 1}/$nPlayers" else "",
                    color = Color.White, fontSize = 15.sp,
                    maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (altChannel != null) {
                        IconButton(
                            onClick = { onSwitchChannel(altChannel.key) },
                            modifier = Modifier.tvFocusable(CircleShape),
                        ) {
                            Icon(
                                Icons.Filled.SwapHoriz,
                                "Passa a ${altChannel.name}",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(
                        onClick = { showStats = !showStats; vm.settings.showStats = showStats },
                        modifier = Modifier.tvFocusable(CircleShape),
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            "Statistiche",
                            tint = if (showStats) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    if (nPlayers > 1) {
                        IconButton(
                            onClick = { autoTries = 0; retrying = false; toPlayer((playerIdx + 1) % nPlayers) },
                            modifier = Modifier.tvFocusable(CircleShape),
                        ) {
                            Icon(Icons.Filled.SkipNext, "Cambia player", tint = Color.White)
                        }
                    }
                    IconButton(
                        onClick = {
                            autoTries = 0; retrying = false; playerIdx = 0
                            loading = true; webHolder.loaded = null; attempt++
                        },
                        modifier = Modifier.tvFocusable(CircleShape),
                    ) {
                        Icon(Icons.Filled.Refresh, "Ricarica", tint = Color.White)
                    }
                    IconButton(onClick = onEnterPipNow, modifier = Modifier.tvFocusable(CircleShape)) {
                        Icon(Icons.Filled.PictureInPictureAlt, "PiP", tint = Color.White)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = osdVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            ChannelInfoBar(channel, nowNext)
        }

        if (showStats) {
            WebStatsOverlay(
                channel = channel,
                loading = loading,
                failed = failed,
                retrying = retrying,
                playerIdx = playerIdx,
                nPlayers = nPlayers,
                pageUrl = pageUrl,
                dohEnabled = vm.settings.hageziDns,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 84.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun WebStatsOverlay(
    channel: Channel?,
    loading: Boolean,
    failed: Boolean,
    retrying: Boolean,
    playerIdx: Int,
    nPlayers: Int,
    pageUrl: String?,
    dohEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val host = remember(pageUrl) {
        runCatching { java.net.URI(pageUrl ?: "").host }.getOrNull()?.ifBlank { null }
            ?: pageUrl.orEmpty().take(30)
    }
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        val lines = listOf(
            "stato   : " + when {
                failed -> "errore"
                retrying -> "riconnessione..."
                loading -> "caricamento..."
                else -> "in riproduzione"
            },
            "motore  : Chromium WebView (dlive)",
            "player  : ${playerIdx + 1} di $nPlayers",
            "host    : $host",
            "buffer  : HLS.js 20s live (anti-drift)",
            "dns     : " + if (dohEnabled) "HaGeZi DoH (anti-malware)" else "DNS di sistema",
            "sorgente: Daddy Live (web stream)",
        )
        lines.forEach { line ->
            Text(line, color = Color(0xFFB6F5C0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private class WebHolder {
    var web: WebView? = null
    var loaded: String? = null
}

private class FullscreenChrome(
    private val web: WebView,
    private val root: FrameLayout,
    private val setWebHidden: (Boolean) -> Unit,
) : WebChromeClient() {
    private var custom: View? = null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        custom = view
        view?.let {
            root.addView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ),
            )
        }
        setWebHidden(true)
    }

    override fun onHideCustomView() {
        custom?.let { root.removeView(it) }
        custom = null
        setWebHidden(false)
    }
}

private fun WebView.configure() {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        userAgentString = DESKTOP_UA
    }
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    isFocusable = false
    isFocusableInTouchMode = false
    setLayerType(View.LAYER_TYPE_HARDWARE, null)

    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        runCatching {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }
}

private fun proxyAndSanitizeRequest(req: WebResourceRequest): WebResourceResponse? {
    val url = req.url.toString()
    val host = req.url.host?.lowercase().orEmpty()
    return try {
        val rb = Request.Builder().url(url)
        req.requestHeaders.forEach { (k, v) ->
            if (!k.equals("X-Requested-With", ignoreCase = true) &&
                !k.equals("x-requested-with", ignoreCase = true)
            ) {
                rb.header(k, v)
            }
        }
        rb.header("User-Agent", DESKTOP_UA)
        val effectiveReferer = when {
            host.contains("dembed") -> "https://dlive.sx/"
            host.contains("missflight") || host.contains("assetrage") || host.contains("tiestep") || url.contains("/e/") -> "https://dlive.sx/"
            url.contains("/stream-") -> {
                val id = DliveResolver.channelId(url)
                val h = req.url.host ?: "dlive.sx"
                "https://$h/watch.php?id=$id"
            }
            else -> req.requestHeaders["Referer"] ?: req.requestHeaders["referer"] ?: DliveResolver.PLAYBACK_REFERER
        }
        rb.header("Referer", effectiveReferer)
        val res = Http.client.newCall(rb.build()).execute()
        val rawContentType = res.header("Content-Type", "text/html; charset=utf-8") ?: "text/html"
        val mimeType = rawContentType.substringBefore(";").trim().lowercase()
        val encoding = if (rawContentType.contains("charset=", ignoreCase = true)) {
            rawContentType.substringAfter("charset=").substringBefore(";").trim()
        } else "UTF-8"
        val headers = mutableMapOf<String, String>()
        res.headers.forEach { (k, v) ->
            if (!k.equals("content-encoding", ignoreCase = true) &&
                !k.equals("content-length", ignoreCase = true)
            ) {
                headers[k] = v
            }
        }
        headers["Access-Control-Allow-Origin"] = "*"

        val bodyStream = if (mimeType == "text/html") {
            val rawHtml = res.body?.string().orEmpty()
            val sanitized = sanitizePlayerHtml(rawHtml, url)
            val bytes = sanitized.toByteArray(runCatching { charset(encoding) }.getOrDefault(Charsets.UTF_8))
            headers["content-length"] = bytes.size.toString()
            ByteArrayInputStream(bytes)
        } else {
            res.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
        }

        WebResourceResponse(mimeType, encoding, res.code, res.message.ifBlank { "OK" }, headers, bodyStream)
    } catch (e: Exception) {
        Log.w("WebPlayer", "Failed proxying $url: ${e.message}")
        null
    }
}

private fun sanitizePlayerHtml(html: String, url: String): String {
    val isWrapper = url.contains("/stream-") || url.contains("/stream/") ||
        url.contains("/cast/") || url.contains("/watch/") ||
        url.contains("/plus/") || url.contains("/casting/") || url.contains("/player/")

    if (isWrapper) {
        val iframeMatcher = Pattern.compile("""<iframe[^>]+>[\s\S]*?</iframe>""", Pattern.CASE_INSENSITIVE).matcher(html)
        var playerIframe = ""
        while (iframeMatcher.find()) {
            val tag = iframeMatcher.group().orEmpty()
            val lower = tag.lowercase()
            if (lower.contains("id=\"thatframe\"") || lower.contains("id='thatframe'") ||
                lower.contains("dembed") || lower.contains("daddy") ||
                lower.contains("missflight") || lower.contains("assetrage") ||
                lower.contains("tiestep") || lower.contains("/e/") ||
                lower.contains("epiembeds") || lower.contains("wideiptv") ||
                lower.contains("allowfullscreen")
            ) {
                playerIframe = tag
                break
            }
        }

        if (playerIframe.isNotEmpty()) {
            return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <style>
                        html, body {
                            margin: 0 !important;
                            padding: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            overflow: hidden !important;
                            background: #000 !important;
                        }
                        #thatframe, iframe {
                            position: fixed !important;
                            inset: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            border: 0 !important;
                            background: #000 !important;
                            z-index: 2147483647 !important;
                        }
                    </style>
                    <script>
                        try {
                            window.open = function() { return null; };
                            Object.defineProperty(window, 'open', { value: function() { return null; }, writable: false, configurable: false });
                        } catch(e) {}
                        window.addEventListener('message', function(e) {
                            try {
                                if (e.data && e.data.type === 'PLAYING' && window.AndroidPlayer) {
                                    window.AndroidPlayer.report(e.data.state);
                                }
                            } catch(err) {}
                        });
                    </script>
                </head>
                <body>
                    $playerIframe
                </body>
                </html>
            """.trimIndent()
        }
    }

    if (url.contains("dembed.st") || url.contains("daddy.php")) {
        return sanitizeDembedHtml(html)
    }

    return sanitizeFallbackHtml(html)
}

/**
 * Strips 635 KB background crypto/devtools detector, ad popunders, and tunes buffer parameters
 * for butter-smooth Daddy playback on dembed.st.
 */
private fun sanitizeDembedHtml(html: String): String {
    var h = html

    // 1. Remove CPU-heavy crypto script (window['...']=...)
    h = h.replace(Regex("""<script>window\[['"][A-Za-z0-9]+['"]\][\s\S]*?<\/script>"""), "")

    // 2. Remove aclib and popunder scripts
    h = h.replace(Regex("""<script>[\s\r\n]*aclib\.runPop[\s\S]*?<\/script>"""), "")
    h = h.replace(Regex("""<script[^>]*data-cfasync=[^>]*>[\s\S]*?<\/script>"""), "")
    h = h.replace(Regex("""<script[^>]*src=["'][^"']*(?:aclib|popcdn|popunder|histats|xstats)[^"']*["'][^>]*>[\s\S]*?<\/script>"""), "")

    // 3. Bypass iframe/sandbox checks
    h = h.replace("if (!isEmbedded()) {", "if (false && !isEmbedded()) {")
    h = h.replace("if (inAdSandbox()) {", "if (false && inAdSandbox()) {")

    // 4. Tune live sync and buffering to eliminate stuttering
    // Keep a compact 10s back buffer so memory is aggressively freed on Android TV.
    // Disable catchup speedup (1.05x -> 1.0x) so decoders don't jitter or drop frames when drifting.
    h = h.replace("\"live_sync_segments\":3", "\"live_sync_segments\":4")
    h = h.replace("\"live_max_latency_segments\":8", "\"live_max_latency_segments\":12")
    h = h.replace("\"back_buffer_seconds\":15", "\"back_buffer_seconds\":10")
    h = h.replace("\"catchup\":true", "\"catchup\":false")
    h = h.replace("\"catchup_rate\":1.05", "\"catchup_rate\":1.0")
    h = h.replace("\"start_muted\":true", "\"start_muted\":false")

    // 5. Inject deep buffer tuning into hlsjsConfig:
    // - highBufferWatchdogPeriod: 0 prevents the periodic stall-nudge seek that causes stutter after 2 min.
    // - maxLiveSyncPlaybackRate: 1 ensures strictly 1.0x playback speed without hardware decoder pitch stutter.
    // - maxBufferLength: 20 keeps memory usage low and constant.
    val tuneInject = """
        maxBufferLength: 20,
        maxMaxBufferLength: 40,
        maxBufferSize: 15000000,
        backBufferLength: 10,
        maxLiveSyncPlaybackRate: 1,
        highBufferWatchdogPeriod: 0,
        nudgeMaxRetry: 5,
        maxBufferHole: 0.5,
        fragLoadingMaxRetry: 10,
        manifestLoadingMaxRetry: 10,
        levelLoadingMaxRetry: 10,
        liveDurationInfinity: true,
        loader: LiveLoader,
    """.trimIndent()
    h = h.replace("loader: LiveLoader,", tuneInject)

    // 6. Inject AndroidPlayer bridge and unmuter (with self-terminating timers)
    val injection = """
        <script>
            (function() {
                var reported = false;
                var playTimer = null;
                var unmuteTimer = null;
                var unmuteCount = 0;

                function checkPlaying() {
                    if (reported) return;
                    var v = document.querySelector('video');
                    if (v && !v.paused && v.currentTime > 0.1) {
                        reported = true;
                        if (playTimer) { clearInterval(playTimer); playTimer = null; }
                        try { if (window.AndroidPlayer) AndroidPlayer.report('playing'); } catch(e) {}
                        try { window.top.postMessage({ type: 'PLAYING', state: 'playing' }, '*'); } catch(e) {}
                    }
                }
                playTimer = setInterval(checkPlaying, 350);
                setTimeout(function() { if (playTimer) { clearInterval(playTimer); playTimer = null; } }, 15000);

                function autoUnmute() {
                    unmuteCount++;
                    var v = document.querySelector('video');
                    if (v && v.muted) {
                        v.muted = false;
                        v.removeAttribute('muted');
                    }
                    var u = document.getElementById('unmute');
                    if (u && !u.hidden) {
                        try { u.click(); } catch(e) {}
                        u.hidden = true;
                    }
                    if (unmuteCount > 20 && unmuteTimer) {
                        clearInterval(unmuteTimer);
                        unmuteTimer = null;
                    }
                }
                unmuteTimer = setInterval(autoUnmute, 300);
                setTimeout(function() { if (unmuteTimer) { clearInterval(unmuteTimer); unmuteTimer = null; } }, 10000);
            })();
        </script>
        <style>
            #unmute { display: none !important; opacity: 0 !important; }
            .lw-mark { display: none !important; }
        </style>
    """.trimIndent()

    return if (h.contains("</body>", ignoreCase = true)) {
        h.replaceFirst(Regex("</body>", RegexOption.IGNORE_CASE), "$injection\n</body>")
    } else {
        "$h\n$injection"
    }
}

private fun sanitizeFallbackHtml(html: String): String {
    var cleaned = sanitizeEConfig(html)
    cleaned = cleaned.replace(Regex("""if\s*\(\s*(?:window|self)\s*==\s*(?:window|self)\.top\s*\)\s*\{[\s\S]*?\}"""), "// no redirect")
    cleaned = cleaned.replace(Regex("""<script[^>]*p2p-engine[^>]*><\/script>""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""<script[^>]*data-cfasync=[^>]*>[\s\S]*?<\/script>""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""<script[^>]*src=["'][^"']*(?:xstats|plausible|histats|aclib|clever|adsboosters|nanisms|burstyflavia|llvpn|effectivecpm|clypeiescapes|popcdn|stream\.js)[^"']*["'][^>]*>[\s\S]*?<\/script>""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""<script[^>]*>(?:(?!<\/script>)[\s\S])*(?:greatdexchange|popunder|intellipopup|xadsmart|aclib|CleverCoreLoader|AdsCoreLoader|popcdn|plausible)(?:(?!<\/script>)[\s\S])*<\/script>""", RegexOption.IGNORE_CASE), "")

    val injection = """
        <style>
            html, body {
                margin: 0 !important;
                padding: 0 !important;
                width: 100% !important;
                height: 100% !important;
                overflow: hidden !important;
                background: #000 !important;
            }
            #player, video, .clappr-player, [data-player] {
                position: fixed !important;
                inset: 0 !important;
                width: 100vw !important;
                height: 100vh !important;
                z-index: 2147483640 !important;
                background: #000 !important;
                border: 0 !important;
            }
            video { object-fit: contain !important; }
            .player-poster, [data-poster], .play-wrapper { display: none !important; }
            iframe:not(#thatframe):not([allowfullscreen]) {
                display: none !important;
                width: 0 !important;
                height: 0 !important;
                pointer-events: none !important;
            }
            ins, .ad, [id*="banner"], [class*="banner"], [id^="_wau"], #chatango, .chatango {
                display: none !important;
            }
        </style>
        <script>
            $HLS_TUNE_JS
            try {
                window.open = function() { return null; };
                Object.defineProperty(window, 'open', { value: function() { return null; }, writable: false, configurable: false });
                window.aclib = { runPop: function(){}, runBanner: function(){} };
            } catch(e) {}
        </script>
    """.trimIndent()

    return if (cleaned.contains("<head>", ignoreCase = true)) {
        cleaned.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>\n$injection")
    } else {
        "$injection\n$cleaned"
    }
}

private fun sanitizeEConfig(html: String): String {
    val pattern = Pattern.compile("""window\._econfig\s*=\s*['"]([^'"]+)['"]""")
    val matcher = pattern.matcher(html)
    if (!matcher.find()) return html
    val rawB64 = matcher.group(1) ?: return html

    return try {
        val arrOrder = intArrayOf(2, 0, 3, 1)
        val raw = android.util.Base64.decode(rawB64, android.util.Base64.DEFAULT)
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
            val decodedChunk = android.util.Base64.decode(trimmed.toByteArray(Charsets.ISO_8859_1), android.util.Base64.DEFAULT)
            reordered[arrOrder[i]] = decodedChunk
        }
        val combined = reordered.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val finalJson = String(android.util.Base64.decode(combined, android.util.Base64.DEFAULT), Charsets.UTF_8)

        val json = JSONObject(finalJson)
        json.put("hframes", org.json.JSONArray())
        json.put("pop1", false)
        json.put("pops", org.json.JSONArray())
        json.put("sandbox_block", false)
        json.put("devtools_block", false)
        json.put("p2p", false)
        json.put("swarmcloud", false)
        json.put("url_nop2p", true)
        json.put("hframes_inject_secs", 999999)

        val newJsonStr = json.toString()
        val b64New = android.util.Base64.encodeToString(newJsonStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val partLen = (b64New.length + 3) / 4
        val parts = Array(4) { i ->
            val start = i * partLen
            val end = (start + partLen).coerceAtMost(b64New.length)
            if (start < b64New.length) b64New.substring(start, end) else ""
        }

        val reChunks = Array(4) { "" }
        for (i in 0 until 4) {
            val pIdx = arrOrder[i]
            val p = parts[pIdx]
            val b = android.util.Base64.encodeToString(p.toByteArray(Charsets.ISO_8859_1), android.util.Base64.NO_WRAP)
            val withDummy = if (b.length >= 3) b.substring(0, 3) + "X" + b.substring(3) else b + "X"
            reChunks[i] = withDummy
        }

        val maxL = reChunks.maxOf { it.length }
        val paddedChunks = reChunks.map { it.padEnd(maxL, ' ') }
        val newRaw = paddedChunks.joinToString("")
        val newEconfig = android.util.Base64.encodeToString(newRaw.toByteArray(Charsets.ISO_8859_1), android.util.Base64.NO_WRAP)

        html.replace(rawB64, newEconfig)
    } catch (e: Exception) {
        Log.w("WebPlayer", "Failed modifying _econfig: ${e.message}")
        html
    }
}

private const val HLS_TUNE_JS = """
(function () {
  try {
    var TUNE = {
      maxBufferLength: 20, maxMaxBufferLength: 40, backBufferLength: 10,
      maxBufferSize: 15000000, maxLiveSyncPlaybackRate: 1,
      maxBufferHole: 0.5, highBufferWatchdogPeriod: 0,
      liveSyncDurationCount: 4, liveMaxLatencyDurationCount: 12,
      liveDurationInfinity: true, enableWorker: true,
      nudgeMaxRetry: 5, appendErrorMaxRetry: 8, fragLoadingMaxRetry: 10,
      manifestLoadingMaxRetry: 10, levelLoadingMaxRetry: 10
    };
    var apply = function (cfg) { cfg = cfg || {}; for (var k in TUNE) cfg[k] = TUNE[k]; return cfg; };
    var wrapClappr = function(C) {
      if (!C || C.__tuned) return C;
      var Orig = C.Player;
      if (Orig) {
        C.Player = function(opt) {
          opt = opt || {};
          opt.playback = opt.playback || {};
          opt.playback.hlsjsConfig = apply(opt.playback.hlsjsConfig || {});
          opt.autoPlay = true;
          var inst = new Orig(opt);
          try {
            if (C.Events && C.Events.PLAYER_READY) {
              inst.on(C.Events.PLAYER_READY, function() { try { inst.play(); } catch(e) {} });
            }
          } catch(e) {}
          return inst;
        };
        C.Player.prototype = Orig.prototype;
        for (var k in Orig) { try { C.Player[k] = Orig[k]; } catch(e) {} }
      }
      C.__tuned = true;
      return C;
    };
    if (window.Clappr) wrapClappr(window.Clappr);
    else {
      var realC;
      Object.defineProperty(window, 'Clappr', {
        configurable: true,
        get: function() { return realC; },
        set: function(v) { realC = wrapClappr(v); }
      });
    }
    var wrap = function (H) {
      if (!H || H.__tuned) return H;
      var W = function (cfg) { return new H(apply(cfg)); };
      W.prototype = H.prototype;
      for (var s in H) { try { W[s] = H[s]; } catch (e) {} }
      if (H.DefaultConfig) apply(H.DefaultConfig);
      W.__tuned = true;
      return W;
    };
    if (window.Hls) { window.Hls = wrap(window.Hls); }
    else {
      var real;
      Object.defineProperty(window, 'Hls', {
        configurable: true,
        get: function () { return real; },
        set: function (v) { real = wrap(v); }
      });
    }
    var patchLive = function () {
      document.querySelectorAll('video').forEach(function (v) {
        var h = v.__hls || (v.player && v.player._hls);
        if (h && h.config && !h.config.__tuned) { for (var k in TUNE) h.config[k] = TUNE[k]; h.config.__tuned = true; }
      });
    };
    setTimeout(patchLive, 2000); setTimeout(patchLive, 5000);
  } catch (e) {}
})();
"""

private const val TIDY_JS = """
(function () {
  try {
    var css = 'html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important}' +
      '#player,video,.clappr-player,[data-player],#thatframe,iframe[allowfullscreen],iframe[src*="premiumtv"],iframe[src*="daddy"],iframe[src*="/e/"]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:2147483640!important;background:#000!important;border:0!important}' +
      'video{object-fit:contain!important}' +
      '.player-poster,[data-poster],.play-wrapper{display:none!important;opacity:0!important;pointer-events:none!important}' +
      '#offline{z-index:2147483641!important}' +
      '#unmute{z-index:2147483642!important}' +
      'iframe:not(#thatframe):not([allowfullscreen]):not([src*="/e/"]):not([src*="premiumtv"]):not([src*="daddy"]){display:none!important;width:0!important;height:0!important;pointer-events:none!important}' +
      'iframe[src*="ads"],iframe[src*="hillsakra"],ins,.ad,[id*="banner"],[class*="banner"],[id^="_wau"],#chatango,.chatango{display:none!important}';
    var s = document.createElement('style'); s.textContent = css; document.head.appendChild(s);
    var kick = function () {
      document.querySelectorAll('video').forEach(function (v) {
        try { v.muted = false; v.removeAttribute('muted'); v.play(); } catch (e) {}
      });
      var poster = document.querySelector('[data-poster], .player-poster, .play-wrapper');
      if (poster) {
        try {
          poster.click();
          poster.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
          poster.style.display = 'none';
        } catch (e) {}
      }
      var u = document.querySelector('#unmute, [id*="unmute"], .unmute');
      if (u) {
        try {
          u.click();
          u.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
          setTimeout(function() { if (u) u.style.display = 'none'; }, 2000);
        } catch (e) {}
      }
    };
    kick(); setTimeout(kick, 1200); setTimeout(kick, 3500);
    window.open = function () { return null; };

    var report = function (st) {
      try { if (window.AndroidPlayer) AndroidPlayer.report(st); } catch (e) {}
    };
    var fragErrors = 0, done = false;
    ['log', 'warn', 'error'].forEach(function (fn) {
      var orig = console[fn];
      console[fn] = function () {
        try {
          var m = Array.prototype.join.call(arguments, ' ');
          if (/fragLoadError|could not recover|Website Access Blocked|CORS policy|networkError fatal true|manifestLoadError|levelLoadError/i.test(m)) fragErrors++;
        } catch (e) {}
        return orig.apply(console, arguments);
      };
    });
    var tick = function () {
      if (done) return;
      var v = document.querySelector('video');
      if (v && !v.paused && v.currentTime > 0.3 && v.readyState >= 3) {
        done = true;
        clearInterval(iv);
        report('playing');
        return;
      }
      var bad = (v && v.error) || fragErrors >= 12 ||
        /Could not play video|not available on your domain|Access Denied|Backend fetch failed|502 Bad|503 |STREAM IS OFFLINE|stream offline/i.test(document.body ? document.body.innerText : '');
      if (bad) {
        done = true;
        clearInterval(iv);
        report('fatal');
        return;
      }
    };
    var iv = setInterval(tick, 900);
    setTimeout(function () { if (!done) { done = true; clearInterval(iv); report('playing'); } }, 5000);
  } catch (e) {}
})();
"""
