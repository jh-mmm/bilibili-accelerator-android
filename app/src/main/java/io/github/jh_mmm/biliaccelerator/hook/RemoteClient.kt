package io.github.jh_mmm.biliaccelerator.hook

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import io.github.jh_mmm.biliaccelerator.receiver.StatsReceiver
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
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
    private val contextInitialized = AtomicBoolean(false)
    private val isObserverRegistered = AtomicBoolean(false)
    private val isSyncReceiverRegistered = AtomicBoolean(false)
    private val isRemotePrefsAttached = AtomicBoolean(false)
    @Volatile
    private var remotePrefsRef: SharedPreferences? = null
    @Volatile
    private var remotePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile
    private var lastSyncedHookStatus: Map<String, String> = emptyMap()

    fun getCachedConfig(): AcceleratorConfig = cachedConfig

    internal fun setCachedConfigForTesting(config: AcceleratorConfig, fetchTimeMs: Long = System.currentTimeMillis()) {
        cachedConfig = config
        lastConfigFetchTime.set(fetchTimeMs)
    }

    fun attachXposedModule(module: XposedModule) {
        if (!isRemotePrefsAttached.compareAndSet(false, true)) return
        try {
            if ((module.frameworkProperties and XposedInterface.PROP_CAP_REMOTE) == 0L) return
            val prefs = module.getRemotePreferences(XposedServiceProvider.REMOTE_CONFIG_GROUP)
            remotePrefsRef = prefs
            applyConfigJsonFromRemotePrefs(prefs)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                if (key == XposedServiceProvider.KEY_REMOTE_CONFIG_JSON || key == null) {
                    applyConfigJsonFromRemotePrefs(sp)
                }
            }
            remotePrefsListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
            Log.d(TAG, "RemotePreferences unavailable: ${t.message}")
        }
    }

    private fun applyConfigJsonFromRemotePrefs(prefs: SharedPreferences) {
        try {
            val json = prefs.getString(XposedServiceProvider.KEY_REMOTE_CONFIG_JSON, null)
            if (!json.isNullOrBlank()) {
                val cfg = gson.fromJson(json, AcceleratorConfig::class.java) ?: return
                cachedConfig = cfg
                lastConfigFetchTime.set(System.currentTimeMillis())
                appContext?.let { saveConfigToLocalCache(it, cfg) }
                syncBtrProxyState(cfg)
                Log.i(TAG, "Synced config via LibXposed RemotePreferences: targetHost=${cfg.targetHost}")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to parse RemotePreferences config: ${t.message}")
        }
    }

    fun setAppContext(context: Context) {
        val app = try {
            context.applicationContext ?: context
        } catch (_: Throwable) {
            context
        }
        appContext = app

        if (!contextInitialized.compareAndSet(false, true)) {
            return
        }

        // 初始化跨进程共享状态目录（Playinfo 元数据与跨会话并发持久化）
        PlayinfoRegistry.configureSharedStateDir(runCatching { app.noBackupFilesDir ?: app.cacheDir }.getOrNull())

        // 从目标 App 私有目录载入历史持久化配置，若未从 RemotePreferences 同步到则使用本地缓存
        if (lastConfigFetchTime.get() == 0L) {
            loadConfigFromLocalCache(app)
        } else {
            saveConfigToLocalCache(app, cachedConfig)
        }

        // 按需更新 BTR 参数（实际代理套接字在首次媒体拦截时按进程惰性启动，避免非播放进程多开空闲线程池）
        syncBtrProxyState(cachedConfig)

        // 注册 ContentObserver 监听配置变更（Android < 11 或可见性允许时实现毫秒级同步）
        registerConfigObserver(app)

        // 注册双向配置同步广播接收器（兜底 Android 11+ 包可见性反向阻断 StatsProvider）
        registerConfigSyncReceiver(app)

        // 发送启动握手（同步包名、版本，更新看板“等待拦截”活跃状态）
        sendHandshakeAsync(app)

        // 异步拉取最新配置并在拉取成功后同步 BTR Proxy 状态
        fetchConfigAsync(app) { latestConfig ->
            syncBtrProxyState(latestConfig)
        }
    }

    @Volatile
    private var hasStartedBtrProxy = false

    fun ensureBtrProxyStarted(config: AcceleratorConfig): Boolean {
        if (!config.enabled || !config.enableBtr) return false
        hasStartedBtrProxy = true
        val proxy = io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer
        if (!proxy.isStarted) {
            proxy.start(
                concurrency = config.btrConcurrency,
                chunkSizeKb = config.btrChunkSizeKb,
                autoConcurrency = config.btrAutoConcurrency,
                autoChunkSize = config.btrAutoChunkSize
            )
            if (proxy.isStarted) {
                HookStatusTracker.recordStatus("btr_proxy", "127.0.0.1:${proxy.port}")
                syncHookStatusIfDirtyAsync()
            }
        } else {
            proxy.updateParameters(
                concurrency = config.btrConcurrency,
                chunkSizeKb = config.btrChunkSizeKb,
                autoConcurrency = config.btrAutoConcurrency,
                autoChunkSize = config.btrAutoChunkSize
            )
        }
        return proxy.isStarted
    }

    fun syncBtrProxyState(config: AcceleratorConfig) {
        try {
            if (!hasStartedBtrProxy) {
                HookStatusTracker.recordStatus(
                    "btr_proxy",
                    if (config.enabled && config.enableBtr) "ready (on-demand)" else "disabled"
                )
                syncHookStatusIfDirtyAsync()
                return
            }
            val proxy = io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer
            if (config.enabled && config.enableBtr) {
                if (proxy.isStarted) {
                    proxy.updateParameters(
                        concurrency = config.btrConcurrency,
                        chunkSizeKb = config.btrChunkSizeKb,
                        autoConcurrency = config.btrAutoConcurrency,
                        autoChunkSize = config.btrAutoChunkSize
                    )
                    HookStatusTracker.recordStatus("btr_proxy", "127.0.0.1:${proxy.port}")
                } else {
                    proxy.updateParameters(
                        concurrency = config.btrConcurrency,
                        chunkSizeKb = config.btrChunkSizeKb,
                        autoConcurrency = config.btrAutoConcurrency,
                        autoChunkSize = config.btrAutoChunkSize
                    )
                    HookStatusTracker.recordStatus("btr_proxy", "ready (on-demand)")
                }
            } else {
                if (proxy.isStarted) {
                    proxy.stop()
                }
                HookStatusTracker.recordStatus("btr_proxy", "disabled")
            }
            syncHookStatusIfDirtyAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to sync BTR proxy state: ${t.message}")
        }
    }

    private fun registerConfigSyncReceiver(context: Context) {
        if (!isSyncReceiverRegistered.compareAndSet(false, true)) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action != StatsReceiver.ACTION_SYNC_CONFIG) return
                    val authToken: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        runCatching { intent.getParcelableExtra(StatsReceiver.EXTRA_AUTH_TOKEN, PendingIntent::class.java) }.getOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        runCatching { intent.getParcelableExtra<PendingIntent>(StatsReceiver.EXTRA_AUTH_TOKEN) }.getOrNull()
                    }
                    val creatorPkg = authToken?.creatorPackage
                    val sentFromPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        runCatching { sentFromPackage }.getOrNull()
                    } else null
                    val authorized = creatorPkg == BiliAcceleratorCore.MODULE_PACKAGE ||
                            sentFromPkg == BiliAcceleratorCore.MODULE_PACKAGE
                    if (!authorized) return

                    val json = intent.getStringExtra(StatsProvider.EXTRA_CONFIG_JSON) ?: return
                    val newConfig = runCatching { gson.fromJson(json, AcceleratorConfig::class.java) }.getOrNull() ?: return
                    cachedConfig = newConfig
                    lastConfigFetchTime.set(System.currentTimeMillis())
                    saveConfigToLocalCache(context, newConfig)
                    syncBtrProxyState(newConfig)
                    Log.i(TAG, "Synced config via module broadcast push: targetHost=${newConfig.targetHost}")
                }
            }
            val filter = IntentFilter(StatsReceiver.ACTION_SYNC_CONFIG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register config sync receiver: ${t.message}")
        }
    }

    private fun registerConfigObserver(context: Context) {
        if (isObserverRegistered.compareAndSet(false, true)) {
            try {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        Log.i(TAG, "Configuration change detected via ContentObserver, reloading immediately...")
                        lastConfigFetchTime.set(0L)
                        fetchConfigAsync(context) { newConfig ->
                            syncBtrProxyState(newConfig)
                        }
                    }
                }
                context.contentResolver.registerContentObserver(StatsProvider.CONTENT_URI, true, observer)
                Log.i(TAG, "Successfully registered ContentObserver for real-time config sync")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register ContentObserver: ${t.message}")
            }
        }
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

    fun refreshConfigAsyncIfStale() {
        val now = System.currentTimeMillis()
        val lastFetch = lastConfigFetchTime.get()
        if (now - lastFetch < CONFIG_CACHE_TTL && lastFetch != 0L) return
        val context = getContext() ?: return
        fetchConfigAsync(context) { newConfig ->
            syncBtrProxyState(newConfig)
        }
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
                syncBtrProxyState(direct)
                return direct
            }
            // Provider 不可达时设置 5 秒退避，防止后续每次 Hook 调用都在调用线程上同步阻塞
            lastConfigFetchTime.compareAndSet(0L, now - CONFIG_CACHE_TTL + 5_000L)
        }

        fetchConfigAsync(context) { newConfig ->
            syncBtrProxyState(newConfig)
        }
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

    private fun fetchConfigAsync(context: Context, onLoaded: ((AcceleratorConfig) -> Unit)? = null) {
        if (isFetching.compareAndSet(false, true)) {
            configExecutor.execute {
                try {
                    val config = fetchConfigDirect(context)
                    if (config != null) {
                        cachedConfig = config
                        lastConfigFetchTime.set(System.currentTimeMillis())
                        saveConfigToLocalCache(context, config)
                        onLoaded?.invoke(config)
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

    fun syncHookStatusIfDirtyAsync() {
        val ctx = appContext ?: return
        if (!contextInitialized.get()) return
        val current = HookStatusTracker.getSnapshot()
        if (current.isEmpty() || current == lastSyncedHookStatus) return
        configExecutor.execute {
            val latest = HookStatusTracker.getSnapshot()
            if (latest.isNotEmpty() && latest != lastSyncedHookStatus) {
                sendHandshakeDirect(ctx)
            }
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
        val hookSnapshot = HookStatusTracker.getSnapshot()
        val hookSnapshotJson = gson.toJson(hookSnapshot)
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
                lastSyncedHookStatus = hookSnapshot
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
                lastSyncedHookStatus = hookSnapshot
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
        val currentHooks = HookStatusTracker.getSnapshot()
        val dirtyHooksJson = if (currentHooks.isNotEmpty() && currentHooks != lastSyncedHookStatus) {
            gson.toJson(currentHooks)
        } else null
        var reported = false

        // 1. 优先尝试 ContentProvider 批量 IPC
        try {
            val extras = Bundle().apply {
                putString(StatsProvider.EXTRA_REWRITE_BATCH_JSON, json)
                if (dirtyHooksJson != null) {
                    putString(StatsProvider.EXTRA_HOOK_STATUS_JSON, dirtyHooksJson)
                }
            }
            val response = context.contentResolver.call(
                StatsProvider.CONTENT_URI,
                StatsProvider.METHOD_RECORD_BATCH_REWRITE,
                null,
                extras
            )
            if (response?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true) {
                reported = true
                if (dirtyHooksJson != null) {
                    lastSyncedHookStatus = currentHooks
                }
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
                        if (dirtyHooksJson != null) {
                            putExtra(StatsReceiver.EXTRA_HOOK_STATUS_JSON, dirtyHooksJson)
                        }
                    }
                    context.sendBroadcast(intent)
                    if (dirtyHooksJson != null) {
                        lastSyncedHookStatus = currentHooks
                    }
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
