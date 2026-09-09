package com.michde.italyitv.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.michde.italyitv.data.model.Channel
import com.michde.italyitv.data.model.NowNext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

data class PlaybackFlags(val ready: Boolean, val playing: Boolean, val buffering: Boolean)

private val HHMM = SimpleDateFormat("HH:mm", Locale.ITALY)
private fun hhmm(ms: Long) = if (ms > 0) HHMM.format(ms) else "--:--"

/** Bottom OSD bar: channel name + current/next EPG programme with a progress bar. */
@Composable
fun ChannelInfoBar(channel: Channel?, nn: NowNext?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            channel?.name ?: "",
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        val now = nn?.now
        val next = nn?.next
        if (now == null && next == null) {
            Spacer(Modifier.height(4.dp))
            Text("Nessuna guida EPG per questo canale", color = Color(0xFF9AA0A6), fontSize = 13.sp)
        } else {
            if (now != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ORA  ", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${hhmm(now.startMs)}–${hhmm(now.stopMs)}  ", color = Color(0xFFBFC4CC), fontSize = 13.sp)
                    Text(now.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                LinearProgressIndicator(
                    progress = { nn.progressAt(System.currentTimeMillis()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0x33FFFFFF),
                )
            }
            if (next != null) {
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("POI  ", color = Color(0xFF9AA0A6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("${hhmm(next.startMs)}  ", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                    Text(next.title, color = Color(0xFFCED2D8), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun playbackFlags(player: Player): State<PlaybackFlags> {
    val state = remember { mutableStateOf(PlaybackFlags(false, false, false)) }
    DisposableEffect(player) {
        fun push() {
            state.value = PlaybackFlags(
                ready = player.playbackState == Player.STATE_READY,
                playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
            )
        }
        val l = object : Player.Listener {
            override fun onEvents(p: Player, e: Player.Events) = push()
        }
        player.addListener(l)
        push()
        onDispose { player.removeListener(l) }
    }
    return state
}

@OptIn(UnstableApi::class)
@Composable
fun StatsOverlay(player: ExoPlayer, channel: Channel?, modifier: Modifier = Modifier) {
    val text by produceState("", player) {
        while (true) {
            value = buildStats(player, channel)
            delay(1000)
        }
    }
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        text.split('\n').forEach {
            Text(it, color = Color(0xFFB6F5C0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@OptIn(UnstableApi::class)
private fun buildStats(player: ExoPlayer, channel: Channel?): String {
    val sb = StringBuilder()
    val vf: Format? = player.videoFormat
    val af: Format? = player.audioFormat
    val vs = player.videoSize

    fun kbps(v: Int) = if (v == Format.NO_VALUE || v <= 0) "?" else "${v / 1000} kbps"

    sb.append("stato   : ").append(
        when (player.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> if (player.isPlaying) "playing" else "pausa"
            Player.STATE_ENDED -> "fine"
            else -> "?"
        }
    ).append('\n')

    if (vs.width > 0) sb.append("video   : ${vs.width}x${vs.height}")
    else sb.append("video   : —")
    if (vf != null) {
        if (vf.frameRate > 0) sb.append(" @${"%.0f".format(vf.frameRate)}fps")
        sb.append("  ").append(vf.codecs ?: vf.sampleMimeType ?: "")
        sb.append("  ").append(kbps(vf.bitrate))
    }
    sb.append('\n')

    if (af != null) {
        sb.append("audio   : ")
            .append(af.language ?: "und").append("  ")
            .append(af.codecs ?: af.sampleMimeType ?: "").append("  ")
            .append(if (af.channelCount > 0) "${af.channelCount}ch " else "")
            .append(if (af.sampleRate > 0) "${af.sampleRate / 1000}kHz " else "")
            .append(kbps(af.bitrate))
            .append('\n')
    }

    val bufSec = ((player.bufferedPosition - player.currentPosition).coerceAtLeast(0)) / 1000.0
    sb.append("buffer  : ${"%.1f".format(bufSec)}s (${player.bufferedPercentage}%)\n")

    val est = player.totalBufferedDuration
    sb.append("caricato: ${est / 1000}s\n")

    channel?.let { sb.append("sorgente: ").append(if (it.needsResolve) "risolto" else "diretto") }
    return sb.toString()
}
