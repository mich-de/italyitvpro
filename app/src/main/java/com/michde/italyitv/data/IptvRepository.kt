package com.michde.italyitv.data

import com.michde.italyitv.core.Http
import com.michde.italyitv.core.NameTools
import com.michde.italyitv.data.epg.EpgIndex
import com.michde.italyitv.data.epg.LogoResolver
import com.michde.italyitv.data.model.Channel
import com.michde.italyitv.data.model.NowNext
import com.michde.italyitv.data.model.ParsedChannel
import com.michde.italyitv.data.model.ResolvedStream
import com.michde.italyitv.data.model.SyncLine
import com.michde.italyitv.data.model.SyncState
import com.michde.italyitv.data.parser.XmltvParser
import com.michde.italyitv.data.remote.DliveResolver
import com.michde.italyitv.data.remote.HuhuApi
import com.michde.italyitv.data.remote.InjectedChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IptvRepository(private val settings: SettingsStore) {

    private companion object {
        const val EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"
    }

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels

    private val _nowNext = MutableStateFlow<Map<String, NowNext>>(emptyMap())
    val nowNext: StateFlow<Map<String, NowNext>> = _nowNext

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    private val _log = MutableStateFlow<List<SyncLine>>(emptyList())
    val log: StateFlow<List<SyncLine>> = _log

    private var epg: EpgIndex = EpgIndex.EMPTY
    private val refreshLock = Mutex()

    private fun log(text: String, kind: SyncLine.Kind = SyncLine.Kind.INFO) {
        _log.update { it + SyncLine(text, kind) }
    }

    /** Full pipeline: catalog -> inject -> clean -> EPG match -> now/next. */
    suspend fun refresh(force: Boolean = false) = refreshLock.withLock {
        if (_channels.value.isNotEmpty() && !force) return@withLock
        _log.value = emptyList()
        _sync.value = SyncState.Running("Avvio")
        try {
            withContext(Dispatchers.IO) {
                // 1. catalog
                _sync.value = SyncState.Running("Catalogo")
                log("Connessione a huhu.to…")
                val raw: List<ParsedChannel> = runCatching { HuhuApi.loadItalianChannels() }
                    .onFailure { log("Catalogo huhu.to non disponibile: ${it.message}", SyncLine.Kind.WARN) }
                    .getOrDefault(emptyList())
                log("Scaricati ${raw.size} canali dal catalogo", SyncLine.Kind.OK)

                // 2. inject curated channels (dedup by key)
                val merged = LinkedHashMap<String, ParsedChannel>()
                InjectedChannels.list.forEach { merged[it.key] = it }
                raw.forEach { merged.putIfAbsent(it.key, it) }
                log("Aggiunti ${InjectedChannels.list.size} canali curati (DAZN/Sky/Rai)")

                // 3. EPG
                _sync.value = SyncState.Running("EPG")
                log("Scarico la guida EPG…")
                epg = runCatching {
                    Http.openStream(EPG_URL).use { XmltvParser.parse(it) }
                        .let { EpgIndex(it.channels, it.programmes) }
                }.onFailure { log("EPG non disponibile: ${it.message}", SyncLine.Kind.WARN) }
                    .getOrDefault(EpgIndex.EMPTY)
                if (epg.channelCount > 0)
                    log("EPG: ${epg.channelCount} canali, ${epg.programmeCount} programmi", SyncLine.Kind.OK)

                // 3b. logo sources
                _sync.value = SyncState.Running("Loghi")
                log("Scarico i loghi (Free-TV + tv-logos)…")
                val logos = LogoResolver.load()
                if (logos.size > 0) log("Indice loghi: ${logos.size} voci", SyncLine.Kind.OK)

                // 4. enrich
                _sync.value = SyncState.Running("Abbinamento")
                var withEpg = 0
                var withLogo = 0
                val curatedKeys = InjectedChannels.list.map { it.key }.toSet()
                val enriched = merged.values.map { pc ->
                    val clean = NameTools.clean(pc.name)
                    val epgId = epg.matchId(pc.tvgId, clean)
                    if (epgId != null) withEpg++
                    val epgIcon = epgId?.let { epg.iconById[it] }
                    // huhu catalog logos live on a dead CDN — prefer our resolver, keep
                    // the catalog url only if it points somewhere else.
                    val catalogLogo = pc.logo?.takeIf {
                        !it.contains("logo.huhu.to") && !it.contains("huhu.to")
                    }
                    val logo = logos.resolve(clean, epgIcon) ?: catalogLogo
                    if (logo != null) withLogo++
                    // tag the backend so the two copies of a channel are told apart:
                    // "(Daddy)" is already baked into the injected names, huhu gets "(huhu)".
                    val display = when {
                        HuhuApi.isPlayHandle(pc.url) && !clean.contains("(huhu)", true) -> "$clean (huhu)"
                        else -> clean
                    }
                    Channel(
                        key = pc.key,
                        name = display,
                        rawName = pc.name,
                        url = pc.url,
                        logo = logo,
                        category = NameTools.classify(clean, pc.group),
                        tvgId = epgId,
                        userAgent = pc.userAgent,
                        referrer = pc.referrer,
                        needsResolve = HuhuApi.isPlayHandle(pc.url) || DliveResolver.isDlive(pc.url),
                    )
                }
                // collapse duplicate huhu entries, but never touch curated channels
                val (curated, fromCatalog) = enriched.partition { it.key in curatedKeys }
                val deduped = (curated + fromCatalog
                    .groupBy { NameTools.matchKey(it.name) + "|" + it.category.ordinal }
                    .map { (_, group) ->
                        group.maxByOrNull { (if (it.tvgId != null) 2 else 0) + (if (it.logo != null) 1 else 0) }!!
                    })
                    .sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))

                _channels.value = deduped
                log("Abbinati $withEpg canali all'EPG, $withLogo con logo", SyncLine.Kind.OK)
                computeNowNext()
                settings.lastSyncMs = System.currentTimeMillis()
                _sync.value = SyncState.Done(deduped.size, withEpg, withLogo)
                log("Pronto: ${deduped.size} canali", SyncLine.Kind.OK)
            }
        } catch (t: Throwable) {
            _sync.value = SyncState.Failed(t.message ?: "Errore")
            log("Errore: ${t.message}", SyncLine.Kind.ERROR)
        }
    }

    fun computeNowNext() {
        val now = System.currentTimeMillis()
        _nowNext.value = _channels.value.associate { it.key to epg.nowNext(it.tvgId, now) }
    }

    /** dlive/Daddy channels play through a WebView; this is the page it loads. */
    suspend fun dlivePlayerPage(channel: Channel): String? = withContext(Dispatchers.IO) {
        runCatching { DliveResolver.playerPageUrl(channel.url) }.getOrNull()
    }

    /** All "Player 1..N" backends for a dlive channel, in order (walk on failure). */
    suspend fun dlivePlayerPages(channel: Channel): List<String> = withContext(Dispatchers.IO) {
        runCatching { DliveResolver.playerPageUrls(channel.url) }.getOrDefault(emptyList())
    }

    /**
     * Resolve a play handle / dlive url to a real stream plus the headers its CDN
     * demands on every request. Always returns something playable-ish; callers
     * should still be ready for a 403 and retry.
     */
    suspend fun resolveStream(channel: Channel): ResolvedStream = withContext(Dispatchers.IO) {
        val fallbackRef = channel.referrer?.takeIf { it.isNotBlank() }
        when {
            HuhuApi.isPlayHandle(channel.url) -> {
                val ua = channel.userAgent ?: Http.OKHTTP_UA
                val urls = runCatching { HuhuApi.resolveAll(channel.url, ua) }
                    .getOrDefault(emptyList())
                    .ifEmpty { listOf(channel.url) }
                ResolvedStream(
                    url = urls.first(),
                    fallbacks = urls.drop(1),
                    referer = fallbackRef,
                    origin = fallbackRef?.trimEnd('/'),
                    userAgent = ua,
                )
            }
            DliveResolver.isDlive(channel.url) ->
                runCatching { DliveResolver.resolve(channel.url) }.getOrNull()
                    ?: ResolvedStream(
                        url = channel.url,
                        referer = fallbackRef,
                        origin = fallbackRef?.trimEnd('/'),
                        userAgent = channel.userAgent,
                    )
            else -> {
                val urls = Http.withHttpFallback(channel.url)
                ResolvedStream(
                    url = urls.first(),
                    fallbacks = urls.drop(1),
                    referer = fallbackRef,
                    origin = fallbackRef?.trimEnd('/'),
                    userAgent = channel.userAgent ?: Http.DEFAULT_UA,
                )
            }
        }
    }
}
