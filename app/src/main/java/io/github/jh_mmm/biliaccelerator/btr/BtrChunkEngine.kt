package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import okhttp3.Call
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object BtrChunkEngine {

    private const val TAG = "BiliAccelerator-BTR"
    const val CHUNK_SIZE_AUDIO = 128 * 1024
    const val CHUNK_SIZE_VIDEO = 512 * 1024
    const val AUDIO_MAX_CONCURRENCY = 2
    const val PROBE_PREFIX_BYTES = 64 * 1024

    private const val MAX_PREFETCH_CHUNKS = 8           // 基础滑动窗口参考值
    private const val MAX_BUFFER_BYTES = 16L * 1024 * 1024 // 单流内存缓存上限 16MB，防 OOM 同时避免队头阻塞
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 BiliApp/7800300"
    private const val RESUME_MIN_BYTES = 32 * 1024      // 断点续传拼接最低字节门槛（对齐 BTR 原版）
    private const val CANCEL_SPEED_MIN_BYTES = 48 * 1024L // 被取消竞争请求记录历史带宽门槛（对齐 BTR 48KB）
    private const val DEFAULT_HEDGE_DELAY_MS = 1200L    // 对冲请求延迟触发阈值（毫秒）
    private val CONCURRENCY_LADDER = intArrayOf(2, 4, 6, 8, 12, 16)

    // 全局共享工作线程池：复用线程，允许核心线程超时释放，防止切集时线程暴增引发 OOM（core=16, max=48）
    private val globalWorkerPool = ThreadPoolExecutor(
        16,
        48,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(256)
    ) { r ->
        Thread(r, "BTR-GlobalWorker").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    // 对冲竞速专属独立执行线程池：与主拉取线程隔离，彻底杜绝冲刺期对冲排队饥饿
    private val hedgeWorkerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "BTR-HedgeWorker").apply { isDaemon = true }
    }

    // 全局分发调度线程池
    private val globalDispatcherPool = Executors.newCachedThreadPool { r ->
        Thread(r, "BTR-Dispatcher").apply { isDaemon = true }
    }

    // 活跃网络调用映射表：支持按 streamCallKey 细粒度隔离与按 sessionId 会话级清理
    private val streamActiveCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val sessionStreamKeys = ConcurrentHashMap<String, MutableSet<String>>()

    private fun registerStreamCall(streamCallKey: String, sessionId: String, call: Call) {
        streamActiveCalls.computeIfAbsent(streamCallKey) { ConcurrentHashMap.newKeySet() }.add(call)
        sessionStreamKeys.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(streamCallKey)
    }

    private fun unregisterStreamCall(streamCallKey: String, call: Call) {
        streamActiveCalls[streamCallKey]?.remove(call)
    }

    private fun cancelStreamCalls(streamCallKey: String) {
        streamActiveCalls.remove(streamCallKey)?.forEach { call ->
            runCatching { call.cancel() }
        }
    }

    fun cancelSessionCalls(sessionId: String) {
        sessionStreamKeys.remove(sessionId)?.forEach { streamCallKey ->
            cancelStreamCalls(streamCallKey)
        }
    }

    // 节点 EWMA 测速追踪器（对齐 BTR 与 realzza 动态路由）
    private class HostSpeedInfo(
        @Volatile var avgBps: Double = 0.0,
        @Volatile var lastMeasuredAt: Long = 0L,
        @Volatile var sampleCount: Int = 0
    )

    private val hostSpeeds = ConcurrentHashMap<String, HostSpeedInfo>()
    private const val SPEED_MEASURE_TTL_MS = 90_000L
    private const val MAX_TRACKED_HOSTS = 256

    fun recordHostSpeed(host: String, bytes: Long, latencyMs: Long) {
        if (bytes < 32 * 1024 || latencyMs <= 0) return
        val bps = (bytes * 1000.0) / latencyMs
        val now = System.currentTimeMillis()
        if (hostSpeeds.size > MAX_TRACKED_HOSTS) {
            hostSpeeds.entries.removeIf { now - it.value.lastMeasuredAt > SPEED_MEASURE_TTL_MS }
        }
        val info = hostSpeeds.getOrPut(host) { HostSpeedInfo() }
        synchronized(info) {
            if (now - info.lastMeasuredAt > SPEED_MEASURE_TTL_MS || info.avgBps <= 0.0) {
                info.avgBps = bps
            } else {
                // EWMA 衰减平滑：70% 历史 + 30% 新测速
                info.avgBps = info.avgBps * 0.7 + bps * 0.3
            }
            info.lastMeasuredAt = now
            info.sampleCount++
        }
    }

    fun getHostSpeed(host: String): Double {
        val info = hostSpeeds[host] ?: return 0.0
        val now = System.currentTimeMillis()
        return if (now - info.lastMeasuredAt < SPEED_MEASURE_TTL_MS) info.avgBps else 0.0
    }

    private val cdnPenalties = ConcurrentHashMap<String, Long>()

    private fun penalizeHost(host: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (cdnPenalties.size > MAX_TRACKED_HOSTS) {
            cdnPenalties.entries.removeIf { it.value <= now }
        }
        cdnPenalties[host] = now + durationMs
    }

    data class ChunkTask(
        val index: Long,
        val startByte: Long,
        val endByte: Long,
        @Volatile var data: ByteArray? = null,
        @Volatile var isSuccess: Boolean = false,
        @Volatile var isFailed: Boolean = false,
        @Volatile var errorMessage: String? = null,
        @Volatile var receivedBytes: Int = 0,
        @Volatile var prefixData: ByteArray? = null
    ) {
        private var prefixOwnerId: Long = 0L
        private var prefixOwnerFailed: Boolean = false
        private var livePrefixOut: ByteArrayOutputStream? = null

        fun tryClaimPrefixProducer(attemptId: Long): Boolean {
            synchronized(this) {
                if (isSuccess || prefixData != null) return false
                if (prefixOwnerId == 0L || (prefixOwnerFailed && receivedBytes == 0)) {
                    prefixOwnerId = attemptId
                    prefixOwnerFailed = false
                    if (livePrefixOut == null) {
                        val initCap = minOf(512 * 1024, (endByte - startByte + 1).toInt().coerceAtLeast(1024))
                        livePrefixOut = ByteArrayOutputStream(initCap)
                    }
                    return true
                }
                return prefixOwnerId == attemptId
            }
        }

        fun appendLivePrefix(attemptId: Long, buf: ByteArray, len: Int, expectedTotal: Int) {
            if (len <= 0) return
            synchronized(this) {
                if (prefixOwnerId != attemptId || isSuccess) return
                val out = livePrefixOut ?: return
                val maxAppend = minOf(len, (expectedTotal - 1) - out.size())
                if (maxAppend > 0) {
                    out.write(buf, 0, maxAppend)
                    receivedBytes = out.size()
                }
            }
        }

        fun releasePrefixProducer(attemptId: Long, keepPartial: Boolean) {
            synchronized(this) {
                if (prefixOwnerId == attemptId) {
                    prefixOwnerFailed = true
                    if (!keepPartial || receivedBytes < RESUME_MIN_BYTES) {
                        prefixOwnerId = 0L
                        livePrefixOut?.reset()
                        receivedBytes = 0
                    }
                }
            }
        }

        fun takePrefixSnapshot(minResumeBytes: Int, expectedTotal: Int): ByteArray? {
            synchronized(this) {
                if (isSuccess) return null
                val out = livePrefixOut
                if (out != null && out.size() in minResumeBytes until expectedTotal) {
                    return out.toByteArray()
                }
                val external = prefixData
                if (external != null && receivedBytes in minResumeBytes until expectedTotal && external.size >= receivedBytes) {
                    return if (external.size == receivedBytes) external else external.copyOf(receivedBytes)
                }
                return null
            }
        }

        fun clearPrefixBuffersOnWin() {
            synchronized(this) {
                prefixData = null
                livePrefixOut = null
                prefixOwnerId = 0L
                prefixOwnerFailed = false
            }
        }

        fun resetPrefix() {
            synchronized(this) {
                prefixData = null
                livePrefixOut = null
                prefixOwnerId = 0L
                prefixOwnerFailed = false
                receivedBytes = 0
            }
        }

        fun releaseBuffers() {
            synchronized(this) {
                data = null
                prefixData = null
                livePrefixOut = null
                prefixOwnerId = 0L
                receivedBytes = 0
            }
        }
    }

    /**
     * 自适应动态并发控制器（对齐 BTR createAutoConcurrency 阶梯试探 + 412/429 退避）：
     * - 维护 [2, 4, 6, 8, 12, 16] 并发阶梯
     * - 周期性测算吞吐量决定升档或回退，遇上游限流 (412/429) 立即降档并静默休眠
     */
    internal class DynamicConcurrencyController(
        initialConcurrency: Int,
        private val autoScale: Boolean,
        private val minConcurrency: Int = 2,
        private val maxConcurrency: Int = 16,
        initialNowMs: Long = System.currentTimeMillis()
    ) {
        private val lock = Any()
        private var ladderIdx: Int
        @Volatile
        var currentConcurrency: Int
            private set
        @Volatile
        var peakConcurrency: Int
            private set

        private var intervalStartMs: Long = initialNowMs
        private var intervalBytes: Long = 0L
        private var bestBps: Double = 0.0
        private var lastTrialDirection: Int = 0
        private var prevIndexBeforeTrial: Int = 0
        private var pushbackCooldownUntilMs: Long = 0L

        init {
            val clampedInitial = initialConcurrency.coerceIn(minConcurrency, maxConcurrency)
            val idx = CONCURRENCY_LADDER.indexOfFirst { it >= clampedInitial }
                .let { if (it == -1) CONCURRENCY_LADDER.lastIndex else it }
            ladderIdx = idx
            currentConcurrency = CONCURRENCY_LADDER[idx].coerceIn(minConcurrency, maxConcurrency)
            peakConcurrency = currentConcurrency
        }

        fun recordProgress(bytes: Long, nowMs: Long = System.currentTimeMillis()) {
            if (!autoScale || bytes <= 0L) return
            synchronized(lock) {
                intervalBytes += bytes
                val elapsed = nowMs - intervalStartMs
                if (elapsed < 1000L || intervalBytes < 256 * 1024L) return

                val currentBps = (intervalBytes * 1000.0) / elapsed.coerceAtLeast(1L)
                intervalStartMs = nowMs
                intervalBytes = 0L

                if (nowMs < pushbackCooldownUntilMs) {
                    if (bestBps <= 0.0) bestBps = currentBps
                    return
                }

                if (lastTrialDirection > 0) {
                    if (currentBps >= bestBps * 1.08) {
                        bestBps = currentBps
                        lastTrialDirection = 0
                    } else {
                        ladderIdx = prevIndexBeforeTrial
                        currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                        lastTrialDirection = 0
                        pushbackCooldownUntilMs = nowMs + 6_000L
                    }
                    return
                }

                if (currentBps > bestBps) {
                    bestBps = currentBps
                }

                if (ladderIdx + 1 < CONCURRENCY_LADDER.size && CONCURRENCY_LADDER[ladderIdx + 1] <= maxConcurrency) {
                    prevIndexBeforeTrial = ladderIdx
                    ladderIdx++
                    currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                    if (currentConcurrency > peakConcurrency) peakConcurrency = currentConcurrency
                    lastTrialDirection = 1
                }
            }
        }

        fun onPushback(nowMs: Long = System.currentTimeMillis()): Int {
            synchronized(lock) {
                if (ladderIdx > 0) {
                    ladderIdx--
                }
                currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                lastTrialDirection = 0
                pushbackCooldownUntilMs = nowMs + 15_000L
                intervalStartMs = nowMs
                intervalBytes = 0L
                return currentConcurrency
            }
        }
    }

    private class StreamStatsTracker(
        val sessionId: String,
        val primaryHost: String,
        @Volatile var concurrency: Int,
        val proxyPort: Int,
        val isEpisode: Boolean = false
    ) {
        val totalChunks = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val totalLatency = AtomicLong(0L)
        val hitHosts = ConcurrentHashMap.newKeySet<String>()

        fun recordChunk(host: String, bytes: Long, latencyMs: Long) {
            totalChunks.incrementAndGet()
            totalBytes.addAndGet(bytes)
            totalLatency.addAndGet(latencyMs)
            hitHosts.add(host)
        }

        fun finishAndReport() {
            val count = totalChunks.get()
            if (count == 0) return

            val bytes = totalBytes.get()
            val avgLatency = totalLatency.get() / count
            val mbStr = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))

            val prefix = when {
                isEpisode -> "BTR剧集加速(兼容模式)"
                sessionId.startsWith("audio_") || primaryHost.contains("audio") -> "BTR音频优化"
                else -> "BTR并发加速"
            }
            val summaryReason = "$prefix · ${count}分片(${mbStr}MB) · ${hitHosts.size}个CDN · 均时${avgLatency}ms"

            val result = RewriteResult(
                changed = true,
                originalUrl = "",
                finalUrl = "",
                originalHost = primaryHost,
                targetHost = "127.0.0.1:$proxyPort (${concurrency}线程)",
                reason = summaryReason,
                isPcdn = false,
                isMcdn = false,
                segmentKey = "btr_stream_$sessionId",
                isBtr = true,
                btrChunks = count,
                btrBytes = bytes,
                btrConcurrency = concurrency,
                btrCdnCount = hitHosts.size,
                btrAvgLatencyMs = avgLatency
            )
            RemoteClient.notifyRewrite(result)
        }
    }

    /**
     * 自适应切片尺寸计算：
     * 根据视频总长度（清晰度与体积表征）动态调整切片大小：
     * - 音频流保持 128KB 极低延时
     * - < 15MB: 256KB (小文件/低码率，保证极低首帧起播时延)
     * - 15MB ~ 60MB: 512KB (720P/1080P 常规基准切片)
     * - 60MB ~ 250MB: 1024KB (1MB) (1080P 60帧/高码率视频)
     * - >= 250MB: 2048KB (2MB) (4K/8K/高码率长视频，大幅减少高吞吐下的并发 HTTP 请求与头部握手开销)
     */
    fun calculateDynamicChunkSize(
        totalLength: Long,
        baseChunkSizeKb: Int,
        autoChunkSize: Boolean,
        isAudio: Boolean
    ): Long {
        if (isAudio) return 128 * 1024L
        val baseBytes = (baseChunkSizeKb.coerceIn(128, 2048) * 1024).toLong()
        if (!autoChunkSize) return baseBytes
        return when {
            totalLength <= 0 -> baseBytes
            totalLength < 15 * 1024 * 1024L -> 256 * 1024L
            totalLength < 60 * 1024 * 1024L -> 512 * 1024L
            totalLength < 250 * 1024 * 1024L -> 1024 * 1024L
            else -> 2048 * 1024L // 4K/8K/超高码率大视频自适应切片提升至 2048KB (2MB)，大幅减少高吞吐握手开销
        }
    }

    fun pipeStream(
        session: BtrMediaSession,
        requestedStart: Long = 0L,
        requestedEnd: Long? = null, // null 表示直至文件尾部
        isHeadOnly: Boolean = false,
        clientOut: OutputStream,
        concurrency: Int = 6,
        chunkSizeKb: Int = 512,
        autoConcurrency: Boolean = true,
        autoChunkSize: Boolean = true,
        proxyPort: Int = -1,
        parsedRange: ParsedRange = ParsedRange(
            hasRange = requestedStart > 0 || requestedEnd != null,
            start = requestedStart,
            end = requestedEnd
        ),
        keepAlive: Boolean = false
    ): Boolean {
        val isAudio = session.isAudio || BiliAcceleratorCore.isAudioTrack(session.primaryUrl)
        val requestedBytes = if (parsedRange.hasRange && parsedRange.end != null && parsedRange.end >= parsedRange.start) {
            parsedRange.end - parsedRange.start + 1
        } else -1L

        val baseProbeSize = PROBE_PREFIX_BYTES.toLong()
        val initialProbeChunkSize = if (requestedBytes in 1 until baseProbeSize) {
            requestedBytes
        } else {
            baseProbeSize
        }

        val isEpisode = session.isEpisode
        val minCon = 2
        val maxCon = when {
            isAudio -> AUDIO_MAX_CONCURRENCY
            isEpisode -> minOf(concurrency, 6).coerceIn(2, 8)
            else -> 16
        }
        val initialCon = when {
            isAudio -> AUDIO_MAX_CONCURRENCY
            isEpisode -> maxCon
            autoConcurrency -> maxOf(concurrency, 6).coerceIn(minCon, maxCon)
            else -> concurrency.coerceIn(minCon, maxCon)
        }
        val useAuto = !isAudio && !isEpisode && autoConcurrency
        val concurrencyController = DynamicConcurrencyController(
            initialConcurrency = initialCon,
            autoScale = useAuto,
            minConcurrency = minCon,
            maxConcurrency = if (useAuto) maxCon else initialCon
        )

        val candidateUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
        val primaryHost = BiliAcceleratorCore.cleanHost(extractHostname(session.primaryUrl))
        val streamCallKey = "${session.sessionId}_${System.nanoTime()}"

        val statsTracker = StreamStatsTracker(session.sessionId, primaryHost, initialCon, proxyPort, isEpisode = isEpisode)

        try {
            val hasCachedMetadata = session.totalLength > 0
            val totalLength: Long
            val contentType: String
            var firstChunk: ByteArray? = null
            var probeServingHost = primaryHost
            var probeLatencyMs = 20L
            var currentOffset: Long
            val effectiveStart: Long
            val effectiveEnd: Long?
            val actualEnd: Long

            if (parsedRange.isSuffix) {
                // Suffix Range 模式: bytes=-suffixLength (如 bytes=-500)
                if (hasCachedMetadata) {
                    totalLength = session.totalLength
                    contentType = session.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                } else {
                    val probeResult = probeMedia(candidateUrls, session, 0L, initialProbeChunkSize, concurrencyController)
                    if (probeResult == null) {
                        sendHttpError(clientOut, 502, "Bad Gateway - Upstream CDN Unreachable")
                        return false
                    }
                    if (probeResult.isNonRange) {
                        return pipeNonRangeStream(candidateUrls.firstOrNull() ?: session.primaryUrl, session, clientOut, statsTracker)
                    }
                    totalLength = probeResult.totalLength
                    contentType = probeResult.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                    probeServingHost = probeResult.servingHost.ifBlank { primaryHost }
                    probeLatencyMs = probeResult.probeLatencyMs
                    if (totalLength > 0) {
                        session.totalLength = totalLength
                        session.contentType = contentType
                    }
                    if (totalLength > 0 && probeResult.firstChunkData != null && totalLength <= probeResult.firstChunkData.size) {
                        firstChunk = probeResult.firstChunkData
                    }
                }

                effectiveStart = if (totalLength > 0) maxOf(0L, totalLength - parsedRange.suffixLength) else 0L
                effectiveEnd = if (totalLength > 0) totalLength - 1L else null
                actualEnd = effectiveEnd ?: -1L

                if (firstChunk != null && effectiveStart < firstChunk.size) {
                    val offsetInFirst = effectiveStart.toInt()
                    val len = (actualEnd - effectiveStart + 1).toInt()
                    firstChunk = firstChunk.copyOfRange(offsetInFirst, minOf(firstChunk.size, offsetInFirst + len))
                    currentOffset = effectiveStart + firstChunk.size
                } else {
                    firstChunk = null
                    currentOffset = effectiveStart
                }
            } else {
                effectiveStart = parsedRange.start
                effectiveEnd = parsedRange.end

                if (hasCachedMetadata && effectiveStart > 0) {
                    // P0: Seek 探针跳过优化！
                    totalLength = session.totalLength
                    contentType = session.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                    firstChunk = null
                    currentOffset = effectiveStart
                    actualEnd = when {
                        effectiveEnd != null -> minOf(effectiveEnd, totalLength - 1L)
                        totalLength > 0 -> totalLength - 1L
                        else -> -1L
                    }
                } else {
                    // 首片 64KB 竞速探针 (多源并发对冲探针，极速返回 HTTP 响应头)
                    val probeResult = probeMedia(candidateUrls, session, effectiveStart, initialProbeChunkSize, concurrencyController)
                    if (probeResult == null) {
                        sendHttpError(clientOut, 502, "Bad Gateway - Upstream CDN Unreachable")
                        return false
                    }

                    if (probeResult.isNonRange) {
                        return pipeNonRangeStream(candidateUrls.firstOrNull() ?: session.primaryUrl, session, clientOut, statsTracker)
                    }

                    totalLength = probeResult.totalLength
                    contentType = probeResult.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                    probeServingHost = probeResult.servingHost.ifBlank { primaryHost }
                    probeLatencyMs = probeResult.probeLatencyMs
                    if (totalLength > 0) {
                        session.totalLength = totalLength
                        session.contentType = contentType
                    }

                    firstChunk = probeResult.firstChunkData
                    actualEnd = when {
                        effectiveEnd != null -> minOf(effectiveEnd, if (totalLength > 0) totalLength - 1L else effectiveEnd)
                        totalLength > 0 -> totalLength - 1L
                        else -> -1L
                    }
                    currentOffset = effectiveStart + (firstChunk?.size ?: 0)
                }
            }

            // 根据探针测得或已缓存的媒体总长度，自适应计算后续分片拉取的最佳 ChunkSize
            val activeChunkSize = calculateDynamicChunkSize(
                totalLength = totalLength,
                baseChunkSizeKb = chunkSizeKb,
                autoChunkSize = autoChunkSize,
                isAudio = isAudio
            )

            // 2. 构造并发送 HTTP 206 Partial Content (或 200 OK) 响应头给播放器
            val isPartial = parsedRange.hasRange || effectiveStart > 0 || effectiveEnd != null
            val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val sb = StringBuilder(statusLine)
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Accept-Ranges: bytes\r\n")

            // HTTP Keep-Alive 支持：当客户端支持长连接且 Content-Length 明确时开启长连接复用
            if (keepAlive && totalLength > 0 && actualEnd >= effectiveStart) {
                sb.append("Connection: keep-alive\r\n")
                sb.append("Keep-Alive: timeout=15, max=100\r\n")
            } else {
                sb.append("Connection: close\r\n")
            }

            if (totalLength > 0 && actualEnd >= effectiveStart) {
                val contentLength = actualEnd - effectiveStart + 1
                sb.append("Content-Length: $contentLength\r\n")
                if (isPartial) {
                    sb.append("Content-Range: bytes $effectiveStart-$actualEnd/$totalLength\r\n")
                }
            } else if (!isPartial && totalLength > 0) {
                sb.append("Content-Length: $totalLength\r\n")
            }
            sb.append("\r\n")

            clientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            clientOut.flush()

            if (isHeadOnly) return true

            // 3. 写出首片探测数据（仅在未跳过探针且有首片时）
            if (firstChunk != null && firstChunk.isNotEmpty()) {
                val bytesToWrite = if (actualEnd >= effectiveStart) {
                    val needed = (actualEnd - effectiveStart + 1).toInt()
                    if (needed < firstChunk.size) firstChunk.copyOf(needed) else firstChunk
                } else firstChunk

                clientOut.write(bytesToWrite)
                clientOut.flush()
                statsTracker.recordChunk(probeServingHost, bytesToWrite.size.toLong(), probeLatencyMs)
                concurrencyController.recordProgress(bytesToWrite.size.toLong())
                currentOffset = effectiveStart + bytesToWrite.size
            }

            // 计算剩余字节范围
            if (actualEnd < effectiveStart || currentOffset > actualEnd) {
                // 已全部写出或未知长度单片传输完成
                return true
            }

            // 4. 并发切片任务切分与自适应并发流水线（工作线程数与缓存窗口解耦，防止单块重试引起全局阻塞）
            val chunkTasks = mutableListOf<ChunkTask>()
            var offset = currentOffset
            var taskIdx = 0L
            while (offset <= actualEnd) {
                val end = minOf(offset + activeChunkSize - 1, actualEnd)
                chunkTasks.add(ChunkTask(taskIdx++, offset, end))
                offset = end + 1
            }

            val totalTasks = chunkTasks.size
            val taskMap = ConcurrentHashMap<Long, ChunkTask>()
            val completionLock = Object()
            val workerGate = Object()
            val activeWorkers = AtomicInteger(0)
            val isClientAborted = AtomicBoolean(false)
            val deliveredIndex = AtomicLong(0L)

            val maxWindowByBytes = (MAX_BUFFER_BYTES / activeChunkSize.coerceAtLeast(64 * 1024L))
                .toInt()
                .coerceIn(MAX_PREFETCH_CHUNKS, 32)

            // 调度分发协作者在 globalDispatcherPool 异步执行
            val dispatchFuture = globalDispatcherPool.submit {
                var dispatchIdx = 0
                while (dispatchIdx < totalTasks && !isClientAborted.get()) {
                    val activeLimit = concurrencyController.currentConcurrency
                    val maxBufferWindow = minOf(maxWindowByBytes, maxOf(12, activeLimit * 2))

                    synchronized(workerGate) {
                        while (!isClientAborted.get() &&
                            (activeWorkers.get() >= concurrencyController.currentConcurrency ||
                                (dispatchIdx - deliveredIndex.get()) >= maxBufferWindow)
                        ) {
                            try {
                                workerGate.wait(25)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                    if (isClientAborted.get()) break

                    val task = chunkTasks[dispatchIdx]
                    taskMap[task.index] = task
                    activeWorkers.incrementAndGet()

                    try {
                        globalWorkerPool.execute {
                            try {
                                if (!isClientAborted.get()) {
                                    fetchChunkWithFailover(
                                        task = task,
                                        urls = candidateUrls,
                                        session = session,
                                        statsTracker = statsTracker,
                                        concurrencyController = concurrencyController,
                                        isClientAborted = isClientAborted,
                                        streamCallKey = streamCallKey
                                    )
                                }
                            } finally {
                                activeWorkers.decrementAndGet()
                                synchronized(workerGate) {
                                    workerGate.notifyAll()
                                }
                                synchronized(completionLock) {
                                    completionLock.notifyAll()
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        activeWorkers.decrementAndGet()
                        task.isFailed = true
                        task.errorMessage = "Worker pool rejection: ${t.message}"
                        synchronized(workerGate) {
                            workerGate.notifyAll()
                        }
                        synchronized(completionLock) {
                            completionLock.notifyAll()
                        }
                    }
                    dispatchIdx++
                }
            }

            try {
                // 严格按序 (0, 1, 2...) 写出分片到客户端 Socket
                var nextDeliverIndex = 0L
                while (nextDeliverIndex < totalTasks && !isClientAborted.get()) {
                    val currentTask = taskMap[nextDeliverIndex]
                    if (currentTask == null || (currentTask.data == null && !currentTask.isFailed)) {
                        synchronized(completionLock) {
                            val task = taskMap[nextDeliverIndex]
                            if (!isClientAborted.get() && (task == null || (task.data == null && !task.isFailed))) {
                                completionLock.wait(400)
                            }
                        }
                        continue
                    }

                    // P0: 杜绝死等！若分片彻底拉取失败，立即中断交付并触发播放器原生自愈/切集
                    if (currentTask.isFailed || currentTask.data == null) {
                        Log.e(TAG, "Chunk task #$nextDeliverIndex failed (${currentTask.errorMessage}). Aborting stream delivery to trigger player recovery.")
                        isClientAborted.set(true)
                        break
                    }

                    // 写出分片
                    try {
                        val data = currentTask.data
                        if (data != null) {
                            clientOut.write(data)
                            clientOut.flush()
                            concurrencyController.recordProgress(data.size.toLong())
                            statsTracker.concurrency = concurrencyController.peakConcurrency
                        }
                        // 及时置空，释放堆内存并同步已交付游标
                        currentTask.releaseBuffers()
                        taskMap.remove(nextDeliverIndex)
                        nextDeliverIndex++
                        deliveredIndex.set(nextDeliverIndex)
                        synchronized(workerGate) {
                            workerGate.notifyAll()
                        }
                    } catch (e: SocketException) {
                        // 客户端断开连接 (例如用户拖动进度条 Seek 或切集)
                        Log.i(TAG, "Client disconnected (Seek or stop). Session=${session.sessionId}")
                        isClientAborted.set(true)
                        break
                    }
                }
                return (nextDeliverIndex >= totalTasks && !isClientAborted.get())
            } finally {
                isClientAborted.set(true)
                synchronized(workerGate) {
                    workerGate.notifyAll()
                }
                dispatchFuture.cancel(true)
                cancelStreamCalls(streamCallKey)
                taskMap.clear()
            }
        } finally {
            statsTracker.finishAndReport()
        }
    }

    private data class ProbeResult(
        val totalLength: Long,
        val contentLength: Long,
        val contentType: String?,
        val firstChunkData: ByteArray?,
        val servingHost: String,
        val probeLatencyMs: Long,
        val isNonRange: Boolean = false
    )

    private data class ContentRangeInfo(
        val start: Long,
        val end: Long?,
        val total: Long?
    )

    private val attemptSeq = AtomicLong(0L)
    private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+|\*)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    private fun parseContentRange(cr: String?): ContentRangeInfo? {
        if (cr.isNullOrBlank()) return null
        val full = CONTENT_RANGE_REGEX.find(cr)
        if (full != null) {
            val start = full.groupValues[1].toLongOrNull() ?: return null
            val end = full.groupValues[2].toLongOrNull()
            val total = full.groupValues[3].toLongOrNull()
            return ContentRangeInfo(start, end, total)
        }
        val fallbackStart = parseRangeStart(cr) ?: return null
        val fallbackTotal = cr.substringAfterLast('/', "").trim().toLongOrNull()
        return ContentRangeInfo(fallbackStart, null, fallbackTotal)
    }

    internal fun isValidContentRange(
        code: Int,
        cr: String?,
        expectedStart: Long,
        session: BtrMediaSession
    ): Boolean {
        if (code != 206) return true
        if (cr.isNullOrBlank()) return true
        val info = parseContentRange(cr) ?: return false
        if (info.start != expectedStart) return false
        if (info.total != null && session.totalLength > 0 && info.total != session.totalLength) {
            return false
        }
        return true
    }

    private fun pipeNonRangeStream(
        url: String,
        session: BtrMediaSession,
        clientOut: OutputStream,
        statsTracker: StreamStatsTracker
    ): Boolean {
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
        val request = Request.Builder()
            .url(url)
            .header("Referer", session.referer)
            .header("User-Agent", ua)
            .header("Connection", "close")
            .build()

        return try {
            okClient.newCall(request).execute().use { response ->
                val code = response.code
                val cl = response.body?.contentLength() ?: -1L
                val ct = response.header("Content-Type") ?: if (session.isAudio) "audio/mp4" else "video/mp4"

                val sb = StringBuilder("HTTP/1.1 $code OK\r\n")
                sb.append("Content-Type: $ct\r\n")
                if (cl > 0) sb.append("Content-Length: $cl\r\n")
                sb.append("Connection: close\r\n\r\n")
                clientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
                clientOut.flush()

                var totalBytes = 0L
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(PROBE_PREFIX_BYTES)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        clientOut.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
                clientOut.flush()
                statsTracker.recordChunk(host, totalBytes, System.currentTimeMillis() - startTime)
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun executeSingleProbe(
        url: String,
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long,
        okClient: okhttp3.OkHttpClient,
        primaryHost: String,
        concurrencyController: DynamicConcurrencyController?,
        activeCalls: MutableSet<Call>?,
        isDecided: AtomicBoolean?
    ): ProbeResult? {
        if (isDecided?.get() == true) return null
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val endOffset = startOffset + chunkSize - 1
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$startOffset-$endOffset")
            .header("Referer", session.referer)
            .header("User-Agent", session.userAgent ?: DEFAULT_USER_AGENT)
            .header("Connection", "keep-alive")
            .build()

        val call = okClient.newCall(request)
        activeCalls?.add(call)
        return try {
            call.execute().use { response ->
                if (isDecided?.get() == true) return null
                val code = response.code
                if (code == 200 || code == 206) {
                    val cr = response.header("Content-Range")
                    val isNonRange = (code == 200 && cr == null)
                    if (!isNonRange && !isValidContentRange(code, cr, startOffset, session)) {
                        session.banList.recordHttpFailure(url, host, 410)
                        if (host == primaryHost) {
                            reportFailover(host, "首片 Content-Range 异常 ($cr)", session.sessionId)
                        }
                        return null
                    }
                    val crInfo = parseContentRange(cr)
                    val cl = response.body?.contentLength() ?: -1L
                    val total = if (isNonRange) cl else (crInfo?.total ?: cr?.substringAfterLast("/")?.trim()?.toLongOrNull() ?: -1L)
                    val ct = response.header("Content-Type")?.takeIf { it.isNotBlank() }

                    val data = if (isNonRange) ByteArray(0) else (response.body?.bytes() ?: ByteArray(0))
                    if (!isNonRange && data.isEmpty()) {
                        session.banList.recordTransportFailure(host, 20_000L)
                        penalizeHost(host, 20_000L)
                        if (host == primaryHost) {
                            reportFailover(host, "首片探测返回 0KB 空数据熔断", session.sessionId)
                        }
                        return null
                    }
                    val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                    session.banList.recordSuccess(url, host)
                    if (data.isNotEmpty()) {
                        recordHostSpeed(host, data.size.toLong(), latency)
                    }
                    ProbeResult(total, cl, ct, data, host, latency, isNonRange = isNonRange)
                } else {
                    val isNodeFault = session.banList.recordHttpFailure(url, host, code)
                    if (code == 412 || code == 429) {
                        concurrencyController?.onPushback()
                    }
                    if (isNodeFault) {
                        val penaltyTime = if (code == 429) 45_000L else if (code == 412) 30_000L else 20_000L
                        penalizeHost(host, penaltyTime)
                    }
                    if (host == primaryHost) {
                        reportFailover(host, "HTTP $code 错误", session.sessionId)
                    }
                    null
                }
            }
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                session.banList.recordTransportFailure(host, 15_000L)
                penalizeHost(host, 12_000L)
                if (host == primaryHost) {
                    reportFailover(host, e.message ?: "连接超时", session.sessionId)
                }
            }
            null
        } finally {
            activeCalls?.remove(call)
        }
    }

    private fun probeMedia(
        urls: List<String>,
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long,
        concurrencyController: DynamicConcurrencyController? = null
    ): ProbeResult? {
        val sortedUrls = sortUrlsByHealthAndSpeed(urls, 0L, session)
        if (sortedUrls.isEmpty()) return null
        val primaryHost = extractHostname(session.primaryUrl)
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.probeClient

        val raceCandidates = sortedUrls.take(3)
        val fallbackCandidates = sortedUrls.drop(3)

        if (raceCandidates.size == 1) {
            executeSingleProbe(
                raceCandidates[0], session, startOffset, chunkSize, okClient, primaryHost,
                concurrencyController, activeCalls = null, isDecided = null
            )?.let { return it }
        } else {
            val isDecided = AtomicBoolean(false)
            val resultFuture = CompletableFuture<ProbeResult?>()
            val activeCalls = ConcurrentHashMap.newKeySet<Call>()
            val failedCount = AtomicInteger(0)
            val staggerLock = Object()
            val delaysMs = longArrayOf(0L, 120L, 300L)

            for (i in raceCandidates.indices) {
                val url = raceCandidates[i]
                val delayMs = delaysMs.getOrElse(i) { 300L }
                hedgeWorkerPool.execute {
                    if (delayMs > 0L) {
                        val deadline = System.currentTimeMillis() + delayMs
                        synchronized(staggerLock) {
                            while (!isDecided.get() && failedCount.get() < i) {
                                val rem = deadline - System.currentTimeMillis()
                                if (rem <= 0L) break
                                try {
                                    staggerLock.wait(rem)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                    }
                    if (isDecided.get()) return@execute

                    val probeRes = executeSingleProbe(
                        url, session, startOffset, chunkSize, okClient, primaryHost,
                        concurrencyController, activeCalls, isDecided
                    )
                    if (probeRes != null && isDecided.compareAndSet(false, true)) {
                        resultFuture.complete(probeRes)
                        synchronized(staggerLock) { staggerLock.notifyAll() }
                        activeCalls.forEach { call -> runCatching { call.cancel() } }
                    } else if (!isDecided.get()) {
                        val failures = failedCount.incrementAndGet()
                        synchronized(staggerLock) { staggerLock.notifyAll() }
                        if (failures >= raceCandidates.size && isDecided.compareAndSet(false, true)) {
                            resultFuture.complete(null)
                        }
                    }
                }
            }

            try {
                val winner = resultFuture.get(15, TimeUnit.SECONDS)
                if (winner != null) return winner
            } catch (_: Exception) {
                isDecided.set(true)
                activeCalls.forEach { call -> runCatching { call.cancel() } }
            }
        }

        for (url in fallbackCandidates) {
            executeSingleProbe(
                url, session, startOffset, chunkSize, okClient, primaryHost,
                concurrencyController, activeCalls = null, isDecided = null
            )?.let { return it }
        }
        return null
    }

    private fun fetchChunkWithFailover(
        task: ChunkTask,
        urls: List<String>,
        session: BtrMediaSession,
        statsTracker: StreamStatsTracker,
        concurrencyController: DynamicConcurrencyController?,
        isClientAborted: AtomicBoolean,
        streamCallKey: String
    ) {
        if (isClientAborted.get()) return

        val rotationIndex = session.globalChunkIndex.getAndIncrement()
        val tryUrls = sortUrlsByHealthAndSpeed(urls, rotationIndex, session)
        if (tryUrls.isEmpty()) {
            task.isFailed = true
            task.errorMessage = "No available CDN urls"
            return
        }

        val primaryUrl = tryUrls.first()
        val hedgeUrl = if (tryUrls.size > 1) tryUrls[1] else null
        val hedgeDelayMs = if (task.index <= 1L) 800L else DEFAULT_HEDGE_DELAY_MS

        val hasWinner = AtomicBoolean(false)
        val primaryFinished = AtomicBoolean(false)
        val hedgeLock = Object()
        val primaryCallRef = java.util.concurrent.atomic.AtomicReference<Call?>(null)
        val hedgeCallRef = java.util.concurrent.atomic.AtomicReference<Call?>(null)

        // 启动对冲并发监测任务（主请求慢/假死超阈值，或主请求提前失败时立即唤醒第二路接力）
        val hedgeFuture = if (hedgeUrl != null && !isClientAborted.get()) {
            hedgeWorkerPool.submit {
                try {
                    val deadline = System.currentTimeMillis() + hedgeDelayMs
                    synchronized(hedgeLock) {
                        while (!hasWinner.get() && !primaryFinished.get() && !isClientAborted.get()) {
                            val rem = deadline - System.currentTimeMillis()
                            if (rem <= 0L) break
                            hedgeLock.wait(rem)
                        }
                    }
                    if (!hasWinner.get() && !isClientAborted.get() && !task.isSuccess) {
                        tryAttemptFetch(
                            task = task,
                            url = hedgeUrl,
                            session = session,
                            statsTracker = statsTracker,
                            concurrencyController = concurrencyController,
                            hasWinner = hasWinner,
                            isHedge = true,
                            callHolder = hedgeCallRef,
                            counterpartCallHolder = primaryCallRef,
                            streamCallKey = streamCallKey
                        )
                    }
                } catch (_: InterruptedException) {}
            }
        } else null

        // 主请求在当前线程执行
        try {
            tryAttemptFetch(
                task = task,
                url = primaryUrl,
                session = session,
                statsTracker = statsTracker,
                concurrencyController = concurrencyController,
                hasWinner = hasWinner,
                isHedge = false,
                callHolder = primaryCallRef,
                counterpartCallHolder = hedgeCallRef,
                streamCallKey = streamCallKey
            )
        } finally {
            primaryFinished.set(true)
            synchronized(hedgeLock) { hedgeLock.notifyAll() }
        }

        if (hedgeFuture != null) {
            if (task.isSuccess || isClientAborted.get()) {
                hedgeFuture.cancel(true)
                hedgeCallRef.get()?.let { slowHedge -> runCatching { slowHedge.cancel() } }
            } else {
                // 若主路失败、对冲已唤醒接力，短暂等待其完成而非立即误杀
                runCatching { hedgeFuture.get(15, TimeUnit.SECONDS) }
                if (!task.isSuccess) {
                    hedgeCallRef.get()?.let { slowHedge -> runCatching { slowHedge.cancel() } }
                }
            }
        }

        // 若前两路对冲/主请求未成功，按序尝试剩余备选节点（带会话级地址隔离 + 断点续传）
        if (!task.isSuccess && !isClientAborted.get()) {
            val remainingUrls = tryUrls.drop(2)
            val now = System.currentTimeMillis()
            for (url in remainingUrls) {
                if (isClientAborted.get() || task.isSuccess) break
                val host = extractHostname(url)
                if (session.banList.isBanned(url, host, now) || isPenalized(host)) continue

                val singleCallRef = java.util.concurrent.atomic.AtomicReference<Call?>(null)
                tryAttemptFetch(
                    task = task,
                    url = url,
                    session = session,
                    statsTracker = statsTracker,
                    concurrencyController = concurrencyController,
                    hasWinner = hasWinner,
                    isHedge = false,
                    callHolder = singleCallRef,
                    counterpartCallHolder = null,
                    streamCallKey = streamCallKey
                )
                if (task.isSuccess) break
            }
        }

        // 最终直连兜底尝试
        if (!task.isSuccess && !isClientAborted.get() && urls.isNotEmpty()) {
            val fallbackCandidate = tryUrls.firstOrNull { u ->
                val h = extractHostname(u)
                !session.banList.isBanned(u, h)
            } ?: urls.first()
            tryDirectFetch(task, fallbackCandidate, session, statsTracker, concurrencyController)
        }

        if (!task.isSuccess && !isClientAborted.get()) {
            task.isFailed = true
            task.errorMessage = "All candidate CDNs failed for range ${task.startByte}-${task.endByte}"
        }
    }

    private fun tryAttemptFetch(
        task: ChunkTask,
        url: String,
        session: BtrMediaSession,
        statsTracker: StreamStatsTracker,
        concurrencyController: DynamicConcurrencyController?,
        hasWinner: AtomicBoolean,
        isHedge: Boolean,
        callHolder: java.util.concurrent.atomic.AtomicReference<Call?>,
        counterpartCallHolder: java.util.concurrent.atomic.AtomicReference<Call?>?,
        streamCallKey: String
    ) {
        val host = extractHostname(url)
        val attemptId = attemptSeq.incrementAndGet()
        val startTime = System.currentTimeMillis()
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val expectedTotal = (task.endByte - task.startByte + 1).toInt()

        // P1: 在途实时前缀共享 + 断点续传（对齐 BTR readBody 实时累加）
        // 当主路正在下载（或已部分截断）且已积攒 >= 32KB 有效前缀时，对冲/重试直接从 start + resumeOffset 拉取剩余部分
        val existingPrefix = task.takePrefixSnapshot(RESUME_MIN_BYTES, expectedTotal)
        val resumeOffset = existingPrefix?.size ?: 0

        val requestStart = task.startByte + resumeOffset
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$requestStart-${task.endByte}")
            .header("Referer", session.referer)
            .header("User-Agent", ua)
            .header("Connection", "keep-alive")
            .build()

        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
        val call = okClient.newCall(request)
        callHolder.set(call)
        registerStreamCall(streamCallKey, session.sessionId, call)

        var localBytesRead = 0L
        var completedNormally = false
        val isPrefixProducer = (resumeOffset == 0) && task.tryClaimPrefixProducer(attemptId)

        try {
            call.execute().use { response ->
                val code = response.code
                val cr = response.header("Content-Range")
                val cl = response.body?.contentLength() ?: -1L
                val needBytes = (task.endByte - requestStart + 1).toInt()

                // HTTP 200 非 Range 响应防 OOM 保护
                if (code == 200 && cr == null && (cl > needBytes + 32 * 1024 || cl <= 0)) {
                    session.banList.recordTransportFailure(host, 20_000L)
                    penalizeHost(host, 20_000L)
                    reportFailover(host, "HTTP 200 非 Range 响应防御 (防 OOM)", session.sessionId)
                    return
                }

                if (code == 200 || code == 206) {
                    // 严格校验所有 206 响应的 Content-Range 起止字节及文件总长一致性
                    if (code == 206 && !isValidContentRange(code, cr, requestStart, session)) {
                        session.banList.recordHttpFailure(url, host, 410)
                        reportFailover(host, "分片 Content-Range 异常 ($cr vs reqStart=$requestStart)", session.sessionId)
                        return
                    }

                    val body = response.body ?: return
                    val stream = body.byteStream()
                    val buffer = ByteArray(16 * 1024)
                    val out = ByteArrayOutputStream(needBytes.coerceAtLeast(1024))
                    var read: Int

                    while (stream.read(buffer).also { read = it } != -1) {
                        if (hasWinner.get() && task.isSuccess) {
                            return // 另一路对冲已胜出交付，立即退出
                        }
                        out.write(buffer, 0, read)
                        localBytesRead += read
                        if (isPrefixProducer) {
                            task.appendLivePrefix(attemptId, buffer, read, expectedTotal)
                        }
                    }

                    val pieceBytes = out.toByteArray()
                    if (pieceBytes.isEmpty()) {
                        session.banList.recordTransportFailure(host, 20_000L)
                        penalizeHost(host, 20_000L)
                        reportFailover(host, "分片拉取返回 0KB 空响应熔断", session.sessionId)
                        return
                    }

                    val allowTruncatedFullIgnoreRange = (resumeOffset > 0 && code == 200 && pieceBytes.size == expectedTotal)
                    if (needBytes > 0 && pieceBytes.size < needBytes && !allowTruncatedFullIgnoreRange) {
                        if (resumeOffset == 0 && pieceBytes.size >= RESUME_MIN_BYTES && !hasWinner.get() && !isPrefixProducer) {
                            synchronized(task) {
                                if (!hasWinner.get() && pieceBytes.size > task.receivedBytes) {
                                    task.prefixData = pieceBytes
                                    task.receivedBytes = pieceBytes.size
                                }
                            }
                        }
                        session.banList.recordTransportFailure(host, 15_000L)
                        penalizeHost(host, 15_000L)
                        reportFailover(host, "分片数据截断 (${pieceBytes.size}/$needBytes 字节)", session.sessionId)
                        return
                    }

                    // 拼接前序断点数据（如果有）
                    val rangeStart = parseRangeStart(cr)
                    val canResume = resumeOffset > 0 && existingPrefix != null && code == 206 &&
                        (rangeStart == null || rangeStart == requestStart) && pieceBytes.size == needBytes

                    val finalData = if (canResume) {
                        val combined = ByteArray(expectedTotal)
                        System.arraycopy(existingPrefix!!, 0, combined, 0, resumeOffset)
                        System.arraycopy(pieceBytes, 0, combined, resumeOffset, pieceBytes.size)
                        combined
                    } else if (resumeOffset > 0 && pieceBytes.size == expectedTotal) {
                        // 服务器忽略了 Range 头部，返回了完整分片数据
                        pieceBytes
                    } else if (resumeOffset > 0) {
                        task.resetPrefix()
                        session.banList.recordHttpFailure(url, host, 410)
                        reportFailover(host, "断点续传范围失配 (rangeStart=$rangeStart, reqStart=$requestStart, len=${pieceBytes.size}/$needBytes)", session.sessionId)
                        return
                    } else if (expectedTotal > 0 && pieceBytes.size > expectedTotal) {
                        pieceBytes.copyOf(expectedTotal)
                    } else {
                        pieceBytes
                    }

                    completedNormally = true
                    if (hasWinner.compareAndSet(false, true)) {
                        task.data = finalData
                        task.isSuccess = true
                        task.clearPrefixBuffersOnWin()
                        session.banList.recordSuccess(url, host)
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        recordHostSpeed(host, finalData.size.toLong(), latency)
                        statsTracker.recordChunk(host, finalData.size.toLong(), latency)
                        concurrencyController?.recordProgress(finalData.size.toLong())

                        // 赢家诞生，秒级中断另一路竞争 Call 释放连接池与网络带宽
                        counterpartCallHolder?.get()?.let { slowCall ->
                            runCatching { slowCall.cancel() }
                        }
                    }
                    return
                } else if (code >= 400) {
                    val isNodeFault = session.banList.recordHttpFailure(url, host, code)
                    if (code == 412 || code == 429) {
                        concurrencyController?.onPushback()
                    }
                    if (isNodeFault) {
                        val penaltyTime = if (code == 429) 45_000L else if (code == 412) 30_000L else 20_000L
                        penalizeHost(host, penaltyTime)
                    }
                    if (!isHedge || session.banList.isHostKnownGood(host)) {
                        reportFailover(host, "HTTP $code 熔断", session.sessionId)
                    }
                }
            }
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                session.banList.recordTransportFailure(host, 15_000L)
                penalizeHost(host, 12_000L)
                reportFailover(host, "分片拉取异常: ${e.message}", session.sessionId)
            }
        } finally {
            if (!completedNormally && localBytesRead >= CANCEL_SPEED_MIN_BYTES) {
                val partialElapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                recordHostSpeed(host, localBytesRead, partialElapsed)
            }
            if (isPrefixProducer && !task.isSuccess) {
                task.releasePrefixProducer(attemptId, keepPartial = !hasWinner.get())
            }
            unregisterStreamCall(streamCallKey, call)
        }
    }

    private fun parseRangeStart(cr: String?): Long? {
        if (cr.isNullOrBlank()) return null
        val match = Regex("""bytes\s+(\d+)-""").find(cr)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun sortUrlsByHealthAndSpeed(
        urls: List<String>,
        taskIndex: Long,
        session: BtrMediaSession? = null
    ): List<String> {
        val now = System.currentTimeMillis()
        val sortedUrls = urls.sortedBy { url ->
            val host = extractHostname(url)
            val isUrlBanned = session?.banList?.isBanned(url, host, now) == true
            val penaltyUntil = cdnPenalties[host] ?: 0L
            val globalPenalty = if (penaltyUntil > now) penaltyUntil - now else 0L
            (if (isUrlBanned) 60_000L else 0L) + globalPenalty
        }
        val healthyUrls = sortedUrls.filter { url ->
            val host = extractHostname(url)
            !isPenalized(host) && session?.banList?.isBanned(url, host, now) != true
        }

        val pool = if (healthyUrls.isNotEmpty()) {
            val ranked = healthyUrls.sortedByDescending { getHostSpeed(extractHostname(it)) }
            val topSpeed = getHostSpeed(extractHostname(ranked.first()))
            val eligible = if (topSpeed > 0.0) {
                ranked.filter {
                    val sp = getHostSpeed(extractHostname(it))
                    sp == 0.0 || sp >= (topSpeed / 8.0)
                }
            } else ranked
            if (eligible.isNotEmpty()) eligible else ranked
        } else {
            sortedUrls
        }

        if (pool.isEmpty()) return emptyList()
        val offset = (taskIndex % pool.size).toInt()
        return pool.subList(offset, pool.size) + pool.subList(0, offset)
    }

    private fun tryDirectFetch(
        task: ChunkTask,
        url: String,
        session: BtrMediaSession,
        statsTracker: StreamStatsTracker,
        concurrencyController: DynamicConcurrencyController? = null
    ) {
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${task.startByte}-${task.endByte}")
            .header("Referer", session.referer)
            .header("User-Agent", ua)
            .header("Connection", "keep-alive")
            .build()

        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.fallbackClient
        try {
            okClient.newCall(request).execute().use { response ->
                val code = response.code
                val expectedBytes = (task.endByte - task.startByte + 1).toInt()
                val cr = response.header("Content-Range")
                val cl = response.body?.contentLength() ?: -1L

                if (code == 200 && cr == null && (cl > expectedBytes + 32 * 1024 || cl <= 0)) {
                    return@use
                }
                if (code == 206 && !isValidContentRange(code, cr, task.startByte, session)) {
                    session.banList.recordHttpFailure(url, host, 410)
                    return@use
                }

                if (code in 200..299) {
                    val rawBytes = response.body?.bytes() ?: ByteArray(0)
                    if (rawBytes.isNotEmpty() && (expectedBytes <= 0 || rawBytes.size >= expectedBytes)) {
                        val bytes = if (expectedBytes > 0 && rawBytes.size > expectedBytes) rawBytes.copyOf(expectedBytes) else rawBytes
                        task.data = bytes
                        task.isSuccess = true
                        session.banList.recordSuccess(url, host)
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        statsTracker.recordChunk(host, bytes.size.toLong(), latency)
                        concurrencyController?.recordProgress(bytes.size.toLong())
                    }
                } else if (code == 412 || code == 429) {
                    concurrencyController?.onPushback()
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun extractHostname(url: String): String {
        return BiliAcceleratorCore.parseUri(url)?.host
            ?: runCatching { java.net.URI(url).host }.getOrNull()
            ?: ""
    }

    private fun isPenalized(host: String): Boolean {
        val penalty = cdnPenalties[host] ?: return false
        return penalty > System.currentTimeMillis()
    }

    private fun reportFailover(badHost: String, reason: String, sessionId: String) {
        val result = RewriteResult(
            changed = true,
            originalUrl = "",
            finalUrl = "",
            originalHost = badHost,
            targetHost = "备用镜像/CDN",
            reason = "BTR节点熔断: $reason",
            isPcdn = false,
            isMcdn = false,
            segmentKey = "btr_failover_${sessionId}_${System.currentTimeMillis()}",
            isBtr = true
        )
        RemoteClient.notifyRewrite(result)
    }

    private fun sendHttpError(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {}
    }
}
