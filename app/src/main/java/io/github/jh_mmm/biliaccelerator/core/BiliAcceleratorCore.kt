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
    val customCdnPool: List<String> = emptyList()
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

    val MAINLAND_CANDIDATE_POOL = listOf(
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorcoso1.bilivideo.com",
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com",
        "upos-tf-all-hw.bilivideo.com",
        "upos-tf-all-tx.bilivideo.com",
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirrorhwov.bilivideo.com"
    )

    val OVERSEAS_CANDIDATE_POOL = listOf(
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirrorhwov.bilivideo.com",
        "cn-hk-eq-01-01.bilivideo.com",
        "cn-hk-eq-01-03.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-tf-all-hw.bilivideo.com",
        "upos-tf-all-tx.bilivideo.com"
    )

    val CANDIDATE_POOL = MAINLAND_CANDIDATE_POOL
    val DEFAULT_CDN_POOL = CANDIDATE_POOL

    /**
     * 判断当前系统是否处于海外环境（根据时区与语言环境自适应加权）
     */
    fun isOverseasEnvironment(): Boolean {
        return try {
            val tzId = java.util.TimeZone.getDefault().id.lowercase()
            val country = java.util.Locale.getDefault().country.uppercase()
            val isMainlandTz = tzId.contains("shanghai") || tzId.contains("chongqing") ||
                    tzId.contains("harbin") || tzId.contains("urumqi") || tzId == "prc" || tzId == "cst"
            val isMainlandLocale = country == "CN"
            !isMainlandTz || (!isMainlandLocale && !isMainlandTz)
        } catch (_: Throwable) {
            false
        }
    }

    fun getEffectiveCdnPool(config: AcceleratorConfig): List<String> {
        val userList = config.customCdnPool
            .map { cleanHost(it).lowercase() }
            .filter { isValidHost(it) && !isAkamaiHost(it) }
            .distinct()
        if (userList.isNotEmpty()) return userList.take(32)

        return if (isOverseasEnvironment()) OVERSEAS_CANDIDATE_POOL else MAINLAND_CANDIDATE_POOL
    }

    private val HOSTNAME_REGEX = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(:[0-9]{1,5})?$""")
    private val MEDIA_PATH_REGEX = Regex("""\.(m4s|mp4|flv|m3u8)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val IPV6_GROUP_REGEX = Regex("""^[0-9a-fA-F]{1,4}$""")

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
                lower.contains("has_drm=1")
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
        return hostname.endsWith(".mcdn.bilivideo.cn", ignoreCase = true) ||
                hostname.endsWith(".mcdn.bilivideo.com", ignoreCase = true) ||
                hostname.endsWith(".mcdn.bilivideo.net", ignoreCase = true)
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
        return h.endsWith(".bilivideo.com") ||
                h.endsWith(".bilivideo.cn") ||
                h.endsWith(".bilivideo.net") ||
                isBiliAkamaiHost(h)
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
                lowerUrl.contains("gotcha") ||
                lowerUrl.contains("live.bilibili.com") ||
                lowerUrl.contains("live-play") ||
                lowerUrl.contains("live_id=")
    }

    private val AUDIO_REP_REGEX = Regex("""-(302\d{2}|3025\d)\.(m4s|mp4)""", RegexOption.IGNORE_CASE)

    fun isAudioTrack(url: String): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase()
        val pathPart = lowerUrl.substringBefore('?').substringBefore('#')
        if (pathPart.contains("audio") || pathPart.contains("sound") || pathPart.contains("dolby") || pathPart.contains("flac") || pathPart.contains("hires")) return true
        if (AUDIO_REP_REGEX.containsMatchIn(pathPart)) return true
        val parsed = parseUri(url)
        val idParam = parsed?.queryParam("id") ?: parsed?.queryParam("rep_id")
        if (idParam != null && (idParam.matches(Regex("""^30(2\d\d|25\d)$""")) || idParam.contains("audio") || idParam.contains("flac"))) return true
        val codecs = parsed?.queryParam("codecs")?.lowercase()
        if (codecs != null && (codecs.startsWith("mp4a") || codecs.startsWith("flac") || codecs.startsWith("ec-3") || codecs.startsWith("ac-4"))) return true
        return false
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
            effectivePool.firstOrNull() ?: CANDIDATE_POOL[0]
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
        val segKey = extractSegmentKey(parsed.path)
        val cleanProxyHost = cleanHost(config.proxyHost).substringBefore(':').ifBlank { "proxy-tf-all-ws.bilivideo.com" }
        if (hostname == cleanProxyHost) {
            return noChange(original, "already-proxied", isMcdn = true)
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
        val isMcdn = isMcdnHost(hostname) || queryMcdn
        val isPcdn = (isIp && (hasBiliMediaSignature || isKnownP2pHost(hostname) || isLive)) ||
                isKnownP2pHost(hostname) ||
                (hasNonDefaultPort && (isBiliFamily || hasBiliMediaSignature || isLive))

        // 1. szbdyd / mountaintoys 调度器：直接恢复 xy_usource 参数所携带的真实原生 CDN 域名（点播与直播通用）
        val isScheduler = hostname.endsWith(".szbdyd.com", ignoreCase = true) ||
                hostname.endsWith(".mountaintoys.cn", ignoreCase = true)
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

        // 2. MCDN 节点处理：支持 proxy-all / proxy-v1 / replace 多档策略颗粒度
        if (isMcdn && config.proxyMcdn) {
            val shouldProxy = shouldProxyMcdn(isMcdn, parsed.path, config)
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
            } else if (config.mcdnStrategy.equals("replace", ignoreCase = true) || config.forceUpos) {
                // 关键防护（对齐 BTR Issue #47）：
                // 若 MCDN 路径以 /v1/resource/ 开头，普通 UPOS 节点没有中继资源，直接换 Host 必报 HTTP 403！
                // 遇到此类地址必须强制降级走官方 CDN 中继代理 (proxyWrap)，杜绝 403 熔断。
                if (parsed.path.startsWith("/v1/resource/")) {
                    val rewritten = parsed.proxyWrap(cleanProxyHost)
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
        }

        // 3. Akamai 节点处理：默认放行官方 Akamai 鉴权线路；仅在 rewriteAkamai 开启时重定向至首选 UPOS
        val isAkamai = isAkamaiHost(hostname)
        val isBstar = hostname.endsWith(".bstarstatic.com", ignoreCase = true)
        val isUposAkamai = isBiliAkamaiHost(hostname)

        if (isAkamai && !config.rewriteAkamai && !config.forceUpos) {
            return noChange(original, "akamai-pass", isPcdn = false, isMcdn = false, segmentKey = segKey)
        }

        // 4. PCDN 拦截 / Force 模式 / Akamai 改写
        val shouldRewrite = (isPcdn && config.blockPcdn) ||
                (isAkamai && (config.rewriteAkamai || config.forceUpos)) ||
                (config.forceUpos && (isBiliCdnHost(hostname) || isUposAkamai) && !isBstar)

        if (shouldRewrite) {
            val target = resolveTargetHost(config)
            val rewritten = parsed.replaceAuthority(target)
            if (rewritten != original) {
                val reason = when {
                    isAkamai -> "akamai-redirect"
                    isPcdn -> "pcdn-host"
                    isMcdn -> "mcdn-host"
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
                    isMcdn = isMcdn,
                    segmentKey = segKey
                )
            } else {
                return noChange(original, "ok", isPcdn = isPcdn, isMcdn = isMcdn, segmentKey = segKey)
            }
        }

        return noChange(original, "ok", isPcdn = isPcdn, isMcdn = isMcdn, segmentKey = segKey)
    }

    fun filterAndFillBackupUrls(
        rawList: List<String>,
        config: AcceleratorConfig,
        onRewrite: ((RewriteResult) -> Unit)? = null
    ): List<String> {
        if (!config.enabled) return rawList.distinct()
        val isLiveList = rawList.any { isLiveMediaUrl("", it) }
        val target = config.targetHost
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
            return if (filtered.isNotEmpty()) filtered.distinct() else rawList.distinct()
        }

        // VOD 点播流候选过滤与补全
        for (url in rawList) {
            val res = rewriteUrl(url, config)
            if (res.changed) {
                onRewrite?.invoke(res)
                filtered.add(res.finalUrl)
            } else if (!res.isPcdn || !config.blockPcdn) {
                filtered.add(url)
            }
        }

        val sampleUrl = rawList.firstOrNull()
        if (filtered.isEmpty() && sampleUrl != null) {
            // 对齐 BTR Issue #47：中继/MCDN 地址、DRM 加密地址与 Akamai 地址严禁作为 Donor 生成普通 UPOS 镜像
            if (!isRelayUrl(sampleUrl) && !isAkamaiHost(sampleUrl) && !hasDrmSignal(sampleUrl)) {
                val candidates = CANDIDATE_POOL.filter { it != target }
                for (candidate in candidates.take(3)) {
                    val backupRes = rewriteUrl(
                        sampleUrl,
                        config.copy(targetHost = candidate, forceUpos = true)
                    )
                    if (backupRes.changed) {
                        filtered.add(backupRes.finalUrl)
                    }
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
