package com.realzza.biliaccelerator.hook

import com.realzza.biliaccelerator.core.BiliAcceleratorCore
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val MODULE_PACKAGE = "com.realzza.biliaccelerator"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Self activation check in module UI
        if (lpparam.packageName == MODULE_PACKAGE) {
            try {
                val mainActivity = XposedHelpers.findClassIfExists(
                    "$MODULE_PACKAGE.ui.MainActivity",
                    lpparam.classLoader
                )
                if (mainActivity != null) {
                    XposedHelpers.findAndHookMethod(
                        mainActivity,
                        "isModuleActive",
                        XC_MethodReplacement.returnConstant(true)
                    )
                }
            } catch (_: Throwable) {}
            return
        }

        // 2. Target Bilibili Apps
        if (BiliAcceleratorCore.TARGET_PACKAGES.contains(lpparam.packageName)) {
            val proc = lpparam.processName
            // 过滤已知纯后台/非播放进程，减小无谓的反射查找与内存开销
            if (proc.endsWith(":push") || proc.endsWith(":channel") || proc.endsWith(":web") || proc.contains(":isolated")) {
                return
            }

            XposedBridge.log("BiliAccelerator: Injecting into ${lpparam.packageName} (process: $proc)")

            // Media Player Hook
            PlayerHook.init(lpparam.classLoader)

            // gRPC Protocol Hook
            MossGrpcHook.init(lpparam.classLoader)
        }
    }
}
