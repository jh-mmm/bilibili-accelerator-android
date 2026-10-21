package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class BtrMediaSession(
    val sessionId: String,
    val primaryUrl: String,
    @Volatile var backupUrls: List<String>,
    @Volatile var userAgent: String?,
    @Volatile var referer: String = "https://www.bilibili.com/",
    val isAudio: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: AtomicLong = AtomicLong(System.currentTimeMillis())
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
        config: AcceleratorConfig? = null
    ): String {
        cleanupStaleSessions()
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val validBackups = backupUrls.filter { it.isNotBlank() && it != primaryUrl }.distinct()
        // 若宿主未传入备用地址，自动合成兼容的官方同源 UPOS 镜像（优先支持用户自定义 CDN 白名单），确保多源 CDN 并发调度生效
        val finalBackups = if (validBackups.isEmpty()) {
            synthesizeCompatibleBackups(primaryUrl, config)
        } else {
            validBackups
        }
        val session = BtrMediaSession(
            sessionId = id,
            primaryUrl = primaryUrl,
            backupUrls = finalBackups,
            userAgent = userAgent,
            isAudio = isAudio
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
        current.backupUrls = validBackups
    }

    fun updateBackupsForLatest(backups: List<String>) {
        val id = latestSessionId ?: return
        updateBackups(id, backups)
    }

    fun updateBackupsByCandidateUrls(backups: List<String>) {
        if (backups.isEmpty()) return
        val samplePath = extractComparablePath(backups.first())
        if (samplePath.isNotBlank()) {
            for ((_, session) in sessions) {
                if (extractComparablePath(session.primaryUrl) == samplePath) {
                    session.backupUrls = backups.filter { it.isNotBlank() && it != session.primaryUrl }.distinct()
                    return
                }
            }
        }
        updateBackupsForLatest(backups)
    }

    private fun extractComparablePath(url: String): String {
        return BiliAcceleratorCore.parseUri(url)?.path ?: ""
    }

    /**
     * 安全合成同源通用 UPOS 备用节点：
     * 当宿主未提供 backupUrls 时，在同组通用 UPOS 节点（cos / ali / hw / tx）之间安全互换，
     * 排除必须使用专属鉴权的 Akamai 及海外私有 ov 节点，彻底杜绝 403 熔断并实现多 CDN 聚合并发拉取。
     */
    fun synthesizeCompatibleBackups(primaryUrl: String, config: AcceleratorConfig? = null): List<String> {
        val results = mutableListOf<String>()
        val uri = BiliAcceleratorCore.parseUri(primaryUrl) ?: return emptyList()
        val path = uri.path
        if (!path.contains("/upgcxcode/")) return emptyList()

        val host = uri.host.lowercase()
        if (host.endsWith(".akamaized.net") || host.contains("ov.bilivideo.com")) {
            return emptyList()
        }

        val candidateList = if (config != null) {
            BiliAcceleratorCore.getEffectiveCdnPool(config)
        } else {
            listOf(
                "upos-sz-mirrorcos.bilivideo.com",
                "upos-sz-mirrorali.bilivideo.com",
                "upos-sz-mirrorhw.bilivideo.com",
                "upos-tf-all-tx.bilivideo.com",
                "upos-tf-all-hw.bilivideo.com"
            )
        }

        for (m in candidateList) {
            val cleanM = BiliAcceleratorCore.cleanHost(m).lowercase()
            // 排除与主域名相同、Akamai 专属鉴权、以及海外带区域鉴权的 ov 节点
            if (cleanM != host && !cleanM.endsWith(".akamaized.net") && !cleanM.contains("ov.bilivideo.com")) {
                results.add(uri.replaceAuthority(cleanM))
                if (results.size >= 3) break
            }
        }
        return results
    }

    /**
     * 合成备用地址逻辑：
     * 为彻底根治 BTR 节点 403 熔断问题，默认不再随意向候选池硬塞未签名的外部 UPOS 镜像，
     * 而是优先依赖官方原生 setBackupUrls 提供的合法已签名地址。
     * 若在测试环境或强制配置时需要模拟，仅保留同源且带必要参数的候选。
     */
    fun synthesizeBackups(primaryUrl: String, config: AcceleratorConfig): List<String> {
        val results = mutableListOf<String>()
        val uri = BiliAcceleratorCore.parseUri(primaryUrl) ?: return emptyList()
        val path = uri.path
        if (!path.contains("/upgcxcode/")) return emptyList()

        // 仅在明确开启强行 UPOS 重定向且不是海外私有鉴权时提供同组镜像作为回退
        if (config.forceUpos) {
            for (host in BiliAcceleratorCore.CANDIDATE_POOL) {
                // 排除海外带区域鉴权的 ov 节点，防止国内 IP 产生 403 熔断
                if (host.contains("ov.bilivideo.com")) continue
                if (host != uri.host && host != config.targetHost) {
                    val candidate = BiliAcceleratorCore.rewriteUrl(primaryUrl, config.copy(targetHost = host))
                    if (candidate.changed && candidate.finalUrl.isNotBlank()) {
                        results.add(candidate.finalUrl)
                    }
                }
            }
        }
        return results.distinct()
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
