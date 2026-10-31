package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class ParsedRange(
    val hasRange: Boolean,
    val start: Long = 0L,
    val end: Long? = null,
    val isSuffix: Boolean = false,
    val suffixLength: Long = 0L
)

object BtrLocalProxyServer {

    private const val TAG = "BiliAccelerator-Proxy"

    @Volatile
    var port: Int = -1
        private set

    val isStarted: Boolean
        get() = port > 0 && isRunning

    @Volatile
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var currentConcurrency: Int = 6
    @Volatile
    private var currentChunkSizeKb: Int = 512
    @Volatile
    private var currentAutoConcurrency: Boolean = true
    @Volatile
    private var currentAutoChunkSize: Boolean = true

    // 有界弹性线程池，core=4, max=64, 保持 60 秒存活，杜绝线程无节制创建
    private val clientThreadPool = ThreadPoolExecutor(
        4,
        64,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(128)
    ) { r ->
        Thread(r, "BTR-ProxyWorker").apply { isDaemon = true }
    }

    @Synchronized
    fun start(concurrency: Int = 6, chunkSizeKb: Int = 512, autoConcurrency: Boolean = true, autoChunkSize: Boolean = true) {
        currentConcurrency = concurrency
        currentChunkSizeKb = chunkSizeKb
        currentAutoConcurrency = autoConcurrency
        currentAutoChunkSize = autoChunkSize

        if (isRunning && port > 0) return
        try {
            // 绑定 127.0.0.1 回环地址，调大 backlog 到 128 提升突发连接承载能力
            val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            isRunning = true
            Log.i(TAG, "BTR Local Proxy Server successfully started on 127.0.0.1:$port")

            val acceptThread = Thread({
                while (isRunning) {
                    try {
                        val client = ss.accept() ?: break
                        clientThreadPool.execute {
                            handleClientSocket(client, currentConcurrency, currentChunkSizeKb, currentAutoConcurrency, currentAutoChunkSize)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }, "BTR-ProxyAcceptor").apply { isDaemon = true }

            acceptThread.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start BTR local loopback proxy: ${t.message}", t)
            port = -1
            isRunning = false
        }
    }

    fun updateParameters(concurrency: Int, chunkSizeKb: Int, autoConcurrency: Boolean, autoChunkSize: Boolean) {
        currentConcurrency = concurrency
        currentChunkSizeKb = chunkSizeKb
        currentAutoConcurrency = autoConcurrency
        currentAutoChunkSize = autoChunkSize
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        port = -1
    }

    private fun handleClientSocket(socket: Socket, concurrency: Int, chunkSizeKb: Int, autoConcurrency: Boolean, autoChunkSize: Boolean) {
        socket.use { s ->
            try {
                // 网络协议栈调优：开启 tcpNoDelay 消除 Nagle 算法 40ms 延迟，调大发送缓冲区
                s.tcpNoDelay = true
                s.soTimeout = 15_000 // HTTP Keep-Alive 闲置超时 15 秒
                s.sendBufferSize = 256 * 1024
                s.receiveBufferSize = 64 * 1024

                val rawIn = s.getInputStream()
                val rawOut = s.getOutputStream()
                val out = BufferedOutputStream(rawOut, 64 * 1024)
                val reader = BufferedReader(InputStreamReader(rawIn, Charsets.US_ASCII))

                // 支持 HTTP/1.1 长连接（Keep-Alive）复用循环，彻底杜绝单请求反复建立 TCP 与 TIME_WAIT 端口耗尽
                while (isRunning && !s.isClosed) {
                    val requestLine = try {
                        reader.readLine()
                    } catch (_: java.net.SocketTimeoutException) {
                        break // Keep-Alive 闲置超时退出
                    } catch (_: java.net.SocketException) {
                        break
                    } ?: break

                    if (requestLine.isBlank()) continue

                    val parts = requestLine.split(" ")
                    if (parts.size < 2) break

                    val method = parts[0].uppercase()
                    val path = parts[1]
                    val httpVersion = if (parts.size >= 3) parts[2].uppercase() else "HTTP/1.1"
                    val isHeadOnly = method == "HEAD"

                    val headers = mutableMapOf<String, String>()
                    var line: String?
                    while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                        val colonIdx = line!!.indexOf(':')
                        if (colonIdx > 0) {
                            val k = line!!.substring(0, colonIdx).trim().lowercase()
                            val v = line!!.substring(colonIdx + 1).trim()
                            headers[k] = v
                        }
                    }

                    // HTTP/1.1 默认启用 Keep-Alive，除非客户端显式请求 Connection: close
                    val clientConn = headers["connection"]?.lowercase()
                    val isHttp11 = httpVersion == "HTTP/1.1"
                    val shouldKeepAlive = when (clientConn) {
                        "close" -> false
                        "keep-alive" -> true
                        else -> isHttp11
                    }

                    // 路径格式: /stream/{sessionId}.m4s, /stream/{sessionId}.flv, /stream/{sessionId}.m3u8 或 /stream/{sessionId}/segment.m4s?url=...
                    val streamPart = path.substringAfter("/stream/").substringBefore("?")
                    val sessionId = if (streamPart.contains("/")) {
                        streamPart.substringBefore("/")
                    } else {
                        streamPart.substringBefore(".")
                    }
                    val session = BtrSessionManager.get(sessionId)

                    if (session == null) {
                        sendError(out, 404, "Not Found")
                        break
                    }

                    // 动态透传播放器发来的真实 User-Agent 与 Referer，杜绝 Dalvik UA 触发 CDN 403 熔断
                    headers["user-agent"]?.let { ua ->
                        if (ua.isNotBlank()) session.userAgent = ua
                    }
                    headers["referer"]?.let { ref ->
                        if (ref.isNotBlank()) session.referer = ref
                    }

                    val streamSuccess = if (session.isLive) {
                        when {
                            path.contains(".m3u8") -> {
                                BtrLiveEngine.pipePlaylist(session, path, headers, out, port)
                            }
                            path.contains(".flv") -> {
                                BtrLiveEngine.pipeLiveFlvStream(session, headers, out)
                            }
                            else -> {
                                BtrLiveEngine.pipeSegment(session, path, headers, out)
                            }
                        }
                    } else {
                        // 解析客户端的 Range 请求头: 支持 bytes=start-end, bytes=start-, bytes=-suffixLen 及 bytes=0-0
                        val rangeHeader = headers["range"]
                        val parsedRange = parseRangeHeader(rangeHeader)

                        BtrChunkEngine.pipeStream(
                            session = session,
                            requestedStart = parsedRange.start,
                            requestedEnd = parsedRange.end,
                            isHeadOnly = isHeadOnly,
                            clientOut = out,
                            concurrency = concurrency,
                            chunkSizeKb = chunkSizeKb,
                            autoConcurrency = autoConcurrency,
                            autoChunkSize = autoChunkSize,
                            proxyPort = port,
                            parsedRange = parsedRange,
                            keepAlive = shouldKeepAlive
                        )
                    }

                    if (!shouldKeepAlive || !streamSuccess) {
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun parseRangeHeader(rangeHeader: String?): ParsedRange {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) {
            return ParsedRange(hasRange = false, start = 0L, end = null)
        }
        val rangeSpec = rangeHeader.substringAfter("bytes=").trim()
        val singleRange = rangeSpec.substringBefore(',').trim()
        val dashIdx = singleRange.indexOf('-')
        if (dashIdx == -1) {
            return ParsedRange(hasRange = false, start = 0L, end = null)
        }
        val startStr = singleRange.substring(0, dashIdx).trim()
        val endStr = singleRange.substring(dashIdx + 1).trim()

        if (startStr.isEmpty()) {
            // Suffix range: bytes=-500 (请求媒体文件的最后 500 字节，例如播放器解析 MP4 末尾 moov box)
            val suffixLen = endStr.toLongOrNull()
            return if (suffixLen != null && suffixLen > 0L) {
                ParsedRange(hasRange = true, isSuffix = true, suffixLength = suffixLen)
            } else {
                ParsedRange(hasRange = false, start = 0L, end = null)
            }
        }

        val start = startStr.toLongOrNull() ?: 0L
        val end = if (endStr.isNotEmpty()) endStr.toLongOrNull() else null
        return ParsedRange(hasRange = true, start = start, end = end)
    }

    fun parseRangeHeaderPair(rangeHeader: String?): Pair<Long, Long?> {
        val r = parseRangeHeader(rangeHeader)
        return Pair(r.start, r.end)
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (_: Throwable) {}
    }
}
