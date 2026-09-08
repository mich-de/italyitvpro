package com.michde.italyitv.data.model

/** A channel as parsed from a source, before enrichment. */
data class ParsedChannel(
    val key: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String = "Italia",
    val tvgId: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
)

/**
 * A stream URL plus the HTTP headers the CDN needs on every request
 * (playlist, key and media segments alike).
 */
data class ResolvedStream(
    val url: String,
    val referer: String? = null,
    val origin: String? = null,
    val userAgent: String? = null,
)

/** A fully enriched channel shown in the UI. */
data class Channel(
    val key: String,
    val name: String,
    val rawName: String,
    val url: String,
    val logo: String?,
    val category: Category,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
    val isFavorite: Boolean = false,
    val needsResolve: Boolean = false,
)

enum class Category(val label: String) {
    GENERALISTI("Generalisti"),
    SPORT("Sport"),
    CINEMA("Cinema"),
    SERIE("Serie & Intrattenimento"),
    NEWS("News"),
    BAMBINI("Bambini"),
    DOCUMENTARI("Documentari"),
    MUSICA("Musica"),
    LOCALI("Locali"),
    ALTRO("Altro"),
}

data class Programme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val desc: String? = null,
)

data class NowNext(
    val now: Programme? = null,
    val next: Programme? = null,
) {
    fun progressAt(nowMs: Long): Float {
        val p = now ?: return 0f
        val span = (p.stopMs - p.startMs).coerceAtLeast(1L)
        return ((nowMs - p.startMs).toFloat() / span).coerceIn(0f, 1f)
    }
}

/** One line in the loading log shown on the splash screen. */
data class SyncLine(val text: String, val kind: Kind = Kind.INFO) {
    enum class Kind { INFO, OK, WARN, ERROR }
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val step: String) : SyncState
    data class Done(val channels: Int, val withEpg: Int, val withLogo: Int) : SyncState
    data class Failed(val message: String) : SyncState
}
