package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule

object MossGrpcHook {

    private const val TAG = "BiliAccelerator-Moss"

    private val RUNTIME_HELPER_CLASSES = listOf(
        "com.bilibili.lib.moss.utils.RuntimeHelper",
        "com.bilibili.lib.moss.RuntimeHelper",
        "com.bilibili.moss.utils.RuntimeHelper"
    )

    fun init(module: XposedModule, classLoader: ClassLoader) {
        var hooked = false
        for (className in RUNTIME_HELPER_CLASSES) {
            val runtimeHelper = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                null
            } ?: continue

            try {
                val tfMethods = (runtimeHelper.declaredMethods + runtimeHelper.methods)
                    .distinct()
                    .filter { it.name == "tf" && it.returnType != Void.TYPE }

                for (method in tfMethods) {
                    module.hook(method).intercept { chain ->
                        val config = RemoteClient.fetchConfig()
                        val result = chain.proceed()
                        if (!config.enabled || !config.blockPcdn || result == null) {
                            return@intercept result
                        }

                        // 1. Primitive/Boxed number return type: 严格保持原类型避免拆箱 ClassCastException
                        if (result is Number) {
                            if (result.toLong() == 0L) {
                                val modified = when (result) {
                                    is Long -> 1L
                                    is Int -> 1
                                    is Short -> 1.toShort()
                                    is Byte -> 1.toByte()
                                    else -> 1
                                }
                                Log.i(TAG, "Injected TF=1 (${result.javaClass.simpleName}) to force official mirror CDN from server")
                                return@intercept modified
                            }
                            return@intercept result
                        }

                        // 2. Protobuf Enum return type
                        try {
                            val getNumber = result.javaClass.getMethod("getNumber")
                            val currentNum = getNumber.invoke(result) as? Int
                            if (currentNum == 0) {
                                val forNumber = result.javaClass.getMethod("forNumber", Int::class.javaPrimitiveType)
                                val mirrorTf = forNumber.invoke(null, 1)
                                if (mirrorTf != null) {
                                    Log.i(TAG, "Injected TF=1 (enum) to force official mirror CDN from server")
                                    return@intercept mirrorTf
                                }
                            }
                        } catch (_: Throwable) {}

                        result
                    }
                }

                if (tfMethods.isNotEmpty()) {
                    Log.i(TAG, "Successfully hooked ${tfMethods.size} RuntimeHelper.tf() methods on $className")
                    hooked = true
                    break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook RuntimeHelper.tf() on $className: ${t.message}")
            }
        }

        if (!hooked) {
            Log.i(TAG, "RuntimeHelper class not found, skipping Moss TF hook")
        }
    }
}
