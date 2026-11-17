package io.github.jh_mmm.biliaccelerator.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager
import java.io.File
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

data class PlayinfoTrackEntry(
    val pathKey: String,
    val fileKey: String,
    val primaryUrl: String,
    val backupUrls: List<String>,
    val deadlineEpochSec: Long,
    val isAudio: Boolean,
    val hasDrm: Boolean,
    val isPreview: Boolean,
    val isEpisode: Boolean,
    val cid: Long = 0L,
    val epId: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
)

fun interface PlayinfoRefreshCallback {
    fun refresh(): Boolean
}

data class SharedConcurrencyState(
    val learnedConcurrency: Int = 0,
    val hardCapConcurrency: Int = 0,
    val hardCapUntilMs: Long = 0L,
    val updatedAtMs: Long = 0L
)

private data class SharedTrackSummary(
    val pathKey: String = "",
    val fileKey: String = "",
    val primaryUrl: String = "",
    val backupUrls: List<String> = emptyList(),
    val isAudio: Boolean = false,
    val hasDrm: Boolean = false,
    val isPreview: Boolean = false,
    val isEpisode: Boolean = false,
    val deadlineEpochSec: Long = 0L,
    val updatedAtMs: Long = 0L
)

object PlayinfoRegistry {

    private const val MAX_ENTRIES = 512
    private const val ENTRY_TTL_MS = 6L * 60L * 60L * 1000L
    private const val MAX_SHARED_ENTRIES = 128
    private const val SHARED_DIR_NAME = "btr_shared_state"
    private const val TRACKS_FILE_NAME = "playinfo_tracks.json"
    private const val CONCURRENCY_FILE_NAME = "concurrency_state.json"

    private val gson = Gson()
    private val byPathKey = ConcurrentHashMap<String, PlayinfoTrackEntry>()
    private val byParentFileKey = ConcurrentHashMap<String, PlayinfoTrackEntry>()
    private val byFileKey = ConcurrentHashMap<String, PlayinfoTrackEntry>()
    private val refreshersByPath = ConcurrentHashMap<String, PlayinfoRefreshCallback>()
    private val refreshersByParentFile = ConcurrentHashMap<String, PlayinfoRefreshCallback>()
    private val refreshersByFile = ConcurrentHashMap<String, PlayinfoRefreshCallback>()

    private val DISTINCTIVE_ID_REGEX = Regex("""\d{5,}""")
    private val GENERIC_FILE_KEYS = setOf(
        "index.m3u8", "master.m3u8", "playlist.m3u8", "video.m4s", "audio.m4s",
        "stream.m4s", "stream.mp4", "stream.flv", "init.m4s", "chunk.m4s"
    )

    @Volatile
    private var sharedStateDir: File? = null
    private val sharedFileLock = Any()
    private val lastTracksFileCheckMs = AtomicLong(0L)
    @Volatile
    private var lastTracksFileMtime = 0L
    @Volatile
    private var lastTracksFileLength = -1L
    @Volatile
    private var lastTracksContentHash = 0L
    private val lastConcurrencyFileCheckMs = AtomicLong(0L)
    @Volatile
    private var lastConcurrencyFileMtime = 0L
    @Volatile
    private var lastConcurrencyFileLength = -1L

    fun configureSharedStateDir(baseDir: File?) {
        if (baseDir == null) return
        runCatching {
            val dir = File(baseDir, SHARED_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            if (dir.isDirectory) {
                sharedStateDir = dir
            }
        }
    }

    internal fun resetForTesting(clearDir: Boolean = true) {
        byPathKey.clear()
        byParentFileKey.clear()
        byFileKey.clear()
        refreshersByPath.clear()
        refreshersByParentFile.clear()
        refreshersByFile.clear()
        lastTracksFileCheckMs.set(0L)
        lastTracksFileMtime = 0L
        lastTracksFileLength = -1L
        lastTracksContentHash = 0L
        lastConcurrencyFileCheckMs.set(0L)
        lastConcurrencyFileMtime = 0L
        lastConcurrencyFileLength = -1L
        if (clearDir) {
            sharedStateDir = null
        }
    }

    fun looksLikePlayinfoJson(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length < 48) return false
        val hasMediaContainer = text.contains("\"dash\"") ||
            text.contains("\"durl\"") ||
            text.contains("\"video_info\"") ||
            text.contains("\"vod_info\"") ||
            text.contains("\"stream_list\"")
        if (!hasMediaContainer) return false
        return text.contains("bilivideo") ||
            text.contains("akamaized") ||
            text.contains("upgcxcode") ||
            text.contains("bstarstatic") ||
            text.contains("is_drm") ||
            text.contains("drm_tech_type") ||
            text.contains("is_preview")
    }

    private fun unwrapPlayinfoLookupUrl(rawUrl: String): String {
        val step1 = BiliAcceleratorCore.unwrapSmtcdnsUrl(rawUrl).ifBlank { rawUrl }
        val parsed = BiliAcceleratorCore.parseUri(step1) ?: return step1
        val host = parsed.host.lowercase()
        if (host.startsWith("proxy-tf-all-")) {
            val wrapped = parsed.queryParam("url")
            if (!wrapped.isNullOrBlank()) {
                return BiliAcceleratorCore.unwrapSmtcdnsUrl(wrapped).ifBlank { wrapped }
            }
        }
        return step1
    }

    private fun normalizeKeys(rawUrl: String): Triple<String, String, String> {
        val effectiveUrl = unwrapPlayinfoLookupUrl(rawUrl)
        val parsed = BiliAcceleratorCore.parseUri(effectiveUrl) ?: return Triple("", "", "")
        val path = parsed.path.trim().trimEnd('/').lowercase()
        val fileKey = path.substringAfterLast('/')
        val parentAndFile = path.trimStart('/').split('/').let { segs ->
            if (segs.size >= 2) "${segs[segs.size - 2]}/${segs.last()}" else ""
        }
        return Triple(path, parentAndFile, fileKey)
    }

    private fun isDistinctiveFileKey(fileKey: String): Boolean {
        if (fileKey.isBlank()) return false
        val lower = fileKey.lowercase()
        if (lower in GENERIC_FILE_KEYS || lower.startsWith("chunk-") || lower.startsWith("chunk_")) return false
        return DISTINCTIVE_ID_REGEX.containsMatchIn(lower) || lower.length >= 16
    }

    private fun parentFileKeyFromPath(pathKey: String): String {
        val trimmed = pathKey.trimStart('/')
        val segs = trimmed.split('/')
        return if (segs.size >= 2) "${segs[segs.size - 2]}/${segs.last()}" else ""
    }

    fun normalizePathAndFileKey(rawUrl: String): Pair<String, String> {
        val (path, _, fileKey) = normalizeKeys(rawUrl)
        return path to fileKey
    }

    private fun extractEarliestDeadline(urls: List<String>): Long {
        var earliest = 0L
        for (u in urls) {
            val dl = BiliAcceleratorCore.extractUrlDeadlineSeconds(u)
            if (dl > 0L && (earliest == 0L || dl < earliest)) {
                earliest = dl
            }
        }
        return earliest
    }

    fun parsePlayinfoJson(
        jsonText: String,
        sourceUrl: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): List<PlayinfoTrackEntry> {
        if (!looksLikePlayinfoJson(jsonText)) return emptyList()
        val rootElem = runCatching { JsonParser.parseString(jsonText) }.getOrNull() ?: return emptyList()
        if (!rootElem.isJsonObject) return emptyList()
        val rootObj = rootElem.asJsonObject

        val dataObj = rootObj.obj("data")
        val resultObj = rootObj.obj("result")
        val videoInfoObj = dataObj?.obj("video_info") ?: resultObj?.obj("video_info")
        val vodInfoObj = dataObj?.obj("vod_info") ?: resultObj?.obj("vod_info") ?: rootObj.obj("vod_info")
        val viewInfoObj = dataObj?.obj("view_info") ?: resultObj?.obj("view_info") ?: rootObj.obj("view_info")
        val bizInfoObj = dataObj?.obj("play_view_business_info") ?: resultObj?.obj("play_view_business_info")
        val supplementObj = dataObj?.obj("supplement") ?: resultObj?.obj("supplement")
        val bodyObj = videoInfoObj ?: vodInfoObj ?: dataObj ?: resultObj ?: rootObj

        val containers = listOfNotNull(rootObj, dataObj, resultObj, videoInfoObj, vodInfoObj, viewInfoObj, bizInfoObj, supplementObj, bodyObj)

        val rootDrm = containers.any { hasJsonDrmSignal(it) }
        val rootPreview = containers.any { hasJsonPreviewSignal(it) }
        val rootEpisode = BiliAcceleratorCore.isEpisodeMediaUrl(sourceUrl) ||
            sourceUrl.contains("/pgc/", ignoreCase = true) ||
            sourceUrl.contains("/ogv/", ignoreCase = true) ||
            sourceUrl.contains("/bangumi/", ignoreCase = true) ||
            containers.any { hasJsonEpisodeSignal(it) }

        val epId = containers.firstNotNullOfOrNull { c ->
            c.longVal("ep_id") ?: c.longVal("epid") ?: c.longVal("episode_id")
                ?: c.obj("ogv_episode_info")?.longVal("episode_id")
                ?: c.obj("episode_info")?.longVal("ep_id")
                ?: c.obj("episode_info")?.longVal("episode_id")
        } ?: 0L
        val cid = containers.firstNotNullOfOrNull { c ->
            c.longVal("cid") ?: c.obj("arc")?.longVal("cid")
        } ?: 0L

        val entries = mutableListOf<PlayinfoTrackEntry>()
        val dashContainers = listOfNotNull(
            bodyObj.obj("dash"),
            dataObj?.obj("dash"),
            resultObj?.obj("dash"),
            rootObj.obj("dash")
        ).distinct()

        for (dash in dashContainers) {
            collectDashArray(dash.arr("video"), isAudioHint = false, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
            collectDashArray(dash.arr("audio"), isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
            dash.obj("dolby")?.arr("audio")?.let {
                collectDashArray(it, isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
            }
            for (hiResKey in listOf("flac", "loss_less_item")) {
                dash.obj(hiResKey)?.let { flac ->
                    flac.arr("audio")?.let {
                        collectDashArray(it, isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
                    }
                    flac.obj("audio")?.let { singleFlac ->
                        val arr = JsonArray().apply { add(singleFlac) }
                        collectDashArray(arr, isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
                    }
                }
            }
        }

        // stream_list + dash_audio / dolby / loss_less_item (PlayViewUnite / playerunite JSON)
        for (c in containers) {
            c.arr("stream_list")?.let { streamList ->
                for (elem in streamList) {
                    val streamObj = elem.asObjOrNull() ?: continue
                    val streamInfo = streamObj.obj("stream_info")
                    val streamDrm = rootDrm || (streamInfo != null && hasJsonDrmSignal(streamInfo))
                    val streamPreview = rootPreview || (streamInfo != null && hasJsonPreviewSignal(streamInfo))
                    streamObj.obj("dash_video")?.let { dv ->
                        addTrackFromJsonObject(dv, isAudioHint = false, streamDrm, streamPreview, rootEpisode, cid, epId, nowMs, entries)
                    }
                    streamObj.obj("segment_video")?.arr("segment")?.let { segs ->
                        collectDashArray(segs, isAudioHint = false, streamDrm, streamPreview, rootEpisode, cid, epId, nowMs, entries)
                    }
                }
            }
            c.arr("dash_audio")?.let {
                collectDashArray(it, isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
            }
            c.obj("dolby")?.arr("audio")?.let {
                collectDashArray(it, isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
            }
            c.obj("loss_less_item")?.let { lli ->
                lli.arr("audio")?.let {
                    collectDashArray(it, isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
                }
                lli.obj("audio")?.let { singleHiRes ->
                    val arr = JsonArray().apply { add(singleHiRes) }
                    collectDashArray(arr, isAudioHint = true, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
                }
            }
        }

        // durl / durls (MP4/FLV fallback)
        for (c in containers) {
            c.arr("durl")?.let { collectDashArray(it, isAudioHint = false, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries) }
            c.arr("durls")?.forEach { group ->
                group.asObjOrNull()?.arr("durl")?.let {
                    collectDashArray(it, isAudioHint = false, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, entries)
                }
            }
        }

        return entries.distinctBy { "${it.pathKey}|${it.primaryUrl}" }
    }

    private fun collectDashArray(
        arr: JsonArray?,
        isAudioHint: Boolean,
        rootDrm: Boolean,
        rootPreview: Boolean,
        rootEpisode: Boolean,
        cid: Long,
        epId: Long,
        nowMs: Long,
        out: MutableList<PlayinfoTrackEntry>
    ) {
        if (arr == null) return
        for (elem in arr) {
            val obj = elem.asObjOrNull() ?: continue
            addTrackFromJsonObject(obj, isAudioHint, rootDrm, rootPreview, rootEpisode, cid, epId, nowMs, out)
        }
    }

    private fun appendResolvedTrackEntries(
        rawPrimary: String,
        rawBackups: List<String>,
        hasDrm: Boolean,
        isPreview: Boolean,
        isEpisode: Boolean,
        isAudio: Boolean,
        cid: Long,
        epId: Long,
        nowMs: Long,
        out: MutableList<PlayinfoTrackEntry>
    ) {
        val unwrappedPrimary = BiliAcceleratorCore.unwrapSmtcdnsUrl(rawPrimary).ifBlank { rawPrimary }
        val unwrappedBackups = rawBackups
            .filter { it.isNotBlank() }
            .map { BiliAcceleratorCore.unwrapSmtcdnsUrl(it).ifBlank { it } }
            .distinct()
        val allNormalized = (listOf(unwrappedPrimary) + unwrappedBackups).filter { it.isNotBlank() }.distinct()
        if (allNormalized.isEmpty()) return

        val hasCleanOfficial = !hasDrm && !isPreview && allNormalized.any { BiliAcceleratorCore.isCleanUposDonor(it) }
        val effectivePrimary = if (!hasDrm && !isPreview && !BiliAcceleratorCore.isCleanUposDonor(unwrappedPrimary)) {
            allNormalized.firstOrNull { BiliAcceleratorCore.isCleanUposDonor(it) } ?: unwrappedPrimary
        } else {
            unwrappedPrimary
        }

        val effectiveBackups = allNormalized.filter { it != effectivePrimary }.let { remaining ->
            if (hasCleanOfficial) {
                remaining.filter { url ->
                    val parsed = BiliAcceleratorCore.parseUri(url) ?: return@filter false
                    val host = BiliAcceleratorCore.cleanHost(parsed.host)
                        .lowercase()
                        .substringBefore(':')
                    val oddPort = parsed.port != -1 && parsed.port != 80 && parsed.port != 443
                    val hasOsMcdn = parsed.queryParam("os")?.equals("mcdn", ignoreCase = true) == true
                    !BiliAcceleratorCore.isIpAddress(host) &&
                        !BiliAcceleratorCore.isKnownP2pHost(host) &&
                        !BiliAcceleratorCore.isMcdnHost(host) &&
                        !BiliAcceleratorCore.isSchedulerHost(host) &&
                        !oddPort &&
                        !hasOsMcdn
                }
            } else {
                remaining
            }
        }.distinct()

        val deadlineUrls = (listOf(effectivePrimary) + effectiveBackups).ifEmpty { allNormalized }
        val deadline = extractEarliestDeadline(deadlineUrls)
        val seenKeys = HashSet<String>()
        val keyUrls = (listOf(rawPrimary) + rawBackups + allNormalized).filter { it.isNotBlank() }.distinct()
        for (u in keyUrls) {
            val (pathKey, fileKey) = normalizePathAndFileKey(u)
            if (pathKey.isBlank() || !seenKeys.add(pathKey)) continue
            out.add(
                PlayinfoTrackEntry(
                    pathKey = pathKey,
                    fileKey = fileKey,
                    primaryUrl = effectivePrimary,
                    backupUrls = effectiveBackups,
                    deadlineEpochSec = deadline,
                    isAudio = isAudio,
                    hasDrm = hasDrm,
                    isPreview = isPreview,
                    isEpisode = isEpisode,
                    cid = cid,
                    epId = epId,
                    updatedAtMs = nowMs
                )
            )
        }
    }

    private fun addTrackFromJsonObject(
        obj: JsonObject,
        isAudioHint: Boolean,
        rootDrm: Boolean,
        rootPreview: Boolean,
        rootEpisode: Boolean,
        cid: Long,
        epId: Long,
        nowMs: Long,
        out: MutableList<PlayinfoTrackEntry>
    ) {
        val primary = obj.strVal("baseUrl") ?: obj.strVal("base_url") ?: obj.strVal("url") ?: return
        if (primary.isBlank() || !BiliAcceleratorCore.hasMediaSignal(primary)) return
        val backups = mutableListOf<String>()
        for (key in listOf("backupUrl", "backup_url", "backup_url_list")) {
            obj.arr(key)?.forEach { b ->
                val s = b.asStringOrNull()
                if (!s.isNullOrBlank() && s != primary) backups.add(s)
            }
        }
        val allUrls = listOf(primary) + backups
        val hasDrm = rootDrm || hasJsonDrmSignal(obj) || allUrls.any { BiliAcceleratorCore.hasDrmSignal(it) }
        val isPreview = rootPreview || hasJsonPreviewSignal(obj) || allUrls.any { BiliAcceleratorCore.isEpisodePreview(it) }
        val isEpisode = rootEpisode || allUrls.any { BiliAcceleratorCore.isEpisodeMediaUrl(it) }
        val isAudio = isAudioHint || BiliAcceleratorCore.isAudioTrack(primary)
        appendResolvedTrackEntries(
            rawPrimary = primary,
            rawBackups = backups,
            hasDrm = hasDrm,
            isPreview = isPreview,
            isEpisode = isEpisode,
            isAudio = isAudio,
            cid = cid,
            epId = epId,
            nowMs = nowMs,
            out = out
        )
    }

    fun ingestJson(
        jsonText: String,
        sourceUrl: String = "",
        refreshCallback: PlayinfoRefreshCallback? = null
    ): List<PlayinfoTrackEntry> {
        val entries = parsePlayinfoJson(jsonText, sourceUrl)
        if (entries.isEmpty()) return emptyList()
        storeEntries(entries, refreshCallback)
        return entries
    }

    fun parsePlayViewReply(
        reply: Any?,
        sourceTag: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): List<PlayinfoTrackEntry> {
        if (reply == null) return emptyList()
        val className = reply.javaClass.name.lowercase()
        val isEpisodeRoot = BiliAcceleratorCore.isEpisodeMediaUrl(sourceTag) ||
            className.contains(".pgc.") ||
            className.contains(".ogv.") ||
            className.contains("bangumi")

        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val flags = ProtoFlags(isEpisode = isEpisodeRoot)
        val rawTracks = mutableListOf<RawProtoTrack>()

        inspectProtoNode(
            node = reply,
            depth = 0,
            visited = visited,
            flags = flags,
            tracks = rawTracks,
            isAudioContext = false,
            inheritedDrm = false,
            inheritedPreview = false
        )

        if (rawTracks.isEmpty()) return emptyList()
        val entries = mutableListOf<PlayinfoTrackEntry>()
        for (t in rawTracks) {
            val allUrls = (listOf(t.primaryUrl) + t.backupUrls).filter { it.isNotBlank() }.distinct()
            if (allUrls.isEmpty()) continue
            val hasDrm = flags.hasDrm || t.hasDrm || allUrls.any { BiliAcceleratorCore.hasDrmSignal(it) }
            val isPreview = flags.isPreview || t.isPreview || allUrls.any { BiliAcceleratorCore.isEpisodePreview(it) }
            val isEpisode = flags.isEpisode || t.isEpisode || allUrls.any { BiliAcceleratorCore.isEpisodeMediaUrl(it) }
            val isAudio = t.isAudio || BiliAcceleratorCore.isAudioTrack(t.primaryUrl)
            appendResolvedTrackEntries(
                rawPrimary = t.primaryUrl,
                rawBackups = t.backupUrls,
                hasDrm = hasDrm,
                isPreview = isPreview,
                isEpisode = isEpisode,
                isAudio = isAudio,
                cid = flags.cid,
                epId = flags.epId,
                nowMs = nowMs,
                out = entries
            )
        }
        return entries.distinctBy { "${it.pathKey}|${it.primaryUrl}" }
    }

    fun ingestPlayViewReply(
        reply: Any?,
        sourceTag: String = "",
        refreshCallback: PlayinfoRefreshCallback? = null
    ): List<PlayinfoTrackEntry> {
        val entries = parsePlayViewReply(reply, sourceTag)
        if (entries.isEmpty()) return emptyList()
        storeEntries(entries, refreshCallback)
        return entries
    }

    private data class ProtoFlags(
        var hasDrm: Boolean = false,
        var isPreview: Boolean = false,
        var isEpisode: Boolean = false,
        var cid: Long = 0L,
        var epId: Long = 0L
    )

    private data class RawProtoTrack(
        val primaryUrl: String,
        val backupUrls: List<String>,
        val isAudio: Boolean,
        val hasDrm: Boolean = false,
        val isPreview: Boolean = false,
        val isEpisode: Boolean = false
    )

    private fun inspectProtoNode(
        node: Any?,
        depth: Int,
        visited: MutableSet<Any>,
        flags: ProtoFlags,
        tracks: MutableList<RawProtoTrack>,
        isAudioContext: Boolean,
        inheritedDrm: Boolean = false,
        inheritedPreview: Boolean = false
    ) {
        if (node == null || depth > 7) return
        if (node is String || node is Number || node is Boolean || node is Enum<*>) return
        if (!visited.add(node)) return

        if (node is Iterable<*>) {
            for (item in node) {
                inspectProtoNode(item, depth + 1, visited, flags, tracks, isAudioContext, inheritedDrm, inheritedPreview)
            }
            return
        }
        if (node is Array<*>) {
            for (item in node) {
                inspectProtoNode(item, depth + 1, visited, flags, tracks, isAudioContext, inheritedDrm, inheritedPreview)
            }
            return
        }
        if (node is Map<*, *>) {
            for (v in node.values) {
                inspectProtoNode(v, depth + 1, visited, flags, tracks, isAudioContext, inheritedDrm, inheritedPreview)
            }
            return
        }

        val cls = node.javaClass
        val clsName = cls.name.lowercase()
        if (clsName.startsWith("java.") || clsName.startsWith("android.")) return

        if (clsName.contains("pgc") || clsName.contains("ogv") || clsName.contains("bangumi")) {
            flags.isEpisode = true
        }

        val isPerStreamScope = clsName.endsWith("stream") || clsName.contains("\$stream") || clsName.contains("dashitem") || clsName.contains("responseurl")
        val localAudio = isAudioContext || clsName.contains("audio") || clsName.contains("dolby") || clsName.contains("flac")
        var localDrm = inheritedDrm
        var localPreview = inheritedPreview

        val methods = runCatching { cls.methods }.getOrNull().orEmpty()
        var foundBaseUrl: String? = null
        val foundBackups = mutableListOf<String>()
        val childGetters = mutableListOf<Pair<String, Any>>()
        var enumDrmTechResult: Boolean? = null
        var rawDrmTechValue: Int? = null

        // First pass: inspect scalar getters, URLs, and flags before recursing into children
        for (m in methods) {
            if (m.parameterTypes.isNotEmpty()) continue
            val name = m.name
            if (name == "getClass" || name == "hashCode" || name == "toString" || name == "getDefaultInstanceForType" || name == "getSerializedSize" || name == "getParserForType") {
                continue
            }
            when (name) {
                "getIsDrm", "hasDrm", "getWidevine" -> {
                    if (isTruthyValue(invokeZeroArg(m, node))) localDrm = true
                }
                "getDrmTechTypeValue", "getDrmTypeValue" -> {
                    val v = (invokeZeroArg(m, node) as? Number)?.toInt()
                    if (v != null) rawDrmTechValue = v
                }
                "getDrmTechType", "getDrmType" -> {
                    val v = invokeZeroArg(m, node)
                    val active = isDrmEnumOrValueActive(v)
                    if (v is Enum<*> || v is String) {
                        enumDrmTechResult = active
                    }
                    if (active) localDrm = true
                }
                "getPssh" -> {
                    val v = invokeZeroArg(m, node)?.toString()
                    if (!v.isNullOrBlank()) localDrm = true
                }
                "getIsPreview", "getPreview", "getIsTrial", "getTryLook" -> {
                    if (isTruthyValue(invokeZeroArg(m, node))) localPreview = true
                }
                "getWatchTimeLimit" -> {
                    val v = (invokeZeroArg(m, node) as? Number)?.toLong() ?: 0L
                    if (v > 0L) localPreview = true
                }
                "getEpId", "getEpisodeId", "getSeasonId" -> {
                    val v = (invokeZeroArg(m, node) as? Number)?.toLong() ?: 0L
                    if (v > 0L) {
                        flags.isEpisode = true
                        if (name != "getSeasonId" && flags.epId == 0L) flags.epId = v
                    }
                }
                "getCid" -> {
                    val v = (invokeZeroArg(m, node) as? Number)?.toLong() ?: 0L
                    if (v > 0L && flags.cid == 0L) flags.cid = v
                }
                "getVideoType", "getVideoTypeValue" -> {
                    val s = invokeZeroArg(m, node)?.toString()?.uppercase().orEmpty()
                    if (s.contains("PGC") || s.contains("OGV") || s == "2") flags.isEpisode = true
                }
                "getBaseUrl", "getUrl" -> {
                    val s = invokeZeroArg(m, node) as? String
                    if (!s.isNullOrBlank() && BiliAcceleratorCore.hasMediaSignal(s)) {
                        foundBaseUrl = s
                    }
                }
                "getBackupUrlList", "getBackupUrl" -> {
                    val v = invokeZeroArg(m, node)
                    if (v is Iterable<*>) {
                        v.filterIsInstance<String>().filter { BiliAcceleratorCore.hasMediaSignal(it) }.forEach { foundBackups.add(it) }
                    } else if (v is String && BiliAcceleratorCore.hasMediaSignal(v)) {
                        foundBackups.add(v)
                    }
                }
                else -> {
                    if (name.startsWith("get") && !name.endsWith("OrBuilder") && !name.endsWith("Count") && !name.endsWith("Bytes")) {
                        val ret = m.returnType
                        if (!ret.isPrimitive && ret != String::class.java && !Number::class.java.isAssignableFrom(ret)) {
                            val child = invokeZeroArg(m, node)
                            if (child != null && child !== node) {
                                childGetters.add(name to child)
                            }
                        }
                    }
                }
            }
        }
        if (enumDrmTechResult == null && (rawDrmTechValue ?: 0) > 0) {
            localDrm = true
        }

        // Also scan declared fields for obfuscated Protobuf builds (e.g. R8 keeping field names or media URLs)
        val childFields = mutableListOf<Pair<String, Any>>()
        val fields = runCatching { cls.declaredFields }.getOrNull().orEmpty()
        for (f in fields) {
            val fname = f.name.lowercase()
            if (fname.contains("memoized") || fname == "unknownfields" || fname == "default_instance" || fname == "parser") continue
            val valObj = runCatching {
                f.isAccessible = true
                f.get(node)
            }.getOrNull() ?: continue

            when {
                fname.contains("drmtechtype") || fname.contains("drm_tech_type") || fname.contains("drmtype") || fname.contains("drm_type") -> {
                    if (!(valObj is Number && enumDrmTechResult == false) && isDrmEnumOrValueActive(valObj)) {
                        localDrm = true
                    }
                }
                fname.contains("isdrm") || fname.contains("is_drm") || fname.contains("hasdrm") || fname.contains("has_drm") || fname.contains("widevine") -> {
                    if (isTruthyValue(valObj)) localDrm = true
                }
                fname.contains("ispreview") || fname.contains("is_preview") || fname.contains("trylook") || fname.contains("try_look") -> {
                    if (isTruthyValue(valObj)) localPreview = true
                }
                fname.contains("epid") || fname.contains("ep_id") || fname.contains("seasonid") || fname.contains("season_id") -> {
                    val n = (valObj as? Number)?.toLong() ?: 0L
                    if (n > 0L) {
                        flags.isEpisode = true
                        if (!fname.contains("season") && flags.epId == 0L) flags.epId = n
                    }
                }
                valObj is String && BiliAcceleratorCore.hasMediaSignal(valObj) -> {
                    if (fname.contains("backup")) {
                        foundBackups.add(valObj)
                    } else if (foundBaseUrl == null) {
                        foundBaseUrl = valObj
                    }
                }
                valObj is Iterable<*> -> {
                    val strItems = valObj.filterIsInstance<String>().filter { BiliAcceleratorCore.hasMediaSignal(it) }
                    if (strItems.isNotEmpty() && fname.contains("backup")) {
                        foundBackups.addAll(strItems)
                    } else {
                        childFields.add(fname to valObj)
                    }
                }
                !valObj.javaClass.isPrimitive && valObj !is Number && valObj !is Boolean && valObj !is Enum<*> && valObj !is String -> {
                    childFields.add(fname to valObj)
                }
            }
        }

        // If this is a streamInfo sub-object inside a Stream node, inspect it before sibling dashVideo/segmentVideo
        val orderedGetters = childGetters.sortedBy { (name, _) ->
            when {
                name.contains("StreamInfo", ignoreCase = true) || name.contains("ViewInfo", ignoreCase = true) || name.contains("TrialInfo", ignoreCase = true) -> 0
                else -> 1
            }
        }
        val streamContextFlags = if (isPerStreamScope) ProtoFlags(isEpisode = flags.isEpisode) else flags
        for ((name, child) in orderedGetters) {
            val childAudio = localAudio || name.contains("Audio", ignoreCase = true) || name.contains("Dolby", ignoreCase = true) || name.contains("Flac", ignoreCase = true)
            inspectProtoNode(
                node = child,
                depth = depth + 1,
                visited = visited,
                flags = streamContextFlags,
                tracks = tracks,
                isAudioContext = childAudio,
                inheritedDrm = localDrm,
                inheritedPreview = localPreview
            )
            if (isPerStreamScope) {
                if (streamContextFlags.hasDrm) localDrm = true
                if (streamContextFlags.isPreview) localPreview = true
                if (streamContextFlags.isEpisode) flags.isEpisode = true
                if (streamContextFlags.cid != 0L && flags.cid == 0L) flags.cid = streamContextFlags.cid
                if (streamContextFlags.epId != 0L && flags.epId == 0L) flags.epId = streamContextFlags.epId
            }
        }
        for ((fname, valObj) in childFields) {
            val childAudio = localAudio || fname.contains("audio") || fname.contains("dolby") || fname.contains("flac")
            inspectProtoNode(
                node = valObj,
                depth = depth + 1,
                visited = visited,
                flags = streamContextFlags,
                tracks = tracks,
                isAudioContext = childAudio,
                inheritedDrm = localDrm,
                inheritedPreview = localPreview
            )
            if (isPerStreamScope) {
                if (streamContextFlags.hasDrm) localDrm = true
                if (streamContextFlags.isPreview) localPreview = true
                if (streamContextFlags.isEpisode) flags.isEpisode = true
            }
        }

        if (!isPerStreamScope) {
            if (localDrm) flags.hasDrm = true
            if (localPreview) flags.isPreview = true
        }

        val primary = foundBaseUrl ?: foundBackups.firstOrNull()
        if (!primary.isNullOrBlank()) {
            tracks.add(
                RawProtoTrack(
                    primaryUrl = primary,
                    backupUrls = foundBackups.filter { it != primary }.distinct(),
                    isAudio = localAudio,
                    hasDrm = localDrm,
                    isPreview = localPreview,
                    isEpisode = flags.isEpisode
                )
            )
        }
    }

    private fun invokeZeroArg(m: Method, target: Any): Any? {
        return runCatching {
            if (!m.isAccessible) m.isAccessible = true
            m.invoke(target)
        }.getOrNull()
    }

    private fun storeEntries(entries: List<PlayinfoTrackEntry>, refreshCallback: PlayinfoRefreshCallback?) {
        pruneIfNeeded()
        for (entry in entries) {
            val prev = byPathKey[entry.pathKey]
            val merged = if (prev != null && prev.deadlineEpochSec > entry.deadlineEpochSec && !entry.hasDrm && !entry.isPreview) {
                entry.copy(
                    primaryUrl = prev.primaryUrl,
                    backupUrls = prev.backupUrls,
                    deadlineEpochSec = prev.deadlineEpochSec,
                    hasDrm = entry.hasDrm || prev.hasDrm,
                    isPreview = entry.isPreview || prev.isPreview,
                    isEpisode = entry.isEpisode || prev.isEpisode
                )
            } else if (prev != null) {
                entry.copy(
                    hasDrm = entry.hasDrm || prev.hasDrm,
                    isPreview = entry.isPreview || prev.isPreview,
                    isEpisode = entry.isEpisode || prev.isEpisode
                )
            } else {
                entry
            }
            byPathKey[entry.pathKey] = merged
            val parentFileKey = parentFileKeyFromPath(entry.pathKey)
            if (parentFileKey.isNotBlank()) {
                byParentFileKey[parentFileKey] = merged
            }
            if (isDistinctiveFileKey(entry.fileKey)) {
                byFileKey[entry.fileKey] = merged
            }
            if (refreshCallback != null) {
                refreshersByPath[entry.pathKey] = refreshCallback
                if (parentFileKey.isNotBlank()) {
                    refreshersByParentFile[parentFileKey] = refreshCallback
                }
                if (isDistinctiveFileKey(entry.fileKey)) {
                    refreshersByFile[entry.fileKey] = refreshCallback
                }
            }
        }

        persistSharedTracks(entries)

        // Proactively propagate fresh deadlines into any active BtrMediaSession
        val uniquePrimaries = entries
            .filter { !it.hasDrm && !it.isPreview && it.deadlineEpochSec > 0L }
            .distinctBy { it.pathKey }
        for (e in uniquePrimaries) {
            runCatching {
                BtrSessionManager.refreshSignatureIfSamePath(e.primaryUrl, e.backupUrls)
            }
        }
    }

    fun lookup(url: String, forceSharedReload: Boolean = false): PlayinfoTrackEntry? {
        if (url.isBlank()) return null
        val (pathKey, parentFileKey, fileKey) = normalizeKeys(url)
        if (pathKey.isBlank() && fileKey.isBlank()) return null
        val useBareFileKey = isDistinctiveFileKey(fileKey)

        val now = System.currentTimeMillis()
        if (!forceSharedReload) {
            val cached = (if (pathKey.isNotBlank()) byPathKey[pathKey] else null)
                ?: (if (parentFileKey.isNotBlank()) byParentFileKey[parentFileKey] else null)
                ?: (if (useBareFileKey) byFileKey[fileKey] else null)
            if (cached != null && (now - cached.updatedAtMs) <= ENTRY_TTL_MS) {
                return cached
            }
        }

        // Cross-process shared lookup fallback (e.g. main process parsed PlayViewReply, :ijkservice plays it)
        loadSharedTracksIfUpdated(force = forceSharedReload)
        val fromShared = (if (pathKey.isNotBlank()) byPathKey[pathKey] else null)
            ?: (if (parentFileKey.isNotBlank()) byParentFileKey[parentFileKey] else null)
            ?: (if (useBareFileKey) byFileKey[fileKey] else null)
        return if (fromShared != null && (now - fromShared.updatedAtMs) <= ENTRY_TTL_MS) fromShared else null
    }

    fun findFresherTrack(url: String, currentDeadlineSec: Long): PlayinfoTrackEntry? {
        val meta = lookup(url, forceSharedReload = true) ?: return null
        if (meta.hasDrm || meta.isPreview) return null
        return if (meta.deadlineEpochSec > currentDeadlineSec && meta.primaryUrl.isNotBlank()) meta else null
    }

    fun findRefreshCallback(url: String): PlayinfoRefreshCallback? {
        val (pathKey, parentFileKey, fileKey) = normalizeKeys(url)
        if (pathKey.isNotBlank()) {
            refreshersByPath[pathKey]?.let { return it }
        }
        if (parentFileKey.isNotBlank()) {
            refreshersByParentFile[parentFileKey]?.let { return it }
        }
        if (isDistinctiveFileKey(fileKey)) {
            refreshersByFile[fileKey]?.let { return it }
        }
        return null
    }

    private fun pruneIfNeeded() {
        val now = System.currentTimeMillis()
        if (byPathKey.size > MAX_ENTRIES) {
            byPathKey.entries.removeIf { now - it.value.updatedAtMs > ENTRY_TTL_MS }
            while (byPathKey.size > MAX_ENTRIES) {
                val oldestKey = byPathKey.entries.minByOrNull { it.value.updatedAtMs }?.key ?: break
                byPathKey.remove(oldestKey)
            }
        }
        if (byParentFileKey.size > MAX_ENTRIES) {
            byParentFileKey.entries.removeIf { now - it.value.updatedAtMs > ENTRY_TTL_MS }
            while (byParentFileKey.size > MAX_ENTRIES) {
                val oldestKey = byParentFileKey.entries.minByOrNull { it.value.updatedAtMs }?.key ?: break
                byParentFileKey.remove(oldestKey)
            }
        }
        if (byFileKey.size > MAX_ENTRIES) {
            byFileKey.entries.removeIf { now - it.value.updatedAtMs > ENTRY_TTL_MS }
            while (byFileKey.size > MAX_ENTRIES) {
                val oldestKey = byFileKey.entries.minByOrNull { it.value.updatedAtMs }?.key ?: break
                byFileKey.remove(oldestKey)
            }
        }
        if (refreshersByPath.size > MAX_ENTRIES) {
            refreshersByPath.entries.removeIf { !byPathKey.containsKey(it.key) }
        }
        if (refreshersByParentFile.size > MAX_ENTRIES) {
            refreshersByParentFile.entries.removeIf { !byParentFileKey.containsKey(it.key) }
        }
        if (refreshersByFile.size > MAX_ENTRIES) {
            refreshersByFile.entries.removeIf { !byFileKey.containsKey(it.key) }
        }
    }

    private fun crc32Of(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun persistSharedTracks(newEntries: List<PlayinfoTrackEntry>) {
        val dir = sharedStateDir ?: return
        runCatching {
            synchronized(sharedFileLock) {
                val file = File(dir, TRACKS_FILE_NAME)
                val existing = readSharedTracksFile(file).toMutableMap()
                val now = System.currentTimeMillis()
                existing.entries.removeIf { now - it.value.updatedAtMs > ENTRY_TTL_MS }
                for (e in newEntries) {
                    if (e.pathKey.isBlank()) continue
                    val prev = existing[e.pathKey]
                    val keepPrevUrls = prev != null && prev.deadlineEpochSec > e.deadlineEpochSec && !e.hasDrm && !e.isPreview && prev.primaryUrl.isNotBlank()
                    val bestPrimary = when {
                        keepPrevUrls -> prev!!.primaryUrl
                        e.primaryUrl.isNotBlank() -> e.primaryUrl
                        else -> prev?.primaryUrl.orEmpty()
                    }
                    val bestBackups = when {
                        keepPrevUrls -> prev!!.backupUrls
                        prev != null && e.deadlineEpochSec > prev.deadlineEpochSec -> e.backupUrls
                        e.backupUrls.isNotEmpty() -> e.backupUrls
                        else -> prev?.backupUrls.orEmpty()
                    }
                    existing[e.pathKey] = SharedTrackSummary(
                        pathKey = e.pathKey,
                        fileKey = e.fileKey,
                        primaryUrl = bestPrimary,
                        backupUrls = bestBackups,
                        isAudio = e.isAudio,
                        hasDrm = e.hasDrm || (prev?.hasDrm == true),
                        isPreview = e.isPreview || (prev?.isPreview == true),
                        isEpisode = e.isEpisode || (prev?.isEpisode == true),
                        deadlineEpochSec = maxOf(e.deadlineEpochSec, prev?.deadlineEpochSec ?: 0L),
                        updatedAtMs = e.updatedAtMs
                    )
                }
                val trimmed = existing.values
                    .sortedByDescending { it.updatedAtMs }
                    .take(MAX_SHARED_ENTRIES)
                val jsonBytes = gson.toJson(trimmed).toByteArray(Charsets.UTF_8)
                val tmp = File(dir, "$TRACKS_FILE_NAME.tmp.${android.os.Process.myPid()}.${System.nanoTime()}")
                try {
                    tmp.writeBytes(jsonBytes)
                    try {
                        Files.move(
                            tmp.toPath(),
                            file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: Throwable) {
                        Files.move(
                            tmp.toPath(),
                            file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
                lastTracksFileMtime = file.lastModified()
                lastTracksFileLength = file.length()
                lastTracksContentHash = crc32Of(jsonBytes)
            }
        }
    }

    private fun loadSharedTracksIfUpdated(force: Boolean = false) {
        val dir = sharedStateDir ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastTracksFileCheckMs.get() < 250L) return
        lastTracksFileCheckMs.set(now)
        runCatching {
            val file = File(dir, TRACKS_FILE_NAME)
            if (!file.exists()) return
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return
            val crc = crc32Of(bytes)
            val len = bytes.size.toLong()
            if (!force && len == lastTracksFileLength && crc == lastTracksContentHash && lastTracksContentHash != 0L) {
                return
            }
            synchronized(sharedFileLock) {
                val type = object : TypeToken<List<SharedTrackSummary>>() {}.type
                val parsedList: List<SharedTrackSummary> = gson.fromJson(String(bytes, Charsets.UTF_8), type) ?: emptyList()
                for (s in parsedList) {
                    if (s.pathKey.isBlank() || (now - s.updatedAtMs) > ENTRY_TTL_MS) continue
                    val existing = byPathKey[s.pathKey]
                    val incomingFresher = existing == null ||
                        s.updatedAtMs > existing.updatedAtMs ||
                        s.deadlineEpochSec > existing.deadlineEpochSec
                    if (incomingFresher) {
                        val useIncomingUrls = s.primaryUrl.isNotBlank() &&
                            (existing == null || s.deadlineEpochSec >= existing.deadlineEpochSec)
                        val resolvedBackups = when {
                            existing != null && s.deadlineEpochSec > existing.deadlineEpochSec -> s.backupUrls
                            useIncomingUrls && s.backupUrls.isNotEmpty() -> s.backupUrls
                            else -> existing?.backupUrls.orEmpty()
                        }
                        val entry = PlayinfoTrackEntry(
                            pathKey = s.pathKey,
                            fileKey = s.fileKey,
                            primaryUrl = if (useIncomingUrls) s.primaryUrl else existing?.primaryUrl.orEmpty(),
                            backupUrls = resolvedBackups,
                            deadlineEpochSec = maxOf(s.deadlineEpochSec, existing?.deadlineEpochSec ?: 0L),
                            isAudio = s.isAudio,
                            hasDrm = s.hasDrm || (existing?.hasDrm == true),
                            isPreview = s.isPreview || (existing?.isPreview == true),
                            isEpisode = s.isEpisode || (existing?.isEpisode == true),
                            updatedAtMs = maxOf(s.updatedAtMs, existing?.updatedAtMs ?: 0L)
                        )
                        byPathKey[s.pathKey] = entry
                        val parentFileKey = parentFileKeyFromPath(s.pathKey)
                        if (parentFileKey.isNotBlank()) {
                            byParentFileKey[parentFileKey] = entry
                        }
                        if (isDistinctiveFileKey(s.fileKey)) {
                            byFileKey[s.fileKey] = entry
                        }
                    }
                }
                lastTracksFileMtime = file.lastModified()
                lastTracksFileLength = len
                lastTracksContentHash = crc
            }
        }
    }

    private fun readSharedTracksFile(file: File): Map<String, SharedTrackSummary> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<List<SharedTrackSummary>>() {}.type
            val list: List<SharedTrackSummary> = gson.fromJson(file.readText(Charsets.UTF_8), type) ?: emptyList()
            list.associateBy { it.pathKey }
        }.getOrDefault(emptyMap())
    }

    fun saveSharedConcurrencyState(state: SharedConcurrencyState) {
        val dir = sharedStateDir ?: return
        runCatching {
            synchronized(sharedFileLock) {
                val file = File(dir, CONCURRENCY_FILE_NAME)
                val tmp = File(dir, "$CONCURRENCY_FILE_NAME.tmp.${android.os.Process.myPid()}")
                tmp.writeText(gson.toJson(state), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(gson.toJson(state), Charsets.UTF_8)
                    tmp.delete()
                }
                lastConcurrencyFileMtime = file.lastModified()
                lastConcurrencyFileLength = file.length()
            }
        }
    }

    fun loadSharedConcurrencyState(): SharedConcurrencyState? {
        val dir = sharedStateDir ?: return null
        return runCatching {
            val file = File(dir, CONCURRENCY_FILE_NAME)
            if (!file.exists()) return null
            lastConcurrencyFileMtime = file.lastModified()
            lastConcurrencyFileLength = file.length()
            gson.fromJson(file.readText(Charsets.UTF_8), SharedConcurrencyState::class.java)
        }.getOrNull()
    }

    fun loadSharedConcurrencyStateIfUpdated(): SharedConcurrencyState? {
        val dir = sharedStateDir ?: return null
        val now = System.currentTimeMillis()
        if (now - lastConcurrencyFileCheckMs.get() < 250L) return null
        lastConcurrencyFileCheckMs.set(now)
        return runCatching {
            val file = File(dir, CONCURRENCY_FILE_NAME)
            if (!file.exists()) return null
            val mtime = file.lastModified()
            val len = file.length()
            if (mtime <= 0L || (mtime == lastConcurrencyFileMtime && len == lastConcurrencyFileLength)) {
                return null
            }
            synchronized(sharedFileLock) {
                lastConcurrencyFileMtime = file.lastModified()
                lastConcurrencyFileLength = file.length()
                gson.fromJson(file.readText(Charsets.UTF_8), SharedConcurrencyState::class.java)
            }
        }.getOrNull()
    }

    fun clearSharedConcurrencyState() {
        val dir = sharedStateDir ?: return
        runCatching {
            synchronized(sharedFileLock) {
                File(dir, CONCURRENCY_FILE_NAME).delete()
                lastConcurrencyFileMtime = 0L
                lastConcurrencyFileLength = -1L
            }
        }
    }

    // ---- JSON & reflection helpers ----

    private fun hasJsonDrmSignal(obj: JsonObject): Boolean {
        if (obj.boolOrIntTrue("is_drm") || obj.boolOrIntTrue("has_drm") || obj.boolOrIntTrue("widevine")) {
            return true
        }
        val drmTech = obj.get("drm_tech_type")
        if (drmTech != null && !drmTech.isJsonNull && drmTech.isJsonPrimitive) {
            val p = drmTech.asJsonPrimitive
            if (p.isNumber && p.asInt > 0) return true
            if (p.isString && isDrmEnumOrValueActive(p.asString)) return true
        }
        val drmType = obj.get("drm_type")
        if (drmType != null && !drmType.isJsonNull && drmType.isJsonPrimitive) {
            val p = drmType.asJsonPrimitive
            if (p.isNumber && p.asInt > 0) return true
            if (p.isString && isDrmEnumOrValueActive(p.asString)) return true
        }
        if (!obj.strVal("pssh").isNullOrBlank()) return true
        val drmList = obj.arr("drm_list")
        if (drmList != null && drmList.size() > 0) return true
        return false
    }

    private fun hasJsonPreviewSignal(obj: JsonObject): Boolean {
        if (obj.boolOrIntTrue("is_preview") || obj.boolOrIntTrue("preview") || obj.boolOrIntTrue("is_trial") || obj.boolOrIntTrue("try_look")) {
            return true
        }
        val limit = obj.longVal("watch_time_limit") ?: 0L
        if (limit > 0L) return true
        val trialInfo = obj.obj("trial_info")
        if (trialInfo != null && (trialInfo.boolOrIntTrue("is_trial") || (trialInfo.longVal("watch_time_limit") ?: 0L) > 0L || (trialInfo.longVal("trial_time") ?: 0L) > 0L)) {
            return true
        }
        return false
    }

    private fun hasJsonEpisodeSignal(obj: JsonObject): Boolean {
        if ((obj.longVal("ep_id") ?: 0L) > 0L ||
            (obj.longVal("epid") ?: 0L) > 0L ||
            (obj.longVal("episode_id") ?: 0L) > 0L ||
            (obj.longVal("season_id") ?: 0L) > 0L ||
            (obj.longVal("season_type") ?: 0L) > 0L
        ) {
            return true
        }
        if (obj.obj("ogv_episode_info") != null || obj.obj("ogv_season_info") != null) return true
        val biz = obj.strVal("business")?.lowercase()
        val videoType = obj.strVal("video_type")?.lowercase()
        return biz == "pgc" || biz == "ogv" || videoType == "pgc" || videoType == "ogv"
    }

    private fun isTruthyValue(v: Any?): Boolean {
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() > 0
            is String -> v == "1" || v.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun isInactiveDrmName(raw: String): Boolean {
        val s = raw.trim().uppercase()
        return s.isEmpty() ||
            s == "0" ||
            s == "FALSE" ||
            s == "NON" ||
            s == "NONE" ||
            s == "NO_DRM" ||
            s == "NONE_DRM" ||
            s == "UNKNOWN" ||
            s == "UNKNOWN_DRM" ||
            s == "DRM_TECH_TYPE_UNKNOWN" ||
            s == "DRM_TECH_TYPE_NONE" ||
            s == "DRM_TECH_TYPE_NON" ||
            s == "UNRECOGNIZED"
    }

    private fun isDrmEnumOrValueActive(v: Any?): Boolean {
        return when (v) {
            null -> false
            is Boolean -> v
            is Number -> v.toInt() > 0
            is Enum<*> -> !isInactiveDrmName(v.name) && v.ordinal > 0
            is String -> !isInactiveDrmName(v)
            else -> false
        }
    }

    private fun JsonElement.asObjOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonElement.asStringOrNull(): String? = if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.strVal(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.longVal(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.let { el ->
        val p = el.asJsonPrimitive
        when {
            p.isNumber -> runCatching { p.asLong }.getOrNull()
            p.isString -> p.asString.toLongOrNull()
            else -> null
        }
    }
    private fun JsonObject.boolOrIntTrue(name: String): Boolean {
        val el = get(name) ?: return false
        if (!el.isJsonPrimitive) return false
        val p = el.asJsonPrimitive
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asInt > 0
            p.isString -> p.asString == "1" || p.asString.equals("true", ignoreCase = true)
            else -> false
        }
    }
}
