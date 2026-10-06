package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.math.BigDecimal
import java.math.BigInteger
import java.lang.reflect.Method

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
                // 仅拦截无参或单个上下文参数的 tf() 方法，避免误命中同名其他重载方法
                val tfMethods = (runtimeHelper.declaredMethods + runtimeHelper.methods)
                    .distinct()
                    .filter { it.name == "tf" && it.returnType != Void.TYPE && it.parameterTypes.size <= 1 }

                for (method in tfMethods) {
                    module.hook(method).intercept { chain ->
                        val config = RemoteClient.fetchConfig()
                        val result = chain.proceed()
                        if (!config.enabled || !config.blockPcdn || result == null) {
                            return@intercept result
                        }

                        val returnType = method.returnType

                        // 1. Boolean / boolean 返回类型
                        if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java || result is Boolean) {
                            if (result == false) {
                                Log.i(TAG, "Injected TF=true (Boolean) to force official mirror CDN from server")
                                return@intercept true
                            }
                            return@intercept result
                        }

                        // 2. String 返回类型
                        if (returnType == String::class.java || result is String) {
                            val str = result.toString()
                            if (str == "0" || str.equals("false", ignoreCase = true) || str.isEmpty()) {
                                Log.i(TAG, "Injected TF='1' (String) to force official mirror CDN from server")
                                return@intercept "1"
                            }
                            return@intercept result
                        }

                        // 3. Primitive/Boxed number 返回类型：严格按 method.returnType 匹配，杜绝拆箱 ClassCastException
                        if (result is Number) {
                            if (result.toDouble() == 0.0) {
                                val modified = convertNumberToTfOne(returnType, result)
                                Log.i(TAG, "Injected TF=1 (${modified.javaClass.simpleName}) to force official mirror CDN from server")
                                return@intercept modified
                            }
                            return@intercept result
                        }

                        // 4. Protobuf Enum 返回类型
                        // 在 B 站 Moss gRPC 协议中，TF (Traffic Flow / Mirror Flag) 枚举中：
                        // TF=0 代表 UNSPECIFIED / DEFAULT（默认算法调度，包含大量 PCDN/MCDN 节点）；
                        // TF=1 代表 OFFICIAL / MIRROR（由服务端强制下发官方 UPOS 直连镜像节点）。
                        try {
                            val getNumber = result.javaClass.getMethod("getNumber")
                            val currentNum = getNumber.invoke(result) as? Int
                            if (currentNum == 0) {
                                val forNumberMethod = try {
                                    result.javaClass.getMethod("forNumber", Int::class.javaPrimitiveType)
                                } catch (_: NoSuchMethodException) {
                                    result.javaClass.getMethod("valueOf", Int::class.javaPrimitiveType)
                                }
                                val mirrorTf = forNumberMethod.invoke(null, 1)
                                if (mirrorTf != null) {
                                    Log.i(TAG, "Injected TF=1 (protobuf enum: ${result.javaClass.simpleName}) to force official mirror CDN from server")
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
                    HookStatusTracker.recordStatus("moss_grpc", className)
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

    internal fun convertNumberToTfOne(returnType: Class<*>, result: Number): Any {
        return when {
            returnType == java.lang.Long.TYPE || returnType == java.lang.Long::class.java || result is Long -> 1L
            returnType == java.lang.Integer.TYPE || returnType == java.lang.Integer::class.java || result is Int -> 1
            returnType == java.lang.Short.TYPE || returnType == java.lang.Short::class.java || result is Short -> 1.toShort()
            returnType == java.lang.Byte.TYPE || returnType == java.lang.Byte::class.java || result is Byte -> 1.toByte()
            returnType == java.lang.Float.TYPE || returnType == java.lang.Float::class.java || result is Float -> 1.0f
            returnType == java.lang.Double.TYPE || returnType == java.lang.Double::class.java || result is Double -> 1.0
            result is BigDecimal -> BigDecimal.ONE
            result is BigInteger -> BigInteger.ONE
            else -> 1
        }
    }
}
