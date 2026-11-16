package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRefreshCallback
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object MossGrpcHook {

    private const val TAG = "BiliAccelerator-Moss"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val inPlayinfoInspect = ThreadLocal.withInitial { false }
    private val inGrpcRefresh = ThreadLocal.withInitial { false }
    private val inHttpRefresh = ThreadLocal.withInitial { false }

    private data class CapturedHttpPlayurlCall(
        val client: Any,
        val request: Any,
        val url: String,
        val capturedAtMs: Long
    )

    private val lastHttpPlayurlCall = AtomicReference<CapturedHttpPlayurlCall?>(null)

    private val RUNTIME_HELPER_CLASSES = listOf(
        "com.bilibili.lib.moss.utils.RuntimeHelper",
        "com.bilibili.lib.moss.RuntimeHelper",
        "com.bilibili.moss.utils.RuntimeHelper"
    )

    private val PLAY_VIEW_REPLY_CLASSES = listOf(
        "com.bapis.bilibili.app.playurl.v1.PlayViewReply",
        "com.bapis.bilibili.playershared.PlayViewReply",
        "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply",
        "com.bapis.bilibili.pgc.gateway.player.v2.PlayViewReply",
        "com.bapis.bilibili.pgc.gateway.player.v1.PlayViewReply",
        "com.bapis.bilibili.app.playurl.v1.PlayURLReply"
    )

    fun init(module: XposedModule, classLoader: ClassLoader) {
        var hooked = false
        for (className in RUNTIME_HELPER_CLASSES) {
            val runtimeHelper = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                null
            } ?: continue

            val alreadyHooked = synchronized(hookedClasses) {
                !hookedClasses.add(runtimeHelper)
            }
            if (alreadyHooked) {
                hooked = true
                break
            }

            try {
                // 仅拦截无参或单个上下文参数的 tf() 方法，避免误命中同名其他重载方法
                val tfMethods = (runtimeHelper.declaredMethods + runtimeHelper.methods)
                    .distinct()
                    .filter { it.name == "tf" && it.returnType != Void.TYPE && it.parameterTypes.size <= 1 }

                for (method in tfMethods) {
                    module.hook(method).intercept { chain ->
                        // 非阻塞检查：先使用缓存配置，避免在未启用 Moss Hook 时阻塞冷启动或关键线程进行同步 IPC
                        val cachedConfig = RemoteClient.getCachedConfig()
                        val result = chain.proceed()
                        if (!cachedConfig.enabled || !cachedConfig.enableMossHook) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept result
                        }
                        val config = RemoteClient.fetchConfig()
                        if (!shouldIntercept(config, result)) {
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
                        if (result != null) {
                            try {
                                val getNumber = result.javaClass.getMethod("getNumber").apply {
                                    if (!isAccessible) runCatching { isAccessible = true }
                                }
                                val currentNum = getNumber.invoke(result) as? Int
                                if (currentNum == 0) {
                                    val forNumberMethod = try {
                                        result.javaClass.getMethod("forNumber", Int::class.javaPrimitiveType)
                                    } catch (_: NoSuchMethodException) {
                                        result.javaClass.getMethod("valueOf", Int::class.javaPrimitiveType)
                                    }.apply {
                                        if (!isAccessible) runCatching { isAccessible = true }
                                    }
                                    val mirrorTf = forNumberMethod.invoke(null, 1)
                                    if (mirrorTf != null) {
                                        Log.i(TAG, "Injected TF=1 (protobuf enum: ${result.javaClass.simpleName}) to force official mirror CDN from server")
                                        return@intercept mirrorTf
                                    }
                                }
                            } catch (_: Throwable) {}
                        }

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

        if (hookPlayViewReplies(module, classLoader)) hooked = true
        if (hookGrpcClientCalls(module, classLoader)) hooked = true
        if (hookHttpPlayinfo(module, classLoader)) hooked = true

        if (hooked) {
            isHooked = true
        } else {
            Log.i(TAG, "RuntimeHelper / Playinfo classes not found, skipping Moss hooks")
        }
    }

    internal fun resetPlayinfoCaptureForTesting() {
        lastHttpPlayurlCall.set(null)
    }

    internal fun hasCapturedHttpPlayurlCallForTesting(): Boolean {
        return lastHttpPlayurlCall.get() != null
    }

    internal fun inspectPlayViewReplyIfEnabled(
        reply: Any?,
        sourceTag: String,
        refreshCallback: PlayinfoRefreshCallback? = null
    ): Boolean {
        if (!RemoteClient.getCachedConfig().enabled) return false
        if (reply == null || inPlayinfoInspect.get() == true) return false
        inPlayinfoInspect.set(true)
        return try {
            PlayinfoRegistry.ingestPlayViewReply(
                reply,
                sourceTag = sourceTag,
                refreshCallback = refreshCallback
            ).isNotEmpty()
        } catch (_: Throwable) {
            false
        } finally {
            inPlayinfoInspect.set(false)
        }
    }

    private fun invokeZeroArgAccessible(target: Any, methodName: String): Any? {
        val m = target.javaClass.getMethod(methodName)
        if (!m.isAccessible) {
            runCatching { m.isAccessible = true }
        }
        return m.invoke(target)
    }

    internal fun inspectHttpPlayinfoJsonIfEnabled(
        text: String?,
        allowRefreshCallback: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!RemoteClient.getCachedConfig().enabled) return false
        if (text == null || inPlayinfoInspect.get() == true || (allowRefreshCallback && inHttpRefresh.get() == true)) {
            return false
        }
        if (!fastLooksLikePlayinfoJson(text)) return false
        inPlayinfoInspect.set(true)
        return try {
            val caller = if (allowRefreshCallback) lastHttpPlayurlCall.get() else null
            val recentCaller = if (caller != null) {
                val ageMs = nowMs - caller.capturedAtMs
                if (ageMs in 0L..10_000L) {
                    caller
                } else {
                    if (ageMs > 10_000L) {
                        lastHttpPlayurlCall.compareAndSet(caller, null)
                    }
                    null
                }
            } else null
            val recentRefreshCb = recentCaller?.let { buildHttpRefreshCallback(it) }
            val ingested = PlayinfoRegistry.ingestJson(
                text,
                sourceUrl = recentCaller?.url.orEmpty(),
                refreshCallback = recentRefreshCb
            ).isNotEmpty()
            if (ingested && recentCaller != null) {
                lastHttpPlayurlCall.compareAndSet(recentCaller, null)
            }
            ingested
        } catch (_: Throwable) {
            false
        } finally {
            inPlayinfoInspect.set(false)
        }
    }

    internal fun captureHttpPlayurlCallIfEnabled(
        client: Any?,
        req: Any?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!RemoteClient.getCachedConfig().enabled) return false
        if (client == null || req == null || inHttpRefresh.get() == true) return false
        val methodStr = runCatching { invokeZeroArgAccessible(req, "method")?.toString() }.getOrNull()
        val urlStr = runCatching { invokeZeroArgAccessible(req, "url")?.toString() }.getOrNull()
        if (methodStr.equals("GET", ignoreCase = true) && urlStr != null && isPlayurlApiUrl(urlStr)) {
            lastHttpPlayurlCall.set(CapturedHttpPlayurlCall(client, req, urlStr, nowMs))
            return true
        }
        return false
    }

    private fun hookPlayViewReplies(module: XposedModule, classLoader: ClassLoader): Boolean {
        var anyHooked = false
        for (className in PLAY_VIEW_REPLY_CLASSES) {
            val replyClass = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                null
            } ?: continue

            val newlyAdded = synchronized(hookedClasses) { hookedClasses.add(replyClass) }
            if (!newlyAdded) {
                anyHooked = true
                continue
            }

            try {
                val parseFromMethods = replyClass.declaredMethods.filter { m ->
                    m.name == "parseFrom" &&
                        Modifier.isStatic(m.modifiers) &&
                        replyClass.isAssignableFrom(m.returnType)
                }
                for (m in parseFromMethods) {
                    module.hook(m).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        val reply = chain.proceed()
                        inspectPlayViewReplyIfEnabled(reply, sourceTag = className)
                        reply
                    }
                }
                if (parseFromMethods.isNotEmpty()) {
                    anyHooked = true
                    HookStatusTracker.recordStatus("moss_playview", className)
                    Log.i(TAG, "Hooked ${parseFromMethods.size} parseFrom methods on $className")
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to hook parseFrom on $className: ${t.message}")
            }
        }
        return anyHooked
    }

    internal fun attachFuturePlayViewListener(
        futureObj: Any?,
        executor: Executor = Executor { runnable -> runnable.run() }
    ): Boolean {
        if (!RemoteClient.getCachedConfig().enabled) return false
        if (futureObj !is Future<*> || inGrpcRefresh.get() == true) return false
        return runCatching {
            val addListener = futureObj.javaClass.getMethod("addListener", Runnable::class.java, Executor::class.java)
            if (!addListener.isAccessible) {
                runCatching { addListener.isAccessible = true }
            }
            addListener.invoke(
                futureObj,
                Runnable {
                    if (!RemoteClient.getCachedConfig().enabled) return@Runnable
                    val doneVal = runCatching { futureObj.get() }.getOrNull()
                    if (doneVal != null && looksLikePlayViewObject(doneVal)) {
                        inspectPlayViewReplyIfEnabled(doneVal, sourceTag = "grpc-future")
                    }
                },
                executor
            )
            true
        }.getOrDefault(false)
    }

    internal fun wrapAsyncStreamObserver(
        streamObserverClass: Class<*>,
        origObserver: Any?,
        classLoader: ClassLoader = streamObserverClass.classLoader ?: MossGrpcHook::class.java.classLoader
    ): Any? {
        if (!RemoteClient.getCachedConfig().enabled) return null
        if (origObserver == null || !streamObserverClass.isInstance(origObserver) || inGrpcRefresh.get() == true) {
            return null
        }
        return runCatching {
            Proxy.newProxyInstance(
                streamObserverClass.classLoader ?: classLoader,
                arrayOf(streamObserverClass)
            ) { _, method, proxyArgs ->
                if (method.name == "onNext" && proxyArgs != null && proxyArgs.isNotEmpty() && RemoteClient.getCachedConfig().enabled) {
                    val value = proxyArgs[0]
                    if (value != null && looksLikePlayViewObject(value)) {
                        inspectPlayViewReplyIfEnabled(value, sourceTag = "grpc-async")
                    }
                }
                if (!method.isAccessible) {
                    runCatching { method.isAccessible = true }
                }
                try {
                    method.invoke(origObserver, *(proxyArgs ?: emptyArray()))
                } catch (ite: InvocationTargetException) {
                    throw ite.targetException ?: ite
                }
            }
        }.getOrNull()
    }

    private fun hookGrpcClientCalls(module: XposedModule, classLoader: ClassLoader): Boolean {
        val clientCallsClass = try {
            classLoader.loadClass("io.grpc.stub.ClientCalls")
        } catch (_: Throwable) {
            null
        } ?: return false

        val newlyAdded = synchronized(hookedClasses) { hookedClasses.add(clientCallsClass) }
        if (!newlyAdded) return true

        var anyHooked = false
        try {
            val methods = (clientCallsClass.declaredMethods + clientCallsClass.methods).distinct()
            val directExecutor = Executor { runnable -> runnable.run() }
            val streamObserverClass = runCatching { classLoader.loadClass("io.grpc.stub.StreamObserver") }.getOrNull()

            for (m in methods) {
                if (m.name == "blockingUnaryCall") {
                    module.hook(m).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        val res = chain.proceed()
                        if (RemoteClient.getCachedConfig().enabled && res != null && inGrpcRefresh.get() != true && looksLikePlayViewObject(res)) {
                            val refreshCb = buildGrpcRefreshCallback(m, chain.args)
                            inspectPlayViewReplyIfEnabled(res, sourceTag = "grpc-blocking", refreshCallback = refreshCb)
                        }
                        res
                    }
                    anyHooked = true
                } else if (m.name == "futureUnaryCall") {
                    module.hook(m).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        val futureObj = chain.proceed()
                        attachFuturePlayViewListener(futureObj, directExecutor)
                        futureObj
                    }
                    anyHooked = true
                } else if (m.name == "asyncUnaryCall" && streamObserverClass != null && m.parameterTypes.lastOrNull() == streamObserverClass) {
                    module.hook(m).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        val args = chain.args
                        val observerIdx = args.lastIndex
                        val origObserver = args.getOrNull(observerIdx)
                        val proxyObserver = wrapAsyncStreamObserver(streamObserverClass, origObserver, classLoader)
                        if (proxyObserver != null) {
                            val newArgs = args.toTypedArray().apply { this[observerIdx] = proxyObserver }
                            return@intercept chain.proceed(newArgs)
                        }
                        chain.proceed()
                    }
                    anyHooked = true
                }
            }
            if (anyHooked) {
                HookStatusTracker.recordStatus("moss_grpc_calls", "io.grpc.stub.ClientCalls")
                Log.i(TAG, "Hooked io.grpc.stub.ClientCalls for PlayView inspection & proactive refresh")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to hook io.grpc.stub.ClientCalls: ${t.message}")
        }
        return anyHooked
    }

    internal fun buildGrpcRefreshCallback(method: Method, args: List<Any?>): PlayinfoRefreshCallback? {
        if (args.size != 4) return null
        val channel = args[0] ?: return null
        val methodDesc = args[1] ?: return null
        val rawCallOptions = args[2] ?: return null
        val reqMsg = args[3] ?: return null
        val fullMethodName = runCatching {
            invokeZeroArgAccessible(methodDesc, "getFullMethodName")?.toString()
        }.getOrNull().orEmpty()
        if (!fullMethodName.contains("PlayView", ignoreCase = true) &&
            !fullMethodName.contains("PlayURL", ignoreCase = true)
        ) {
            return null
        }

        return PlayinfoRefreshCallback {
            if (!RemoteClient.getCachedConfig().enabled) return@PlayinfoRefreshCallback false
            if (inGrpcRefresh.get() == true) return@PlayinfoRefreshCallback false
            inGrpcRefresh.set(true)
            try {
                val freshOptions = runCatching {
                    val withDeadlineAfter = rawCallOptions.javaClass.getMethod(
                        "withDeadlineAfter",
                        Long::class.javaPrimitiveType,
                        TimeUnit::class.java
                    ).apply {
                        if (!isAccessible) runCatching { isAccessible = true }
                    }
                    withDeadlineAfter.invoke(rawCallOptions, 10L, TimeUnit.SECONDS)
                }.getOrElse { rawCallOptions }
                if (!method.isAccessible) {
                    runCatching { method.isAccessible = true }
                }
                val freshReply = method.invoke(null, channel, methodDesc, freshOptions, reqMsg) ?: return@PlayinfoRefreshCallback false
                val tracks = PlayinfoRegistry.ingestPlayViewReply(freshReply, sourceTag = "grpc-refresh")
                tracks.isNotEmpty()
            } catch (_: Throwable) {
                false
            } finally {
                inGrpcRefresh.set(false)
            }
        }
    }

    private fun hookHttpPlayinfo(module: XposedModule, classLoader: ClassLoader): Boolean {
        var anyHooked = false

        // 1. OkHttp ResponseBody.string() + OkHttpClient.newCall()
        val responseBodyClass = runCatching { classLoader.loadClass("okhttp3.ResponseBody") }.getOrNull()
        if (responseBodyClass != null && synchronized(hookedClasses) { hookedClasses.add(responseBodyClass) }) {
            runCatching {
                val strMethod = responseBodyClass.getDeclaredMethod("string")
                module.hook(strMethod).intercept { chain ->
                    if (!RemoteClient.getCachedConfig().enabled) {
                        RemoteClient.refreshConfigAsyncIfStale()
                        return@intercept chain.proceed()
                    }
                    val text = chain.proceed() as? String
                    inspectHttpPlayinfoJsonIfEnabled(text, allowRefreshCallback = true)
                    text
                }
                anyHooked = true
            }
        }

        val okClientClass = runCatching { classLoader.loadClass("okhttp3.OkHttpClient") }.getOrNull()
        val reqClass = runCatching { classLoader.loadClass("okhttp3.Request") }.getOrNull()
        if (okClientClass != null && reqClass != null && synchronized(hookedClasses) { hookedClasses.add(okClientClass) }) {
            runCatching {
                val newCallMethod = okClientClass.getDeclaredMethod("newCall", reqClass)
                module.hook(newCallMethod).intercept { chain ->
                    if (RemoteClient.getCachedConfig().enabled) {
                        captureHttpPlayurlCallIfEnabled(chain.thisObject, chain.args.firstOrNull())
                    } else {
                        RemoteClient.refreshConfigAsyncIfStale()
                    }
                    chain.proceed()
                }
                anyHooked = true
            }
        }

        // 2. org.json.JSONObject(String) 兜底（覆盖 Cronet / HttpURLConnection 等解析路径）
        val jsonObjectClass = runCatching { classLoader.loadClass("org.json.JSONObject") }.getOrNull()
        if (jsonObjectClass != null && synchronized(hookedClasses) { hookedClasses.add(jsonObjectClass) }) {
            runCatching {
                val ctor = jsonObjectClass.getConstructor(String::class.java)
                module.hook(ctor).intercept { chain ->
                    if (!RemoteClient.getCachedConfig().enabled) {
                        RemoteClient.refreshConfigAsyncIfStale()
                        return@intercept chain.proceed()
                    }
                    val jsonStr = chain.args.firstOrNull() as? String
                    val obj = chain.proceed()
                    inspectHttpPlayinfoJsonIfEnabled(jsonStr, allowRefreshCallback = false)
                    obj
                }
                anyHooked = true
            }
        }

        if (anyHooked) {
            HookStatusTracker.recordStatus("http_playinfo", "OkHttp/JSONObject")
        }
        return anyHooked
    }

    private fun buildHttpRefreshCallback(caller: CapturedHttpPlayurlCall): PlayinfoRefreshCallback {
        return PlayinfoRefreshCallback {
            if (!RemoteClient.getCachedConfig().enabled) return@PlayinfoRefreshCallback false
            if (inHttpRefresh.get() == true) return@PlayinfoRefreshCallback false
            inHttpRefresh.set(true)
            try {
                val newCall = caller.client.javaClass.methods.firstOrNull {
                    it.name == "newCall" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(caller.request)
                } ?: return@PlayinfoRefreshCallback false
                if (!newCall.isAccessible) {
                    runCatching { newCall.isAccessible = true }
                }
                val callObj = newCall.invoke(caller.client, caller.request) ?: return@PlayinfoRefreshCallback false
                val respObj = invokeZeroArgAccessible(callObj, "execute") ?: return@PlayinfoRefreshCallback false
                try {
                    val isSuccessful = runCatching {
                        invokeZeroArgAccessible(respObj, "isSuccessful") as? Boolean
                    }.getOrNull() ?: true
                    if (!isSuccessful) return@PlayinfoRefreshCallback false
                    val bodyObj = invokeZeroArgAccessible(respObj, "body") ?: return@PlayinfoRefreshCallback false
                    val bodyText = invokeZeroArgAccessible(bodyObj, "string") as? String
                        ?: return@PlayinfoRefreshCallback false
                    PlayinfoRegistry.ingestJson(bodyText, sourceUrl = caller.url).isNotEmpty()
                } finally {
                    runCatching { invokeZeroArgAccessible(respObj, "close") }
                }
            } catch (_: Throwable) {
                false
            } finally {
                inHttpRefresh.set(false)
            }
        }
    }

    internal fun fastLooksLikePlayinfoJson(text: String): Boolean {
        if (text.length !in 128..2_000_000) return false
        if (text.startsWith("#EXTM3U", ignoreCase = true)) return false
        val trimmedStart = text.indexOf('{')
        if (trimmedStart !in 0..16) return false
        val hasTrackField = text.contains("\"dash\"") || text.contains("\"durl\"") ||
            text.contains("\"support_formats\"") || text.contains("\"video_info\"") ||
            text.contains("\"vod_info\"") || text.contains("\"stream_list\"")
        if (!hasTrackField) return false
        return text.contains("bilivideo") || text.contains("bstarstatic") ||
            text.contains("akamaized") || text.contains("upgcxcode") ||
            text.contains("is_drm") || text.contains("drm_tech_type") ||
            text.contains("is_preview")
    }

    internal fun isPlayurlApiUrl(url: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return false
        val parsed = BiliAcceleratorCore.parseUri(url) ?: return false
        val host = parsed.host.lowercase()
        val isBiliApiHost = host == "bilibili.com" || host.endsWith(".bilibili.com") ||
            host == "biliapi.net" || host.endsWith(".biliapi.net") ||
            host == "biliapi.com" || host.endsWith(".biliapi.com") ||
            host == "biliintl.com" || host.endsWith(".biliintl.com") ||
            host == "bilibili.tv" || host.endsWith(".bilibili.tv")
        return isBiliApiHost && parsed.path.contains("/playurl", ignoreCase = true)
    }

    internal fun looksLikePlayViewObject(obj: Any): Boolean {
        val name = obj.javaClass.name
        return name.contains("PlayView", ignoreCase = true) || name.contains("PlayURL", ignoreCase = true)
    }

    internal fun shouldIntercept(config: AcceleratorConfig, result: Any?): Boolean {
        return config.enabled && config.blockPcdn && config.enableMossHook && result != null
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
