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
import java.util.concurrent.atomic.AtomicBoolean

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "BiliAccelerator"
        private val hookedPackages = ConcurrentHashMap.newKeySet<String>()
        private val featureHooksInstalled = AtomicBoolean(false)

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
            if (proc == packageName) return true
            if (!proc.startsWith("$packageName:")) return true
            val suffix = proc.substring(packageName.length).lowercase()
            if (IGNORED_PROCESS_SUFFIXES.any { suffix == it || suffix.startsWith("$it:") }) {
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
    }

    @android.annotation.SuppressLint("NewApi")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName

        // 1. 忽略模块自身包名与非主包加载
        if (packageName == BiliAcceleratorCore.MODULE_PACKAGE) {
            return
        }
        val isFirst = try {
            param.isFirstPackage
        } catch (_: Throwable) {
            true
        }
        if (!isFirst) {
            return
        }

        // 2. Target Bilibili Apps
        if (BiliAcceleratorCore.TARGET_PACKAGES.contains(packageName)) {
            val processName = resolveCurrentProcessName()
            if (!isRelevantProcess(packageName, processName)) {
                Log.d(TAG, "Skipping non-media subprocess: $processName")
                return
            }

            if (!hookedPackages.add(packageName)) {
                return
            }

            val classLoader = try {
                param.defaultClassLoader
            } catch (_: Throwable) {
                null
            } ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

            Log.i(TAG, "Injecting into $packageName (process=${processName ?: "main"}) via LibXposed API 101")

            // 捕获 Application 实例并在后台线程异步完成业务 Hook 挂载，彻底消除宿主主线程同步 Class.forName + hook 开销
            captureApplicationContextAndInstallHooks(packageName, classLoader)
        }
    }

    private fun installFeatureHooksAsync(classLoader: ClassLoader) {
        if (featureHooksInstalled.compareAndSet(false, true)) {
            hookInstallerExecutor.execute {
                try {
                    PlayerHook.init(this@HookEntry, classLoader)
                    MossGrpcHook.init(this@HookEntry, classLoader)
                } catch (t: Throwable) {
                    Log.w(TAG, "Async hook installation error: ${t.message}")
                }
            }
        }
    }

    private fun captureApplicationContextAndInstallHooks(packageName: String, classLoader: ClassLoader) {
        val appClass = Application::class.java

        // Application.attach(Context) 由框架在 handleBindApplication 阶段调用，早于任何 Activity/Service/播放器初始化
        try {
            val attachMethod = appClass.getDeclaredMethod("attach", Context::class.java)
            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                val ctx = (chain.thisObject as? Context) ?: (chain.args.firstOrNull() as? Context)
                val procName = resolveCurrentProcessName()
                if (!isRelevantProcess(packageName, procName)) {
                    return@intercept result
                }
                if (ctx != null) {
                    RemoteClient.setAppContext(ctx)
                    HookStatusTracker.recordStatus("app_context", "Application.attach (${procName ?: packageName})")
                }
                val effectiveClassLoader = ctx?.classLoader ?: classLoader
                installFeatureHooksAsync(effectiveClassLoader)
                result
            }
            Log.i(TAG, "Successfully hooked Application.attach")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook Application.attach (ROM compatibility): ${t.message}")
        }

        // 兜底：仅当 attach Hook 失败时才挂载 Application.onCreate 并异步安装业务 Hook
        try {
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Context)?.let { ctx ->
                    if (isRelevantProcess(packageName, resolveCurrentProcessName())) {
                        RemoteClient.setAppContext(ctx)
                        HookStatusTracker.recordStatus("app_context", "Application.onCreate")
                        installFeatureHooksAsync(ctx.classLoader ?: classLoader)
                    }
                }
                result
            }
            Log.i(TAG, "Successfully hooked Application.onCreate")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook Application.onCreate (ROM compatibility): ${t.message}")
        }

        installFeatureHooksAsync(classLoader)
    }
}

