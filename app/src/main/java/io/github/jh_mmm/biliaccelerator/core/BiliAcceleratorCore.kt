package io.github.jh_mmm.biliaccelerator.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

data class LiveFilterResult(
    val changed: Boolean,
    val json: String,
    val rewrites: List<RewriteResult>
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
        "com.bilibili.app.blue"
    )

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
    private val MCDN_QUERY_REGEX = Regex("""(?:^|[?&])os=mcdn(?:&|$)""", RegexOption.IGNORE_CASE)

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
            // 单个冒号视作 host:port，剥离端口后再判断是否为 IPv4
            h = h.substringBefore(':')
        }

        val parts = h.split('.')
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true }) {
            return true
        }

        // 合法的 IPv6 地址至少包含两个冒号，且最多出现一次 "::"
        if (h.count { it == ':' } < 2) return false
        if (h.split("::").size > 2) return false
        if (h.startsWith(":") && !h.startsWith("::")) return false
        if (h.endsWith(":") && !h.endsWith("::")) return false

        val groups = h.split(':')
        if (groups.size > 8) return false
        val nonEmptyGroups = groups.filter { it.isNotEmpty() }
        return nonEmptyGroups.isNotEmpty() && nonEmptyGroups.all { IPV6_GROUP_REGEX.matches(it) }
    }

    fun cleanHost(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var cleaned = raw.trim()
        cleaned = cleaned.replace(Regex("""^https?://""", RegexOption.IGNORE_CASE), "")
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

    fun isMediaUrl(parsed: ParsedUri): Boolean {
        val p = parsed.path
        return p.startsWith("/upgcxcode/") ||
                p.startsWith("/v1/resource/") ||
                p.contains("/upgcxcode/") ||
                p.contains("/v1/resource/") ||
                MEDIA_PATH_REGEX.containsMatchIn(p)
    }

    fun isMediaUrl(url: String): Boolean {
        val parsed = parseUri(url) ?: return false
        return isMediaUrl(parsed)
    }

    internal fun isKnownP2pHost(hostname: String): Boolean {
        if (KNOWN_P2P_HOSTS.contains(hostname)) return true
        for (suffix in KNOWN_P2P_SUFFIXES) {
            if (hostname.endsWith(suffix, ignoreCase = true)) return true
        }
        // 302 redirect residential P2P nodes (e.g. upos-sz-302ppio)
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

    /**
     * 统一使用标准 java.net.URI 解析与构建，确保 Android 真机与 JVM 单元测试执行完全相同的代码路径，
     * 消除双分支在编码（如空格编码为 %20 而非 +）、端口剥离与空路径上的行为差异。
     */
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

    /**
     * 与上游保持一致：仅将 /live-bvc/ 判定为直播流媒体路径。
     * 避免宽泛匹配 /live/ 或 gotcha 误伤路径恰好含 live 字样的点播视频。
     */
    fun isLiveMediaUrl(path: String): Boolean {
        return path.lowercase().contains("/live-bvc/")
    }

    /**
     * 移植自上游 isSlowLiveHost：识别直播候选主机列表中的 PCDN / MCDN / 慢速节点。
     */
    fun isSlowLiveHost(hostOrUrl: String, extra: String? = null, config: AcceleratorConfig): Boolean {
        val raw = hostOrUrl.trim()
        if (raw.isEmpty()) return false
        val normalized = if (raw.contains("://")) raw else "https://${raw.removePrefix("//")}"
        val parsed = parseUri(normalized) ?: return false
        val hostname = parsed.host.lowercase()
        val hasNonDefaultPort = config.portHeuristic && parsed.port != -1 && parsed.port != 80 && parsed.port != 443
        val queryMcdn = parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
        val extraMcdn = extra != null && MCDN_QUERY_REGEX.containsMatchIn(extra)

        return isIpAddress(hostname) ||
                isMcdnHost(hostname) ||
                isKnownP2pHost(hostname) ||
                hasNonDefaultPort ||
                queryMcdn ||
                extraMcdn
    }

    /**
     * 移植自上游 rewrite.js filterLiveUrlInfo：
     * 过滤直播 getRoomPlayInfo 响应 JSON 中 url_info: [{host, extra}] 里的 PCDN/MCDN 慢速候选主机，
     * 仅保留官方直播 CDN 节点，且绝不删空最后一个可用主机。
     */
    fun filterLiveUrlInfo(jsonText: String, config: AcceleratorConfig): LiveFilterResult {
        if (!config.enabled || !config.blockPcdn || !jsonText.contains("url_info")) {
            return LiveFilterResult(changed = false, json = jsonText, rewrites = emptyList())
        }
        return try {
            val root = JsonParser.parseString(jsonText)
            val rewrites = mutableListOf<RewriteResult>()
            val changed = filterLiveUrlInfoElement(root, config, rewrites, depth = 0)
            if (changed) {
                LiveFilterResult(changed = true, json = Gson().toJson(root), rewrites = rewrites)
            } else {
                LiveFilterResult(changed = false, json = jsonText, rewrites = emptyList())
            }
        } catch (_: Throwable) {
            LiveFilterResult(changed = false, json = jsonText, rewrites = emptyList())
        }
    }

    fun filterLiveUrlInfoElement(
        payload: JsonElement?,
        config: AcceleratorConfig,
        rewrites: MutableList<RewriteResult>,
        depth: Int = 0
    ): Boolean {
        if (!config.enabled || !config.blockPcdn || payload == null || depth > 16) {
            return false
        }
        var changed = false
        if (payload.isJsonObject) {
            val obj = payload.asJsonObject
            val urlInfoEl = obj.get("url_info")
            if (urlInfoEl != null && urlInfoEl.isJsonArray) {
                val list = urlInfoEl.asJsonArray
                val items = (0 until list.size()).mapNotNull { i ->
                    list.get(i)?.takeIf { it.isJsonObject }?.asJsonObject
                }
                if (items.size > 1 && items.size == list.size() && items.all { it.get("host")?.isJsonPrimitive == true }) {
                    val kept = items.filter { item ->
                        val hostVal = item.get("host")?.asString ?: ""
                        val extraVal = item.get("extra")?.takeIf { it.isJsonPrimitive }?.asString
                        !isSlowLiveHost(hostVal, extraVal, config)
                    }
                    if (kept.isNotEmpty() && kept.size < items.size) {
                        val fallbackHostRaw = kept[0].get("host")?.asString ?: ""
                        val fallbackHostClean = cleanHost(fallbackHostRaw).substringBefore(':').lowercase()
                        for (item in items) {
                            if (!kept.contains(item)) {
                                val origHostRaw = item.get("host")?.asString ?: ""
                                val origHostClean = cleanHost(origHostRaw).substringBefore(':').lowercase()
                                rewrites.add(
                                    RewriteResult(
                                        changed = true,
                                        originalUrl = origHostRaw,
                                        finalUrl = fallbackHostRaw,
                                        originalHost = origHostClean,
                                        targetHost = fallbackHostClean,
                                        reason = "live-pcdn-filter",
                                        isPcdn = true,
                                        isMcdn = isMcdnHost(origHostClean),
                                        segmentKey = "live-url-info:$origHostClean"
                                    )
                                )
                            }
                        }
                        val newArr = JsonArray()
                        kept.forEach { newArr.add(it) }
                        obj.add("url_info", newArr)
                        changed = true
                    }
                }
            }
            for ((_, child) in obj.entrySet()) {
                if (filterLiveUrlInfoElement(child, config, rewrites, depth + 1)) {
                    changed = true
                }
            }
        } else if (payload.isJsonArray) {
            val arr = payload.asJsonArray
            for (i in 0 until arr.size()) {
                if (filterLiveUrlInfoElement(arr.get(i), config, rewrites, depth + 1)) {
                    changed = true
                }
            }
        }
        return changed
    }

    internal fun extractSegmentKey(path: String): String {
        if (path.isBlank()) return ""
        val fileName = path.substringAfterLast('/').trim()
        return fileName.take(96)
    }

    /**
     * 判定 URL 是否为 B 站签发的可用干净官方 CDN 节点（非 PCDN、非 MCDN、非调度器）。
     * 供 PlayerHook 的 promoteIssued 机制与 RouteEngine 优先提权使用。
     */
    fun isUsableIssuedUrl(url: String, config: AcceleratorConfig): Boolean {
        val parsed = parseUri(url) ?: return false
        if (!isMediaUrl(parsed)) return false
        if (isLiveMediaUrl(parsed.path)) {
            return !isSlowLiveHost(url, null, config)
        }
        val hostname = parsed.host.lowercase()
        val isScheduler = hostname.endsWith(".szbdyd.com") || hostname.endsWith(".mountaintoys.cn")
        if (isScheduler) return false
        val isMcdn = isMcdnHost(hostname)
        if (isMcdn) return false

        val port = parsed.port
        val isBiliFamily = isBiliFamilyHost(hostname)
        val hasBiliMediaSignature = parsed.path.contains("/upgcxcode/") ||
                parsed.path.contains("/v1/resource/") ||
                parsed.queryParam("xy_usource") != null ||
                parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
        val isIp = isIpAddress(hostname)
        val hasNonDefaultPort = config.portHeuristic && port != -1 && port != 80 && port != 443
        val queryMcdn = parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
        val isPcdn = (isIp && (hasBiliMediaSignature || isKnownP2pHost(hostname))) ||
                isKnownP2pHost(hostname) ||
                (hasNonDefaultPort && (isBiliFamily || hasBiliMediaSignature)) ||
                queryMcdn

        return !isPcdn && (isBiliCdnHost(hostname) || hostname.endsWith(".bstarstatic.com"))
    }

    fun rewriteUrl(original: String, config: AcceleratorConfig): RewriteResult {
        if (!config.enabled || !hasMediaSignal(original)) {
            return noChange(original, "ignored", host = "")
        }

        val parsed = parseUri(original) ?: return noChange(original, "parse-failed", host = "")
        val hostname = parsed.host.lowercase()
        val segKey = extractSegmentKey(parsed.path)

        if (!isMediaUrl(parsed)) {
            return noChange(original, "ignored", host = hostname, segmentKey = segKey)
        }

        val cleanProxyHost = cleanHost(config.proxyHost).substringBefore(':').ifBlank { "proxy-tf-all-ws.bilivideo.com" }
        if (hostname == cleanProxyHost) {
            return noChange(original, "already-proxied", isMcdn = true, host = hostname, segmentKey = segKey)
        }

        if (isLiveMediaUrl(parsed.path)) {
            return noChange(original, "live-skip", host = hostname, segmentKey = segKey)
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
        // 端口和 IP 启发式仅在属于 B 站域名族或携带明确 B 站媒体特征时生效，防止误伤第三方外链视频或广告
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
                    return noChange(original, "ok", isPcdn = true, host = hostname, segmentKey = segKey)
                }
            }
            // 若 xy_usource 畸形、恶意域名或不在合法列表中，跳过恢复，流转至下方 PCDN 拦截规则
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
                return noChange(original, "ok", isMcdn = true, host = hostname, segmentKey = segKey)
            }
        }

        // 3. PCDN 拦截 / Force 模式统一重定向到优质 UPOS 镜像
        val isAkamai = hostname.endsWith(".akamaized.net", ignoreCase = true)
        val isBstar = hostname.endsWith(".bstarstatic.com", ignoreCase = true)
        val shouldRewrite = (isPcdn && config.blockPcdn) ||
                (isMcdn && !config.proxyMcdn) ||
                (config.forceUpos && isBiliCdnHost(hostname) && !isAkamai && !isBstar)

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
                return noChange(original, "ok", isPcdn = isPcdn, isMcdn = isMcdn, host = hostname, segmentKey = segKey)
            }
        }

        return noChange(original, "ok", isPcdn = isPcdn, isMcdn = isMcdn, host = hostname, segmentKey = segKey)
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

