package com.michde.italyitv.data.epg

import com.michde.italyitv.core.NameTools
import com.michde.italyitv.data.model.NowNext
import com.michde.italyitv.data.model.Programme
import com.michde.italyitv.data.parser.EpgChannel
import kotlin.math.abs

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
            // key from the FULL id ("Sky.Sport.Uno.it" -> "skysportuno"), not just the
            // first segment — that produced a bare "sky" that shadowed every Sky channel.
            val idk = NameTools.matchKey(
                c.id.removeSuffix(".it").replace("..", ".").replace('.', ' ')
            )
            if (idk.length >= 5) putIfAbsent(idk, c.id)
        }
    }

    /** epg channel id -> icon url */
    val iconById: Map<String, String> =
        epgChannels.mapNotNull { c -> c.icon?.let { c.id to it } }.toMap()

    /** epg channel id -> programmes sorted by start */
    private val prgById: Map<String, List<Programme>> =
        programmes.groupBy { it.channelId }.mapValues { (_, v) -> v.sortedBy { it.startMs } }

    /** Generic names that match no EPG channel exactly but have an obvious target. */
    private val aliases: Map<String, String> = buildMap {
        fun add(key: String, vararg ids: String) {
            ids.firstOrNull { prgById.containsKey(it) }?.let { put(key, it) }
        }
        add("skysport", "Sky.Sport.Uno.it", "Sky.Sport.24.it")
        add("skysportseriea", "Sky.Sport.Calcio.it", "Sky.Sport.Uno.it")
        add("skycinema", "Sky.Cinema.Uno.it")
        add("skycalcio1", "Sky.Sport..251.it")
        add("skycalcio2", "Sky.Sport..252.it")
        add("skycalcio3", "Sky.Sport..253.it")
        add("skycalcio4", "Sky.Sport..254.it")
        add("skycalcio5", "Sky.Sport..255.it")
        add("skycalcio6", "Sky.Sport..256.it")
        add("skycalcio7", "Sky.Sport..257.it")
        add("dazn", "DAZN.1.it.it")
        add("dazn1", "DAZN.1.it.it")
        add("dazn2", "DAZN.2.it.it")
        add("raisport", "RaiSport.it", "RAI.Sport...227.it")
        add("eurosport1", "Eurosport.Italia.it")
        add("eurosport2", "Eurosport.2.Italia.it")
    }

    val programmeCount = programmes.size
    val channelCount = epgChannels.size

    /** Best-effort EPG id for a channel; null when no confident match (better no EPG than wrong EPG). */
    fun matchId(explicitTvgId: String?, channelName: String): String? {
        // 1. explicit tvg-id from the source
        if (!explicitTvgId.isNullOrBlank()) {
            if (prgById.containsKey(explicitTvgId)) return explicitTvgId
            byName[NameTools.matchKey(explicitTvgId.substringBefore('.'))]
                ?.takeIf { prgById.containsKey(it) }?.let { return it }
        }

        val k = NameTools.matchKey(channelName)
        if (k.length < 3) return null

        // 2. curated alias for a known-generic name
        aliases[k]?.let { return it }

        // 3. exact key match
        byName[k]?.takeIf { prgById.containsKey(it) }?.let { return it }

        // 4. close prefix match, but ONLY if it is unambiguous — one candidate, similar length.
        //    (A loose "startsWith" here is what sent every "Sky …" to "Sky Uno".)
        if (k.length >= 6) {
            val cands = byName.entries
                .filter { e ->
                    e.key.length >= 6 &&
                        abs(e.key.length - k.length) <= 3 &&
                        (e.key.startsWith(k) || k.startsWith(e.key)) &&
                        prgById.containsKey(e.value)
                }
                .map { it.value }
                .distinct()
            if (cands.size == 1) return cands.first()
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
