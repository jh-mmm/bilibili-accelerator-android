package io.github.jh_mmm.biliaccelerator.hook

import android.content.Context
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "BiliAccelerator"
        private val hookedPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }

    @android.annotation.SuppressLint("NewApi")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        val classLoader = try {
            param.defaultClassLoader
        } catch (_: Throwable) {
            null
        } ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

        // 1. 忽略模块自身包名
        // 注意：在 LibXposed Modern API (API 101+) 下，LSPosed 明确不再将现代模块自身加入注入作用域，
        // 激活状态感知改由 XposedService Binder 绑定与运行时回执（Heartbeat/Stats）机制接管。
        if (packageName == BiliAcceleratorCore.MODULE_PACKAGE) {
            return
        }

        // 2. Target Bilibili Apps
        if (BiliAcceleratorCore.TARGET_PACKAGES.contains(packageName)) {
            // 确保同一进程内对特定包名仅注入一次，避免多ClassLoader或边界调用重复安装
            if (!hookedPackages.add(packageName)) {
                return
            }

            Log.i(TAG, "Injecting into $packageName via LibXposed API 101")

            // 尽早捕获 Application 实例，供统计上报（跨进程 ContentProvider 调用）使用
            captureApplicationContext(classLoader)

            // Media Player Hook
            PlayerHook.init(this, classLoader)

            // gRPC Protocol Hook
            MossGrpcHook.init(this, classLoader)
        }
    }

    private fun captureApplicationContext(classLoader: ClassLoader) {
        var attachHooked = false
        // Application.attach(Context) 由框架调用且应用无法覆写，是最可靠的捕获点
        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val attachMethod = appClass.getDeclaredMethod("attach", Context::class.java)
            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Context)?.let { ctx ->
                    RemoteClient.setAppContext(ctx)
                    HookStatusTracker.recordStatus("app_context", "Application.attach")
                }
                result
            }
            attachHooked = true
            Log.i(TAG, "Successfully hooked Application.attach")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook Application.attach (ROM compatibility): ${t.message}")
        }

        // 兜底：部分 ROM 的 attach 签名有差异时改用 onCreate
        try {
            val appClass = classLoader.loadClass("android.app.Application")
            val onCreateMethod = appClass.getDeclaredMethod("onCreate")
            hook(onCreateMethod).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Context)?.let { ctx ->
                    RemoteClient.setAppContext(ctx)
                    if (!attachHooked) {
                        HookStatusTracker.recordStatus("app_context", "Application.onCreate")
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
