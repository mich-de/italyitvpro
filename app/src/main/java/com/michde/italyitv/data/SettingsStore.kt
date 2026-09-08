package com.michde.italyitv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("canali_pro", Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(prefs.getStringSet("favorites", emptySet())!!.toSet())
    val favorites: StateFlow<Set<String>> = _favorites

    var showStats: Boolean
        get() = prefs.getBoolean("show_stats", false)
        set(v) = prefs.edit().putBoolean("show_stats", v).apply()

    var italianOnly: Boolean
        get() = prefs.getBoolean("italian_only", false)
        set(v) = prefs.edit().putBoolean("italian_only", v).apply()

    var lastSyncMs: Long
        get() = prefs.getLong("last_sync", 0L)
        set(v) = prefs.edit().putLong("last_sync", v).apply()

    fun toggleFavorite(key: String) {
        val cur = _favorites.value.toMutableSet()
        if (!cur.add(key)) cur.remove(key)
        _favorites.value = cur
        prefs.edit().putStringSet("favorites", cur).apply()
    }
}
