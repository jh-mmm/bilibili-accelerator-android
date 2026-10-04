package io.github.jh_mmm.biliaccelerator.hook

import android.app.AndroidAppHelper
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.provider.StatsProvider
import io.github.jh_mmm.biliaccelerator.receiver.StatsReceiver
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
            val xsp = XSharedPreferences("io.github.jh_mmm.biliaccelerator", StatsProvider.PREFS_CONFIG)
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
            val json = gson.toJson(result)
            var reported = false

            // 1. 优先尝试 ContentProvider IPC
            try {
                val extras = Bundle().apply {
                    putString(StatsProvider.EXTRA_REWRITE_RESULT, json)
                }
                val response = context.contentResolver.call(
                    StatsProvider.CONTENT_URI,
                    StatsProvider.METHOD_RECORD_REWRITE,
                    null,
                    extras
                )
                if (response?.getBoolean(StatsProvider.EXTRA_SUCCESS) == true) {
                    reported = true
                    XposedBridge.log("$TAG: [Provider] 统计上报成功: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: [Provider] 暂不可达: ${t.javaClass.simpleName} (${t.message})")
            }

            // 2. 兜底通道：若 Provider 不可达（如高版本 Android 包可见性拦截），发送带唤醒标志的显式广播
            if (!reported) {
                try {
                    val intent = Intent(StatsReceiver.ACTION_RECORD_REWRITE).apply {
                        setPackage("io.github.jh_mmm.biliaccelerator")
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra(StatsReceiver.EXTRA_REWRITE_RESULT, json)
                    }
                    context.sendBroadcast(intent)
                    XposedBridge.log("$TAG: [Broadcast] 备用通道已发送: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: [Broadcast] 发送异常: $t")
                }
            }
        }
    }
}
