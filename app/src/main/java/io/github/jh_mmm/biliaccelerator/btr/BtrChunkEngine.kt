package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object BtrChunkEngine {

    private const val TAG = "BiliAccelerator-BTR"
    private const val DEFAULT_CHUNK_SIZE = 512 * 1024L // 512 KB
    private const val MAX_PREFETCH_CHUNKS = 16          // 内存滑动窗口上限 (约 8MB~16MB，严格防 OOM)
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 BiliApp/7800300"

    private val cdnPenalties = ConcurrentHashMap<String, Long>()

    init {
        try {
            System.setProperty("http.keepAlive", "true")
            System.setProperty("http.maxConnections", "32")
        } catch (_: Throwable) {}
    }

    data class ChunkTask(
        val index: Long,
        val startByte: Long,
        val endByte: Long,
        @Volatile var data: ByteArray? = null,
        @Volatile var isSuccess: Boolean = false
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

            val summaryReason = "BTR并发加速 · ${count}分片(${mbStr}MB) · ${hitHosts.size}个CDN · 均时${avgLatency}ms"

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

    fun pipeStream(
        session: BtrMediaSession,
        requestedStart: Long,
        requestedEnd: Long?, // null 表示直至文件尾部
        isHeadOnly: Boolean,
        clientOut: OutputStream,
        concurrency: Int = 6,
        chunkSizeKb: Int = 512,
        proxyPort: Int = -1
    ) {
        val chunkSize = (chunkSizeKb.coerceIn(128, 2048) * 1024).toLong()
        val candidateUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
        val primaryHost = BiliAcceleratorCore.cleanHost(extractHostname(session.primaryUrl))

        val statsTracker = StreamStatsTracker(session.sessionId, primaryHost, concurrency, proxyPort)

        try {
            // 1. Probe 首片并获取媒体总长度 (避免多余 HEAD 请求开销)
            val probeResult = probeMedia(candidateUrls, session, requestedStart, chunkSize)
            if (probeResult == null) {
                sendHttpError(clientOut, 502, "Bad Gateway - Upstream CDN Unreachable")
                return
            }

            val totalLength = probeResult.totalLength
            val actualEnd = when {
                requestedEnd != null && requestedEnd > 0 -> minOf(requestedEnd, if (totalLength > 0) totalLength - 1 else requestedEnd)
                totalLength > 0 -> totalLength - 1
                else -> -1L
            }

            // 2. 构造并发送 HTTP 206 Partial Content (或 200 OK) 响应头给播放器
            val isPartial = requestedStart > 0 || actualEnd > 0
            val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val sb = StringBuilder(statusLine)
            val contentType = probeResult.contentType
                ?: if (session.primaryUrl.contains("audio") || session.primaryUrl.contains("sound")) {
                    "audio/mp4"
                } else {
                    "video/mp4"
                }
            sb.append("Content-Type: $contentType\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Connection: keep-alive\r\n")

            if (totalLength > 0 && actualEnd >= requestedStart) {
                val contentLength = actualEnd - requestedStart + 1
                sb.append("Content-Length: $contentLength\r\n")
                if (isPartial) {
                    sb.append("Content-Range: bytes $requestedStart-$actualEnd/$totalLength\r\n")
                }
            } else if (probeResult.contentLength > 0) {
                sb.append("Content-Length: ${probeResult.contentLength}\r\n")
            }
            sb.append("\r\n")

            clientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            clientOut.flush()

            if (isHeadOnly) return

            // 3. 写出首片探测数据
            val firstChunk = probeResult.firstChunkData
            if (firstChunk != null && firstChunk.isNotEmpty()) {
                val bytesToWrite = if (actualEnd > 0) {
                    val needed = (actualEnd - requestedStart + 1).toInt()
                    if (needed < firstChunk.size) firstChunk.copyOf(needed) else firstChunk
                } else firstChunk

                clientOut.write(bytesToWrite)
                clientOut.flush()
                statsTracker.recordChunk(probeResult.servingHost, bytesToWrite.size.toLong(), probeResult.probeLatencyMs)
            }

            // 计算剩余字节范围
            val currentOffset = requestedStart + (firstChunk?.size ?: 0)
            if (actualEnd <= 0 || currentOffset > actualEnd) {
                // 已全部写出或未知长度单片传输完成
                return
            }

            // 4. 并发切片任务切分与滑动窗口流水线
            val chunkTasks = mutableListOf<ChunkTask>()
            var offset = currentOffset
            var taskIdx = 0L
            while (offset <= actualEnd) {
                val end = minOf(offset + chunkSize - 1, actualEnd)
                chunkTasks.add(ChunkTask(taskIdx++, offset, end))
                offset = end + 1
            }

            val totalTasks = chunkTasks.size
            val taskMap = ConcurrentHashMap<Long, ChunkTask>()
            val completionLock = Object()
            val isClientAborted = AtomicBoolean(false)
            val deliveredIndex = AtomicLong(0L)

            val workerPool = Executors.newFixedThreadPool(concurrency.coerceIn(2, 12))

            try {
                // 调度分发协作者
                workerPool.execute {
                    var dispatchIdx = 0
                    while (dispatchIdx < totalTasks && !isClientAborted.get()) {
                        // 背压控制：未交付分片超过最大预取窗口时挂起，防止 OOM
                        while (dispatchIdx - deliveredIndex.get() >= MAX_PREFETCH_CHUNKS && !isClientAborted.get()) {
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
                                fetchChunkWithFailover(task, candidateUrls, session, statsTracker)
                                synchronized(completionLock) {
                                    completionLock.notifyAll()
                                }
                            }
                        }
                        dispatchIdx++
                    }
                }

                // 严格按序 (0, 1, 2...) 写出分片到客户端 Socket
                var nextDeliverIndex = 0L
                while (nextDeliverIndex < totalTasks && !isClientAborted.get()) {
                    val currentTask = taskMap[nextDeliverIndex]
                    if (currentTask == null || currentTask.data == null) {
                        synchronized(completionLock) {
                            if (!isClientAborted.get() && (taskMap[nextDeliverIndex]?.data == null)) {
                                completionLock.wait(400)
                            }
                        }
                        continue
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
        val probeLatencyMs: Long
    )

    private fun probeMedia(
        urls: List<String>,
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long
    ): ProbeResult? {
        val sortedUrls = sortUrlsByHealth(urls)
        val ua = session.userAgent ?: DEFAULT_USER_AGENT
        for (url in sortedUrls) {
            val host = extractHostname(url)
            val startTime = System.currentTimeMillis()
            var conn: HttpURLConnection? = null
            try {
                val endOffset = startOffset + chunkSize - 1
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("Range", "bytes=$startOffset-$endOffset")
                    setRequestProperty("Referer", session.referer)
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Connection", "keep-alive")
                }

                val code = conn.responseCode
                if (code == 200 || code == 206) {
                    val cr = conn.getHeaderField("Content-Range")
                    val total = cr?.substringAfterLast("/")?.trim()?.toLongOrNull() ?: -1L
                    val cl = conn.contentLengthLong
                    val ct = conn.contentType?.takeIf { it.isNotBlank() }

                    val data = readInputStream(conn.inputStream, chunkSize.toInt())
                    val latency = System.currentTimeMillis() - startTime
                    return ProbeResult(total, cl, ct, data, host, latency)
                } else if (code == 403 || code >= 500) {
                    penalizeHost(host, 20_000L)
                    reportFailover(host, "HTTP $code 错误", session.sessionId)
                }
            } catch (e: Exception) {
                penalizeHost(host, 15_000L)
                reportFailover(host, e.message ?: "连接超时", session.sessionId)
            } finally {
                conn?.disconnect()
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
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("Range", "bytes=${task.startByte}-${task.endByte}")
                    setRequestProperty("Referer", session.referer)
                    setRequestProperty("User-Agent", ua)
                    setRequestProperty("Connection", "keep-alive")
                }

                val code = conn.responseCode
                if (code == 200 || code == 206) {
                    val expectedBytes = (task.endByte - task.startByte + 1).toInt()
                    val bytes = readInputStream(conn.inputStream, expectedBytes)
                    val latency = System.currentTimeMillis() - startTime
                    task.data = bytes
                    task.isSuccess = true
                    statsTracker.recordChunk(host, bytes.size.toLong(), latency)
                    return
                } else if (code == 403 || code >= 500) {
                    penalizeHost(host, 20_000L)
                    reportFailover(host, "HTTP $code 熔断", session.sessionId)
                }
            } catch (e: Exception) {
                penalizeHost(host, 15_000L)
                reportFailover(host, "分片拉取超时/异常: ${e.message}", session.sessionId)
            } finally {
                conn?.disconnect()
            }
        }

        // 最终兜底：使用第一个可用 URL 尝试同步直连
        if (!task.isSuccess && urls.isNotEmpty()) {
            tryDirectFetch(task, urls.first(), session, statsTracker)
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
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=${task.startByte}-${task.endByte}")
                setRequestProperty("Referer", session.referer)
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Connection", "keep-alive")
            }
            if (conn.responseCode in 200..299) {
                val expectedBytes = (task.endByte - task.startByte + 1).toInt()
                val bytes = readInputStream(conn.inputStream, expectedBytes)
                task.data = bytes
                task.isSuccess = true
                statsTracker.recordChunk(host, bytes.size.toLong(), System.currentTimeMillis() - startTime)
            }
        } catch (_: Throwable) {
        } finally {
            conn?.disconnect()
        }
    }

    private fun readInputStream(input: InputStream, estimatedSize: Int): ByteArray {
        val out = ByteArrayOutputStream(if (estimatedSize > 0) estimatedSize else 16384)
        val buf = ByteArray(16384)
        var len: Int
        input.use { inStream ->
            while (inStream.read(buf).also { len = it } != -1) {
                out.write(buf, 0, len)
            }
        }
        return out.toByteArray()
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
