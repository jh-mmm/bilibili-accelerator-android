package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

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
    private val clientThreadPool = Executors.newCachedThreadPool { r ->
        Thread(r, "BTR-ProxyWorker").apply { isDaemon = true }
    }

    @Synchronized
    fun start(concurrency: Int = 6, chunkSizeKb: Int = 512) {
        if (isRunning && port > 0) return
        try {
            // 绑定 127.0.0.1 回环地址，端口 0 由系统随机分配可用高位端口
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            isRunning = true
            Log.i(TAG, "BTR Local Proxy Server successfully started on 127.0.0.1:$port")

            val acceptThread = Thread({
                while (isRunning) {
                    try {
                        val client = ss.accept() ?: break
                        clientThreadPool.execute {
                            handleClientSocket(client, concurrency, chunkSizeKb)
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

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
        serverSocket = null
        port = -1
    }

    private fun handleClientSocket(socket: Socket, concurrency: Int, chunkSizeKb: Int) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0].uppercase()
                val path = parts[1]
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

                // 路径格式: /stream/{sessionId}.m4s
                val sessionId = path.substringAfter("/stream/").substringBefore(".m4s").substringBefore("?")
                val session = BtrSessionManager.get(sessionId)
                val out = s.getOutputStream()

                if (session == null) {
                    sendError(out, 404, "Not Found")
                    return
                }

                // 解析客户端的 Range 请求头: bytes=start-end
                val rangeHeader = headers["range"]
                val (rangeStart, rangeEnd) = parseRangeHeader(rangeHeader)

                BtrChunkEngine.pipeStream(
                    session = session,
                    requestedStart = rangeStart,
                    requestedEnd = rangeEnd,
                    isHeadOnly = isHeadOnly,
                    clientOut = out,
                    concurrency = concurrency,
                    chunkSizeKb = chunkSizeKb,
                    proxyPort = port
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun parseRangeHeader(rangeHeader: String?): Pair<Long, Long?> {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) {
            return Pair(0L, null)
        }
        val rangeSpec = rangeHeader.substringAfter("bytes=").trim()
        val parts = rangeSpec.split("-")
        val start = parts[0].toLongOrNull() ?: 0L
        val end = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toLongOrNull() else null
        return Pair(start, end)
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (_: Throwable) {}
    }
}
