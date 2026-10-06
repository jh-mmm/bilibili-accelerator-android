package io.github.jh_mmm.biliaccelerator.hook

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import io.github.jh_mmm.biliaccelerator.receiver.StatsReceiver
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RemoteClient {

    private const val TAG = "BiliAccelerator-Remote"
    private const val TARGET_PREFS_NAME = "bili_accelerator_target_cache"
    private const val KEY_CACHED_CONFIG = "cached_config_json"

    private val gson = Gson()

    // 独立线程池：配置拉取与上报隔离，彻底杜绝重负载日志上报饿死配置刷新
    private val configExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RemoteClient-ConfigWorker").apply { isDaemon = true }
    }
    private val reportScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RemoteClient-ReportWorker").apply { isDaemon = true }
    }

    @Volatile
    private var cachedConfig: AcceleratorConfig = AcceleratorConfig()
    private val lastConfigFetchTime = AtomicLong(0L)
    private val isFetching = AtomicBoolean(false)
    private const val CONFIG_CACHE_TTL = 30_000L // 30 秒缓存刷新

    // 批量上报队列与防抖定时器
    private val pendingBatch = ConcurrentLinkedQueue<RewriteResult>()
    private const val BATCH_MAX_SIZE = 10
    private const val FLUSH_INTERVAL_MS = 1500L
    private var pendingFlushFuture: ScheduledFuture<*>? = null
    private val flushLock = Any()
    private val lastBroadcastTime = AtomicLong(0L)

    @Volatile
    private var appContext: Context? = null

    fun setAppContext(context: Context) {
        val app = try {
            context.applicationContext ?: context
        } catch (_: Throwable) {
            context
        }
        appContext = app

        // 从目标 App 私有目录载入历史持久化配置，避免冷启动瞬态使用硬编码默认镜像
        loadConfigFromLocalCache(app)

        // 尽早触发一次异步配置拉取
        fetchConfigAsync(app)
    }

    fun getContext(): Context? {
        appContext?.let { return it }
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentAppMethod.invoke(null) as? Context
            if (app != null) {
                setAppContext(app)
                app
            } else null
        } catch (t: Throwable) {
            Log.d(TAG, "getContext via ActivityThread failed: ${t.message}")
            null
        }
    }

    private fun loadConfigFromLocalCache(context: Context) {
        try {
            val sp = context.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            val json = sp.getString(KEY_CACHED_CONFIG, null)
            if (!json.isNullOrEmpty()) {
                val saved = gson.fromJson(json, AcceleratorConfig::class.java)
                if (saved != null) {
                    cachedConfig = saved
                    Log.i(TAG, "Loaded cached config from local target app storage: targetHost=${saved.targetHost}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load local cached config: ${t.message}")
        }
    }

    private fun saveConfigToLocalCache(context: Context, config: AcceleratorConfig) {
        try {
            val sp = context.getSharedPreferences(TARGET_PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_CACHED_CONFIG, gson.toJson(config)).apply()
        } catch (_: Throwable) {}
    }

    fun fetchConfig(): AcceleratorConfig {
        val now = System.currentTimeMillis()
        val lastFetch = lastConfigFetchTime.get()

        if (now - lastFetch < CONFIG_CACHE_TTL && lastFetch != 0L) {
            return cachedConfig
        }

        val context = getContext() ?: return cachedConfig

        // 首次冷启动且尚未获取成功时，执行一次快速同步拉取，杜绝第一个视频走默认镜像的竞态
        if (lastFetch == 0L) {
            val direct = fetchConfigDirect(context)
            if (direct != null) {
                cachedConfig = direct
                lastConfigFetchTime.set(now)
                saveConfigToLocalCache(context, direct)
                return direct
            }
        }

        // 后续走后台异步刷新，避免阻塞播放线程
        fetchConfigAsync(context)
        return cachedConfig
    }

    private fun fetchConfigDirect(context: Context): AcceleratorConfig? {
        return try {
            val response = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_GET_CONFIG,
                null,
                null
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
                        saveConfigToLocalCache(context, config)
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
            val item = pendingBatch.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return

        val context = getContext()
        if (context == null) {
            Log.w(TAG, "统计批量上报跳过：目标进程 Context 为空 (丢弃 ${batch.size} 条)")
            return
        }

        val json = gson.toJson(batch)
        var reported = false

        // 1. 优先尝试 ContentProvider 批量 IPC
        try {
            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_REWRITE_BATCH_JSON, json)
            }
            val response = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_RECORD_BATCH_REWRITE,
                null,
                extras
            )
            if (response?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true) {
                reported = true
                Log.i(TAG, "[Provider] 批量上报成功 (${batch.size} 条重定向)")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[Provider] 暂不可达: ${t.javaClass.simpleName} (${t.message})")
        }

        // 2. 兜底通道：发送带鉴权 Token 的显式广播，附带限流防止广播风暴
        if (!reported) {
            val now = System.currentTimeMillis()
            if (now - lastBroadcastTime.get() >= 1000L) {
                lastBroadcastTime.set(now)
                try {
                    // 使用不可伪造的 PendingIntent 作为调用者身份鉴权凭证
                    val authIntent = Intent().apply {
                        setPackage(context.packageName)
                    }
                    val authToken = PendingIntent.getBroadcast(
                        context,
                        0,
                        authIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val intent = Intent(StatsReceiver.ACTION_RECORD_REWRITE).apply {
                        setPackage(BiliAcceleratorCore.MODULE_PACKAGE)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra(StatsReceiver.EXTRA_REWRITE_BATCH_JSON, json)
                        putExtra(StatsReceiver.EXTRA_AUTH_TOKEN, authToken)
                    }
                    context.sendBroadcast(intent)
                    Log.i(TAG, "[Broadcast] 备用通道批量发送成功 (${batch.size} 条重定向)")
                } catch (t: Throwable) {
                    Log.w(TAG, "[Broadcast] 发送异常: $t")
                }
            } else {
                Log.w(TAG, "[Broadcast] 广播限流保护触发，暂缓发送")
            }
        }
    }
}
