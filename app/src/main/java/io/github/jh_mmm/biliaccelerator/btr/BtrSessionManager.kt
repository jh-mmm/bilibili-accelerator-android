package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRefreshCallback
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

interface PlayerConcurrencySink {
    fun onStall(nowMs: Long = System.currentTimeMillis())
    fun onBufferHunger(bufferedBytes: Long = 0L, nowMs: Long = System.currentTimeMillis())
    fun onSlow(nowMs: Long = System.currentTimeMillis())
}

/**
 * 会话级拦截封禁名单（对齐 BTR cdn-resolver.js createBanList）：
 * - 基于 goodNodes 与 goodAddresses 交叉归因的三元责任判定 (node / address / pair)，
 *   避免冷启动首次 403/410 在未知过错方时误杀无辜节点或全池清空；
 * - 叠加单 URL / 单主机短时指数退避 (blockedUntil)，兼顾快速隔离与可恢复重试。
 */
class SessionBanList {
    private val goodHosts = ConcurrentHashMap.newKeySet<String>()
    private val goodAddresses = ConcurrentHashMap.newKeySet<String>()
    private val emptyReplies = ConcurrentHashMap<String, Int>()
    @Volatile
    private var permanentBans: Set<String> = emptySet()
    private val judgeLock = Any()
    private val urlBlockedUntil = ConcurrentHashMap<String, Long>()
    private val urlFailures = ConcurrentHashMap<String, Int>()
    private val hostBlockedUntil = ConcurrentHashMap<String, Long>()
    private val hostFailures = ConcurrentHashMap<String, Int>()

    private fun addressOf(url: String): String {
        if (url.isBlank()) return ""
        return runCatching {
            val uri = java.net.URI(url)
            val path = uri.rawPath.orEmpty()
            val q = uri.rawQuery
            if (!q.isNullOrEmpty()) "$path?$q" else path
        }.getOrElse { url }
    }

    private fun judgeLocked() {
        val strikes = HashMap<String, Int>()
        for ((key, count) in emptyReplies) {
            val parts = key.split('\n')
            if (parts.size < 3) continue
            val node = parts[0]
            val address = parts[1]
            val refused = parts[2].isNotEmpty()
            val blamed = when {
                !refused -> "node:$node"
                goodHosts.contains(node) -> if (goodAddresses.contains(address)) "pair:$node $address" else "address:$address"
                goodAddresses.contains(address) -> "node:$node"
                else -> ""
            }
            if (blamed.isNotEmpty()) {
                strikes[blamed] = (strikes[blamed] ?: 0) + count
            }
        }
        val nextBans = HashSet<String>()
        for ((blamedKey, strikeCount) in strikes) {
            if (strikeCount >= 2) {
                nextBans.add(blamedKey)
            }
        }
        permanentBans = nextBans
    }

    fun recordSuccess(url: String, host: String) {
        val address = addressOf(url)
        if (url.isNotBlank()) {
            urlBlockedUntil.remove(url)
            urlFailures.remove(url)
        }
        if (host.isNotBlank()) {
            hostBlockedUntil.remove(host)
            hostFailures.remove(host)
        }
        synchronized(judgeLock) {
            var changed = false
            if (address.isNotBlank() && goodAddresses.add(address)) {
                changed = true
            }
            if (host.isNotBlank() && goodHosts.add(host)) {
                changed = true
            }
            if (changed) {
                judgeLocked()
            }
        }
    }

    /**
     * 记录 HTTP 状态码故障。
     * @return true 表示属于节点限流或服务端故障，可额外施加全局短时冷却；false 表示仅当前地址或单会话鉴权隔离。
     */
    fun recordHttpFailure(
        url: String,
        host: String,
        code: Int,
        now: Long = System.currentTimeMillis(),
        receivedBytes: Long = 0L
    ): Boolean {
        val address = addressOf(url)
        return when {
            code == 403 || code == 404 || code == 410 -> {
                if (url.isNotBlank()) {
                    val f = (urlFailures[url] ?: 0) + 1
                    urlFailures[url] = f
                    val backoffMs = minOf(60_000L, 3_000L * (1 shl minOf(f - 1, 4)))
                    urlBlockedUntil[url] = now + backoffMs
                }
                if (receivedBytes <= 0L && host.isNotBlank() && address.isNotBlank()) {
                    val key = "$host\n$address\nrefused"
                    synchronized(judgeLock) {
                        emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                        judgeLocked()
                    }
                }
                false
            }
            code == 412 || code == 429 -> {
                if (host.isNotBlank()) {
                    val ttl = if (code == 429) 45_000L else 30_000L
                    hostBlockedUntil[host] = now + ttl
                    if (receivedBytes <= 0L && address.isNotBlank()) {
                        val key = "$host\n$address\n"
                        synchronized(judgeLock) {
                            emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                            judgeLocked()
                        }
                    }
                }
                true
            }
            else -> {
                if (host.isNotBlank()) {
                    val f = (hostFailures[host] ?: 0) + 1
                    hostFailures[host] = f
                    val backoffMs = minOf(30_000L, 3_000L * (1 shl minOf(f - 1, 3)))
                    hostBlockedUntil[host] = now + backoffMs
                    if (receivedBytes <= 0L && address.isNotBlank()) {
                        val key = "$host\n$address\n"
                        synchronized(judgeLock) {
                            emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                            judgeLocked()
                        }
                    }
                }
                true
            }
        }
    }

    fun recordTransportFailure(
        host: String,
        durationMs: Long = 15_000L,
        now: Long = System.currentTimeMillis(),
        url: String = "",
        receivedBytes: Long = 0L
    ) {
        if (host.isNotBlank()) {
            val f = (hostFailures[host] ?: 0) + 1
            hostFailures[host] = f
            val backoffMs = maxOf(durationMs, minOf(60_000L, 3_000L * (1 shl minOf(f - 1, 4))))
            hostBlockedUntil[host] = now + backoffMs
            if (receivedBytes <= 0L && url.isNotBlank()) {
                val key = "$host\n${addressOf(url)}\n"
                synchronized(judgeLock) {
                    emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                    judgeLocked()
                }
            }
        }
    }

    fun isPermanentlyBanned(url: String, host: String): Boolean {
        val address = addressOf(url)
        val bans = permanentBans
        return bans.contains("node:$host") ||
                bans.contains("address:$address") ||
                bans.contains("pair:$host $address")
    }

    fun isBanned(url: String, host: String, now: Long = System.currentTimeMillis()): Boolean {
        if (isPermanentlyBanned(url, host)) return true
        val urlUntil = urlBlockedUntil[url]
        if (urlUntil != null && urlUntil > now) return true
        val hostUntil = hostBlockedUntil[host]
        if (hostUntil != null && hostUntil > now) return true
        return false
    }

    fun isHostKnownGood(host: String): Boolean = goodHosts.contains(host)
}

data class BtrMediaSession(
    val sessionId: String,
    @Volatile var primaryUrl: String,
    @Volatile var backupUrls: List<String>,
    @Volatile var userAgent: String?,
    @Volatile var referer: String = "https://www.bilibili.com/",
    val isAudio: Boolean = false,
    val isEpisode: Boolean = false,
    val isLive: Boolean = false,
    @Volatile var liveHostPool: BtrLiveHostPool? = null,
    val lastAccessed: AtomicLong = AtomicLong(System.currentTimeMillis()),
    @Volatile var totalLength: Long = -1L,
    @Volatile var contentType: String? = null,
    val globalChunkIndex: AtomicLong = AtomicLong(0L),
    val banList: SessionBanList = SessionBanList(),
    @Volatile var liveMapUrl: String? = null,
    val liveLastNum: AtomicLong = AtomicLong(0L),
    val liveSpeculativeMisses: AtomicInteger = AtomicInteger(0),
    val config: AcceleratorConfig? = null,
    @Volatile var synthesizedMirrors: List<String> = emptyList(),
    @Volatile var urlDeadlineEpochSec: Long = 0L,
    @Volatile var concurrencyController: Any? = null,
    @Volatile var proactiveRefreshTask: ScheduledFuture<*>? = null,
    val refreshInFlight: AtomicBoolean = AtomicBoolean(false),
    @Volatile var nextRefreshAllowedAtMs: Long = 0L,
    val refreshConsecutiveFailures: AtomicInteger = AtomicInteger(0),
    @Volatile var refreshProvider: PlayinfoRefreshCallback? = null,
    val warmEnd: AtomicLong = AtomicLong(0L),
    val unmeasuredTrialWaited: AtomicInteger = AtomicInteger(0),
    val urlLock: Any = Any(),
    val liveHostUrls: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    @Volatile var liveCustomPoolOnly: Boolean = false,
    val activeStreamSeq: AtomicLong = AtomicLong(0L),
    val lastAbortedSeekHint: AtomicBoolean = AtomicBoolean(false),
    val lastObservedStart: AtomicLong = AtomicLong(-1L)
) {
    fun currentCandidateUrls(): List<String> = synchronized(urlLock) {
        (listOf(primaryUrl) + backupUrls).distinct()
    }
}

object BtrSessionManager {

    private const val TAG = "BiliAccelerator-Session"
    private const val MAX_SESSIONS = 50
    private const val SESSION_TTL_MS = 30 * 60 * 1000L // 30 分钟无访问自动清理
    internal const val PROACTIVE_REFRESH_LEAD_SEC = 120L
    internal const val REFRESH_BASE_COOLDOWN_MS = 45_000L
    internal const val REFRESH_MAX_COOLDOWN_MS = 300_000L

    private val sessions = ConcurrentHashMap<String, BtrMediaSession>()
    private val builderSessionMap = Collections.synchronizedMap(WeakHashMap<Any, String>())

    private val refreshScheduler = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "BTR-SigRefresh").apply { isDaemon = true }
    }.apply {
        setKeepAliveTime(45L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }

    @Volatile
    var defaultRefreshProvider: ((BtrMediaSession) -> Boolean)? = null

    @Volatile
    private var latestSessionId: String? = null

    fun register(
        primaryUrl: String,
        backupUrls: List<String> = emptyList(),
        userAgent: String? = null,
        isAudio: Boolean = false,
        isEpisode: Boolean = false,
        isLive: Boolean = false,
        config: AcceleratorConfig? = null
    ): String {
        cleanupStaleSessions()
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val validBackups = backupUrls.filter { it.isNotBlank() && it != primaryUrl }.distinct()
        val isPrimaryRelay = BiliAcceleratorCore.isRelayUrl(primaryUrl)

        val referer = if (isEpisode) {
            "https://www.bilibili.com/bangumi/play/"
        } else if (isLive) {
            "https://live.bilibili.com/"
        } else {
            "https://www.bilibili.com/"
        }

        val cleanPrimaryLiveUrl = if (isLive) BtrLiveCore.unwrapProxyUrl(primaryUrl).ifBlank { primaryUrl } else primaryUrl
        val isFlvLive = isLive && cleanPrimaryLiveUrl.substringBefore('?').substringBefore('#').endsWith(".flv", ignoreCase = true)
        val isConfirmedFmp4LiveSegment = isLive && BtrLiveCore.isLiveSegmentUrl(cleanPrimaryLiveUrl)
        val liveHostUrlsMap = ConcurrentHashMap<String, String>()
        var isLiveCustomOnly = false

        val pool: BtrLiveHostPool? = if (isLive) {
            val livePool = BtrLiveHostPool()
            val primaryHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cleanPrimaryLiveUrl)?.host).lowercase()
            if (primaryHost.isNotBlank() && !BtrLiveCore.isP2pUrl(cleanPrimaryLiveUrl)) {
                livePool.add(primaryHost, proven = true)
                liveHostUrlsMap[primaryHost] = cleanPrimaryLiveUrl
            }
            // 注入备用节点中包含的候选官方直播域名（保留各线路完整 URL 含独立签名/extra 参数）
            for (backup in validBackups) {
                val unwrappedBackup = BtrLiveCore.unwrapProxyUrl(backup).ifBlank { backup }
                val bHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(unwrappedBackup)?.host).lowercase()
                if (bHost.isNotBlank() && !BtrLiveCore.isP2pUrl(unwrappedBackup)) {
                    livePool.add(bHost, proven = false)
                    liveHostUrlsMap.putIfAbsent(bHost, unwrappedBackup)
                }
            }
            // 对齐 BTR live-hook.js:124：配置自定义 CDN 池时仅使用自定义节点，不再混入默认 KNOWN_FMP4_HOSTS；
            // 非自定义模式下仅对已确认的 .m4s 分片注入 KNOWN_FMP4_HOSTS，.m3u8 需在 pipePlaylist 确认 #EXT-X-MAP 后再注入，避免污染 HLS-TS
            val validCustomLiveHosts = config?.customCdnPool
                ?.map { BiliAcceleratorCore.cleanHost(it).lowercase().substringBefore(':') }
                ?.filter { it.isNotBlank() && BiliAcceleratorCore.isValidCustomCdnHost(it) && !BtrLiveCore.isP2pUrl(it) }
                ?.distinct()
                .orEmpty()
            if (validCustomLiveHosts.isNotEmpty()) {
                isLiveCustomOnly = true
                for (cHost in validCustomLiveHosts) {
                    livePool.add(cHost, proven = false)
                }
            } else if (!isFlvLive && isConfirmedFmp4LiveSegment) {
                for (knownHost in BtrLiveCore.KNOWN_FMP4_HOSTS) {
                    if (knownHost != primaryHost) {
                        livePool.add(knownHost, proven = false)
                    }
                }
            }
            livePool
        } else {
            null
        }

        // 若宿主未传入完整多源池，合成兼容的跨区 UPOS 镜像池（对齐 realzza V5 与 BTR：跨区共同竞速）
        var synthesizedList: List<String> = emptyList()
        val finalBackups = if (isLive) {
            validBackups
        } else {
            val isolated = if (isPrimaryRelay) {
                validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
            } else {
                validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) }
            }
            val cleanIsolated = if (isPrimaryRelay) {
                isolated
            } else {
                isolated.filter { !isFallbackOrP2pUrl(it) && !BiliAcceleratorCore.isAkamaiHost(it) }
            }
            val akamaiOrP2pFallbacks = if (isPrimaryRelay) {
                emptyList()
            } else {
                isolated.filter { BiliAcceleratorCore.isAkamaiHost(it) } +
                    isolated.filter { isFallbackOrP2pUrl(it) && !BiliAcceleratorCore.isAkamaiHost(it) }
            }
            // 非中继会话不主动丢弃原列表中的中继/代理/PCDN备选，而是降级置于末尾作 403 兜底（绝不作为 Donor 合成普通 UPOS）
            val relayFallbacks = if (isPrimaryRelay) {
                emptyList()
            } else {
                validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
            }
            val primaryHostLower = hostKeyOf(primaryUrl)
            val isPrimaryBstar = primaryHostLower.endsWith(".bstarstatic.com")
            val shouldSynthesize = !isPrimaryRelay && !isPrimaryBstar && (
                config != null || isolated.none { !BiliAcceleratorCore.isAkamaiHost(it) }
            )
            if (shouldSynthesize) {
                val donor = (cleanIsolated + listOf(primaryUrl)).firstOrNull { candidate ->
                    val ch = hostKeyOf(candidate)
                    !candidate.contains("hdnts=", ignoreCase = true) &&
                        !BiliAcceleratorCore.isRelayUrl(candidate) &&
                        !BiliAcceleratorCore.isAkamaiHost(candidate) &&
                        !isFallbackOrP2pUrl(candidate) &&
                        !BiliAcceleratorCore.hasDrmSignal(candidate) &&
                        !ch.endsWith(".bstarstatic.com")
                } ?: primaryUrl
                synthesizedList = synthesizeCompatibleBackups(donor, config, isEpisode = isEpisode)
                deduplicateUrlsByHost(
                    cleanIsolated + synthesizedList + akamaiOrP2pFallbacks + relayFallbacks,
                    excludeHost = primaryHostLower
                )
            } else {
                deduplicateUrlsByHost(
                    cleanIsolated + akamaiOrP2pFallbacks + relayFallbacks,
                    excludeHost = primaryHostLower
                )
            }
        }

        val deadlineSec = extractDeadlineSeconds(cleanPrimaryLiveUrl)
        val refreshCb = if (!isLive) PlayinfoRegistry.findRefreshCallback(cleanPrimaryLiveUrl) else null
        val session = BtrMediaSession(
            sessionId = id,
            primaryUrl = cleanPrimaryLiveUrl,
            backupUrls = finalBackups,
            userAgent = userAgent,
            referer = referer,
            isAudio = isAudio,
            isEpisode = isEpisode,
            isLive = isLive,
            liveHostPool = pool,
            config = config,
            synthesizedMirrors = synthesizedList,
            urlDeadlineEpochSec = deadlineSec,
            refreshProvider = refreshCb,
            liveHostUrls = liveHostUrlsMap,
            liveCustomPoolOnly = isLiveCustomOnly
        )
        sessions[id] = session
        latestSessionId = id
        if (!isLive && deadlineSec > 0L) {
            scheduleProactiveRefresh(session)
        }
        return id
    }

    fun remove(sessionId: String) {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            onSessionEvicted(sessionId, removed)
        }
    }

    fun attachRefreshProvider(sessionId: String, provider: PlayinfoRefreshCallback) {
        val session = sessions[sessionId] ?: return
        session.refreshProvider = provider
        if (!session.isLive && session.urlDeadlineEpochSec > 0L) {
            scheduleProactiveRefresh(session)
        }
    }

    fun scheduleProactiveRefresh(session: BtrMediaSession, nowMs: Long = System.currentTimeMillis()) {
        session.proactiveRefreshTask?.cancel(false)
        session.proactiveRefreshTask = null
        val deadlineSec = session.urlDeadlineEpochSec
        if (session.isLive || deadlineSec <= 0L) return
        if (sessions[session.sessionId] !== session) return

        val triggerAtMs = (deadlineSec - PROACTIVE_REFRESH_LEAD_SEC) * 1000L
        val delayMs = maxOf(0L, triggerAtMs - nowMs, session.nextRefreshAllowedAtMs - nowMs)
        try {
            session.proactiveRefreshTask = refreshScheduler.schedule(
                {
                    if (sessions[session.sessionId] === session) {
                        runCatching { maybeTriggerProactiveRefresh(session) }
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS
            )
        } catch (_: Throwable) {
        }
    }

    fun maybeTriggerProactiveRefresh(
        session: BtrMediaSession,
        forceOnAuthFailure: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (session.isLive) return false
        val currentDeadlineSec = session.urlDeadlineEpochSec

        val cachedFresher = PlayinfoRegistry.findFresherTrack(session.primaryUrl, currentDeadlineSec)
        if (cachedFresher != null && cachedFresher.deadlineEpochSec > currentDeadlineSec) {
            refreshSignatureIfSamePath(cachedFresher.primaryUrl, cachedFresher.backupUrls)
            if (session.urlDeadlineEpochSec > currentDeadlineSec) {
                return true
            }
        }

        val nowSec = nowMs / 1000L
        val withinLeadWindow = currentDeadlineSec > 0L && nowSec >= (currentDeadlineSec - PROACTIVE_REFRESH_LEAD_SEC)
        if (!forceOnAuthFailure && !withinLeadWindow) return false
        if (nowMs < session.nextRefreshAllowedAtMs) return false

        val provider = session.refreshProvider ?: PlayinfoRegistry.findRefreshCallback(session.primaryUrl)
        val fallbackProvider = defaultRefreshProvider
        if (provider == null && fallbackProvider == null) return false

        if (!session.refreshInFlight.compareAndSet(false, true)) return false
        return try {
            val invokedOk = try {
                if (provider != null) provider.refresh() else fallbackProvider!!.invoke(session)
            } catch (t: Throwable) {
                Log.w(TAG, "Proactive signature refresh failed: ${t.message}")
                false
            }
            val updatedTrack = PlayinfoRegistry.findFresherTrack(session.primaryUrl, currentDeadlineSec)
            if (updatedTrack != null && updatedTrack.deadlineEpochSec > currentDeadlineSec) {
                refreshSignatureIfSamePath(updatedTrack.primaryUrl, updatedTrack.backupUrls)
            }
            val advanced = session.urlDeadlineEpochSec > currentDeadlineSec || (forceOnAuthFailure && invokedOk)
            if (advanced) {
                session.refreshConsecutiveFailures.set(0)
                session.nextRefreshAllowedAtMs = 0L
                true
            } else {
                val failures = session.refreshConsecutiveFailures.incrementAndGet()
                val cooldownMs = minOf(REFRESH_MAX_COOLDOWN_MS, REFRESH_BASE_COOLDOWN_MS * (1L shl minOf(failures - 1, 4)))
                session.nextRefreshAllowedAtMs = nowMs + cooldownMs
                scheduleProactiveRefresh(session, nowMs)
                false
            }
        } finally {
            session.refreshInFlight.set(false)
        }
    }

    fun notifyPlayerStall(sessionId: String? = null, nowMs: Long = System.currentTimeMillis()) {
        if (!sessionId.isNullOrBlank()) {
            (sessions[sessionId]?.concurrencyController as? PlayerConcurrencySink)?.onStall(nowMs)
            return
        }
        val cutoff = nowMs - 60_000L
        for (s in sessions.values) {
            if (!s.isLive && s.lastAccessed.get() >= cutoff) {
                (s.concurrencyController as? PlayerConcurrencySink)?.onStall(nowMs)
            }
        }
    }

    fun notifyPlayerBufferHunger(
        sessionId: String? = null,
        bufferedBytes: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!sessionId.isNullOrBlank()) {
            (sessions[sessionId]?.concurrencyController as? PlayerConcurrencySink)?.onBufferHunger(bufferedBytes, nowMs)
            return
        }
        val cutoff = nowMs - 60_000L
        for (s in sessions.values) {
            if (!s.isLive && s.lastAccessed.get() >= cutoff) {
                (s.concurrencyController as? PlayerConcurrencySink)?.onBufferHunger(bufferedBytes, nowMs)
            }
        }
    }

    private fun hostKeyOf(url: String): String {
        val parsed = BiliAcceleratorCore.parseUri(url) ?: return ""
        val host = BiliAcceleratorCore.cleanHost(parsed.host)
            .lowercase()
            .substringBefore(':')
        if (host.isBlank()) return ""
        val port = parsed.port
        return if (port > 0 && port != 80 && port != 443) "$host:$port" else host
    }

    private fun deduplicateUrlsByHost(urls: List<String>, excludeHost: String = ""): List<String> {
        if (urls.isEmpty()) return emptyList()
        val seenHosts = HashSet<String>()
        if (excludeHost.isNotBlank()) seenHosts.add(excludeHost.lowercase())
        val out = ArrayList<String>(urls.size)
        for (url in urls) {
            if (url.isBlank()) continue
            val host = hostKeyOf(url)
            val dedupKey = host.ifBlank { url }
            if (seenHosts.add(dedupKey)) {
                out.add(url)
            }
        }
        return out
    }

    private fun extractDeadlineSeconds(url: String): Long {
        val direct = BiliAcceleratorCore.extractUrlDeadlineSeconds(url)
        if (direct > 0L) return direct
        val unwrapped = unwrapCandidateUrl(url)
        return if (unwrapped != url) BiliAcceleratorCore.extractUrlDeadlineSeconds(unwrapped) else 0L
    }

    fun refreshSignatureIfSamePath(primaryUrl: String, backupUrls: List<String>) {
        val rawAll = (listOf(primaryUrl) + backupUrls).filter { it.isNotBlank() }.distinct()
        if (rawAll.isEmpty()) return
        val newDeadline = rawAll
            .map { extractDeadlineSeconds(it) }
            .filter { it > 0L }
            .maxOrNull() ?: 0L
        if (newDeadline <= 0L) return
        val newPath = rawAll.firstNotNullOfOrNull {
            extractNormalizedPathAndKey(it).first.takeIf { p -> p.isNotBlank() }
        }.orEmpty()
        if (newPath.isBlank()) return
        val normalizedAll = rawAll.map { BiliAcceleratorCore.unwrapSmtcdnsUrl(it).ifBlank { it } }.distinct()

        for (session in sessions.values) {
            if (session.isLive) continue
            synchronized(session.urlLock) {
                val existingPath = extractNormalizedPathAndKey(session.primaryUrl).first
                if (existingPath == newPath && newDeadline > session.urlDeadlineEpochSec) {
                    val isExistingCleanUpgcxcode = BiliAcceleratorCore.isCleanUposDonor(
                        session.primaryUrl,
                        requireUpgcxcode = true
                    )
                    val isExistingRelay = BiliAcceleratorCore.isRelayUrl(session.primaryUrl)
                    val cleanDonorFromRefresh = normalizedAll.firstOrNull { BiliAcceleratorCore.isCleanUposDonor(it) }
                    val rewrittenPrimary = session.config?.let { cfg ->
                        val candidate = normalizedAll.firstOrNull { !BiliAcceleratorCore.isRelayUrl(it) } ?: primaryUrl
                        val res = BiliAcceleratorCore.rewriteUrl(candidate, cfg)
                        res.finalUrl.takeIf { res.changed && BiliAcceleratorCore.isCleanUposDonor(it) }
                    }
                    val preservedCleanUposPrimary = if (isExistingCleanUpgcxcode && cleanDonorFromRefresh == null && rewrittenPrimary == null) {
                        val existingHost = BiliAcceleratorCore.cleanHost(
                            BiliAcceleratorCore.parseUri(session.primaryUrl)?.host
                        ).substringBefore(':')
                        val freshUpgcxcode = normalizedAll.firstOrNull {
                            !BiliAcceleratorCore.isRelayUrl(it) &&
                                BiliAcceleratorCore.parseUri(it)?.path?.contains("/upgcxcode/") == true
                        }
                        freshUpgcxcode?.let { fresh ->
                            BiliAcceleratorCore.parseUri(fresh)?.replaceAuthority(existingHost)
                        }?.takeIf { BiliAcceleratorCore.isCleanUposDonor(it, requireUpgcxcode = true) }
                    } else {
                        null
                    }

                    val resolvedPrimary = cleanDonorFromRefresh
                        ?: rewrittenPrimary
                        ?: preservedCleanUposPrimary
                        ?: if (isExistingRelay) {
                            primaryUrl
                        } else {
                            normalizedAll.firstOrNull { !BiliAcceleratorCore.isRelayUrl(it) } ?: primaryUrl
                        }

                    session.primaryUrl = resolvedPrimary
                    session.urlDeadlineEpochSec = newDeadline
                    session.refreshConsecutiveFailures.set(0)
                    session.nextRefreshAllowedAtMs = 0L

                    val isPrimaryRelay = BiliAcceleratorCore.isRelayUrl(resolvedPrimary)
                    val donor = (normalizedAll + listOf(resolvedPrimary))
                        .firstOrNull { !it.contains("hdnts=", ignoreCase = true) && BiliAcceleratorCore.isCleanUposDonor(it) }
                        ?: resolvedPrimary
                    if (!isPrimaryRelay && (session.config != null || session.synthesizedMirrors.isNotEmpty())) {
                        session.synthesizedMirrors = synthesizeCompatibleBackups(donor, session.config, isEpisode = session.isEpisode)
                    } else if (isPrimaryRelay) {
                        session.synthesizedMirrors = emptyList()
                    }
                    val validBackups = normalizedAll.filter { it.isNotBlank() && it != resolvedPrimary }.distinct()
                    val isolatedBackups = if (isPrimaryRelay) {
                        rawAll.filter { it.isNotBlank() && it != resolvedPrimary && BiliAcceleratorCore.isRelayUrl(it) }.distinct()
                    } else {
                        validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) }
                    }
                    val primaryHostLower = hostKeyOf(resolvedPrimary)
                    session.backupUrls = if (!isPrimaryRelay && (session.config != null || session.synthesizedMirrors.isNotEmpty())) {
                        deduplicateUrlsByHost(isolatedBackups + session.synthesizedMirrors, excludeHost = primaryHostLower)
                    } else if (isolatedBackups.isNotEmpty()) {
                        deduplicateUrlsByHost(isolatedBackups, excludeHost = primaryHostLower)
                    } else {
                        session.backupUrls.filter { url ->
                            val d = extractDeadlineSeconds(url)
                            d <= 0L || d >= newDeadline
                        }
                    }
                    scheduleProactiveRefresh(session)
                }
            }
        }
    }

    private fun onSessionEvicted(sessionId: String, session: BtrMediaSession) {
        session.proactiveRefreshTask?.cancel(false)
        session.proactiveRefreshTask = null
        runCatching { BtrChunkEngine.cancelSessionCalls(sessionId) }
        if (session.isLive) {
            runCatching { BtrLiveEngine.onSessionEvicted(session) }
        }
    }

    fun bindBuilderSession(builder: Any, sessionId: String) {
        builderSessionMap[builder] = sessionId
    }

    fun unbindBuilderSession(builder: Any) {
        builderSessionMap.remove(builder)
    }

    fun getSessionForBuilder(builder: Any): String? {
        return builderSessionMap[builder]
    }

    fun get(sessionId: String): BtrMediaSession? {
        val s = sessions[sessionId]
        s?.lastAccessed?.set(System.currentTimeMillis())
        return s
    }

    private fun isFallbackOrP2pUrl(url: String): Boolean {
        if (url.isBlank() || BiliAcceleratorCore.isRelayUrl(url) || BiliAcceleratorCore.isAkamaiHost(url)) {
            return false
        }
        val parsed = BiliAcceleratorCore.parseUri(url) ?: return false
        val host = BiliAcceleratorCore.cleanHost(parsed.host).lowercase().substringBefore(':')
        val port = parsed.port
        return BiliAcceleratorCore.isKnownP2pHost(host) ||
            BiliAcceleratorCore.isIpAddress(host) ||
            (port != -1 && port != 80 && port != 443)
    }

    fun updateBackups(sessionId: String, backups: List<String>) {
        val current = sessions[sessionId] ?: return
        synchronized(current.urlLock) {
            val validBackups = backups.filter { it.isNotBlank() && it != current.primaryUrl }.distinct()
            val isPrimaryRelay = BiliAcceleratorCore.isRelayUrl(current.primaryUrl)
            val currentPath = extractNormalizedPathAndKey(current.primaryUrl).first

            // 当初始 URL 为带 /upgcxcode/ 的 MCDN/PCDN（含代理包装）且后续补充到同资产官方 UPOS 备用镜像时，升级主地址并释放全池镜像
            val promotedUposDonor = if (
                !current.isLive &&
                currentPath.contains("/upgcxcode/") &&
                !BiliAcceleratorCore.isCleanUposDonor(current.primaryUrl, requireUpgcxcode = true)
            ) {
                validBackups
                    .map { BiliAcceleratorCore.unwrapSmtcdnsUrl(it).ifBlank { it } }
                    .firstOrNull { backup ->
                        !backup.contains("hdnts=", ignoreCase = true) &&
                            BiliAcceleratorCore.isCleanUposDonor(backup, requireUpgcxcode = true) &&
                            extractNormalizedPathAndKey(backup).first == currentPath
                    }
            } else {
                null
            }
            if (promotedUposDonor != null) {
                val previousPrimary = current.primaryUrl
                val previousSynth = current.synthesizedMirrors.toSet()
                val existingRealNonRelay = current.backupUrls.filter {
                    it !in previousSynth && !BiliAcceleratorCore.isRelayUrl(it)
                }
                val existingRelayAndOrig = buildList {
                    if (!current.isLive && previousPrimary.isNotBlank() && previousPrimary != promotedUposDonor) {
                        add(previousPrimary)
                    }
                    addAll(current.backupUrls.filter { BiliAcceleratorCore.isRelayUrl(it) })
                }

                current.primaryUrl = promotedUposDonor
                val deadlineSec = extractDeadlineSeconds(promotedUposDonor)
                if (deadlineSec > current.urlDeadlineEpochSec) {
                    current.urlDeadlineEpochSec = deadlineSec
                }
                if (current.config != null || current.synthesizedMirrors.isNotEmpty() || isPrimaryRelay) {
                    current.synthesizedMirrors = synthesizeCompatibleBackups(
                        promotedUposDonor,
                        current.config,
                        isEpisode = current.isEpisode
                    )
                }
                val unwrappedIncoming = validBackups.map { BiliAcceleratorCore.unwrapSmtcdnsUrl(it).ifBlank { it } }
                val incomingNonRelay = unwrappedIncoming.filter { !BiliAcceleratorCore.isRelayUrl(it) }
                val incomingRelay = validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
                val cleanBackups = (incomingNonRelay + existingRealNonRelay).filter {
                    !isFallbackOrP2pUrl(it) && !BiliAcceleratorCore.isAkamaiHost(it)
                }
                val akamaiFallbacks = (incomingNonRelay + existingRealNonRelay).filter {
                    BiliAcceleratorCore.isAkamaiHost(it)
                }
                val p2pFallbacks = (incomingNonRelay + existingRealNonRelay).filter {
                    isFallbackOrP2pUrl(it)
                }
                current.backupUrls = deduplicateUrlsByHost(
                    cleanBackups + current.synthesizedMirrors + akamaiFallbacks + p2pFallbacks + incomingRelay + existingRelayAndOrig,
                    excludeHost = hostKeyOf(current.primaryUrl)
                )
                scheduleProactiveRefresh(current)
                return
            }

            val isolatedBackups = if (isPrimaryRelay) {
                validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
            } else {
                validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) }
            }
            val incomingRelayFallbacks = if (!current.isLive && !isPrimaryRelay) {
                validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
            } else {
                emptyList()
            }
            if (isolatedBackups.isNotEmpty() || incomingRelayFallbacks.isNotEmpty()) {
                val primaryHostLower = hostKeyOf(current.primaryUrl)
                val existingSynth = current.synthesizedMirrors.toSet()
                val existingRealNonRelay = if (isPrimaryRelay) {
                    emptyList()
                } else {
                    current.backupUrls.filter { it !in existingSynth && !BiliAcceleratorCore.isRelayUrl(it) }
                }
                val existingRelayFallbacks = if (isPrimaryRelay || !current.isLive) {
                    current.backupUrls.filter { BiliAcceleratorCore.isRelayUrl(it) }
                } else {
                    emptyList()
                }
                val cleanIncoming = if (isPrimaryRelay) {
                    isolatedBackups
                } else {
                    isolatedBackups.filter { !isFallbackOrP2pUrl(it) && !BiliAcceleratorCore.isAkamaiHost(it) }
                }
                val cleanExisting = existingRealNonRelay.filter {
                    !isFallbackOrP2pUrl(it) && !BiliAcceleratorCore.isAkamaiHost(it)
                }
                val incomingAkamai = if (isPrimaryRelay) emptyList() else isolatedBackups.filter { BiliAcceleratorCore.isAkamaiHost(it) }
                val existingAkamai = existingRealNonRelay.filter { BiliAcceleratorCore.isAkamaiHost(it) }
                val incomingP2p = if (isPrimaryRelay) emptyList() else isolatedBackups.filter { isFallbackOrP2pUrl(it) }
                val existingP2p = existingRealNonRelay.filter { isFallbackOrP2pUrl(it) }

                val shouldKeepSynthesized = !current.isLive && !isPrimaryRelay && (
                    current.config != null ||
                        (current.synthesizedMirrors.isNotEmpty() && isolatedBackups.none { !BiliAcceleratorCore.isAkamaiHost(it) })
                    )
                val synthMirrors = if (shouldKeepSynthesized) current.synthesizedMirrors else emptyList()
                current.backupUrls = deduplicateUrlsByHost(
                    cleanIncoming + cleanExisting + synthMirrors +
                        incomingAkamai + existingAkamai + incomingP2p + existingP2p +
                        incomingRelayFallbacks + existingRelayFallbacks,
                    excludeHost = primaryHostLower
                )
            }

            // 若为直播会话，同时同步至直播节点池及逐线路 URL 映射表
            current.liveHostPool?.let { pool ->
                for (b in isolatedBackups) {
                    val unwrappedBackup = BtrLiveCore.unwrapProxyUrl(b).ifBlank { b }
                    val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(unwrappedBackup)?.host).lowercase()
                    if (host.isNotBlank() && !BtrLiveCore.isP2pUrl(unwrappedBackup)) {
                        pool.add(host, proven = false)
                        current.liveHostUrls.putIfAbsent(host, unwrappedBackup)
                    }
                }
            }
        }
    }

    fun updateBackupsForLatest(backups: List<String>) {
        val id = latestSessionId ?: return
        updateBackups(id, backups)
    }

    fun updateBackupsByCandidateUrls(backups: List<String>) {
        if (backups.isEmpty()) return
        val incomingSigs = backups.map { extractNormalizedPathAndKey(it) }
        val incomingPaths = incomingSigs.mapNotNull { it.first.takeIf(String::isNotBlank) }.toSet()
        val incomingKeys = incomingSigs.mapNotNull { it.second.takeIf { key -> key.isNotBlank() && key.contains('.') } }.toSet()
        val incomingIsAudio = backups.any { BiliAcceleratorCore.isAudioTrack(unwrapCandidateUrl(it)) }
        val incomingIsLive = backups.any { BiliAcceleratorCore.isLiveMediaUrl("", unwrapCandidateUrl(it)) }

        val orderedSessions = sessions.values.sortedByDescending { it.lastAccessed.get() }

        // 1. 优先精确路径匹配（涵盖主备任一地址，规避代理外衣或单地址路径偏移）
        if (incomingPaths.isNotEmpty()) {
            val exactPathMatch = orderedSessions.firstOrNull { session ->
                val sessionUrls = listOf(session.primaryUrl) + session.backupUrls
                sessionUrls.any { extractNormalizedPathAndKey(it).first in incomingPaths }
            }
            if (exactPathMatch != null) {
                updateBackups(exactPathMatch.sessionId, backups)
                return
            }
        }

        // 2. 次级回退：同轨道类型 (isAudio/isLive 一致) + 文件名片段关键帧一致，严禁跨视频/跨轨道串台
        if (incomingKeys.isNotEmpty()) {
            val keyMatch = orderedSessions.firstOrNull { session ->
                session.isAudio == incomingIsAudio &&
                    session.isLive == incomingIsLive &&
                    extractNormalizedPathAndKey(session.primaryUrl).second in incomingKeys
            }
            if (keyMatch != null) {
                updateBackups(keyMatch.sessionId, backups)
                return
            }
        }
    }

    private fun unwrapCandidateUrl(rawUrl: String): String {
        val step1 = BtrLiveCore.unwrapProxyUrl(rawUrl).ifBlank { rawUrl }
        val parsed = BiliAcceleratorCore.parseUri(step1) ?: return step1
        val host = parsed.host.lowercase()
        val wrapped = if (host.startsWith("proxy-tf-all-") || parsed.path == "/") {
            parsed.queryParam("url")
        } else null
        return if (!wrapped.isNullOrBlank()) {
            runCatching { URLDecoder.decode(wrapped, "UTF-8") }.getOrDefault(wrapped)
        } else {
            step1
        }
    }

    private fun extractNormalizedPathAndKey(rawUrl: String): Pair<String, String> {
        val unwrapped = unwrapCandidateUrl(rawUrl)
        val path = BiliAcceleratorCore.parseUri(unwrapped)?.path?.trim()?.trimEnd('/')?.lowercase().orEmpty()
        val key = path.substringAfterLast('/')
        return path to key
    }

    /**
     * 安全合成同源通用 UPOS 备用节点（对齐 realzza V5 与 BTR）：
     * - 允许大陆 UPOS 与海外 *ov.bilivideo.com 跨区共同竞速（不硬隔离，由测速决定最优节点）；
     * - 严格排除 bstarstatic.com（区域专属签名与 bilivideo 不互通）；
     * - 港澳台专属专线 cn-hk-eq-* 仅在主地址本身为 cn-hk-eq-* 时合成，严禁混入常规流导致 403。
     */
    fun synthesizeCompatibleBackups(
        primaryUrl: String,
        config: AcceleratorConfig? = null,
        isEpisode: Boolean = false
    ): List<String> {
        val results = mutableListOf<String>()
        val uri = BiliAcceleratorCore.parseUri(primaryUrl) ?: return emptyList()
        val path = uri.path
        if (!path.contains("/upgcxcode/")) return emptyList()

        val host = uri.host.lowercase().substringBefore(':')
        val bareHost = host.removeSurrounding("[", "]")
        // 本地回环地址、中继地址、DRM 加密流、hdnts 绑定签名流与 bstarstatic.com 区域专属流严禁合成普通 UPOS 镜像
        if (bareHost == "127.0.0.1" ||
            bareHost == "localhost" ||
            bareHost == "::1" ||
            bareHost == "0:0:0:0:0:0:0:1" ||
            primaryUrl.contains("hdnts=", ignoreCase = true) ||
            BiliAcceleratorCore.isRelayUrl(primaryUrl) ||
            path.startsWith("/v1/resource/") ||
            BiliAcceleratorCore.hasDrmSignal(primaryUrl) ||
            host.endsWith(".bstarstatic.com")
        ) {
            return emptyList()
        }

        val isHkRegional = host.contains("cn-hk-eq")
        val isCustom = config != null && config.customCdnPool.isNotEmpty()

        val candidateList = when {
            isCustom -> BiliAcceleratorCore.getEffectiveCdnPool(config!!)
            isHkRegional -> BiliAcceleratorCore.HK_REGIONAL_CANDIDATE_POOL + BiliAcceleratorCore.CANDIDATE_POOL
            else -> BiliAcceleratorCore.CANDIDATE_POOL
        }

        val maxBackups = if (config != null) config.btrCdnPoolSize.coerceIn(1, candidateList.size) else minOf(8, candidateList.size)

        for (m in candidateList) {
            val cleanM = BiliAcceleratorCore.cleanHost(m).lowercase().substringBefore(':')
            if (cleanM == host || BiliAcceleratorCore.isAkamaiHost(cleanM) || cleanM.endsWith(".bstarstatic.com")) continue
            if (!isCustom && !isHkRegional && cleanM.contains("cn-hk-eq")) continue

            results.add(uri.replaceAuthority(cleanM))
            if (results.size >= maxBackups) break
        }
        return results
    }

    private fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastAccessed.get() > SESSION_TTL_MS) {
                iterator.remove()
                onSessionEvicted(entry.key, entry.value)
            }
        }
        if (sessions.size >= MAX_SESSIONS) {
            val overflow = sessions.size - MAX_SESSIONS + 1
            val victims = sessions.entries
                .sortedBy { it.value.lastAccessed.get() }
                .take(overflow)
            for (victim in victims) {
                if (sessions.remove(victim.key, victim.value)) {
                    onSessionEvicted(victim.key, victim.value)
                }
            }
        }
    }
}
