package io.github.jh_mmm.biliaccelerator.hook

import android.net.Uri
import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.RouteEngine
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

object PlayerHook {

    private const val TAG = "BiliAccelerator-Player"
    private const val DEDUP_WINDOW_MS = 2_000L
    private const val DEDUP_MAX_ENTRIES = 128

    // Both spellings are observed across different Bilibili versions (Assert vs Asset)
    private val SEGMENT_BUILDER_CLASSES = listOf(
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssetSegment\$Builder",
        "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaSegment\$Builder"
    )

    data class PromotedSegmentResult(
        val baseUrl: String,
        val backupUrls: List<String>,
        val promoted: Boolean,
        val promoteResult: RewriteResult? = null
    )

    private data class BuilderTrackState(
        var rawPrimaryUrl: String? = null,
        var reported: Boolean = false
    )

    private val builderStates = Collections.synchronizedMap(WeakHashMap<Any, BuilderTrackState>())

    private val recentSegmentTimestamps = object : LinkedHashMap<String, Long>(DEDUP_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > DEDUP_MAX_ENTRIES
        }
    }

    internal fun shouldReportSegment(url: String, result: RewriteResult, builderInstance: Any? = null): Boolean {
        if (result.reason == "ignored" || result.reason == "parse-failed" || result.reason == "live-skip") {
            return false
        }
        if (builderInstance != null) {
            synchronized(builderStates) {
                val state = builderStates.getOrPut(builderInstance) { BuilderTrackState() }
                if (state.reported) {
                    return false
                }
                state.reported = true
            }
        }
        val key = BiliAcceleratorCore.parseUri(url)?.path?.takeIf { it.isNotEmpty() } ?: url.substringBefore('?')
        val now = System.currentTimeMillis()
        synchronized(recentSegmentTimestamps) {
            val lastTime = recentSegmentTimestamps[key]
            if (lastTime != null && (now - lastTime) in 0..DEDUP_WINDOW_MS) {
                return false
            }
            recentSegmentTimestamps[key] = now
            return true
        }
    }

    fun init(module: XposedModule, classLoader: ClassLoader) {
        hookPlayInfoResponse(module, classLoader)

        var hooked = false
        for (className in SEGMENT_BUILDER_CLASSES) {
            val builderClass = try {
                Class.forName(className, false, classLoader)
            } catch (_: Throwable) {
                null
            } ?: continue

            val hookCount = hookSegmentBuilder(module, builderClass)
            if (hookCount > 0) {
                hooked = true
                HookStatusTracker.recordStatus("player_segment", "$className ($hookCount hooks)")
                Log.i(TAG, "Successfully hooked player segment builder: $className ($hookCount hooks)")
                break
            }
        }

        if (hooked) {
            // Segment Builder 已成功挂载时不再重复挂载 IjkMediaPlayer fallback，杜绝同一请求被双重拦截与重复统计
            HookStatusTracker.recordStatus("player_fallback", "未启用 (Segment Builder 已就绪)")
            return
        }

        HookStatusTracker.recordStatus("player_segment", "未找到 (版本不兼容)")
        Log.i(TAG, "Standard IjkMediaAsset builder not found, relying on player fallback hooks")

        // Fallback: 仅在 Segment Builder 未挂载时启用 IjkMediaPlayer 兜底 Hook
        hookFallbackPlayer(module, classLoader)
    }

    /**
     * 拦截 OkHttp ResponseBody.string() 中携带的 getRoomPlayInfo 直播 url_info 与 DASH playurl JSON：
     * - 对 getRoomPlayInfo 的 url_info 候选主机列表执行 filterLiveUrlInfo，剔除 PCDN/MCDN 节点；
     * - 对 DASH playurl JSON 执行 RouteEngine.ingestPlayurlJson，构建会话表示层映射表。
     */
    private fun hookPlayInfoResponse(module: XposedModule, classLoader: ClassLoader) {
        val responseBodyClass = try {
            Class.forName("okhttp3.ResponseBody", false, classLoader)
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            val stringMethod = responseBodyClass.getDeclaredMethod("string")
            module.hook(stringMethod).intercept { chain ->
                val raw = chain.proceed() as? String ?: return@intercept null
                if (raw.length !in 32..524_288) {
                    return@intercept raw
                }
                val hasUrlInfo = raw.contains("\"url_info\"")
                val hasDash = raw.contains("\"dash\"") && raw.contains("bilivideo")
                if (!hasUrlInfo && !hasDash) {
                    return@intercept raw
                }
                val config = RemoteClient.fetchConfig()
                if (!config.enabled) {
                    return@intercept raw
                }
                if (hasDash) {
                    runCatching { RouteEngine.ingestPlayurlJson(raw, config) }
                }
                if (hasUrlInfo && config.blockPcdn) {
                    val filtered = BiliAcceleratorCore.filterLiveUrlInfo(raw, config)
                    if (filtered.changed) {
                        filtered.rewrites.firstOrNull()?.let { RemoteClient.notifyRewrite(it) }
                        return@intercept filtered.json
                    }
                }
                raw
            }
        } catch (_: Throwable) {}
    }

    private fun hookSegmentBuilder(module: XposedModule, clazz: Class<*>): Int {
        var hookCount = 0

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
                hookCount++
            } catch (t: Throwable) {
                Log.d(TAG, "Could not hook constructor: ${t.message}")
            }
        }

        // 2. Hook setter methods: setUrl, setBaseUrl, setResolveUrl
        val setterNames = setOf("setUrl", "setBaseUrl", "setResolveUrl")
        val methods = (clazz.declaredMethods + clazz.methods).distinct()
        val urlSetterMethod = methods.firstOrNull {
            (it.name == "setUrl" || it.name == "setBaseUrl") &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
        }

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
                    hookCount++
                } catch (_: Throwable) {}
            }
        }

        // 3. Hook setBackupUrls(Collection/List or Array)
        val backupMethods = methods.filter {
            it.name == "setBackupUrls" && it.parameterTypes.size == 1
        }

        for (method in backupMethods) {
            try {
                module.hook(method).intercept { chain ->
                    val config = RemoteClient.fetchConfig()
                    if (!config.enabled || !config.blockPcdn) {
                        return@intercept chain.proceed()
                    }

                    val arg = chain.args.firstOrNull() ?: return@intercept chain.proceed()
                    val (rawList, hasNonString) = when (arg) {
                        is Collection<*> -> {
                            val strings = arg.filterIsInstance<String>()
                            strings to (strings.size != arg.size)
                        }
                        is Array<*> -> {
                            val strings = arg.filterIsInstance<String>()
                            strings to (strings.size != arg.size)
                        }
                        else -> return@intercept chain.proceed()
                    }
                    // 若存在非 String 元素、列表为空或没有任何媒体特征，原样放行，绝不盲目替换原集合引用
                    if (hasNonString || rawList.isEmpty() || rawList.none { BiliAcceleratorCore.hasMediaSignal(it) }) {
                        return@intercept chain.proceed()
                    }

                    // 优先尝试 promoteIssued：若主 URL 是 PCDN 且备份列表中包含 B 站签发的干净官方节点，直接提权为首选 URL
                    var effectiveRawList = rawList
                    val thisObj = chain.thisObject
                    val rawPrimary = if (thisObj != null) {
                        synchronized(builderStates) { builderStates[thisObj]?.rawPrimaryUrl }
                    } else {
                        null
                    }
                    RouteEngine.ingestSegment(rawPrimary, rawList, config)
                    if (thisObj != null && !config.forceUpos) {
                        if (!rawPrimary.isNullOrEmpty() && !BiliAcceleratorCore.isUsableIssuedUrl(rawPrimary, config)) {
                            val promoted = promoteIssued(rawPrimary, rawList, config)
                            if (promoted.promoted && urlSetterMethod != null) {
                                runCatching {
                                    urlSetterMethod.isAccessible = true
                                    urlSetterMethod.invoke(thisObj, promoted.baseUrl)
                                    promoted.promoteResult?.let { RemoteClient.notifyRewrite(it) }
                                    effectiveRawList = promoted.backupUrls
                                }
                            }
                        }
                    }

                    val distinctFiltered = if (effectiveRawList === rawList) {
                        filterAndFillBackupUrls(rawList, config)
                    } else {
                        effectiveRawList
                    }

                    // 若过滤前后列表完全一致，保留原集合对象引用不变
                    if (distinctFiltered == rawList) {
                        return@intercept chain.proceed()
                    }

                    // Fail-closed：即使全部 PCDN 被过滤后为空列表，也传入空集合阻断 PCDN 回退
                    val paramType = method.parameterTypes[0]
                    val convertedArg: Any = when {
                        paramType.isArray -> distinctFiltered.toTypedArray()
                        java.util.Set::class.java.isAssignableFrom(paramType) -> java.util.LinkedHashSet(distinctFiltered)
                        java.util.ArrayList::class.java.isAssignableFrom(paramType) -> java.util.ArrayList(distinctFiltered)
                        java.util.List::class.java.isAssignableFrom(paramType) -> java.util.ArrayList(distinctFiltered)
                        java.util.Collection::class.java.isAssignableFrom(paramType) -> java.util.ArrayList(distinctFiltered)
                        else -> java.util.ArrayList(distinctFiltered)
                    }
                    val newArgs = chain.args.toTypedArray().apply { this[0] = convertedArg }
                    chain.proceed(newArgs)
                }
                hookCount++
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook ${method.name}: ${t.message}")
            }
        }

        return hookCount
    }

    /**
     * 移植自上游 promoteIssued：
     * 当主 URL 是劣质节点（PCDN/MCDN/调度器）且备用列表中存在 B 站官方已签发的干净 CDN URL 时，
     * 优先将该已签发的备用 URL 提权为主 URL（其自身已带对应域名的合法签名，无需强制替换域名）。
     */
    internal fun promoteIssued(
        baseUrl: String,
        backupUrls: List<String>,
        config: AcceleratorConfig
    ): PromotedSegmentResult {
        RouteEngine.ingestSegment(baseUrl, backupUrls, config)
        if (!config.enabled || !config.blockPcdn || config.forceUpos || BiliAcceleratorCore.isUsableIssuedUrl(baseUrl, config)) {
            return PromotedSegmentResult(
                baseUrl = baseUrl,
                backupUrls = filterAndFillBackupUrls(backupUrls, config),
                promoted = false
            )
        }

        val usableIdx = backupUrls.indexOfFirst { BiliAcceleratorCore.isUsableIssuedUrl(it, config) }
        if (usableIdx == -1) {
            return PromotedSegmentResult(
                baseUrl = baseUrl,
                backupUrls = filterAndFillBackupUrls(backupUrls, config),
                promoted = false
            )
        }

        val promotedUrl = backupUrls[usableIdx]
        val remainingBackups = backupUrls.filterIndexed { index, _ -> index != usableIdx }
        val filteredBackups = filterAndFillBackupUrls(remainingBackups, config)
        val parsedBase = BiliAcceleratorCore.parseUri(baseUrl)
        val fromHost = parsedBase?.host?.lowercase() ?: ""
        val toHost = BiliAcceleratorCore.parseUri(promotedUrl)?.host?.lowercase() ?: ""
        val isLive = parsedBase?.let { BiliAcceleratorCore.isLiveMediaUrl(it.path) } == true
        val segKey = parsedBase?.let { BiliAcceleratorCore.extractSegmentKey(it.path) } ?: ""

        return PromotedSegmentResult(
            baseUrl = promotedUrl,
            backupUrls = filteredBackups,
            promoted = true,
            promoteResult = RewriteResult(
                changed = true,
                originalUrl = baseUrl,
                finalUrl = promotedUrl,
                originalHost = fromHost,
                targetHost = toHost,
                reason = if (isLive) "live-pcdn-filter" else "pcdn-promote",
                isPcdn = true,
                isMcdn = false,
                segmentKey = segKey
            )
        )
    }

    /**
     * 过滤备用 URL 列表中的 PCDN 节点：
     * 1. 直播流（/live-bvc/）：使用 isSlowLiveHost 剔除慢速 PCDN/MCDN 节点，保留官方直播 CDN，且绝不删空最后一个可用节点；
     * 2. 点播流（非 Force 模式）：优先保留 B 站自己签发的干净官方备用 URL 及调度器恢复的原生源站；
     *    仅当没有任何已签发干净节点时，才以 targetHost 重写作为兜底，绝不凭空从 CANDIDATE_POOL 合成未签发域名；
     * 3. 备选列表过滤不再逐条上报 totalRequests，避免统计虚高。
     */
    internal fun filterAndFillBackupUrls(rawList: List<String>, config: AcceleratorConfig): List<String> {
        if (rawList.isEmpty() || !config.enabled || !config.blockPcdn) return rawList

        // 1. 直播流分支：过滤慢速直播 PCDN/MCDN 节点，若全部为慢速节点则保留原列表兜底
        val isLiveList = rawList.any { url ->
            BiliAcceleratorCore.parseUri(url)?.let { BiliAcceleratorCore.isLiveMediaUrl(it.path) } == true
        }
        if (isLiveList) {
            val keptLive = rawList.filter { !BiliAcceleratorCore.isSlowLiveHost(it, null, config) }
            return if (keptLive.isNotEmpty()) keptLive.distinct() else rawList.distinct()
        }

        // 2. Force 模式：统一按配置重写至目标 UPOS 镜像
        if (config.forceUpos) {
            val forced = mutableListOf<String>()
            for (url in rawList) {
                val res = BiliAcceleratorCore.rewriteUrl(url, config)
                if (res.changed) {
                    forced.add(res.finalUrl)
                } else if (!res.isPcdn) {
                    forced.add(url)
                }
            }
            return forced.distinct()
        }

        // 3. 默认模式（bad-only）：优先保留 B 站签发的干净官方备用 URL 及调度器恢复的原生源站
        val cleanIssuedOrRestored = mutableListOf<String>()
        val fallbackRewritten = mutableListOf<String>()

        for (url in rawList) {
            if (BiliAcceleratorCore.isUsableIssuedUrl(url, config)) {
                cleanIssuedOrRestored.add(url)
                continue
            }
            val res = BiliAcceleratorCore.rewriteUrl(url, config)
            if (res.changed) {
                if (res.reason == "szbdyd-source" || res.reason == "mountaintoys-source" || res.reason == "mcdn-proxy") {
                    cleanIssuedOrRestored.add(res.finalUrl)
                } else {
                    fallbackRewritten.add(res.finalUrl)
                }
            } else if (!res.isPcdn) {
                cleanIssuedOrRestored.add(url)
            }
        }

        // 若已有 B 站签发的干净官方节点，优先使用它们；仅当无任何干净签发节点时才用 targetHost 兜底
        return if (cleanIssuedOrRestored.isNotEmpty()) {
            (cleanIssuedOrRestored + fallbackRewritten).distinct()
        } else {
            fallbackRewritten.distinct()
        }
    }

    internal fun rewriteArgIfMedia(args: List<Any?>, builderInstance: Any? = null): Array<Any?>? {
        val config = RemoteClient.fetchConfig()
        if (!config.enabled) return null

        var newArgs: Array<Any?>? = null
        var primaryRecorded = false

        for (i in args.indices) {
            val originalUrl = args[i] as? String ?: continue

            // 若参数为 getRoomPlayInfo 直播 JSON 或 DASH playurl JSON，执行候选主机过滤与路由表录入
            val trimmed = originalUrl.trimStart()
            if (trimmed.startsWith("{")) {
                if (trimmed.contains("\"dash\"")) {
                    RouteEngine.ingestPlayurlJson(originalUrl, config)
                }
                if (trimmed.contains("\"url_info\"")) {
                    val liveFiltered = BiliAcceleratorCore.filterLiveUrlInfo(originalUrl, config)
                    if (liveFiltered.changed) {
                        liveFiltered.rewrites.firstOrNull()?.let { RemoteClient.notifyRewrite(it) }
                        val targetArray = newArgs ?: args.toTypedArray().also { newArgs = it }
                        targetArray[i] = liveFiltered.json
                    }
                    continue
                }
            }

            if (!BiliAcceleratorCore.hasMediaSignal(originalUrl)) continue

            if (builderInstance != null && i == 0) {
                synchronized(builderStates) {
                    val state = builderStates.getOrPut(builderInstance) { BuilderTrackState() }
                    if (state.rawPrimaryUrl == null) {
                        state.rawPrimaryUrl = originalUrl
                    }
                }
            }

            val result = BiliAcceleratorCore.rewriteUrl(originalUrl, config)
            val routedUrl = RouteEngine.engineRoute(result.finalUrl, config)
            val effectiveResult = if (routedUrl != result.finalUrl) {
                val routedHost = BiliAcceleratorCore.parseUri(routedUrl)?.host?.lowercase() ?: result.targetHost
                result.copy(
                    changed = true,
                    finalUrl = routedUrl,
                    targetHost = routedHost,
                    reason = if (result.changed) result.reason else "route-switch"
                )
            } else {
                result
            }

            if (!primaryRecorded && shouldReportSegment(originalUrl, effectiveResult, builderInstance)) {
                RemoteClient.notifyRewrite(effectiveResult)
                primaryRecorded = true
            }

            if (effectiveResult.changed) {
                Log.i(TAG, "Rewrote media url: ${effectiveResult.originalHost} -> ${effectiveResult.targetHost} [${effectiveResult.reason}]")
                val targetArray = newArgs ?: args.toTypedArray().also { newArgs = it }
                targetArray[i] = effectiveResult.finalUrl
            }
        }
        return newArgs
    }

    private fun hookFallbackPlayer(module: XposedModule, classLoader: ClassLoader) {
        val ijkPlayerClass = try {
            Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer", false, classLoader)
        } catch (_: Throwable) {
            null
        }

        if (ijkPlayerClass == null) {
            HookStatusTracker.recordStatus("player_fallback", "未找到 (IjkMediaPlayer 类缺失)")
            return
        }

        var fallbackHookCount = 0

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
                            if (shouldReportSegment(original, result)) {
                                RemoteClient.notifyRewrite(result)
                            }
                            if (result.changed && chain.args.isNotEmpty()) {
                                Log.i(TAG, "Rewrote IjkMediaPlayer dataSource: ${result.originalHost} -> ${result.targetHost}")
                                val newArgs = chain.args.toTypedArray().apply { this[0] = result.finalUrl }
                                return@intercept chain.proceed(newArgs)
                            }
                        }
                    }
                    chain.proceed()
                }
                fallbackHookCount++
            } catch (_: NoSuchMethodException) {}

            // 2. Hook setDataSource(Context context, Uri uri) 及重载
            val uriMethods = (ijkPlayerClass.declaredMethods + ijkPlayerClass.methods).distinct().filter {
                it.name == "setDataSource" && it.parameterTypes.size >= 2 && it.parameterTypes[1] == Uri::class.java
            }
            for (m in uriMethods) {
                try {
                    module.hook(m).intercept { chain ->
                        if (chain.args.size >= 2) {
                            val originalUri = chain.args[1] as? Uri
                            if (originalUri != null) {
                                val original = originalUri.toString()
                                if (BiliAcceleratorCore.hasMediaSignal(original)) {
                                    val config = RemoteClient.fetchConfig()
                                    if (config.enabled) {
                                        val result = BiliAcceleratorCore.rewriteUrl(original, config)
                                        if (shouldReportSegment(original, result)) {
                                            RemoteClient.notifyRewrite(result)
                                        }
                                        if (result.changed) {
                                            Log.i(TAG, "Rewrote IjkMediaPlayer Uri dataSource: ${result.originalHost} -> ${result.targetHost}")
                                            val newArgs = chain.args.toTypedArray().apply { this[1] = Uri.parse(result.finalUrl) }
                                            return@intercept chain.proceed(newArgs)
                                        }
                                    }
                                }
                            }
                        }
                        chain.proceed()
                    }
                    fallbackHookCount++
                } catch (_: Throwable) {}
            }

            if (fallbackHookCount > 0) {
                HookStatusTracker.recordStatus("player_fallback", "tv.danmaku.ijk.media.player.IjkMediaPlayer ($fallbackHookCount hooks)")
                Log.i(TAG, "Successfully hooked IjkMediaPlayer setDataSource methods ($fallbackHookCount hooks)")
            } else {
                HookStatusTracker.recordStatus("player_fallback", "未挂载")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hookFallbackPlayer failed: ${t.message}")
            HookStatusTracker.recordStatus("player_fallback", "挂载失败: ${t.message}")
        }
    }
}

