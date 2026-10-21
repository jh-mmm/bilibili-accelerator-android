package io.github.jh_mmm.biliaccelerator.hook

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
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

    internal fun resolveProcessName(context: Context? = null): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { android.app.Application.getProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getDeclaredMethod("currentProcessName")
            method.isAccessible = true
            (method.invoke(null) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }
        return context?.packageName
    }

    private fun sendHandshakeDirect(context: Context) {
        val pkgName = context.packageName
        val procName = resolveProcessName(context) ?: pkgName
        if (!HookEntry.isRelevantProcess(pkgName, procName)) {
            return
        }
        val pm = context.packageManager
        val pInfo = runCatching { pm.getPackageInfo(pkgName, 0) }.getOrNull()
        val verName = pInfo?.versionName ?: "unknown"
        val hookSnapshotJson = gson.toJson(HookStatusTracker.getSnapshot())
        var handshakeOk = false

        // 1. 优先尝试 ContentProvider 握手
        try {
            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_CLIENT_PACKAGE, pkgName)
                putString(StatsProvider.EXTRA_CLIENT_VERSION, verName)
                putString(StatsProvider.EXTRA_CLIENT_PROCESS, procName)
                putString(StatsProvider.EXTRA_HOOK_STATUS_JSON, hookSnapshotJson)
            }
            val res = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_HANDSHAKE,
                null,
                extras
            )
            if (res?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true) {
                handshakeOk = true
                Log.i(TAG, "Successfully sent handshake to module provider from $pkgName ($procName, v$verName)")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "sendHandshakeDirect via provider failed: ${t.message}")
        }

        // 2. 兜底通道：若 Provider 不可达（如高版本 Android 包可见性拦截），发送握手显式广播
        if (!handshakeOk) {
            try {
                val authIntent = Intent().apply {
                    setPackage(pkgName)
                }
                val authToken = PendingIntent.getBroadcast(
                    context,
                    0,
                    authIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val intent = Intent(StatsReceiver.ACTION_HANDSHAKE).apply {
                    setPackage(BiliAcceleratorCore.MODULE_PACKAGE)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(StatsReceiver.EXTRA_CLIENT_PACKAGE, pkgName)
                    putExtra(StatsReceiver.EXTRA_CLIENT_VERSION, verName)
                    putExtra(StatsReceiver.EXTRA_CLIENT_PROCESS, procName)
                    putExtra(StatsReceiver.EXTRA_HOOK_STATUS_JSON, hookSnapshotJson)
                    putExtra(StatsReceiver.EXTRA_AUTH_TOKEN, authToken)
                }
                context.sendBroadcast(intent)
                Log.i(TAG, "Successfully sent handshake to module via broadcast fallback from $pkgName ($procName, v$verName)")
            } catch (t: Throwable) {
                Log.w(TAG, "sendHandshakeDirect via broadcast failed: ${t.message}")
            }
        }
    }

    fun notifyRewrite(result: RewriteResult) {
        pendingBatch.offer(sanitizeForIpc(result))

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
            segmentKey = segKey,
            isBtr = result.isBtr,
            btrChunks = result.btrChunks,
            btrBytes = result.btrBytes,
            btrConcurrency = result.btrConcurrency,
            btrCdnCount = result.btrCdnCount,
            btrAvgLatencyMs = result.btrAvgLatencyMs
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

        // 2. 兜底通道：发送带鉴权 Token 的显式广播，附带限流防止广播风暴（1.0.5 经典双通道）
        if (!reported) {
            val now = System.currentTimeMillis()
            val timeSinceLastBroadcast = now - lastBroadcastTime.get()
            if (timeSinceLastBroadcast >= 1000L) {
                lastBroadcastTime.set(now)
                try {
                    val authIntent = Intent().apply {
                        setPackage(context.packageName)
                    }
                    val authToken = PendingIntent.getBroadcast(
                        context,
                        0,
                        authIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val procName = resolveProcessName(context) ?: context.packageName
                    val intent = Intent(StatsReceiver.ACTION_RECORD_REWRITE).apply {
                        setPackage(BiliAcceleratorCore.MODULE_PACKAGE)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra(StatsReceiver.EXTRA_REWRITE_BATCH_JSON, json)
                        putExtra(StatsReceiver.EXTRA_AUTH_TOKEN, authToken)
                        putExtra(StatsReceiver.EXTRA_CLIENT_PACKAGE, context.packageName)
                        putExtra(StatsReceiver.EXTRA_CLIENT_PROCESS, procName)
                    }
                    context.sendBroadcast(intent)
                    Log.i(TAG, "[Broadcast] 备用通道批量发送成功 (${batch.size} 条重定向)")
                } catch (t: Throwable) {
                    Log.w(TAG, "[Broadcast] 发送异常: $t")
                }
            } else {
                // 修复丢包缺陷：将尚未发送的批次数据重新回填到队列头部，并安排延迟重试
                for (item in batch) {
                    pendingBatch.offer(item)
                }
                val retryDelay = maxOf(100L, 1000L - timeSinceLastBroadcast)
                synchronized(flushLock) {
                    pendingFlushFuture?.cancel(false)
                    pendingFlushFuture = reportScheduler.schedule({
                        flushBatch()
                    }, retryDelay, TimeUnit.MILLISECONDS)
                }
                Log.d(TAG, "[Broadcast] 广播限流保护触发，已回填 ${batch.size} 条数据并将于 ${retryDelay}ms 后重试")
            }
        }
    }
}
