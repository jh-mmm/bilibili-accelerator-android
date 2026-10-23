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
    val lastAccessed: AtomicLong = AtomicLong(System.currentTimeMillis()),
    @Volatile var totalLength: Long = -1L,
    @Volatile var contentType: String? = null
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
        val isPrimaryRelay = BiliAcceleratorCore.isRelayUrl(primaryUrl)
        // 若宿主未传入备用地址，自动合成兼容的官方同源 UPOS 镜像（优先支持用户自定义 CDN 白名单），确保多源 CDN 并发调度生效
        val finalBackups = if (validBackups.isEmpty()) {
            synthesizeCompatibleBackups(primaryUrl, config)
        } else {
            // 隔离检查（对齐 BTR 规范）：
            // 若当前主地址是中继流，严禁混入普通 UPOS 镜像；
            // 若当前主地址是普通 UPOS 流，严禁混入中继地址（避免触发普通节点 403）
            if (isPrimaryRelay) {
                validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
            } else {
                validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) }
            }
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
        val isPrimaryRelay = BiliAcceleratorCore.isRelayUrl(current.primaryUrl)
        val isolatedBackups = if (isPrimaryRelay) {
            validBackups.filter { BiliAcceleratorCore.isRelayUrl(it) }
        } else {
            validBackups.filter { !BiliAcceleratorCore.isRelayUrl(it) }
        }
        current.backupUrls = isolatedBackups
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
     * 当宿主未提供 backupUrls 时，在同组通用 UPOS 节点之间安全互换，
     * 解决海外/大陆多源并发拉取，彻底杜绝 403 熔断。
     */
    fun synthesizeCompatibleBackups(primaryUrl: String, config: AcceleratorConfig? = null): List<String> {
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
        // 对齐 BTR：当海外账号仅分发 akamaized.net 节点时，支持提取其签名合成海外专属 ov 镜像，恢复多源并发能力
        val isOverseasStream = host.contains("ov.bilivideo.com") || isAkamai || BiliAcceleratorCore.isOverseasEnvironment()

        val candidateList = if (config != null) {
            BiliAcceleratorCore.getEffectiveCdnPool(config)
        } else if (isOverseasStream) {
            listOf(
                "upos-sz-mirrorcosov.bilivideo.com",
                "upos-sz-mirroraliov.bilivideo.com",
                "upos-sz-mirrorhwov.bilivideo.com"
            )
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
            if (cleanM == host || cleanM.endsWith(".akamaized.net")) continue
            // 严格隔离：海外 ov 节点仅在海外主地址时合成，大陆节点仅在大陆主地址时合成，防止区域鉴权 403
            val candidateIsOv = cleanM.contains("ov.bilivideo.com")
            if (isOverseasStream != candidateIsOv) continue

            results.add(uri.replaceAuthority(cleanM))
            if (results.size >= 3) break
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
