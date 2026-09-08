package com.michde.italyitv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.michde.italyitv.data.model.SyncState
import com.michde.italyitv.ui.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val sync by vm.syncState.collectAsStateWithLifecycle()
    var showStats by remember { mutableStateOf(vm.settings.showStats) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 28.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("Impostazioni", style = MaterialTheme.typography.titleLarge, fontSize = 20.sp)
        }

        SettingRow(
            title = "Statistiche player",
            subtitle = "Mostra bitrate, buffer, risoluzione e codec durante la riproduzione",
        ) {
            Switch(checked = showStats, onCheckedChange = {
                showStats = it; vm.settings.showStats = it
            })
        }

        Row(
            Modifier.fillMaxWidth().clickable { vm.refresh() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ricarica catalogo ed EPG", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                val s = when (val st = sync) {
                    is SyncState.Done -> "${st.channels} canali · ${st.withEpg} con EPG · ${st.withLogo} con logo"
                    is SyncState.Running -> "In corso: ${st.step}…"
                    is SyncState.Failed -> "Ultimo errore: ${st.message}"
                    else -> "—"
                }
                Text(s, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Sorgente: catalogo huhu.to + canali curati (dlive.sx). EPG e loghi da epgshare01.online. " +
                "Questa app non ospita contenuti: legge liste pubblicate da terzi.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        control()
    }
}
