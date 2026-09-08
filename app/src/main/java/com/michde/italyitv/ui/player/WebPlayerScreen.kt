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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.michde.italyitv.data.remote.DliveResolver
import com.michde.italyitv.ui.AppViewModel

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
    var attempt by remember { mutableIntStateOf(0) }

    val pageUrl by produceState<String?>(initialValue = null, channelKey, attempt) {
        value = null
        val ch = channel
        value = if (ch == null) null
        else runCatching { vm.repo.dlivePlayerPage(ch) }.getOrNull() ?: ch.url
    }

    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val webHolder = remember { WebHolder() }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun report(state: String) {
                webHolder.web?.post {
                    when (state) {
                        "playing" -> { loading = false; failed = false }
                        "fatal" -> { loading = false; failed = true }
                    }
                }
            }
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
            .background(Color.Black),
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

                        override fun onPageFinished(v: WebView?, u: String?) {
                            if (u != null && u != "about:blank") loading = false
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

        if (failed) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        when {
            failed -> Column(
                Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Sorgente Daddy non disponibile",
                    color = Color.White, fontSize = 17.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Il CDN di questo canale è bloccato a monte in questo momento. " +
                        "Riprova tra poco, oppure usa la versione non-(Daddy) dello stesso canale.",
                    color = Color(0xFFBBBBBB), fontSize = 13.sp, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                IconButton(onClick = { webHolder.loaded = null; attempt++ }) {
                    Icon(Icons.Filled.Refresh, "Riprova", tint = Color.White)
                }
            }
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = 30.dp, start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White)
            }
            Text(
                channel?.name ?: "", color = Color.White, fontSize = 15.sp,
                maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = {
                    loading = true
                    webHolder.loaded = null
                    attempt++
                }) {
                    Icon(Icons.Filled.Refresh, "Ricarica", tint = Color.White)
                }
                IconButton(onClick = onEnterPipNow) {
                    Icon(Icons.Filled.PictureInPictureAlt, "PiP", tint = Color.White)
                }
            }
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

    // Some CDNs (Cloudflare WAF rules) 403 requests carrying WebView's default
    // `X-Requested-With: <package>` header. Drop it where supported.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        runCatching {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }
}

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
        /Could not play video|not available on your domain|Access Denied/i.test(document.body ? document.body.innerText : '');
      if (bad) { done = true; report('fatal'); return; }
    };
    var iv = setInterval(tick, 900);
    setTimeout(function () { if (!done) { clearInterval(iv); if (fragErrors > 0) report('fatal'); } }, 22000);
  } catch (e) {}
})();
"""
