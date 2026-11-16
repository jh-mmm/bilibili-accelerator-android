package io.github.jh_mmm.biliaccelerator.hook

import android.net.Uri
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap

object PlayerHook {

    private const val TAG = "BiliAccelerator-Player"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val hookedBuilderClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedPlayerClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    private val builderPendingBackups = Collections.synchronizedMap(WeakHashMap<Any, List<String>>())
    private val builderCleanLiveHost = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val builderBtrUrl = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val liveDirCleanHosts = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 16
    }

    private fun recordCleanLiveHost(url: String, host: String) {
        if (host.isBlank()) return
        val dir = BiliAcceleratorCore.parseUri(url)?.path?.substringBeforeLast('/')?.takeIf { it.isNotBlank() } ?: return
        synchronized(liveDirCleanHosts) {
            liveDirCleanHosts[dir] = host
        }
    }

    private fun findCleanLiveHostForUrl(url: String, builderInstance: Any?): String? {
        builderInstance?.let { builderCleanLiveHost[it] }?.let { return it }
        val dir = BiliAcceleratorCore.parseUri(url)?.path?.substringBeforeLast('/')?.takeIf { it.isNotBlank() } ?: return null
        return synchronized(liveDirCleanHosts) { liveDirCleanHosts[dir] }
    }

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
        synchronized(hookedBuilderClasses) {
            if (!hookedBuilderClasses.add(clazz)) return
        }
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

                    val distinctFiltered = if (config.blockPcdn || config.proxyMcdn || config.forceUpos || (config.rewriteAkamai && config.enableBtr)) {
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
                                    !BiliAcceleratorCore.isSlowLiveHost(host, it, config.portHeuristic)
                        }
                        if (cleanLive != null) {
                            val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cleanLive)?.host)
                            if (host.isNotBlank()) {
                                builderCleanLiveHost[builder] = host
                                recordCleanLiveHost(cleanLive, host)
                            }
                        }
                    }

                    val boundSessionId = chain.thisObject?.let {
                        io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.getSessionForBuilder(it)
                    }
                    val activeBtrProxy = config.enableBtr && io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.isStarted
                    if (activeBtrProxy) {
                        if (boundSessionId != null) {
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.updateBackups(boundSessionId, distinctFiltered)
                        } else if (chain.thisObject == null) {
                            // 仅在无 Builder 实例关联时才尝试按精确路径/文件关键帧匹配现有会话；
                            // 若处于 Builder 构建前期（setBackupUrls 早于 setUrl），严禁串台写进旧会话
                            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.updateBackupsByCandidateUrls(distinctFiltered)
                        }
                    }

                    // 当该 Builder 已改写至 BTR 本地代理时，收敛原生 backupUrls 为本地代理地址，避免回退时直接绕过 BTR 访问外部节点
                    val nativeBackups = if (activeBtrProxy && boundSessionId != null) {
                        val session = io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.get(boundSessionId)
                        if (session != null && !session.isLive) {
                            val btrUrl = chain.thisObject?.let { builderBtrUrl[it] }
                            if (!btrUrl.isNullOrBlank()) listOf(btrUrl) else emptyList()
                        } else {
                            distinctFiltered
                        }
                    } else {
                        distinctFiltered
                    }

                    if (nativeBackups.isNotEmpty() || (activeBtrProxy && boundSessionId != null)) {
                        val paramType = method.parameterTypes[0]
                        val convertedArg: Any = when {
                            paramType.isArray -> nativeBackups.toTypedArray()
                            java.util.Set::class.java.isAssignableFrom(paramType) -> nativeBackups.toSet()
                            else -> nativeBackups
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

    private data class BuilderHints(
        val hasDrm: Boolean = false,
        val isPreview: Boolean = false,
        val isEpisode: Boolean = false
    )

    private fun inspectBuilderHints(target: Any?): BuilderHints {
        if (target == null) return BuilderHints()
        var hasDrm = false
        var isPreview = false
        var isEpisode = false
        try {
            var cls: Class<*>? = target.javaClass
            var depth = 0
            while (cls != null && cls != Any::class.java && depth < 3) {
                for (f in cls.declaredFields) {
                    val name = f.name.lowercase()
                    if (!name.contains("drm") && !name.contains("preview") &&
                        !name.contains("epid") && !name.contains("seasonid") &&
                        !name.contains("ep_id") && !name.contains("season_id")
                    ) continue
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(target)
                        when {
                            name.contains("drm") -> {
                                val active = when (v) {
                                    is Boolean -> v
                                    is Number -> v.toInt() > 0
                                    is String -> v.isNotBlank() && v != "0" && !v.equals("false", true)
                                    else -> v != null
                                }
                                if (active) hasDrm = true
                            }
                            name.contains("preview") -> {
                                val active = when (v) {
                                    is Boolean -> v
                                    is Number -> v.toInt() > 0
                                    is String -> v == "1" || v.equals("true", true)
                                    else -> false
                                }
                                if (active) isPreview = true
                            }
                            name.contains("epid") || name.contains("ep_id") || name.contains("season") -> {
                                val active = when (v) {
                                    is Number -> v.toLong() > 0L
                                    is String -> v.isNotBlank() && v != "0"
                                    else -> false
                                }
                                if (active) isEpisode = true
                            }
                        }
                    }
                }
                cls = cls.superclass
                depth++
            }
        } catch (_: Throwable) {}
        return BuilderHints(hasDrm = hasDrm, isPreview = isPreview, isEpisode = isEpisode)
    }

    private fun shouldReportResult(result: RewriteResult): Boolean {
        return result.reason != "ignored" &&
                result.reason != "parse-failed" &&
                result.reason != "already-local-proxy"
    }

    private fun tryRewriteToBtr(
        originalUrl: String,
        result: RewriteResult,
        config: AcceleratorConfig,
        builderInstance: Any? = null,
        requestHeaders: Map<*, *>? = null
    ): String? {
        if (!config.enabled || !config.enableBtr) {
            return null
        }
        // 防重入：严禁对已重定向至本地代理回环的 URL 重复改写
        if (originalUrl.contains("127.0.0.1:") || originalUrl.contains("localhost:") ||
            (result.changed && (result.finalUrl.contains("127.0.0.1:") || result.finalUrl.contains("localhost:")))) {
            return null
        }
        val isLive = result.reason.startsWith("live-") ||
                BiliAcceleratorCore.isLiveMediaUrl("", originalUrl) ||
                (result.changed && BiliAcceleratorCore.isLiveMediaUrl("", result.finalUrl))

        // 直播流多源并发加速控制：未开启时保持原生绕过
        if (isLive && !config.enableLiveAcceleration) {
            return null
        }

        val hints = inspectBuilderHints(builderInstance)
        val isEpisode = hints.isEpisode ||
                BiliAcceleratorCore.isEpisodeMediaUrl(originalUrl) ||
                (result.changed && BiliAcceleratorCore.isEpisodeMediaUrl(result.finalUrl))

        // 剧集专属模式控制：未开启时严格遵循 BTR 原版调度放行至原生播放器
        if (isEpisode) {
            if (!config.enableEpisodeAcceleration) {
                Log.i(TAG, "Episode acceleration disabled in config, leaving to native player")
                return null
            }
            // 剧集试看流（is_preview=1 / Builder 试看属性）：对齐 BTR 原版 unsupportedEpisode，试看流严禁代理介入
            if (hints.isPreview || BiliAcceleratorCore.isEpisodePreview(originalUrl) || (result.changed && BiliAcceleratorCore.isEpisodePreview(result.finalUrl))) {
                Log.i(TAG, "Episode preview detected, leaving to native player")
                return null
            }
        }

        // 版权保护/DRM 加密流（Widevine/ChinaDRM/PSSH/is_drm/Builder DRM 属性）绝对不重写至 BTR 本地代理，交由原生直连放行
        if (hints.hasDrm || BiliAcceleratorCore.hasDrmSignal(originalUrl) || (result.changed && BiliAcceleratorCore.hasDrmSignal(result.finalUrl))) {
            Log.i(TAG, "DRM protected stream detected, bypassing BTR proxy")
            return null
        }

        if (!RemoteClient.ensureBtrProxyStarted(config)) {
            return null
        }

        var targetUrl = if (result.changed) result.finalUrl else originalUrl
        val pendingBackups = builderInstance?.let { builderPendingBackups[it] }.orEmpty().toMutableList()

        // 对齐 BTR / realzza：BTR 模式下将 Akamai 重写至 UPOS 节点时，必须保留原签名 Akamai 地址作为 403 兜底备用源
        if (!isLive && result.changed && (result.reason == "akamai-redirect" || BiliAcceleratorCore.isAkamaiHost(result.originalHost))) {
            if (!pendingBackups.contains(originalUrl)) {
                pendingBackups.add(originalUrl)
            }
        }

        if (!isLive && (result.isPcdn || result.isMcdn)) {
            val cleanOfficialBackup = pendingBackups.firstOrNull { backup ->
                val bHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(backup)?.host).lowercase()
                bHost.isNotBlank() && BiliAcceleratorCore.isBiliCdnHost(bHost) &&
                    !BiliAcceleratorCore.isMcdnHost(bHost) && !BiliAcceleratorCore.isKnownP2pHost(bHost) &&
                    !BiliAcceleratorCore.isIpAddress(bHost) && !BiliAcceleratorCore.isAkamaiHost(bHost) &&
                    !bHost.endsWith(".bstarstatic.com")
            }
            if (cleanOfficialBackup != null) {
                targetUrl = cleanOfficialBackup
                Log.i(TAG, "Promoted official backup mirror to primary: $cleanOfficialBackup")
            }
        }

        if (isLive) {
            val cleanHost = findCleanLiveHostForUrl(targetUrl, builderInstance)
            if (!cleanHost.isNullOrBlank()) {
                val parsed = BiliAcceleratorCore.parseUri(targetUrl)
                if (parsed != null && !BiliAcceleratorCore.isSlowLiveHost(cleanHost, portHeuristic = config.portHeuristic)) {
                    val candidate = parsed.replaceAuthority(cleanHost)
                    if (!pendingBackups.contains(candidate)) {
                        pendingBackups.add(candidate)
                    }
                }
            }
        }

        val headerUa = requestHeaders?.entries?.firstOrNull { it.key?.toString()?.equals("user-agent", ignoreCase = true) == true }?.value?.toString()
        val headerRef = requestHeaders?.entries?.firstOrNull { it.key?.toString()?.equals("referer", ignoreCase = true) == true }?.value?.toString()
        val isAudio = !isLive && BiliAcceleratorCore.isAudioTrack(targetUrl)

        val proxyPort = io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port
        val ext = when {
            targetUrl.contains(".m3u8") -> "m3u8"
            targetUrl.contains(".flv") -> "flv"
            else -> "m4s"
        }

        // 若同一 Builder 连续调用构造器 / setUrl / setResolveUrl，优先复用或替换旧 Session，杜绝孤立会话膨胀
        if (builderInstance != null) {
            val existingId = io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.getSessionForBuilder(builderInstance)
            val existingSession = existingId?.let { io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.get(it) }
            if (existingSession != null && existingSession.primaryUrl == targetUrl && existingSession.isLive == isLive) {
                if (pendingBackups.isNotEmpty()) {
                    io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.updateBackups(existingSession.sessionId, pendingBackups)
                }
                if (!headerUa.isNullOrBlank()) existingSession.userAgent = headerUa
                if (!headerRef.isNullOrBlank()) existingSession.referer = headerRef
                val reusedUrl = "http://127.0.0.1:$proxyPort/stream/${existingSession.sessionId}.$ext"
                builderBtrUrl[builderInstance] = reusedUrl
                return reusedUrl
            }
            if (existingId != null) {
                io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.remove(existingId)
            }
        }

        if (!isLive) {
            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.refreshSignatureIfSamePath(targetUrl, pendingBackups)
        }

        val sessionId = io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.register(
            primaryUrl = targetUrl,
            backupUrls = pendingBackups,
            userAgent = headerUa,
            isAudio = isAudio,
            isEpisode = isEpisode,
            isLive = isLive,
            config = config
        )
        if (!headerRef.isNullOrBlank()) {
            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.get(sessionId)?.referer = headerRef
        }
        val btrUrl = "http://127.0.0.1:$proxyPort/stream/$sessionId.$ext"
        if (builderInstance != null) {
            io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager.bindBuilderSession(builderInstance, sessionId)
            builderBtrUrl[builderInstance] = btrUrl
        }
        return btrUrl
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
            if (config.enableLiveAcceleration) {
                val btrUrl = tryRewriteToBtr(originalUrl, result, config, builderInstance)
                if (btrUrl != null) {
                    if (result.changed) {
                        RemoteClient.notifyRewrite(result)
                    }
                    Log.i(TAG, "Rewrote live stream to BTR proxy: ${result.originalHost} -> 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
                    val newArgs = args.toTypedArray()
                    newArgs[strIndex] = btrUrl
                    return newArgs
                }
            }
            if (result.changed) {
                RemoteClient.notifyRewrite(result)
                val newArgs = args.toTypedArray()
                newArgs[strIndex] = result.finalUrl
                return newArgs
            }
            if (result.isPcdn && config.blockPcdn) {
                val cleanLiveHost = findCleanLiveHostForUrl(originalUrl, builderInstance)
                if (!cleanLiveHost.isNullOrBlank() && !BiliAcceleratorCore.isSlowLiveHost(cleanLiveHost, portHeuristic = config.portHeuristic)) {
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
            if (shouldReportResult(result)) RemoteClient.notifyRewrite(result)
            return null
        }

        val btrUrl = tryRewriteToBtr(originalUrl, result, config, builderInstance)
        if (btrUrl != null) {
            if (result.changed) {
                RemoteClient.notifyRewrite(result)
            }
            Log.i(TAG, "Rewrote media url to BTR proxy: ${result.originalHost} -> 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = btrUrl
            return newArgs
        }

        if (shouldReportResult(result)) RemoteClient.notifyRewrite(result)
        if (result.changed) {
            val pendingBackups = builderInstance?.let { builderPendingBackups[it] }.orEmpty()
            val cleanOfficialBackup = if (!isLive && (result.isPcdn || result.isMcdn)) {
                pendingBackups.firstOrNull { backup ->
                    val bHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(backup)?.host).lowercase()
                    bHost.isNotBlank() && BiliAcceleratorCore.isBiliCdnHost(bHost) &&
                        !BiliAcceleratorCore.isMcdnHost(bHost) && !BiliAcceleratorCore.isKnownP2pHost(bHost) &&
                        !BiliAcceleratorCore.isIpAddress(bHost) && !BiliAcceleratorCore.isAkamaiHost(bHost)
                }
            } else null

            val finalUrl = cleanOfficialBackup ?: result.finalUrl
            Log.i(TAG, "Rewrote media url: ${result.originalHost} -> ${BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(finalUrl)?.host)} [${result.reason}]")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = finalUrl
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

        synchronized(hookedPlayerClasses) {
            if (!hookedPlayerClasses.add(ijkPlayerClass)) return true
        }

        var fallbackHooked = false

        try {
            // 1. Hook setDataSource(String path) 及 setDataSource(String path, Map<String, String> headers) 等所有首参为 String 的重载
            val stringMethods = (ijkPlayerClass.declaredMethods + ijkPlayerClass.methods).distinct().filter {
                it.name == "setDataSource" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java
            }
            for (m in stringMethods) {
                try {
                    module.hook(m).intercept { chain ->
                        val original = chain.args.getOrNull(0) as? String
                        if (original != null && BiliAcceleratorCore.hasMediaSignal(original)) {
                            val config = RemoteClient.fetchConfig()
                            if (config.enabled) {
                                val headersMap = chain.args.getOrNull(1) as? Map<*, *>
                                val result = BiliAcceleratorCore.rewriteUrl(original, config)
                                val btrUrl = tryRewriteToBtr(original, result, config, chain.thisObject, headersMap)
                                if (btrUrl != null) {
                                    if (result.changed) {
                                        RemoteClient.notifyRewrite(result)
                                    }
                                    Log.i(TAG, "Rewrote IjkMediaPlayer dataSource to BTR: 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
                                    val newArgs = chain.args.toTypedArray().apply { this[0] = btrUrl }
                                    return@intercept chain.proceed(newArgs)
                                }
                                if (shouldReportResult(result)) RemoteClient.notifyRewrite(result)
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
                } catch (_: Throwable) {}
            }

            // 2. Hook setDataSource(Context context, Uri uri) 及 setDataSource(Context, Uri, Map) 等重载
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
                                    val headersMap = chain.args.getOrNull(2) as? Map<*, *>
                                    val result = BiliAcceleratorCore.rewriteUrl(original, config)
                                    val btrUrl = tryRewriteToBtr(original, result, config, chain.thisObject, headersMap)
                                    if (btrUrl != null) {
                                        if (result.changed) {
                                            RemoteClient.notifyRewrite(result)
                                        }
                                        Log.i(TAG, "Rewrote IjkMediaPlayer Uri dataSource to BTR: 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
                                        val newArgs = chain.args.toTypedArray().apply { this[1] = Uri.parse(btrUrl) }
                                        return@intercept chain.proceed(newArgs)
                                    }
                                    if (shouldReportResult(result)) RemoteClient.notifyRewrite(result)
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
