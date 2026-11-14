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
 * 会话级拦截封禁名单（对齐 BTR createBanList）：
 * 区分单条 URL 地址鉴权失败 (address: 403/404/410) 与节点级限流/宕机 (node: 412/429/5xx/超时)，
 * 避免某一视频的签名地址被单节点拒收时全局拉黑健康的 CDN 节点，影响其他正常播放。
 */
class SessionBanList {
    private val goodHosts = ConcurrentHashMap.newKeySet<String>()
    private val goodUrls = ConcurrentHashMap.newKeySet<String>()
    private val bannedUrls = ConcurrentHashMap<String, Long>()
    private val bannedHosts = ConcurrentHashMap<String, Long>()

    fun recordSuccess(url: String, host: String) {
        if (url.isNotBlank()) {
            goodUrls.add(url)
            bannedUrls.remove(url)
        }
        if (host.isNotBlank()) {
            goodHosts.add(host)
            bannedHosts.remove(host)
        }
    }

    /**
     * 记录 HTTP 状态码故障。
     * @return true 表示属于节点限流或服务端故障，可额外施加全局短时冷却；false 表示仅当前地址或单会话鉴权隔离。
     */
    fun recordHttpFailure(url: String, host: String, code: Int, now: Long = System.currentTimeMillis()): Boolean {
        return when {
            code == 403 || code == 404 || code == 410 -> {
                if (url.isNotBlank()) {
                    bannedUrls[url] = Long.MAX_VALUE
                }
                if (host.isNotBlank() && !goodHosts.contains(host)) {
                    bannedHosts[host] = now + 30_000L
                }
                false
            }
            code == 412 || code == 429 -> {
                if (host.isNotBlank()) {
                    val ttl = if (code == 429) 45_000L else 30_000L
                    bannedHosts[host] = now + ttl
                }
                true
            }
            else -> {
                if (host.isNotBlank()) {
                    bannedHosts[host] = now + 20_000L
                }
                true
            }
        }
    }

    fun recordTransportFailure(host: String, durationMs: Long = 15_000L, now: Long = System.currentTimeMillis()) {
        if (host.isNotBlank()) {
            bannedHosts[host] = now + durationMs
        }
    }

    fun isBanned(url: String, host: String, now: Long = System.currentTimeMillis()): Boolean {
        val urlUntil = bannedUrls[url]
        if (urlUntil != null && urlUntil > now) return true
        val hostUntil = bannedHosts[host]
        if (hostUntil != null && hostUntil > now) return true
        return false
    }

    fun isHostKnownGood(host: String): Boolean = goodHosts.contains(host)
}

data class BtrMediaSession(
    val sessionId: String,
    val primaryUrl: String,
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
    val liveSpeculativeMisses: AtomicInteger = AtomicInteger(0)
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

        val pool: BtrLiveHostPool? = if (isLive) {
            val livePool = BtrLiveHostPool()
            val primaryHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cleanPrimaryLiveUrl)?.host).lowercase()
            if (primaryHost.isNotBlank() && !BtrLiveCore.isP2pUrl(cleanPrimaryLiveUrl)) {
                livePool.add(primaryHost, proven = true)
            }
            // 注入已知官方 fMP4 集群
            for (knownHost in BtrLiveCore.KNOWN_FMP4_HOSTS) {
                if (knownHost != primaryHost) {
                    livePool.add(knownHost, proven = false)
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

        // 若宿主未传入备用地址或仅传入了 Akamai 兜底原链，自动补充兼容的官方同源 UPOS 镜像（优先支持用户自定义 CDN 白名单）
        val finalBackups = if (isLive) {
            validBackups
        } else {
            // 隔离检查（对齐 BTR 规范）：
            // 若当前主地址是中继流，严禁混入普通 UPOS 镜像；
            // 若当前主地址是普通 UPOS 流，严禁混入中继地址（避免触发普通节点 403）
            val isolated = if (isPrimaryRelay) {
                validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
            } else {
                validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) }
            }
            if (!isPrimaryRelay && isolated.none { !BiliAcceleratorCore.isAkamaiHost(it) }) {
                val donor = listOf(primaryUrl).plus(isolated).firstOrNull {
                    !BiliAcceleratorCore.isRelayUrl(it) &&
                        !BiliAcceleratorCore.isAkamaiHost(it) &&
                        !BiliAcceleratorCore.hasDrmSignal(it)
                } ?: primaryUrl
                val synthesized = synthesizeCompatibleBackups(donor, config, isEpisode = isEpisode)
                (synthesized + isolated).distinct().filter { it != primaryUrl }
            } else {
                isolated
            }
        }

        val session = BtrMediaSession(
            sessionId = id,
            primaryUrl = cleanPrimaryLiveUrl,
            backupUrls = finalBackups,
            userAgent = userAgent,
            referer = referer,
            isAudio = isAudio,
            isEpisode = isEpisode,
            isLive = isLive,
            liveHostPool = pool
        )
        sessions[id] = session
        latestSessionId = id
        return id
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
            current.backupUrls = isolatedBackups
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

        // 2. 次级回退：同轨道类型 (isAudio/isLive 一致) + 文件名片段关键帧一致，严禁音视频串台
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

        // 3. 兜底匹配：仅限同轨道类型（音视频、点直播严格隔离）的最近访问会话
        val sameTrackLatest = orderedSessions.firstOrNull { session ->
            session.isAudio == incomingIsAudio && session.isLive == incomingIsLive
        }
        if (sameTrackLatest != null) {
            updateBackups(sameTrackLatest.sessionId, backups)
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
     * 安全合成同源通用 UPOS 备用节点：
     * 当宿主未提供 backupUrls 时，在同组通用 UPOS 节点之间安全互换，
     * 解决海外/大陆多源并发拉取，彻底杜绝 403 熔断。
     *
     * 针对剧集（isEpisode）：
     * 严格执行区域版权限流保护，若是港澳台或海外限定剧集（bstarstatic, cn-hk-eq, ov.bilivideo.com 等），
     * 严禁混入大陆普通 UPOS 节点，避免触发版权鉴权 403。
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

        // 对齐 BTR Issue #47：中继地址（Relay/MCDN）与 DRM 版权流严禁作为 Donor 母本合成普通镜像
        if (BiliAcceleratorCore.isRelayUrl(primaryUrl) || path.startsWith("/v1/resource/") || BiliAcceleratorCore.hasDrmSignal(primaryUrl)) {
            return emptyList()
        }

        val host = uri.host.lowercase()
        val isAkamai = host.endsWith(".akamaized.net")
        val isRegionalPgcHost = host.contains("bstar") ||
                host.contains("cn-hk-eq") ||
                host.contains("ov.bilivideo.com") ||
                isAkamai

        // 对齐 BTR：当海外账号仅分发 akamaized.net 节点或播放港澳台/海外专属剧集时，提取签名合成海外专属镜像
        val isOverseasStream = host.contains("ov.bilivideo.com") ||
                host.contains("cn-hk-eq") ||
                isAkamai ||
                (isEpisode && isRegionalPgcHost)

        val candidateList = if (config != null && config.customCdnPool.isNotEmpty()) {
            BiliAcceleratorCore.getEffectiveCdnPool(config)
        } else if (isOverseasStream) {
            BiliAcceleratorCore.OVERSEAS_CANDIDATE_POOL
        } else {
            BiliAcceleratorCore.MAINLAND_CANDIDATE_POOL
        }

        val maxBackups = if (config != null) config.btrCdnPoolSize.coerceIn(1, candidateList.size) else minOf(5, candidateList.size)
        val isCustom = config != null && config.customCdnPool.isNotEmpty()

        for (m in candidateList) {
            val cleanM = BiliAcceleratorCore.cleanHost(m).lowercase()
            if (cleanM == host || cleanM.endsWith(".akamaized.net")) continue
            // 严格隔离：非自定义 CDN 池时，海外 ov 节点仅在海外主地址时合成，大陆节点仅在大陆主地址时合成，防止区域鉴权 403
            if (!isCustom) {
                val candidateIsOverseas = cleanM.contains("ov.bilivideo.com") || cleanM.contains("cn-hk-eq") || cleanM.contains("bstar")
                if (isOverseasStream != candidateIsOverseas) continue
            }

            results.add(uri.replaceAuthority(cleanM))
            if (results.size >= maxBackups) break
        }
        return results
    }

    private fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        if (sessions.size > MAX_SESSIONS) {
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastAccessed.get() > SESSION_TTL_MS) {
                    iterator.remove()
                }
            }
        }
    }
}
