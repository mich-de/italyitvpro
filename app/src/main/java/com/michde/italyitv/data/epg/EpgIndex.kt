package com.michde.italyitv.data.epg

import com.michde.italyitv.core.NameTools
import com.michde.italyitv.data.model.NowNext
import com.michde.italyitv.data.model.Programme
import com.michde.italyitv.data.parser.EpgChannel

/** In-memory EPG: name matching + now/next lookup. */
class EpgIndex(
    epgChannels: List<EpgChannel>,
    programmes: List<Programme>,
) {
    /** matchKey -> epg channel id */
    private val byName: Map<String, String> = buildMap {
        for (c in epgChannels) {
            for (n in c.names) {
                val k = NameTools.matchKey(n)
                if (k.length >= 3) putIfAbsent(k, c.id)
            }
            val idk = NameTools.matchKey(c.id.substringBefore('.'))
            if (idk.length >= 3) putIfAbsent(idk, c.id)
        }
    }

    /** epg channel id -> icon url */
    val iconById: Map<String, String> =
        epgChannels.mapNotNull { c -> c.icon?.let { c.id to it } }.toMap()

    /** epg channel id -> programmes sorted by start */
    private val prgById: Map<String, List<Programme>> =
        programmes.groupBy { it.channelId }.mapValues { (_, v) -> v.sortedBy { it.startMs } }

    val programmeCount = programmes.size
    val channelCount = epgChannels.size

    /** Best-effort EPG id for a channel; null if no match. */
    fun matchId(explicitTvgId: String?, channelName: String): String? {
        if (!explicitTvgId.isNullOrBlank() && prgById.containsKey(explicitTvgId)) return explicitTvgId
        if (!explicitTvgId.isNullOrBlank()) {
            byName[NameTools.matchKey(explicitTvgId.substringBefore('.'))]?.let { return it }
        }
        val k = NameTools.matchKey(channelName)
        byName[k]?.let { return it }
        // relaxed: prefix match
        if (k.length >= 5) {
            byName.entries.firstOrNull { it.key.startsWith(k) || k.startsWith(it.key) }?.let { return it.value }
        }
        return null
    }

    fun nowNext(epgId: String?, nowMs: Long): NowNext {
        val list = prgById[epgId] ?: return NowNext()
        val idx = list.indexOfFirst { nowMs < it.stopMs }
        if (idx < 0) return NowNext()
        val current = list[idx].takeIf { nowMs >= it.startMs }
        val next = when {
            current != null -> list.getOrNull(idx + 1)
            else -> list[idx] // upcoming
        }
        return NowNext(now = current, next = next)
    }

    companion object {
        val EMPTY = EpgIndex(emptyList(), emptyList())
    }
}
