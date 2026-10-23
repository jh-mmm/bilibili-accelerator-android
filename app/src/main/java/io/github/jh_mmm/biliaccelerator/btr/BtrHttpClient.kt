package io.github.jh_mmm.biliaccelerator.btr

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

object BtrHttpClient {

    // 连接池复用：支持高达 32 个空闲 HTTP/2 或 Keep-Alive 连接，保持 5 分钟活性
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 32,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    // 音频流专属独立连接池：8 个空闲连接，与视频流物理隔离，杜绝高码率视频 16 线程占满连接池导致音频饥饿
    private val audioConnectionPool = ConnectionPool(
        maxIdleConnections = 8,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    // 关键并发解除：OkHttp 默认 maxRequestsPerHost=5 会在多线程拉取单主节点时强行将请求排队，必须提升上限
    private fun createDispatcher(maxRequests: Int = 128, maxPerHost: Int = 32): Dispatcher {
        return Dispatcher().apply {
            this.maxRequests = maxRequests
            this.maxRequestsPerHost = maxPerHost
        }
    }

    // 全局基础客户端：开启 HTTP/2 多路复用和 HTTP/1.1，解除单节点并发瓶颈
    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(createDispatcher(128, 32))
        .connectionPool(connectionPool)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(6000, TimeUnit.MILLISECONDS)
        .writeTimeout(5000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false) // 由 BtrChunkEngine 统一控制 Failover 与熔断
        .build()

    // 音频专属独立客户端：使用独立连接池与独立调度器，低时延优先
    val audioClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(createDispatcher(32, 16))
        .connectionPool(audioConnectionPool)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .writeTimeout(5000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    // 探针专用客户端：更紧凑的超时控制
    val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(6000, TimeUnit.MILLISECONDS)
        .build()

    // 兜底直连专用客户端
    val fallbackClient: OkHttpClient = client.newBuilder()
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(8000, TimeUnit.MILLISECONDS)
        .build()
}
