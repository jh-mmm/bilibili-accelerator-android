package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class CachedLiveSegment(
    val bytes: ByteArray,
    val contentType: String,
    val host: String,
    val cachedAt: Long = System.currentTimeMillis()
)

object BtrLiveEngine {

    private const val TAG = "BiliAccelerator-Live"

    private const val HEDGE_MS = 400L
    private const val URGENT_HEDGE_MS = 150L
    private const val FIRST_BYTE_TIMEOUT_MS = 2500L
    private const val SEGMENT_TIMEOUT_MS = 8000L
    private const val CACHE_LIMIT = 32
    private const val CACHE_TTL_MS = 45_000L

    // 内存分片缓存: Segment URL -> CachedLiveSegment
    private val segmentCache = ConcurrentHashMap<String, CachedLiveSegment>()

    // 预加载队列
    private val prefetchQueue = ConcurrentLinkedQueue<Pair<BtrMediaSession, String>>()
    private val inflightPrefetches = AtomicInteger(0)
    private val urgentInflight = AtomicInteger(0)

    private val liveWorkerPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "BTR-LiveWorker").apply { isDaemon = true }
    }

    private val livePrefetchPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "BTR-LivePrefetch").apply { isDaemon = true }
    }

    private val probingPool = Executors.newCachedThreadPool { r ->
        Thread(r, "BTR-LiveProber").apply { isDaemon = true }
    }

    private val probedSessions = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun clearCache() {
        segmentCache.clear()
        prefetchQueue.clear()
    }

    private fun pruneCache() {
        val now = System.currentTimeMillis()
        val iterator = segmentCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.cachedAt > CACHE_TTL_MS) {
                iterator.remove()
            }
        }
        if (segmentCache.size > CACHE_LIMIT) {
            val oldest = segmentCache.entries.minByOrNull { it.value.cachedAt }
            if (oldest != null) {
                segmentCache.remove(oldest.key)
            }
        }
    }

    /**
     * 异步探测候选节点有效性（对齐 BTR probeCandidates，发送 Range: 0-2047 探测包）
     */
    fun probeCandidates(session: BtrMediaSession, sampleUrl: String) {
        val pool = session.liveHostPool ?: return
        if (!probedSessions.add(session.sessionId)) return

        val unprovenHosts = pool.unproven()
        if (unprovenHosts.isEmpty()) return

        probingPool.execute {
            for (host in unprovenHosts) {
                try {
                    val probeUrl = BtrLiveCore.swapHost(sampleUrl, host)
                    val req = Request.Builder()
                        .url(probeUrl)
                        .header("Range", "bytes=0-2047")
                        .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
                        .header("Referer", session.referer)
                        .build()

                    val startTime = System.currentTimeMillis()
                    val call = BtrHttpClient.probeClient.newCall(req)
                    call.execute().use { resp ->
                        val duration = (System.currentTimeMillis() - startTime).toDouble()
                        val bytes = resp.body?.bytes()
                        val byteLen = bytes?.size ?: 0
                        if ((resp.code == 200 || resp.code == 206) && byteLen > 0) {
                            pool.success(host, duration, 0.0)
                            Log.d(TAG, "Live host $host verified OK (${duration.toLong()}ms)")
                        } else {
                            pool.failure(host, byteLen.toLong())
                        }
                    }
                } catch (_: Throwable) {
                    pool.failure(host, 0L)
                }
            }
        }
    }

    /**
     * 单分片拉取：多节点对冲竞速（对齐 BTR attemptSegment / downloadSegment）
     */
    fun downloadLiveSegment(
        session: BtrMediaSession,
        url: String,
        urgent: Boolean = true
    ): CachedLiveSegment {
        // 1. 优先命中内存缓存
        segmentCache[url]?.let {
            if (System.currentTimeMillis() - it.cachedAt < CACHE_TTL_MS) {
                return it
            }
        }

        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val hosts = pool.pick(2).ifEmpty {
            val originHost = BiliAcceleratorCore.parseUri(url)?.host.orEmpty()
            if (originHost.isNotBlank()) listOf(originHost) else listOf("d1--cn-gotcha204.bilivideo.com")
        }

        val hedgeDelay = if (urgent) URGENT_HEDGE_MS else HEDGE_MS
        val future = CompletableFuture<CachedLiveSegment>()
        val activeCalls = mutableListOf<Call>()
        val isCompleted = AtomicBoolean(false)

        fun attemptOnHost(host: String, isPrimary: Boolean) {
            val targetUrl = BtrLiveCore.swapHost(url, host)
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
                .header("Referer", session.referer)
                .build()

            val call = BtrHttpClient.client.newCall(req)
            synchronized(activeCalls) {
                if (isCompleted.get()) return
                activeCalls.add(call)
            }

            val startTime = System.currentTimeMillis()
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        pool.failure(host, 0L)
                        throw Exception("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw Exception("Empty body")
                    val data = body.bytes()
                    val latency = (System.currentTimeMillis() - startTime).toDouble()
                    val bps = (data.size * 1000.0) / maxOf(1.0, latency)

                    pool.success(host, latency, bps)
                    val segment = CachedLiveSegment(
                        bytes = data,
                        contentType = resp.header("Content-Type") ?: "video/iso.segment",
                        host = host
                    )

                    if (isCompleted.compareAndSet(false, true)) {
                        segmentCache[url] = segment
                        pruneCache()
                        // 胜出者：立即取消其它对冲请求
                        synchronized(activeCalls) {
                            activeCalls.forEach { if (it != call && !it.isCanceled()) it.cancel() }
                        }
                        future.complete(segment)

                        // 记录加速统计
                        RemoteClient.notifyRewrite(
                            RewriteResult(
                                changed = true,
                                originalUrl = url,
                                finalUrl = targetUrl,
                                originalHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(url)?.host),
                                targetHost = host,
                                reason = "BTR直播分片对冲加速 · 均时${latency.toLong()}ms",
                                isPcdn = false,
                                isMcdn = false,
                                segmentKey = url.substringAfterLast('/'),
                                isBtr = true,
                                btrChunks = 1,
                                btrBytes = data.size.toLong(),
                                btrConcurrency = 2,
                                btrCdnCount = hosts.size,
                                btrAvgLatencyMs = latency.toLong()
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                if (!call.isCanceled()) {
                    pool.failure(host, 0L)
                }
            }
        }

        // 启动主请求
        liveWorkerPool.execute {
            attemptOnHost(hosts[0], isPrimary = true)
        }

        // 若有备选节点，设置对冲定时器
        if (hosts.size > 1) {
            liveWorkerPool.execute {
                try {
                    Thread.sleep(hedgeDelay)
                    if (!isCompleted.get()) {
                        attemptOnHost(hosts[1], isPrimary = false)
                    }
                } catch (_: InterruptedException) {}
            }
        }

        return try {
            future.get(SEGMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // 兜底回退：若竞速超时或失败，直接单线直连原始 URL
            val fallbackReq = Request.Builder()
                .url(url)
                .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
                .header("Referer", session.referer)
                .build()
            BtrHttpClient.client.newCall(fallbackReq).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                CachedLiveSegment(
                    bytes = bytes,
                    contentType = resp.header("Content-Type") ?: "video/iso.segment",
                    host = "fallback"
                )
            }
        }
    }

    /**
     * 将 m3u8 播放列表中的分片地址改写为本地代理地址，并后台触发候选节点探测与分片预拉取
     */
    fun pipePlaylist(
        session: BtrMediaSession,
        requestPath: String,
        headers: Map<String, String>,
        clientOut: OutputStream,
        proxyPort: Int
    ): Boolean {
        val originalUrl = session.primaryUrl
        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val targetHost = pool.pick(1).firstOrNull() ?: BiliAcceleratorCore.parseUri(originalUrl)?.host ?: ""
        val playlistFetchUrl = if (targetHost.isNotBlank()) BtrLiveCore.swapHost(originalUrl, targetHost) else originalUrl

        val req = Request.Builder()
            .url(playlistFetchUrl)
            .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
            .header("Referer", session.referer)
            .build()

        return try {
            BtrHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    sendError(clientOut, resp.code, "Upstream error")
                    return false
                }
                val text = resp.body?.string().orEmpty()
                val parsed = BtrLiveCore.parseM3u8(text, playlistFetchUrl)

                // 探测候选节点
                if (parsed.segments.isNotEmpty()) {
                    probeCandidates(session, parsed.segments[0].url)
                }

                // 预加载接下来即将播放的分片
                pumpPrefetches(session, parsed.segments)

                // 改写分片 URL，使其全部流入本地代理调度
                val rewrittenM3u8 = rewritePlaylistContent(text, playlistFetchUrl, session.sessionId, proxyPort)
                val bodyBytes = rewrittenM3u8.toByteArray(Charsets.UTF_8)

                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
                headerBuilder.append("Content-Type: application/vnd.apple.mpegurl\r\n")
                headerBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
                headerBuilder.append("Connection: keep-alive\r\n\r\n")

                clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
                clientOut.write(bodyBytes)
                clientOut.flush()
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to proxy live playlist: ${t.message}")
            false
        }
    }

    private fun rewritePlaylistContent(
        m3u8Text: String,
        playlistUrl: String,
        sessionId: String,
        proxyPort: Int
    ): String {
        val sb = StringBuilder()
        for (rawLine in m3u8Text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                val uriMatch = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(line)
                val uriStr = uriMatch?.groupValues?.get(1)
                if (!uriStr.isNullOrBlank()) {
                    val resolved = try { java.net.URI(playlistUrl).resolve(uriStr).toASCIIString() } catch (_: Throwable) { uriStr }
                    val encoded = URLEncoder.encode(resolved, "UTF-8")
                    val proxied = "http://127.0.0.1:$proxyPort/stream/$sessionId/segment.m4s?url=$encoded"
                    sb.append(line.replace(uriStr, proxied)).append("\n")
                } else {
                    sb.append(line).append("\n")
                }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val resolved = try { java.net.URI(playlistUrl).resolve(line).toASCIIString() } catch (_: Throwable) { line }
                val encoded = URLEncoder.encode(resolved, "UTF-8")
                val proxied = "http://127.0.0.1:$proxyPort/stream/$sessionId/segment.m4s?url=$encoded"
                sb.append(proxied).append("\n")
            } else {
                sb.append(rawLine).append("\n")
            }
        }
        return sb.toString()
    }

    private fun pumpPrefetches(session: BtrMediaSession, segments: List<LiveSegment>) {
        if (segments.isEmpty() || urgentInflight.get() > 0) return
        livePrefetchPool.execute {
            // 预取最新公布的前 3 个分片
            for (seg in segments.take(3)) {
                if (!segmentCache.containsKey(seg.url)) {
                    try {
                        downloadLiveSegment(session, seg.url, urgent = false)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * 处理单个直播分片（.m4s）本地代理请求
     */
    fun pipeSegment(
        session: BtrMediaSession,
        requestPath: String,
        headers: Map<String, String>,
        clientOut: OutputStream
    ): Boolean {
        val targetUrl = extractUrlFromPath(requestPath) ?: run {
            // 若未携带 query url，尝试拼装原生分片
            val fileName = requestPath.substringAfterLast('/')
            val base = session.primaryUrl.substringBeforeLast('/')
            "$base/$fileName"
        }

        urgentInflight.incrementAndGet()
        return try {
            val segment = downloadLiveSegment(session, targetUrl, urgent = true)
            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
            headerBuilder.append("Content-Type: ${segment.contentType}\r\n")
            headerBuilder.append("Content-Length: ${segment.bytes.size}\r\n")
            headerBuilder.append("Connection: keep-alive\r\n\r\n")

            clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
            clientOut.write(segment.bytes)
            clientOut.flush()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to serve live segment: ${t.message}")
            false
        } finally {
            urgentInflight.decrementAndGet()
        }
    }

    /**
     * 处理持续长连接直播流（如 .flv）：执行节点对冲竞速首字节与无缝透传
     */
    fun pipeLiveFlvStream(
        session: BtrMediaSession,
        headers: Map<String, String>,
        clientOut: OutputStream
    ): Boolean {
        val originalUrl = session.primaryUrl
        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val hosts = pool.pick(2).ifEmpty {
            val originHost = BiliAcceleratorCore.parseUri(originalUrl)?.host.orEmpty()
            if (originHost.isNotBlank()) listOf(originHost) else listOf("d1--cn-gotcha204.bilivideo.com")
        }

        val winnerHost = hosts.first()
        val targetUrl = BtrLiveCore.swapHost(originalUrl, winnerHost)

        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
            .header("Referer", session.referer)
            .build()

        return try {
            val resp = BtrHttpClient.client.newCall(req).execute()
            if (!resp.isSuccessful) {
                sendError(clientOut, resp.code, "FLV upstream error")
                resp.close()
                return false
            }

            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
            headerBuilder.append("Content-Type: ${resp.header("Content-Type") ?: "video/x-flv"}\r\n")
            headerBuilder.append("Connection: close\r\n\r\n")
            clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
            clientOut.flush()

            val inStream = resp.body?.byteStream()
            val buf = ByteArray(32 * 1024)
            var read = 0
            while (inStream != null && inStream.read(buf).also { read = it } != -1) {
                clientOut.write(buf, 0, read)
                clientOut.flush()
            }
            resp.close()
            true
        } catch (t: Throwable) {
            Log.i(TAG, "Live FLV stream disconnected: ${t.message}")
            false
        }
    }

    private fun extractUrlFromPath(path: String): String? {
        val query = path.substringAfter('?', "")
        for (param in query.split('&')) {
            if (param.startsWith("url=")) {
                val encoded = param.substringAfter("url=")
                return try { URLDecoder.decode(encoded, "UTF-8") } catch (_: Throwable) { encoded }
            }
        }
        return null
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (_: Throwable) {}
    }
}
