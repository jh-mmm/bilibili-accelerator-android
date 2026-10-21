package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class BtrMediaSession(
    val sessionId: String,
    val primaryUrl: String,
    val backupUrls: List<String>,
    val userAgent: String?,
    val referer: String = "https://www.bilibili.com/",
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: AtomicLong = AtomicLong(System.currentTimeMillis())
)

object BtrSessionManager {

    private const val MAX_SESSIONS = 50
    private const val SESSION_TTL_MS = 30 * 60 * 1000L // 30 分钟无访问自动清理

    private val sessions = ConcurrentHashMap<String, BtrMediaSession>()

    @Volatile
    private var latestSessionId: String? = null

    fun register(
        primaryUrl: String,
        backupUrls: List<String> = emptyList(),
        userAgent: String? = null
    ): String {
        cleanupStaleSessions()
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val session = BtrMediaSession(
            sessionId = id,
            primaryUrl = primaryUrl,
            backupUrls = backupUrls.filter { it.isNotBlank() && it != primaryUrl }.distinct(),
            userAgent = userAgent
        )
        sessions[id] = session
        latestSessionId = id
        return id
    }

    fun get(sessionId: String): BtrMediaSession? {
        val s = sessions[sessionId]
        s?.lastAccessed?.set(System.currentTimeMillis())
        return s
    }

    fun updateBackupsForLatest(backups: List<String>) {
        val id = latestSessionId ?: return
        val current = sessions[id] ?: return
        val combined = (current.backupUrls + backups).filter { it.isNotBlank() && it != current.primaryUrl }.distinct()
        sessions[id] = current.copy(backupUrls = combined)
    }

    fun synthesizeBackups(primaryUrl: String, config: AcceleratorConfig): List<String> {
        val results = mutableListOf<String>()
        val uri = BiliAcceleratorCore.parseUri(primaryUrl) ?: return emptyList()
        val path = uri.path ?: ""
        if (!path.contains("/upgcxcode/")) return emptyList()

        for (host in BiliAcceleratorCore.CANDIDATE_POOL) {
            if (host != uri.host && host != config.targetHost) {
                val candidate = BiliAcceleratorCore.rewriteUrl(primaryUrl, config.copy(targetHost = host))
                if (candidate.changed && candidate.finalUrl.isNotBlank()) {
                    results.add(candidate.finalUrl)
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
