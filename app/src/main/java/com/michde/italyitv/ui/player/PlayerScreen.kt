package com.michde.italyitv.ui.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.michde.italyitv.data.model.Channel
import com.michde.italyitv.ui.AppViewModel

private val RESIZE_MODES = intArrayOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    AspectRatioFrameLayout.RESIZE_MODE_FILL,
)

/** silent re-resolve attempts on a playback error before surfacing it */
private const val MAX_AUTO_RETRY = 2

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
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var autoRetries by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(vm.settings.showStats) }

    // one factory instance, headers updated per-resolution before each prepare()
    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(channel?.userAgent ?: "Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
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

    // resolve stream url + apply the headers its CDN needs to every request
    LaunchedEffect(channelKey, attempt) {
        error = null
        streamUrl = null
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
        streamUrl = r.url
    }

    LaunchedEffect(streamUrl) {
        val url = streamUrl ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    LaunchedEffect(channelKey) { autoRetries = 0 }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(Unit) { onSetAutoPip(true) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // Daddy/huhu streams hand out short-lived tokens and flaky CDN
                // edges — re-resolve a couple of times before giving up.
                if (channel?.needsResolve == true && autoRetries < MAX_AUTO_RETRY) {
                    autoRetries++
                    attempt++
                } else {
                    error = playbackErrorText(e)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) autoRetries = 0
            }
        }
        player.addListener(listener)
        onDispose {
            onSetAutoPip(false)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(true)
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

        // top controls
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
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                showStats = !showStats; vm.settings.showStats = showStats
            }) {
                Icon(Icons.Filled.Info, "Info", tint = if (showStats) MaterialTheme.colorScheme.primary else Color.White)
            }
            IconButton(onClick = { muted = !muted }) {
                Icon(
                    if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    "Muto", tint = Color.White,
                )
            }
            IconButton(onClick = { resizeIdx = (resizeIdx + 1) % RESIZE_MODES.size }) {
                Icon(Icons.Filled.AspectRatio, "Formato", tint = Color.White)
            }
            IconButton(onClick = onEnterPipNow) {
                Icon(Icons.Filled.PictureInPictureAlt, "PiP", tint = Color.White)
            }
        }

        if (showStats) {
            StatsOverlay(
                player = player,
                channel = channel,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
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
        IconButton(onClick = onRetry) {
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
