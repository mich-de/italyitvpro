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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michde.italyitv.data.remote.DliveResolver
import com.michde.italyitv.ui.AppViewModel
import com.michde.italyitv.ui.tvFocusable

/**
 * Hosts whose sub-requests we drop outright (ad / pop-under / tracker noise).
 * NB: the swarmcloud P2P announce (ann.cdn-lab.shop) is deliberately NOT here —
 * when the HTTP segment CDN is down, P2P peers are the only way to get video.
 */
private val AD_HOSTS = listOf(
    "spikertrepan.com", "effectivecpmnetwork.com", "waust.at",
    "hillsakra.com", "propellerads", "onclickalgo", "hilltopads", "popads",
    "poptm", "adsco.re", "quillsulfa.com", "doubleclick.net",
    "googlesyndication.com", "chatango.com", "disqus.com",
)

/** Only main-frame navigations to these registrable domains are allowed. */
private val ALLOWED_NAV = listOf(
    "dlive.sx", "dlive.stream", "romponalis.st", "phantemlis.top", "workers.dev",
)

/**
 * Plays dlive.sx / "Daddy Live" channels inside a real Chromium WebView.
 *
 * Their HLS segments are Cloudflare-bot-gated `*.workers.dev` chunks disguised as
 * `.zst`/`.pdf` — no raw HLS client (ExoPlayer, curl) can fetch them, only a
 * browser running the site's own Clappr + hls.js + P2P stack. So we load that
 * page directly and get out of the way.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayerScreen(
    vm: AppViewModel,
    channelKey: String,
    onBack: () -> Unit,
    onSetAutoPip: (Boolean) -> Unit,
    onEnterPipNow: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val channel = remember(channelKey) { vm.channel(channelKey) }
    val nowNextMap by vm.nowNext.collectAsStateWithLifecycle()
    val nowNext = nowNextMap[channelKey]
    var attempt by remember { mutableIntStateOf(0) }

    // on-screen display (controls + channel/EPG bar): shown on tap/key, auto-hides
    var osdVisible by remember { mutableStateOf(true) }
    var osdNonce by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val firstBtnFocus = remember { FocusRequester() }
    fun wake() { osdVisible = true; osdNonce++ }

    // dlive offers several "Player 1..7" backends; we walk them on failure
    val players by produceState<List<String>>(emptyList(), channelKey, attempt) {
        val ch = channel
        value = if (ch == null) emptyList()
        else runCatching { vm.repo.dlivePlayerPages(ch) }.getOrDefault(emptyList())
            .ifEmpty { listOf(ch.url) }
    }
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
        loading = true; failed = false
        webHolder.loaded = null
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun report(state: String) {
                webHolder.web?.post {
                    when (state) {
                        "playing" -> { loading = false; failed = false; retrying = false; autoTries = 0 }
                        "fatal" -> {
                            val now = System.currentTimeMillis()
                            if (now - lastFatalAt < 2_500) return@post // ignore a stale page's late report
                            lastFatalAt = now
                            loading = false
                            when {
                                playerIdx < players.lastIndex -> toPlayer(playerIdx + 1) // next Player
                                autoTries < MAX_AUTO_TRIES -> { autoTries++; retrying = true } // all failed → wait & restart
                                else -> failed = true
                            }
                        }
                    }
                }
            }
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
                when {
                    ignored -> false
                    e.type != KeyEventType.KeyDown -> false
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
                WebView.setWebContentsDebuggingEnabled(true)
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
                            if (req?.isForMainFrame != true) return false // always allow the iframe + its sub-resources
                            val host = req.url?.host ?: return false
                            val ok = ALLOWED_NAV.any { host == it || host.endsWith(".$it") }
                            return !ok // swallow off-site (ad) main-frame redirects
                        }

                        override fun shouldInterceptRequest(v: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                            val host = req?.url?.host ?: return null
                            return if (AD_HOSTS.any { host.contains(it) })
                                WebResourceResponse("text/plain", "utf-8", null)
                            else null
                        }

                        override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                            // must run before hls.js is created — enlarge its tiny live buffer
                            v?.evaluateJavascript(HLS_TUNE_JS, null)
                        }

                        override fun onPageFinished(v: WebView?, u: String?) {
                            if (u != null && u != "about:blank") loading = false
                            v?.evaluateJavascript(HLS_TUNE_JS, null)
                            v?.evaluateJavascript(TIDY_JS, null)
                        }
                    }
                }
                webHolder.web = web
                root.addView(web)
                root
            },
            update = {
                val web = webHolder.web ?: return@AndroidView
                val url = pageUrl
                if (url != null && url != webHolder.loaded) {
                    webHolder.loaded = url
                    loading = true
                    failed = false
                    web.loadUrl(url, mapOf("Referer" to DliveResolver.PLAYBACK_REFERER))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (failed || retrying) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        val nPlayers = players.size.coerceAtLeast(1)
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
                        "Riprova tra qualche minuto, o usa la versione non-(Daddy).",
                    color = Color(0xFFBBBBBB), fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                IconButton(onClick = { autoTries = 0; failed = false; playerIdx = 0; webHolder.loaded = null; attempt++ }) {
                    Icon(Icons.Filled.Refresh, "Riprova", tint = Color.White)
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
                    (channel?.name ?: "") + if (players.size > 1) "  · Player ${playerIdx + 1}/${players.size}" else "",
                    color = Color.White, fontSize = 15.sp,
                    maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (players.size > 1) {
                        IconButton(
                            onClick = { autoTries = 0; retrying = false; toPlayer((playerIdx + 1) % players.size) },
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
    }
}

private class WebHolder {
    var web: WebView? = null
    var loaded: String? = null
}

/** Routes HTML5 fullscreen video into [root] so the <video> truly fills the screen. */
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
        userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    // let D-pad keys reach Compose (our OSD) instead of the web page's own controls
    isFocusable = false
    isFocusableInTouchMode = false
    // force GPU compositing of the video — SW fallback is the other stutter cause
    setLayerType(View.LAYER_TYPE_HARDWARE, null)

    // Some CDNs (Cloudflare WAF rules) 403 requests carrying WebView's default
    // `X-Requested-With: <package>` header. Drop it where supported.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        runCatching {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }
}

private const val MAX_AUTO_TRIES = 4

/**
 * The daddy player pages configure hls.js with `maxBufferLength: 5` and a very
 * tight live target — that is what makes the video stutter on anything but a
 * perfect line. Intercept `window.Hls` and merge in a roomier buffer + a looser
 * live latency target before Clappr builds the player; also patch a live
 * instance if one already exists.
 */
private const val HLS_TUNE_JS = """
(function () {
  try {
    var TUNE = {
      maxBufferLength: 30, maxMaxBufferLength: 90, backBufferLength: 30,
      maxBufferHole: 0.6, highBufferWatchdogPeriod: 3,
      liveSyncDurationCount: 6, liveMaxLatencyDurationCount: 20,
      nudgeMaxRetry: 12, appendErrorMaxRetry: 6, fragLoadingMaxRetry: 8,
      manifestLoadingMaxRetry: 6, levelLoadingMaxRetry: 6
    };
    var apply = function (cfg) { cfg = cfg || {}; for (var k in TUNE) cfg[k] = TUNE[k]; return cfg; };
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
    // also nudge an already-running instance
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
      '#player,video,.clappr-player,[data-player],iframe[src*="premiumtv"],iframe[src*="daddy"]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;border:0!important}' +
      'video{object-fit:contain!important}' +
      'iframe[src*="ads"],iframe[src*="hillsakra"],ins,.ad,[id*="banner"],[class*="banner"],[id^="_wau"],#chatango,.chatango{display:none!important}';
    var s = document.createElement('style'); s.textContent = css; document.head.appendChild(s);
    var kick = function () {
      document.querySelectorAll('video').forEach(function (v) {
        try { v.muted = false; v.removeAttribute('muted'); v.play(); } catch (e) {}
      });
    };
    kick(); setTimeout(kick, 1200); setTimeout(kick, 3500);
    // neuter pop-under hijacks
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
      if (v && !v.paused && v.currentTime > 0.3 && v.readyState >= 3) { done = true; report('playing'); return; }
      var bad = (v && v.error) || fragErrors >= 6 ||
        /Could not play video|not available on your domain|Access Denied|Backend fetch failed|502 Bad|503 |disable your ad|adblock/i.test(document.body ? document.body.innerText : '');
      if (bad) { done = true; report('fatal'); return; }
    };
    var iv = setInterval(tick, 900);
    // hard deadline: if nothing is playing by now this backend is a dud → next player
    setTimeout(function () { if (!done) { done = true; clearInterval(iv); report('fatal'); } }, 18000);
  } catch (e) {}
})();
"""
