package com.realzza.biliaccelerator.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object MossGrpcHook {

    private const val TAG = "BiliAccelerator-Moss"

    private val RUNTIME_HELPER_CLASSES = listOf(
        "com.bilibili.lib.moss.utils.RuntimeHelper",
        "com.bilibili.lib.moss.RuntimeHelper",
        "com.bilibili.moss.utils.RuntimeHelper"
    )

    fun init(classLoader: ClassLoader) {
        var hooked = false
        for (className in RUNTIME_HELPER_CLASSES) {
            val runtimeHelper = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            try {
                val tfMethods = (runtimeHelper.declaredMethods + runtimeHelper.methods)
                    .distinct()
                    .filter { it.name == "tf" && it.returnType != Void.TYPE }

                for (method in tfMethods) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = RemoteClient.fetchConfig()
                            if (!config.enabled || !config.blockPcdn) return

                            val result = param.result ?: return

                            // 1. Primitive/Boxed number return type: 严格保持原类型避免拆箱 ClassCastException
                            if (result is Number) {
                                if (result.toLong() == 0L) {
                                    param.result = when (result) {
                                        is Long -> 1L
                                        is Int -> 1
                                        is Short -> 1.toShort()
                                        is Byte -> 1.toByte()
                                        else -> 1
                                    }
                                    XposedBridge.log("$TAG: Injected TF=1 (${result.javaClass.simpleName}) to force official mirror CDN from server")
                                }
                                return
                            }

                            // 2. Protobuf Enum return type
                            try {
                                val getNumber = result.javaClass.getMethod("getNumber")
                                val currentNum = getNumber.invoke(result) as? Int
                                if (currentNum == 0) {
                                    val forNumber = result.javaClass.getMethod("forNumber", Int::class.javaPrimitiveType)
                                    val mirrorTf = forNumber.invoke(null, 1)
                                    if (mirrorTf != null) {
                                        param.result = mirrorTf
                                        XposedBridge.log("$TAG: Injected TF=1 (enum) to force official mirror CDN from server")
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                }

                if (tfMethods.isNotEmpty()) {
                    XposedBridge.log("$TAG: Successfully hooked ${tfMethods.size} RuntimeHelper.tf() methods on $className")
                    hooked = true
                    break
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Failed to hook RuntimeHelper.tf() on $className: ${t.message}")
            }
        }

        if (!hooked) {
            XposedBridge.log("$TAG: RuntimeHelper class not found, skipping Moss TF hook")
        }
    }
}
