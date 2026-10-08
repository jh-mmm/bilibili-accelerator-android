package io.github.jh_mmm.biliaccelerator.hook

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RemoteClient {

    private const val TAG = "BiliAccelerator-Remote"
    private const val TARGET_PREFS_NAME = "bili_accelerator_target_cache"

    private val gson = Gson()

    // 独立线程池：配置拉取与上报隔离，彻底杜绝重负载日志上报饿死配置刷新
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RemoteClient-ConfigWorker").apply { isDaemon = true }
    }
    private val reportScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RemoteClient-ReportWorker").apply { isDaemon = true }
    }

    // 默认 fail-closed：拉取成功前不开启加速，确保模块冻结/卸载时安全失效
    @Volatile
    private var cachedConfig: AcceleratorConfig = AcceleratorConfig(enabled = false)
    private val lastConfigFetchTime = AtomicLong(0L)
    private val isFetching = AtomicBoolean(false)
    private const val CONFIG_CACHE_TTL = 30_000L // 30 秒缓存刷新

    // 批量上报双端队列与防抖定时器
    private val pendingBatch = ConcurrentLinkedDeque<RewriteResult>()
    private const val BATCH_MAX_SIZE = 10
    private const val QUEUE_MAX_CAPACITY = 200
    private const val FLUSH_INTERVAL_MS = 1500L
    private var pendingFlushFuture: ScheduledFuture<*>? = null
    private val flushLock = Any()

    @Volatile
    private var appContext: Context? = null

    // 缓存 ActivityThread.currentApplication 反射方法，避免每次媒体请求重复反射查找
    private val currentApplicationMethod: Method? by lazy {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            activityThreadClass.getMethod("currentApplication")
        } catch (t: Throwable) {
            Log.d(TAG, "ActivityThread.currentApplication not accessible: ${t.message}")
            null
        }
    }

    fun setAppContext(context: Context) {
        val app = try {
            context.applicationContext ?: context
        } catch (_: Throwable) {
            context
        }
        appContext = app

        // 清理可能遗留的历史目标私有目录缓存文件，杜绝污染目标 App 存储
        try {
            app.deleteSharedPreferences(TARGET_PREFS_NAME)
        } catch (_: Throwable) {}

        // 尽早触发一次异步配置拉取
        fetchConfigAsync(app)
    }

    fun getContext(): Context? {
        appContext?.let { return it }
        return try {
            val app = currentApplicationMethod?.invoke(null) as? Context
            if (app != null) {
                setAppContext(app)
                app
            } else null
        } catch (t: Throwable) {
            Log.d(TAG, "getContext failed: ${t.message}")
            null
        }
    }

    fun fetchConfig(): AcceleratorConfig {
        val now = System.currentTimeMillis()
        val lastFetch = lastConfigFetchTime.get()

        if (now - lastFetch < CONFIG_CACHE_TTL && lastFetch != 0L) {
            return cachedConfig
        }

        val context = getContext() ?: return cachedConfig

        // 纯后台异步刷新，决不阻塞播放或 gRPC 线程
        fetchConfigAsync(context)
        return cachedConfig
    }

    private fun fetchConfigDirect(context: Context): AcceleratorConfig? {
        return try {
            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_HOOK_STATUS_JSON, gson.toJson(HookStatusTracker.getSnapshot()))
            }
            val response = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_GET_CONFIG,
                null,
                extras
            )
            val json = response?.getString(StatsProvider.EXTRA_CONFIG_JSON)
            if (!json.isNullOrEmpty()) {
                gson.fromJson(json, AcceleratorConfig::class.java)
            } else null
        } catch (t: Throwable) {
            Log.d(TAG, "Direct config fetch failed: ${t.message}")
            null
        }
    }

    private fun fetchConfigAsync(context: Context) {
        if (isFetching.compareAndSet(false, true)) {
            configExecutor.execute {
                try {
                    val config = fetchConfigDirect(context)
                    if (config != null) {
                        cachedConfig = config
                        lastConfigFetchTime.set(System.currentTimeMillis())
                    } else {
                        // 拉取失败退避 5 秒
                        lastConfigFetchTime.set(System.currentTimeMillis() - CONFIG_CACHE_TTL + 5_000L)
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Async config fetch exception: ${t.message}")
                    lastConfigFetchTime.set(System.currentTimeMillis() - CONFIG_CACHE_TTL + 5_000L)
                } finally {
                    isFetching.set(false)
                }
            }
        }
    }

    fun notifyRewrite(result: RewriteResult) {
        pendingBatch.offer(result)

        if (pendingBatch.size >= BATCH_MAX_SIZE) {
            reportScheduler.execute { flushBatch() }
        } else {
            synchronized(flushLock) {
                pendingFlushFuture?.cancel(false)
                pendingFlushFuture = reportScheduler.schedule({
                    flushBatch()
                }, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun flushBatch() {
        val batch = mutableListOf<RewriteResult>()
        while (batch.size < 50) {
            val item = pendingBatch.pollFirst() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return

        val context = getContext()
        if (context == null) {
            Log.w(TAG, "统计批量上报跳过：目标进程 Context 为空 (丢弃 ${batch.size} 条)")
            return
        }

        val json = gson.toJson(batch)
        val hookStatusJson = gson.toJson(HookStatusTracker.getSnapshot())
        var reported = false

        // 1. 优先尝试 ContentProvider 批量 IPC
        try {
            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_REWRITE_BATCH_JSON, json)
                putString(StatsProvider.EXTRA_HOOK_STATUS_JSON, hookStatusJson)
            }
            val response = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_RECORD_BATCH_REWRITE,
                null,
                extras
            )
            if (response?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true) {
                reported = true
                Log.i(TAG, "[Provider] 批量上报成功 (${batch.size} 条媒体请求)")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[Provider] 暂不可达: ${t.javaClass.simpleName} (${t.message})")
        }

        // 2. 若 Provider 暂时不可达，保留最多 QUEUE_MAX_CAPACITY 条记录，稍后重试
        if (!reported) {
            val overflow = pendingBatch.size + batch.size - QUEUE_MAX_CAPACITY
            val itemsToKeep = if (overflow > 0) batch.drop(overflow) else batch
            for (item in itemsToKeep.asReversed()) {
                pendingBatch.offerFirst(item)
            }
            synchronized(flushLock) {
                pendingFlushFuture?.cancel(false)
                pendingFlushFuture = reportScheduler.schedule({
                    flushBatch()
                }, 5000L, TimeUnit.MILLISECONDS)
            }
        }
    }
}
