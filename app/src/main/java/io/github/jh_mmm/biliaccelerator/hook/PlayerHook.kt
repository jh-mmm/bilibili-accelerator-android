package io.github.jh_mmm.biliaccelerator.hook

import android.net.Uri
import android.util.Log
import io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import io.github.jh_mmm.biliaccelerator.core.PlayinfoTrackEntry
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.libxposed.api.XposedModule
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object PlayerHook {

    private const val TAG = "BiliAccelerator-Player"

    @Volatile
    var isHooked: Boolean = false
        internal set

    private val hookedBuilderClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedPlayerClasses = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedMethods = Collections.newSetFromMap(WeakHashMap<Method, Boolean>())
    private val hookedConstructors = Collections.newSetFromMap(WeakHashMap<Constructor<*>, Boolean>())

    /**
     * 基于对象引用恒等性 (===) 与 WeakReference 的线程安全映射，
     * 防止目标 Builder/Player 类重写 equals/hashCode 导致的状态丢失或错乱。
     */
    private class IdentityWeakMap<V : Any> {
        private class WeakIdentityKey(
            referent: Any,
            queue: ReferenceQueue<Any>? = null
        ) : WeakReference<Any>(referent, queue) {
            val identityHash: Int = System.identityHashCode(referent)
            override fun hashCode(): Int = identityHash
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is WeakIdentityKey) return false
                val a = get() ?: return false
                val b = other.get() ?: return false
                return a === b
            }
        }

        private val queue = ReferenceQueue<Any>()
        private val map = ConcurrentHashMap<WeakIdentityKey, V>()

        private fun expungeStale() {
            while (true) {
                val ref = queue.poll() ?: break
                @Suppress("UNCHECKED_CAST")
                map.remove(ref as WeakIdentityKey)
            }
        }

        operator fun get(key: Any?): V? {
            if (key == null) return null
            expungeStale()
            return map[WeakIdentityKey(key)]
        }

        operator fun set(key: Any, value: V): V? {
            expungeStale()
            return map.put(WeakIdentityKey(key, queue), value)
        }

        fun remove(key: Any?): V? {
            if (key == null) return null
            expungeStale()
            return map.remove(WeakIdentityKey(key))
        }

        fun containsKey(key: Any?): Boolean = get(key) != null
    }

    private val builderPendingBackups = IdentityWeakMap<List<String>>()
    private val builderRawBackups = IdentityWeakMap<List<String>>()
    private val builderDeferredBackupMethod = IdentityWeakMap<Method>()
    private val builderLastBackupMethod = IdentityWeakMap<Method>()
    private val builderNativeConvergedToBtr = IdentityWeakMap<Boolean>()
    private val builderResolvedNonBtr = IdentityWeakMap<Boolean>()
    private val builderCleanLiveUrls = IdentityWeakMap<List<String>>()
    private val builderBtrUrl = IdentityWeakMap<String>()
    private val suppressBackupHook = ThreadLocal.withInitial { false }
    private val livePathCleanUrls = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > 32
    }

    internal fun computeNativeBackupsForSession(
        btrUrl: String?,
        distinctFiltered: List<String>,
        btrConfigEnabled: Boolean
    ): List<String> {
        if (!btrConfigEnabled) return distinctFiltered
        return if (!btrUrl.isNullOrBlank()) listOf(btrUrl) else emptyList()
    }

    private fun convertBackupsForParamType(paramType: Class<*>, backups: List<String>): Any {
        return when {
            paramType.isArray -> backups.toTypedArray()
            java.util.SortedSet::class.java.isAssignableFrom(paramType) -> java.util.TreeSet(backups)
            java.util.Set::class.java.isAssignableFrom(paramType) -> LinkedHashSet(backups)
            java.util.LinkedList::class.java.isAssignableFrom(paramType) -> java.util.LinkedList(backups)
            else -> ArrayList(backups)
        }
    }

    private inline fun <T> withBackupHookSuppressed(block: () -> T): T {
        val prev = suppressBackupHook.get() ?: false
        suppressBackupHook.set(true)
        return try {
            block()
        } finally {
            suppressBackupHook.set(prev)
        }
    }

    internal fun applyBuilderBackupUrls(
        builder: Any,
        backups: List<String>,
        preferredMethod: Method? = null
    ): Boolean {
        val targetMethod = preferredMethod ?: (builder.javaClass.declaredMethods + builder.javaClass.methods)
            .firstOrNull { it.name == "setBackupUrls" && it.parameterTypes.size == 1 }
            ?: return false
        val convertedArg = convertBackupsForParamType(targetMethod.parameterTypes[0], backups)
        return withBackupHookSuppressed {
            try {
                targetMethod.isAccessible = true
                targetMethod.invoke(builder, convertedArg)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    internal fun clearBuilderBtrState(builder: Any?) {
        if (builder == null) return
        builderBtrUrl.remove(builder)
        builderResolvedNonBtr.remove(builder)
        BtrSessionManager.unbindBuilderSession(builder)
    }

    internal fun flushDeferredBackups(builder: Any?, btrUrl: String?) {
        if (builder == null) return
        val deferredMethod = builderDeferredBackupMethod.remove(builder)
        val fallbackMethod = deferredMethod ?: builderLastBackupMethod[builder]
        val pendingBackups = builderPendingBackups[builder]
        val wasConvergedToBtr = builderNativeConvergedToBtr.remove(builder) == true
        if (!btrUrl.isNullOrBlank()) {
            if (deferredMethod != null || pendingBackups != null) {
                if (applyBuilderBackupUrls(builder, listOf(btrUrl), fallbackMethod)) {
                    builderNativeConvergedToBtr[builder] = true
                }
            }
        } else if ((deferredMethod != null || wasConvergedToBtr) && pendingBackups != null) {
            applyBuilderBackupUrls(builder, pendingBackups, fallbackMethod)
        }
    }

    internal fun flushDeferredBackupsBeforeBuild(builder: Any?) {
        if (builder != null && builderDeferredBackupMethod.containsKey(builder)) {
            flushDeferredBackups(builder, builderBtrUrl[builder])
        }
    }

    internal fun interceptSetBackupUrls(
        builder: Any?,
        method: Method,
        rawArg: Any?,
        config: AcceleratorConfig = RemoteClient.fetchConfig(),
        onRewrite: ((RewriteResult) -> Unit)? = { RemoteClient.notifyRewrite(it) },
        proceedOriginal: () -> Any?,
        proceedWithArg: (Any) -> Any?
    ): Any? {
        if (suppressBackupHook.get() == true) {
            return proceedOriginal()
        }
        val rawList: List<String>? = when (rawArg) {
            is Collection<*> -> rawArg.filterIsInstance<String>()
            is Array<*> -> rawArg.filterIsInstance<String>()
            else -> null
        }
        if (rawList == null || rawList.isEmpty()) {
            builder?.let {
                builderDeferredBackupMethod.remove(it)
                builderNativeConvergedToBtr.remove(it)
                builderPendingBackups.remove(it)
                builderRawBackups.remove(it)
            }
            return withBackupHookSuppressed { proceedOriginal() }
        }

        if (!config.enabled) {
            builder?.let {
                builderDeferredBackupMethod.remove(it)
                builderNativeConvergedToBtr.remove(it)
                builderPendingBackups.remove(it)
                builderRawBackups.remove(it)
            }
            return withBackupHookSuppressed { proceedOriginal() }
        }

        val hasDrmBackup = rawList.any { rawUrl ->
            PlayinfoRegistry.lookup(rawUrl)?.hasDrm == true ||
                BiliAcceleratorCore.hasDrmSignal(rawUrl)
        }
        if (hasDrmBackup) {
            builder?.let {
                builderDeferredBackupMethod.remove(it)
                builderNativeConvergedToBtr.remove(it)
                builderPendingBackups.remove(it)
                builderRawBackups.remove(it)
                clearBuilderBtrState(it)
                builderResolvedNonBtr[it] = true
            }
            return withBackupHookSuppressed { proceedOriginal() }
        }

        val distinctRaw = rawList.distinct()
        val distinctFiltered = if (
            config.blockPcdn || config.proxyMcdn || config.forceUpos || (config.rewriteAkamai && config.enableBtr)
        ) {
            filterAndFillBackupUrls(rawList, config, onRewrite)
        } else {
            distinctRaw
        }

        builder?.let {
            builderRawBackups[it] = distinctRaw
            builderPendingBackups[it] = distinctFiltered
            builderLastBackupMethod[it] = method
            val cleanLiveList = distinctFiltered.filter { cand ->
                val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cand)?.host)
                BiliAcceleratorCore.isLiveMediaUrl("", cand) &&
                    !BiliAcceleratorCore.isSlowLiveHost(host, cand, config.portHeuristic)
            }
            if (cleanLiveList.isNotEmpty()) {
                builderCleanLiveUrls[it] = cleanLiveList
                for (cand in cleanLiveList) {
                    recordCleanLiveUrl(cand)
                }
            }
        }

        val rawBoundSessionId = builder?.let { BtrSessionManager.getSessionForBuilder(it) }
        val boundSessionId = rawBoundSessionId?.takeIf { BtrSessionManager.get(it) != null }
        if (builder != null && rawBoundSessionId != null && boundSessionId == null) {
            clearBuilderBtrState(builder)
            builderResolvedNonBtr[builder] = true
        }
        val btrConfigActive = config.enableBtr
        if (btrConfigActive) {
            if (boundSessionId != null) {
                BtrSessionManager.updateBackups(boundSessionId, distinctFiltered)
                val btrUrl = builderBtrUrl[builder]
                val nativeBackups = computeNativeBackupsForSession(btrUrl, distinctFiltered, true)
                builderDeferredBackupMethod.remove(builder)
                val res = withBackupHookSuppressed {
                    proceedWithArg(convertBackupsForParamType(method.parameterTypes[0], nativeBackups))
                }
                builderNativeConvergedToBtr[builder] = true
                return res
            } else if (builder != null && builderResolvedNonBtr[builder] != true) {
                // Builder 构建期 setBackupUrls 早于 setUrl：暂缓原生调用，待 setUrl 判定是否走 BTR 后再统一收敛或放行
                builderDeferredBackupMethod[builder] = method
                val retType = method.returnType
                if (retType == Void.TYPE) {
                    return null
                }
                if (retType.isAssignableFrom(builder.javaClass)) {
                    return builder
                }
                return withBackupHookSuppressed {
                    proceedWithArg(convertBackupsForParamType(method.parameterTypes[0], emptyList()))
                }
            } else if (builder == null) {
                BtrSessionManager.updateBackupsByCandidateUrls(distinctFiltered)
            }
        }

        if (builder != null) {
            builderDeferredBackupMethod.remove(builder)
            builderNativeConvergedToBtr.remove(builder)
        }
        if (distinctFiltered.isNotEmpty()) {
            return withBackupHookSuppressed {
                proceedWithArg(convertBackupsForParamType(method.parameterTypes[0], distinctFiltered))
            }
        }
        return withBackupHookSuppressed { proceedOriginal() }
    }

    private fun normalizeLivePathKey(url: String): String {
        return BiliAcceleratorCore.parseUri(url)?.path?.trim()?.trimEnd('/')?.lowercase().orEmpty()
    }

    private fun recordCleanLiveUrl(url: String) {
        val pathKey = normalizeLivePathKey(url)
        if (pathKey.isBlank()) return
        synchronized(livePathCleanUrls) {
            val existing = livePathCleanUrls[pathKey].orEmpty()
            if (!existing.contains(url)) {
                livePathCleanUrls[pathKey] = (existing + url).take(8)
            }
        }
    }

    private fun findCleanLiveUrlForUrl(
        url: String,
        builderInstance: Any?,
        portHeuristic: Boolean
    ): String? {
        val targetPath = normalizeLivePathKey(url)
        val builderCandidates = builderInstance?.let { builderCleanLiveUrls[it] }.orEmpty()
        val pathCandidates = if (targetPath.isNotBlank()) {
            synchronized(livePathCleanUrls) { livePathCleanUrls[targetPath].orEmpty() }
        } else emptyList()
        val combined = (builderCandidates + pathCandidates).distinct()
        return combined.firstOrNull { cand ->
            val candPath = normalizeLivePathKey(cand)
            val host = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cand)?.host)
            (targetPath.isBlank() || candPath == targetPath) &&
                !BiliAcceleratorCore.isSlowLiveHost(host, cand, portHeuristic)
        }
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
        // 1. Hook all constructors (distinct + 全局去重防止继承方法重复注入)
        val constructors = (clazz.declaredConstructors + clazz.constructors).distinct()
        for (constructor in constructors) {
            if (!synchronized(hookedConstructors) { hookedConstructors.add(constructor) }) continue
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
                if (!synchronized(hookedMethods) { hookedMethods.add(m) }) continue
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
            if (!synchronized(hookedMethods) { hookedMethods.add(method) }) continue
            try {
                module.hook(method).intercept { chain ->
                    interceptSetBackupUrls(
                        builder = chain.thisObject,
                        method = method,
                        rawArg = chain.args.firstOrNull(),
                        proceedOriginal = { chain.proceed() },
                        proceedWithArg = { convertedArg ->
                            val newArgs = chain.args.toTypedArray().apply { this[0] = convertedArg }
                            chain.proceed(newArgs)
                        }
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook ${method.name}: ${t.message}")
            }
        }

        // 4. 兜底 Hook build()/create()：确保 Builder 仅有 setBackupUrls 而无 setUrl 时也能正确刷出暂缓的备用地址
        val buildMethods = methods.filter { (it.name == "build" || it.name == "create") && it.parameterTypes.isEmpty() }
        for (m in buildMethods) {
            if (!synchronized(hookedMethods) { hookedMethods.add(m) }) continue
            try {
                module.hook(m).intercept { chain ->
                    flushDeferredBackupsBeforeBuild(chain.thisObject)
                    chain.proceed()
                }
            } catch (_: Throwable) {}
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
                result.reason != "already-local-proxy" &&
                result.reason != "already-proxied"
    }

    private data class BtrRewriteOutcome(
        val btrUrl: String,
        val effectiveResult: RewriteResult
    )

    private fun tryRewriteToBtr(
        originalUrl: String,
        result: RewriteResult,
        config: AcceleratorConfig,
        builderInstance: Any? = null,
        requestHeaders: Map<*, *>? = null
    ): BtrRewriteOutcome? {
        if (!config.enabled || !config.enableBtr) {
            return null
        }
        if (!BiliAcceleratorCore.isMediaUrl(originalUrl)) {
            return null
        }
        // 防重入：严禁对已重定向至本地代理回环的 URL 重复改写
        if (originalUrl.contains("127.0.0.1:") || originalUrl.contains("localhost:") ||
            (result.changed && (result.finalUrl.contains("127.0.0.1:") || result.finalUrl.contains("localhost:")))) {
            return null
        }
        val candidateTarget = if (result.changed) result.finalUrl else originalUrl
        if (result.reason == "parse-failed" || BiliAcceleratorCore.parseUri(candidateTarget) == null) {
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
        val playinfoTrack: PlayinfoTrackEntry? = PlayinfoRegistry.lookup(originalUrl)
            ?: if (result.changed) PlayinfoRegistry.lookup(result.finalUrl) else null
        val isEpisode = hints.isEpisode ||
                (playinfoTrack?.isEpisode == true) ||
                BiliAcceleratorCore.isEpisodeMediaUrl(originalUrl) ||
                (result.changed && BiliAcceleratorCore.isEpisodeMediaUrl(result.finalUrl))
        val isPreview = hints.isPreview ||
                (playinfoTrack?.isPreview == true) ||
                BiliAcceleratorCore.isEpisodePreview(originalUrl) ||
                (result.changed && BiliAcceleratorCore.isEpisodePreview(result.finalUrl))
        val hasDrm = hints.hasDrm ||
                (playinfoTrack?.hasDrm == true) ||
                BiliAcceleratorCore.hasDrmSignal(originalUrl) ||
                (result.changed && BiliAcceleratorCore.hasDrmSignal(result.finalUrl))

        // 剧集专属模式控制：未开启时严格遵循 BTR 原版调度放行至原生播放器
        if (isEpisode) {
            if (!config.enableEpisodeAcceleration) {
                Log.i(TAG, "Episode acceleration disabled in config, leaving to native player")
                return null
            }
            // 剧集试看流（is_preview=1 / Playinfo / Builder 试看属性）：对齐 BTR 原版 unsupportedEpisode，试看流严禁代理介入
            if (isPreview) {
                Log.i(TAG, "Episode preview detected, leaving to native player")
                return null
            }
        }

        // 版权保护/DRM 加密流（Widevine/ChinaDRM/PSSH/is_drm/Playinfo/Builder DRM 属性）绝对不重写至 BTR 本地代理，交由原生直连放行
        if (hasDrm) {
            Log.i(TAG, "DRM protected stream detected, bypassing BTR proxy")
            return null
        }

        if (!RemoteClient.ensureBtrProxyStarted(config)) {
            return null
        }

        val rawBackups = builderInstance?.let { builderRawBackups[it] }.orEmpty()
        val pendingBackups = builderInstance?.let { builderPendingBackups[it] }.orEmpty().toMutableList()

        if (playinfoTrack != null && playinfoTrack.backupUrls.isNotEmpty()) {
            val filteredPlayinfoBackups = if (config.blockPcdn || config.proxyMcdn || config.forceUpos || (config.rewriteAkamai && config.enableBtr)) {
                filterAndFillBackupUrls(playinfoTrack.backupUrls, config, null)
            } else {
                playinfoTrack.backupUrls
            }
            for (b in filteredPlayinfoBackups) {
                if (b.isNotBlank() && b != originalUrl && !pendingBackups.contains(b)) {
                    pendingBackups.add(b)
                }
            }
        }

        val cleanOfficialBackup = findPromotableOfficialBackup(originalUrl, result, rawBackups, isLive)
        var targetUrl: String
        var effectiveResult: RewriteResult
        if (cleanOfficialBackup != null) {
            targetUrl = cleanOfficialBackup
            val promotedHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(cleanOfficialBackup)?.host)
            effectiveResult = result.copy(
                changed = cleanOfficialBackup != originalUrl,
                finalUrl = cleanOfficialBackup,
                targetHost = promotedHost,
                reason = "pcdn-promote"
            )
            Log.i(TAG, "Promoted official backup mirror to primary: $cleanOfficialBackup")
        } else if (!isLive && result.reason == "akamai-redirect") {
            // 无干净 UPOS 备用源时，保留原签名 Akamai 地址作为主源，严禁将 Akamai hdnts= 签名发往 UPOS
            targetUrl = originalUrl
            effectiveResult = result.copy(
                changed = false,
                finalUrl = originalUrl,
                targetHost = result.originalHost,
                reason = "akamai-pass"
            )
        } else {
            targetUrl = if (result.changed) result.finalUrl else originalUrl
            effectiveResult = result
        }

        // 对齐 BTR / realzza：BTR 模式下将 Akamai 重写至 UPOS 节点时，必须保留原签名 Akamai 地址作为 403 兜底备用源
        if (!isLive && (result.reason == "akamai-redirect" || BiliAcceleratorCore.isAkamaiHost(result.originalHost))) {
            if (originalUrl != targetUrl && !pendingBackups.contains(originalUrl)) {
                pendingBackups.add(originalUrl)
            }
        }

        if (isLive) {
            val cleanCandidate = findCleanLiveUrlForUrl(targetUrl, builderInstance, config.portHeuristic)
            if (!cleanCandidate.isNullOrBlank() && cleanCandidate != targetUrl && !pendingBackups.contains(cleanCandidate)) {
                pendingBackups.add(cleanCandidate)
            }
        }

        pendingBackups.removeAll { candidate ->
            if (candidate == targetUrl) {
                true
            } else {
                val candHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(candidate)?.host)
                !BiliAcceleratorCore.isAkamaiHost(candHost) && candidate.contains("hdnts=", ignoreCase = true)
            }
        }

        val headerUa = requestHeaders?.entries?.firstOrNull { it.key?.toString()?.equals("user-agent", ignoreCase = true) == true }?.value?.toString()
        val headerRef = requestHeaders?.entries?.firstOrNull { it.key?.toString()?.equals("referer", ignoreCase = true) == true }?.value?.toString()
        val isAudio = !isLive && (playinfoTrack?.isAudio == true || BiliAcceleratorCore.isAudioTrack(targetUrl))

        val proxyPort = io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port
        val ext = when {
            targetUrl.contains(".m3u8") -> "m3u8"
            targetUrl.contains(".flv") -> "flv"
            else -> "m4s"
        }

        // 若同一 Builder 连续调用构造器 / setUrl / setResolveUrl，优先复用或替换旧 Session，杜绝孤立会话膨胀
        if (builderInstance != null) {
            val existingId = BtrSessionManager.getSessionForBuilder(builderInstance)
            val existingSession = existingId?.let { BtrSessionManager.get(it) }
            if (existingSession != null && existingSession.primaryUrl == targetUrl && existingSession.isLive == isLive) {
                if (pendingBackups.isNotEmpty()) {
                    BtrSessionManager.updateBackups(existingSession.sessionId, pendingBackups)
                }
                if (!headerUa.isNullOrBlank()) existingSession.userAgent = headerUa
                if (!headerRef.isNullOrBlank()) existingSession.referer = headerRef
                val reusedUrl = "http://127.0.0.1:$proxyPort/stream/${existingSession.sessionId}.$ext"
                builderResolvedNonBtr.remove(builderInstance)
                builderBtrUrl[builderInstance] = reusedUrl
                flushDeferredBackups(builderInstance, reusedUrl)
                return BtrRewriteOutcome(reusedUrl, effectiveResult)
            }
            if (existingId != null) {
                BtrSessionManager.remove(existingId)
            }
        }

        if (!isLive) {
            BtrSessionManager.refreshSignatureIfSamePath(targetUrl, pendingBackups)
        }

        val sessionId = BtrSessionManager.register(
            primaryUrl = targetUrl,
            backupUrls = pendingBackups,
            userAgent = headerUa,
            isAudio = isAudio,
            isEpisode = isEpisode,
            isLive = isLive,
            config = config
        )
        if (!headerRef.isNullOrBlank()) {
            BtrSessionManager.get(sessionId)?.referer = headerRef
        }
        val btrUrl = "http://127.0.0.1:$proxyPort/stream/$sessionId.$ext"
        if (builderInstance != null) {
            builderResolvedNonBtr.remove(builderInstance)
            BtrSessionManager.bindBuilderSession(builderInstance, sessionId)
            builderBtrUrl[builderInstance] = btrUrl
            flushDeferredBackups(builderInstance, btrUrl)
        }
        return BtrRewriteOutcome(btrUrl, effectiveResult)
    }

    internal fun rewriteArgIfMedia(
        args: List<Any?>,
        builderInstance: Any? = null,
        configOverride: AcceleratorConfig? = null,
        onRewrite: ((RewriteResult) -> Unit)? = { RemoteClient.notifyRewrite(it) }
    ): Array<Any?>? {
        val strIndex = args.indexOfFirst { it is String && BiliAcceleratorCore.hasMediaSignal(it) }
        if (strIndex == -1) {
            clearBuilderBtrState(builderInstance)
            flushDeferredBackups(builderInstance, btrUrl = null)
            return null
        }

        val originalUrl = args[strIndex] as String
        val config = configOverride ?: RemoteClient.fetchConfig()
        if (!config.enabled) {
            clearBuilderBtrState(builderInstance)
            flushDeferredBackups(builderInstance, btrUrl = null)
            builderInstance?.let { builderResolvedNonBtr[it] = true }
            return null
        }

        val parsed = BiliAcceleratorCore.parseUri(originalUrl)
        val isLive = parsed != null && BiliAcceleratorCore.isLiveMediaUrl(parsed.path, originalUrl)
        val result = BiliAcceleratorCore.rewriteUrl(originalUrl, config)
        if (result.reason == "already-local-proxy") {
            return null
        }
        val playinfoTrack = PlayinfoRegistry.lookup(originalUrl)
            ?: if (result.changed) PlayinfoRegistry.lookup(result.finalUrl) else null
        if (playinfoTrack?.hasDrm == true) {
            clearBuilderBtrState(builderInstance)
            flushDeferredBackups(builderInstance, btrUrl = null)
            builderInstance?.let { builderResolvedNonBtr[it] = true }
            Log.i(TAG, "Playinfo DRM detected in media rewrite, leaving stream untouched")
            return null
        }

        if (isLive) {
            if (config.enableLiveAcceleration) {
                val btrOutcome = tryRewriteToBtr(originalUrl, result, config, builderInstance)
                if (btrOutcome != null) {
                    if (btrOutcome.effectiveResult.changed && shouldReportResult(btrOutcome.effectiveResult)) {
                        onRewrite?.invoke(btrOutcome.effectiveResult)
                    }
                    Log.i(TAG, "Rewrote live stream to BTR proxy: ${result.originalHost} -> 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
                    val newArgs = args.toTypedArray()
                    newArgs[strIndex] = btrOutcome.btrUrl
                    return newArgs
                }
            }
            clearBuilderBtrState(builderInstance)
            flushDeferredBackups(builderInstance, btrUrl = null)
            builderInstance?.let { builderResolvedNonBtr[it] = true }
            if (result.changed) {
                onRewrite?.invoke(result)
                val newArgs = args.toTypedArray()
                newArgs[strIndex] = result.finalUrl
                return newArgs
            }
            if (result.isPcdn && config.blockPcdn) {
                val cleanLiveUrl = findCleanLiveUrlForUrl(originalUrl, builderInstance, config.portHeuristic)
                val cleanLiveHost = cleanLiveUrl?.let { BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(it)?.host) }
                if (!cleanLiveUrl.isNullOrBlank() && !cleanLiveHost.isNullOrBlank() && cleanLiveUrl != originalUrl) {
                    val liveResult = RewriteResult(
                        changed = true,
                        originalUrl = originalUrl,
                        finalUrl = cleanLiveUrl,
                        originalHost = parsed.host,
                        targetHost = cleanLiveHost,
                        reason = "live-pcdn-filter",
                        isPcdn = true,
                        isMcdn = result.isMcdn,
                        segmentKey = result.segmentKey
                    )
                    onRewrite?.invoke(liveResult)
                    val newArgs = args.toTypedArray()
                    newArgs[strIndex] = cleanLiveUrl
                    return newArgs
                }
            }
            if (shouldReportResult(result)) onRewrite?.invoke(result)
            return null
        }

        val btrOutcome = tryRewriteToBtr(originalUrl, result, config, builderInstance)
        if (btrOutcome != null) {
            if (btrOutcome.effectiveResult.changed && shouldReportResult(btrOutcome.effectiveResult)) {
                onRewrite?.invoke(btrOutcome.effectiveResult)
            }
            Log.i(TAG, "Rewrote media url to BTR proxy: ${result.originalHost} -> 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = btrOutcome.btrUrl
            return newArgs
        }

        clearBuilderBtrState(builderInstance)
        flushDeferredBackups(builderInstance, btrUrl = null)
        builderInstance?.let { builderResolvedNonBtr[it] = true }
        if (result.changed) {
            val rawBackups = builderInstance?.let { builderRawBackups[it] }.orEmpty()
            val cleanOfficialBackup = findPromotableOfficialBackup(originalUrl, result, rawBackups, isLive)

            val finalUrl = cleanOfficialBackup ?: result.finalUrl
            val finalTargetHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(finalUrl)?.host)
            val reportedResult = if (cleanOfficialBackup != null) {
                result.copy(
                    finalUrl = finalUrl,
                    targetHost = finalTargetHost,
                    reason = "pcdn-promote"
                )
            } else {
                result
            }
            if (shouldReportResult(reportedResult)) onRewrite?.invoke(reportedResult)
            Log.i(TAG, "Rewrote media url: ${result.originalHost} -> $finalTargetHost [${reportedResult.reason}]")
            val newArgs = args.toTypedArray()
            newArgs[strIndex] = finalUrl
            return newArgs
        }
        if (shouldReportResult(result)) onRewrite?.invoke(result)
        return null
    }

    private fun findPromotableOfficialBackup(
        originalUrl: String,
        result: RewriteResult,
        rawBackups: List<String>,
        isLive: Boolean
    ): String? {
        if (isLive) return null
        val shouldPromote = result.isPcdn ||
            result.isMcdn ||
            BiliAcceleratorCore.isRelayUrl(originalUrl) ||
            result.reason == "akamai-redirect"
        if (!shouldPromote) return null
        val playinfoEntry = PlayinfoRegistry.lookup(originalUrl)
            ?: if (result.changed) PlayinfoRegistry.lookup(result.finalUrl) else null
        val playinfoUrls = listOfNotNull(playinfoEntry?.primaryUrl) + playinfoEntry?.backupUrls.orEmpty()
        val candidateBackups = (rawBackups + playinfoUrls)
            .map { BiliAcceleratorCore.unwrapSmtcdnsUrl(it).ifBlank { it } }
            .distinct()
        return candidateBackups.firstOrNull { backup ->
            BiliAcceleratorCore.isCleanUposDonor(backup)
        }
    }

    internal fun rewriteFallbackDataSource(
        original: String?,
        playerInstance: Any?,
        headersMap: Map<*, *>? = null,
        configOverride: AcceleratorConfig? = null,
        onRewrite: ((RewriteResult) -> Unit)? = { RemoteClient.notifyRewrite(it) }
    ): String? {
        if (original == null || !BiliAcceleratorCore.hasMediaSignal(original) || !BiliAcceleratorCore.isMediaUrl(original)) {
            clearBuilderBtrState(playerInstance)
            return null
        }
        if (PlayinfoRegistry.lookup(original)?.hasDrm == true) {
            clearBuilderBtrState(playerInstance)
            return null
        }
        val config = configOverride ?: RemoteClient.fetchConfig()
        if (!config.enabled) {
            clearBuilderBtrState(playerInstance)
            return null
        }
        val result = BiliAcceleratorCore.rewriteUrl(original, config)
        if (result.reason == "already-local-proxy") {
            return null
        }
        if (result.changed && PlayinfoRegistry.lookup(result.finalUrl)?.hasDrm == true) {
            clearBuilderBtrState(playerInstance)
            return null
        }
        val btrOutcome = tryRewriteToBtr(original, result, config, playerInstance, headersMap)
        if (btrOutcome != null) {
            if (btrOutcome.effectiveResult.changed && shouldReportResult(btrOutcome.effectiveResult)) {
                onRewrite?.invoke(btrOutcome.effectiveResult)
            }
            Log.i(TAG, "Rewrote IjkMediaPlayer dataSource to BTR: 127.0.0.1:${io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer.port}")
            return btrOutcome.btrUrl
        }
        clearBuilderBtrState(playerInstance)
        if (result.changed) {
            val rawBackups = playerInstance?.let { builderRawBackups[it] }.orEmpty()
            val isLive = BiliAcceleratorCore.isLiveMediaUrl("", original)
            val cleanOfficialBackup = findPromotableOfficialBackup(original, result, rawBackups, isLive)
            val finalUrl = cleanOfficialBackup ?: result.finalUrl
            val finalTargetHost = BiliAcceleratorCore.cleanHost(BiliAcceleratorCore.parseUri(finalUrl)?.host)
            val reportedResult = if (cleanOfficialBackup != null) {
                result.copy(
                    finalUrl = finalUrl,
                    targetHost = finalTargetHost,
                    reason = "pcdn-promote"
                )
            } else {
                result
            }
            if (shouldReportResult(reportedResult)) onRewrite?.invoke(reportedResult)
            Log.i(TAG, "Rewrote IjkMediaPlayer dataSource: ${result.originalHost} -> $finalTargetHost [${reportedResult.reason}]")
            return finalUrl
        }
        if (shouldReportResult(result)) onRewrite?.invoke(result)
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
                if (!synchronized(hookedMethods) { hookedMethods.add(m) }) continue
                try {
                    module.hook(m).intercept { chain ->
                        val original = chain.args.getOrNull(0) as? String
                        val headersMap = chain.args.getOrNull(1) as? Map<*, *>
                        val rewritten = rewriteFallbackDataSource(original, chain.thisObject, headersMap)
                        if (rewritten != null) {
                            val newArgs = chain.args.toTypedArray().apply { this[0] = rewritten }
                            return@intercept chain.proceed(newArgs)
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
                if (!synchronized(hookedMethods) { hookedMethods.add(m) }) continue
                try {
                    module.hook(m).intercept { chain ->
                        val originalUri = chain.args.getOrNull(1) as? Uri
                        val headersMap = chain.args.getOrNull(2) as? Map<*, *>
                        val rewritten = rewriteFallbackDataSource(originalUri?.toString(), chain.thisObject, headersMap)
                        if (rewritten != null) {
                            val newArgs = chain.args.toTypedArray().apply { this[1] = Uri.parse(rewritten) }
                            return@intercept chain.proceed(newArgs)
                        }
                        chain.proceed()
                    }
                    fallbackHooked = true
                } catch (_: Throwable) {}
            }

            // 3. Hook IjkMediaPlayer / AbstractMediaPlayer buffering & stall callbacks 通知并发控制器
            //    优先拦截 postEventFromNative；仅当未挂载 postEventFromNative 时才回退到 notifyOnInfo/notifyOnBufferingUpdate，避免重复触发
            val eventClasses = listOfNotNull(ijkPlayerClass, ijkPlayerClass.superclass)
            var hookedPostEvent = false
            for (cls in eventClasses) {
                for (m in cls.declaredMethods) {
                    if (m.name == "postEventFromNative" && m.parameterTypes.size >= 4) {
                        if (!synchronized(hookedMethods) { hookedMethods.add(m) }) {
                            hookedPostEvent = true
                            continue
                        }
                        try {
                            module.hook(m).intercept { chain ->
                                val weakPlayer = (chain.args.getOrNull(0) as? WeakReference<*>)?.get()
                                val sid = weakPlayer?.let { BtrSessionManager.getSessionForBuilder(it) }
                                val what = chain.args.getOrNull(1) as? Int
                                val arg1 = chain.args.getOrNull(2) as? Int
                                if (what == 200 && arg1 == 701) {
                                    BtrSessionManager.notifyPlayerStall(sid)
                                } else if (what == 3 && arg1 != null && arg1 in 0..15) {
                                    BtrSessionManager.notifyPlayerBufferHunger(sid)
                                }
                                chain.proceed()
                            }
                            hookedPostEvent = true
                        } catch (_: Throwable) {}
                    }
                }
            }
            if (!hookedPostEvent) {
                for (cls in eventClasses) {
                    for (m in cls.declaredMethods) {
                        try {
                            when (m.name) {
                                "notifyOnInfo" -> if (m.parameterTypes.size == 2 && synchronized(hookedMethods) { hookedMethods.add(m) }) {
                                    module.hook(m).intercept { chain ->
                                        val what = chain.args.getOrNull(0) as? Int
                                        if (what == 701) {
                                            val sid = chain.thisObject?.let { BtrSessionManager.getSessionForBuilder(it) }
                                            BtrSessionManager.notifyPlayerStall(sid)
                                        }
                                        chain.proceed()
                                    }
                                }
                                "notifyOnBufferingUpdate" -> if (m.parameterTypes.size == 1 && synchronized(hookedMethods) { hookedMethods.add(m) }) {
                                    module.hook(m).intercept { chain ->
                                        val pct = chain.args.getOrNull(0) as? Int
                                        if (pct != null && pct in 0..15) {
                                            val sid = chain.thisObject?.let { BtrSessionManager.getSessionForBuilder(it) }
                                            BtrSessionManager.notifyPlayerBufferHunger(sid)
                                        }
                                        chain.proceed()
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
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
