package io.github.jh_mmm.biliaccelerator.hook

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRefreshCallback
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
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

object MossGrpcHook {

    private const val TAG = "BiliAccelerator-Moss"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val hookedClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedMethods = Collections.newSetFromMap(WeakHashMap<Method, Boolean>())
    private val hookedConstructors = Collections.newSetFromMap(WeakHashMap<Constructor<*>, Boolean>())
    private val inPlayinfoInspect = ThreadLocal.withInitial { false }
    private val inGrpcRefresh = ThreadLocal.withInitial { false }
    private val inHttpRefresh = ThreadLocal.withInitial { false }

    private data class CapturedHttpPlayurlCall(
        val client: Any,
        val request: Any,
        val url: String,
        val capturedAtMs: Long
    )

    private data class CapturedGrpcCallMeta(
        val channel: Any,
        val methodDesc: Any,
        val callOptions: Any
    )

    private const val MAX_RECENT_HTTP_CALLS = 16
    private const val HTTP_CALL_TTL_MS = 10_000L

    private val recentCallsLock = Any()
    private val recentCapturedCalls = ArrayDeque<CapturedHttpPlayurlCall>()
    private val requestToCapturedCall = Collections.synchronizedMap(WeakHashMap<Any, CapturedHttpPlayurlCall>())
    private val bodyToCapturedCall = Collections.synchronizedMap(WeakHashMap<Any, CapturedHttpPlayurlCall>())
    private val clientCallToMeta = Collections.synchronizedMap(WeakHashMap<Any, CapturedGrpcCallMeta>())

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
        synchronized(recentCallsLock) {
            recentCapturedCalls.clear()
        }
        requestToCapturedCall.clear()
        bodyToCapturedCall.clear()
        clientCallToMeta.clear()
    }

    internal fun hasCapturedHttpPlayurlCallForTesting(): Boolean {
        return synchronized(recentCallsLock) { recentCapturedCalls.isNotEmpty() } ||
            requestToCapturedCall.isNotEmpty() ||
            bodyToCapturedCall.isNotEmpty()
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

    private fun pruneExpiredHttpCallsLocked(nowMs: Long) {
        while (recentCapturedCalls.isNotEmpty()) {
            val oldest = recentCapturedCalls.first()
            if (nowMs - oldest.capturedAtMs > HTTP_CALL_TTL_MS || nowMs < oldest.capturedAtMs) {
                recentCapturedCalls.removeFirst()
                requestToCapturedCall.remove(oldest.request)
            } else {
                break
            }
        }
        while (recentCapturedCalls.size > MAX_RECENT_HTTP_CALLS) {
            val evicted = recentCapturedCalls.removeFirst()
            requestToCapturedCall.remove(evicted.request)
        }
    }

    private fun consumeMatchingHttpCaller(
        bodyObj: Any?,
        nowMs: Long
    ): CapturedHttpPlayurlCall? {
        if (bodyObj != null) {
            val bound = bodyToCapturedCall.remove(bodyObj)
            if (bound != null) {
                synchronized(recentCallsLock) {
                    recentCapturedCalls.remove(bound)
                    pruneExpiredHttpCallsLocked(nowMs)
                }
                requestToCapturedCall.remove(bound.request)
                val ageMs = nowMs - bound.capturedAtMs
                if (ageMs in 0L..HTTP_CALL_TTL_MS) {
                    return bound
                }
            }
        }
        return synchronized(recentCallsLock) {
            pruneExpiredHttpCallsLocked(nowMs)
            val recent = if (recentCapturedCalls.isNotEmpty()) recentCapturedCalls.removeLast() else null
            if (recent != null) {
                requestToCapturedCall.remove(recent.request)
            }
            recent
        }
    }

    internal fun bindResponseBodyPlayurlCall(
        responseObj: Any?,
        bodyObj: Any?,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!RemoteClient.getCachedConfig().enabled || responseObj == null || bodyObj == null) return false
        val respReq = runCatching { invokeZeroArgAccessible(responseObj, "request") }.getOrNull()
        val matched = if (respReq != null) {
            requestToCapturedCall.remove(respReq)?.also { found ->
                synchronized(recentCallsLock) {
                    recentCapturedCalls.remove(found)
                }
            } ?: run {
                val respUrl = runCatching { invokeZeroArgAccessible(respReq, "url")?.toString() }.getOrNull()
                if (respUrl != null) {
                    synchronized(recentCallsLock) {
                        pruneExpiredHttpCallsLocked(nowMs)
                        val idx = recentCapturedCalls.indexOfLast { it.url == respUrl }
                        if (idx >= 0) {
                            recentCapturedCalls.removeAt(idx).also { requestToCapturedCall.remove(it.request) }
                        } else null
                    }
                } else null
            }
        } else null
        if (matched != null && (nowMs - matched.capturedAtMs) in 0L..HTTP_CALL_TTL_MS) {
            bodyToCapturedCall[bodyObj] = matched
            return true
        }
        return false
    }

    internal fun inspectHttpPlayinfoJsonIfEnabled(
        text: String?,
        allowRefreshCallback: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        bodyObj: Any? = null
    ): Boolean {
        if (!RemoteClient.getCachedConfig().enabled) return false
        if (text == null || inPlayinfoInspect.get() == true || (allowRefreshCallback && inHttpRefresh.get() == true)) {
            return false
        }
        if (!fastLooksLikePlayinfoJson(text)) return false
        inPlayinfoInspect.set(true)
        return try {
            val recentCaller = if (allowRefreshCallback) consumeMatchingHttpCaller(bodyObj, nowMs) else null
            val recentRefreshCb = recentCaller?.let { buildHttpRefreshCallback(it) }
            PlayinfoRegistry.ingestJson(
                text,
                sourceUrl = recentCaller?.url.orEmpty(),
                refreshCallback = recentRefreshCb
            ).isNotEmpty()
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
            val captured = CapturedHttpPlayurlCall(client, req, urlStr, nowMs)
            requestToCapturedCall[req] = captured
            synchronized(recentCallsLock) {
                pruneExpiredHttpCallsLocked(nowMs)
                recentCapturedCalls.addLast(captured)
                pruneExpiredHttpCallsLocked(nowMs)
            }
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
        executor: Executor = Executor { runnable -> runnable.run() },
        refreshCallback: PlayinfoRefreshCallback? = null
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
                        inspectPlayViewReplyIfEnabled(
                            doneVal,
                            sourceTag = "grpc-future",
                            refreshCallback = refreshCallback
                        )
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
        classLoader: ClassLoader = streamObserverClass.classLoader ?: MossGrpcHook::class.java.classLoader,
        refreshCallback: PlayinfoRefreshCallback? = null
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
                        inspectPlayViewReplyIfEnabled(
                            value,
                            sourceTag = "grpc-async",
                            refreshCallback = refreshCallback
                        )
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

    private val KNOWN_GRPC_CHANNEL_CLASSES = listOf(
        "io.grpc.internal.ManagedChannelImpl",
        "io.grpc.internal.ManagedChannelImpl\$RealChannel",
        "io.grpc.ClientInterceptors\$InterceptorChannel",
        "io.grpc.internal.ManagedChannelOrphanWrapper",
        "io.grpc.internal.ForwardingManagedChannel"
    )

    internal fun isPlayViewMethodDescriptor(methodDesc: Any?): Boolean {
        if (methodDesc == null) return false
        val fullMethodName = runCatching {
            invokeZeroArgAccessible(methodDesc, "getFullMethodName")?.toString()
        }.getOrNull().orEmpty()
        return fullMethodName.contains("PlayView", ignoreCase = true) ||
            fullMethodName.contains("PlayURL", ignoreCase = true)
    }

    internal fun recordClientCallMetaForTesting(
        clientCall: Any,
        channel: Any,
        methodDesc: Any,
        callOptions: Any
    ) {
        if (isPlayViewMethodDescriptor(methodDesc)) {
            clientCallToMeta[clientCall] = CapturedGrpcCallMeta(channel, methodDesc, callOptions)
        }
    }

    internal fun resolveGrpcRefreshCallbackFromArgs(
        blockingMethod: Method?,
        args: List<Any?>
    ): PlayinfoRefreshCallback? {
        if (blockingMethod == null) return null
        if (args.size >= 4) {
            return buildGrpcRefreshCallback(blockingMethod, args)
        }
        if (args.size >= 2) {
            val clientCall = args[0] ?: return null
            val reqMsg = args[1] ?: return null
            val meta = clientCallToMeta.remove(clientCall) ?: return null
            return buildGrpcRefreshCallback(
                blockingMethod,
                listOf(meta.channel, meta.methodDesc, meta.callOptions, reqMsg)
            )
        }
        return null
    }

    private fun hookChannelNewCall(module: XposedModule, channelClass: Class<*>?) {
        if (channelClass == null || Modifier.isAbstract(channelClass.modifiers)) return
        val newlyAdded = synchronized(hookedClasses) { hookedClasses.add(channelClass) }
        if (!newlyAdded) return
        runCatching {
            var cls: Class<*>? = channelClass
            while (cls != null && cls != Any::class.java) {
                for (m in cls.declaredMethods) {
                    if (m.name == "newCall" &&
                        !Modifier.isAbstract(m.modifiers) &&
                        m.parameterTypes.size == 2
                    ) {
                        val shouldHook = synchronized(hookedMethods) { hookedMethods.add(m) }
                        if (!shouldHook) continue
                        runCatching {
                            module.hook(m).intercept { chain ->
                                val clientCall = chain.proceed()
                                if (clientCall != null &&
                                    RemoteClient.getCachedConfig().enabled &&
                                    inGrpcRefresh.get() != true
                                ) {
                                    val channel = chain.thisObject
                                    val methodDesc = chain.args.getOrNull(0)
                                    val callOptions = chain.args.getOrNull(1)
                                    if (channel != null && methodDesc != null && callOptions != null &&
                                        isPlayViewMethodDescriptor(methodDesc)
                                    ) {
                                        clientCallToMeta[clientCall] = CapturedGrpcCallMeta(channel, methodDesc, callOptions)
                                    }
                                }
                                clientCall
                            }
                        }
                    }
                }
                cls = cls.superclass
            }
        }
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
            val channelInterfaceClass = runCatching { classLoader.loadClass("io.grpc.Channel") }.getOrNull()
            for (chName in KNOWN_GRPC_CHANNEL_CLASSES) {
                runCatching { classLoader.loadClass(chName) }.getOrNull()?.let { hookChannelNewCall(module, it) }
            }
            val abstractStubClass = runCatching { classLoader.loadClass("io.grpc.stub.AbstractStub") }.getOrNull()
            if (abstractStubClass != null && synchronized(hookedClasses) { hookedClasses.add(abstractStubClass) }) {
                for (ctor in abstractStubClass.declaredConstructors) {
                    if (synchronized(hookedConstructors) { hookedConstructors.add(ctor) }) {
                        runCatching {
                            module.hook(ctor).intercept { chain ->
                                val channelArg = chain.args.firstOrNull { arg ->
                                    arg != null && (channelInterfaceClass == null || channelInterfaceClass.isInstance(arg))
                                }
                                if (channelArg != null) {
                                    hookChannelNewCall(module, channelArg.javaClass)
                                }
                                chain.proceed()
                            }
                        }
                    }
                }
            }

            val methods = (clientCallsClass.declaredMethods + clientCallsClass.methods).distinct()
            val blockingUnaryMethod = methods.firstOrNull {
                it.name == "blockingUnaryCall" && it.parameterTypes.size == 4
            }
            val directExecutor = Executor { runnable -> runnable.run() }
            val streamObserverClass = runCatching { classLoader.loadClass("io.grpc.stub.StreamObserver") }.getOrNull()

            for (m in methods) {
                if (m.name == "blockingUnaryCall") {
                    if (!synchronized(hookedMethods) { hookedMethods.add(m) }) continue
                    module.hook(m).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        if (chain.args.size >= 4) {
                            chain.args.getOrNull(0)?.let { hookChannelNewCall(module, it.javaClass) }
                        }
                        val res = chain.proceed()
                        if (RemoteClient.getCachedConfig().enabled && res != null && inGrpcRefresh.get() != true && looksLikePlayViewObject(res)) {
                            val refreshCb = resolveGrpcRefreshCallbackFromArgs(blockingUnaryMethod ?: m, chain.args)
                            inspectPlayViewReplyIfEnabled(res, sourceTag = "grpc-blocking", refreshCallback = refreshCb)
                        }
                        res
                    }
                    anyHooked = true
                } else if (m.name == "futureUnaryCall") {
                    if (!synchronized(hookedMethods) { hookedMethods.add(m) }) continue
                    module.hook(m).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        val refreshCb = resolveGrpcRefreshCallbackFromArgs(blockingUnaryMethod, chain.args)
                        val futureObj = chain.proceed()
                        attachFuturePlayViewListener(futureObj, directExecutor, refreshCallback = refreshCb)
                        futureObj
                    }
                    anyHooked = true
                } else if (m.name == "asyncUnaryCall" && streamObserverClass != null && m.parameterTypes.lastOrNull() == streamObserverClass) {
                    if (!synchronized(hookedMethods) { hookedMethods.add(m) }) continue
                    module.hook(m).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        val args = chain.args
                        val observerIdx = args.lastIndex
                        val origObserver = args.getOrNull(observerIdx)
                        val refreshCb = resolveGrpcRefreshCallbackFromArgs(blockingUnaryMethod, args)
                        val proxyObserver = wrapAsyncStreamObserver(
                            streamObserverClass,
                            origObserver,
                            classLoader,
                            refreshCallback = refreshCb
                        )
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
        if (args.size < 4) return null
        val channel = args[0] ?: return null
        val methodDesc = args[1] ?: return null
        val rawCallOptions = args[2] ?: return null
        val reqMsg = args[3] ?: return null
        if (!isPlayViewMethodDescriptor(methodDesc)) {
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

        // 1. OkHttp ResponseBody.string() + Response.body() + OkHttpClient.newCall()
        val responseBodyClass = runCatching { classLoader.loadClass("okhttp3.ResponseBody") }.getOrNull()
        if (responseBodyClass != null && synchronized(hookedClasses) { hookedClasses.add(responseBodyClass) }) {
            runCatching {
                val strMethod = responseBodyClass.getDeclaredMethod("string")
                if (synchronized(hookedMethods) { hookedMethods.add(strMethod) }) {
                    module.hook(strMethod).intercept { chain ->
                        if (!RemoteClient.getCachedConfig().enabled) {
                            RemoteClient.refreshConfigAsyncIfStale()
                            return@intercept chain.proceed()
                        }
                        val text = chain.proceed() as? String
                        inspectHttpPlayinfoJsonIfEnabled(
                            text,
                            allowRefreshCallback = true,
                            bodyObj = chain.thisObject
                        )
                        text
                    }
                    anyHooked = true
                }
            }
        }

        val responseClass = runCatching { classLoader.loadClass("okhttp3.Response") }.getOrNull()
        if (responseClass != null && synchronized(hookedClasses) { hookedClasses.add(responseClass) }) {
            runCatching {
                val bodyMethod = responseClass.getDeclaredMethod("body")
                if (synchronized(hookedMethods) { hookedMethods.add(bodyMethod) }) {
                    module.hook(bodyMethod).intercept { chain ->
                        val bodyObj = chain.proceed()
                        if (bodyObj != null && RemoteClient.getCachedConfig().enabled) {
                            bindResponseBodyPlayurlCall(chain.thisObject, bodyObj)
                        }
                        bodyObj
                    }
                    anyHooked = true
                }
            }
        }

        val okClientClass = runCatching { classLoader.loadClass("okhttp3.OkHttpClient") }.getOrNull()
        val reqClass = runCatching { classLoader.loadClass("okhttp3.Request") }.getOrNull()
        if (okClientClass != null && reqClass != null && synchronized(hookedClasses) { hookedClasses.add(okClientClass) }) {
            runCatching {
                val newCallMethod = okClientClass.getDeclaredMethod("newCall", reqClass)
                if (synchronized(hookedMethods) { hookedMethods.add(newCallMethod) }) {
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
        return isBiliApiHost && (
            parsed.path.contains("/playurl", ignoreCase = true) ||
                parsed.path.contains("/playview", ignoreCase = true)
            )
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
