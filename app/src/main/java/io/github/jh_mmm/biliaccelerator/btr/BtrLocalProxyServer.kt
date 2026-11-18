package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class ParsedRange(
    val hasRange: Boolean,
    val start: Long = 0L,
    val end: Long? = null,
    val isSuffix: Boolean = false,
    val suffixLength: Long = 0L,
    val isInvalid: Boolean = false
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

    // 直通弹性线程池（SynchronousQueue）：不排队阻塞在 Keep-Alive / FLV 长连接后面，杜绝 >4 连接卡死
    private val clientThreadPool = ThreadPoolExecutor(
        8,
        128,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue()
    ) { r ->
        Thread(r, "BTR-ProxyWorker").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
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
                    var client: Socket? = null
                    try {
                        client = ss.accept() ?: break
                        val accepted = client
                        clientThreadPool.execute {
                            handleClientSocket(accepted)
                        }
                    } catch (e: Exception) {
                        runCatching { client?.close() }
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

    private fun handleClientSocket(socket: Socket) {
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
                    if (method != "GET" && method != "HEAD") {
                        sendError(out, 405, "Method Not Allowed")
                        break
                    }
                    val isHeadOnly = method == "HEAD"

                    val headers = mutableMapOf<String, String>()
                    var eofMidHeaders = false
                    while (true) {
                        val line = reader.readLine()
                        if (line == null) {
                            eofMidHeaders = true
                            break
                        }
                        if (line.isEmpty()) break
                        val colonIdx = line.indexOf(':')
                        if (colonIdx > 0) {
                            val k = line.substring(0, colonIdx).trim().lowercase()
                            val v = line.substring(colonIdx + 1).trim()
                            headers[k] = v
                        }
                    }
                    if (eofMidHeaders) break

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

                    // 仅按单次请求透传合法 User-Agent / Referer，避免探测或异常请求头永久污染 session 共享状态
                    val reqUserAgent = headers["user-agent"]
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank() &&
                                !it.startsWith("Dalvik/", ignoreCase = true) &&
                                !it.startsWith("Java/", ignoreCase = true)
                        }
                        ?: session.userAgent
                    if (session.userAgent.isNullOrBlank() && !reqUserAgent.isNullOrBlank()) {
                        synchronized(session.urlLock) {
                            if (session.userAgent.isNullOrBlank()) {
                                session.userAgent = reqUserAgent
                            }
                        }
                    }
                    val reqReferer = headers["referer"]
                        ?.trim()
                        ?.takeIf { ref ->
                            ref.isNotBlank() && (
                                ref.contains("bilibili.com", ignoreCase = true) ||
                                    ref.contains("biliintl.com", ignoreCase = true) ||
                                    ref.contains("bilibili.tv", ignoreCase = true) ||
                                    ref.contains("bstarstatic.com", ignoreCase = true)
                                )
                        }
                        ?: session.referer

                    val requestPathOnly = path.substringBefore('?')
                    val keepConnectionAlive = if (session.isLive) {
                        when {
                            requestPathOnly.endsWith(".m3u8", ignoreCase = true) -> {
                                BtrLiveEngine.pipePlaylist(
                                    session = session,
                                    requestPath = path,
                                    headers = headers,
                                    clientOut = out,
                                    proxyPort = port,
                                    keepAlive = shouldKeepAlive,
                                    isHeadOnly = isHeadOnly,
                                    requestUserAgent = reqUserAgent,
                                    requestReferer = reqReferer
                                )
                            }
                            requestPathOnly.endsWith(".flv", ignoreCase = true) -> {
                                BtrLiveEngine.pipeLiveFlvStream(
                                    session = session,
                                    headers = headers,
                                    clientOut = out,
                                    isHeadOnly = isHeadOnly,
                                    requestUserAgent = reqUserAgent,
                                    requestReferer = reqReferer
                                )
                            }
                            else -> {
                                BtrLiveEngine.pipeSegment(
                                    session = session,
                                    requestPath = path,
                                    headers = headers,
                                    clientOut = out,
                                    keepAlive = shouldKeepAlive,
                                    isHeadOnly = isHeadOnly,
                                    requestUserAgent = reqUserAgent,
                                    requestReferer = reqReferer
                                )
                            }
                        }
                    } else {
                        // 解析客户端的 Range 请求头: 支持 bytes=start-end, bytes=start-, bytes=-suffixLen 及 bytes=0-0
                        val rangeHeader = headers["range"]
                        val parsedRange = parseRangeHeader(rangeHeader)

                        val reqConcurrency = currentConcurrency
                        val reqChunkSizeKb = currentChunkSizeKb
                        val reqAutoConcurrency = currentAutoConcurrency
                        val reqAutoChunkSize = currentAutoChunkSize

                        BtrChunkEngine.pipeStream(
                            session = session,
                            requestedStart = parsedRange.start,
                            requestedEnd = parsedRange.end,
                            isHeadOnly = isHeadOnly,
                            clientOut = out,
                            concurrency = reqConcurrency,
                            chunkSizeKb = reqChunkSizeKb,
                            autoConcurrency = reqAutoConcurrency,
                            autoChunkSize = reqAutoChunkSize,
                            proxyPort = port,
                            parsedRange = parsedRange,
                            keepAlive = shouldKeepAlive,
                            requestUserAgent = reqUserAgent,
                            requestReferer = reqReferer
                        )
                    }

                    if (!shouldKeepAlive || !keepConnectionAlive) {
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun parseRangeHeader(rangeHeader: String?): ParsedRange {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.trim().startsWith("bytes=", ignoreCase = true)) {
            return ParsedRange(hasRange = false, start = 0L, end = null)
        }
        val rangeSpec = rangeHeader.substringAfter("=").trim()
        val singleRange = rangeSpec.substringBefore(',').trim()
        val dashIdx = singleRange.indexOf('-')
        if (dashIdx == -1) {
            return ParsedRange(hasRange = false, start = 0L, end = null)
        }
        val startStr = singleRange.substring(0, dashIdx).trim()
        val endStr = singleRange.substring(dashIdx + 1).trim()

        if (startStr.isEmpty()) {
            // Suffix range: bytes=-500 (请求媒体文件的最后 500 字节)
            val suffixLen = endStr.toLongOrNull()
            return if (suffixLen != null && suffixLen > 0L) {
                ParsedRange(hasRange = true, isSuffix = true, suffixLength = suffixLen)
            } else {
                ParsedRange(hasRange = true, isSuffix = true, suffixLength = 0L, isInvalid = true)
            }
        }

        val start = startStr.toLongOrNull()
        val end = if (endStr.isNotEmpty()) endStr.toLongOrNull() else null
        if (start == null || start < 0L || (endStr.isNotEmpty() && (end == null || end < start))) {
            return ParsedRange(hasRange = true, start = start ?: 0L, end = end, isInvalid = true)
        }
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
