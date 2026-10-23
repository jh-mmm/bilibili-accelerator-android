package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import okhttp3.Request
import java.io.OutputStream
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object BtrChunkEngine {

    private const val TAG = "BiliAccelerator-BTR"
    const val CHUNK_SIZE_AUDIO = 128 * 1024
    const val CHUNK_SIZE_VIDEO = 512 * 1024
    const val AUDIO_MAX_CONCURRENCY = 2
    const val PROBE_PREFIX_BYTES = 64 * 1024

    private const val DEFAULT_CHUNK_SIZE = 512 * 1024L // 512 KB
    private const val MAX_PREFETCH_CHUNKS = 8           // 移动端自适应内存滑动窗口上限 (限制在 4MB~8MB，严格防 OOM)
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 BiliApp/7800300"

    private val cdnPenalties = ConcurrentHashMap<String, Long>()

    data class ChunkTask(
        val index: Long,
        val startByte: Long,
        val endByte: Long,
        @Volatile var data: ByteArray? = null,
        @Volatile var isSuccess: Boolean = false,
        @Volatile var isFailed: Boolean = false,
        @Volatile var errorMessage: String? = null
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
            128 * 1024L // 音频流采用 128KB 小切片低时延分片
        } else {
            512 * 1024L // 探测首片统一使用 512KB 快速拉取总长度
        }

        val baseConcurrency = if (isAudio) 2 else concurrency.coerceIn(2, 16)
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

            // 下载专用工作线程池：保证所有 burstConcurrency 线程全部服务于分片 HTTP 下载
            val workerPool = Executors.newFixedThreadPool(burstConcurrency)

            // 调度分发协作者在独立轻量守护线程运行，绝不占死 workerPool 工作线程
            val dispatchThread = Thread({
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

                    workerPool.execute {
                        if (!isClientAborted.get()) {
                            try {
                                fetchChunkWithFailover(task, candidateUrls, session, statsTracker)
                            } finally {
                                synchronized(completionLock) {
                                    completionLock.notifyAll()
                                }
                            }
                        }
                    }
                    dispatchIdx++
                }
            }, "BTR-Dispatcher-${session.sessionId}").apply { isDaemon = true }

            try {
                dispatchThread.start()

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
                dispatchThread.interrupt()
                workerPool.shutdownNow()
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
                    val buffer = ByteArray(64 * 1024)
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
        val sortedUrls = sortUrlsByHealth(urls)
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
                        val latency = System.currentTimeMillis() - startTime
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
        statsTracker: StreamStatsTracker
    ) {
        val sortedUrls = sortUrlsByHealth(urls)
        val healthyUrls = sortedUrls.filterNot { isPenalized(extractHostname(it)) }
        val tryUrls = if (healthyUrls.isNotEmpty()) {
            val startIndex = (task.index % healthyUrls.size).toInt()
            healthyUrls.subList(startIndex, healthyUrls.size) + healthyUrls.subList(0, startIndex)
        } else {
            sortedUrls
        }

        val ua = session.userAgent ?: DEFAULT_USER_AGENT

        for (url in tryUrls) {
            val host = extractHostname(url)
            if (isPenalized(host)) continue

            val startTime = System.currentTimeMillis()
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=${task.startByte}-${task.endByte}")
                .header("Referer", session.referer)
                .header("User-Agent", ua)
                .header("Connection", "keep-alive")
                .build()

            val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
            try {
                okClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val expectedBytes = (task.endByte - task.startByte + 1).toInt()
                    val cr = response.header("Content-Range")
                    val cl = response.body?.contentLength() ?: -1L

                    // 关键防护 (防 OOM): 若返回 HTTP 200 且未携带 Content-Range，说明 CDN 忽略了 Range 分片并试图下发全量媒体流！
                    // 若媒体体积较大，直接调用 response.body?.bytes() 将导致数十上百兆字节读入内存瞬间触发 OOM 崩溃！
                    if (code == 200 && cr == null && (cl > expectedBytes + 32 * 1024 || cl <= 0)) {
                        penalizeHost(host, 30_000L)
                        reportFailover(host, "HTTP 200 非 Range 响应防御 (防 OOM)", session.sessionId)
                        return@use
                    }

                    if (code == 200 || code == 206) {
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        if (bytes.isEmpty()) {
                            penalizeHost(host, 30_000L)
                            reportFailover(host, "分片拉取返回 0KB 空响应熔断", session.sessionId)
                            return@use
                        }
                        if (expectedBytes > 0 && bytes.size < expectedBytes) {
                            penalizeHost(host, 20_000L)
                            reportFailover(host, "分片数据截断 (${bytes.size}/$expectedBytes 字节)", session.sessionId)
                            return@use
                        }
                        val latency = System.currentTimeMillis() - startTime
                        task.data = bytes
                        task.isSuccess = true
                        statsTracker.recordChunk(host, bytes.size.toLong(), latency)
                        return
                    } else if (code == 403 || code >= 500) {
                        penalizeHost(host, 20_000L)
                        reportFailover(host, "HTTP $code 熔断", session.sessionId)
                    }
                }
            } catch (e: Exception) {
                penalizeHost(host, 15_000L)
                reportFailover(host, "分片拉取超时/异常: ${e.message}", session.sessionId)
            }
        }

        // 最终兜底：使用第一个可用 URL 尝试同步直连
        if (!task.isSuccess && urls.isNotEmpty()) {
            tryDirectFetch(task, urls.first(), session, statsTracker)
        }

        if (!task.isSuccess) {
            task.isFailed = true
            task.errorMessage = "All candidate CDNs failed for range ${task.startByte}-${task.endByte}"
        }
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
