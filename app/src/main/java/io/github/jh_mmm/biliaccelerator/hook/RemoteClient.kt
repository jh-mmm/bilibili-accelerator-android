package io.github.jh_mmm.biliaccelerator.hook

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
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
    private val configExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RemoteClient-ConfigWorker").apply { isDaemon = true }
    }
    private val reportScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RemoteClient-ReportWorker").apply { isDaemon = true }
    }

    // 1.0.5 经典逻辑：默认 fail-open (enabled = true)，即便目标应用（如国际版）在部分系统上无法访问 Provider，拦截与重定向仍能正常按默认镜像运行
    @Volatile
    private var cachedConfig: AcceleratorConfig = AcceleratorConfig(enabled = true)
    private val lastConfigFetchTime = AtomicLong(0L)
    private val isFetching = AtomicBoolean(false)
    private const val CONFIG_CACHE_TTL = 30_000L

    private val pendingBatch = ConcurrentLinkedQueue<RewriteResult>()
    private const val BATCH_MAX_SIZE = 10
    private const val FLUSH_INTERVAL_MS = 1500L
    private var pendingFlushFuture: ScheduledFuture<*>? = null
    private val flushLock = Any()

    @Volatile
    private var appContext: Context? = null

    fun setAppContext(context: Context) {
        val app = try {
            context.applicationContext ?: context
        } catch (_: Throwable) {
            context
        }
        appContext = app

        // 从目标 App 私有目录载入历史持久化配置（1.0.5 逻辑，避免冷启动使用硬编码默认镜像）
        loadConfigFromLocalCache(app)

        // 发送启动握手（同步包名、版本，更新看板“等待拦截”活跃状态）
        sendHandshakeAsync(app)

        // 异步拉取最新配置
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

        // 首次冷启动且尚未获取成功时，执行一次同步直接拉取
        if (lastFetch == 0L) {
            val direct = fetchConfigDirect(context)
            if (direct != null) {
                cachedConfig = direct
                lastConfigFetchTime.set(now)
                saveConfigToLocalCache(context, direct)
                return direct
            }
        }

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

    fun sendHandshakeAsync(context: Context) {
        configExecutor.execute {
            sendHandshakeDirect(context)
        }
    }

    private fun sendHandshakeDirect(context: Context) {
        try {
            val pkgName = context.packageName
            val pm = context.packageManager
            val pInfo = runCatching { pm.getPackageInfo(pkgName, 0) }.getOrNull()
            val verName = pInfo?.versionName ?: "unknown"

            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_CLIENT_PACKAGE, pkgName)
                putString(StatsProvider.EXTRA_CLIENT_VERSION, verName)
                putString(StatsProvider.EXTRA_HOOK_STATUS_JSON, gson.toJson(HookStatusTracker.getSnapshot()))
            }
            context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_HANDSHAKE,
                null,
                extras
            )
            Log.i(TAG, "Successfully sent handshake to module provider from $pkgName (v$verName)")
        } catch (t: Throwable) {
            Log.d(TAG, "sendHandshakeDirect error: ${t.message}")
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

    internal fun sanitizeForIpc(result: RewriteResult): RewriteResult {
        val segKey = result.segmentKey.ifBlank {
            BiliAcceleratorCore.parseUri(result.originalUrl)?.let { BiliAcceleratorCore.extractSegmentKey(it.path) } ?: ""
        }
        return RewriteResult(
            changed = result.changed,
            originalUrl = "",
            finalUrl = "",
            originalHost = result.originalHost,
            targetHost = result.targetHost,
            reason = result.reason,
            isPcdn = result.isPcdn,
            isMcdn = result.isMcdn,
            segmentKey = segKey
        )
    }

    private fun flushBatch() {
        val batch = mutableListOf<RewriteResult>()
        while (batch.size < 50) {
            val item = pendingBatch.poll() ?: break
            batch.add(sanitizeForIpc(item))
        }
        if (batch.isEmpty()) return

        val context = getContext()
        if (context == null) {
            Log.w(TAG, "统计批量上报跳过：目标进程 Context 为空 (丢弃 ${batch.size} 条)")
            return
        }

        val json = gson.toJson(batch)
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
                Log.i(TAG, "[Provider] 批量上报成功 (${batch.size} 条重定向)")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[Provider] 暂不可达: ${t.javaClass.simpleName} (${t.message})")
        }
    }
}
