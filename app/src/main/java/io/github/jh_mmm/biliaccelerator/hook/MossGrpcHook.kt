package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.math.BigDecimal
import java.math.BigInteger

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
                // 仅拦截带单个请求上下文参数、声明在 RuntimeHelper 且返回类型符合协议预期（Boolean/String/Number/Enum）的 tf() 方法
                val tfMethods = (runtimeHelper.declaredMethods + runtimeHelper.methods)
                    .distinct()
                    .filter {
                        it.name == "tf" &&
                                it.declaringClass == runtimeHelper &&
                                it.parameterTypes.size == 1 &&
                                isSupportedReturnType(it.returnType)
                    }

                var hookCount = 0
                for (method in tfMethods) {
                    try {
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
                        hookCount++
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to hook method ${method.name} on $className: ${t.message}")
                    }
                }

                if (hookCount > 0) {
                    Log.i(TAG, "Successfully hooked $hookCount RuntimeHelper.tf() methods on $className")
                    hooked = true
                    HookStatusTracker.recordStatus("moss_grpc", "$className ($hookCount hooks)")
                    break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to process RuntimeHelper on $className: ${t.message}")
            }
        }

        if (!hooked) {
            HookStatusTracker.recordStatus("moss_grpc", "未找到 (版本不兼容)")
            Log.i(TAG, "RuntimeHelper class not found or incompatible, skipping Moss TF hook")
        }
    }

    internal fun isSupportedReturnType(clazz: Class<*>): Boolean {
        if (clazz == java.lang.Boolean.TYPE || clazz == java.lang.Boolean::class.java) return true
        if (clazz == String::class.java) return true
        if (Number::class.java.isAssignableFrom(clazz) || (clazz.isPrimitive && clazz != java.lang.Void.TYPE)) return true
        if (clazz.isEnum || clazz.name.contains("Tf", ignoreCase = true) || clazz.name.contains("Traffic", ignoreCase = true)) return true
        return false
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
