package com.michde.italyitv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import com.michde.italyitv.core.NameTools
import com.michde.italyitv.data.model.Category
import com.michde.italyitv.data.model.Channel
import com.michde.italyitv.data.model.NowNext
import com.michde.italyitv.data.model.SyncState
import com.michde.italyitv.ui.AppViewModel

@Composable
fun ChannelsScreen(
    vm: AppViewModel,
    onOpenChannel: (Channel) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nowNext by vm.nowNext.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()

    // swallow stray taps that arrive right as this screen appears
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(400); armed = true }
    val openChannel: (Channel) -> Unit = { if (armed) onOpenChannel(it) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 28.dp),
    ) {
        // top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Canali Italia", style = MaterialTheme.typography.titleLarge, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.toggleFavoritesOnly() }) {
                Icon(
                    if (ui.favoritesOnly) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    "Preferiti",
                    tint = if (ui.favoritesOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Filled.Refresh, "Aggiorna", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, "Impostazioni", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SearchField(value = ui.query, onValueChange = vm::setQuery)

        // category chips
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("Tutti", ui.category == null) { vm.setCategory(null) }
            ui.categories.forEach { c ->
                Chip(c.label, ui.category == c) { vm.setCategory(c) }
            }
        }

        if (sync is SyncState.Running) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(ui.visible, key = { it.key }) { ch ->
                ChannelRow(ch, nowNext[ch.key], onClick = { openChannel(ch) },
                    onFav = { vm.toggleFavorite(ch.key) })
            }
            if (ui.visible.isEmpty() && sync !is SyncState.Running) {
                item {
                    Text(
                        if (ui.query.isBlank()) "Nessun canale" else "Nessun risultato per «${ui.query}»",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    // local TextFieldValue keeps the caret stable regardless of upstream lag
    var tfv by remember { mutableStateOf(TextFieldValue(value)) }
    OutlinedTextField(
        value = tfv,
        onValueChange = { tfv = it; onValueChange(it.text) },
        placeholder = { Text("Cerca canale…") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (tfv.text.isNotEmpty()) IconButton(onClick = {
                tfv = TextFieldValue(""); onValueChange("")
            }) { Icon(Icons.Filled.Close, "Cancella") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Initials(name: String) {
    Text(
        NameTools.clean(name).filter { it.isLetterOrDigit() || it == ' ' }
            .split(' ').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold, fontSize = 13.sp,
    )
}

@Composable
private fun ChannelRow(ch: Channel, nn: NowNext?, onClick: () -> Unit, onFav: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(58.dp, 42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (ch.logo != null) {
                SubcomposeAsyncImage(
                    model = ch.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    error = { Initials(ch.name) },
                    loading = {},
                )
            } else {
                Initials(ch.name)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ch.name, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = nn?.now?.let { "${it.title}" } ?: ch.category.label
            Text(
                sub, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onFav) {
            Icon(
                if (ch.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                "Preferito",
                tint = if (ch.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
