package io.github.jh_mmm.biliaccelerator.hook

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "BiliAccelerator"
        private val hookedPackages = ConcurrentHashMap.newKeySet<String>()

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

        private val hookInstallerExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "BiliAccel-HookInstaller").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }

        /**
         * 过滤非媒体后台子进程（如 :push、:crash、:channel 等），仅在主进程及播放器/媒体相关子进程挂载 Hook，
         * 避免多进程无意义重复 Hook、重复拉取配置与统计口径污染。
         */
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

        internal fun resolveCurrentProcessName(): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    val name = Application.getProcessName()
                    if (!name.isNullOrBlank()) return name
                }
            }
            return runCatching {
                File("/proc/self/cmdline")
                    .readBytes()
                    .takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .toString(Charsets.UTF_8)
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        @Volatile
        var isSelfHookInjected: Boolean = false
            internal set
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

    @android.annotation.SuppressLint("NewApi")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName

        // 1. 模块自身包名：框架已将本模块纳入作用域并成功注入，标记激活状态
        if (packageName == BiliAcceleratorCore.MODULE_PACKAGE) {
            handleModuleSelfLoaded(param)
            return
        }
        // 2. Target Bilibili Apps
        if (BiliAcceleratorCore.isTargetPackage(packageName)) {
            val processName = resolveCurrentProcessName()
            if (!isRelevantProcess(packageName, processName)) {
                Log.d(TAG, "Skipping non-media subprocess: $processName")
                return
            }

            val processToken = "$packageName@${processName ?: "main"}"
            if (!hookedPackages.add(processToken)) {
                return
            }

            val classLoader = try {
                param.defaultClassLoader
            } catch (_: Throwable) {
                null
            } ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

            Log.i(TAG, "Injecting into $packageName (process=${processName ?: "main"}) via LibXposed API 101")

            // 尽早异步预热安装业务 Hook，杜绝生命周期挂载死锁
            installFeatureHooksAsync(classLoader)

            // 捕获 Application 实例并在 Context 到位后启动握手与配置同步
            captureApplicationContextAndInstallHooks(packageName, classLoader)
        }
    }

    private fun installFeatureHooksAsync(classLoader: ClassLoader) {
        if (PlayerHook.isHooked && MossGrpcHook.isHooked) return
        hookInstallerExecutor.execute {
            try {
                // 尝试尽早获取现有 Application 上下文以提前建立启动握手
                RemoteClient.getContext()
                if (!PlayerHook.isHooked) {
                    PlayerHook.init(this@HookEntry, classLoader)
                }
                if (!MossGrpcHook.isHooked) {
                    MossGrpcHook.init(this@HookEntry, classLoader)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Async hook installation error: ${t.message}")
            }
        }
    }

    private fun captureApplicationContextAndInstallHooks(packageName: String, classLoader: ClassLoader) {
        val appClass = Application::class.java

        // 尽早尝试捕获当前已有的 Application 上下文
        RemoteClient.getContext()

        // 1. Hook Application.attach(Context)
        try {
            val attachMethod = appClass.getDeclaredMethod("attach", Context::class.java)
            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                val ctx = (chain.thisObject as? Context) ?: (chain.args.firstOrNull() as? Context)
                val procName = resolveCurrentProcessName()
                if (ctx != null && isRelevantProcess(packageName, procName)) {
                    RemoteClient.setAppContext(ctx)
                    HookStatusTracker.recordStatus("app_context", "Application.attach (${procName ?: packageName})")
                    installFeatureHooksAsync(ctx.classLoader ?: classLoader)
                }
                result
            }
            Log.i(TAG, "Successfully hooked Application.attach")
        } catch (t: Throwable) {
            Log.d(TAG, "Hook Application.attach fallback: ${t.message}")
        }

        // 2. Hook ContextWrapper.attachBaseContext(Context)
        try {
            val attachBaseMethod = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            hook(attachBaseMethod).intercept { chain ->
                val result = chain.proceed()
                val ctx = (chain.thisObject as? Context) ?: (chain.args.firstOrNull() as? Context)
                val procName = resolveCurrentProcessName()
                if (ctx != null && isRelevantProcess(packageName, procName)) {
                    RemoteClient.setAppContext(ctx)
                    HookStatusTracker.recordStatus("app_context", "ContextWrapper.attachBaseContext (${procName ?: packageName})")
                    installFeatureHooksAsync(ctx.classLoader ?: classLoader)
                }
                result
            }
            Log.i(TAG, "Successfully hooked ContextWrapper.attachBaseContext")
        } catch (t: Throwable) {
            Log.d(TAG, "Hook ContextWrapper.attachBaseContext fallback: ${t.message}")
        }

        // 3. Hook Application.onCreate
        try {
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Context)?.let { ctx ->
                    val procName = resolveCurrentProcessName()
                    if (isRelevantProcess(packageName, procName)) {
                        RemoteClient.setAppContext(ctx)
                        HookStatusTracker.recordStatus("app_context", "Application.onCreate (${procName ?: packageName})")
                        installFeatureHooksAsync(ctx.classLoader ?: classLoader)
                    }
                }
                result
            }
            Log.i(TAG, "Successfully hooked Application.onCreate")
        } catch (t: Throwable) {
            Log.d(TAG, "Hook Application.onCreate fallback: ${t.message}")
        }
    }
}

