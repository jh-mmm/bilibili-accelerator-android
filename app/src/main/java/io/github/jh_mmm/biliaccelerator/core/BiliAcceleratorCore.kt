package io.github.jh_mmm.biliaccelerator.core

import android.net.Uri
import io.github.jh_mmm.biliaccelerator.BuildConfig
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class RewriteResult(
    val changed: Boolean,
    val originalUrl: String,
    val finalUrl: String,
    val originalHost: String,
    val targetHost: String,
    val reason: String,
    val isPcdn: Boolean,
    val isMcdn: Boolean,
    val segmentKey: String = "",
    val isBtr: Boolean = false,
    val btrChunks: Int = 0,
    val btrBytes: Long = 0L,
    val btrConcurrency: Int = 0,
    val btrCdnCount: Int = 0,
    val btrAvgLatencyMs: Long = 0L
)

data class AcceleratorConfig(
    val enabled: Boolean = true,
    val targetHost: String = "upos-sz-mirrorcos.bilivideo.com",
    val proxyHost: String = "proxy-tf-all-ws.bilivideo.com",
    val blockPcdn: Boolean = true,
    val proxyMcdn: Boolean = true,
    val mcdnStrategy: String = "proxy-all", // proxy-all | proxy-v1 | replace
    val forceUpos: Boolean = false,
    val rewriteAkamai: Boolean = false,
    val portHeuristic: Boolean = true,
    val enableMossHook: Boolean = false,
    val enableBtr: Boolean = true,
    val btrAutoConcurrency: Boolean = true,
    val btrConcurrency: Int = 6,
    val btrAutoChunkSize: Boolean = true,
    val btrChunkSizeKb: Int = 512,
    val customCdnPool: List<String> = emptyList(),
    val btrCdnPoolSize: Int = 8,
    val enableEpisodeAcceleration: Boolean = true,
    val enableLiveAcceleration: Boolean = false
)

object BiliAcceleratorCore {

    val MODULE_PACKAGE: String = BuildConfig.APPLICATION_ID

    val TARGET_PACKAGES = setOf(
        "tv.danmaku.bili",
        "com.bilibili.app.in",
        "com.bstar.intl",
        "tv.danmaku.bilipr",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.blue",
        "com.bilibili.studio",
        "com.bilibili.comic"
    )

    private val PACKAGE_NAME_REGEX = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")

    fun isTargetPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val p = packageName.trim().lowercase()
        if (!PACKAGE_NAME_REGEX.matches(p)) return false
        if (TARGET_PACKAGES.contains(p)) return true
        return p.startsWith("tv.danmaku.bili.") ||
                p.startsWith("com.bilibili.") ||
                p.startsWith("com.bstar.")
    }

    // 跨区统一交错候选池（对齐 bilibili-accelerator v0.5.0：不预设地域顺序，保证小池尺寸下大陆与海外节点均被包含，由实测吞吐动态决定优胜者）
    val INTERLEAVED_CANDIDATE_POOL = listOf(
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorhwov.bilivideo.com",
        "upos-tf-all-hw.bilivideo.com",
        "upos-tf-all-tx.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com"
    )

    val MAINLAND_CANDIDATE_POOL = listOf(
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-tf-all-hw.bilivideo.com",
        "upos-tf-all-tx.bilivideo.com",
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirrorhwov.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com"
    )

    val OVERSEAS_CANDIDATE_POOL = listOf(
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirrorhwov.bilivideo.com",
        "upos-sz-mirrorali.bilivideo.com",
        "upos-tf-all-hw.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-tf-all-tx.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com"
    )

    val HK_REGIONAL_CANDIDATE_POOL = listOf(
        "cn-hk-eq-01-01.bilivideo.com",
        "cn-hk-eq-01-03.bilivideo.com"
    )

    val CANDIDATE_POOL = INTERLEAVED_CANDIDATE_POOL
    val DEFAULT_CDN_POOL = CANDIDATE_POOL

    /**
     * 判断当前系统时区是否处于海外环境（不依赖 Locale，防止海外简体中文系统被误判为国内；移除含糊的 cst 时区名）
     */
    fun isOverseasEnvironment(): Boolean {
        return try {
            val tzId = java.util.TimeZone.getDefault().id.lowercase()
            val isMainlandTz = tzId.contains("shanghai") || tzId.contains("chongqing") ||
                    tzId.contains("harbin") || tzId.contains("urumqi") || tzId == "prc"
            !isMainlandTz
        } catch (_: Throwable) {
            false
        }
    }

    fun getEffectiveCdnPool(config: AcceleratorConfig): List<String> {
        val userList = config.customCdnPool
            .map { cleanHost(it).lowercase() }
            .filter { isValidHost(it) && !isAkamaiHost(it) }
            .distinct()
        val basePool = if (userList.isNotEmpty()) userList else CANDIDATE_POOL
        val poolSize = config.btrCdnPoolSize.coerceIn(1, 32)
        return basePool.take(poolSize)
    }

    private val HOSTNAME_REGEX = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(:[0-9]{1,5})?$""")
    private val MEDIA_PATH_REGEX = Regex("""\.(m4s|mp4|flv|m3u8)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val IPV6_GROUP_REGEX = Regex("""^[0-9a-fA-F]{1,4}$""")
    private val XY_MCDN_REGEX = Regex("""^xy(?:\d+x){3}\d+xy\.mcdn\.bilivideo\.(?:cn|com|net)$""", RegexOption.IGNORE_CASE)

    fun isIpAddress(rawHost: String): Boolean {
        var h = cleanHost(rawHost).lowercase()
        if (h.startsWith("[")) {
            val closeBracket = h.indexOf(']')
            if (closeBracket != -1) {
                h = h.substring(1, closeBracket)
            } else {
                return false
            }
        } else if (!h.contains("::") && h.count { it == ':' } == 1) {
            h = h.substringBefore(':')
        }

        val parts = h.split('.')
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true }) {
            return true
        }

        if (h.count { it == ':' } < 2) return false
        if (h.split("::").size > 2) return false
        if (h.startsWith(":") && !h.startsWith("::")) return false
        if (h.endsWith(":") && !h.endsWith("::")) return false

        val groups = h.split(':')
        if (groups.size > 8) return false
        if (!h.contains("::") && groups.size != 8) return false
        val nonEmptyGroups = groups.filter { it.isNotEmpty() }
        return nonEmptyGroups.isNotEmpty() && nonEmptyGroups.all { IPV6_GROUP_REGEX.matches(it) }
    }

    fun cleanHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = raw.trim()
        cleaned = cleaned.replace(Regex("""^(?:https?:)?//""", RegexOption.IGNORE_CASE), "")
        val slashIdx = cleaned.indexOf('/')
        if (slashIdx != -1) {
            cleaned = cleaned.substring(0, slashIdx)
        }
        val atIdx = cleaned.indexOf('@')
        if (atIdx != -1) {
            cleaned = cleaned.substring(atIdx + 1)
        }
        val hashIdx = cleaned.indexOf('#')
        if (hashIdx != -1) {
            cleaned = cleaned.substring(0, hashIdx)
        }
        val qIdx = cleaned.indexOf('?')
        if (qIdx != -1) {
            cleaned = cleaned.substring(0, qIdx)
        }
        return cleaned.trim()
    }

    fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        return HOSTNAME_REGEX.matches(host)
    }

    private val KNOWN_P2P_SUFFIXES = listOf(
        ".szbdyd.com",
        ".mountaintoys.cn",
        ".nexusedgeio.com",
        ".ahdohpiechei.com",
        ".xycdn.com"
    )

    private val KNOWN_P2P_HOSTS = setOf(
        "upos-sz-mirror14b.bilivideo.com"
    )

    /**
     * 识别版权保护/DRM 加密流（Widevine/ChinaDRM/PSSH/UGC付费等）：
     * 这类流需要客户端底层硬件解密管线直接处理，BTR 本地代理无法解密，必须原生直连放行。
     */
    fun hasDrmSignal(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.contains("drm_tech_type") ||
                (lower.contains("drm_type=") && !lower.contains("drm_type=0")) ||
                lower.contains("bvc-drm") ||
                lower.contains("pssh") ||
                lower.contains("widevine") ||
                lower.contains("/drm/") ||
                lower.contains("has_drm=1") ||
                lower.contains("is_drm=1") ||
                lower.contains("drm=1")
    }

    /**
     * 识别番剧、电影、纪录片等剧集（PGC / OGV）媒体流
     */
    fun isEpisodeMediaUrl(url: String, path: String = ""): Boolean {
        if (url.isBlank() && path.isBlank()) return false
        val lowerUrl = url.lowercase()
        val lowerPath = (if (path.isNotBlank()) path else parseUri(url)?.path ?: "").lowercase()
        return lowerPath.contains("/bangumi/") ||
                lowerPath.contains("/ogv/") ||
                lowerPath.contains("/pgc/") ||
                lowerUrl.contains("ep_id=") ||
                lowerUrl.contains("epid=") ||
                lowerUrl.contains("season_id=") ||
                lowerUrl.contains("season_type=") ||
                lowerUrl.contains("scene=ogv") ||
                lowerUrl.contains("scene=pgc") ||
                lowerUrl.contains("business=pgc") ||
                lowerUrl.contains("biz=pgc") ||
                lowerUrl.contains("is_pgc=1")
    }

    /**
     * 识别剧集试看片段（对齐 BTR 原版 unsupportedEpisode: is_preview=1，交给 B 站原生播放器处理）
     */
    fun isEpisodePreview(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.contains("is_preview=1") ||
                lower.contains("preview=1") ||
                lower.contains("trial=1")
    }

    fun hasMediaSignal(url: String): Boolean {
        val pathPart = url.substringBefore('?').substringBefore('#')
        return url.contains("bilivideo") ||
                url.contains("akamaized.net") ||
                url.contains("bstarstatic") ||
                url.contains("bstar") ||
                url.contains("szbdyd.com") ||
                url.contains("mountaintoys") ||
                url.contains("nexusedgeio") ||
                url.contains("ahdohpiechei") ||
                url.contains("xycdn.com") ||
                url.contains("hdslb.com") ||
                url.contains("mcdn.bili") ||
                url.contains("os=mcdn") ||
                pathPart.contains("/upgcxcode/") ||
                pathPart.contains("/v1/resource/") ||
                MEDIA_PATH_REGEX.containsMatchIn(pathPart)
    }

    internal fun isKnownP2pHost(hostname: String): Boolean {
        if (KNOWN_P2P_HOSTS.contains(hostname)) return true
        for (suffix in KNOWN_P2P_SUFFIXES) {
            if (hostname.endsWith(suffix, ignoreCase = true)) return true
        }
        if (hostname.startsWith("upos-", ignoreCase = true)) {
            val firstLabel = hostname.split(".").firstOrNull() ?: ""
            if (firstLabel.contains("302")) return true
        }
        return false
    }

    internal fun isMcdnHost(hostname: String): Boolean {
        val clean = cleanHost(hostname).lowercase().substringBefore(':')
        return XY_MCDN_REGEX.matches(clean) ||
                clean.endsWith(".mcdn.bilivideo.cn") ||
                clean.endsWith(".mcdn.bilivideo.com") ||
                clean.endsWith(".mcdn.bilivideo.net")
    }

    /**
     * 判断直播主机是否为慢速/劣质节点（对齐 realzza isSlowLiveHost）
     */
    fun isSlowLiveHost(hostValue: String, extra: String? = null, portHeuristic: Boolean = true): Boolean {
        if (hostValue.isBlank()) return false
        val clean = cleanHost(hostValue)
        val hostOnly = clean.substringBefore(':').lowercase()
        val port = clean.substringAfter(':', "").toIntOrNull() ?: -1
        if (isIpAddress(hostOnly) || isMcdnHost(hostOnly) || isKnownP2pHost(hostOnly)) {
            return true
        }
        if (portHeuristic && port != -1 && port != 80 && port != 443) {
            return true
        }
        if (!extra.isNullOrBlank() && (extra.contains("os=mcdn", ignoreCase = true) || extra.contains("pcdn", ignoreCase = true))) {
            return true
        }
        return false
    }

    /**
     * 清洗直播候选节点列表（对齐 realzza filterLiveUrlInfo）
     * 剥离住宅 PCDN、MCDN 和非标端口，保留原生官方 CDN
     */
    fun filterLiveCandidates(rawList: List<String>, portHeuristic: Boolean = true): List<String> {
        if (rawList.isEmpty()) return rawList
        val kept = rawList.filter { candidate ->
            val parsed = parseUri(candidate)
            val host = parsed?.host ?: cleanHost(candidate)
            !isSlowLiveHost(host, parsed?.queryParam?.invoke("extra") ?: candidate, portHeuristic)
        }
        return if (kept.isNotEmpty()) kept.distinct() else rawList.distinct()
    }

    /**
     * 识别中继/MCDN/P2P资源流（对齐 BTR Issue #47）：
     * 这类流的路径和鉴权签名由边缘中继专享，普通 UPOS 镜像无法解析，换 Host 必报 403。
     */
    fun isRelayUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val parsed = parseUri(url) ?: return false
        val host = cleanHost(parsed.host).lowercase().substringBefore(':')
        val path = parsed.path.lowercase()
        return isMcdnHost(host) ||
                host == "proxy-tf-all-ws.bilivideo.com" ||
                host.startsWith("proxy-tf-all-") ||
                path.startsWith("/v1/resource/") ||
                parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
    }

    fun isAkamaiHost(hostname: String): Boolean {
        val h = cleanHost(hostname).lowercase().substringBefore(':')
        return h.endsWith(".akamaized.net")
    }

    fun isBiliAkamaiHost(hostname: String): Boolean {
        val h = cleanHost(hostname).lowercase().substringBefore(':')
        return h.endsWith(".akamaized.net") && (h.startsWith("upos-") || h.contains("bili"))
    }

    fun isBiliCdnHost(hostname: String): Boolean {
        val h = cleanHost(hostname).lowercase().substringBefore(':')
        if (isMcdnHost(h) || isKnownP2pHost(h)) return false
        return h.endsWith(".bilivideo.com") ||
                h.endsWith(".bilivideo.cn") ||
                h.endsWith(".bilivideo.net") ||
                isBiliAkamaiHost(h)
    }

    fun isSchedulerHost(hostname: String): Boolean {
        val h = cleanHost(hostname).lowercase().substringBefore(':')
        return h.endsWith(".szbdyd.com") || h.endsWith(".mountaintoys.cn")
    }

    fun extractUrlDeadlineSeconds(url: String): Long {
        if (url.isBlank()) return 0L
        return parseUri(url)?.queryParam("deadline")?.toLongOrNull() ?: 0L
    }

    fun unwrapSmtcdnsUrl(url: String): String {
        return io.github.jh_mmm.biliaccelerator.btr.BtrLiveCore.unwrapProxyUrl(url.trim())
    }

    fun isBiliFamilyHost(hostname: String): Boolean {
        val h = cleanHost(hostname).lowercase().substringBefore(':')
        return isBiliCdnHost(h) ||
                isKnownP2pHost(h) ||
                isMcdnHost(h) ||
                h.endsWith(".bilibili.com") ||
                h.endsWith(".bstarstatic.com")
    }

    data class ParsedUri(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val queryParam: (String) -> String?,
        val replaceAuthority: (String) -> String,
        val proxyWrap: (String) -> String
    )

    fun parseUri(original: String): ParsedUri? {
        try {
            val u = Uri.parse(original)
            val host = u.host
            if (!host.isNullOrBlank()) {
                val scheme = u.scheme?.lowercase() ?: "https"
                if (scheme == "http" || scheme == "https") {
                    return ParsedUri(
                        scheme = scheme,
                        host = host,
                        port = u.port,
                        path = u.path ?: "",
                        queryParam = { key -> u.getQueryParameter(key) },
                        replaceAuthority = { newAuth ->
                            val cleanAuth = cleanHost(newAuth)
                            u.buildUpon().scheme("https").encodedAuthority(cleanAuth).build().toString()
                        },
                        proxyWrap = { proxyHost ->
                            val cleanProxy = cleanHost(proxyHost).substringBefore(':')
                            Uri.Builder()
                                .scheme("https")
                                .encodedAuthority(cleanProxy)
                                .path("/")
                                .appendQueryParameter("url", original)
                                .build().toString()
                        }
                    )
                }
            }
        } catch (_: Throwable) {}

        // JVM 单元测试回退（当 android.net.Uri 在非 Android 环境未被模拟时）
        return try {
            val trimmed = original.trim()
            if (trimmed.isEmpty()) return null
            val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
            val javaUri = URI(normalized)
            val scheme = javaUri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = javaUri.host ?: return null
            if (host.isBlank()) return null

            val rawQuery = javaUri.rawQuery
            ParsedUri(
                scheme = scheme,
                host = host,
                port = javaUri.port,
                path = javaUri.rawPath ?: "",
                queryParam = { key ->
                    if (rawQuery.isNullOrEmpty()) {
                        null
                    } else {
                        rawQuery.split("&").firstNotNullOfOrNull { pair ->
                            val eqIdx = pair.indexOf('=')
                            val k = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
                            if (k == key) {
                                val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
                                runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                            } else {
                                null
                            }
                        }
                    }
                },
                replaceAuthority = { newAuth ->
                    val cleanAuth = cleanHost(newAuth)
                    val rawPath = javaUri.rawPath?.takeIf { it.isNotEmpty() } ?: ""
                    val q = if (!rawQuery.isNullOrEmpty()) "?$rawQuery" else ""
                    val f = if (!javaUri.rawFragment.isNullOrEmpty()) "#${javaUri.rawFragment}" else ""
                    "https://$cleanAuth$rawPath$q$f"
                },
                proxyWrap = { proxyHost ->
                    val cleanProxy = cleanHost(proxyHost).substringBefore(':')
                    val encoded = URLEncoder.encode(normalized, "UTF-8").replace("+", "%20")
                    "https://$cleanProxy/?url=$encoded"
                }
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun isLiveMediaUrl(path: String, fullUrl: String = ""): Boolean {
        val lowerPath = path.lowercase()
        val lowerUrl = fullUrl.lowercase()
        return lowerPath.contains("/live-bvc/") ||
                lowerPath.contains("/live-stream/") ||
                lowerPath.contains("/live-flv/") ||
                lowerUrl.contains("/live-bvc/") ||
                lowerUrl.contains("/live-stream/") ||
                lowerUrl.contains("/live-flv/") ||
                lowerUrl.contains("gotcha") ||
                lowerUrl.contains("live.bilibili.com") ||
                lowerUrl.contains("live-play") ||
                lowerUrl.contains("live_id=")
    }

    private val AUDIO_REP_REGEX = Regex("""-(302\d{2}|3025\d)\.(m4s|mp4)""", RegexOption.IGNORE_CASE)
    private val VIDEO_REP_REGEX = Regex("""-(300\d{2}|301\d{2}|100\d{3})\.(m4s|mp4)""", RegexOption.IGNORE_CASE)

    fun isAudioTrack(url: String): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase()
        val pathPart = lowerUrl.substringBefore('?').substringBefore('#')
        val parsed = parseUri(url)
        val codecs = parsed?.queryParam("codecs")?.lowercase()
        if (codecs != null && (codecs.startsWith("mp4a") || codecs.startsWith("flac") || codecs.startsWith("ec-3") || codecs.startsWith("ac-4"))) {
            return true
        }
        val idParam = parsed?.queryParam("id") ?: parsed?.queryParam("rep_id")
        if (idParam != null && (idParam.matches(Regex("""^30(2\d\d|25\d)$""")) || idParam.equals("dolby", ignoreCase = true) || idParam.equals("flac", ignoreCase = true))) {
            return true
        }
        if (AUDIO_REP_REGEX.containsMatchIn(pathPart)) return true
        if (VIDEO_REP_REGEX.containsMatchIn(pathPart)) return false
        val fileName = pathPart.substringAfterLast('/')
        return fileName.startsWith("audio") || fileName.startsWith("dolby") || fileName.startsWith("flac")
    }

    fun shouldProxyMcdn(isMcdn: Boolean, path: String, config: AcceleratorConfig): Boolean {
        if (!isMcdn || !config.proxyMcdn) return false
        return when (config.mcdnStrategy.lowercase()) {
            "proxy-all" -> true
            "proxy-v1" -> path.startsWith("/v1/resource/")
            "replace" -> false
            else -> true
        }
    }

    fun resolveTargetHost(config: AcceleratorConfig): String {
        val cleanedTarget = cleanHost(config.targetHost)
        val targetHostOnly = cleanedTarget.substringBefore(':')
        val effectivePool = getEffectiveCdnPool(config)
        return if (isValidHost(cleanedTarget) && (isBiliCdnHost(targetHostOnly) || effectivePool.contains(targetHostOnly) || CANDIDATE_POOL.contains(targetHostOnly)) && !isAkamaiHost(targetHostOnly)) {
            targetHostOnly
        } else {
            config.customCdnPool
                .map { cleanHost(it).lowercase().substringBefore(':') }
                .firstOrNull { isValidHost(it) && !isAkamaiHost(it) }
                ?: CANDIDATE_POOL[0]
        }
    }

    fun resolveTargetHost(rawTarget: String): String {
        return resolveTargetHost(AcceleratorConfig(targetHost = rawTarget))
    }

    fun extractSegmentKey(path: String): String {
        if (path.isBlank()) return ""
        val fileName = path.substringAfterLast('/').trim()
        return fileName.take(96)
    }

    fun rewriteUrl(original: String, config: AcceleratorConfig): RewriteResult {
        if (!config.enabled || !hasMediaSignal(original)) {
            return noChange(original, "ignored")
        }

        val parsed = parseUri(original) ?: return noChange(original, "parse-failed")
        val hostname = parsed.host.lowercase()
        if (hostname == "127.0.0.1" || hostname == "localhost") {
            return noChange(original, "already-local-proxy")
        }
        val segKey = extractSegmentKey(parsed.path)
        val cleanProxyHost = cleanHost(config.proxyHost).substringBefore(':').ifBlank { "proxy-tf-all-ws.bilivideo.com" }
        if (hostname == cleanProxyHost) {
            return noChange(original, "already-proxied", isMcdn = true)
        }

        // 0. 解包 .smtcdns.net/.com 商业中继代理 URL（兼容多级子域与点播/直播）
        val unwrappedProxy = unwrapSmtcdnsUrl(original)
        if (unwrappedProxy.isNotBlank() && unwrappedProxy != original) {
            val unwrappedUri = parseUri(unwrappedProxy)
            val unwrappedHost = unwrappedUri?.host?.lowercase() ?: ""
            if (unwrappedUri != null && unwrappedHost.isNotBlank()) {
                val unwrappedSegKey = extractSegmentKey(unwrappedUri.path)
                val innerRes = rewriteUrl(unwrappedProxy, config)
                if (isLiveMediaUrl(unwrappedUri.path, unwrappedProxy)) {
                    val isUnwrappedCleanLive = !isIpAddress(unwrappedHost) &&
                            !isKnownP2pHost(unwrappedHost) &&
                            !isMcdnHost(unwrappedHost)
                    return RewriteResult(
                        changed = true,
                        originalUrl = original,
                        finalUrl = if (innerRes.changed) innerRes.finalUrl else unwrappedProxy,
                        originalHost = hostname,
                        targetHost = if (innerRes.changed) innerRes.targetHost else unwrappedHost,
                        reason = if (innerRes.changed) innerRes.reason else if (isUnwrappedCleanLive) "live-smtcdns-unwrap" else "live-pcdn-detected",
                        isPcdn = true,
                        isMcdn = innerRes.isMcdn,
                        segmentKey = unwrappedSegKey
                    )
                }
                return RewriteResult(
                    changed = true,
                    originalUrl = original,
                    finalUrl = if (innerRes.changed) innerRes.finalUrl else unwrappedProxy,
                    originalHost = hostname,
                    targetHost = if (innerRes.changed) innerRes.targetHost else unwrappedHost,
                    reason = if (innerRes.changed) innerRes.reason else "smtcdns-unwrap",
                    isPcdn = true,
                    isMcdn = innerRes.isMcdn,
                    segmentKey = unwrappedSegKey
                )
            }
        }

        val isLive = isLiveMediaUrl(parsed.path, original)
        val port = parsed.port
        val isBiliFamily = isBiliFamilyHost(hostname)
        val hasBiliMediaSignature = parsed.path.contains("/upgcxcode/") ||
                parsed.path.contains("/v1/resource/") ||
                parsed.queryParam("xy_usource") != null ||
                parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true

        val isIp = isIpAddress(hostname)
        val hasNonDefaultPort = config.portHeuristic && port != -1 && port != 80 && port != 443
        val queryMcdn = parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
        val explicitMcdnHost = isMcdnHost(hostname)
        // 对齐 realzza rewrite.js: 仅显式 *.mcdn.* 域名属于 MCDN 代理目标；裸 os=mcdn 归入 PCDN 拦截
        val isMcdn = explicitMcdnHost
        val isPcdn = (isIp && (hasBiliMediaSignature || isKnownP2pHost(hostname) || isLive)) ||
                isKnownP2pHost(hostname) ||
                queryMcdn ||
                (hasNonDefaultPort && (isBiliFamily || hasBiliMediaSignature || isLive))

        // 1. szbdyd / mountaintoys 调度器：直接恢复 xy_usource 参数所携带的真实原生 CDN 域名（点播与直播通用）
        val isScheduler = isSchedulerHost(hostname)
        if (isScheduler) {
            val rawSource = parsed.queryParam("xy_usource")
            val cleanedSource = cleanHost(rawSource)
            val hostOnly = cleanedSource.substringBefore(':')
            if (isValidHost(cleanedSource) && isBiliCdnHost(hostOnly)) {
                val rewritten = parsed.replaceAuthority(hostOnly)
                val baseReason = if (hostname.endsWith(".mountaintoys.cn", ignoreCase = true)) "mountaintoys-source" else "szbdyd-source"
                val reason = if (isLive) "live-$baseReason" else baseReason
                if (rewritten != original) {
                    return RewriteResult(
                        changed = true,
                        originalUrl = original,
                        finalUrl = rewritten,
                        originalHost = hostname,
                        targetHost = hostOnly,
                        reason = reason,
                        isPcdn = true,
                        isMcdn = false,
                        segmentKey = segKey
                    )
                } else {
                    return noChange(original, "ok", isPcdn = true, segmentKey = segKey)
                }
            }
        }

        // 直播流 PCDN/MCDN 检测：若非 szbdyd/mountaintoys 还原，标记其为直播 PCDN，绝不盲目改写至 VOD UPOS 镜像
        if (isLive) {
            return if (isPcdn || isMcdn) {
                noChange(original, "live-pcdn-detected", isPcdn = true, isMcdn = isMcdn, segmentKey = segKey)
            } else {
                noChange(original, "live-ok", isPcdn = false, isMcdn = false, segmentKey = segKey)
            }
        }

        // 关键防护：版权保护/DRM 加密流（Widevine/ChinaDRM/PSSH/UGC付费等）必须原生直连放行，严禁改写域名
        if (hasDrmSignal(original)) {
            return noChange(original, "drm-protected", isPcdn = false, isMcdn = false, segmentKey = segKey)
        }

        // 2. MCDN 节点处理：
        // - proxyMcdn=true 时按 proxy-all / proxy-v1 / replace 策略执行（replace 对 /v1/resource/ 安全放行以杜绝 403）
        // - proxyMcdn=false 时，非 /v1/resource/ 的 *.mcdn.* 节点在 blockPcdn 或 forceUpos 开启下同样替换为 UPOS；
        //   而 /v1/resource/ 路径因 UPOS 节点不支持并会直接 403，停用代理时保持直连放行
        val isV1Resource = parsed.path.startsWith("/v1/resource/", ignoreCase = true)
        if (isMcdn) {
            if (config.proxyMcdn) {
                val shouldProxy = shouldProxyMcdn(true, parsed.path, config)
                if (shouldProxy) {
                    val rewritten = parsed.proxyWrap(cleanProxyHost)
                    if (rewritten != original) {
                        return RewriteResult(
                            changed = true,
                            originalUrl = original,
                            finalUrl = rewritten,
                            originalHost = hostname,
                            targetHost = cleanProxyHost,
                            reason = "mcdn-proxy",
                            isPcdn = false,
                            isMcdn = true,
                            segmentKey = segKey
                        )
                    } else {
                        return noChange(original, "ok", isMcdn = true, segmentKey = segKey)
                    }
                } else {
                    if (isV1Resource) {
                        return noChange(original, "mcdn-v1-pass", isPcdn = isPcdn, isMcdn = true, segmentKey = segKey)
                    }
                    val target = resolveTargetHost(config)
                    val rewritten = parsed.replaceAuthority(target)
                    if (rewritten != original) {
                        return RewriteResult(
                            changed = true,
                            originalUrl = original,
                            finalUrl = rewritten,
                            originalHost = hostname,
                            targetHost = target,
                            reason = "mcdn-host",
                            isPcdn = false,
                            isMcdn = true,
                            segmentKey = segKey
                        )
                    } else {
                        return noChange(original, "ok", isMcdn = true, segmentKey = segKey)
                    }
                }
            } else {
                if (isV1Resource) {
                    return noChange(original, "mcdn-v1-pass", isPcdn = false, isMcdn = true, segmentKey = segKey)
                }
                if (config.blockPcdn || config.forceUpos) {
                    val target = resolveTargetHost(config)
                    val rewritten = parsed.replaceAuthority(target)
                    if (rewritten != original) {
                        return RewriteResult(
                            changed = true,
                            originalUrl = original,
                            finalUrl = rewritten,
                            originalHost = hostname,
                            targetHost = target,
                            reason = "mcdn-host",
                            isPcdn = isPcdn,
                            isMcdn = true,
                            segmentKey = segKey
                        )
                    }
                }
                return noChange(original, "ok", isPcdn = isPcdn, isMcdn = true, segmentKey = segKey)
            }
        }

        // /v1/resource/ 路径依赖边缘中继鉴权，普通 UPOS 节点不支持并会直接 403；即使非 *.mcdn.* 域名也禁止改写为普通 UPOS
        if (isV1Resource) {
            return noChange(original, "mcdn-v1-pass", isPcdn = isPcdn, isMcdn = false, segmentKey = segKey)
        }

        // 3. Akamai 节点处理：默认放行官方 Akamai 鉴权线路；杜绝非 BTR 模式下直接改写 Host 导致 403 播放失败
        val isAkamai = isAkamaiHost(hostname)
        val isBstar = hostname.endsWith(".bstarstatic.com", ignoreCase = true)
        val isUposAkamai = isBiliAkamaiHost(hostname)

        // 关键防护（对齐 realzza 与 BTR）：Akamai HMAC 鉴权签名与 UPOS 完全不兼容。
        // 在非 BTR 模式下（无法通过 BTR 多源对冲兜底），严禁直接替换 Host 为 UPOS，强制放行以杜绝 HTTP 403 播放失败。
        if (isAkamai && (!config.enableBtr || !config.rewriteAkamai)) {
            return noChange(original, "akamai-pass", isPcdn = false, isMcdn = false, segmentKey = segKey)
        }

        // 4. PCDN 拦截 / Force 模式 / Akamai 改写
        val shouldRewrite = (isPcdn && config.blockPcdn) ||
                (isAkamai && config.rewriteAkamai && config.enableBtr) ||
                (config.forceUpos && (isBiliCdnHost(hostname) || isUposAkamai) && !isAkamai && !isBstar)

        if (shouldRewrite) {
            val target = resolveTargetHost(config)
            val rewritten = parsed.replaceAuthority(target)
            if (rewritten != original) {
                val reason = when {
                    isAkamai -> "akamai-redirect"
                    isPcdn -> "pcdn-host"
                    else -> "force-upos"
                }
                return RewriteResult(
                    changed = true,
                    originalUrl = original,
                    finalUrl = rewritten,
                    originalHost = hostname,
                    targetHost = target,
                    reason = reason,
                    isPcdn = isPcdn,
                    isMcdn = false,
                    segmentKey = segKey
                )
            } else {
                return noChange(original, "ok", isPcdn = isPcdn, isMcdn = false, segmentKey = segKey)
            }
        }

        return noChange(original, "ok", isPcdn = isPcdn, isMcdn = false, segmentKey = segKey)
    }

    private fun isCleanUposDonor(url: String, requireUpgcxcode: Boolean = false): Boolean {
        val parsed = parseUri(url) ?: return false
        val host = cleanHost(parsed.host).lowercase().substringBefore(':')
        if (host.isBlank() || isIpAddress(host) || isKnownP2pHost(host) || isMcdnHost(host) ||
            isAkamaiHost(host) || isSchedulerHost(host) || host.endsWith(".bstarstatic.com")
        ) {
            return false
        }
        if (parsed.port != -1 && parsed.port != 80 && parsed.port != 443) return false
        if (isRelayUrl(url) || hasDrmSignal(url) || isEpisodePreview(url)) return false
        if (requireUpgcxcode && !parsed.path.contains("/upgcxcode/")) return false
        return isBiliCdnHost(host)
    }

    fun filterAndFillBackupUrls(
        rawList: List<String>,
        config: AcceleratorConfig,
        onRewrite: ((RewriteResult) -> Unit)? = null
    ): List<String> {
        if (!config.enabled) return rawList.distinct()
        val isLiveList = rawList.any { isLiveMediaUrl("", it) }
        val resolvedTarget = resolveTargetHost(config)
        val filtered = mutableListOf<String>()

        if (isLiveList) {
            // 直播流候选过滤：
            // 1. 若带 xy_usource 则恢复为官方源站
            // 2. 剥离 PCDN / MCDN / 高位端口 / 裸 IP 节点，保留原生官方 CDN (gotcha / live-bvc 等)
            for (url in rawList) {
                val res = rewriteUrl(url, config)
                if (res.changed) {
                    onRewrite?.invoke(res)
                    filtered.add(res.finalUrl)
                } else if (!res.isPcdn && !res.isMcdn) {
                    filtered.add(url)
                }
            }
            // 容错兜底：若所有节点均被判定为 PCDN（极端情况），保留原列表避免直播黑屏
            val cleaned = filterLiveCandidates(filtered, config.portHeuristic)
            return if (cleaned.isNotEmpty()) cleaned else (if (filtered.isNotEmpty()) filtered.distinct() else rawList.distinct())
        }

        // VOD 点播流候选过滤与补全
        for (url in rawList) {
            val res = rewriteUrl(url, config)
            if (res.changed) {
                onRewrite?.invoke(res)
                filtered.add(res.finalUrl)
                // 对齐 BTR：在 BTR 开启且改写 Akamai 为 UPOS 时保留原签名 Akamai 地址作为 403 兜底
                if (res.reason == "akamai-redirect") {
                    filtered.add(url)
                }
            } else if (!res.isPcdn || !config.blockPcdn) {
                filtered.add(url)
            }
        }

        // 优先从原始列表中挑选真正干净的官方 UPOS URL 作为 Donor；仅当原始列表全为 /upgcxcode/ PCDN 并已被改写为 UPOS 时才从已改写列表补充
        val donorUrl = rawList.firstOrNull { isCleanUposDonor(it) }
            ?: filtered.firstOrNull { isCleanUposDonor(it, requireUpgcxcode = true) }
        if (filtered.distinct().size <= 1 && donorUrl != null) {
            val existingHosts = filtered.mapNotNull { parseUri(it)?.host?.lowercase()?.substringBefore(':') }.toSet()
            val pool = getEffectiveCdnPool(config)
            val candidates = pool.filter {
                !it.equals(resolvedTarget, ignoreCase = true) && !existingHosts.contains(it.lowercase())
            }
            val fillCount = config.btrCdnPoolSize.coerceIn(2, 6)
            for (candidate in candidates.take(fillCount)) {
                val backupRes = rewriteUrl(
                    donorUrl,
                    config.copy(targetHost = candidate, forceUpos = true)
                )
                if (backupRes.changed) {
                    filtered.add(backupRes.finalUrl)
                }
            }
        }

        return filtered.distinct()
    }

    fun noChange(
        url: String,
        reason: String,
        isPcdn: Boolean = false,
        isMcdn: Boolean = false,
        host: String? = null,
        segmentKey: String = ""
    ): RewriteResult {
        val resolvedHost = host ?: (parseUri(url)?.host?.lowercase() ?: "")
        return RewriteResult(
            changed = false,
            originalUrl = url,
            finalUrl = url,
            originalHost = resolvedHost,
            targetHost = resolvedHost,
            reason = reason,
            isPcdn = isPcdn,
            isMcdn = isMcdn,
            segmentKey = segmentKey
        )
    }
}
