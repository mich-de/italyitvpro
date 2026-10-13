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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class IptvRepository(
    private val context: android.content.Context,
    private val settings: SettingsStore,
) {

    private companion object {
        const val EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"
        const val CACHE_MAX_AGE_MS = 6 * 3600 * 1000L // 6 hours
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

    /** Full pipeline: cache check -> parallel sync (catalog + EPG + logos) -> merge -> save. */
    suspend fun refresh(force: Boolean = false) = refreshLock.withLock {
        if (_channels.value.isNotEmpty() && !force) return@withLock
        _log.value = emptyList()

        // 1. Instant startup from local disk cache if fresh
        if (!force) {
            val cached = withContext(Dispatchers.IO) { loadCache() }
            if (cached != null) {
                _channels.value = cached.first
                epg = cached.second
                computeNowNext()
                val withEpg = cached.first.count { it.tvgId != null }
                val withLogo = cached.first.count { it.logo != null }
                _sync.value = SyncState.Done(cached.first.size, withEpg, withLogo)
                log("Caricamento rapido: ${cached.first.size} canali da cache", SyncLine.Kind.OK)
                return@withLock
            }
        }

        _sync.value = SyncState.Running("Avvio")
        try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    _sync.value = SyncState.Running("Sincronizzazione")
                    log("Avvio download in parallelo (catalogo, EPG, loghi)…")

                    // 1. Fetch catalog in background
                    val catalogDeferred = async {
                        val raw: List<ParsedChannel> = runCatching { HuhuApi.loadItalianChannels() }
                            .onFailure { log("Catalogo huhu.to non disponibile: ${it.message}", SyncLine.Kind.WARN) }
                            .getOrDefault(emptyList())
                        log("Catalogo: ${raw.size} canali scaricati", SyncLine.Kind.OK)
                        raw
                    }

                    // 2. Fetch and parse EPG in background (with local gz caching)
                    val epgDeferred = async {
                        val cachedGz = context.cacheDir.resolve("epg.xml.gz")
                        var parsed: com.michde.italyitv.data.parser.EpgData? = null

                        if (!force && cachedGz.exists() && (System.currentTimeMillis() - cachedGz.lastModified() < 8 * 3600 * 1000L)) {
                            parsed = runCatching {
                                java.io.FileInputStream(cachedGz).use { fis ->
                                    java.util.zip.GZIPInputStream(fis).use { gz -> XmltvParser.parse(gz) }
                                }
                            }.getOrNull()
                        }

                        if (parsed == null) {
                            parsed = runCatching {
                                val bytes = Http.getBytes(EPG_URL, Http.OKHTTP_UA)
                                runCatching { cachedGz.writeBytes(bytes) }
                                java.io.ByteArrayInputStream(bytes).use { bis ->
                                    java.util.zip.GZIPInputStream(bis).use { gz -> XmltvParser.parse(gz) }
                                }
                            }.onFailure { log("EPG non disponibile: ${it.message}", SyncLine.Kind.WARN) }
                                .getOrNull()
                        }

                        val res = if (parsed != null) EpgIndex(parsed.channels, parsed.programmes) else EpgIndex.EMPTY
                        if (res.channelCount > 0) {
                            log("EPG: ${res.channelCount} canali, ${res.programmeCount} programmi", SyncLine.Kind.OK)
                        }
                        Pair(res, parsed)
                    }

                    // 3. Fetch logo index in background
                    val logosDeferred = async {
                        val l = LogoResolver.load(context)
                        if (l.size > 0) log("Indice loghi: ${l.size} voci", SyncLine.Kind.OK)
                        l
                    }

                    val raw = catalogDeferred.await()
                    val (epgIndex, epgData) = epgDeferred.await()
                    val logos = logosDeferred.await()
                    epg = epgIndex

                    // Merge curated channels
                    val merged = LinkedHashMap<String, ParsedChannel>()
                    InjectedChannels.list.forEach { merged[it.key] = it }
                    raw.forEach { merged.putIfAbsent(it.key, it) }
                    log("Aggiunti ${InjectedChannels.list.size} canali curati (DAZN/Sky/Rai)")

                    // Enrich
                    _sync.value = SyncState.Running("Abbinamento")
                    var withEpg = 0
                    var withLogo = 0
                    val curatedKeys = InjectedChannels.list.map { it.key }.toSet()
                    val enriched = merged.values.map { pc ->
                        val clean = NameTools.clean(pc.name)
                        val epgId = epg.matchId(pc.tvgId, clean)
                        if (epgId != null) withEpg++
                        val epgIcon = epgId?.let { epg.iconById[it] }
                        val catalogLogo = pc.logo?.takeIf {
                            !it.contains("logo.huhu.to") && !it.contains("huhu.to")
                        }
                        val logo = logos.resolve(clean, epgIcon) ?: catalogLogo
                        if (logo != null) withLogo++
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

                    val (curated, fromCatalog) = enriched.partition { it.key in curatedKeys }
                    val deduped = (curated + fromCatalog
                        .groupBy { NameTools.matchKey(it.name) + "|" + it.category.ordinal }
                        .map { (_, group) ->
                            group.maxByOrNull { (if (it.tvgId != null) 2 else 0) + (if (it.logo != null) 1 else 0) }!!
                        })
                        .sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))

                    _channels.value = deduped
                    computeNowNext()
                    settings.lastSyncMs = System.currentTimeMillis()

                    if (epgData != null) {
                        saveCache(deduped, epgData.channels, epgData.programmes)
                    }

                    _sync.value = SyncState.Done(deduped.size, withEpg, withLogo)
                    log("Pronto: ${deduped.size} canali ($withEpg con guida)", SyncLine.Kind.OK)
                }
            }
        } catch (t: Throwable) {
            _sync.value = SyncState.Failed(t.message ?: "Errore")
            log("Errore: ${t.message}", SyncLine.Kind.ERROR)
        }
    }

    private fun saveCache(
        channels: List<Channel>,
        epgChannels: List<com.michde.italyitv.data.parser.EpgChannel>,
        programmes: List<com.michde.italyitv.data.model.Programme>,
    ) {
        runCatching {
            val chFile = context.cacheDir.resolve("channels_cache.json")
            val chArr = org.json.JSONArray()
            for (c in channels) {
                val o = org.json.JSONObject()
                o.put("key", c.key)
                o.put("name", c.name)
                o.put("rawName", c.rawName)
                o.put("url", c.url)
                if (c.logo != null) o.put("logo", c.logo)
                o.put("category", c.category.name)
                if (c.tvgId != null) o.put("tvgId", c.tvgId)
                if (c.userAgent != null) o.put("userAgent", c.userAgent)
                if (c.referrer != null) o.put("referrer", c.referrer)
                o.put("needsResolve", c.needsResolve)
                chArr.put(o)
            }
            chFile.writeText(chArr.toString())

            val epgFile = context.cacheDir.resolve("epg_index_cache.json")
            val epgObj = org.json.JSONObject()
            val epgChArr = org.json.JSONArray()
            for (c in epgChannels) {
                val o = org.json.JSONObject()
                o.put("id", c.id)
                o.put("names", org.json.JSONArray(c.names))
                if (c.icon != null) o.put("icon", c.icon)
                epgChArr.put(o)
            }
            epgObj.put("channels", epgChArr)

            val prgArr = org.json.JSONArray()
            for (p in programmes) {
                val o = org.json.JSONObject()
                o.put("c", p.channelId)
                o.put("s", p.startMs)
                o.put("e", p.stopMs)
                o.put("t", p.title)
                if (p.desc != null) o.put("d", p.desc)
                prgArr.put(o)
            }
            epgObj.put("programmes", prgArr)
            epgFile.writeText(epgObj.toString())
        }
    }

    private fun loadCache(): Pair<List<Channel>, EpgIndex>? {
        return runCatching {
            val chFile = context.cacheDir.resolve("channels_cache.json")
            val epgFile = context.cacheDir.resolve("epg_index_cache.json")
            if (!chFile.exists() || !epgFile.exists()) return null
            val age = System.currentTimeMillis() - chFile.lastModified()
            if (age > CACHE_MAX_AGE_MS) return null

            val chArr = org.json.JSONArray(chFile.readText())
            val channels = ArrayList<Channel>(chArr.length())
            for (i in 0 until chArr.length()) {
                val o = chArr.getJSONObject(i)
                channels.add(
                    Channel(
                        key = o.getString("key"),
                        name = o.getString("name"),
                        rawName = o.getString("rawName"),
                        url = o.getString("url"),
                        logo = o.optString("logo").takeIf { it.isNotEmpty() },
                        category = com.michde.italyitv.data.model.Category.valueOf(o.getString("category")),
                        tvgId = o.optString("tvgId").takeIf { it.isNotEmpty() },
                        userAgent = o.optString("userAgent").takeIf { it.isNotEmpty() },
                        referrer = o.optString("referrer").takeIf { it.isNotEmpty() },
                        needsResolve = o.optBoolean("needsResolve"),
                    )
                )
            }

            val cachedKeys = channels.map { it.key }.toSet()
            val curatedKeys = InjectedChannels.list.map { it.key }.toSet()
            if (!cachedKeys.containsAll(curatedKeys)) {
                // Curated channels have changed or been added: discard cache to force redownload
                return null
            }

            val epgObj = org.json.JSONObject(epgFile.readText())
            val epgChArr = epgObj.getJSONArray("channels")
            val epgChannels = ArrayList<com.michde.italyitv.data.parser.EpgChannel>(epgChArr.length())
            for (i in 0 until epgChArr.length()) {
                val o = epgChArr.getJSONObject(i)
                val namesArr = o.getJSONArray("names")
                val names = (0 until namesArr.length()).map { namesArr.getString(it) }
                epgChannels.add(
                    com.michde.italyitv.data.parser.EpgChannel(
                        id = o.getString("id"),
                        names = names,
                        icon = o.optString("icon").takeIf { it.isNotEmpty() },
                    )
                )
            }

            val prgArr = epgObj.getJSONArray("programmes")
            val programmes = ArrayList<com.michde.italyitv.data.model.Programme>(prgArr.length())
            for (i in 0 until prgArr.length()) {
                val o = prgArr.getJSONObject(i)
                programmes.add(
                    com.michde.italyitv.data.model.Programme(
                        channelId = o.getString("c"),
                        startMs = o.getLong("s"),
                        stopMs = o.getLong("e"),
                        title = o.getString("t"),
                        desc = o.optString("d").takeIf { it.isNotEmpty() },
                    )
                )
            }

            Pair(channels, EpgIndex(epgChannels, programmes))
        }.getOrNull()
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

    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ResolvedStream>>()

    /**
     * Resolve a play handle / dlive url to a real stream plus the headers its CDN
     * demands on every request. Always returns something playable-ish; callers
     * should still be ready for a 403 and retry.
     */
    suspend fun resolveStream(channel: Channel, forceFresh: Boolean = false): ResolvedStream = withContext(Dispatchers.IO) {
        val fallbackRef = channel.referrer?.takeIf { it.isNotBlank() }
        if (!forceFresh) {
            val cached = streamCache[channel.key]
            if (cached != null && (System.currentTimeMillis() - cached.first < 10 * 60 * 1000L)) {
                return@withContext cached.second
            }
        }
        val resolved = when {
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
        streamCache[channel.key] = Pair(System.currentTimeMillis(), resolved)
        resolved
    }

    fun invalidateStreamCache(channelKey: String) {
        streamCache.remove(channelKey)
    }
}
