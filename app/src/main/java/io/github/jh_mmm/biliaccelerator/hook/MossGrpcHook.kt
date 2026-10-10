package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger

object MossGrpcHook {

    private const val TAG = "BiliAccelerator-Moss"
    private const val MAX_SAMPLE_LOGS = 20

    private val RUNTIME_HELPER_CLASSES = listOf(
        "com.bilibili.lib.moss.utils.RuntimeHelper",
        "com.bilibili.lib.moss.RuntimeHelper",
        "com.bilibili.moss.utils.RuntimeHelper"
    )

    private val sampleLogCount = AtomicInteger(0)
    private val TF_RUNTIME_KEY_REGEX = Regex(
        """(?:^|[._:/-])(tf|traffic|mirror|upos|pcdn|mcdn|bcache|cdn)(?:$|[._:/-])""",
        RegexOption.IGNORE_CASE
    )

    fun init(module: XposedModule, classLoader: ClassLoader) {
        var hooked = false
        for (className in RUNTIME_HELPER_CLASSES) {
            val runtimeHelper = try {
                Class.forName(className, false, classLoader)
            } catch (_: Throwable) {
                null
            } ?: continue

            try {
                // 收窄匹配：优先匹配单参数为 B 站业务上下文/Protobuf 对象的 tf() 方法；
                // 若类中仅存在 tf(String)，则退化为挂载 tf(String) 并在运行期按流量/镜像关键字白名单过滤，杜绝盲翻通用 Feature Flag。
                val allDeclared = (runtimeHelper.declaredMethods + runtimeHelper.methods).distinct()
                val strictMethods = allDeclared.filter { isCandidateTfMethod(it, runtimeHelper) }
                val tfMethods = if (strictMethods.isNotEmpty()) {
                    strictMethods
                } else {
                    allDeclared.filter { isStringKeyedTfMethod(it, runtimeHelper) }
                }

                var hookCount = 0
                for (method in tfMethods) {
                    val methodSignature = method.toGenericString()
                    try {
                        module.hook(method).intercept { chain ->
                            val config = RemoteClient.fetchConfig()
                            val result = chain.proceed()
                            if (!config.enabled || !config.blockPcdn || !config.enableMossHook || result == null) {
                                return@intercept result
                            }

                            val firstArg = chain.args.firstOrNull()
                            val argDesc = summarizeArg(firstArg)
                            if (!shouldAllowRuntimeArg(firstArg)) {
                                logSampledObservation(methodSignature, argDesc, result)
                                return@intercept result
                            }

                            val returnType = method.returnType

                            // 1. Boolean / boolean 返回类型
                            if (returnType == java.lang.Boolean.TYPE || returnType == java.lang.Boolean::class.java || result is Boolean) {
                                if (result == false) {
                                    logSampledInjection(methodSignature, argDesc, result, true)
                                    return@intercept true
                                }
                                return@intercept result
                            }

                            // 2. String 返回类型
                            if (returnType == String::class.java || result is String) {
                                val str = result.toString()
                                if (str == "0" || str.equals("false", ignoreCase = true) || str.isEmpty()) {
                                    logSampledInjection(methodSignature, argDesc, result, "1")
                                    return@intercept "1"
                                }
                                return@intercept result
                            }

                            // 3. Primitive/Boxed number 返回类型：严格按 method.returnType 匹配，杜绝拆箱 ClassCastException
                            if (result is Number) {
                                if (result.toDouble() == 0.0) {
                                    val modified = convertNumberToTfOne(returnType, result)
                                    logSampledInjection(methodSignature, argDesc, result, modified)
                                    return@intercept modified
                                }
                                return@intercept result
                            }

                            // 4. Protobuf Enum 返回类型
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
                                        logSampledInjection(methodSignature, argDesc, result, mirrorTf)
                                        return@intercept mirrorTf
                                    }
                                }
                            } catch (_: Throwable) {}

                            result
                        }
                        hookCount++
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to hook method $methodSignature on $className: ${t.message}")
                    }
                }

                if (hookCount > 0) {
                    Log.i(TAG, "Successfully hooked $hookCount RuntimeHelper.tf() methods on $className (gated by enableMossHook)")
                    hooked = true
                    HookStatusTracker.recordStatus("moss_grpc", "$className ($hookCount hooks, 实验开关受控)")
                    break
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to process RuntimeHelper on $className: ${t.message}")
            }
        }

        if (!hooked) {
            HookStatusTracker.recordStatus("moss_grpc", "未找到 (版本不兼容或已收窄跳过)")
            Log.i(TAG, "RuntimeHelper class not found or no eligible tf() method, skipping Moss TF hook")
        }
    }

    private fun summarizeArg(arg: Any?): String {
        if (arg == null) return "null"
        val className = arg.javaClass.name
        val str = runCatching { arg.toString().take(80) }.getOrDefault(className)
        return "$className($str)"
    }

    private fun logSampledObservation(signature: String, argDesc: String, currentVal: Any) {
        val count = sampleLogCount.incrementAndGet()
        if (count <= MAX_SAMPLE_LOGS) {
            Log.i(TAG, "[Sample #$count/$MAX_SAMPLE_LOGS] Moss TF skipped non-traffic key on [$signature] arg=[$argDesc] val=$currentVal")
        }
    }

    private fun logSampledInjection(signature: String, argDesc: String, oldVal: Any, newVal: Any) {
        val count = sampleLogCount.incrementAndGet()
        if (count <= MAX_SAMPLE_LOGS) {
            Log.i(TAG, "[Sample #$count/$MAX_SAMPLE_LOGS] Moss TF override on [$signature] arg=[$argDesc]: $oldVal -> $newVal")
        }
    }

    internal fun shouldAllowRuntimeArg(arg: Any?): Boolean {
        if (arg == null) return false
        if (arg is CharSequence) {
            val key = arg.toString().trim()
            return key.isNotEmpty() && TF_RUNTIME_KEY_REGEX.containsMatchIn(key)
        }
        return isAllowedParameterType(arg.javaClass)
    }

    internal fun isStringKeyedTfMethod(method: Method, declaringTarget: Class<*> = method.declaringClass): Boolean {
        if (method.name != "tf" || method.declaringClass != declaringTarget || method.parameterTypes.size != 1) {
            return false
        }
        return method.parameterTypes[0] == String::class.java && isSupportedReturnType(method.returnType)
    }

    internal fun isCandidateTfMethod(method: Method, declaringTarget: Class<*> = method.declaringClass): Boolean {
        if (method.name != "tf" || method.declaringClass != declaringTarget || method.parameterTypes.size != 1) {
            return false
        }
        val paramType = method.parameterTypes[0]
        if (!isAllowedParameterType(paramType)) {
            return false
        }
        return isSupportedReturnType(method.returnType)
    }

    /**
     * 排除 String、基本数据类型、包装数字/布尔、集合等通用 Feature Flag / Toggle 查询入参，
     * 仅允许 B 站业务上下文或 Protobuf 对象作为入参，防止误将通用实验开关翻转。
     */
    internal fun isAllowedParameterType(paramType: Class<*>): Boolean {
        if (paramType.isPrimitive || paramType.isArray) return false
        if (paramType == String::class.java || CharSequence::class.java.isAssignableFrom(paramType)) return false
        if (paramType == Any::class.java || paramType == java.lang.Boolean::class.java) return false
        if (Number::class.java.isAssignableFrom(paramType)) return false
        val pkgName = paramType.name
        if (pkgName.startsWith("java.") || pkgName.startsWith("javax.") || pkgName.startsWith("kotlin.") || pkgName.startsWith("android.")) {
            return false
        }
        return true
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

