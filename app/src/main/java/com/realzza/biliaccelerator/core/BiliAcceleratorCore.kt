package com.realzza.biliaccelerator.core

import android.net.Uri
import java.net.URLDecoder

data class RewriteResult(
    val changed: Boolean,
    val originalUrl: String,
    val finalUrl: String,
    val originalHost: String,
    val targetHost: String,
    val reason: String,
    val isPcdn: Boolean,
    val isMcdn: Boolean
)

data class AcceleratorConfig(
    val enabled: Boolean = true,
    val targetHost: String = "upos-sz-mirrorcos.bilivideo.com",
    val proxyHost: String = "proxy-tf-all-ws.bilivideo.com",
    val blockPcdn: Boolean = true,
    val proxyMcdn: Boolean = true,
    val forceUpos: Boolean = false,
    val portHeuristic: Boolean = true
)

object BiliAcceleratorCore {

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
    private val IP_REGEX = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
    private val XY_MCDN_REGEX = Regex("""^xy(?:\d+x){3}\d+xy\.mcdn\.bilivideo\.(?:cn|com|net)$""", RegexOption.IGNORE_CASE)

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
                url.contains("/upgcxcode/") ||
                url.contains("/v1/resource/") ||
                MEDIA_PATH_REGEX.containsMatchIn(url)
    }

    private fun isKnownP2pHost(hostname: String): Boolean {
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

    private fun isMcdnHost(hostname: String): Boolean {
        return hostname.endsWith(".mcdn.bilivideo.cn", ignoreCase = true) ||
                hostname.endsWith(".mcdn.bilivideo.com", ignoreCase = true) ||
                hostname.endsWith(".mcdn.bilivideo.net", ignoreCase = true)
    }

    fun isBiliCdnHost(hostname: String): Boolean {
        val h = cleanHost(hostname).lowercase().substringBefore(':')
        return h.endsWith(".bilivideo.com") ||
                h.endsWith(".bilivideo.cn") ||
                h.endsWith(".bilivideo.net") ||
                h.endsWith(".akamaized.net")
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
                return ParsedUri(
                    scheme = u.scheme ?: "https",
                    host = host,
                    port = u.port,
                    path = u.path ?: "",
                    queryParam = { key -> u.getQueryParameter(key) },
                    replaceAuthority = { newAuth ->
                        u.buildUpon().scheme("https").encodedAuthority(newAuth).build().toString()
                    },
                    proxyWrap = { proxyHost ->
                        Uri.Builder()
                            .scheme("https")
                            .encodedAuthority(proxyHost)
                            .path("/")
                            .appendQueryParameter("url", original)
                            .build().toString()
                    }
                )
            }
        } catch (_: Throwable) {}

        // JVM 单元测试回退（当 android.net.Uri 在非 Android 环境未被模拟时）
        return try {
            val javaUri = java.net.URI(original)
            val host = javaUri.host ?: return null
            ParsedUri(
                scheme = javaUri.scheme ?: "https",
                host = host,
                port = javaUri.port,
                path = javaUri.rawPath ?: "",
                queryParam = { key ->
                    val q = javaUri.query ?: return@ParsedUri null
                    q.split("&").mapNotNull {
                        val parts = it.split("=", limit = 2)
                        if (parts[0] == key) if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else "" else null
                    }.firstOrNull()
                },
                replaceAuthority = { newAuth ->
                    val path = javaUri.rawPath ?: ""
                    val q = if (!javaUri.rawQuery.isNullOrEmpty()) "?${javaUri.rawQuery}" else ""
                    val f = if (!javaUri.rawFragment.isNullOrEmpty()) "#${javaUri.rawFragment}" else ""
                    "https://$newAuth$path$q$f"
                },
                proxyWrap = { proxyHost ->
                    val encoded = java.net.URLEncoder.encode(original, "UTF-8")
                    "https://$proxyHost/?url=$encoded"
                }
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun isMediaUrl(uri: Uri): Boolean {
        val path = uri.path ?: ""
        val full = uri.toString()
        return MEDIA_PATH_REGEX.containsMatchIn(full) ||
                path.startsWith("/upgcxcode/") ||
                path.startsWith("/v1/resource/")
    }

    fun isLiveMediaUrl(path: String): Boolean {
        return path.contains("/live-bvc/")
    }

    fun rewriteUrl(original: String, config: AcceleratorConfig): RewriteResult {
        if (!config.enabled || !hasMediaSignal(original)) {
            return noChange(original, "ignored")
        }

        val parsed = parseUri(original) ?: return noChange(original, "parse-failed")
        val hostname = parsed.host.lowercase()
        val cleanProxyHost = cleanHost(config.proxyHost).ifBlank { "proxy-tf-all-ws.bilivideo.com" }
        if (hostname == cleanProxyHost) {
            return noChange(original, "already-proxied")
        }

        if (isLiveMediaUrl(parsed.path)) {
            return noChange(original, "live-skip")
        }

        val port = parsed.port
        val hasNonDefaultPort = config.portHeuristic && port != -1 && port != 80 && port != 443
        val queryMcdn = parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
        val isMcdn = isMcdnHost(hostname) || XY_MCDN_REGEX.matches(hostname)
        val isPcdn = IP_REGEX.matches(hostname) ||
                isKnownP2pHost(hostname) ||
                hasNonDefaultPort ||
                queryMcdn

        // 1. szbdyd / mountaintoys 调度器：直接恢复 xy_usource 参数所携带的真实原生 CDN 域名
        val isScheduler = hostname.endsWith(".szbdyd.com", ignoreCase = true) ||
                hostname.endsWith(".mountaintoys.cn", ignoreCase = true)
        if (isScheduler) {
            val rawSource = parsed.queryParam("xy_usource")
            val cleanedSource = cleanHost(rawSource)
            val hostOnly = cleanedSource.substringBefore(':')
            if (isValidHost(cleanedSource) && isBiliCdnHost(hostOnly)) {
                val rewritten = parsed.replaceAuthority(cleanedSource)
                if (rewritten != original) {
                    return RewriteResult(
                        changed = true,
                        originalUrl = original,
                        finalUrl = rewritten,
                        originalHost = hostname,
                        targetHost = cleanedSource,
                        reason = if (hostname.endsWith(".mountaintoys.cn", ignoreCase = true)) "mountaintoys-source" else "szbdyd-source",
                        isPcdn = true,
                        isMcdn = false
                    )
                } else {
                    return noChange(original, "ok")
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
                    isMcdn = true
                )
            } else {
                return noChange(original, "ok")
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
            val target = if (isValidHost(cleanedTarget) && (isBiliCdnHost(cleanedTarget) || CANDIDATE_POOL.contains(cleanedTarget))) {
                cleanedTarget
            } else {
                CANDIDATE_POOL[0]
            }
            val targetHostOnly = target.substringBefore(':')
            val rewritten = parsed.replaceAuthority(targetHostOnly)
            if (rewritten != original) {
                return RewriteResult(
                    changed = true,
                    originalUrl = original,
                    finalUrl = rewritten,
                    originalHost = hostname,
                    targetHost = targetHostOnly,
                    reason = if (isPcdn) "pcdn-host" else if (isMcdn) "mcdn-host" else "force-upos",
                    isPcdn = isPcdn,
                    isMcdn = isMcdn
                )
            } else {
                return noChange(original, "ok")
            }
        }

        return noChange(original, "ok")
    }

    private fun noChange(url: String, reason: String): RewriteResult {
        val host = parseUri(url)?.host ?: ""
        return RewriteResult(
            changed = false,
            originalUrl = url,
            finalUrl = url,
            originalHost = host,
            targetHost = host,
            reason = reason,
            isPcdn = false,
            isMcdn = false
        )
    }
}
