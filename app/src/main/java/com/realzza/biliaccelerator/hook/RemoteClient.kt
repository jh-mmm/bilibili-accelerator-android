package com.realzza.biliaccelerator.hook

import android.app.AndroidAppHelper
import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.realzza.biliaccelerator.core.AcceleratorConfig
import com.realzza.biliaccelerator.core.RewriteResult
import com.realzza.biliaccelerator.provider.StatsProvider
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object RemoteClient {

    private const val TAG = "BiliAccelerator-Remote"

    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RemoteClient-Worker").apply { isDaemon = true }
    }

    @Volatile
    private var cachedConfig: AcceleratorConfig = tryLoadXSharedPrefs() ?: AcceleratorConfig()
    private val lastConfigFetchTime = AtomicLong(0L)
    private val isFetching = AtomicBoolean(false)
    private const val CONFIG_CACHE_TTL = 30_000L // 30 seconds

    private fun tryLoadXSharedPrefs(): AcceleratorConfig? {
        return try {
            val xsp = XSharedPreferences("com.realzza.biliaccelerator", StatsProvider.PREFS_CONFIG)
            xsp.reload()
            if (xsp.file.canRead()) {
                AcceleratorConfig(
                    enabled = xsp.getBoolean(StatsProvider.KEY_ENABLED, true),
                    targetHost = xsp.getString(StatsProvider.KEY_TARGET_HOST, "upos-sz-mirrorcos.bilivideo.com") ?: "upos-sz-mirrorcos.bilivideo.com",
                    blockPcdn = xsp.getBoolean(StatsProvider.KEY_BLOCK_PCDN, true),
                    proxyMcdn = xsp.getBoolean(StatsProvider.KEY_PROXY_MCDN, true),
                    forceUpos = xsp.getBoolean(StatsProvider.KEY_FORCE_UPOS, false),
                    portHeuristic = xsp.getBoolean(StatsProvider.KEY_PORT_HEURISTIC, true)
                )
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    // 由 HookEntry 在目标进程 Application 创建时注入，作为最可靠的 Context 来源
    @Volatile
    private var appContext: Context? = null

    fun setAppContext(context: Context) {
        appContext = try {
            context.applicationContext ?: context
        } catch (_: Throwable) {
            context
        }
    }

    fun getContext(): Context? {
        appContext?.let { return it }
        return try {
            AndroidAppHelper.currentApplication()
        } catch (_: Throwable) {
            null
        }
    }

    fun fetchConfig(): AcceleratorConfig {
        val now = System.currentTimeMillis()
        val lastFetch = lastConfigFetchTime.get()

        if (now - lastFetch < CONFIG_CACHE_TTL) {
            return cachedConfig
        }

        // 异步后台拉取配置，彻底避免媒体播放线程或 gRPC 线程发生同步 Binder IPC 阻塞
        if (isFetching.compareAndSet(false, true)) {
            executor.execute {
                try {
                    val context = getContext()
                    if (context == null) {
                        XposedBridge.log("$TAG: 配置拉取跳过：目标进程 Context 为空")
                        lastConfigFetchTime.set(System.currentTimeMillis() - CONFIG_CACHE_TTL + 5_000L)
                        return@execute
                    }
                    val response = context.contentResolver.call(
                        StatsProvider.CONTENT_URI,
                        StatsProvider.METHOD_GET_CONFIG,
                        null,
                        null
                    )
                    val json = response?.getString(StatsProvider.EXTRA_CONFIG_JSON)
                    if (!json.isNullOrEmpty()) {
                        cachedConfig = gson.fromJson(json, AcceleratorConfig::class.java)
                        lastConfigFetchTime.set(System.currentTimeMillis())
                    } else {
                        lastConfigFetchTime.set(System.currentTimeMillis() - CONFIG_CACHE_TTL + 5_000L)
                    }
                } catch (_: Throwable) {
                    lastConfigFetchTime.set(System.currentTimeMillis() - CONFIG_CACHE_TTL + 5_000L)
                } finally {
                    isFetching.set(false)
                }
            }
        }

        return cachedConfig
    }

    fun notifyRewrite(result: RewriteResult) {
        executor.execute {
            val context = getContext()
            if (context == null) {
                XposedBridge.log("$TAG: 统计上报失败：目标进程 Context 为空（Application 未捕获且 AndroidAppHelper 不可用）")
                return@execute
            }
            try {
                val extras = Bundle().apply {
                    putString(StatsProvider.EXTRA_REWRITE_RESULT, gson.toJson(result))
                }
                val response = context.contentResolver.call(
                    StatsProvider.CONTENT_URI,
                    StatsProvider.METHOD_RECORD_REWRITE,
                    null,
                    extras
                )
                val ok = response?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true
                XposedBridge.log("$TAG: 统计上报 ${result.originalHost} -> ${result.targetHost} [${result.reason}] success=$ok")
            } catch (t: Throwable) {
                if (t is IllegalArgumentException && t.message?.contains("Unknown authority") == true) {
                    XposedBridge.log("$TAG: 统计上报失败: Provider 暂不可达 [Unknown authority]。请确保 BiliAccelerator 模块安装后已在桌面点击打开过至少一次（退出系统 Stopped 状态）")
                } else {
                    XposedBridge.log("$TAG: 统计上报异常: $t")
                }
            }
        }
    }
}
