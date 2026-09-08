package com.michde.italyitv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.michde.italyitv.data.IptvRepository
import com.michde.italyitv.data.SettingsStore
import com.michde.italyitv.core.NameTools
import com.michde.italyitv.data.model.Category
import com.michde.italyitv.data.model.Channel
import com.michde.italyitv.data.model.NowNext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChannelsUi(
    val all: List<Channel> = emptyList(),
    val visible: List<Channel> = emptyList(),
    val categories: List<Category> = emptyList(),
    val query: String = "",
    val category: Category? = null,
    val favoritesOnly: Boolean = false,
)

class AppViewModel(
    val repo: IptvRepository,
    val settings: SettingsStore,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<Category?>(null)
    private val favoritesOnly = MutableStateFlow(false)

    val syncState get() = repo.sync
    val syncLog get() = repo.log

    val ui: StateFlow<ChannelsUi> =
        combine(
            repo.channels, settings.favorites, query, category, favoritesOnly,
        ) { channels, favs, q, cat, favOnly ->
            val withFav = channels.map { it.copy(isFavorite = it.key in favs) }
            val cats = withFav.map { it.category }.distinct().sortedBy { it.ordinal }
            val needle = NameTools.matchKey(q)
            val visible = withFav.asSequence()
                .filter { !favOnly || it.isFavorite }
                .filter { cat == null || it.category == cat }
                .filter { q.isBlank() || NameTools.matchKey(it.name).contains(needle) || it.rawName.contains(q, true) }
                .toList()
            ChannelsUi(withFav, visible, cats, q, cat, favOnly)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChannelsUi())

    val nowNext: StateFlow<Map<String, NowNext>> = repo.nowNext

    init {
        viewModelScope.launch { repo.refresh() }
        viewModelScope.launch {
            while (true) { delay(60_000); repo.computeNowNext() }
        }
    }

    fun setQuery(v: String) { query.value = v }
    fun setCategory(v: Category?) { category.value = v }
    fun toggleFavoritesOnly() { favoritesOnly.value = !favoritesOnly.value }
    fun toggleFavorite(key: String) = settings.toggleFavorite(key)
    fun refresh() = viewModelScope.launch { repo.refresh(force = true) }
    fun channel(key: String): Channel? = repo.channels.value.firstOrNull { it.key == key }

    class Factory(private val repo: IptvRepository, private val settings: SettingsStore) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(repo, settings) as T
    }
}
