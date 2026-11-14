package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

data class LiveSegment(
    val name: String,
    val url: String,
    val num: Long,
    val duration: Double = 0.0
)

data class ParsedM3u8(
    val mapUrl: String?,
    val segments: List<LiveSegment>,
    val lastNum: Long
)

data class LiveHostStatus(
    val host: String,
    val state: String, // "healthy", "blocked", "banned", "untested"
    val bps: Long,
    val fbMs: Long
)

class HostHealth(
    @Volatile var fbMs: Double = 0.0,
    @Volatile var bps: Double = 0.0,
    @Volatile var failures: Int = 0,
    @Volatile var blockedUntil: Long = 0L,
    @Volatile var lastSuccessAt: Long = 0L,
    @Volatile var proven: Boolean = false,
    @Volatile var emptyReplies: Int = 0,
    @Volatile var banned: Boolean = false
)

class BtrLiveHostPool(
    private val onBan: ((String) -> Unit)? = null
) {
    private val health = ConcurrentHashMap<String, HostHealth>()

    private fun entry(host: String): HostHealth {
        val cleanHost = BiliAcceleratorCore.cleanHost(host).lowercase()
        return health.computeIfAbsent(cleanHost) { HostHealth() }
    }

    fun add(host: String, proven: Boolean = false) {
        if (host.isBlank()) return
        val clean = BiliAcceleratorCore.cleanHost(host).lowercase()
        val item = entry(clean)
        if (proven) item.proven = true
    }

    fun success(host: String, fbMs: Double, bps: Double) {
        val item = entry(host)
        synchronized(item) {
            item.proven = true
            item.banned = false
            item.emptyReplies = 0
            item.failures = 0
            item.blockedUntil = 0L
            item.lastSuccessAt = System.currentTimeMillis()
            if (fbMs > 0.0) {
                item.fbMs = if (item.fbMs > 0.0) item.fbMs * 0.6 + fbMs * 0.4 else fbMs
            }
            if (bps > 0.0) {
                item.bps = if (item.bps > 0.0) item.bps * 0.6 + bps * 0.4 else bps
            }
        }
    }

    fun failure(host: String, receivedBytes: Long = 0L) {
        val item = entry(host)
        synchronized(item) {
            item.failures += 1
            val now = System.currentTimeMillis()
            val penalty = min(20000.0, 1500.0 * (2.0.pow(min(item.failures, 3)))).toLong()
            item.blockedUntil = now + penalty

            if (receivedBytes <= 0L) {
                item.emptyReplies += 1
                if (item.emptyReplies >= 2 && !item.banned) {
                    item.banned = true
                    onBan?.invoke(host)
                }
            }
        }
    }

    fun pick(count: Int = 2): List<String> {
        val now = System.currentTimeMillis()
        val all = health.entries.toList()
        if (all.isEmpty()) return emptyList()

        val open = all.filter { !it.value.banned && it.value.blockedUntil <= now }
        val pool = when {
            open.isNotEmpty() -> open
            else -> {
                val unbanned = all.filter { !it.value.banned }
                if (unbanned.isNotEmpty()) unbanned else all
            }
        }

        val ranked = pool.sortedWith { a, b ->
            val aItem = a.value
            val bItem = b.value
            // 1. 验证通过节点优先
            val provenCompare = bItem.proven.compareTo(aItem.proven)
            if (provenCompare != 0) return@sortedWith provenCompare

            // 2. 首字节延迟低的优先
            val aFb = if (aItem.fbMs > 0.0) aItem.fbMs else 999999.0
            val bFb = if (bItem.fbMs > 0.0) bItem.fbMs else 999999.0
            val fbCompare = aFb.compareTo(bFb)
            if (fbCompare != 0) return@sortedWith fbCompare

            // 3. 吞吐速率高的优先
            bItem.bps.compareTo(aItem.bps)
        }

        return ranked.take(maxOf(1, count)).map { it.key }
    }

    fun unproven(): List<String> {
        return health.entries
            .filter { !it.value.proven && !it.value.banned }
            .map { it.key }
    }

    fun status(): List<LiveHostStatus> {
        val now = System.currentTimeMillis()
        return health.entries.map { (host, item) ->
            val state = when {
                item.banned -> "banned"
                item.blockedUntil > now -> "blocked"
                item.proven -> "healthy"
                else -> "untested"
            }
            LiveHostStatus(
                host = host,
                state = state,
                bps = item.bps.toLong(),
                fbMs = item.fbMs.toLong()
            )
        }
    }
}

object BtrLiveCore {

    /**
     * BTR 原版已知 fMP4 HLS 官方镜像集群（支持同源签名跨节点拉取）
     */
    val KNOWN_FMP4_HOSTS = listOf(
        "d1--cn-gotcha204.bilivideo.com",
        "d1--cn-gotcha208.bilivideo.com",
        "d1--ov-gotcha208.bilivideo.com",
        "d1--ov-gotcha208b.bilivideo.com"
    )

    private val LIVE_HOST_REGEX = Regex("""(?:\.|^)bilivideo\.(com|cn|net)$""", RegexOption.IGNORE_CASE)
    private val PROXY_WRAP_REGEX = Regex(
        """^(https?:)//[\w.-]+\.smtcdns\.(?:net|com)/([\w-]+\.bilivideo\.(?:com|cn|net))(/.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun isLiveSegmentUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            val host = uri.host ?: ""
            val path = uri.path ?: ""
            uri.scheme?.equals("https", ignoreCase = true) == true &&
                    LIVE_HOST_REGEX.containsMatchIn(host) &&
                    path.contains("/live-bvc/") &&
                    path.endsWith(".m4s", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun isLivePlaylistUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            val host = uri.host ?: ""
            val path = uri.path ?: ""
            uri.scheme?.equals("https", ignoreCase = true) == true &&
                    LIVE_HOST_REGEX.containsMatchIn(host) &&
                    path.endsWith(".m3u8", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun isP2pUrl(value: String): Boolean {
        val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(value)?.host).lowercase()
        if (host.isBlank()) return false
        return BiliAcceleratorCore.isKnownP2pHost(host) ||
                BiliAcceleratorCore.isMcdnHost(host) ||
                host.split(".").firstOrNull()?.contains("302") == true
    }

    /**
     * 将商业中继代理包装的 URL 解包恢复至官方源节点 (对齐 BTR unwrapProxyUrl)
     * e.g. https://xxx.smtcdns.net/d1--yy.bilivideo.com/... -> https://d1--yy.bilivideo.com/...
     */
    fun unwrapProxyUrl(value: String): String {
        val match = PROXY_WRAP_REGEX.matchEntire(value.trim()) ?: return ""
        val scheme = match.groupValues[1]
        val host = match.groupValues[2]
        val rest = match.groupValues[3]
        return "$scheme//$host$rest"
    }

    fun segmentNumber(name: String): Long {
        val match = Regex("""(\d+)\.m4s""", RegexOption.IGNORE_CASE).find(name)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    /**
     * 解析 m3u8 播放列表文本并提取分片与初始化分片 URL (对齐 BTR parseM3u8)
     */
    fun parseM3u8(text: String, playlistUrl: String): ParsedM3u8 {
        val lines = text.lines()
        val segments = mutableListOf<LiveSegment>()
        var mapUrl: String? = null
        var currentDuration = 0.0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("#EXT-X-MAP", ignoreCase = true)) {
                val uriMatch = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(line)
                val uriStr = uriMatch?.groupValues?.get(1)
                if (!uriStr.isNullOrBlank()) {
                    mapUrl = resolveUrl(uriStr, playlistUrl)
                }
                continue
            }
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val durStr = line.substringAfter("#EXTINF:").substringBefore(',').trim()
                currentDuration = durStr.toDoubleOrNull() ?: 0.0
                continue
            }
            if (line.isBlank() || line.startsWith("#")) continue

            val resolved = resolveUrl(line, playlistUrl)
            val num = segmentNumber(line)
            segments.add(LiveSegment(name = line, url = resolved, num = num, duration = currentDuration))
            currentDuration = 0.0
        }

        val lastNum = segments.lastOrNull()?.num ?: 0L
        return ParsedM3u8(mapUrl = mapUrl, segments = segments, lastNum = lastNum)
    }

    fun swapHost(originalUrl: String, newHost: String): String {
        val normalized = unwrapProxyUrl(originalUrl).ifBlank { originalUrl }
        return try {
            val uri = URI(normalized)
            URI(
                uri.scheme,
                uri.userInfo,
                newHost,
                -1, // 移除特殊端口
                uri.path,
                uri.query,
                uri.fragment
            ).toASCIIString()
        } catch (_: Throwable) {
            normalized
        }
    }

    /**
     * 基于播放列表最后一个已知分片猜测下一未发布的分片 URL（对齐 BTR speculative prefetch: lastNum + 1）
     */
    fun guessNextSegmentUrl(lastSeg: LiveSegment): String? {
        if (lastSeg.num <= 0L) return null
        val nextNum = lastSeg.num + 1L
        return try {
            val uri = URI(lastSeg.url)
            val path = uri.rawPath ?: return null
            val regex = Regex("""(\d+)(\.m4s)$""", RegexOption.IGNORE_CASE)
            val replacedPath = regex.replace(path) { "$nextNum${it.groupValues[2]}" }
            if (replacedPath == path) return null
            URI(
                uri.scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                replacedPath,
                uri.rawQuery,
                uri.rawFragment
            ).toASCIIString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveUrl(relativeOrAbsolute: String, baseUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(relativeOrAbsolute).toASCIIString()
        } catch (_: Throwable) {
            relativeOrAbsolute
        }
    }
}
