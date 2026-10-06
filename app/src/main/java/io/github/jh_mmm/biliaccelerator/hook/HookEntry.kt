package io.github.jh_mmm.biliaccelerator.hook

import android.content.Context
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {

    companion object {
        private const val TAG = "BiliAccelerator"
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        val classLoader = param.defaultClassLoader ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

        // 1. Self activation check in module UI
        if (packageName == BiliAcceleratorCore.MODULE_PACKAGE) {
            try {
                val mainActivity = classLoader.loadClass("${BiliAcceleratorCore.MODULE_PACKAGE}.ui.MainActivity")
                val isModuleActiveMethod = mainActivity.getDeclaredMethod("isModuleActive")
                hook(isModuleActiveMethod).intercept {
                    true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Self hook failed", t)
            }
            return
        }

        // 2. Target Bilibili Apps
        if (BiliAcceleratorCore.TARGET_PACKAGES.contains(packageName)) {
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
