package io.github.jh_mmm.biliaccelerator.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 移植自上游 src/core/routing.js 与 src/page/bili-accelerator.page.js 的点播路由引擎 (RouteEngine)。
 * 负责维护 DASH 会话表示层映射表 (SessionTable)、双半衰期 EWMA 吞吐估算、卡顿/失速判定、
 * 候选节点挑选 (pickChallengers)、竞速裁决 (raceVerdict / stuckRaceVerdict) 以及按会话路由 (engineRoute)。
 */
object RouteEngine {

    const val RACE_BYTES: Long = 768L * 1024L
    const val RACE_TIMEOUT_MS: Long = 4000L

    const val EWMA_FAST_HALF_LIFE_S: Double = 2.0
    const val EWMA_SLOW_HALF_LIFE_S: Double = 5.0
    const val MIN_SAMPLE_BYTES: Long = 16_000L
    const val MIN_TOTAL_BYTES: Long = 128_000L

    const val STUCK_MIN_FRAGMENT_BYTES: Long = 128L * 1024L
    const val STUCK_MIN_TRANSFER_MS: Long = 500L
    const val STUCK_NO_FIRST_BYTE_MS: Long = 1000L
    const val STUCK_URGENT_BUFFER_S: Double = 3.0
    const val STUCK_RATE_FACTOR: Double = 1.3
    const val STUCK_MIN_REMAINING_MS: Long = 1000L
    const val STUCK_MARGIN_MS: Long = 2000L

    const val SHORTFALL_RATE_FACTOR: Double = 1.2
    const val SHORTFALL_MIN_BYTES: Long = 4L * 1024L * 1024L
    const val SHORTFALL_MIN_MS: Long = 8000L
    const val SHORTFALL_BUFFER_S: Double = 30.0

    const val ERROR_WINDOW_MS: Long = 30_000L
    const val ERROR_LIMIT: Int = 2

    const val SWITCH_GAIN: Double = 1.5
    const val MAX_SWITCHES: Int = 4
    const val SWITCH_BACKOFF_MS: Long = 10_000L
    const val NO_SWITCH_COOLDOWN_MS: Long = 15_000L
    const val MAX_COOLDOWN_MS: Long = 120_000L
    const val LOST_RACE_REST_MS: Long = 60_000L
    const val AVOID_MS: Long = 15_000L

    const val HISTORY_HALF_LIFE_MS: Long = 3L * 24L * 60L * 60L * 1000L
    const val RECENT_FAILURE_MS: Long = 60L * 60L * 1000L
    const val EXPLORE_RATE: Double = 0.2
    const val UNKNOWN_HOST_MBPS: Double = 10.0
    private const val MAX_SESSIONS: Int = 16

    val AUDIO_ID_RE = Regex("^30(2\\d\\d|25\\d)$")
    private val FILE_EXT_RE = Regex("\\.(m4s|mp4|flv)$", RegexOption.IGNORE_CASE)
    private val CID_PREFIX_RE = Regex("^(\\d+)")
    private val REP_ID_RE = Regex("-(\\d+)\\.(?:m4s|mp4|flv)$", RegexOption.IGNORE_CASE)
    private val RANGE_RE = Regex("bytes=(\\d+)-(\\d*)", RegexOption.IGNORE_CASE)
    private val UPOS_HOST_RE = Regex("^upos-[a-z0-9-]+\\.bilivideo\\.com$", RegexOption.IGNORE_CASE)
    private val UPOS_MIRROR_DESC_RE = Regex("^upos-[a-z]+-mirror([a-z0-9]+?)(ov)?\\.bilivideo\\.com$", RegexOption.IGNORE_CASE)
    private val UPOS_TF_DESC_RE = Regex("^upos-tf-all-([a-z]+)\\.bilivideo\\.com$", RegexOption.IGNORE_CASE)

    private val VENDORS = mapOf(
        "cos" to "tencent",
        "ali" to "alibaba",
        "hw" to "huawei",
        "tx" to "tencent",
        "akam" to "akamai"
    )

    data class ByteRange(
        val start: Long,
        val end: Long?,
        val length: Long?
    )

    data class RepEntry(
        val key: String,
        val id: Int = 0,
        val kind: String = "video",
        val bandwidth: Long = 0L,
        val codecs: String = "",
        val urls: MutableMap<String, String> = LinkedHashMap(),
        val issued: MutableList<String> = mutableListOf()
    )

    data class SessionTable(
        var cid: String? = null,
        val reps: MutableMap<String, RepEntry> = ConcurrentHashMap()
    )

    data class DetourTarget(
        val host: String,
        val untilMs: Long
    )

    data class SessionState(
        val cid: String,
        val table: SessionTable = SessionTable(cid),
        @Volatile var requiredBps: Double = 0.0,
        @Volatile var assigned: String? = null,
        @Volatile var lastHost: String? = null,
        @Volatile var active: String? = null,
        @Volatile var fallback: String? = null,
        @Volatile var avoid: DetourTarget? = null,
        @Volatile var detour: DetourTarget? = null,
        val failed: MutableMap<String, Int> = ConcurrentHashMap(),
        val lost: MutableMap<String, Long> = ConcurrentHashMap(),
        val measured: MutableMap<String, Boolean> = ConcurrentHashMap()
    )

    data class InflightRequest(
        val total: Long,
        val loaded: Long,
        val startedAt: Long,
        val firstByteAt: Long? = null,
        val rateBps: Double = 0.0
    )

    data class StuckVerdict(
        val reason: String,
        val waitedMs: Long? = null,
        val rateBps: Double? = null,
        val remainingMs: Double? = null
    )

    data class EvaluateInput(
        val now: Long,
        val requiredBps: Double,
        val bufferAheadS: Double,
        val inflight: List<InflightRequest> = emptyList(),
        val estimateBps: Double? = null,
        val measuredBytes: Long = 0L,
        val measuredMs: Long = 0L,
        val recentErrors: Int = 0
    )

    data class RouteTrigger(
        val trigger: String,
        val req: InflightRequest? = null
    )

    data class HostHistoryEntry(
        var mbps: Double = 0.0,
        var n: Double = 0.0,
        var at: Long = 0L,
        var lastFailAt: Long? = null
    )

    data class RouteHistory(
        val hosts: MutableMap<String, HostHistoryEntry> = ConcurrentHashMap()
    )

    data class ChallengerInput(
        val candidates: List<String>,
        val current: String?,
        val issued: List<String> = emptyList(),
        val measured: Map<String, Boolean> = emptyMap(),
        val failed: Map<String, Int> = emptyMap(),
        val lost: Map<String, Long> = emptyMap(),
        val history: RouteHistory? = null,
        val now: Long = 0L,
        val random: () -> Double = { Random.nextDouble() }
    )

    data class RaceSample(
        val host: String,
        val ok: Boolean,
        val ms: Long,
        val bytes: Long = RACE_BYTES
    )

    data class RaceVerdict(
        val winner: String?,
        val runnerUp: String?,
        val switchTo: String?
    )

    data class StuckRaceVerdict(
        val winner: String?,
        val runnerUp: String?,
        val switchTo: String?,
        val hostRateBps: Double,
        val retryOn: String?
    )

    data class HostDescription(
        val region: String?,
        val vendor: String?,
        val id: String
    )

    data class HostProbeSample(
        val host: String,
        val ok: Boolean,
        val ttfb: Double? = null,
        val mbps: Double? = null
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()
    @Volatile
    private var currentSessionCid: String? = null
    private val history = RouteHistory()

    // ---- fragment identity ---------------------------------------------------

    fun fileKey(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = BiliAcceleratorCore.parseUri(url) ?: return null
        val path = parsed.path
        val name = path.substringAfterLast('/')
        return if (FILE_EXT_RE.containsMatchIn(name)) name else null
    }

    fun cidOf(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return CID_PREFIX_RE.find(key)?.groupValues?.getOrNull(1)
    }

    fun repIdOf(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return REP_ID_RE.find(key)?.groupValues?.getOrNull(1)
    }

    fun parseRange(value: String?): ByteRange? {
        if (value.isNullOrBlank()) return null
        val match = RANGE_RE.find(value) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val endStr = match.groupValues[2]
        val end = if (endStr.isEmpty()) null else endStr.toLongOrNull()
        val length = if (end != null) end - start + 1 else null
        return ByteRange(start, end, length)
    }

    fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val parsed = BiliAcceleratorCore.parseUri(url) ?: return ""
        return parsed.host.lowercase()
    }

    fun isAkamai(host: String?): Boolean {
        return host?.lowercase()?.endsWith(".akamaized.net") == true
    }

    fun isUposHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return UPOS_HOST_RE.matches(host.trim())
    }

    // ---- the session table ----------------------------------------------------

    fun dashContainers(payload: JsonElement?): List<JsonObject> {
        val obj = payload?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val candidates = mutableListOf<JsonElement?>()
        candidates.add(obj.get("data"))
        val result = obj.get("result")
        candidates.add(result)
        if (result != null && result.isJsonObject) {
            candidates.add(result.asJsonObject.get("video_info"))
        }
        candidates.add(obj)

        return candidates.mapNotNull { candidate ->
            val cObj = candidate?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            cObj.get("dash")?.takeIf { it.isJsonObject }?.asJsonObject
        }
    }

    fun buildTable(jsonPayload: String, config: AcceleratorConfig): SessionTable? {
        val element = try {
            JsonParser.parseString(jsonPayload)
        } catch (_: Throwable) {
            return null
        }
        return buildTable(element, config)
    }

    fun buildTable(payload: JsonElement?, config: AcceleratorConfig): SessionTable? {
        val table = SessionTable()
        for (dash in dashContainers(payload)) {
            val groups = mutableListOf<Pair<String, JsonArray>>()
            dash.get("video")?.takeIf { it.isJsonArray }?.let { groups.add("video" to it.asJsonArray) }
            dash.get("audio")?.takeIf { it.isJsonArray }?.let { groups.add("audio" to it.asJsonArray) }
            dash.get("dolby")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("audio")?.takeIf { it.isJsonArray }?.let { groups.add("audio" to it.asJsonArray) }
            dash.get("flac")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("audio")?.takeIf { it.isJsonObject }?.let { flacAudio ->
                    val arr = JsonArray()
                    arr.add(flacAudio)
                    groups.add("audio" to arr)
                }

            for ((kind, arr) in groups) {
                for (item in arr) {
                    val entry = item?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val base = entry.getStringOrNull("baseUrl") ?: entry.getStringOrNull("base_url")
                    val backupsElement = entry.get("backupUrl") ?: entry.get("backup_url")
                    val urls = mutableListOf<String>()
                    if (!base.isNullOrBlank()) urls.add(base)
                    if (backupsElement != null && backupsElement.isJsonArray) {
                        for (b in backupsElement.asJsonArray) {
                            if (b.isJsonPrimitive && b.asJsonPrimitive.isString) {
                                urls.add(b.asString)
                            }
                        }
                    }
                    val key = urls.firstNotNullOfOrNull { fileKey(it) } ?: continue
                    val rep = table.reps[key] ?: RepEntry(
                        key = key,
                        id = entry.getIntOrDefault("id", 0),
                        kind = kind,
                        bandwidth = entry.getLongOrDefault("bandwidth", 0L),
                        codecs = entry.getStringOrNull("codecs") ?: ""
                    )
                    for (u in urls) {
                        val h = hostOf(u)
                        if (h.isEmpty() || fileKey(u) != key || !BiliAcceleratorCore.isUsableIssuedUrl(u, config)) {
                            continue
                        }
                        if (!rep.urls.containsKey(h)) {
                            rep.urls[h] = u
                            rep.issued.add(h)
                        }
                    }
                    table.reps[key] = rep
                    if (table.cid == null) {
                        table.cid = cidOf(key)
                    }
                }
            }
        }
        return if (table.reps.isNotEmpty()) table else null
    }

    /**
     * 从播放器的 Segment.Builder 或 playurl 实时录入已签发 URL 到会话表中。
     */
    fun ingestSegment(baseUrl: String?, backupUrls: Collection<String>?, config: AcceleratorConfig): SessionState? {
        if (!config.enabled) return null
        val allUrls = mutableListOf<String>()
        if (!baseUrl.isNullOrBlank()) allUrls.add(baseUrl)
        backupUrls?.forEach { if (it.isNotBlank()) allUrls.add(it) }
        val key = allUrls.firstNotNullOfOrNull { fileKey(it) } ?: return null
        val cid = cidOf(key) ?: return null
        val session = sessionFor(cid)
        val repId = repIdOf(key)
        val kind = if (repId != null && AUDIO_ID_RE.matches(repId)) "audio" else "video"
        val rep = session.table.reps.computeIfAbsent(key) {
            RepEntry(key = key, id = repId?.toIntOrNull() ?: 0, kind = kind)
        }
        for (u in allUrls) {
            val h = hostOf(u)
            if (h.isEmpty() || fileKey(u) != key || !BiliAcceleratorCore.isUsableIssuedUrl(u, config)) {
                continue
            }
            if (!rep.urls.containsKey(h)) {
                rep.urls[h] = u
                if (!rep.issued.contains(h)) {
                    rep.issued.add(h)
                }
            }
        }
        if (kind == "video") {
            currentSessionCid = cid
        }
        return session
    }

    fun ingestPlayurlJson(jsonPayload: String, config: AcceleratorConfig): SessionState? {
        if (!config.enabled) return null
        val table = buildTable(jsonPayload, config) ?: return null
        val cid = table.cid ?: return null
        val session = sessionFor(cid)
        for ((k, v) in table.reps) {
            session.table.reps[k] = v
        }
        currentSessionCid = cid
        return session
    }

    fun sessionFor(cid: String): SessionState {
        sessions[cid]?.let { return it }
        val created = SessionState(cid = cid)
        sessions[cid] = created
        if (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.firstOrNull { it != currentSessionCid && it != cid }
            if (oldest != null) {
                sessions.remove(oldest)
            }
        }
        return created
    }

    fun getSession(cid: String): SessionState? = sessions[cid]

    fun clearSessions() {
        sessions.clear()
        currentSessionCid = null
    }

    /**
     * 获取某表示层在指定 host 上的 URL：
     * 若该 host 有 B 站直接签发的 URL 则直接返回；
     * 否则仅当目标 host 为 UPOS 镜像且存在已签发 UPOS 源时进行同签名路径 host 替换（绝不为 Akamai 合成未签发 URL）。
     */
    fun urlFor(rep: RepEntry?, host: String?): String? {
        if (rep == null || host.isNullOrBlank()) return null
        val cleanTarget = host.trim().lowercase()
        rep.urls[cleanTarget]?.let { return it }
        if (!isUposHost(cleanTarget)) {
            return null
        }
        val sourceUrl = rep.issued.firstOrNull { isUposHost(it) }?.let { rep.urls[it] } ?: return null
        val parsed = BiliAcceleratorCore.parseUri(sourceUrl) ?: return null
        return parsed.replaceAuthority(cleanTarget)
    }

    fun candidatesFor(rep: RepEntry?, pool: List<String> = BiliAcceleratorCore.CANDIDATE_POOL): List<String> {
        if (rep == null) return emptyList()
        val out = rep.issued.toMutableList()
        for (rawHost in pool) {
            val host = rawHost.trim().lowercase()
            if (host.isNotEmpty() && !out.contains(host) && urlFor(rep, host) != null) {
                out.add(host)
            }
        }
        return out
    }

    /**
     * 按当前会话状态路由分片 URL（支持 detour 临时重试、active 选举主路由与 avoid 故障退避）。
     */
    fun engineRoute(url: String, config: AcceleratorConfig, nowMs: Long = System.currentTimeMillis()): String {
        if (!config.enabled) return url
        val key = fileKey(url) ?: return url
        val cid = cidOf(key) ?: return url
        val session = sessions[cid] ?: return url
        val rep = session.table.reps[key] ?: return url

        val detour = session.detour
        if (detour != null && nowMs < detour.untilMs) {
            return urlFor(rep, detour.host) ?: url
        }
        val active = session.active ?: return url
        var target: String? = active
        val avoid = session.avoid
        if (avoid != null && avoid.host == target && nowMs < avoid.untilMs) {
            target = session.fallback
        }
        return if (!target.isNullOrBlank()) (urlFor(rep, target) ?: url) else url
    }

    // ---- goodput & EWMA ------------------------------------------------------

    class Ewma(halfLifeS: Double) {
        private val alpha = exp(ln(0.5) / halfLifeS)
        private var estimate = 0.0
        private var totalWeight = 0.0

        fun sample(weight: Double, value: Double) {
            val adjAlpha = alpha.pow(weight)
            estimate = value * (1.0 - adjAlpha) + adjAlpha * estimate
            totalWeight += weight
        }

        fun get(): Double {
            val zeroFactor = 1.0 - alpha.pow(totalWeight)
            return if (zeroFactor > 0.0) estimate / zeroFactor else 0.0
        }
    }

    class BandwidthEstimator {
        private val fast = Ewma(EWMA_FAST_HALF_LIFE_S)
        private val slow = Ewma(EWMA_SLOW_HALF_LIFE_S)
        private var totalBytes: Long = 0L
        private var totalMs: Long = 0L

        fun sample(durationMs: Long, numBytes: Long): Boolean {
            if (durationMs <= 0L || numBytes < MIN_SAMPLE_BYTES) {
                return false
            }
            val bps = 8000.0 * numBytes.toDouble() / durationMs.toDouble()
            val weightS = durationMs.toDouble() / 1000.0
            fast.sample(weightS, bps)
            slow.sample(weightS, bps)
            totalBytes += numBytes
            totalMs += durationMs
            return true
        }

        fun estimate(): Double? {
            return if (totalBytes >= MIN_TOTAL_BYTES) min(fast.get(), slow.get()) else null
        }

        fun bytes(): Long = totalBytes
        fun ms(): Long = totalMs
    }

    fun createEstimator(): BandwidthEstimator = BandwidthEstimator()

    fun recentRate(samples: List<Pair<Long, Long>>, now: Long, windowMs: Long): Double {
        if (samples.isEmpty()) return 0.0
        val last = samples.last()
        var first = samples.first()
        for (i in samples.indices.reversed()) {
            if (now - samples[i].first <= windowMs) {
                first = samples[i]
            } else {
                break
            }
        }
        if (first === last) {
            first = samples.first()
        }
        val dt = last.first - first.first
        return if (dt > 0L) 8000.0 * (last.second - first.second).toDouble() / dt.toDouble() else 0.0
    }

    // ---- when to act ----------------------------------------------------------

    fun stuckVerdict(req: InflightRequest?, now: Long, requiredBps: Double, bufferAheadS: Double): StuckVerdict? {
        if (req == null || req.total < STUCK_MIN_FRAGMENT_BYTES || requiredBps <= 0.0) {
            return null
        }
        val bufferMs = max(0.0, bufferAheadS) * 1000.0
        val firstByteAt = req.firstByteAt
        if (firstByteAt == null) {
            val waited = now - req.startedAt
            if (waited >= STUCK_NO_FIRST_BYTE_MS && bufferAheadS < STUCK_URGENT_BUFFER_S) {
                return StuckVerdict(reason = "no-first-byte", waitedMs = waited)
            }
            return null
        }
        val transferMs = now - firstByteAt
        if (transferMs < STUCK_MIN_TRANSFER_MS && req.loaded < STUCK_MIN_FRAGMENT_BYTES) {
            return null
        }
        val rate = if (req.rateBps > 0.0) {
            req.rateBps
        } else if (transferMs > 0L) {
            8000.0 * req.loaded.toDouble() / transferMs.toDouble()
        } else {
            0.0
        }
        val remainingMs = if (rate > 0.0) (req.total - req.loaded).toDouble() * 8000.0 / rate else Double.POSITIVE_INFINITY
        if (rate < STUCK_RATE_FACTOR * requiredBps &&
            remainingMs > STUCK_MIN_REMAINING_MS &&
            remainingMs > bufferMs - STUCK_MARGIN_MS
        ) {
            return StuckVerdict(reason = "slow-fragment", rateBps = rate, remainingMs = remainingMs)
        }
        return null
    }

    fun evaluate(input: EvaluateInput): RouteTrigger? {
        if (input.requiredBps <= 0.0) return null
        if (input.recentErrors >= ERROR_LIMIT) {
            return RouteTrigger(trigger = "errors")
        }
        for (req in input.inflight) {
            if (stuckVerdict(req, input.now, input.requiredBps, input.bufferAheadS) != null) {
                return RouteTrigger(trigger = "stuck", req = req)
            }
        }
        val enough = input.measuredBytes >= SHORTFALL_MIN_BYTES || input.measuredMs >= SHORTFALL_MIN_MS
        if (enough &&
            input.estimateBps != null &&
            input.estimateBps < SHORTFALL_RATE_FACTOR * input.requiredBps &&
            input.bufferAheadS < SHORTFALL_BUFFER_S
        ) {
            return RouteTrigger(trigger = "shortfall")
        }
        return null
    }

    // ---- choosing & racing ---------------------------------------------------

    fun historyScore(history: RouteHistory?, host: String): Double? {
        val rec = history?.hosts?.get(host) ?: return null
        return if (rec.n > 0.0) rec.mbps else null
    }

    fun recentlyFailed(history: RouteHistory?, host: String, now: Long): Boolean {
        val rec = history?.hosts?.get(host) ?: return false
        val lastFail = rec.lastFailAt ?: return false
        return now - lastFail < RECENT_FAILURE_MS
    }

    fun pickChallengers(input: ChallengerInput): List<String> {
        val now = input.now
        val pool = input.candidates.filter { h ->
            h.isNotBlank() &&
                h != input.current &&
                (input.failed[h] ?: 0) < ERROR_LIMIT &&
                (input.lost[h]?.let { now - it < LOST_RACE_REST_MS } != true)
        }
        if (pool.isEmpty()) return emptyList()

        val failedLately = { h: String -> if (recentlyFailed(input.history, h, now)) 1 else 0 }
        val known = pool.filter { failedLately(it) == 0 }
            .mapNotNull { historyScore(input.history, it) }
            .sorted()
        val typical = if (known.isNotEmpty()) known[(known.size - 1) / 2] else UNKNOWN_HOST_MBPS
        val score = { h: String -> historyScore(input.history, h) ?: typical }
        val overseas = { h: String -> if (describeHost(h).region == "overseas") 1 else 0 }

        val ordered = pool.sortedWith { a, b ->
            val cmpFail = failedLately(a).compareTo(failedLately(b))
            if (cmpFail != 0) return@sortedWith cmpFail
            val cmpScore = score(b).compareTo(score(a))
            if (cmpScore != 0) return@sortedWith cmpScore
            overseas(a).compareTo(overseas(b))
        }

        val picks = mutableListOf<String>()
        for (h in input.issued) {
            if (picks.size < 1 && pool.contains(h) && input.measured[h] != true) {
                picks.add(h)
            }
        }
        for (h in ordered) {
            if (picks.size < 2 && !picks.contains(h)) {
                picks.add(h)
            }
        }
        if (picks.size == 2 && input.random() < EXPLORE_RATE) {
            val rest = pool.filter { !picks.contains(it) }
            if (rest.isNotEmpty()) {
                val idx = (input.random() * rest.size).toInt().coerceIn(0, rest.lastIndex)
                picks[1] = rest[idx]
            }
        }
        return picks
    }

    fun raceVerdict(
        results: List<RaceSample>,
        currentRateBps: Double,
        raceBytes: Long = RACE_BYTES
    ): RaceVerdict {
        val done = results.filter { it.ok && it.ms > 0L }.sortedBy { it.ms }
        val currentMs = if (currentRateBps > 0.0) raceBytes.toDouble() * 8000.0 / currentRateBps else Double.POSITIVE_INFINITY
        if (done.isEmpty()) {
            return RaceVerdict(winner = null, runnerUp = null, switchTo = null)
        }
        val winner = done[0]
        val better = winner.ms.toDouble() * SWITCH_GAIN <= currentMs
        return RaceVerdict(
            winner = winner.host,
            runnerUp = done.getOrNull(1)?.host,
            switchTo = if (better) winner.host else null
        )
    }

    fun stuckRaceVerdict(
        results: List<RaceSample>,
        stuckRateBps: Double,
        sustainedBps: Double,
        recentErrors: Int,
        raceBytes: Long = RACE_BYTES
    ): StuckRaceVerdict {
        val fragment = raceVerdict(results, stuckRateBps, raceBytes)
        val hostRateBps = if (recentErrors > 0) max(0.0, stuckRateBps) else max(stuckRateBps, sustainedBps)
        val video = raceVerdict(results, hostRateBps, raceBytes)
        val retryOn = if (video.switchTo == null && fragment.switchTo != null) fragment.switchTo else null
        return StuckRaceVerdict(
            winner = video.winner,
            runnerUp = video.runnerUp,
            switchTo = video.switchTo,
            hostRateBps = hostRateBps,
            retryOn = retryOn
        )
    }

    fun nextCooldown(kind: String, switches: Int, previousMs: Long): Long {
        if (kind == "switch") {
            return (SWITCH_BACKOFF_MS.toDouble() * 2.0.pow(max(0, switches - 1).toDouble())).toLong()
        }
        val next = if (previousMs > 0L) previousMs * 2L else NO_SWITCH_COOLDOWN_MS
        return min(MAX_COOLDOWN_MS, next)
    }

    fun recordSample(history: RouteHistory = this.history, host: String, mbps: Double, now: Long): RouteHistory {
        val rec = history.hosts[host] ?: HostHistoryEntry(mbps = 0.0, n = 0.0, at = now)
        val age = max(0L, now - (if (rec.at > 0L) rec.at else now))
        val nEff = rec.n * 0.5.pow(age.toDouble() / HISTORY_HALF_LIFE_MS.toDouble())
        rec.mbps = (rec.mbps * nEff + mbps) / (nEff + 1.0)
        rec.n = nEff + 1.0
        rec.at = now
        history.hosts[host] = rec
        return history
    }

    fun recordFailure(history: RouteHistory = this.history, host: String, now: Long): RouteHistory {
        val rec = history.hosts[host] ?: HostHistoryEntry(mbps = 0.0, n = 0.0, at = now)
        rec.lastFailAt = now
        history.hosts[host] = rec
        return history
    }

    fun describeHost(host: String?): HostDescription {
        val h = (host ?: "").trim().lowercase()
        if (isAkamai(h)) {
            return HostDescription(region = "overseas", vendor = "akamai", id = "akamai")
        }
        UPOS_MIRROR_DESC_RE.find(h)?.let { m ->
            val code = m.groupValues[1]
            val ov = m.groupValues[2]
            return HostDescription(
                region = if (ov.isNotEmpty()) "overseas" else "mainland",
                vendor = VENDORS[code],
                id = code + ov
            )
        }
        UPOS_TF_DESC_RE.find(h)?.let { m ->
            val code = m.groupValues[1]
            return HostDescription(
                region = "mainland",
                vendor = VENDORS[code],
                id = "tf-$code"
            )
        }
        return HostDescription(region = null, vendor = null, id = h.substringBefore('.').ifEmpty { h })
    }

    /**
     * 移植自上游 rewrite.js rankHosts：按吞吐率 (Mbps) 降序、TTFB 升序排列探测主机。
     */
    fun rankHosts(samples: List<HostProbeSample>): List<String> {
        return samples.sortedWith { a, b ->
            val aOk = a.ok && (a.mbps != null || a.ttfb != null)
            val bOk = b.ok && (b.mbps != null || b.ttfb != null)
            if (aOk != bOk) return@sortedWith if (aOk) -1 else 1
            if (!aOk) return@sortedWith 0
            val aRate = a.mbps ?: 0.0
            val bRate = b.mbps ?: 0.0
            if (aRate != bRate) return@sortedWith bRate.compareTo(aRate)
            val aLat = a.ttfb ?: Double.POSITIVE_INFINITY
            val bLat = b.ttfb ?: Double.POSITIVE_INFINITY
            aLat.compareTo(bLat)
        }.map { BiliAcceleratorCore.cleanHost(it.host) }
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        val el = this.get(key) ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString else null
    }

    private fun JsonObject.getIntOrDefault(key: String, defaultVal: Int): Int {
        val el = this.get(key) ?: return defaultVal
        return runCatching { el.asInt }.getOrDefault(defaultVal)
    }

    private fun JsonObject.getLongOrDefault(key: String, defaultVal: Long): Long {
        val el = this.get(key) ?: return defaultVal
        return runCatching { el.asLong }.getOrDefault(defaultVal)
    }
}
