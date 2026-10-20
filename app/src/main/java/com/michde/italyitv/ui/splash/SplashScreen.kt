package com.michde.italyitv.ui.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michde.italyitv.data.model.SyncLine
import com.michde.italyitv.data.model.SyncState
import com.michde.italyitv.ui.AppViewModel
import com.michde.italyitv.ui.theme.Amber
import com.michde.italyitv.ui.theme.InkSurface
import com.michde.italyitv.ui.theme.Ivory
import com.michde.italyitv.ui.theme.IvoryDim
import com.michde.italyitv.ui.theme.SignRed
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(vm: AppViewModel, onReady: () -> Unit) {
    val state by vm.syncState.collectAsStateWithLifecycle()
    val log by vm.syncLog.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }
    LaunchedEffect(state) {
        if (state is SyncState.Done) { delay(650); onReady() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Amber),
                    contentAlignment = Alignment.Center,
                ) { Text("IT", color = Color(0xFF2A1B04), fontWeight = FontWeight.Black, fontSize = 20.sp) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Canali Italia", color = Ivory, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Text("Pro", color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(22.dp))

            val anim by rememberInfiniteTransition(label = "p").animateFloat(
                initialValue = 0.15f, targetValue = 0.85f,
                animationSpec = infiniteRepeatable(
                    tween(900, easing = LinearEasing), RepeatMode.Reverse,
                ),
                label = "pv",
            )
            val fill = if (state is SyncState.Done) 1f else anim
            Box(
                Modifier
                    .width(220.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(InkSurface),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(3.dp)
                        .background(Amber),
                )
            }

            Spacer(Modifier.height(18.dp))

            Box(
                Modifier
                    .width(340.dp)
                    .height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(InkSurface)
                    .padding(12.dp),
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(log) { line -> LogRow(line) }
                    if (state is SyncState.Running) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(11.dp), color = Amber, strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    (state as SyncState.Running).step + "…",
                                    color = IvoryDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            if (state is SyncState.Failed) {
                Spacer(Modifier.height(10.dp))
                Text((state as SyncState.Failed).message, color = SignRed, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LogRow(line: SyncLine) {
    val color = when (line.kind) {
        SyncLine.Kind.OK -> Color(0xFF7FD488)
        SyncLine.Kind.WARN -> Amber
        SyncLine.Kind.ERROR -> SignRed
        else -> IvoryDim
    }
    val bullet = when (line.kind) {
        SyncLine.Kind.OK -> "✓ "
        SyncLine.Kind.WARN -> "! "
        SyncLine.Kind.ERROR -> "✗ "
        else -> "› "
    }
    Text(
        bullet + line.text,
        color = color,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
}
