package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object NetworkSecurityHook {

    private const val TAG = "BiliAccelerator-NetSec"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedMethods = Collections.newSetFromMap(WeakHashMap<Method, Boolean>())

    private val KNOWN_POLICY_CLASSES = listOf(
        "android.security.net.config.FrameworkNetworkSecurityPolicy",
        "libcore.net.NetworkSecurityPolicy\$DefaultNetworkSecurityPolicy",
        "libcore.net.NetworkSecurityPolicy",
        "android.security.NetworkSecurityPolicy"
    )

    internal fun isLocalLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        return h == "127.0.0.1" ||
                h == "localhost" ||
                h == "::1" ||
                h == "[::1]" ||
                h.startsWith("127.0.0.1:") ||
                h.startsWith("localhost:")
    }

    internal fun resolveConcretePolicyClasses(classLoader: ClassLoader): List<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        val baseClass = runCatching {
            classLoader.loadClass("android.security.NetworkSecurityPolicy")
        }.getOrNull()

        if (baseClass != null) {
            runCatching {
                val getInstance = baseClass.getMethod("getInstance")
                if (!getInstance.isAccessible) {
                    runCatching { getInstance.isAccessible = true }
                }
                val instance = getInstance.invoke(null)
                if (instance != null) {
                    result.add(instance.javaClass)
                }
            }
            result.add(baseClass)
        }

        for (name in KNOWN_POLICY_CLASSES) {
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let { result.add(it) }
        }
        return result.toList()
    }

    fun init(module: XposedModule, classLoader: ClassLoader) {
        try {
            val candidateClasses = resolveConcretePolicyClasses(classLoader)
            if (candidateClasses.isEmpty()) return

            var newlyHookedCount = 0
            for (policyClass in candidateClasses) {
                val addedClass = synchronized(hookedClasses) { hookedClasses.add(policyClass) }
                if (!addedClass) continue

                // 仅 Hook 非抽象的带域名参数 isCleartextTrafficPermitted(String hostname)，精准放行 127.0.0.1/localhost；
                // 严禁 Hook 无参 isCleartextTrafficPermitted() 全局放行宿主对外部公网域名的明文 HTTP 流量。
                val hostMethods = collectConcreteHostMethods(policyClass)
                for (m in hostMethods) {
                    val shouldHook = synchronized(hookedMethods) { hookedMethods.add(m) }
                    if (!shouldHook) continue
                    try {
                        module.hook(m).intercept { chain ->
                            val host = chain.args.firstOrNull() as? String
                            if (isLocalLoopbackHost(host)) {
                                val config = RemoteClient.getCachedConfig()
                                if (config.enabled && (config.enableBtr || io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted)) {
                                    return@intercept true
                                }
                            }
                            chain.proceed()
                        }
                        isHooked = true
                        newlyHookedCount++
                    } catch (t: Throwable) {
                        Log.d(TAG, "Failed to hook ${m.declaringClass.name}.${m.name}: ${t.message}")
                    }
                }
            }

            if (newlyHookedCount > 0 && isHooked) {
                HookStatusTracker.recordStatus("network_security", "NetworkSecurityPolicy concrete (loopback-only)")
                Log.i(TAG, "NetworkSecurityPolicy loopback-only cleartext hook successfully mounted ($newlyHookedCount methods)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook NetworkSecurityPolicy: ${t.message}")
        }
    }

    private fun collectConcreteHostMethods(startClass: Class<*>): List<Method> {
        val methods = LinkedHashSet<Method>()
        var cls: Class<*>? = startClass
        while (cls != null && cls != Any::class.java) {
            for (m in cls.declaredMethods) {
                if (m.name == "isCleartextTrafficPermitted" &&
                    !Modifier.isAbstract(m.modifiers) &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == String::class.java
                ) {
                    methods.add(m)
                }
            }
            cls = cls.superclass
        }
        return methods.toList()
    }
}
