package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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
    private const val MAX_PROTECTED_MAP_URLS = 4
    private const val MAX_PREFETCH_QUEUE_DEPTH = 12
    private const val MAX_SPECULATIVE_MISSES = 6

    private data class PrefetchItem(
        val session: BtrMediaSession,
        val url: String,
        val isSpeculative: Boolean = false
    )

    private class SpeculativeNotReadyException(code: Int) : Exception("HTTP $code speculative miss")
    private class NonPenaltyHttpException(code: Int) : Exception("HTTP $code non-penalty")

    // 内存分片缓存: Segment URL -> CachedLiveSegment
    private val segmentCache = ConcurrentHashMap<String, CachedLiveSegment>()
    // 有界 LRU #EXT-X-MAP 初始化分片保护集合（上限 4 个近期直播间，防止长久运行占满 32 槽缓存）
    private val protectedMapUrls = object : java.util.LinkedHashMap<String, Boolean>(MAX_PROTECTED_MAP_URLS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > MAX_PROTECTED_MAP_URLS
        }
    }

    private fun isProtectedMapUrl(url: String): Boolean {
        return synchronized(protectedMapUrls) { protectedMapUrls.containsKey(url) }
    }

    // 在途请求共享表: Segment URL -> CompletableFuture (避免预取与播放器即时请求双重下载)
    private val inFlightSegments = ConcurrentHashMap<String, CompletableFuture<CachedLiveSegment>>()

    // 预加载调度队列与去重表
    private val prefetchQueue = ConcurrentLinkedQueue<PrefetchItem>()
    private val queuedPrefetchUrls = ConcurrentHashMap.newKeySet<String>()
    private val inflightPrefetches = AtomicInteger(0)
    private val urgentInflight = AtomicInteger(0)

    private val liveWorkerPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "BTR-LiveWorker").apply { isDaemon = true }
    }

    private val livePrefetchPool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "BTR-LivePrefetch").apply { isDaemon = true }
    }

    private val probingPool = Executors.newCachedThreadPool { r ->
        Thread(r, "BTR-LiveProber").apply { isDaemon = true }
    }

    private val firstByteWatchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "BTR-LiveFBWatchdog").apply { isDaemon = true }
    }

    private val probedSessions = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun clearCache() {
        segmentCache.clear()
        synchronized(protectedMapUrls) { protectedMapUrls.clear() }
        inFlightSegments.clear()
        prefetchQueue.clear()
        queuedPrefetchUrls.clear()
        probedSessions.clear()
    }

    fun onSessionEvicted(session: BtrMediaSession) {
        probedSessions.remove(session.sessionId)
        session.liveMapUrl?.let { mapUrl ->
            synchronized(protectedMapUrls) { protectedMapUrls.remove(mapUrl) }
        }
    }

    internal fun protectMapUrl(url: String) {
        if (url.isNotBlank()) {
            synchronized(protectedMapUrls) {
                protectedMapUrls[url] = true
            }
        }
    }

    internal fun putCacheForTest(url: String, segment: CachedLiveSegment) {
        segmentCache[url] = segment
        pruneCache()
    }

    internal fun getCachedForTest(url: String): CachedLiveSegment? = segmentCache[url]

    private fun pruneCache(protectedUrl: String? = null) {
        val now = System.currentTimeMillis()
        val iterator = segmentCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val isProtected = (protectedUrl != null && entry.key == protectedUrl) || isProtectedMapUrl(entry.key)
            if (!isProtected && now - entry.value.cachedAt > CACHE_TTL_MS) {
                iterator.remove()
            }
        }
        while (segmentCache.size > CACHE_LIMIT) {
            val oldest = segmentCache.entries
                .filter { entry ->
                    val isProtected = (protectedUrl != null && entry.key == protectedUrl) || isProtectedMapUrl(entry.key)
                    !isProtected
                }
                .minByOrNull { it.value.cachedAt }
                ?: break
            segmentCache.remove(oldest.key)
        }
    }

    /**
     * 异步并发探测候选节点有效性（对齐 BTR probeCandidates：Promise.allSettled 并发发送 Range: 0-2047 探测包）
     */
    fun probeCandidates(session: BtrMediaSession, sampleUrl: String) {
        val pool = session.liveHostPool ?: return
        val unprovenHosts = pool.unproven()
        if (unprovenHosts.isEmpty()) return
        if (!probedSessions.add(session.sessionId)) return

        val ua = session.userAgent ?: "BiliApp/7800300"
        val ref = session.referer
        for (host in unprovenHosts) {
            probingPool.execute {
                try {
                    val probeUrl = BtrLiveCore.swapHost(sampleUrl, host)
                    val req = Request.Builder()
                        .url(probeUrl)
                        .header("Range", "bytes=0-2047")
                        .header("User-Agent", ua)
                        .header("Referer", ref)
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
                        } else if (resp.code != 404) {
                            pool.failure(host, byteLen.toLong())
                        }
                    }
                } catch (_: Throwable) {
                    pool.failure(host, 0L)
                }
            }
        }
    }

    private val LIVE_CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

    /**
     * 单分片拉取：多节点对冲竞速（对齐 BTR attemptSegment / downloadSegment）
     */
    fun downloadLiveSegment(
        session: BtrMediaSession,
        url: String,
        urgent: Boolean = true,
        allow404: Boolean = false,
        isSpeculative: Boolean = false,
        requestUserAgent: String? = null,
        requestReferer: String? = null
    ): CachedLiveSegment {
        val normalizedUrl = BtrLiveCore.unwrapProxyUrl(url).ifBlank { url }
        val isMap = isProtectedMapUrl(normalizedUrl) || session.liveMapUrl == normalizedUrl

        // 1. 优先命中内存缓存（#EXT-X-MAP 初始化分片受免过期保护）
        segmentCache[normalizedUrl]?.let {
            if (isMap || System.currentTimeMillis() - it.cachedAt < CACHE_TTL_MS) {
                return it
            }
        }

        // 2. 避免预取与即时请求重复下载：共享 in-flight CompletableFuture
        var isOwner = false
        val future = inFlightSegments.computeIfAbsent(normalizedUrl) {
            isOwner = true
            CompletableFuture<CachedLiveSegment>()
        }
        if (!isOwner) {
            return try {
                future.get(SEGMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                segmentCache[normalizedUrl]?.let { return it }
                if (allow404) throw e
                downloadFallbackSegment(session, normalizedUrl, requestUserAgent, requestReferer)
            }
        }

        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val hosts = pool.pick(2).ifEmpty {
            val originHost = BiliAcceleratorCore.parseUri(normalizedUrl)?.host.orEmpty()
            if (originHost.isNotBlank()) listOf(originHost) else listOf("d1--cn-gotcha204.bilivideo.com")
        }

        val hedgeDelay = when {
            isSpeculative -> HEDGE_MS * 3
            urgent -> URGENT_HEDGE_MS
            else -> HEDGE_MS
        }
        val effectiveUa = requestUserAgent ?: session.userAgent ?: "BiliApp/7800300"
        val effectiveRef = requestReferer ?: session.referer
        val activeCalls = mutableListOf<Call>()
        val isCompleted = AtomicBoolean(false)
        val primaryFailedSignal = AtomicBoolean(false)
        val hedgeWakeLock = Object()
        val failureCount = AtomicInteger(0)
        val hit404Count = AtomicInteger(0)

        fun attemptOnHost(host: String, isPrimary: Boolean) {
            val targetUrl = BtrLiveCore.swapHost(normalizedUrl, host)
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", effectiveUa)
                .header("Referer", effectiveRef)
                .build()

            val call = BtrHttpClient.client.newCall(req)
            synchronized(activeCalls) {
                if (isCompleted.get()) return
                activeCalls.add(call)
            }

            val startTime = System.currentTimeMillis()
            val hasFirstByte = AtomicBoolean(false)
            val fbWatchdog: ScheduledFuture<*> = firstByteWatchdog.schedule(
                {
                    if (!hasFirstByte.get() && !isCompleted.get()) {
                        runCatching { call.cancel() }
                    }
                },
                FIRST_BYTE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )

            var received = 0L
            try {
                call.execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        if (resp.code == 404) {
                            hit404Count.incrementAndGet()
                            // 对齐 BTR live-hook.js:231: 404 仅表示边缘节点尚未同步最新切片，绝不触发节点惩罚或封禁
                            if (allow404) {
                                throw SpeculativeNotReadyException(404)
                            }
                            throw NonPenaltyHttpException(404)
                        }
                        pool.failure(host, 0L)
                        throw Exception("HTTP ${resp.code}")
                    }
                    // 对齐 BTR live-hook.js:191-194: 直播请求不带 Range，若上游返回 206 必须校验 Content-Range 覆盖完整分片 (0..total-1)
                    var declaredTotalFromRange: Long? = null
                    if (resp.code == 206) {
                        val cr = resp.header("Content-Range")?.trim().orEmpty()
                        val crMatch = LIVE_CONTENT_RANGE_REGEX.matchEntire(cr)
                        val start = crMatch?.groupValues?.get(1)?.toLongOrNull()
                        val end = crMatch?.groupValues?.get(2)?.toLongOrNull()
                        val total = crMatch?.groupValues?.get(3)?.toLongOrNull()
                        if (start != 0L || total == null || end != total - 1L) {
                            throw Exception("Partial 206 live segment ($cr)")
                        }
                        declaredTotalFromRange = total
                    }

                    val body = resp.body ?: throw Exception("Empty body")
                    val expectedLength = declaredTotalFromRange ?: body.contentLength()
                    val stream = body.byteStream()
                    val out = ByteArrayOutputStream(expectedLength.toInt().coerceIn(8192, 2 * 1024 * 1024))
                    val buffer = ByteArray(16 * 1024)
                    var firstByteMs = 0.0
                    var read: Int

                    while (stream.read(buffer).also { read = it } != -1) {
                        if (read > 0 && hasFirstByte.compareAndSet(false, true)) {
                            firstByteMs = maxOf(1.0, (System.currentTimeMillis() - startTime).toDouble())
                            fbWatchdog.cancel(false)
                        }
                        if (isCompleted.get()) return
                        out.write(buffer, 0, read)
                        received += read
                    }

                    val data = out.toByteArray()
                    if (data.isEmpty()) {
                        pool.failure(host, 0L)
                        throw Exception("0KB empty response from $host")
                    }
                    if (expectedLength > 0 && data.size.toLong() != expectedLength) {
                        pool.failure(host, data.size.toLong())
                        throw Exception("Incomplete body from $host (${data.size}/$expectedLength)")
                    }

                    val totalDurationMs = maxOf(1.0, (System.currentTimeMillis() - startTime).toDouble())
                    val bps = (data.size * 1000.0) / totalDurationMs
                    val effectiveFbMs = if (firstByteMs > 0.0) firstByteMs else totalDurationMs

                    pool.success(host, effectiveFbMs, bps)
                    val segment = CachedLiveSegment(
                        bytes = data,
                        contentType = resp.header("Content-Type") ?: "video/iso.segment",
                        host = host
                    )

                    if (isCompleted.compareAndSet(false, true)) {
                        segmentCache[normalizedUrl] = segment
                        pruneCache(session.liveMapUrl)
                        synchronized(hedgeWakeLock) { hedgeWakeLock.notifyAll() }
                        synchronized(activeCalls) {
                            activeCalls.forEach { if (it != call && !it.isCanceled()) it.cancel() }
                        }
                        future.complete(segment)

                        RemoteClient.notifyRewrite(
                            RewriteResult(
                                changed = true,
                                originalUrl = normalizedUrl,
                                finalUrl = targetUrl,
                                originalHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(normalizedUrl)?.host),
                                targetHost = host,
                                reason = "BTR直播分片对冲加速 · 首包${effectiveFbMs.toLong()}ms",
                                isPcdn = false,
                                isMcdn = false,
                                segmentKey = normalizedUrl.substringAfterLast('/'),
                                isBtr = true,
                                btrChunks = 1,
                                btrBytes = data.size.toLong(),
                                btrConcurrency = hosts.size,
                                btrCdnCount = hosts.size,
                                btrAvgLatencyMs = effectiveFbMs.toLong()
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t !is SpeculativeNotReadyException && t !is NonPenaltyHttpException && !call.isCanceled()) {
                    pool.failure(host, received)
                }
                if (isPrimary) {
                    primaryFailedSignal.set(true)
                    synchronized(hedgeWakeLock) { hedgeWakeLock.notifyAll() }
                }
                if (failureCount.incrementAndGet() >= hosts.size) {
                    future.completeExceptionally(t)
                }
            } finally {
                fbWatchdog.cancel(false)
            }
        }

        // 启动主请求
        liveWorkerPool.execute {
            attemptOnHost(hosts[0], isPrimary = true)
        }

        // 若有备选节点，监听对冲超时或主路提前失败立即唤醒
        if (hosts.size > 1) {
            liveWorkerPool.execute {
                try {
                    val deadline = System.currentTimeMillis() + hedgeDelay
                    synchronized(hedgeWakeLock) {
                        while (!isCompleted.get() && !primaryFailedSignal.get()) {
                            val rem = deadline - System.currentTimeMillis()
                            if (rem <= 0L) break
                            hedgeWakeLock.wait(rem)
                        }
                    }
                    if (!isCompleted.get()) {
                        attemptOnHost(hosts[1], isPrimary = false)
                    }
                } catch (_: InterruptedException) {}
            }
        }

        return try {
            future.get(SEGMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            synchronized(activeCalls) {
                activeCalls.forEach { if (!it.isCanceled()) runCatching { it.cancel() } }
            }
            if (allow404 && hit404Count.get() > 0) {
                throw SpeculativeNotReadyException(404)
            }
            val fb = downloadFallbackSegment(session, normalizedUrl, effectiveUa, effectiveRef)
            future.complete(fb)
            fb
        } finally {
            inFlightSegments.remove(normalizedUrl)
        }
    }

    private fun downloadFallbackSegment(
        session: BtrMediaSession,
        url: String,
        requestUserAgent: String? = null,
        requestReferer: String? = null
    ): CachedLiveSegment {
        val normalizedUrl = BtrLiveCore.unwrapProxyUrl(url).ifBlank { url }
        val fallbackReq = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", requestUserAgent ?: session.userAgent ?: "BiliApp/7800300")
            .header("Referer", requestReferer ?: session.referer)
            .build()
        return BtrHttpClient.client.newCall(fallbackReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Fallback request failed: HTTP ${resp.code}")
            }
            if (resp.code == 206) {
                val cr = resp.header("Content-Range")?.trim().orEmpty()
                val crMatch = LIVE_CONTENT_RANGE_REGEX.matchEntire(cr)
                val start = crMatch?.groupValues?.get(1)?.toLongOrNull()
                val end = crMatch?.groupValues?.get(2)?.toLongOrNull()
                val total = crMatch?.groupValues?.get(3)?.toLongOrNull()
                if (start != 0L || total == null || end != total - 1L) {
                    throw Exception("Fallback partial 206 live segment ($cr)")
                }
            }
            val body = resp.body ?: throw Exception("Empty fallback body")
            val expectedLen = body.contentLength()
            val bytes = body.bytes()
            if (bytes.isEmpty()) {
                throw Exception("Fallback request returned 0 bytes")
            }
            if (expectedLen > 0 && bytes.size.toLong() != expectedLen) {
                throw Exception("Fallback request incomplete (${bytes.size}/$expectedLen)")
            }
            val segment = CachedLiveSegment(
                bytes = bytes,
                contentType = resp.header("Content-Type") ?: "video/iso.segment",
                host = "fallback"
            )
            segmentCache[normalizedUrl] = segment
            pruneCache(session.liveMapUrl)
            segment
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
        proxyPort: Int,
        keepAlive: Boolean = false,
        requestUserAgent: String? = null,
        requestReferer: String? = null
    ): Boolean {
        val requestedSubUrl = extractUrlFromPath(requestPath)
        val rawTarget = requestedSubUrl ?: session.primaryUrl
        val originalUrl = BtrLiveCore.unwrapProxyUrl(rawTarget).ifBlank { rawTarget }
        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val targetHost = pool.pick(1).firstOrNull() ?: BiliAcceleratorCore.parseUri(originalUrl)?.host ?: ""
        val playlistFetchUrl = if (targetHost.isNotBlank()) BtrLiveCore.swapHost(originalUrl, targetHost) else originalUrl

        val req = Request.Builder()
            .url(playlistFetchUrl)
            .header("User-Agent", requestUserAgent ?: session.userAgent ?: "BiliApp/7800300")
            .header("Referer", requestReferer ?: session.referer)
            .build()

        return try {
            BtrHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    sendError(clientOut, resp.code, "Upstream error")
                    return false
                }
                val text = resp.body?.string().orEmpty()
                val connHeader = if (keepAlive) "Connection: keep-alive\r\nKeep-Alive: timeout=15, max=100" else "Connection: close"

                // Master Playlist (#EXT-X-STREAM-INF): 必须将子播放列表的相对路径改写为本地代理或绝对路径，防止播放器基于 127.0.0.1/stream/ 解析报 404
                if (text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val rewrittenMaster = rewriteMasterPlaylistContent(text, playlistFetchUrl, session.sessionId, proxyPort)
                    val bodyBytes = rewrittenMaster.toByteArray(Charsets.UTF_8)
                    val headerBuilder = StringBuilder()
                    headerBuilder.append("HTTP/1.1 200 OK\r\n")
                    headerBuilder.append("Content-Type: application/vnd.apple.mpegurl\r\n")
                    headerBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
                    headerBuilder.append("$connHeader\r\n\r\n")

                    clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
                    clientOut.write(bodyBytes)
                    clientOut.flush()
                    return keepAlive
                }

                val parsed = BtrLiveCore.parseM3u8(text, playlistFetchUrl)
                val isFmp4 = BtrLiveCore.isFmp4Playlist(text, parsed)

                val outputPlaylist = if (isFmp4) {
                    // 保护 #EXT-X-MAP 初始化分片防止被缓存淘汰
                    parsed.mapUrl?.let { mapU ->
                        val normalizedMap = BtrLiveCore.unwrapProxyUrl(mapU).ifBlank { mapU }
                        session.liveMapUrl = normalizedMap
                        protectMapUrl(normalizedMap)
                    }
                    if (parsed.lastNum > session.liveLastNum.get()) {
                        session.liveLastNum.set(parsed.lastNum)
                        session.liveSpeculativeMisses.set(0)
                    }

                    // 探测候选节点
                    if (parsed.segments.isNotEmpty()) {
                        probeCandidates(session, parsed.segments.first().url)
                    }

                    // 预加载即将消费的分片（最旧优先）+ 预测性预拉取 lastNum + 1
                    pumpPrefetches(session, parsed.segments, session.liveMapUrl)

                    rewritePlaylistContent(text, playlistFetchUrl, session.sessionId, proxyPort)
                } else {
                    // 非 fMP4（如 HLS-TS 或含 ?wsSecret 的 .ts 切片）：将相对路径解析为绝对上游 URL，不送入 fMP4 节点池（避免 403/404）
                    resolvePlaylistUrlsToAbsolute(text, playlistFetchUrl)
                }

                val bodyBytes = outputPlaylist.toByteArray(Charsets.UTF_8)

                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
                headerBuilder.append("Content-Type: application/vnd.apple.mpegurl\r\n")
                headerBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
                headerBuilder.append("$connHeader\r\n\r\n")

                clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
                clientOut.write(bodyBytes)
                clientOut.flush()
                keepAlive
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to proxy live playlist: ${t.message}")
            sendError(clientOut, 502, "Bad Gateway - Live Playlist Unreachable")
            false
        }
    }

    private val EXT_URI_ATTR_REGEX = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)

    private fun resolveUriAttrLine(line: String, baseUrl: String, transform: (String) -> String): String {
        val uriMatch = EXT_URI_ATTR_REGEX.find(line) ?: return line
        val uriStr = uriMatch.groupValues[1]
        if (uriStr.isBlank()) return line
        val rawResolved = try { java.net.URI(baseUrl).resolve(uriStr).toASCIIString() } catch (_: Throwable) { uriStr }
        val resolved = BtrLiveCore.unwrapProxyUrl(rawResolved).ifBlank { rawResolved }
        return line.replaceRange(uriMatch.groups[1]!!.range, transform(resolved))
    }

    private fun rewriteMasterPlaylistContent(
        m3u8Text: String,
        playlistUrl: String,
        sessionId: String,
        proxyPort: Int
    ): String {
        val sb = StringBuilder()
        for (rawLine in m3u8Text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) && line.contains("URI=", ignoreCase = true)) {
                sb.append(
                    resolveUriAttrLine(line, playlistUrl) { resolved ->
                        val encoded = URLEncoder.encode(resolved, "UTF-8")
                        "http://127.0.0.1:$proxyPort/stream/$sessionId/playlist.m3u8?url=$encoded"
                    }
                ).append("\n")
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val rawResolved = try { java.net.URI(playlistUrl).resolve(line).toASCIIString() } catch (_: Throwable) { line }
                val resolved = BtrLiveCore.unwrapProxyUrl(rawResolved).ifBlank { rawResolved }
                val encoded = URLEncoder.encode(resolved, "UTF-8")
                sb.append("http://127.0.0.1:$proxyPort/stream/$sessionId/playlist.m3u8?url=$encoded").append("\n")
            } else {
                sb.append(rawLine).append("\n")
            }
        }
        return sb.toString()
    }

    private fun resolvePlaylistUrlsToAbsolute(m3u8Text: String, playlistUrl: String): String {
        val sb = StringBuilder()
        for (rawLine in m3u8Text.lines()) {
            val line = rawLine.trim()
            if ((line.startsWith("#EXT-X-MAP:", ignoreCase = true) || line.startsWith("#EXT-X-KEY:", ignoreCase = true)) &&
                line.contains("URI=", ignoreCase = true)
            ) {
                sb.append(resolveUriAttrLine(line, playlistUrl) { it }).append("\n")
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val rawResolved = try { java.net.URI(playlistUrl).resolve(line).toASCIIString() } catch (_: Throwable) { line }
                val resolved = BtrLiveCore.unwrapProxyUrl(rawResolved).ifBlank { rawResolved }
                sb.append(resolved).append("\n")
            } else {
                sb.append(rawLine).append("\n")
            }
        }
        return sb.toString()
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
                sb.append(
                    resolveUriAttrLine(line, playlistUrl) { resolved ->
                        val encoded = URLEncoder.encode(resolved, "UTF-8")
                        "http://127.0.0.1:$proxyPort/stream/$sessionId/segment.m4s?url=$encoded"
                    }
                ).append("\n")
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val pathOnly = line.substringBefore('?').substringBefore('#')
                val rawResolved = try { java.net.URI(playlistUrl).resolve(line).toASCIIString() } catch (_: Throwable) { line }
                val resolved = BtrLiveCore.unwrapProxyUrl(rawResolved).ifBlank { rawResolved }
                if (pathOnly.endsWith(".ts", ignoreCase = true)) {
                    sb.append(resolved).append("\n")
                } else {
                    val encoded = URLEncoder.encode(resolved, "UTF-8")
                    val proxied = "http://127.0.0.1:$proxyPort/stream/$sessionId/segment.m4s?url=$encoded"
                    sb.append(proxied).append("\n")
                }
            } else {
                sb.append(rawLine).append("\n")
            }
        }
        return sb.toString()
    }

    private fun enqueuePrefetch(session: BtrMediaSession, url: String, isSpeculative: Boolean) {
        val cleanUrl = BtrLiveCore.unwrapProxyUrl(url).ifBlank { url }
        if (cleanUrl.isBlank() || segmentCache.containsKey(cleanUrl) || inFlightSegments.containsKey(cleanUrl)) return
        if (prefetchQueue.size >= MAX_PREFETCH_QUEUE_DEPTH) return
        if (queuedPrefetchUrls.add(cleanUrl)) {
            prefetchQueue.offer(PrefetchItem(session, cleanUrl, isSpeculative))
        }
    }

    private fun drainPrefetchQueue() {
        val maxConcurrent = if (urgentInflight.get() > 0) 1 else 3
        while (inflightPrefetches.get() < maxConcurrent) {
            val item = prefetchQueue.poll() ?: break
            queuedPrefetchUrls.remove(item.url)
            if (segmentCache.containsKey(item.url)) continue

            if (inflightPrefetches.incrementAndGet() > maxConcurrent) {
                inflightPrefetches.decrementAndGet()
                enqueuePrefetch(item.session, item.url, item.isSpeculative)
                break
            }

            livePrefetchPool.execute {
                try {
                    downloadLiveSegment(
                        session = item.session,
                        url = item.url,
                        urgent = false,
                        allow404 = item.isSpeculative,
                        isSpeculative = item.isSpeculative
                    )
                    if (item.isSpeculative) {
                        item.session.liveSpeculativeMisses.set(0)
                    }
                } catch (e: Throwable) {
                    if (item.isSpeculative && e is SpeculativeNotReadyException) {
                        item.session.liveSpeculativeMisses.incrementAndGet()
                    }
                } finally {
                    inflightPrefetches.decrementAndGet()
                    drainPrefetchQueue()
                }
            }
        }
    }

    private fun pumpPrefetches(session: BtrMediaSession, segments: List<LiveSegment>, mapUrl: String? = null) {
        if (segments.isEmpty() && mapUrl.isNullOrBlank()) return

        // 1. 最高优先级预取 #EXT-X-MAP 初始化分片
        if (!mapUrl.isNullOrBlank() && !segmentCache.containsKey(mapUrl)) {
            enqueuePrefetch(session, mapUrl, isSpeculative = false)
        }

        // 2. 正序（最旧分片优先，对齐 BTR live-hook.js:348 与播放器消费顺序一致）
        var enqueuedReal = 0
        for (seg in segments) {
            val cleanSegUrl = BtrLiveCore.unwrapProxyUrl(seg.url).ifBlank { seg.url }
            if (!segmentCache.containsKey(cleanSegUrl)) {
                enqueuePrefetch(session, cleanSegUrl, isSpeculative = false)
                enqueuedReal++
            }
        }

        // 3. 当列表中已有分片全部命中有序缓存时，预测性预取下一序号分片 lastNum + 1（404 不计节点故障，对冲延迟 3*HEDGE_MS）
        if (enqueuedReal == 0 && segments.isNotEmpty() && session.liveSpeculativeMisses.get() < MAX_SPECULATIVE_MISSES) {
            val nextUrl = BtrLiveCore.guessNextSegmentUrl(segments.last())
            if (!nextUrl.isNullOrBlank()) {
                enqueuePrefetch(session, nextUrl, isSpeculative = true)
            }
        }

        drainPrefetchQueue()
    }

    /**
     * 处理单个直播分片（.m4s）本地代理请求
     */
    fun pipeSegment(
        session: BtrMediaSession,
        requestPath: String,
        headers: Map<String, String>,
        clientOut: OutputStream,
        keepAlive: Boolean = false,
        requestUserAgent: String? = null,
        requestReferer: String? = null
    ): Boolean {
        val rawTargetUrl = extractUrlFromPath(requestPath) ?: run {
            val fileName = requestPath.substringAfterLast('/')
            val base = session.primaryUrl.substringBeforeLast('/')
            "$base/$fileName"
        }
        val targetUrl = BtrLiveCore.unwrapProxyUrl(rawTargetUrl).ifBlank { rawTargetUrl }

        urgentInflight.incrementAndGet()
        return try {
            val segment = downloadLiveSegment(
                session = session,
                url = targetUrl,
                urgent = true,
                allow404 = false,
                isSpeculative = false,
                requestUserAgent = requestUserAgent,
                requestReferer = requestReferer
            )
            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
            headerBuilder.append("Content-Type: ${segment.contentType}\r\n")
            headerBuilder.append("Content-Length: ${segment.bytes.size}\r\n")
            if (keepAlive) {
                headerBuilder.append("Connection: keep-alive\r\nKeep-Alive: timeout=15, max=100\r\n\r\n")
            } else {
                headerBuilder.append("Connection: close\r\n\r\n")
            }

            clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
            clientOut.write(segment.bytes)
            clientOut.flush()
            keepAlive
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to serve live segment: ${t.message}")
            sendError(clientOut, 502, "Bad Gateway - Live Segment Unreachable")
            false
        } finally {
            if (urgentInflight.decrementAndGet() == 0) {
                drainPrefetchQueue()
            }
        }
    }

    private data class FlvRaceWinner(
        val host: String,
        val targetUrl: String,
        val response: Response,
        val call: Call,
        val connectLatencyMs: Double
    )

    /**
     * 处理持续长连接直播流（如 .flv）：多源对冲竞速响应最快上游并无缝透传
     */
    fun pipeLiveFlvStream(
        session: BtrMediaSession,
        headers: Map<String, String>,
        clientOut: OutputStream,
        requestUserAgent: String? = null,
        requestReferer: String? = null
    ): Boolean {
        val originalUrl = BtrLiveCore.unwrapProxyUrl(session.primaryUrl).ifBlank { session.primaryUrl }
        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val hosts = pool.pick(2).ifEmpty {
            val originHost = BiliAcceleratorCore.parseUri(originalUrl)?.host.orEmpty()
            if (originHost.isNotBlank()) listOf(originHost) else listOf("d1--cn-gotcha204.bilivideo.com")
        }

        val effectiveUa = requestUserAgent ?: session.userAgent ?: "BiliApp/7800300"
        val effectiveRef = requestReferer ?: session.referer
        val raceCandidates = hosts.take(2)
        val winnerFuture = CompletableFuture<FlvRaceWinner?>()
        val winnerClaimed = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val primaryFailed = AtomicBoolean(false)
        val wakeLock = Object()
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val failedCount = AtomicInteger(0)

        fun connectCandidate(host: String, isPrimary: Boolean) {
            val targetUrl = BtrLiveCore.swapHost(originalUrl, host)
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", effectiveUa)
                .header("Referer", effectiveRef)
                .build()

            val call = BtrHttpClient.liveStreamClient.newCall(req)
            activeCalls.add(call)
            val startTime = System.currentTimeMillis()

            try {
                val resp = call.execute()
                if (!resp.isSuccessful || resp.body == null) {
                    val code = resp.code
                    runCatching { resp.close() }
                    if (code != 404) {
                        pool.failure(host, 0L)
                    }
                    throw Exception("FLV HTTP $code")
                }
                val connectLatency = maxOf(1.0, (System.currentTimeMillis() - startTime).toDouble())
                if (!timedOut.get() && winnerClaimed.compareAndSet(false, true)) {
                    val candidateWinner = FlvRaceWinner(host, targetUrl, resp, call, connectLatency)
                    if (timedOut.get() || !winnerFuture.complete(candidateWinner)) {
                        runCatching { resp.close() }
                    } else {
                        synchronized(wakeLock) { wakeLock.notifyAll() }
                        activeCalls.forEach { other ->
                            if (other != call && !other.isCanceled()) runCatching { other.cancel() }
                        }
                    }
                } else {
                    runCatching { resp.close() }
                }
            } catch (t: Throwable) {
                if (!call.isCanceled()) {
                    pool.failure(host, 0L)
                }
                if (isPrimary) {
                    primaryFailed.set(true)
                    synchronized(wakeLock) { wakeLock.notifyAll() }
                }
                if (failedCount.incrementAndGet() >= raceCandidates.size && winnerClaimed.compareAndSet(false, true)) {
                    winnerFuture.complete(null)
                }
            } finally {
                activeCalls.remove(call)
            }
        }

        liveWorkerPool.execute { connectCandidate(raceCandidates[0], isPrimary = true) }
        if (raceCandidates.size > 1) {
            liveWorkerPool.execute {
                val deadline = System.currentTimeMillis() + HEDGE_MS
                synchronized(wakeLock) {
                    while (!winnerClaimed.get() && !primaryFailed.get() && !timedOut.get()) {
                        val rem = deadline - System.currentTimeMillis()
                        if (rem <= 0L) break
                        try {
                            wakeLock.wait(rem)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                if (!winnerClaimed.get() && !timedOut.get()) {
                    connectCandidate(raceCandidates[1], isPrimary = false)
                }
            }
        }

        val winner = try {
            winnerFuture.get(SEGMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            timedOut.set(true)
            runCatching { winnerFuture.getNow(null)?.response?.close() }
            null
        }

        if (winner == null) {
            timedOut.set(true)
            synchronized(wakeLock) { wakeLock.notifyAll() }
            activeCalls.forEach { runCatching { it.cancel() } }
            sendError(clientOut, 502, "FLV upstream all hosts failed")
            return false
        }

        return try {
            winner.response.use { resp ->
                val headerBuilder = StringBuilder()
                headerBuilder.append("HTTP/1.1 200 OK\r\n")
                headerBuilder.append("Content-Type: ${resp.header("Content-Type") ?: "video/x-flv"}\r\n")
                headerBuilder.append("Connection: close\r\n\r\n")
                clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
                clientOut.flush()

                RemoteClient.notifyRewrite(
                    RewriteResult(
                        changed = true,
                        originalUrl = originalUrl,
                        finalUrl = winner.targetUrl,
                        originalHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(originalUrl)?.host),
                        targetHost = winner.host,
                        reason = "BTR直播FLV加速 · 首包${winner.connectLatencyMs.toLong()}ms",
                        isPcdn = false,
                        isMcdn = false,
                        segmentKey = "live_flv_${session.sessionId}",
                        isBtr = true,
                        btrAvgLatencyMs = winner.connectLatencyMs.toLong()
                    )
                )

                val inStream = resp.body?.byteStream()
                val buf = ByteArray(32 * 1024)
                var totalBytes = 0L
                val streamStart = System.currentTimeMillis()
                var read = 0
                while (inStream != null && inStream.read(buf).also { read = it } != -1) {
                    clientOut.write(buf, 0, read)
                    clientOut.flush()
                    totalBytes += read
                }
                if (totalBytes > 0L) {
                    val streamElapsed = maxOf(1.0, (System.currentTimeMillis() - streamStart).toDouble())
                    pool.success(winner.host, winner.connectLatencyMs, (totalBytes * 1000.0) / streamElapsed)
                } else {
                    pool.failure(winner.host, 0L)
                }
                // 响应头写明 Connection: close，必须返回 false 让 BtrLocalProxyServer 立即关闭 Socket
                false
            }
        } catch (t: Throwable) {
            Log.i(TAG, "Live FLV stream on ${winner.host} interrupted: ${t.message}")
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
