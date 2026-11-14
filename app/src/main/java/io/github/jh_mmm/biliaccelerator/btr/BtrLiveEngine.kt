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
    private const val MAX_PREFETCH_QUEUE_DEPTH = 12
    private const val MAX_SPECULATIVE_MISSES = 6

    private data class PrefetchItem(
        val session: BtrMediaSession,
        val url: String,
        val isSpeculative: Boolean = false
    )

    private class SpeculativeNotReadyException(code: Int) : Exception("HTTP $code speculative miss")

    // 内存分片缓存: Segment URL -> CachedLiveSegment
    private val segmentCache = ConcurrentHashMap<String, CachedLiveSegment>()
    // #EXT-X-MAP 初始化分片保护集合（防缓存淘汰误删 init 分片）
    private val protectedMapUrls = ConcurrentHashMap.newKeySet<String>()

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
        protectedMapUrls.clear()
        inFlightSegments.clear()
        prefetchQueue.clear()
        queuedPrefetchUrls.clear()
        probedSessions.clear()
    }

    internal fun protectMapUrl(url: String) {
        if (url.isNotBlank()) {
            protectedMapUrls.add(url)
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
            val isProtected = (protectedUrl != null && entry.key == protectedUrl) || protectedMapUrls.contains(entry.key)
            if (!isProtected && now - entry.value.cachedAt > CACHE_TTL_MS) {
                iterator.remove()
            }
        }
        while (segmentCache.size > CACHE_LIMIT) {
            val oldest = segmentCache.entries
                .filter { entry ->
                    val isProtected = (protectedUrl != null && entry.key == protectedUrl) || protectedMapUrls.contains(entry.key)
                    !isProtected
                }
                .minByOrNull { it.value.cachedAt }
                ?: break
            segmentCache.remove(oldest.key)
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
        urgent: Boolean = true,
        allow404: Boolean = false
    ): CachedLiveSegment {
        val normalizedUrl = BtrLiveCore.unwrapProxyUrl(url).ifBlank { url }
        val isMap = protectedMapUrls.contains(normalizedUrl) || session.liveMapUrl == normalizedUrl

        // 1. 优先命中内存缓存（#EXT-X-MAP 初始化分片受永久免过期保护）
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
                downloadFallbackSegment(session, normalizedUrl)
            }
        }

        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val hosts = pool.pick(2).ifEmpty {
            val originHost = BiliAcceleratorCore.parseUri(normalizedUrl)?.host.orEmpty()
            if (originHost.isNotBlank()) listOf(originHost) else listOf("d1--cn-gotcha204.bilivideo.com")
        }

        val hedgeDelay = if (urgent) URGENT_HEDGE_MS else HEDGE_MS
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
                .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
                .header("Referer", session.referer)
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

            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code == 404 && allow404) {
                            hit404Count.incrementAndGet()
                            throw SpeculativeNotReadyException(404)
                        }
                        pool.failure(host, 0L)
                        throw Exception("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw Exception("Empty body")
                    val expectedLength = body.contentLength()
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
                if (t !is SpeculativeNotReadyException && !call.isCanceled()) {
                    pool.failure(host, 0L)
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
            val fb = downloadFallbackSegment(session, normalizedUrl)
            future.complete(fb)
            fb
        } finally {
            inFlightSegments.remove(normalizedUrl)
        }
    }

    private fun downloadFallbackSegment(session: BtrMediaSession, url: String): CachedLiveSegment {
        val normalizedUrl = BtrLiveCore.unwrapProxyUrl(url).ifBlank { url }
        val fallbackReq = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
            .header("Referer", session.referer)
            .build()
        return BtrHttpClient.client.newCall(fallbackReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Fallback request failed: HTTP ${resp.code}")
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
        proxyPort: Int
    ): Boolean {
        val originalUrl = BtrLiveCore.unwrapProxyUrl(session.primaryUrl).ifBlank { session.primaryUrl }
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
                // 对齐 HLS 规范：若为 Master Playlist (#EXT-X-STREAM-INF)，包含的是多码率子播放列表索引，严禁改写为分片
                if (text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val bodyBytes = text.toByteArray(Charsets.UTF_8)
                    val headerBuilder = StringBuilder()
                    headerBuilder.append("HTTP/1.1 200 OK\r\n")
                    headerBuilder.append("Content-Type: application/vnd.apple.mpegurl\r\n")
                    headerBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
                    headerBuilder.append("Connection: keep-alive\r\n\r\n")

                    clientOut.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
                    clientOut.write(bodyBytes)
                    clientOut.flush()
                    return true
                }

                val parsed = BtrLiveCore.parseM3u8(text, playlistFetchUrl)

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
                    probeCandidates(session, parsed.segments.last().url)
                }

                // 预加载接下来即将播放的最新边缘分片 + 预测性预拉取 lastNum + 1
                pumpPrefetches(session, parsed.segments, session.liveMapUrl)

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
                    val rawResolved = try { java.net.URI(playlistUrl).resolve(uriStr).toASCIIString() } catch (_: Throwable) { uriStr }
                    val resolved = BtrLiveCore.unwrapProxyUrl(rawResolved).ifBlank { rawResolved }
                    val encoded = URLEncoder.encode(resolved, "UTF-8")
                    val proxied = "http://127.0.0.1:$proxyPort/stream/$sessionId/segment.m4s?url=$encoded"
                    sb.append(line.replace(uriStr, proxied)).append("\n")
                } else {
                    sb.append(line).append("\n")
                }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                if (line.endsWith(".ts", ignoreCase = true)) {
                    // TS 流不属于 fMP4 分片，按原样保留
                    sb.append(rawLine).append("\n")
                } else {
                    val rawResolved = try { java.net.URI(playlistUrl).resolve(line).toASCIIString() } catch (_: Throwable) { line }
                    val resolved = BtrLiveCore.unwrapProxyUrl(rawResolved).ifBlank { rawResolved }
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
                // 若因并发突发收紧超限，仅对尚未缓存分片重新放回队首或直接跳过当前调度
                enqueuePrefetch(item.session, item.url, item.isSpeculative)
                break
            }

            livePrefetchPool.execute {
                try {
                    downloadLiveSegment(item.session, item.url, urgent = false, allow404 = item.isSpeculative)
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

        // 2. 逆序（从最新直播边缘向前回溯）收集尚未缓存的即将播放分片（对齐 BTR onPlaylist）
        var enqueuedReal = 0
        for (seg in segments.asReversed().take(4)) {
            val cleanSegUrl = BtrLiveCore.unwrapProxyUrl(seg.url).ifBlank { seg.url }
            if (!segmentCache.containsKey(cleanSegUrl)) {
                enqueuePrefetch(session, cleanSegUrl, isSpeculative = false)
                enqueuedReal++
            }
        }

        // 3. 当列表中已有分片全部命中有序缓存时，预测性预取下一序号分片 lastNum + 1（404 不计节点故障）
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
        clientOut: OutputStream
    ): Boolean {
        val rawTargetUrl = extractUrlFromPath(requestPath) ?: run {
            val fileName = requestPath.substringAfterLast('/')
            val base = session.primaryUrl.substringBeforeLast('/')
            "$base/$fileName"
        }
        val targetUrl = BtrLiveCore.unwrapProxyUrl(rawTargetUrl).ifBlank { rawTargetUrl }

        urgentInflight.incrementAndGet()
        return try {
            val segment = downloadLiveSegment(session, targetUrl, urgent = true, allow404 = false)
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
        clientOut: OutputStream
    ): Boolean {
        val originalUrl = BtrLiveCore.unwrapProxyUrl(session.primaryUrl).ifBlank { session.primaryUrl }
        val pool = session.liveHostPool ?: BtrLiveHostPool().also { session.liveHostPool = it }
        val hosts = pool.pick(2).ifEmpty {
            val originHost = BiliAcceleratorCore.parseUri(originalUrl)?.host.orEmpty()
            if (originHost.isNotBlank()) listOf(originHost) else listOf("d1--cn-gotcha204.bilivideo.com")
        }

        val raceCandidates = hosts.take(2)
        val winnerFuture = CompletableFuture<FlvRaceWinner?>()
        val winnerClaimed = AtomicBoolean(false)
        val primaryFailed = AtomicBoolean(false)
        val wakeLock = Object()
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val failedCount = AtomicInteger(0)

        fun connectCandidate(host: String, isPrimary: Boolean) {
            val targetUrl = BtrLiveCore.swapHost(originalUrl, host)
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", session.userAgent ?: "BiliApp/7800300")
                .header("Referer", session.referer)
                .build()

            val call = BtrHttpClient.client.newCall(req)
            activeCalls.add(call)
            val startTime = System.currentTimeMillis()

            try {
                val resp = call.execute()
                if (!resp.isSuccessful || resp.body == null) {
                    runCatching { resp.close() }
                    pool.failure(host, 0L)
                    throw Exception("FLV HTTP ${resp.code}")
                }
                val connectLatency = maxOf(1.0, (System.currentTimeMillis() - startTime).toDouble())
                if (winnerClaimed.compareAndSet(false, true)) {
                    winnerFuture.complete(FlvRaceWinner(host, targetUrl, resp, call, connectLatency))
                    synchronized(wakeLock) { wakeLock.notifyAll() }
                    activeCalls.forEach { other ->
                        if (other != call && !other.isCanceled()) runCatching { other.cancel() }
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
                    while (!winnerClaimed.get() && !primaryFailed.get()) {
                        val rem = deadline - System.currentTimeMillis()
                        if (rem <= 0L) break
                        try {
                            wakeLock.wait(rem)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                if (!winnerClaimed.get()) {
                    connectCandidate(raceCandidates[1], isPrimary = false)
                }
            }
        }

        val winner = try {
            winnerFuture.get(SEGMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            null
        }

        if (winner == null) {
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
                val streamElapsed = maxOf(1.0, (System.currentTimeMillis() - streamStart).toDouble())
                pool.success(winner.host, winner.connectLatencyMs, (totalBytes * 1000.0) / streamElapsed)
                true
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
