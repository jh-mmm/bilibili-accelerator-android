package io.github.jh_mmm.biliaccelerator.hook

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    private val selfHooked = AtomicBoolean(false)
    private val appContextHooked = AtomicBoolean(false)
    private val targetInitialized = AtomicBoolean(false)

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
            ":guard",
            ":sandbox",
            ":sandboxed",
            ":isolated",
            ":webview",
            ":report",
            ":monitor",
            ":log",
            ":download",
            ":updater",
            ":plugin",
            ":tinker",
            ":bugly"
        )

        internal fun isRelevantProcess(packageName: String, processName: String?): Boolean {
            val proc = processName?.trim()?.takeIf { it.isNotEmpty() } ?: return true
            if (proc == packageName || !proc.contains(':')) return true
            val suffix = proc.substringAfter(':').lowercase()
            if (suffix.contains("media") || suffix.contains("play") || suffix.contains("video") || suffix.contains("ijk") || suffix == "live") {
                return true
            }
            if (IGNORED_PROCESS_SUFFIXES.any { ignored ->
                    val prefix = ignored.removePrefix(":")
                    suffix == prefix || suffix.startsWith("$prefix:") || suffix.startsWith("${prefix}_") || suffix.startsWith("${prefix}process")
                } || suffix.contains("sandbox") || suffix.contains("isolated") || suffix.contains("webview")
            ) {
                return false
            }
            return true
        }
    }

    private fun handleModuleSelfLoaded(packageName: String, classLoader: ClassLoader) {
        if (!selfHooked.compareAndSet(false, true)) return
        isSelfHookInjected = true
        Log.i(TAG, "LSPosed injected into module self package ($packageName), module is active")

        try {
            val mainActivityClass = classLoader.loadClass("io.github.jh_mmm.biliaccelerator.ui.MainActivity")
            val isModuleActiveMethod = mainActivityClass.getDeclaredMethod("isModuleActive")
            hook(isModuleActiveMethod).intercept {
                true
            }
            Log.i(TAG, "Successfully hooked MainActivity.isModuleActive -> true")
        } catch (t: Throwable) {
            selfHooked.set(false)
            Log.d(TAG, "Self-hook MainActivity.isModuleActive fallback: ${t.message}")
        }

        try {
            val statsManagerClass = classLoader.loadClass("io.github.jh_mmm.biliaccelerator.core.StatsManager")
            val markMethod = statsManagerClass.getDeclaredMethod("markFrameworkSelfInjected")
            markMethod.invoke(null)
        } catch (_: Throwable) {}
    }

    private fun resolveClassLoader(candidate: ClassLoader?): ClassLoader {
        return candidate ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        val loaderCandidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { param.defaultClassLoader }.getOrNull()
        } else null
        val classLoader = resolveClassLoader(loaderCandidate)

        if (packageName == BiliAcceleratorCore.MODULE_PACKAGE) {
            handleModuleSelfLoaded(packageName, classLoader)
            return
        }

        if (BiliAcceleratorCore.isTargetPackage(packageName)) {
            injectTargetPackage(packageName, classLoader, phase = "onPackageLoaded")
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        val classLoader = resolveClassLoader(runCatching { param.classLoader }.getOrNull())

        if (packageName == BiliAcceleratorCore.MODULE_PACKAGE) {
            handleModuleSelfLoaded(packageName, classLoader)
            return
        }

        if (BiliAcceleratorCore.isTargetPackage(packageName)) {
            injectTargetPackage(packageName, classLoader, phase = "onPackageReady")
        }
    }

    private fun injectTargetPackage(packageName: String, classLoader: ClassLoader, phase: String) {
        val processName = RemoteClient.resolveProcessName()
        if (!isRelevantProcess(packageName, processName)) {
            if (targetInitialized.compareAndSet(false, true)) {
                Log.d(TAG, "Skipping non-player background process: $processName")
            }
            return
        }

        if (targetInitialized.compareAndSet(false, true)) {
            Log.i(TAG, "Injecting into $packageName (process=${processName ?: "main"}, phase=$phase) via LibXposed API 101")
            RemoteClient.attachXposedModule(this)
        }

        // 尽早捕获 Application 实例，供统计上报与双向配置同步使用（内部防重入）
        captureApplicationContext(packageName, classLoader)

        // 放行 Android 9+ 本地回环 127.0.0.1 明文 HTTP 流量限制（幂等防重入）
        NetworkSecurityHook.init(this, classLoader)

        // 在 onPackageLoaded 与 onPackageReady 均尝试挂载播放器与 Moss Hook（内部按 Class 去重），兼顾自定义 AppComponentFactory / 插件化 ClassLoader
        PlayerHook.init(this, classLoader)
        MossGrpcHook.init(this, classLoader)
    }

    private fun captureApplicationContext(packageName: String, classLoader: ClassLoader) {
        if (!appContextHooked.compareAndSet(false, true)) return
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

        if (!attachHooked) {
            try {
                val appClass = classLoader.loadClass("android.app.Application")
                val onCreateMethod = appClass.getDeclaredMethod("onCreate")
                hook(onCreateMethod).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? Context)?.let { ctx ->
                        RemoteClient.setAppContext(ctx)
                        HookStatusTracker.recordStatus("app_context", "Application.onCreate ($packageName)")
                    }
                    result
                }
                Log.i(TAG, "Successfully hooked Application.onCreate")
            } catch (t: Throwable) {
                appContextHooked.set(false)
                Log.w(TAG, "Failed to hook Application.onCreate (ROM compatibility): ${t.message}")
            }
        }
    }
}
