package com.michde.italyitv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.michde.italyitv.data.model.Channel
import com.michde.italyitv.data.model.NowNext
import com.michde.italyitv.ui.AppViewModel
import com.michde.italyitv.ui.tvFocusable
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

private val RESIZE_MODES = intArrayOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    AspectRatioFrameLayout.RESIZE_MODE_FILL,
)

/** how long the on-screen controls / info stay up after the last key or tap */
private const val OSD_TIMEOUT_MS = 4_000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    vm: AppViewModel,
    channelKey: String,
    onBack: () -> Unit,
    onSetAutoPip: (Boolean) -> Unit,
    onEnterPipNow: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val channel = remember(channelKey) { vm.channel(channelKey) }
    val nowNextMap by vm.nowNext.collectAsStateWithLifecycle()
    val nowNext = nowNextMap[channelKey]

    var streamUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var recoveries by remember { mutableIntStateOf(0) }
    var lastErrorAt by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(vm.settings.showStats) }

    // on-screen display (controls + channel/EPG bar): shown on any key/tap, auto-hides
    var osdVisible by remember { mutableStateOf(true) }
    var osdNonce by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val firstBtnFocus = remember { FocusRequester() }

    LaunchedEffect(osdNonce, osdVisible) {
        if (osdVisible) { delay(OSD_TIMEOUT_MS); osdVisible = false }
    }
    LaunchedEffect(osdVisible) {
        if (osdVisible) {
            repeat(4) { delay(50); if (runCatching { firstBtnFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    // one factory instance, headers updated per-resolution before each prepare()
    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(channel?.userAgent ?: "Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(25_000) // huhu origins can take ~12 s to first byte on a cold hit
    }
    val player = remember {
        // huhu CDNs are slow to first byte — start playback on a small buffer,
        // but keep loading a deep one so a stall doesn't stop the stream.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage("it")
                    .build()
                playWhenReady = true
            }
    }

    // resolve stream url(s) + apply the headers its CDN needs to every request
    LaunchedEffect(channelKey, attempt) {
        error = null
        streamUrls = emptyList()
        val ch = channel ?: run { error = "Canale non trovato"; return@LaunchedEffect }
        val r = runCatching { vm.repo.resolveStream(ch) }
            .getOrElse { error = "Risoluzione fallita: ${it.message}"; null }
            ?: return@LaunchedEffect
        httpFactory.setUserAgent(r.userAgent ?: ch.userAgent ?: "Mozilla/5.0")
        httpFactory.setDefaultRequestProperties(
            buildMap {
                r.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                r.origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
            }
        )
        streamUrls = r.allUrls
    }

    LaunchedEffect(streamUrls) {
        if (streamUrls.isEmpty()) return@LaunchedEffect
        // queue every candidate so ExoPlayer / our error handler can walk them
        player.setMediaItems(streamUrls.map { MediaItem.fromUri(it) })
        player.prepare()
    }

    LaunchedEffect(channelKey) { recoveries = 0; lastErrorAt = 0L }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(Unit) { onSetAutoPip(true) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // Live IPTV streams hiccup constantly (timestamp discontinuities,
                // dropped segments, token refreshes). Recover in place first —
                // prepare() re-reads the playlist from the live edge without the
                // slow re-resolve — and only fall back to a fresh URL if that
                // keeps failing. Never tear down the screen for a transient error.
                val now = System.currentTimeMillis()
                if (now - lastErrorAt > 25_000) recoveries = 0 // was stable → fresh budget
                lastErrorAt = now
                recoveries++
                when {
                    recoveries <= 2 -> runCatching { player.prepare() } // in-place, same url
                    player.hasNextMediaItem() -> runCatching {          // try the next candidate url
                        player.seekToNextMediaItem(); player.prepare()
                    }
                    channel?.needsResolve == true && recoveries <= 6 ->
                        attempt++ // re-resolve fresh url(s) from scratch
                    else -> error = playbackErrorText(e)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            onSetAutoPip(false)
            player.removeListener(listener)
            player.release()
        }
    }

    fun wake() { osdVisible = true; osdNonce++ }

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
                    !osdVisible -> { wake(); true }   // first press just reveals the OSD
                    else -> { osdNonce++; false }     // OSD already up → keep it up, let buttons navigate
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { if (osdVisible) osdVisible = false else wake() }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false // our own auto-hiding OSD instead
                    setPlayer(player)
                }
            },
            update = { it.resizeMode = RESIZE_MODES[resizeIdx] },
            modifier = Modifier.fillMaxSize(),
        )

        // buffering / error
        val playing by playbackFlags(player)
        if (error != null) {
            ErrorPanel(error!!, onRetry = { attempt++ })
        } else if (!playing.ready) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                if (channel?.needsResolve == true) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Avvio sorgente… può richiedere ~10 s al primo caricamento",
                        color = Color(0xFFAAAAAA), fontSize = 12.sp,
                    )
                }
            }
        }

        // top controls — auto-hide
        AnimatedVisibility(
            visible = osdVisible && error == null,
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
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(firstBtnFocus).tvFocusable(CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White)
                }
                Text(
                    channel?.name ?: "", color = Color.White, fontSize = 15.sp,
                    maxLines = 1, modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { showStats = !showStats; vm.settings.showStats = showStats },
                    modifier = Modifier.tvFocusable(CircleShape),
                ) {
                    Icon(Icons.Filled.Info, "Statistiche", tint = if (showStats) MaterialTheme.colorScheme.primary else Color.White)
                }
                IconButton(onClick = { muted = !muted }, modifier = Modifier.tvFocusable(CircleShape)) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        "Muto", tint = Color.White,
                    )
                }
                IconButton(
                    onClick = { resizeIdx = (resizeIdx + 1) % RESIZE_MODES.size },
                    modifier = Modifier.tvFocusable(CircleShape),
                ) {
                    Icon(Icons.Filled.AspectRatio, "Formato", tint = Color.White)
                }
                IconButton(onClick = onEnterPipNow, modifier = Modifier.tvFocusable(CircleShape)) {
                    Icon(Icons.Filled.PictureInPictureAlt, "PiP", tint = Color.White)
                }
            }
        }

        // channel + now/next EPG bar — auto-hide, slides up from the bottom
        AnimatedVisibility(
            visible = osdVisible && error == null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            ChannelInfoBar(channel, nowNext)
        }

        if (showStats) {
            StatsOverlay(
                player = player,
                channel = channel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 84.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Impossibile riprodurre", color = Color.White, fontSize = 17.sp)
        Spacer(Modifier.padding(4.dp))
        Text(message, color = Color(0xFFBBBBBB), fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 40.dp))
        Spacer(Modifier.padding(8.dp))
        IconButton(onClick = onRetry, modifier = Modifier.tvFocusable(CircleShape)) {
            Icon(Icons.Filled.Refresh, "Riprova", tint = Color.White)
        }
    }
}

private fun playbackErrorText(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Rete non raggiungibile o CDN bloccato. Prova un'altra rete."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "Il server dello stream ha risposto con errore (potrebbe essere offline)."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        "Formato dello stream non supportato."
    else -> e.errorCodeName
}
