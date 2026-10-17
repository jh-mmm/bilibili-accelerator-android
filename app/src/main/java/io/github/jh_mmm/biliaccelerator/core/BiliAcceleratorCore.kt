package io.github.jh_mmm.biliaccelerator.core

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
    val segmentKey: String = ""
)

data class AcceleratorConfig(
    val enabled: Boolean = true,
    val targetHost: String = "upos-sz-mirrorcos.bilivideo.com",
    val proxyHost: String = "proxy-tf-all-ws.bilivideo.com",
    val blockPcdn: Boolean = true,
    val proxyMcdn: Boolean = true,
    val forceUpos: Boolean = false,
    val portHeuristic: Boolean = true,
    val enableMossHook: Boolean = false
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

    fun isTargetPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val p = packageName.trim().lowercase()
        if (TARGET_PACKAGES.contains(p)) return true
        if (p == MODULE_PACKAGE.lowercase()) return true
        return p.startsWith("tv.danmaku.bili") ||
                p.startsWith("com.bilibili.") ||
                p.startsWith("com.bstar.") ||
                p.contains(".bilibili.") ||
                p.contains("biliaccelerator")
    }

    val CANDIDATE_POOL = listOf(
        "upos-sz-mirrorcos.bilivideo.com",
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-tf-all-hw.bilivideo.com",
        "upos-tf-all-tx.bilivideo.com",
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirrorhwov.bilivideo.com"
    )

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
        ".ahdohpiechei.com"
    )

    private val KNOWN_P2P_HOSTS = setOf(
        "upos-sz-mirror14b.bilivideo.com"
    )

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

        if (isLiveMediaUrl(parsed.path, original)) {
            return noChange(original, "live-skip")
        }

        val port = parsed.port
        val isBiliFamily = isBiliFamilyHost(hostname)
        val hasBiliMediaSignature = parsed.path.contains("/upgcxcode/") ||
                parsed.path.contains("/v1/resource/") ||
                parsed.queryParam("xy_usource") != null ||
                parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true

        val isIp = isIpAddress(hostname)
        val hasNonDefaultPort = config.portHeuristic && port != -1 && port != 80 && port != 443
        val queryMcdn = parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
        val isMcdn = isMcdnHost(hostname)
        val isPcdn = (isIp && (hasBiliMediaSignature || isKnownP2pHost(hostname))) ||
                isKnownP2pHost(hostname) ||
                (hasNonDefaultPort && (isBiliFamily || hasBiliMediaSignature)) ||
                queryMcdn

        // 1. szbdyd / mountaintoys 调度器：直接恢复 xy_usource 参数所携带的真实原生 CDN 域名
        val isScheduler = hostname.endsWith(".szbdyd.com", ignoreCase = true) ||
                hostname.endsWith(".mountaintoys.cn", ignoreCase = true)
        if (isScheduler) {
            val rawSource = parsed.queryParam("xy_usource")
            val cleanedSource = cleanHost(rawSource)
            val hostOnly = cleanedSource.substringBefore(':')
            if (isValidHost(cleanedSource) && isBiliCdnHost(hostOnly)) {
                val rewritten = parsed.replaceAuthority(hostOnly)
                if (rewritten != original) {
                    return RewriteResult(
                        changed = true,
                        originalUrl = original,
                        finalUrl = rewritten,
                        originalHost = hostname,
                        targetHost = hostOnly,
                        reason = if (hostname.endsWith(".mountaintoys.cn", ignoreCase = true)) "mountaintoys-source" else "szbdyd-source",
                        isPcdn = true,
                        isMcdn = false,
                        segmentKey = segKey
                    )
                } else {
                    return noChange(original, "ok", isPcdn = true, segmentKey = segKey)
                }
            }
        }

        // 2. MCDN 节点代理
        if (isMcdn && config.proxyMcdn) {
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
        }

        // 3. PCDN 拦截 / Force 模式统一重定向到优质 UPOS 镜像
        val isAkamai = hostname.endsWith(".akamaized.net", ignoreCase = true)
        val isBstar = hostname.endsWith(".bstarstatic.com", ignoreCase = true)
        val isUposAkamai = isBiliAkamaiHost(hostname)
        val shouldRewrite = (isPcdn && config.blockPcdn) ||
                (isMcdn && !config.proxyMcdn) ||
                (config.forceUpos && (isBiliCdnHost(hostname) || isUposAkamai) && !isBstar)

        if (shouldRewrite) {
            val cleanedTarget = cleanHost(config.targetHost)
            val targetHostOnly = cleanedTarget.substringBefore(':')
            val target = if (isValidHost(cleanedTarget) && (isBiliCdnHost(targetHostOnly) || CANDIDATE_POOL.contains(targetHostOnly))) {
                targetHostOnly
            } else {
                CANDIDATE_POOL[0]
            }
            val rewritten = parsed.replaceAuthority(target)
            if (rewritten != original) {
                return RewriteResult(
                    changed = true,
                    originalUrl = original,
                    finalUrl = rewritten,
                    originalHost = hostname,
                    targetHost = target,
                    reason = if (isPcdn) "pcdn-host" else if (isMcdn) "mcdn-host" else "force-upos",
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
        val target = config.targetHost
        val filtered = mutableListOf<String>()

        for (url in rawList) {
            val res = rewriteUrl(url, config)
            if (res.changed) {
                onRewrite?.invoke(res)
                filtered.add(res.finalUrl)
            } else if (!res.isPcdn) {
                filtered.add(url)
            }
        }

        val sampleUrl = rawList.firstOrNull()
        if (filtered.isEmpty() && sampleUrl != null) {
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
