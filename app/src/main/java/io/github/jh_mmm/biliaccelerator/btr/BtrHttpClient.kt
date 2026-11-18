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

    // 全局基础客户端：采用 HTTP/1.1 独立连接池（每并发独立 TCP 通道），避免 HTTP/2 单连接复用导致 16 线程分片与直播流触发 RST_STREAM/GOAWAY 或队头阻塞
    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(createDispatcher(128, 32))
        .connectionPool(connectionPool)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(8_000, TimeUnit.MILLISECONDS)
        .readTimeout(20_000, TimeUnit.MILLISECONDS)
        .writeTimeout(10_000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false) // 由 BtrChunkEngine 统一控制 Failover 与熔断
        .build()

    // 音频专属独立客户端：使用独立连接池与独立调度器，HTTP/1.1 低时延优先
    val audioClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(createDispatcher(32, 16))
        .connectionPool(audioConnectionPool)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(6_000, TimeUnit.MILLISECONDS)
        .readTimeout(15_000, TimeUnit.MILLISECONDS)
        .writeTimeout(10_000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    const val CHUNK_ATTEMPT_CALL_TIMEOUT_MS = 25_000L

    // 单分片拉取专用客户端：附加 25s 总调用超时，防止慢速滴流节点无限占用并发槽位，同时避免大切片正常传输被过紧超时误杀
    val chunkClient: OkHttpClient = client.newBuilder()
        .callTimeout(CHUNK_ATTEMPT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // 音频单分片拉取专用客户端：独立池 + 25s 总调用超时
    val chunkAudioClient: OkHttpClient = audioClient.newBuilder()
        .callTimeout(CHUNK_ATTEMPT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // 探针专用客户端：紧凑超时控制，避免慢节点卡死起播
    val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(5_000, TimeUnit.MILLISECONDS)
        .readTimeout(6_000, TimeUnit.MILLISECONDS)
        .callTimeout(7_000, TimeUnit.MILLISECONDS)
        .build()

    // 兜底直连专用客户端
    val fallbackClient: OkHttpClient = client.newBuilder()
        .connectTimeout(8_000, TimeUnit.MILLISECONDS)
        .readTimeout(20_000, TimeUnit.MILLISECONDS)
        .build()

    // 直播 FLV 长流透传专用客户端：HTTP/1.1 独立连接 + 30s 读超时，避免上游短暂推流卡顿或 HTTP/2 复用触发断流
    val liveStreamClient: OkHttpClient = client.newBuilder()
        .connectTimeout(6_000, TimeUnit.MILLISECONDS)
        .readTimeout(30_000, TimeUnit.MILLISECONDS)
        .writeTimeout(10_000, TimeUnit.MILLISECONDS)
        .build()
}
