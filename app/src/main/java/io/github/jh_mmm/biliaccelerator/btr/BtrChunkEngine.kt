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

    private const val MAX_PREFETCH_CHUNKS = 8           // 移动端自适应内存滑动窗口上限 (限制在 4MB~8MB，严格防 OOM)
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 BiliApp/7800300"
    private const val RESUME_MIN_BYTES = 32 * 1024      // 断点续传拼接最低字节门槛（对齐 BTR 原版）
    private const val DEFAULT_HEDGE_DELAY_MS = 1200L    // 对冲请求延迟触发阈值（毫秒）

    // 全局共享工作线程池：复用线程，防止切集时线程暴增引发 OOM（core=8, max=32, 60s 存活）
    private val globalWorkerPool = ThreadPoolExecutor(
        8,
        32,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(512)
    ) { r ->
        Thread(r, "BTR-GlobalWorker").apply { isDaemon = true }
    }

    // 全局分发调度线程池
    private val globalDispatcherPool = Executors.newCachedThreadPool { r ->
        Thread(r, "BTR-Dispatcher").apply { isDaemon = true }
    }

    // 活跃会话网络调用集合，用于切集/退出时秒级取消并释放网络和线程资源
    private val sessionActiveCalls = ConcurrentHashMap<String, MutableSet<Call>>()

    private fun registerSessionCall(sessionId: String, call: Call) {
        sessionActiveCalls.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(call)
    }

    private fun unregisterSessionCall(sessionId: String, call: Call) {
        sessionActiveCalls[sessionId]?.remove(call)
    }

    private fun cancelSessionCalls(sessionId: String) {
        sessionActiveCalls.remove(sessionId)?.forEach { call ->
            runCatching { call.cancel() }
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

    fun recordHostSpeed(host: String, bytes: Long, latencyMs: Long) {
        if (bytes < 32 * 1024 || latencyMs <= 0) return
        val bps = (bytes * 1000.0) / latencyMs
        val now = System.currentTimeMillis()
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
    )

    private class StreamStatsTracker(
        val sessionId: String,
        val primaryHost: String,
        val concurrency: Int,
        val proxyPort: Int
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

            val prefix = if (sessionId.startsWith("audio_") || primaryHost.contains("audio")) "BTR音频优化" else "BTR并发加速"
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
        if (!autoChunkSize) return (baseChunkSizeKb.coerceIn(128, 2048) * 1024).toLong()
        return when {
            totalLength <= 0 -> (baseChunkSizeKb.coerceIn(128, 2048) * 1024).toLong()
            totalLength < 15 * 1024 * 1024L -> 256 * 1024L
            totalLength < 60 * 1024 * 1024L -> 512 * 1024L
            else -> 1024 * 1024L // 移动端自适应优化：大文件/4K 自动切片上限建议为 1024KB (1MB)，大幅缓解堆内存与 OOM 压力
        }
    }

    fun pipeStream(
        session: BtrMediaSession,
        requestedStart: Long,
        requestedEnd: Long?, // null 表示直至文件尾部
        isHeadOnly: Boolean,
        clientOut: OutputStream,
        concurrency: Int = 6,
        chunkSizeKb: Int = 512,
        autoConcurrency: Boolean = true,
        autoChunkSize: Boolean = true,
        proxyPort: Int = -1
    ) {
        val isAudio = session.isAudio || BiliAcceleratorCore.isAudioTrack(session.primaryUrl)
        val initialProbeChunkSize = if (isAudio) {
            CHUNK_SIZE_AUDIO.toLong() // 音频流采用 128KB 小切片低时延分片
        } else {
            CHUNK_SIZE_VIDEO.toLong() // 探测首片统一使用 512KB 快速拉取总长度
        }

        val baseConcurrency = if (isAudio) AUDIO_MAX_CONCURRENCY else concurrency.coerceIn(2, 16)
        val useAuto = !isAudio && autoConcurrency
        val burstConcurrency = if (useAuto) maxOf(baseConcurrency, 8).coerceAtMost(16) else baseConcurrency

        val candidateUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
        val primaryHost = BiliAcceleratorCore.cleanHost(extractHostname(session.primaryUrl))

        val statsTracker = StreamStatsTracker(session.sessionId, primaryHost, burstConcurrency, proxyPort)

        try {
            val hasCachedMetadata = session.totalLength > 0
            val totalLength: Long
            val contentType: String
            val firstChunk: ByteArray?
            val currentOffset: Long
            val actualEnd: Long

            if (hasCachedMetadata && requestedStart > 0) {
                // P0: Seek 探针跳过优化！
                // 该 session 早已完成元数据初始化，用户拖动 Seek 时直接跳过 probeMedia 512KB 网络往返，0ms 秒级起播！
                totalLength = session.totalLength
                contentType = session.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                firstChunk = null
                currentOffset = requestedStart
                actualEnd = when {
                    requestedEnd != null && requestedEnd > 0 -> minOf(requestedEnd, totalLength - 1)
                    totalLength > 0 -> totalLength - 1
                    else -> -1L
                }
            } else {
                // 首片探测 (使用 OkHttp 连接池与 HTTP/2 探针)
                val probeResult = probeMedia(candidateUrls, session, requestedStart, initialProbeChunkSize)
                if (probeResult == null) {
                    sendHttpError(clientOut, 502, "Bad Gateway - Upstream CDN Unreachable")
                    return
                }

                if (probeResult.isNonRange) {
                    // P0: HTTP 200 非 Range 容错，直接全量流式透传，避免老视频 512KB 截断
                    pipeNonRangeStream(candidateUrls.firstOrNull() ?: session.primaryUrl, session, clientOut, statsTracker)
                    return
                }

                totalLength = probeResult.totalLength
                contentType = probeResult.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                if (totalLength > 0) {
                    session.totalLength = totalLength
                    session.contentType = contentType
                }

                firstChunk = probeResult.firstChunkData
                actualEnd = when {
                    requestedEnd != null && requestedEnd > 0 -> minOf(requestedEnd, if (totalLength > 0) totalLength - 1 else requestedEnd)
                    totalLength > 0 -> totalLength - 1
                    else -> -1L
                }
                currentOffset = requestedStart + (firstChunk?.size ?: 0)
            }

            // 根据探针测得或已缓存的媒体总长度，自适应计算后续分片拉取的最佳 ChunkSize
            val activeChunkSize = calculateDynamicChunkSize(
                totalLength = totalLength,
                baseChunkSizeKb = chunkSizeKb,
                autoChunkSize = autoChunkSize,
                isAudio = isAudio
            )

            // 2. 构造并发送 HTTP 206 Partial Content (或 200 OK) 响应头给播放器
            val isPartial = requestedStart > 0 || actualEnd > 0
            val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val sb = StringBuilder(statusLine)
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            // 明确标记 Connection: close，杜绝客户端由于伪 keep-alive 产生复用连接重置 (Connection reset) 异常
            sb.append("Connection: close\r\n")

            if (totalLength > 0 && actualEnd >= requestedStart) {
                val contentLength = actualEnd - requestedStart + 1
                sb.append("Content-Length: $contentLength\r\n")
                if (isPartial) {
                    sb.append("Content-Range: bytes $requestedStart-$actualEnd/$totalLength\r\n")
                }
            } else if (!isPartial && totalLength > 0) {
                sb.append("Content-Length: $totalLength\r\n")
            }
            sb.append("\r\n")

            clientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            clientOut.flush()

            if (isHeadOnly) return

            // 3. 写出首片探测数据（仅在未跳过探针且有首片时）
            if (firstChunk != null && firstChunk.isNotEmpty()) {
                val bytesToWrite = if (actualEnd > 0) {
                    val needed = (actualEnd - requestedStart + 1).toInt()
                    if (needed < firstChunk.size) firstChunk.copyOf(needed) else firstChunk
                } else firstChunk

                clientOut.write(bytesToWrite)
                clientOut.flush()
                statsTracker.recordChunk(primaryHost, bytesToWrite.size.toLong(), 20L)
            }

            // 计算剩余字节范围
            if (actualEnd <= 0 || currentOffset > actualEnd) {
                // 已全部写出或未知长度单片传输完成
                return
            }

            // 4. 并发切片任务切分与滑动窗口流水线
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
            val isClientAborted = AtomicBoolean(false)
            val deliveredIndex = AtomicLong(0L)

            // 调度分发协作者在 globalDispatcherPool 异步执行
            val dispatchFuture = globalDispatcherPool.submit {
                var dispatchIdx = 0
                while (dispatchIdx < totalTasks && !isClientAborted.get()) {
                    // 动态线程调节与背压控制：冲刺期放宽，稳定期按 baseConcurrency 约束在途分片
                    val maxPrefetch = if (useAuto && deliveredIndex.get() >= 4) {
                        minOf(MAX_PREFETCH_CHUNKS, baseConcurrency * 2)
                    } else {
                        MAX_PREFETCH_CHUNKS
                    }

                    while (dispatchIdx - deliveredIndex.get() >= maxPrefetch && !isClientAborted.get()) {
                        try {
                            Thread.sleep(15)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (isClientAborted.get()) break

                    val task = chunkTasks[dispatchIdx]
                    taskMap[task.index] = task

                    globalWorkerPool.execute {
                        if (!isClientAborted.get()) {
                            try {
                                fetchChunkWithFailover(task, candidateUrls, session, statsTracker, isClientAborted)
                            } finally {
                                synchronized(completionLock) {
                                    completionLock.notifyAll()
                                }
                            }
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
                        }
                        // 及时置空，释放堆内存并同步已交付游标
                        currentTask.data = null
                        currentTask.prefixData = null
                        taskMap.remove(nextDeliverIndex)
                        nextDeliverIndex++
                        deliveredIndex.set(nextDeliverIndex)
                    } catch (e: SocketException) {
                        // 客户端断开连接 (例如用户拖动进度条 Seek 或切集)
                        Log.i(TAG, "Client disconnected (Seek or stop). Session=${session.sessionId}")
                        isClientAborted.set(true)
                        break
                    }
                }
            } finally {
                isClientAborted.set(true)
                dispatchFuture.cancel(true)
                cancelSessionCalls(session.sessionId)
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

    private fun pipeNonRangeStream(
        url: String,
        session: BtrMediaSession,
        clientOut: OutputStream,
        statsTracker: StreamStatsTracker
    ) {
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

        try {
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
            }
        } catch (_: Exception) {}
    }

    private fun probeMedia(
        urls: List<String>,
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long
    ): ProbeResult? {
        val sortedUrls = sortUrlsByHealthAndSpeed(urls, 0L)
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val primaryHost = extractHostname(session.primaryUrl)
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.probeClient
        for (url in sortedUrls) {
            val host = extractHostname(url)
            val startTime = System.currentTimeMillis()
            val endOffset = startOffset + chunkSize - 1
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$startOffset-$endOffset")
                .header("Referer", session.referer)
                .header("User-Agent", ua)
                .header("Connection", "keep-alive")
                .build()

            try {
                okClient.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 200 || code == 206) {
                        val cr = response.header("Content-Range")
                        val isNonRange = (code == 200 && cr == null)
                        val total = if (isNonRange) {
                            response.body?.contentLength() ?: -1L
                        } else {
                            cr?.substringAfterLast("/")?.trim()?.toLongOrNull() ?: -1L
                        }
                        val cl = response.body?.contentLength() ?: -1L
                        val ct = response.header("Content-Type")?.takeIf { it.isNotBlank() }

                        val data = if (isNonRange) ByteArray(0) else (response.body?.bytes() ?: ByteArray(0))
                        if (!isNonRange && data.isEmpty()) {
                            penalizeHost(host, 30_000L)
                            if (host == primaryHost) {
                                reportFailover(host, "首片探测返回 0KB 空数据熔断", session.sessionId)
                            }
                            return@use
                        }
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        if (data.isNotEmpty()) {
                            recordHostSpeed(host, data.size.toLong(), latency)
                        }
                        return ProbeResult(total, cl, ct, data, host, latency, isNonRange = isNonRange)
                    } else if (code == 403 || code >= 500) {
                        penalizeHost(host, 20_000L)
                        // 仅当失败的节点是主节点时才上报熔断切换，避免在评估备用候选池时产生虚假熔断告警
                        if (host == primaryHost) {
                            reportFailover(host, "HTTP $code 错误", session.sessionId)
                        }
                    }
                }
            } catch (e: Exception) {
                penalizeHost(host, 15_000L)
                if (host == primaryHost) {
                    reportFailover(host, e.message ?: "连接超时", session.sessionId)
                }
            }
        }
        return null
    }

    private fun fetchChunkWithFailover(
        task: ChunkTask,
        urls: List<String>,
        session: BtrMediaSession,
        statsTracker: StreamStatsTracker,
        isClientAborted: AtomicBoolean
    ) {
        if (isClientAborted.get()) return

        val tryUrls = sortUrlsByHealthAndSpeed(urls, task.index)
        if (tryUrls.isEmpty()) {
            task.isFailed = true
            task.errorMessage = "No available CDN urls"
            return
        }

        val primaryUrl = tryUrls.first()
        val hedgeUrl = if (tryUrls.size > 1) tryUrls[1] else null
        val hedgeDelayMs = if (task.index <= 1L) 800L else DEFAULT_HEDGE_DELAY_MS

        val hasWinner = AtomicBoolean(false)
        val primaryCallRef = java.util.concurrent.atomic.AtomicReference<Call?>(null)
        val hedgeCallRef = java.util.concurrent.atomic.AtomicReference<Call?>(null)

        // 启动对冲并发监测任务（若单路慢/假死，超过 hedgeDelayMs 后第二路并发介入）
        val hedgeFuture = if (hedgeUrl != null && !isClientAborted.get()) {
            globalWorkerPool.submit {
                try {
                    Thread.sleep(hedgeDelayMs)
                    if (!hasWinner.get() && !isClientAborted.get() && !task.isSuccess) {
                        tryAttemptFetch(
                            task = task,
                            url = hedgeUrl,
                            session = session,
                            statsTracker = statsTracker,
                            hasWinner = hasWinner,
                            isHedge = true,
                            callHolder = hedgeCallRef,
                            counterpartCallHolder = primaryCallRef
                        )
                    }
                } catch (_: InterruptedException) {}
            }
        } else null

        // 主请求在当前线程执行
        tryAttemptFetch(
            task = task,
            url = primaryUrl,
            session = session,
            statsTracker = statsTracker,
            hasWinner = hasWinner,
            isHedge = false,
            callHolder = primaryCallRef,
            counterpartCallHolder = hedgeCallRef
        )

        hedgeFuture?.cancel(true)

        // 若前两路对冲/主请求未成功，按序尝试剩余备选节点（带断点续传）
        if (!task.isSuccess && !isClientAborted.get()) {
            val remainingUrls = tryUrls.drop(2)
            for (url in remainingUrls) {
                if (isClientAborted.get() || task.isSuccess) break
                val host = extractHostname(url)
                if (isPenalized(host)) continue

                val singleCallRef = java.util.concurrent.atomic.AtomicReference<Call?>(null)
                tryAttemptFetch(
                    task = task,
                    url = url,
                    session = session,
                    statsTracker = statsTracker,
                    hasWinner = hasWinner,
                    isHedge = false,
                    callHolder = singleCallRef,
                    counterpartCallHolder = null
                )
                if (task.isSuccess) break
            }
        }

        // 最终直连兜底尝试
        if (!task.isSuccess && !isClientAborted.get() && urls.isNotEmpty()) {
            tryDirectFetch(task, urls.first(), session, statsTracker)
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
        hasWinner: AtomicBoolean,
        isHedge: Boolean,
        callHolder: java.util.concurrent.atomic.AtomicReference<Call?>,
        counterpartCallHolder: java.util.concurrent.atomic.AtomicReference<Call?>?
    ) {
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        val expectedTotal = (task.endByte - task.startByte + 1).toInt()

        // P1: 断点续传优化（对齐 BTR 原版）
        // 若前序尝试已成功接收部分数据，且达到断点续传门槛，本次只拉取缺失的剩余字节
        val existingPrefix = task.prefixData
        val resumeOffset = if (existingPrefix != null && task.receivedBytes in RESUME_MIN_BYTES until expectedTotal) {
            task.receivedBytes
        } else {
            0
        }

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
        registerSessionCall(session.sessionId, call)

        try {
            call.execute().use { response ->
                val code = response.code
                val cr = response.header("Content-Range")
                val cl = response.body?.contentLength() ?: -1L
                val needBytes = (task.endByte - requestStart + 1).toInt()

                // HTTP 200 非 Range 响应防 OOM 保护
                if (code == 200 && cr == null && (cl > needBytes + 32 * 1024 || cl <= 0)) {
                    penalizeHost(host, 30_000L)
                    reportFailover(host, "HTTP 200 非 Range 响应防御 (防 OOM)", session.sessionId)
                    return
                }

                if (code == 200 || code == 206) {
                    val body = response.body ?: return
                    val stream = body.byteStream()
                    val buffer = ByteArray(16 * 1024)
                    val out = ByteArrayOutputStream(needBytes)
                    var read: Int

                    while (stream.read(buffer).also { read = it } != -1) {
                        if (hasWinner.get() && task.isSuccess) {
                            return // 另一路对冲已胜出交付，立即退出
                        }
                        out.write(buffer, 0, read)
                        if (resumeOffset == 0) {
                            task.receivedBytes = out.size()
                        }
                    }

                    val pieceBytes = out.toByteArray()
                    if (pieceBytes.isEmpty()) {
                        penalizeHost(host, 30_000L)
                        reportFailover(host, "分片拉取返回 0KB 空响应熔断", session.sessionId)
                        return
                    }

                    if (needBytes > 0 && pieceBytes.size < needBytes) {
                        // 发生截断，保存已接收数据供下次断点拼接
                        if (resumeOffset == 0 && pieceBytes.size >= RESUME_MIN_BYTES) {
                            task.prefixData = pieceBytes
                            task.receivedBytes = pieceBytes.size
                        }
                        penalizeHost(host, 20_000L)
                        reportFailover(host, "分片数据截断 (${pieceBytes.size}/$needBytes 字节)", session.sessionId)
                        return
                    }

                    // 拼接前序断点数据（如果有）
                    val finalData = if (resumeOffset > 0 && existingPrefix != null) {
                        val combined = ByteArray(expectedTotal)
                        System.arraycopy(existingPrefix, 0, combined, 0, resumeOffset)
                        System.arraycopy(pieceBytes, 0, combined, resumeOffset, pieceBytes.size)
                        combined
                    } else {
                        pieceBytes
                    }

                    if (hasWinner.compareAndSet(false, true)) {
                        task.data = finalData
                        task.isSuccess = true
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        recordHostSpeed(host, finalData.size.toLong(), latency)
                        statsTracker.recordChunk(host, finalData.size.toLong(), latency)

                        // 赢家诞生，秒级中断另一路竞争 Call 释放连接池与网络带宽
                        counterpartCallHolder?.get()?.let { slowCall ->
                            runCatching { slowCall.cancel() }
                        }
                    }
                    return
                } else if (code == 403 || code >= 500) {
                    penalizeHost(host, 20_000L)
                    reportFailover(host, "HTTP $code 熔断", session.sessionId)
                }
            }
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                penalizeHost(host, 15_000L)
                reportFailover(host, "分片拉取异常: ${e.message}", session.sessionId)
            }
        } finally {
            unregisterSessionCall(session.sessionId, call)
        }
    }

    private fun sortUrlsByHealthAndSpeed(urls: List<String>, taskIndex: Long): List<String> {
        val now = System.currentTimeMillis()
        val sortedUrls = urls.sortedBy { url ->
            val host = extractHostname(url)
            val penaltyUntil = cdnPenalties[host] ?: 0L
            if (penaltyUntil > now) penaltyUntil - now else 0L
        }
        val healthyUrls = sortedUrls.filterNot { isPenalized(extractHostname(it)) }
        if (healthyUrls.size <= 1) return sortedUrls

        // P2: 按照 EWMA 节点实测测速加权（速度快的优先，淘汰极端落后节点）
        val ranked = healthyUrls.sortedByDescending { getHostSpeed(extractHostname(it)) }
        val topSpeed = getHostSpeed(extractHostname(ranked.first()))
        val eligible = if (topSpeed > 0.0) {
            ranked.filter {
                val sp = getHostSpeed(extractHostname(it))
                sp == 0.0 || sp >= (topSpeed / 8.0)
            }
        } else ranked

        val pool = if (eligible.isNotEmpty()) eligible else ranked
        val offset = (taskIndex % pool.size).toInt()
        return pool.subList(offset, pool.size) + pool.subList(0, offset)
    }

    private fun tryDirectFetch(
        task: ChunkTask,
        url: String,
        session: BtrMediaSession,
        statsTracker: StreamStatsTracker
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

                if (code in 200..299) {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (bytes.isNotEmpty() && (expectedBytes <= 0 || bytes.size >= expectedBytes)) {
                        task.data = bytes
                        task.isSuccess = true
                        statsTracker.recordChunk(host, bytes.size.toLong(), System.currentTimeMillis() - startTime)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun sortUrlsByHealth(urls: List<String>): List<String> {
        val now = System.currentTimeMillis()
        return urls.sortedBy { url ->
            val host = extractHostname(url)
            val penaltyUntil = cdnPenalties[host] ?: 0L
            if (penaltyUntil > now) penaltyUntil - now else 0L
        }
    }

    private fun extractHostname(url: String): String {
        return BiliAcceleratorCore.parseUri(url)?.host
            ?: runCatching { java.net.URI(url).host }.getOrNull()
            ?: ""
    }

    private fun penalizeHost(host: String, durationMs: Long) {
        cdnPenalties[host] = System.currentTimeMillis() + durationMs
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
