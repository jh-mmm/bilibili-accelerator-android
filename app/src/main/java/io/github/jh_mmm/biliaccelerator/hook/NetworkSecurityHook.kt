package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.libxposed.api.XposedModule

object NetworkSecurityHook {

    private const val TAG = "BiliAccelerator-NetSec"

    @Volatile
    var isHooked: Boolean = false
        internal set

    fun init(module: XposedModule, classLoader: ClassLoader) {
        try {
            val policyClass = try {
                classLoader.loadClass("android.security.NetworkSecurityPolicy")
            } catch (_: Throwable) {
                null
            } ?: return

            // 1. Hook isCleartextTrafficPermitted(String hostname)
            val hostMethods = (policyClass.declaredMethods + policyClass.methods).distinct().filter {
                it.name == "isCleartextTrafficPermitted" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            }
            for (m in hostMethods) {
                try {
                    module.hook(m).intercept { chain ->
                        val host = chain.args.firstOrNull() as? String
                        if (host != null && (host == "127.0.0.1" || host == "localhost" || host.startsWith("127.0.0.1:"))) {
                            return@intercept true
                        }
                        chain.proceed()
                    }
                    isHooked = true
                } catch (_: Throwable) {}
            }

            // 2. Hook 无参 isCleartextTrafficPermitted()
            val noArgMethods = (policyClass.declaredMethods + policyClass.methods).distinct().filter {
                it.name == "isCleartextTrafficPermitted" && it.parameterTypes.isEmpty()
            }
            for (m in noArgMethods) {
                try {
                    module.hook(m).intercept { chain ->
                        // 允许明文流量兜底
                        true
                    }
                    isHooked = true
                } catch (_: Throwable) {}
            }

            if (isHooked) {
                HookStatusTracker.recordStatus("network_security", "android.security.NetworkSecurityPolicy")
                Log.i(TAG, "NetworkSecurityPolicy cleartext hook successfully mounted")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook NetworkSecurityPolicy: ${t.message}")
        }
    }
}
