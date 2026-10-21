package io.github.jh_mmm.biliaccelerator.btr

import okhttp3.ConnectionPool
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

    // 全局基础客户端：开启 HTTP/2 多路复用和 HTTP/1.1
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(6000, TimeUnit.MILLISECONDS)
        .writeTimeout(5000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false) // 由 BtrChunkEngine 统一控制 Failover 与熔断
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
