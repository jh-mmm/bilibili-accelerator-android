package io.github.jh_mmm.biliaccelerator.hook

import android.net.Uri
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

object PlayerHook {

    private const val TAG = "BiliAccelerator-Player"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val builderPendingBackups = Collections.synchronizedMap(WeakHashMap<Any, List<String>>())
    private val builderCleanLiveHost = Collections.synchronizedMap(WeakHashMap<Any, String>())
    @Volatile
    private var latestCleanLiveHost: String? = null

    // Both spellings are observed across different Bilibili versions (Assert vs Asset)
    private val SEGMENT_BUILDER_CLASSES = listOf(
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssetSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaSegment\$Builder"
    )

    fun init(module: XposedModule, classLoader: ClassLoader) {
        var hooked = false
        for (className in SEGMENT_BUILDER_CLASSES) {
            val builderClass = try {
                classLoader.loadClass(className)
            } catch (_: Throwable) {
                null
            } ?: continue

            hookSegmentBuilder(module, builderClass)
            hooked = true
            HookStatusTracker.recordStatus("player_segment", className)
            Log.i(TAG, "Successfully hooked player segment builder: $className")
        }

        // Fallback: Hook IjkMediaPlayer directly if segment builder is missing or bypassed
        val fallbackHooked = hookFallbackPlayer(module, classLoader)
        if (fallbackHooked) {
            hooked = true
        }

        if (hooked) {
            isHooked = true
        } else {
            Log.i(TAG, "Standard IjkMediaAsset builder not found, relying on player fallback hooks")
        }
    }

    private fun hookSegmentBuilder(module: XposedModule, clazz: Class<*>) {
        // 1. Hook all constructors (distinct 防止 public 构造器重复注入)
        val constructors = (clazz.declaredConstructors + clazz.constructors).distinct()
        for (constructor in constructors) {
            try {
                module.hook(constructor).intercept { chain ->
                    val newArgs = rewriteArgIfMedia(chain.args, chain.thisObject)
                    if (newArgs != null) {
                        chain.proceed(newArgs)
                    } else {
                        chain.proceed()
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Could not hook constructor: ${t.message}")
            }
        }

        // 2. Hook setter methods: setUrl, setBaseUrl, setResolveUrl
        val setterNames = setOf("setUrl", "setBaseUrl", "setResolveUrl")
        val methods = (clazz.declaredMethods + clazz.methods).distinct()
        for (m in methods) {
            if (m.name in setterNames) {
                try {
                    module.hook(m).intercept { chain ->
                        val newArgs = rewriteArgIfMedia(chain.args, chain.thisObject)
                        if (newArgs != null) {
                            chain.proceed(newArgs)
                        } else {
                            chain.proceed()
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // 3. Hook setBackupUrls(Collection/List or Array)
        val backupMethods = (clazz.declaredMethods + clazz.methods).distinct().filter {
            it.name == "setBackupUrls" && it.parameterTypes.size == 1
        }

        for (method in backupMethods) {
            try {
                module.hook(method).intercept { chain ->
                    val arg = chain.args.firstOrNull() ?: return@intercept chain.proceed()
                    val rawList: List<String> = when (arg) {
                        is Collection<*> -> arg.filterIsInstance<String>()
                        is Array<*> -> arg.filterIsInstance<String>()
                        else -> return@intercept chain.proceed()
                    }
                    if (rawList.isEmpty()) return@intercept chain.proceed()

                    val config = RemoteClient.fetchConfig()
                    if (!config.enabled) {
                        return@intercept chain.proceed()
                    }

                    val distinctFiltered = if (config.blockPcdn || config.proxyMcdn || config.forceUpos) {
                        filterAndFillBackupUrls(rawList, config) { res ->
                            RemoteClient.notifyRewrite(res)
                        }
                    } else {
                        rawList.distinct()
                    }

                    chain.thisObject?.let { builder ->
                        builderPendingBackups[builder] = distinctFiltered
                        val cleanLive = distinctFiltered.firstOrNull {
                            val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(it)?.host)
                            BiliAcceleratorCore.isLiveMediaUrl("", it) &&
                                    !BiliAcceleratorCore.isKnownP2pHost(host) &&
                                    !BiliAcceleratorCore.isIpAddress(host)
                        }
                        if (cleanLive != null) {
                            val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cleanLive)?.host)
                            if (host.isNotBlank()) {
                                builderCleanLiveHost[builder] = host
                                latestCleanLiveHost = host
                            }
                        }
                    }

                    if (config.enableBtr && io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted) {
                        val boundSessionId = chain.thisObject?.let {
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.getSessionForBuilder(it)
                        }
                        if (boundSessionId != null) {
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.updateBackups(boundSessionId, distinctFiltered)
                        } else {
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.updateBackupsByCandidateUrls(distinctFiltered)
                        }
                    }

                    if (distinctFiltered.isNotEmpty()) {
                        val paramType = method.parameterTypes[0]
                        val convertedArg: Any = when {
                            paramType.isArray -> distinctFiltered.toTypedArray()
                            java.util.Set::class.java.isAssignableFrom(paramType) -> distinctFiltered.toSet()
                            else -> distinctFiltered
                        }
                        val newArgs = chain.args.toTypedArray().apply { this[0] = convertedArg }
                        return@intercept chain.proceed(newArgs)
                    }
                    chain.proceed()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook ${method.name}: ${t.message}")
            }
        }
    }

    fun filterAndFillBackupUrls(
        rawList: List<String>,
        config: AcceleratorConfig,
        onRewrite: ((RewriteResult) -> Unit)? = null
    ): List<String> {
        return BiliAcceleratorCore.filterAndFillBackupUrls(rawList, config, onRewrite)
    }

    private fun tryRewriteToBtr(
        originalUrl: String,
        result: RewriteResult,
        config: AcceleratorConfig,
        builderInstance: Any? = null
    ): String? {
        if (!config.enabled || !config.enableBtr || !io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted) {
            return null
        }
        // 直播流（FLV/HLS/Live）绝对不重写至 BTR 本地代理
        if (result.reason.startsWith("live-") || BiliAcceleratorCore.isLiveMediaUrl("", originalUrl)) {
            return null
        }
        // 版权保护/DRM 加密流（Widevine/ChinaDRM/PSSH）绝对不重写至 BTR 本地代理，交由原生直连放行
        if (BiliAcceleratorCore.hasDrmSignal(originalUrl) || (result.changed && BiliAcceleratorCore.hasDrmSignal(result.finalUrl))) {
            Log.i(TAG, "DRM protected stream detected, bypassing BTR proxy")
            return null
        }
        val targetUrl = if (result.changed) result.finalUrl else originalUrl
        val pendingBackups = builderInstance?.let { builderPendingBackups[it] }.orEmpty()
        val isAudio = BiliAcceleratorCore.isAudioTrack(targetUrl)

        val sessionId = io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.register(
            primaryUrl = targetUrl,
            backupUrls = pendingBackups,
            isAudio = isAudio,
            config = config
        )
        if (builderInstance != null) {
            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.bindBuilderSession(builderInstance, sessionId)
        }
        val proxyPort = io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port
        return "http://127.0.0.1:$proxyPort/stream/$sessionId.m4s"
    }

    private fun rewriteArgIfMedia(args: List<Any?>, builderInstance: Any? = null): Array<Any?>? {
        val strIndex = args.indexOfFirst { it is String && BiliAcceleratorCore.hasMediaSignal(it) }
        if (strIndex == -1) return null

        val originalUrl = args[strIndex] as String
        val config = RemoteClient.fetchConfig()
        if (!config.enabled) return null

        val parsed = BiliAcceleratorCore.parseUri(originalUrl)
        val isLive = parsed != null && BiliAcceleratorCore.isLiveMediaUrl(parsed.path, originalUrl)
        val result = BiliAcceleratorCore.rewriteUrl(originalUrl, config)

        if (isLive) {
            if (result.changed) {
                RemoteClient.notifyRewrite(result)
                val newArgs = args.toTypedArray()
                newArgs[strIndex] = result.finalUrl
                return newArgs
            }
            if (result.isPcdn && config.blockPcdn && parsed != null) {
                val cleanLiveHost = builderInstance?.let { builderCleanLiveHost[it] } ?: latestCleanLiveHost
                if (!cleanLiveHost.isNullOrBlank() && !BiliAcceleratorCore.isKnownP2pHost(cleanLiveHost) && !BiliAcceleratorCore.isIpAddress(cleanLiveHost)) {
                    val rewritten = parsed.replaceAuthority(cleanLiveHost)
                    val liveResult = RewriteResult(
                        changed = true,
                        originalUrl = originalUrl,
                        finalUrl = rewritten,
                        originalHost = parsed.host,
                        targetHost = cleanLiveHost,
                        reason = "live-pcdn-filter",
                        isPcdn = true,
                        isMcdn = result.isMcdn,
                        segmentKey = result.segmentKey
                    )
                    RemoteClient.notifyRewrite(liveResult)
                    val newArgs = args.toTypedArray()
                    newArgs[strIndex] = rewritten
                    return newArgs
                }
            }
            RemoteClient.notifyRewrite(result)
            return null
        }

        val btrUrl = tryRewriteToBtr(originalUrl, result, config, builderInstance)
        if (btrUrl != null) {
            Log.i(TAG, "Rewrote media url to BTR proxy: ${result.originalHost} -> 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = btrUrl
            return newArgs
        }

        RemoteClient.notifyRewrite(result)
        if (result.changed) {
            Log.i(TAG, "Rewrote media url: ${result.originalHost} -> ${result.targetHost} [${result.reason}]")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = result.finalUrl
            return newArgs
        }
        return null
    }

    private fun hookFallbackPlayer(module: XposedModule, classLoader: ClassLoader): Boolean {
        val ijkPlayerClass = try {
            classLoader.loadClass("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        } catch (_: Throwable) {
            null
        } ?: return false

        var fallbackHooked = false

        try {
            // 1. Hook setDataSource(String path)
            try {
                val setDataSourceMethod = ijkPlayerClass.getDeclaredMethod("setDataSource", String::class.java)
                module.hook(setDataSourceMethod).intercept { chain ->
                    val original = chain.args.getOrNull(0) as? String
                    if (original != null && BiliAcceleratorCore.hasMediaSignal(original)) {
                        val config = RemoteClient.fetchConfig()
                        if (config.enabled) {
                            val result = BiliAcceleratorCore.rewriteUrl(original, config)
                            val btrUrl = tryRewriteToBtr(original, result, config, chain.thisObject)
                            if (btrUrl != null) {
                                Log.i(TAG, "Rewrote IjkMediaPlayer dataSource to BTR: 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
                                val newArgs = chain.args.toTypedArray().apply { this[0] = btrUrl }
                                return@intercept chain.proceed(newArgs)
                            }
                            RemoteClient.notifyRewrite(result)
                            if (result.changed) {
                                Log.i(TAG, "Rewrote IjkMediaPlayer dataSource: ${result.originalHost} -> ${result.targetHost}")
                                val newArgs = chain.args.toTypedArray().apply { this[0] = result.finalUrl }
                                return@intercept chain.proceed(newArgs)
                            }
                        }
                    }
                    chain.proceed()
                }
                fallbackHooked = true
            } catch (_: NoSuchMethodException) {}

            // 2. Hook setDataSource(Context context, Uri uri) 及重载
            val uriMethods = (ijkPlayerClass.declaredMethods + ijkPlayerClass.methods).distinct().filter {
                it.name == "setDataSource" && it.parameterTypes.size >= 2 && it.parameterTypes[1] == Uri::class.java
            }
            for (m in uriMethods) {
                try {
                    module.hook(m).intercept { chain ->
                        val originalUri = chain.args.getOrNull(1) as? Uri
                        if (originalUri != null) {
                            val original = originalUri.toString()
                            if (BiliAcceleratorCore.hasMediaSignal(original)) {
                                val config = RemoteClient.fetchConfig()
                                if (config.enabled) {
                                    val result = BiliAcceleratorCore.rewriteUrl(original, config)
                                    val btrUrl = tryRewriteToBtr(original, result, config, chain.thisObject)
                                    if (btrUrl != null) {
                                        Log.i(TAG, "Rewrote IjkMediaPlayer Uri dataSource to BTR: 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
                                        val newArgs = chain.args.toTypedArray().apply { this[1] = Uri.parse(btrUrl) }
                                        return@intercept chain.proceed(newArgs)
                                    }
                                    RemoteClient.notifyRewrite(result)
                                    if (result.changed) {
                                        Log.i(TAG, "Rewrote IjkMediaPlayer Uri dataSource: ${result.originalHost} -> ${result.targetHost}")
                                        val newArgs = chain.args.toTypedArray().apply { this[1] = Uri.parse(result.finalUrl) }
                                        return@intercept chain.proceed(newArgs)
                                    }
                                }
                            }
                        }
                        chain.proceed()
                    }
                    fallbackHooked = true
                } catch (_: Throwable) {}
            }

            if (fallbackHooked) {
                HookStatusTracker.recordStatus("player_fallback", "tv.danmaku.ijk.media.player.IjkMediaPlayer")
                Log.i(TAG, "Successfully hooked IjkMediaPlayer setDataSource methods")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hookFallbackPlayer failed: ${t.message}")
        }
        return fallbackHooked
    }
}
