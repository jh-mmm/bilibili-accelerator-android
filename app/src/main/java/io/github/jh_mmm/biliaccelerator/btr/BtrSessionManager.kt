package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val permanentBans = ConcurrentHashMap.newKeySet<String>()
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

    private fun judge() {
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
        permanentBans.clear()
        for ((blamedKey, strikeCount) in strikes) {
            if (strikeCount >= 2) {
                permanentBans.add(blamedKey)
            }
        }
    }

    fun recordSuccess(url: String, host: String) {
        val address = addressOf(url)
        var changed = false
        if (url.isNotBlank()) {
            urlBlockedUntil.remove(url)
            urlFailures.remove(url)
        }
        if (address.isNotBlank() && goodAddresses.add(address)) {
            changed = true
        }
        if (host.isNotBlank()) {
            hostBlockedUntil.remove(host)
            hostFailures.remove(host)
            if (goodHosts.add(host)) {
                changed = true
            }
        }
        if (changed) {
            judge()
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
                    emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                    judge()
                }
                false
            }
            code == 412 || code == 429 -> {
                if (host.isNotBlank()) {
                    val ttl = if (code == 429) 45_000L else 30_000L
                    hostBlockedUntil[host] = now + ttl
                    if (receivedBytes <= 0L && address.isNotBlank()) {
                        val key = "$host\n$address\n"
                        emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                        judge()
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
                        emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                        judge()
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
                emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
                judge()
            }
        }
    }

    fun isPermanentlyBanned(url: String, host: String): Boolean {
        val address = addressOf(url)
        return permanentBans.contains("node:$host") ||
                permanentBans.contains("address:$address") ||
                permanentBans.contains("pair:$host $address")
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
    @Volatile var concurrencyController: Any? = null
)

object BtrSessionManager {

    private const val MAX_SESSIONS = 50
    private const val SESSION_TTL_MS = 30 * 60 * 1000L // 30 分钟无访问自动清理

    private val sessions = ConcurrentHashMap<String, BtrMediaSession>()
    private val builderSessionMap = Collections.synchronizedMap(WeakHashMap<Any, String>())

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

        val pool: BtrLiveHostPool? = if (isLive) {
            val livePool = BtrLiveHostPool()
            val primaryHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cleanPrimaryLiveUrl)?.host).lowercase()
            if (primaryHost.isNotBlank() && !BtrLiveCore.isP2pUrl(cleanPrimaryLiveUrl)) {
                livePool.add(primaryHost, proven = true)
            }
            // 仅对 fMP4 HLS 直播注入 KNOWN_FMP4_HOSTS；FLV 直播节点鉴权互不兼容，严禁混入（否则 403）
            if (!isFlvLive) {
                for (knownHost in BtrLiveCore.KNOWN_FMP4_HOSTS) {
                    if (knownHost != primaryHost) {
                        livePool.add(knownHost, proven = false)
                    }
                }
            }
            // 注入备用节点中包含的候选官方直播域名
            for (backup in validBackups) {
                val unwrappedBackup = BtrLiveCore.unwrapProxyUrl(backup).ifBlank { backup }
                val bHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(unwrappedBackup)?.host).lowercase()
                if (bHost.isNotBlank() && !BtrLiveCore.isP2pUrl(unwrappedBackup)) {
                    livePool.add(bHost, proven = false)
                }
            }
            // 注入用户自定义 CDN 池
            if (config != null) {
                for (custom in config.customCdnPool) {
                    val cHost = BiliAcceleratorCore.cleanHost(custom).lowercase()
                    if (cHost.isNotBlank() && !BtrLiveCore.isP2pUrl(cHost)) {
                        livePool.add(cHost, proven = false)
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
            val primaryHostLower = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(primaryUrl)?.host).lowercase()
            val isPrimaryBstar = primaryHostLower.endsWith(".bstarstatic.com")
            val shouldSynthesize = !isPrimaryRelay && !isPrimaryBstar && (
                config != null || isolated.none { !BiliAcceleratorCore.isAkamaiHost(it) }
            )
            if (shouldSynthesize) {
                val donor = listOf(primaryUrl).plus(isolated).firstOrNull { candidate ->
                    val ch = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(candidate)?.host).lowercase()
                    !BiliAcceleratorCore.isRelayUrl(candidate) &&
                        !BiliAcceleratorCore.isAkamaiHost(candidate) &&
                        !BiliAcceleratorCore.hasDrmSignal(candidate) &&
                        !ch.endsWith(".bstarstatic.com")
                } ?: primaryUrl
                synthesizedList = synthesizeCompatibleBackups(donor, config, isEpisode = isEpisode)
                (isolated + synthesizedList).distinct().filter { it != primaryUrl }
            } else {
                isolated
            }
        }

        val deadlineSec = BiliAcceleratorCore.extractUrlDeadlineSeconds(cleanPrimaryLiveUrl)
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
            urlDeadlineEpochSec = deadlineSec
        )
        sessions[id] = session
        latestSessionId = id
        return id
    }

    fun remove(sessionId: String) {
        val removed = sessions.remove(sessionId)
        if (removed != null) {
            onSessionEvicted(sessionId, removed)
        }
    }

    fun refreshSignatureIfSamePath(primaryUrl: String, backupUrls: List<String>) {
        val newDeadline = BiliAcceleratorCore.extractUrlDeadlineSeconds(primaryUrl)
        if (newDeadline <= 0L) return
        val newPath = extractNormalizedPathAndKey(primaryUrl).first
        if (newPath.isBlank()) return
        for (session in sessions.values) {
            if (session.isLive) continue
            val existingPath = extractNormalizedPathAndKey(session.primaryUrl).first
            if (existingPath == newPath && newDeadline > session.urlDeadlineEpochSec) {
                session.primaryUrl = primaryUrl
                session.urlDeadlineEpochSec = newDeadline
                val donor = listOf(primaryUrl).plus(backupUrls).firstOrNull { !BiliAcceleratorCore.isRelayUrl(it) && !BiliAcceleratorCore.isAkamaiHost(it) } ?: primaryUrl
                if (session.config != null) {
                    session.synthesizedMirrors = synthesizeCompatibleBackups(donor, session.config, isEpisode = session.isEpisode)
                }
                updateBackups(session.sessionId, backupUrls)
            }
        }
    }

    private fun onSessionEvicted(sessionId: String, session: BtrMediaSession) {
        runCatching { BtrChunkEngine.cancelSessionCalls(sessionId) }
        if (session.isLive) {
            runCatching { BtrLiveEngine.onSessionEvicted(session) }
        }
    }

    fun bindBuilderSession(builder: Any, sessionId: String) {
        builderSessionMap[builder] = sessionId
    }

    fun getSessionForBuilder(builder: Any): String? {
        return builderSessionMap[builder]
    }

    fun get(sessionId: String): BtrMediaSession? {
        val s = sessions[sessionId]
        s?.lastAccessed?.set(System.currentTimeMillis())
        return s
    }

    fun updateBackups(sessionId: String, backups: List<String>) {
        val current = sessions[sessionId] ?: return
        val validBackups = backups.filter { it.isNotBlank() && it != current.primaryUrl }.distinct()
        val isPrimaryRelay = BiliAcceleratorCore.isRelayUrl(current.primaryUrl)
        val isolatedBackups = if (isPrimaryRelay) {
            validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
        } else {
            validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) }
        }
        if (isolatedBackups.isNotEmpty()) {
            current.backupUrls = if (current.config != null && !current.isLive && !isPrimaryRelay) {
                val preservedAkamai = current.backupUrls.filter { BiliAcceleratorCore.isAkamaiHost(it) }
                (isolatedBackups + preservedAkamai + current.synthesizedMirrors)
                    .distinct()
                    .filter { it != current.primaryUrl }
            } else {
                isolatedBackups
            }
        }

        // 若为直播会话，同时同步至直播节点池
        current.liveHostPool?.let { pool ->
            for (b in isolatedBackups) {
                val unwrappedBackup = BtrLiveCore.unwrapProxyUrl(b).ifBlank { b }
                val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(unwrappedBackup)?.host).lowercase()
                if (host.isNotBlank() && !BtrLiveCore.isP2pUrl(unwrappedBackup)) {
                    pool.add(host, proven = false)
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
        // 中继地址、DRM 加密流与 bstarstatic.com 区域专属流严禁合成普通 UPOS 镜像
        if (BiliAcceleratorCore.isRelayUrl(primaryUrl) ||
            path.startsWith("/v1/resource/") ||
            BiliAcceleratorCore.hasDrmSignal(primaryUrl) ||
            host.endsWith(".bstarstatic.com")
        ) {
            return emptyList()
        }

        val isAkamai = BiliAcceleratorCore.isAkamaiHost(host)
        val isHkRegional = host.contains("cn-hk-eq")
        val isCustom = config != null && config.customCdnPool.isNotEmpty()

        val candidateList = when {
            isCustom -> BiliAcceleratorCore.getEffectiveCdnPool(config!!)
            isHkRegional -> BiliAcceleratorCore.HK_REGIONAL_CANDIDATE_POOL + BiliAcceleratorCore.OVERSEAS_CANDIDATE_POOL
            host.contains("ov.bilivideo.com") || isAkamai || BiliAcceleratorCore.isOverseasEnvironment() ->
                BiliAcceleratorCore.OVERSEAS_CANDIDATE_POOL
            else -> BiliAcceleratorCore.MAINLAND_CANDIDATE_POOL
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
