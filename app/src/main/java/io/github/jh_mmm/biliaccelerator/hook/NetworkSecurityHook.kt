package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object NetworkSecurityHook {

    private const val TAG = "BiliAccelerator-NetSec"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    internal fun isLocalLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        return h == "127.0.0.1" ||
                h == "localhost" ||
                h == "::1" ||
                h == "[::1]" ||
                h.startsWith("127.0.0.1:") ||
                h.startsWith("localhost:")
    }

    fun init(module: XposedModule, classLoader: ClassLoader) {
        try {
            val policyClass = try {
                classLoader.loadClass("android.security.NetworkSecurityPolicy")
            } catch (_: Throwable) {
                null
            } ?: return

            synchronized(hookedClasses) {
                if (!hookedClasses.add(policyClass)) return
            }

            // 仅 Hook 带域名参数的 isCleartextTrafficPermitted(String hostname)，精准放行 127.0.0.1/localhost；
            // 严禁 Hook 无参 isCleartextTrafficPermitted() 全局放行宿主对外部公网域名的明文 HTTP 流量。
            val hostMethods: List<Method> = (policyClass.declaredMethods + policyClass.methods).distinct().filter {
                it.name == "isCleartextTrafficPermitted" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
            for (m in hostMethods) {
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
                } catch (_: Throwable) {}
            }

            if (isHooked) {
                HookStatusTracker.recordStatus("network_security", "android.security.NetworkSecurityPolicy (loopback-only)")
                Log.i(TAG, "NetworkSecurityPolicy loopback-only cleartext hook successfully mounted")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook NetworkSecurityPolicy: ${t.message}")
        }
    }
}
