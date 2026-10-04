package com.realzza.biliaccelerator.core

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RewriteLogEntry(
    val timestamp: Long,
    val timeFormatted: String,
    val originalHost: String,
    val targetHost: String,
    val reason: String,
    val isPcdn: Boolean,
    val isMcdn: Boolean
)

data class StatsSnapshot(
    val totalRequests: Long = 0,
    val totalRewrites: Long = 0,
    val pcdnBlocked: Long = 0,
    val mcdnProxied: Long = 0,
    val avoidedHosts: List<String> = emptyList(),
    val recentLogs: List<RewriteLogEntry> = emptyList(),
    val installedAt: String = ""
)

object StatsManager {

    private const val PREFS_NAME = "bili_accelerator_stats"
    private const val KEY_TOTAL_REQUESTS = "stat_total_requests"
    private const val KEY_TOTAL_REWRITES = "stat_total_rewrites"
    private const val KEY_PCDN_BLOCKED = "stat_pcdn_blocked"
    private const val KEY_MCDN_PROXIED = "stat_mcdn_proxied"
    private const val KEY_AVOIDED_HOSTS = "stat_avoided_hosts"
    private const val KEY_RECENT_LOGS = "stat_recent_logs"
    private const val KEY_INSTALLED_AT = "stat_installed_at"

    private const val MAX_LOGS = 100
    private const val MAX_AVOIDED_HOSTS = 500

    private val totalRequests = AtomicLong(0)
    private val totalRewrites = AtomicLong(0)
    private val pcdnBlocked = AtomicLong(0)
    private val mcdnProxied = AtomicLong(0)
    private val avoidedHosts = ConcurrentHashMap.newKeySet<String>()
    private val recentLogs = CopyOnWriteArrayList<RewriteLogEntry>()
    private var installedAt = ""

    private val logTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.getDefault())
    private val installTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val gson = Gson()

    private val isInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    // 防抖异步持久化执行器，使用守护线程避免阻止进程退出
    private val saveScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "StatsManager-SaveScheduler").apply { isDaemon = true }
    }
    private var pendingSaveFuture: ScheduledFuture<*>? = null
    private val saveLock = Any()

    fun init(context: Context) {
        // 保证进程内单次幂等初始化，避免多处调用（Provider与Activity）导致日志翻倍与计数回退
        if (!isInitialized.compareAndSet(false, true)) {
            return
        }

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        totalRequests.set(sp.getLong(KEY_TOTAL_REQUESTS, 0))
        totalRewrites.set(sp.getLong(KEY_TOTAL_REWRITES, 0))
        pcdnBlocked.set(sp.getLong(KEY_PCDN_BLOCKED, 0))
        mcdnProxied.set(sp.getLong(KEY_MCDN_PROXIED, 0))

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
                recentLogs.clear()
                recentLogs.addAll(list.take(MAX_LOGS))
            } catch (_: Exception) {}
        }
    }

    fun recordRequest(result: RewriteResult, context: Context? = null) {
        totalRequests.incrementAndGet()

        if (result.changed) {
            totalRewrites.incrementAndGet()
            if (result.isPcdn) {
                pcdnBlocked.incrementAndGet()
                val host = result.originalHost.trim().take(128)
                if (host.isNotBlank()) {
                    if (avoidedHosts.size < MAX_AVOIDED_HOSTS || avoidedHosts.contains(host)) {
                        avoidedHosts.add(host)
                    }
                }
            }
            if (result.isMcdn) {
                mcdnProxied.incrementAndGet()
            }

            val now = System.currentTimeMillis()
            val formattedTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                .format(logTimeFormatter)

            val entry = RewriteLogEntry(
                timestamp = now,
                timeFormatted = formattedTime,
                originalHost = result.originalHost.trim().take(128),
                targetHost = result.targetHost.trim().take(128),
                reason = result.reason.trim().take(64),
                isPcdn = result.isPcdn,
                isMcdn = result.isMcdn
            )

            recentLogs.add(0, entry)
            while (recentLogs.size > MAX_LOGS) {
                recentLogs.removeAt(recentLogs.lastIndex)
            }
        }

        context?.let { scheduleDebouncedSave(it) }
    }

    private fun scheduleDebouncedSave(context: Context) {
        val appContext = context.applicationContext
        synchronized(saveLock) {
            pendingSaveFuture?.cancel(false)
            pendingSaveFuture = saveScheduler.schedule({
                saveToPrefs(appContext)
            }, 1500L, TimeUnit.MILLISECONDS)
        }
    }

    fun saveToPrefs(context: Context) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putLong(KEY_TOTAL_REQUESTS, totalRequests.get())
                .putLong(KEY_TOTAL_REWRITES, totalRewrites.get())
                .putLong(KEY_PCDN_BLOCKED, pcdnBlocked.get())
                .putLong(KEY_MCDN_PROXIED, mcdnProxied.get())
                .putString(KEY_AVOIDED_HOSTS, gson.toJson(avoidedHosts.toList()))
                .putString(KEY_RECENT_LOGS, gson.toJson(recentLogs.toList()))
                .apply()
        } catch (_: Exception) {}
    }

    fun clearStats(context: Context) {
        synchronized(saveLock) {
            pendingSaveFuture?.cancel(false)
        }
        totalRequests.set(0)
        totalRewrites.set(0)
        pcdnBlocked.set(0)
        mcdnProxied.set(0)
        avoidedHosts.clear()
        recentLogs.clear()
        saveToPrefs(context)
    }

    fun getSnapshot(): StatsSnapshot {
        return StatsSnapshot(
            totalRequests = totalRequests.get(),
            totalRewrites = totalRewrites.get(),
            pcdnBlocked = pcdnBlocked.get(),
            mcdnProxied = mcdnProxied.get(),
            avoidedHosts = avoidedHosts.toList().sorted(),
            recentLogs = recentLogs.toList(),
            installedAt = installedAt
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
        sb.appendLine("-----------------------------------------")
        sb.appendLine("【当前配置】")
        sb.appendLine("加速开关: ${if (config.enabled) "已启用" else "已关闭"}")
        sb.appendLine("首选 UPOS 镜像: ${config.targetHost}")
        sb.appendLine("MCDN 官方代理: ${if (config.proxyMcdn) "开启 (中继: ${config.proxyHost})" else "关闭"}")
        sb.appendLine("PCDN 拦截: ${if (config.blockPcdn) "已开启" else "已关闭"}")
        sb.appendLine("强制统一线路: ${if (config.forceUpos) "开启" else "关闭"}")
        sb.appendLine("端口启发式: ${if (config.portHeuristic) "开启" else "关闭"}")
        sb.appendLine("-----------------------------------------")
        sb.appendLine("【拦截统计】")
        sb.appendLine("累计媒体请求总数: ${snapshot.totalRequests}")
        sb.appendLine("累计优化/重定向数: ${snapshot.totalRewrites}")
        sb.appendLine("拦截 PCDN 节点数: ${snapshot.pcdnBlocked}")
        sb.appendLine("MCDN 代理中继数: ${snapshot.mcdnProxied}")
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
