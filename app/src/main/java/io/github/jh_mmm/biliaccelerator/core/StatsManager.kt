package io.github.jh_mmm.biliaccelerator.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class RewriteLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val timeFormatted: String,
    val originalHost: String,
    val targetHost: String,
    val reason: String,
    val isPcdn: Boolean,
    val isMcdn: Boolean,
    val isBtr: Boolean = false,
    val btrChunks: Int = 0,
    val btrBytes: Long = 0L,
    val btrConcurrency: Int = 0,
    val btrCdnCount: Int = 0,
    val btrAvgLatencyMs: Long = 0L
)

data class StatsSnapshot(
    val totalRequests: Long = 0,
    val totalRewrites: Long = 0,
    val pcdnBlocked: Long = 0,
    val mcdnProxied: Long = 0,
    val btrTotalChunks: Long = 0L,
    val btrTotalBytes: Long = 0L,
    val btrFailoverCount: Long = 0L,
    val avoidedHosts: List<String> = emptyList(),
    val recentLogs: List<RewriteLogEntry> = emptyList(),
    val installedAt: String = "",
    val lastHeartbeatTimestamp: Long = 0L,
    val hookStatus: Map<String, String> = emptyMap(),
    val isFrameworkActivated: Boolean = false,
    val clientPackage: String? = null,
    val clientVersion: String? = null,
    val clientProcess: String? = null,
    val frameworkScope: List<String> = emptyList(),
    val hasTargetScope: Boolean = false,
    val hasQueriedScope: Boolean = false
)

object StatsManager {

    private const val PREFS_NAME = "bili_accelerator_stats"
    private const val KEY_TOTAL_REQUESTS = "stat_total_requests"
    private const val KEY_TOTAL_REWRITES = "stat_total_rewrites"
    private const val KEY_PCDN_BLOCKED = "stat_pcdn_blocked"
    private const val KEY_MCDN_PROXIED = "stat_mcdn_proxied"
    private const val KEY_BTR_TOTAL_CHUNKS = "stat_btr_total_chunks"
    private const val KEY_BTR_TOTAL_BYTES = "stat_btr_total_bytes"
    private const val KEY_BTR_FAILOVER_COUNT = "stat_btr_failover_count"
    private const val KEY_AVOIDED_HOSTS = "stat_avoided_hosts"
    private const val KEY_RECENT_LOGS = "stat_recent_logs"
    private const val KEY_INSTALLED_AT = "stat_installed_at"
    private const val KEY_LAST_HEARTBEAT = "stat_last_heartbeat"
    private const val KEY_HOOK_STATUS = "stat_hook_status"
    private const val KEY_FRAMEWORK_ACTIVATED = "stat_framework_activated"
    private const val KEY_CLIENT_PACKAGE = "stat_client_package"
    private const val KEY_CLIENT_VERSION = "stat_client_version"
    private const val KEY_CLIENT_PROCESS = "stat_client_process"

    private const val TAG = "BiliAccelerator-Stats"
    private const val MAX_LOGS = 100
    private const val MAX_AVOIDED_HOSTS = 500
    private const val MAX_HOOK_ENTRIES = 100
    private const val MAX_BATCH_SIZE = 200
    private const val CROSS_PROCESS_DEDUP_WINDOW_MS = 10_000L
    private const val CROSS_PROCESS_DEDUP_MAX = 256

    private val totalRequests = AtomicLong(0)
    private val totalRewrites = AtomicLong(0)
    private val pcdnBlocked = AtomicLong(0)
    private val mcdnProxied = AtomicLong(0)
    private val btrTotalChunks = AtomicLong(0L)
    private val btrTotalBytes = AtomicLong(0L)
    private val btrFailoverCount = AtomicLong(0L)
    private val lastHeartbeat = AtomicLong(0L)
    private val frameworkInjected = AtomicBoolean(false)
    private val frameworkScope = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val hasQueriedScopeFlag = AtomicBoolean(false)
    private val avoidedHosts = ConcurrentHashMap.newKeySet<String>()
    private val recentLogs = ArrayList<RewriteLogEntry>()
    private val logsLock = Any()
    private val hookStatus = ConcurrentHashMap<String, String>()
    private val recentCrossProcessSegments = object : LinkedHashMap<String, Long>(CROSS_PROCESS_DEDUP_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > CROSS_PROCESS_DEDUP_MAX
        }
    }
    private var installedAt = ""
    @Volatile
    private var connectedClientPackage: String? = null
    @Volatile
    private var connectedClientVersion: String? = null
    @Volatile
    private var connectedClientProcess: String? = null

    private val logTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.getDefault())
    private val installTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val gson = Gson()

    private val isInitialized = AtomicBoolean(false)

    // 防抖异步持久化执行器，使用守护线程避免阻止进程退出
    private val saveScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "StatsManager-SaveScheduler").apply { isDaemon = true }
    }
    private var pendingSaveFuture: ScheduledFuture<*>? = null
    private val saveLock = Any()
    private val statsLock = Any()

    fun init(context: Context) {
        if (isInitialized.get()) {
            return
        }

        synchronized(statsLock) {
            if (isInitialized.get()) {
                return
            }
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            totalRequests.set(sp.getLong(KEY_TOTAL_REQUESTS, 0))
            totalRewrites.set(sp.getLong(KEY_TOTAL_REWRITES, 0))
            pcdnBlocked.set(sp.getLong(KEY_PCDN_BLOCKED, 0))
            mcdnProxied.set(sp.getLong(KEY_MCDN_PROXIED, 0))
            btrTotalChunks.set(sp.getLong(KEY_BTR_TOTAL_CHUNKS, 0L))
            btrTotalBytes.set(sp.getLong(KEY_BTR_TOTAL_BYTES, 0L))
            btrFailoverCount.set(sp.getLong(KEY_BTR_FAILOVER_COUNT, 0L))
            lastHeartbeat.set(sp.getLong(KEY_LAST_HEARTBEAT, 0L))
            // 清理旧版本遗留的历史激活持久化，杜绝在 LSPosed 中禁用模块后出现假阳性未激活回落失败
            if (sp.contains(KEY_FRAMEWORK_ACTIVATED)) {
                sp.edit().remove(KEY_FRAMEWORK_ACTIVATED).apply()
            }
            connectedClientPackage = sp.getString(KEY_CLIENT_PACKAGE, null)
            connectedClientVersion = sp.getString(KEY_CLIENT_VERSION, null)
            connectedClientProcess = sp.getString(KEY_CLIENT_PROCESS, null)

            installedAt = sp.getString(KEY_INSTALLED_AT, null) ?: run {
                val now = LocalDateTime.now().format(installTimeFormatter)
                sp.edit().putString(KEY_INSTALLED_AT, now).apply()
                now
            }

            val hostsJson = sp.getString(KEY_AVOIDED_HOSTS, null)
            if (!hostsJson.isNullOrEmpty()) {
                try {
                    val list: List<String> = gson.fromJson(hostsJson, object : TypeToken<List<String>>() {}.type)
                    avoidedHosts.clear()
                    avoidedHosts.addAll(list.take(MAX_AVOIDED_HOSTS))
                } catch (_: Exception) {}
            }

            val logsJson = sp.getString(KEY_RECENT_LOGS, null)
            if (!logsJson.isNullOrEmpty()) {
                try {
                    val list: List<RewriteLogEntry> = gson.fromJson(logsJson, object : TypeToken<List<RewriteLogEntry>>() {}.type)
                    synchronized(logsLock) {
                        recentLogs.clear()
                        recentLogs.addAll(sanitizeLogEntries(list).take(MAX_LOGS))
                    }
                } catch (_: Exception) {}
            }

            val hookJson = sp.getString(KEY_HOOK_STATUS, null)
            if (!hookJson.isNullOrEmpty()) {
                try {
                    val map: Map<String, String> = gson.fromJson(hookJson, object : TypeToken<Map<String, String>>() {}.type)
                    hookStatus.clear()
                    hookStatus.putAll(map)
                    io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker.updateFromMap(map)
                } catch (_: Exception) {}
            }

            isInitialized.set(true)
        }
    }

    internal fun sanitizeLogEntries(list: List<RewriteLogEntry>): List<RewriteLogEntry> {
        return list.mapIndexed { index, item ->
            if (item.id.isNullOrBlank()) {
                item.copy(id = "${item.timestamp}_${item.originalHost}_${item.reason}_$index")
            } else {
                item
            }
        }
    }

    fun ensureInitialized(context: Context) {
        if (!isInitialized.get()) {
            init(context.applicationContext ?: context)
        }
    }

    @JvmStatic
    fun markFrameworkSelfInjected() {
        frameworkInjected.set(true)
    }

    val isFrameworkSelfInjected: Boolean
        get() = frameworkInjected.get()


    fun recordFrameworkScope(scope: List<String>) {
        frameworkScope.clear()
        frameworkScope.addAll(scope)
        hasQueriedScopeFlag.set(true)
    }

    fun clearFrameworkScope() {
        frameworkScope.clear()
        hasQueriedScopeFlag.set(false)
    }

    val frameworkScopeList: List<String>
        get() = frameworkScope.toList()

    val isScopeQueried: Boolean
        get() = hasQueriedScopeFlag.get()

    fun recordHeartbeat(context: Context? = null) {
        if (context != null) {
            ensureInitialized(context)
        }
        lastHeartbeat.set(System.currentTimeMillis())
        context?.let { saveHeartbeatToPrefs(it) }
    }

    internal fun mergeHookStatusEntry(existing: String?, incoming: String): String {
        val safeVal = incoming.trim().take(256)
        if (existing != null &&
            !existing.startsWith("未找到") &&
            !existing.startsWith("未挂载") &&
            (safeVal.startsWith("未找到") || safeVal.startsWith("未挂载"))
        ) {
            return existing
        }
        return safeVal
    }

    internal fun shouldAcceptCrossProcessSegment(
        result: RewriteResult,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val segKey = result.segmentKey.trim()
        if (segKey.isEmpty()) return true
        val compositeKey = "$segKey|${result.originalHost}|${result.targetHost}|${result.reason}|${result.changed}"
        synchronized(recentCrossProcessSegments) {
            val prev = recentCrossProcessSegments[compositeKey]
            if (prev != null && (now - prev) in 0..CROSS_PROCESS_DEDUP_WINDOW_MS) {
                return false
            }
            recentCrossProcessSegments[compositeKey] = now
            return true
        }
    }

    fun recordHookStatus(status: Map<String, String>, context: Context? = null) {
        if (status.isEmpty()) return
        if (context != null) {
            ensureInitialized(context)
        }
        for ((k, v) in status.entries.take(MAX_HOOK_ENTRIES)) {
            val safeKey = k.trim().take(64)
            if (safeKey.isEmpty()) continue
            if (hookStatus.size >= MAX_HOOK_ENTRIES && !hookStatus.containsKey(safeKey)) break
            hookStatus[safeKey] = mergeHookStatusEntry(hookStatus[safeKey], v)
        }
        io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker.updateFromMap(hookStatus)
        context?.let { scheduleDebouncedSave(it) }
    }

    fun getLastHeartbeat(): Long = lastHeartbeat.get()

    private fun saveHeartbeatToPrefs(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putLong(KEY_LAST_HEARTBEAT, lastHeartbeat.get())
                .putString(KEY_CLIENT_PACKAGE, connectedClientPackage)
                .putString(KEY_CLIENT_VERSION, connectedClientVersion)
                .putString(KEY_CLIENT_PROCESS, connectedClientProcess)
                .apply()
        } catch (_: Exception) {}
    }

    fun recordHandshake(pkg: String?, version: String?, process: String?, context: Context? = null) {
        if (context != null) {
            ensureInitialized(context)
        }
        val now = System.currentTimeMillis()
        lastHeartbeat.set(now)
        if (!pkg.isNullOrBlank()) {
            connectedClientPackage = pkg.trim().take(128)
        }
        if (!version.isNullOrBlank()) {
            connectedClientVersion = version.trim().take(64)
        }
        if (!process.isNullOrBlank()) {
            connectedClientProcess = process.trim().take(128)
        }
        context?.let { saveHeartbeatToPrefs(it) }
    }

    fun recordRequests(results: List<RewriteResult>, context: Context? = null) {
        if (results.isEmpty()) return
        if (context != null) {
            ensureInitialized(context)
        }
        for (result in results.take(MAX_BATCH_SIZE)) {
            try {
                recordSingleInternal(result)
            } catch (t: Throwable) {
                Log.w(TAG, "跳过异常统计条目: ${t.message}")
            }
        }
        context?.let { scheduleDebouncedSave(it) }
    }

    private fun recordSingleInternal(result: RewriteResult) {
        val now = System.currentTimeMillis()
        lastHeartbeat.set(now)
        if (!shouldAcceptCrossProcessSegment(result, now)) {
            return
        }
        totalRequests.incrementAndGet()

        if (result.changed) {
            totalRewrites.incrementAndGet()
            val rawOriginalHost = (result.originalHost ?: "").trim().take(128)
            val rawTargetHost = (result.targetHost ?: "").trim().take(128)
            val rawReason = (result.reason ?: "").trim().take(128)

            if (result.isPcdn) {
                pcdnBlocked.incrementAndGet()
                if (rawOriginalHost.isNotBlank()) {
                    if (avoidedHosts.size < MAX_AVOIDED_HOSTS || avoidedHosts.contains(rawOriginalHost)) {
                        avoidedHosts.add(rawOriginalHost)
                    }
                }
            }
            if (result.isMcdn) {
                mcdnProxied.incrementAndGet()
            }
            if (result.isBtr) {
                btrTotalChunks.addAndGet(result.btrChunks.toLong())
                btrTotalBytes.addAndGet(result.btrBytes)
                if (result.reason.contains("熔断") || result.reason.contains("failover")) {
                    btrFailoverCount.incrementAndGet()
                }
            }

            val formattedTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                .format(logTimeFormatter)

            val entry = RewriteLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                timeFormatted = formattedTime,
                originalHost = rawOriginalHost,
                targetHost = rawTargetHost,
                reason = rawReason,
                isPcdn = result.isPcdn,
                isMcdn = result.isMcdn,
                isBtr = result.isBtr,
                btrChunks = result.btrChunks,
                btrBytes = result.btrBytes,
                btrConcurrency = result.btrConcurrency,
                btrCdnCount = result.btrCdnCount,
                btrAvgLatencyMs = result.btrAvgLatencyMs
            )

            synchronized(logsLock) {
                recentLogs.add(0, entry)
                while (recentLogs.size > MAX_LOGS) {
                    recentLogs.removeAt(recentLogs.lastIndex)
                }
            }
        }
    }

    private fun scheduleDebouncedSave(context: Context) {
        val appContext = context.applicationContext ?: context
        synchronized(saveLock) {
            pendingSaveFuture?.cancel(false)
            pendingSaveFuture = saveScheduler.schedule({
                saveToPrefs(appContext)
            }, 1500L, TimeUnit.MILLISECONDS)
        }
    }

    fun saveToPrefs(context: Context) {
        synchronized(statsLock) {
            try {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val logsCopy = synchronized(logsLock) { recentLogs.toList() }
                sp.edit()
                    .putLong(KEY_TOTAL_REQUESTS, totalRequests.get())
                    .putLong(KEY_TOTAL_REWRITES, totalRewrites.get())
                    .putLong(KEY_PCDN_BLOCKED, pcdnBlocked.get())
                    .putLong(KEY_MCDN_PROXIED, mcdnProxied.get())
                    .putLong(KEY_BTR_TOTAL_CHUNKS, btrTotalChunks.get())
                    .putLong(KEY_BTR_TOTAL_BYTES, btrTotalBytes.get())
                    .putLong(KEY_BTR_FAILOVER_COUNT, btrFailoverCount.get())
                    .putLong(KEY_LAST_HEARTBEAT, lastHeartbeat.get())
                    .remove(KEY_FRAMEWORK_ACTIVATED)
                    .putString(KEY_AVOIDED_HOSTS, gson.toJson(avoidedHosts.toList()))
                    .putString(KEY_RECENT_LOGS, gson.toJson(logsCopy))
                    .putString(KEY_HOOK_STATUS, gson.toJson(hookStatus))
                    .putString(KEY_CLIENT_PACKAGE, connectedClientPackage)
                    .putString(KEY_CLIENT_VERSION, connectedClientVersion)
                    .putString(KEY_CLIENT_PROCESS, connectedClientProcess)
                    .apply()
            } catch (_: Exception) {}
        }
    }

    fun clearStats(context: Context) {
        ensureInitialized(context)
        synchronized(saveLock) {
            pendingSaveFuture?.cancel(false)
            pendingSaveFuture = null
        }
        synchronized(statsLock) {
            totalRequests.set(0)
            totalRewrites.set(0)
            pcdnBlocked.set(0)
            mcdnProxied.set(0)
            btrTotalChunks.set(0L)
            btrTotalBytes.set(0L)
            btrFailoverCount.set(0L)
            // 注意：不重置 lastHeartbeat 与 hookStatus，避免指标清零连带将模块健康与激活状态误打回“未激活”
            avoidedHosts.clear()
            synchronized(logsLock) {
                recentLogs.clear()
            }
            synchronized(recentCrossProcessSegments) {
                recentCrossProcessSegments.clear()
            }
            try {
                val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                sp.edit()
                    .putLong(KEY_TOTAL_REQUESTS, 0L)
                    .putLong(KEY_TOTAL_REWRITES, 0L)
                    .putLong(KEY_PCDN_BLOCKED, 0L)
                    .putLong(KEY_MCDN_PROXIED, 0L)
                    .putLong(KEY_BTR_TOTAL_CHUNKS, 0L)
                    .putLong(KEY_BTR_TOTAL_BYTES, 0L)
                    .putLong(KEY_BTR_FAILOVER_COUNT, 0L)
                    .putString(KEY_AVOIDED_HOSTS, gson.toJson(emptyList<String>()))
                    .putString(KEY_RECENT_LOGS, gson.toJson(emptyList<RewriteLogEntry>()))
                    .apply()
            } catch (_: Exception) {}
        }
    }

    fun getSnapshot(): StatsSnapshot {
        val scopeList = frameworkScope.toList()
        val hasTarget = scopeList.any { BiliAcceleratorCore.isTargetPackage(it) }
        return StatsSnapshot(
            totalRequests = totalRequests.get(),
            totalRewrites = totalRewrites.get(),
            pcdnBlocked = pcdnBlocked.get(),
            mcdnProxied = mcdnProxied.get(),
            btrTotalChunks = btrTotalChunks.get(),
            btrTotalBytes = btrTotalBytes.get(),
            btrFailoverCount = btrFailoverCount.get(),
            avoidedHosts = avoidedHosts.toList().sorted(),
            recentLogs = synchronized(logsLock) { recentLogs.toList() },
            installedAt = installedAt,
            lastHeartbeatTimestamp = lastHeartbeat.get(),
            hookStatus = HashMap(hookStatus),
            isFrameworkActivated = frameworkInjected.get(),
            clientPackage = connectedClientPackage,
            clientVersion = connectedClientVersion,
            clientProcess = connectedClientProcess,
            frameworkScope = scopeList,
            hasTargetScope = hasTarget,
            hasQueriedScope = hasQueriedScopeFlag.get()
        )
    }

    fun buildDiagnosticReport(config: AcceleratorConfig, appVersion: String = "Unknown"): String {
        val snapshot = getSnapshot()
        val sb = StringBuilder()
        sb.appendLine("=========================================")
        sb.appendLine("  Bilibili Accelerator Android 诊断报告")
        sb.appendLine("=========================================")
        sb.appendLine("平台环境: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("设备型号: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("客户端版本: $appVersion")
        sb.appendLine("模块安装时间: ${snapshot.installedAt}")
        val lastHb = snapshot.lastHeartbeatTimestamp
        val lastHbDesc = if (lastHb > 0L) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(lastHb), ZoneId.systemDefault()).format(installTimeFormatter)
        } else {
            "无心跳记录"
        }
        sb.appendLine("最近活跃心跳: $lastHbDesc")
        if (snapshot.hasQueriedScope) {
            sb.appendLine("LSPosed 模块作用域: ${if (snapshot.frameworkScope.isEmpty()) "未勾选任何应用" else snapshot.frameworkScope.joinToString(", ")}")
        }
        if (!snapshot.clientPackage.isNullOrEmpty()) {
            sb.appendLine("已连接宿主应用: ${snapshot.clientPackage} (v${snapshot.clientVersion ?: "unknown"})")
            sb.appendLine("宿主活动进程: ${snapshot.clientProcess ?: "unknown"}")
        }
        sb.appendLine("-----------------------------------------")
        sb.appendLine("【当前配置】")
        sb.appendLine("加速开关: ${if (config.enabled) "已启用" else "已关闭"}")
        sb.appendLine("首选 UPOS 镜像: ${config.targetHost}")
        sb.appendLine("MCDN 官方代理: ${if (config.proxyMcdn) "开启 (中继: ${config.proxyHost})" else "关闭"}")
        sb.appendLine("PCDN 拦截: ${if (config.blockPcdn) "已开启" else "已关闭"}")
        sb.appendLine("强制统一线路: ${if (config.forceUpos) "开启" else "关闭"}")
        sb.appendLine("端口启发式: ${if (config.portHeuristic) "开启" else "关闭"}")
        sb.appendLine("Moss gRPC 注入: ${if (config.enableMossHook) "开启 (实验性)" else "关闭 (默认)"}")
        sb.appendLine("-----------------------------------------")
        sb.appendLine("【Hook 挂载状态】")
        try {
            val hookReport = io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker.formatReport(
                snapshot.hookStatus.ifEmpty { io.github.jh_mmm.biliaccelerator.hook.HookStatusTracker.getSnapshot() }
            )
            sb.appendLine(hookReport)
        } catch (_: Throwable) {
            sb.appendLine("状态不可用")
        }
        sb.appendLine("-----------------------------------------")
        sb.appendLine("【拦截统计】")
        sb.appendLine("累计媒体请求总数: ${snapshot.totalRequests}")
        sb.appendLine("累计优化/重定向数: ${snapshot.totalRewrites}")
        sb.appendLine("拦截 PCDN 节点数: ${snapshot.pcdnBlocked}")
        sb.appendLine("MCDN 代理中继数: ${snapshot.mcdnProxied}")
        sb.appendLine("BTR 并发拉取分片数: ${snapshot.btrTotalChunks}")
        sb.appendLine("BTR 累计并发流量: ${snapshot.btrTotalBytes / (1024 * 1024)} MB")
        sb.appendLine("BTR 节点熔断转移数: ${snapshot.btrFailoverCount}")
        sb.appendLine("已规避的慢速节点数量: ${snapshot.avoidedHosts.size}")
        if (snapshot.avoidedHosts.isNotEmpty()) {
            sb.appendLine("规避域名列表: ")
            snapshot.avoidedHosts.take(20).forEach { host ->
                sb.appendLine("  - $host")
            }
            if (snapshot.avoidedHosts.size > 20) {
                sb.appendLine("  ...以及其他 ${snapshot.avoidedHosts.size - 20} 个域名")
            }
        }
        sb.appendLine("-----------------------------------------")
        sb.appendLine("【最近重定向日志 (前 15 条)】")
        if (snapshot.recentLogs.isEmpty()) {
            sb.appendLine("暂无重定向记录")
        } else {
            snapshot.recentLogs.take(15).forEach { log ->
                sb.appendLine("[${log.timeFormatted}] 原因: ${log.reason} | ${log.originalHost} -> ${log.targetHost}")
            }
        }
        sb.appendLine("=========================================")
        return sb.toString()
    }
}
