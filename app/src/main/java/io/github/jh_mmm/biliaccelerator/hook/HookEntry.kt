package io.github.jh_mmm.biliaccelerator.hook

import android.content.Context
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "BiliAccelerator"

        @Volatile
        var isSelfHookInjected: Boolean = false
            internal set

        private val IGNORED_PROCESS_SUFFIXES = setOf(
            ":push",
            ":pushservice",
            ":channel",
            ":crash",
            ":daemon",
            ":widget",
            ":widgetprovider",
            ":miniapp",
            ":nimble",
            ":clean",
            ":sentry",
            ":account",
            ":mall",
            ":patch",
            ":canary",
            ":guard"
        )

        internal fun isRelevantProcess(packageName: String, processName: String?): Boolean {
            val proc = processName?.trim()?.takeIf { it.isNotEmpty() } ?: return true
            if (proc == packageName || !proc.contains(':')) return true
            val suffix = proc.substringAfter(':').lowercase()
            if (suffix.contains("media") || suffix.contains("player") || suffix.contains("video") || suffix.contains("ijk")) {
                return true
            }
            if (IGNORED_PROCESS_SUFFIXES.any { suffix == it.removePrefix(":") || suffix.startsWith(it.removePrefix(":") + ":") }) {
                return false
            }
            return true
        }
    }

    private fun handleModuleSelfLoaded(param: PackageLoadedParam) {
        isSelfHookInjected = true
        Log.i(TAG, "LSPosed injected into module self package (${param.packageName}), module is active")

        val classLoader = try {
            param.defaultClassLoader
        } catch (_: Throwable) {
            null
        } ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

        try {
            val mainActivityClass = classLoader.loadClass("io.github.jh_mmm.biliaccelerator.ui.MainActivity")
            val isModuleActiveMethod = mainActivityClass.getDeclaredMethod("isModuleActive")
            hook(isModuleActiveMethod).intercept {
                true
            }
            Log.i(TAG, "Successfully hooked MainActivity.isModuleActive -> true")
        } catch (t: Throwable) {
            Log.d(TAG, "Self-hook MainActivity.isModuleActive fallback: ${t.message}")
        }

        try {
            val statsManagerClass = classLoader.loadClass("io.github.jh_mmm.biliaccelerator.core.StatsManager")
            val markMethod = statsManagerClass.getDeclaredMethod("markFrameworkSelfInjected")
            markMethod.invoke(null)
        } catch (_: Throwable) {}
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        val classLoader = try {
            param.defaultClassLoader
        } catch (_: Throwable) {
            null
        } ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

        // 1. 模块自身包名：若框架将本模块加入注入作用域，标记激活状态
        if (packageName == BiliAcceleratorCore.MODULE_PACKAGE) {
            handleModuleSelfLoaded(param)
            return
        }

        // 2. 目标应用：B站国内版、国际版、Bstar 等
        if (BiliAcceleratorCore.isTargetPackage(packageName)) {
            val processName = RemoteClient.resolveProcessName()
            if (!isRelevantProcess(packageName, processName)) {
                Log.d(TAG, "Skipping non-player background process: $processName")
                return
            }

            Log.i(TAG, "Injecting into $packageName (process=${processName ?: "main"}) via LibXposed API 101")

            // 尽早捕获 Application 实例，供统计上报（跨进程 ContentProvider 调用）使用
            captureApplicationContext(packageName, classLoader)

            // 放行 Android 9+ 本地回环 127.0.0.1 明文 HTTP 流量限制
            NetworkSecurityHook.init(this, classLoader)

            // 在媒体播放进程中启动轻量级 BTR 本地回环代理服务
            val config = RemoteClient.fetchConfig()
            if (config.enabled && config.enableBtr) {
                io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.start(
                    concurrency = config.btrConcurrency,
                    chunkSizeKb = config.btrChunkSizeKb
                )
                if (io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted) {
                    HookStatusTracker.recordStatus("btr_proxy", "127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
                }
            }

            // 同步初始化业务 Hook（1.0.5 经典逻辑，杜绝异步线程导致播放器早期实例漏 Hook）
            PlayerHook.init(this, classLoader)
            MossGrpcHook.init(this, classLoader)
        }
    }

    private fun captureApplicationContext(packageName: String, classLoader: ClassLoader) {
        var attachHooked = false
        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val attachMethod = appClass.getDeclaredMethod("attach", Context::class.java)
            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                val ctx = (chain.thisObject as? Context) ?: (chain.args.firstOrNull() as? Context)
                if (ctx != null) {
                    RemoteClient.setAppContext(ctx)
                    HookStatusTracker.recordStatus("app_context", "Application.attach ($packageName)")
                }
                result
            }
            attachHooked = true
            Log.i(TAG, "Successfully hooked Application.attach")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook Application.attach (ROM compatibility): ${t.message}")
        }

        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Context)?.let { ctx ->
                    RemoteClient.setAppContext(ctx)
                    if (!attachHooked) {
                        HookStatusTracker.recordStatus("app_context", "Application.onCreate ($packageName)")
                    }
                }
                result
            }
            Log.i(TAG, "Successfully hooked Application.onCreate")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook Application.onCreate (ROM compatibility): ${t.message}")
        }
    }
}
