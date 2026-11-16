package io.github.jh_mmm.biliaccelerator.btr

import android.util.Log
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.SharedConcurrencyState
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import okhttp3.Call
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object BtrChunkEngine {

    private const val TAG = "BiliAccelerator-BTR"
    const val CHUNK_SIZE_AUDIO = 128 * 1024
    const val CHUNK_SIZE_VIDEO = 512 * 1024
    const val AUDIO_MAX_CONCURRENCY = 2
    const val PROBE_PREFIX_BYTES = 64 * 1024

    private const val MAX_PREFETCH_CHUNKS = 8           // 基础滑动窗口参考值
    private const val MAX_BUFFER_BYTES = 16L * 1024 * 1024 // 单流内存缓存上限 16MB，防 OOM 同时避免队头阻塞
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36 BiliApp/7800300"
    private const val RESUME_MIN_BYTES = 32 * 1024      // 断点续传拼接最低字节门槛（对齐 BTR 原版）
    private const val CANCEL_SPEED_MIN_BYTES = 48 * 1024L // 被取消竞争请求记录历史带宽门槛（对齐 BTR 48KB）
    private const val DEFAULT_HEDGE_DELAY_MS = 1200L    // 对冲请求延迟触发阈值（毫秒）
    private const val PIECE_ROUNDS = 3                  // 单分片最大重排竞速轮次（对齐 BTR PIECE_ROUNDS）
    private const val SPEED_CUTOFF_RATIO = 12.0         // 淘汰低于最高带宽 1/12 的慢节点（对齐 BTR）
    private const val UNMEASURED_TRIAL_INTERVAL_CHUNKS = 6L
    private const val CONCURRENCY_PERSIST_TTL_MS = 30 * 60 * 1000L
    private const val CONCURRENCY_DISK_THROTTLE_MS = 3_000L
    private val CONCURRENCY_LADDER = intArrayOf(2, 4, 6, 8, 12, 16)

    private class ClientDisconnectException(cause: IOException) : IOException(cause)

    internal fun shouldAllowUnmeasuredTrial(taskIndex: Long, totalTasks: Long): Boolean {
        if (taskIndex < 2L) return false
        if (taskIndex % UNMEASURED_TRIAL_INTERVAL_CHUNKS == 2L) return true
        return totalTasks > 0L && taskIndex >= maxOf(2L, totalTasks - 3L)
    }

    @Volatile
    private var persistedConcurrencyState: SharedConcurrencyState? = null
    private val lastConcurrencyDiskWriteMs = AtomicLong(0L)

    internal fun loadPersistedConcurrencyState(nowMs: Long = System.currentTimeMillis()): SharedConcurrencyState? {
        val fromDisk = PlayinfoRegistry.loadSharedConcurrencyStateIfUpdated()
            ?: if (persistedConcurrencyState == null) PlayinfoRegistry.loadSharedConcurrencyState() else null
        if (fromDisk != null && (persistedConcurrencyState == null || fromDisk.updatedAtMs >= (persistedConcurrencyState?.updatedAtMs ?: 0L))) {
            persistedConcurrencyState = fromDisk
        }
        val state = persistedConcurrencyState ?: return null
        if (state.updatedAtMs > 0L && (nowMs - state.updatedAtMs) > CONCURRENCY_PERSIST_TTL_MS) {
            return null
        }
        return state
    }

    internal fun savePersistedConcurrencyState(
        state: SharedConcurrencyState,
        forceDiskWrite: Boolean = false
    ) {
        persistedConcurrencyState = state
        val now = System.currentTimeMillis()
        val prev = lastConcurrencyDiskWriteMs.get()
        if (forceDiskWrite || (now - prev) >= CONCURRENCY_DISK_THROTTLE_MS) {
            lastConcurrencyDiskWriteMs.set(now)
            PlayinfoRegistry.saveSharedConcurrencyState(state)
        }
    }

    internal fun resetConcurrencyPersistenceForTesting(clearDisk: Boolean = true) {
        persistedConcurrencyState = null
        lastConcurrencyDiskWriteMs.set(0L)
        if (clearDisk) {
            PlayinfoRegistry.clearSharedConcurrencyState()
        }
    }

    private fun daemonThreadFactory(prefix: String): ThreadFactory {
        val seq = AtomicInteger(0)
        return ThreadFactory { r ->
            Thread(r, "$prefix-${seq.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    // 视频分片专用线程池：使用 SynchronousQueue 直接弹性扩容至 max=96，避免有界队列卡死在 corePoolSize
    private val videoWorkerPool = ThreadPoolExecutor(
        8,
        96,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        daemonThreadFactory("BTR-VideoWorker")
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    // 音频分片隔离线程池：独立于视频高并发队列，保证音频帧低延迟不饥饿
    private val audioWorkerPool = ThreadPoolExecutor(
        2,
        24,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        daemonThreadFactory("BTR-AudioWorker")
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    private fun workerPoolFor(isAudio: Boolean): ThreadPoolExecutor =
        if (isAudio) audioWorkerPool else videoWorkerPool

    // 对冲竞速专属独立执行线程池：与主拉取线程隔离，彻底杜绝冲刺期对冲排队饥饿
    private val hedgeWorkerPool = ThreadPoolExecutor(
        0,
        96,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        daemonThreadFactory("BTR-HedgeWorker")
    )

    // 全局分发调度线程池
    private val globalDispatcherPool = ThreadPoolExecutor(
        0,
        64,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        daemonThreadFactory("BTR-Dispatcher")
    )

    // 活跃网络调用与对冲等待锁映射表：支持按 streamCallKey 细粒度隔离与按 sessionId 会话级全量清理
    private val streamActiveCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val sessionStreamKeys = ConcurrentHashMap<String, MutableSet<String>>()
    private val streamSessionMap = ConcurrentHashMap<String, String>()
    private val streamHedgeLocks = ConcurrentHashMap<String, MutableSet<Object>>()
    private val sessionSwrrWeights = ConcurrentHashMap<String, MutableMap<String, Double>>()
    private val transferSeq = AtomicLong(0L)

    private fun bindStreamSession(streamCallKey: String, sessionId: String) {
        streamSessionMap[streamCallKey] = sessionId
        sessionStreamKeys.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(streamCallKey)
    }

    private fun registerStreamCall(streamCallKey: String, sessionId: String, call: Call) {
        bindStreamSession(streamCallKey, sessionId)
        streamActiveCalls.computeIfAbsent(streamCallKey) { ConcurrentHashMap.newKeySet() }.add(call)
    }

    private fun unregisterStreamCall(streamCallKey: String, call: Call) {
        streamActiveCalls[streamCallKey]?.remove(call)
    }

    private fun registerHedgeLock(streamCallKey: String, hedgeLock: Object) {
        streamHedgeLocks.computeIfAbsent(streamCallKey) { ConcurrentHashMap.newKeySet() }.add(hedgeLock)
    }

    private fun unregisterHedgeLock(streamCallKey: String, hedgeLock: Object) {
        streamHedgeLocks[streamCallKey]?.remove(hedgeLock)
    }

    private fun cancelStreamCalls(streamCallKey: String) {
        streamActiveCalls.remove(streamCallKey)?.forEach { call ->
            runCatching { call.cancel() }
        }
        streamHedgeLocks.remove(streamCallKey)?.forEach { lockObj ->
            synchronized(lockObj) { lockObj.notifyAll() }
        }
        streamSessionMap.remove(streamCallKey)?.let { sid ->
            sessionStreamKeys[sid]?.let { keys ->
                keys.remove(streamCallKey)
                if (keys.isEmpty()) {
                    sessionStreamKeys.remove(sid, keys)
                }
            }
        }
    }

    fun cancelSessionCalls(sessionId: String) {
        sessionStreamKeys.remove(sessionId)?.toList()?.forEach { streamCallKey ->
            cancelStreamCalls(streamCallKey)
        }
        sessionSwrrWeights.remove(sessionId)
    }

    // 节点 / 路由 EWMA 测速追踪器（对齐 BTR routeKey = host + path 隔离策略）
    private class HostSpeedInfo(
        @Volatile var avgBps: Double = 0.0,
        @Volatile var lastMeasuredAt: Long = 0L,
        @Volatile var sampleCount: Int = 0
    )

    private val hostSpeeds = ConcurrentHashMap<String, HostSpeedInfo>()
    private const val SPEED_MEASURE_TTL_MS = 90_000L
    private const val MAX_TRACKED_HOSTS = 512

    internal fun routeKey(url: String): String {
        val uri = BiliAcceleratorCore.parseUri(url) ?: return extractHostname(url).lowercase()
        val host = uri.host.lowercase()
        val path = uri.path
        return if (path.isNotBlank() && path != "/") "$host$path" else host
    }

    fun recordRouteSpeed(url: String, bytes: Long, latencyMs: Long) {
        val key = routeKey(url)
        if (key.isNotBlank()) {
            recordHostSpeed(key, bytes, latencyMs)
        }
    }

    fun getRouteSpeed(url: String): Double {
        val key = routeKey(url)
        return if (key.isNotBlank()) getHostSpeed(key) else 0.0
    }

    fun recordHostSpeed(host: String, bytes: Long, latencyMs: Long) {
        if (host.isBlank() || bytes < 32 * 1024 || latencyMs <= 0) return
        val bps = (bytes * 1000.0) / latencyMs
        val now = System.currentTimeMillis()
        if (hostSpeeds.size > MAX_TRACKED_HOSTS) {
            hostSpeeds.entries.removeIf { now - it.value.lastMeasuredAt > SPEED_MEASURE_TTL_MS }
        }
        val info = hostSpeeds.getOrPut(host) { HostSpeedInfo() }
        synchronized(info) {
            if (now - info.lastMeasuredAt > SPEED_MEASURE_TTL_MS || info.avgBps <= 0.0) {
                info.avgBps = bps
            } else {
                // EWMA 衰减平滑：70% 历史 + 30% 新测速
                info.avgBps = info.avgBps * 0.7 + bps * 0.3
            }
            info.lastMeasuredAt = now
            info.sampleCount++
        }
    }

    fun getHostSpeed(host: String): Double {
        val info = hostSpeeds[host] ?: return 0.0
        val now = System.currentTimeMillis()
        return if (now - info.lastMeasuredAt < SPEED_MEASURE_TTL_MS) info.avgBps else 0.0
    }

    private val cdnPenalties = ConcurrentHashMap<String, Long>()

    private fun penalizeHost(host: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (cdnPenalties.size > MAX_TRACKED_HOSTS) {
            cdnPenalties.entries.removeIf { it.value <= now }
        }
        cdnPenalties[host] = now + durationMs
    }

    data class ChunkTask(
        val index: Long,
        val startByte: Long,
        val endByte: Long,
        @Volatile var data: ByteArray? = null,
        @Volatile var isSuccess: Boolean = false,
        @Volatile var isFailed: Boolean = false,
        @Volatile var errorMessage: String? = null,
        @Volatile var receivedBytes: Int = 0,
        @Volatile var prefixData: ByteArray? = null
    ) {
        @Volatile
        var lastHttpCode: Int = 0
        private var prefixOwnerId: Long = 0L
        private var prefixOwnerFailed: Boolean = false
        private var liveBuffer: ByteArray? = null
        private var liveBufferPos: Int = 0
        private var prefixDataLen: Int = 0

        fun tryClaimPrefixProducer(attemptId: Long): Boolean {
            synchronized(this) {
                if (isSuccess || prefixData != null) return false
                if (prefixOwnerId == 0L || (prefixOwnerFailed && liveBufferPos == 0)) {
                    prefixOwnerId = attemptId
                    prefixOwnerFailed = false
                    return true
                }
                return prefixOwnerId == attemptId
            }
        }

        fun appendLivePrefix(attemptId: Long, buf: ByteArray, len: Int, expectedTotal: Int) {
            if (len <= 0 || expectedTotal <= 0) return
            synchronized(this) {
                if (prefixOwnerId != attemptId || isSuccess) return
                var dest = liveBuffer
                if (dest == null || dest.size < expectedTotal) {
                    val newDest = ByteArray(expectedTotal)
                    if (dest != null && liveBufferPos > 0) {
                        System.arraycopy(dest, 0, newDest, 0, minOf(liveBufferPos, newDest.size))
                    }
                    dest = newDest
                    liveBuffer = dest
                }
                val writable = minOf(len, expectedTotal - liveBufferPos)
                if (writable > 0) {
                    System.arraycopy(buf, 0, dest, liveBufferPos, writable)
                    liveBufferPos += writable
                    receivedBytes = maxOf(liveBufferPos, prefixDataLen)
                }
            }
        }

        fun saveFallbackPrefixIfBetter(bytes: ByteArray, length: Int) {
            if (length < RESUME_MIN_BYTES) return
            synchronized(this) {
                if (isSuccess) return
                val currentBest = maxOf(liveBufferPos, prefixDataLen)
                if (length > currentBest) {
                    prefixData = if (bytes.size == length) bytes.copyOf() else bytes.copyOf(length)
                    prefixDataLen = length
                    receivedBytes = length
                }
            }
        }

        fun consumeOwnedLiveBuffer(attemptId: Long): ByteArray? {
            synchronized(this) {
                if (prefixOwnerId != attemptId) return null
                val buf = liveBuffer
                val size = liveBufferPos
                liveBuffer = null
                liveBufferPos = 0
                prefixOwnerId = 0L
                prefixOwnerFailed = false
                receivedBytes = prefixDataLen
                if (buf == null || size <= 0) return null
                return if (buf.size == size) buf else buf.copyOf(size)
            }
        }

        fun releasePrefixProducer(attemptId: Long, keepPartial: Boolean) {
            synchronized(this) {
                if (prefixOwnerId == attemptId) {
                    prefixOwnerFailed = true
                    if (!keepPartial || liveBufferPos < RESUME_MIN_BYTES) {
                        prefixOwnerId = 0L
                        liveBuffer = null
                        liveBufferPos = 0
                        receivedBytes = prefixDataLen
                    }
                }
            }
        }

        fun takePrefixSnapshot(minResumeBytes: Int, expectedTotal: Int): ByteArray? {
            synchronized(this) {
                if (isSuccess) return null
                val liveBuf = liveBuffer
                val liveSize = if (liveBuf != null && liveBufferPos in minResumeBytes until expectedTotal) liveBufferPos else 0
                val external = prefixData
                val savedSize = if (external != null && prefixDataLen in minResumeBytes until expectedTotal && external.size >= prefixDataLen) prefixDataLen else 0
                return when {
                    liveSize >= savedSize && liveSize > 0 -> liveBuf!!.copyOf(liveSize)
                    savedSize > 0 -> if (external!!.size == savedSize) external else external.copyOf(savedSize)
                    else -> null
                }
            }
        }

        fun clearPrefixBuffersOnWin() {
            synchronized(this) {
                prefixData = null
                prefixDataLen = 0
                liveBuffer = null
                liveBufferPos = 0
                prefixOwnerId = 0L
                prefixOwnerFailed = false
            }
        }

        fun resetPrefix() {
            synchronized(this) {
                prefixData = null
                prefixDataLen = 0
                liveBuffer = null
                liveBufferPos = 0
                prefixOwnerId = 0L
                prefixOwnerFailed = false
                receivedBytes = 0
            }
        }

        fun releaseBuffers() {
            synchronized(this) {
                data = null
                prefixData = null
                prefixDataLen = 0
                liveBuffer = null
                liveBufferPos = 0
                prefixOwnerId = 0L
                prefixOwnerFailed = false
                receivedBytes = 0
            }
        }
    }

    /**
     * 自适应动态并发控制器（对齐 BTR / idm-downloader.js createAutoConcurrency）：
     * - 维护 [2, 4, 6, 8, 12, 16] 并发阶梯，严格受限于 [minConcurrency, maxConcurrency]
     * - 仅当全槽位占满且存在真实排队等待 (active >= limit && queued > 0) 时标记为饱和，跟踪滑动窗口饱和占比
     * - 播放器卡顿 (onStall)、低缓冲饥饿 (onBufferHunger)、连接排队超时 (onSlow) 驱动阶梯升档（受硬限制上限保护）
     * - 试探无增益 (>=8%) 时回退并软休息该档位；412/429 降档并设置硬休息上限且跨会话/跨进程持久化
     * - 需求持续空闲 (>=8s) 时平滑降档以降低功耗，且不污染跨会话学习基线
     */
    internal class DynamicConcurrencyController(
        initialConcurrency: Int,
        internal val autoScale: Boolean,
        internal val minConcurrency: Int = 2,
        internal val maxConcurrency: Int = 16,
        initialNowMs: Long = System.currentTimeMillis(),
        private val enablePersistence: Boolean = false
    ) : PlayerConcurrencySink {
        private companion object {
            const val SAT_WINDOW_MS = 5_000L
            const val STEP_COOLDOWN_MS = 2_500L
            const val TRIAL_REST_MS = 90_000L
            const val STALL_DEBOUNCE_MS = 3_000L
            const val IDLE_STEP_DOWN_MS = 8_000L
        }

        private val lock = Any()
        private var ladderIdx: Int
        @Volatile
        var currentConcurrency: Int
            private set
        @Volatile
        var peakConcurrency: Int
            private set

        private var intervalStartMs: Long = initialNowMs
        private var intervalBytes: Long = 0L
        private var bestBps: Double = 0.0
        private var lastTrialDirection: Int = 0
        private var trialStalled: Boolean = false
        private var prevIndexBeforeTrial: Int = 0
        private var pushbackCooldownUntilMs: Long = 0L
        private var lastChangedAtMs: Long = 0L
        private var hasDemandMetrics: Boolean = false
        private var lastSaturatedAtMs: Long = 0L
        private var idleSinceMs: Long = initialNowMs
        private var isSaturated: Boolean = false
        private var saturatedSinceMs: Long = 0L
        private val saturatedSpans = ArrayList<Pair<Long, Long>>()
        private val softRestUntil = LongArray(CONCURRENCY_LADDER.size)
        private var hardCapIdx: Int = maxIdxForLimit(maxConcurrency)
        private var hardCapUntilMs: Long = 0L
        private var pushbackStreak: Int = 0
        private var lastSlowAtMs: Long = 0L
        private var lastStallAtMs: Long = 0L
        private var lastHungerStepAtMs: Long = 0L
        private var persistedLearnedConcurrency: Int

        init {
            val loaded = if (enablePersistence && autoScale) loadPersistedConcurrencyState(initialNowMs) else null
            var effectiveTarget = initialConcurrency.coerceIn(minConcurrency, maxConcurrency)
            if (loaded != null) {
                var effectiveCeil = maxConcurrency
                if (loaded.hardCapConcurrency > 0 && loaded.hardCapUntilMs > initialNowMs) {
                    val clampedCap = loaded.hardCapConcurrency.coerceIn(minConcurrency, maxConcurrency)
                    hardCapIdx = maxIdxForLimit(clampedCap)
                    hardCapUntilMs = loaded.hardCapUntilMs
                    effectiveCeil = minOf(effectiveCeil, clampedCap)
                }
                if (loaded.learnedConcurrency > 0) {
                    effectiveTarget = loaded.learnedConcurrency.coerceIn(minConcurrency, effectiveCeil)
                } else {
                    effectiveTarget = effectiveTarget.coerceIn(minConcurrency, effectiveCeil)
                }
            }
            val idx = CONCURRENCY_LADDER.indexOfFirst { it >= effectiveTarget }
                .let { if (it == -1) CONCURRENCY_LADDER.lastIndex else it }
                .coerceAtMost(allowedMaxIndex(initialNowMs))
            ladderIdx = idx
            currentConcurrency = CONCURRENCY_LADDER[idx].coerceIn(minConcurrency, maxConcurrency)
            peakConcurrency = currentConcurrency
            persistedLearnedConcurrency = currentConcurrency
        }

        private fun maxIdxForLimit(limit: Int): Int {
            var best = 0
            for (i in CONCURRENCY_LADDER.indices) {
                if (CONCURRENCY_LADDER[i] <= limit) best = i
            }
            return best
        }

        private fun allowedMaxIndex(nowMs: Long): Int {
            val limitIdx = maxIdxForLimit(maxConcurrency)
            return if (nowMs < hardCapUntilMs) minOf(limitIdx, hardCapIdx) else limitIdx
        }

        private fun saturationLocked(nowMs: Long): Double {
            val from = nowMs - SAT_WINDOW_MS
            val it = saturatedSpans.iterator()
            while (it.hasNext()) {
                if (it.next().second <= from) it.remove()
            }
            var busyMs = 0L
            for ((sFrom, sTo) in saturatedSpans) {
                busyMs += maxOf(0L, sTo - maxOf(sFrom, from))
            }
            if (isSaturated) {
                busyMs += maxOf(0L, nowMs - maxOf(saturatedSinceMs, from))
            }
            return minOf(1.0, busyMs.toDouble() / SAT_WINDOW_MS)
        }

        private fun syncCrossProcessHardCapLocked(nowMs: Long) {
            if (!enablePersistence || !autoScale) return
            val remote = loadPersistedConcurrencyState(nowMs) ?: return
            if (remote.hardCapConcurrency > 0 && remote.hardCapUntilMs > nowMs && remote.hardCapUntilMs > hardCapUntilMs) {
                val clampedCap = remote.hardCapConcurrency.coerceIn(minConcurrency, maxConcurrency)
                hardCapIdx = minOf(hardCapIdx, maxIdxForLimit(clampedCap))
                hardCapUntilMs = remote.hardCapUntilMs
                val maxIdx = allowedMaxIndex(nowMs)
                if (ladderIdx > maxIdx) {
                    ladderIdx = maxIdx
                    currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                    lastTrialDirection = 0
                }
            }
        }

        private fun persistState(nowMs: Long, forceDiskWrite: Boolean = false) {
            if (!enablePersistence || !autoScale) return
            val hardCap = if (nowMs < hardCapUntilMs) CONCURRENCY_LADDER[hardCapIdx].coerceIn(minConcurrency, maxConcurrency) else 0
            val hardUntil = if (nowMs < hardCapUntilMs) hardCapUntilMs else 0L
            savePersistedConcurrencyState(
                SharedConcurrencyState(
                    learnedConcurrency = persistedLearnedConcurrency,
                    hardCapConcurrency = hardCap,
                    hardCapUntilMs = hardUntil,
                    updatedAtMs = nowMs
                ),
                forceDiskWrite = forceDiskWrite
            )
        }

        private fun stepUpLocked(nowMs: Long, strong: Boolean): Boolean {
            syncCrossProcessHardCapLocked(nowMs)
            if (lastChangedAtMs > 0L && (nowMs - lastChangedAtMs) < STEP_COOLDOWN_MS) return false
            if (!strong && nowMs < pushbackCooldownUntilMs) return false
            val hardMax = allowedMaxIndex(nowMs)
            if (ladderIdx >= hardMax) return false
            var next = ladderIdx + 1
            while (next <= hardMax) {
                val resting = nowMs < softRestUntil[next]
                if (!resting) break
                if (!strong) return false
                next++
            }
            if (next > hardMax || CONCURRENCY_LADDER[next] > maxConcurrency) return false
            prevIndexBeforeTrial = ladderIdx
            ladderIdx = next
            currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
            if (currentConcurrency > peakConcurrency) peakConcurrency = currentConcurrency
            lastTrialDirection = 1
            trialStalled = false
            lastChangedAtMs = nowMs
            intervalStartMs = nowMs
            intervalBytes = 0L
            return true
        }

        fun recordDemand(
            active: Int,
            limit: Int,
            queued: Int,
            nowMs: Long = System.currentTimeMillis()
        ) {
            if (!autoScale) return
            synchronized(lock) {
                hasDemandMetrics = true
                val saturatedNow = active >= limit && queued > 0
                if (saturatedNow != isSaturated) {
                    if (isSaturated && nowMs > saturatedSinceMs) {
                        saturatedSpans.add(saturatedSinceMs to nowMs)
                    }
                    isSaturated = saturatedNow
                    if (saturatedNow) {
                        saturatedSinceMs = nowMs
                    }
                    saturationLocked(nowMs)
                }
                if (saturatedNow) {
                    lastSaturatedAtMs = nowMs
                    idleSinceMs = nowMs
                } else if (
                    (nowMs - idleSinceMs) >= IDLE_STEP_DOWN_MS &&
                    nowMs >= pushbackCooldownUntilMs &&
                    ladderIdx > 0 &&
                    CONCURRENCY_LADDER[ladderIdx - 1] >= minConcurrency
                ) {
                    ladderIdx--
                    currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                    lastTrialDirection = 0
                    trialStalled = false
                    idleSinceMs = nowMs
                }
            }
        }

        fun recordProgress(bytes: Long, nowMs: Long = System.currentTimeMillis()) {
            if (!autoScale || bytes <= 0L) return
            synchronized(lock) {
                syncCrossProcessHardCapLocked(nowMs)
                intervalBytes += bytes
                val elapsed = nowMs - intervalStartMs
                if (elapsed < 1000L || intervalBytes < 256 * 1024L) return

                val currentBps = (intervalBytes * 1000.0) / elapsed.coerceAtLeast(1L)
                intervalStartMs = nowMs
                intervalBytes = 0L

                if (nowMs < pushbackCooldownUntilMs) {
                    if (bestBps <= 0.0) bestBps = currentBps
                    return
                }

                if (lastTrialDirection > 0) {
                    val gained = currentBps >= bestBps * 1.08
                    if (gained || trialStalled) {
                        if (currentBps > bestBps) bestBps = currentBps
                        lastTrialDirection = 0
                        trialStalled = false
                        pushbackStreak = 0
                        persistedLearnedConcurrency = currentConcurrency
                        persistState(nowMs)
                    } else if (!hasDemandMetrics || isSaturated || saturationLocked(nowMs) >= 0.5) {
                        val failedIdx = ladderIdx
                        softRestUntil[failedIdx] = nowMs + TRIAL_REST_MS
                        ladderIdx = prevIndexBeforeTrial
                        currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                        lastTrialDirection = 0
                        trialStalled = false
                        lastChangedAtMs = nowMs
                        pushbackCooldownUntilMs = nowMs + 6_000L
                        persistedLearnedConcurrency = currentConcurrency
                        persistState(nowMs)
                    } else {
                        lastTrialDirection = 0
                        trialStalled = false
                    }
                    return
                }

                if (currentBps > bestBps) {
                    bestBps = currentBps
                }

                val hasRecentPressure = !hasDemandMetrics ||
                    isSaturated ||
                    (lastSaturatedAtMs > 0L && (nowMs - lastSaturatedAtMs) <= 2_500L && saturationLocked(nowMs) >= 0.4)
                if (hasRecentPressure) {
                    stepUpLocked(nowMs, strong = false)
                } else if (hasDemandMetrics &&
                    !isSaturated &&
                    (nowMs - idleSinceMs) >= IDLE_STEP_DOWN_MS &&
                    ladderIdx > 0 &&
                    CONCURRENCY_LADDER[ladderIdx - 1] >= minConcurrency
                ) {
                    ladderIdx--
                    currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                    idleSinceMs = nowMs
                }
            }
        }

        fun onPushback(nowMs: Long = System.currentTimeMillis(), isHard: Boolean = true): Int {
            synchronized(lock) {
                if (ladderIdx > 0 && CONCURRENCY_LADDER[ladderIdx - 1] >= minConcurrency) {
                    ladderIdx--
                }
                currentConcurrency = CONCURRENCY_LADDER[ladderIdx].coerceIn(minConcurrency, maxConcurrency)
                lastTrialDirection = 0
                trialStalled = false
                lastChangedAtMs = nowMs
                pushbackCooldownUntilMs = maxOf(pushbackCooldownUntilMs, nowMs + 15_000L)
                if (isHard) {
                    pushbackStreak++
                    val restDurationMs = minOf(180_000L, 45_000L * (1L shl minOf(pushbackStreak - 1, 2)))
                    hardCapIdx = minOf(hardCapIdx, ladderIdx)
                    hardCapUntilMs = maxOf(hardCapUntilMs, nowMs + restDurationMs)
                }
                persistedLearnedConcurrency = currentConcurrency
                intervalStartMs = nowMs
                intervalBytes = 0L
                persistState(nowMs, forceDiskWrite = true)
                return currentConcurrency
            }
        }

        override fun onStall(nowMs: Long) {
            if (!autoScale) return
            synchronized(lock) {
                if (lastStallAtMs > 0L && (nowMs - lastStallAtMs) < STALL_DEBOUNCE_MS) return
                lastStallAtMs = nowMs
                if (lastTrialDirection > 0) {
                    trialStalled = true
                }
                if (stepUpLocked(nowMs, strong = true)) {
                    persistedLearnedConcurrency = currentConcurrency
                    persistState(nowMs, forceDiskWrite = true)
                }
            }
        }

        override fun onSlow(nowMs: Long) {
            if (!autoScale) return
            synchronized(lock) {
                if (lastSlowAtMs > 0L && (nowMs - lastSlowAtMs) < STEP_COOLDOWN_MS) return
                lastSlowAtMs = nowMs
                val saturated = !hasDemandMetrics || isSaturated || saturationLocked(nowMs) >= 0.6
                if (saturated && stepUpLocked(nowMs, strong = false)) {
                    persistedLearnedConcurrency = currentConcurrency
                    persistState(nowMs)
                }
            }
        }

        override fun onBufferHunger(bufferedBytes: Long, nowMs: Long) {
            if (!autoScale) return
            synchronized(lock) {
                hasDemandMetrics = true
                lastSaturatedAtMs = nowMs
                idleSinceMs = nowMs
                if (lastHungerStepAtMs > 0L && (nowMs - lastHungerStepAtMs) < STEP_COOLDOWN_MS) return
                if (stepUpLocked(nowMs, strong = false)) {
                    lastHungerStepAtMs = nowMs
                    persistedLearnedConcurrency = currentConcurrency
                    persistState(nowMs)
                }
            }
        }
    }

    private class StreamStatsTracker(
        val sessionId: String,
        val primaryHost: String,
        @Volatile var concurrency: Int,
        val proxyPort: Int,
        val isEpisode: Boolean = false,
        val isAudio: Boolean = false
    ) {
        private val transferId = transferSeq.incrementAndGet()
        val totalChunks = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val totalLatency = AtomicLong(0L)
        val hitHosts = ConcurrentHashMap.newKeySet<String>()

        fun recordChunk(host: String, bytes: Long, latencyMs: Long) {
            totalChunks.incrementAndGet()
            totalBytes.addAndGet(bytes)
            totalLatency.addAndGet(latencyMs)
            hitHosts.add(host)
        }

        fun finishAndReport() {
            val count = totalChunks.get()
            if (count == 0) return

            val bytes = totalBytes.get()
            val avgLatency = totalLatency.get() / count
            val mbStr = String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0))

            val prefix = when {
                isEpisode -> "BTR剧集加速(兼容模式)"
                isAudio || sessionId.startsWith("audio_") || primaryHost.contains("audio") -> "BTR音频优化"
                else -> "BTR并发加速"
            }
            val summaryReason = "$prefix · ${count}分片(${mbStr}MB) · ${hitHosts.size}个CDN · 均时${avgLatency}ms"

            val result = RewriteResult(
                changed = true,
                originalUrl = "",
                finalUrl = "",
                originalHost = primaryHost,
                targetHost = "127.0.0.1:$proxyPort (${concurrency}线程)",
                reason = summaryReason,
                isPcdn = false,
                isMcdn = false,
                segmentKey = "btr_stream_${sessionId}_$transferId",
                isBtr = true,
                btrChunks = count,
                btrBytes = bytes,
                btrConcurrency = concurrency,
                btrCdnCount = hitHosts.size,
                btrAvgLatencyMs = avgLatency
            )
            RemoteClient.notifyRewrite(result)
        }
    }

    /**
     * 自适应切片尺寸计算：
     * 以用户设定的 [baseChunkSizeKb] 为 15MB~60MB 标准档基准，按媒体体积在 256KB~2048KB 间比例缩放：
     * - 音频流固定保持 128KB 极低延时
     * - 未开启自动或总长未知时严格采用 [baseChunkSizeKb]
     */
    fun calculateDynamicChunkSize(
        totalLength: Long,
        baseChunkSizeKb: Int,
        autoChunkSize: Boolean,
        isAudio: Boolean
    ): Long {
        if (isAudio) return 128 * 1024L
        val baseBytes = (baseChunkSizeKb.coerceIn(128, 2048) * 1024).toLong()
        if (!autoChunkSize || totalLength <= 0L) return baseBytes
        val scaled = when {
            totalLength < 15 * 1024 * 1024L -> baseBytes / 2
            totalLength < 60 * 1024 * 1024L -> baseBytes
            totalLength < 250 * 1024 * 1024L -> baseBytes * 2
            else -> baseBytes * 4
        }
        return scaled.coerceIn(256 * 1024L, 2048 * 1024L)
    }

    fun pipeStream(
        session: BtrMediaSession,
        requestedStart: Long = 0L,
        requestedEnd: Long? = null, // null 表示直至文件尾部
        isHeadOnly: Boolean = false,
        clientOut: OutputStream,
        concurrency: Int = 6,
        chunkSizeKb: Int = 512,
        autoConcurrency: Boolean = true,
        autoChunkSize: Boolean = true,
        proxyPort: Int = -1,
        parsedRange: ParsedRange = ParsedRange(
            hasRange = requestedStart > 0 || requestedEnd != null,
            start = requestedStart,
            end = requestedEnd
        ),
        keepAlive: Boolean = false,
        requestUserAgent: String? = null,
        requestReferer: String? = null
    ): Boolean {
        if (parsedRange.isInvalid) {
            sendHttp416(clientOut, session.totalLength.takeIf { it > 0L })
            return false
        }

        val isAudio = session.isAudio || BiliAcceleratorCore.isAudioTrack(session.primaryUrl)
        val effectiveUserAgent = requestUserAgent?.takeIf { it.isNotBlank() }
            ?: session.userAgent?.takeIf { it.isNotBlank() }
            ?: DEFAULT_USER_AGENT
        val effectiveReferer = requestReferer?.takeIf { it.isNotBlank() } ?: session.referer

        val requestedBytes = if (parsedRange.hasRange && parsedRange.end != null && parsedRange.end >= parsedRange.start) {
            parsedRange.end - parsedRange.start + 1
        } else -1L

        val baseProbeSize = PROBE_PREFIX_BYTES.toLong()
        val initialProbeChunkSize = if (requestedBytes in 1 until baseProbeSize) {
            requestedBytes
        } else {
            baseProbeSize
        }

        val userMaxCon = concurrency.coerceIn(2, 16)
        val isEpisode = session.isEpisode
        val minCon = 2
        val maxCon = when {
            isAudio -> AUDIO_MAX_CONCURRENCY
            isEpisode -> minOf(userMaxCon, 6).coerceIn(minCon, 8)
            else -> userMaxCon
        }
        val initialCon = when {
            isAudio -> AUDIO_MAX_CONCURRENCY
            isEpisode -> maxCon
            autoConcurrency -> minOf(userMaxCon, 6).coerceIn(minCon, maxCon)
            else -> userMaxCon
        }
        val useAuto = !isAudio && !isEpisode && autoConcurrency
        val targetMaxCon = if (useAuto) maxCon else initialCon
        val existingController = session.concurrencyController as? DynamicConcurrencyController
        val concurrencyController = if (
            existingController != null &&
            existingController.autoScale == useAuto &&
            existingController.minConcurrency == minCon &&
            existingController.maxConcurrency == targetMaxCon
        ) {
            existingController
        } else {
            DynamicConcurrencyController(
                initialConcurrency = initialCon,
                autoScale = useAuto,
                minConcurrency = minCon,
                maxConcurrency = targetMaxCon,
                enablePersistence = useAuto
            ).also { session.concurrencyController = it }
        }

        BtrSessionManager.maybeTriggerProactiveRefresh(session)
        var candidateUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
        val primaryHost = BiliAcceleratorCore.cleanHost(extractHostname(session.primaryUrl))
        val streamCallKey = "${session.sessionId}_${System.nanoTime()}"
        bindStreamSession(streamCallKey, session.sessionId)

        val statsTracker = StreamStatsTracker(
            sessionId = session.sessionId,
            primaryHost = primaryHost,
            concurrency = concurrencyController.currentConcurrency,
            proxyPort = proxyPort,
            isEpisode = isEpisode,
            isAudio = isAudio
        )

        try {
            val hasCachedMetadata = session.totalLength > 0L
            val totalLength: Long
            val contentType: String
            var firstChunk: ByteArray? = null
            var probeServingHost = primaryHost
            var probeLatencyMs = 20L
            var currentOffset: Long
            val effectiveStart: Long
            val effectiveEnd: Long?
            val actualEnd: Long
            var deferHeadersUntilFirstChunk = false

            if (parsedRange.isSuffix) {
                if (parsedRange.suffixLength <= 0L) {
                    sendHttp416(clientOut, session.totalLength.takeIf { it > 0L })
                    return false
                }
                // Suffix Range 模式: bytes=-suffixLength (如 bytes=-500)
                if (hasCachedMetadata) {
                    totalLength = session.totalLength
                    contentType = session.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                } else {
                    var outcome = probeMediaOutcome(
                        urls = candidateUrls,
                        session = session,
                        startOffset = 0L,
                        chunkSize = initialProbeChunkSize,
                        userAgent = effectiveUserAgent,
                        referer = effectiveReferer,
                        concurrencyController = concurrencyController,
                        streamCallKey = streamCallKey
                    )
                    if (outcome.result == null &&
                        outcome.sawForbidden403 &&
                        BtrSessionManager.maybeTriggerProactiveRefresh(session, forceOnAuthFailure = true)
                    ) {
                        candidateUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
                        outcome = probeMediaOutcome(
                            urls = candidateUrls,
                            session = session,
                            startOffset = 0L,
                            chunkSize = initialProbeChunkSize,
                            userAgent = effectiveUserAgent,
                            referer = effectiveReferer,
                            concurrencyController = concurrencyController,
                            streamCallKey = streamCallKey
                        )
                    }
                    val probeResult = outcome.result
                    if (probeResult == null) {
                        if (outcome.sawRangeNotSatisfiable416 && !outcome.sawForbidden403) {
                            val knownTotal = outcome.totalLengthFrom416?.takeIf { it > 0L }
                                ?: session.totalLength.takeIf { it > 0L }
                            if (knownTotal != null && session.totalLength <= 0L) {
                                session.totalLength = knownTotal
                            }
                            sendHttp416(clientOut, knownTotal)
                            return false
                        }
                        sendProbeFailureError(clientOut, session, outcome.sawForbidden403)
                        return false
                    }
                    if (probeResult.isNonRange) {
                        sendHttpError(clientOut, 502, "Bad Gateway - Upstream Ignored Suffix Range")
                        return false
                    }
                    totalLength = probeResult.totalLength
                    contentType = probeResult.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                    probeServingHost = probeResult.servingHost.ifBlank { primaryHost }
                    probeLatencyMs = probeResult.probeLatencyMs
                    if (totalLength > 0L) {
                        session.totalLength = totalLength
                        session.contentType = contentType
                    }
                    if (totalLength > 0L && probeResult.firstChunkData != null && totalLength <= probeResult.firstChunkData.size) {
                        firstChunk = probeResult.firstChunkData
                    }
                }

                if (totalLength <= 0L) {
                    sendHttp416(clientOut, null)
                    return false
                }
                effectiveStart = maxOf(0L, totalLength - parsedRange.suffixLength)
                effectiveEnd = totalLength - 1L
                actualEnd = effectiveEnd
                if (effectiveStart >= totalLength) {
                    sendHttp416(clientOut, totalLength)
                    return false
                }

                if (firstChunk != null && effectiveStart < firstChunk.size) {
                    val offsetInFirst = effectiveStart.toInt()
                    val len = (actualEnd - effectiveStart + 1).toInt()
                    firstChunk = firstChunk.copyOfRange(offsetInFirst, minOf(firstChunk.size, offsetInFirst + len))
                    currentOffset = effectiveStart + firstChunk.size
                } else {
                    firstChunk = null
                    currentOffset = effectiveStart
                }
            } else {
                effectiveStart = parsedRange.start
                effectiveEnd = parsedRange.end

                if (effectiveEnd != null && effectiveEnd < effectiveStart) {
                    sendHttp416(clientOut, session.totalLength.takeIf { it > 0L })
                    return false
                }

                if (hasCachedMetadata && effectiveStart >= session.totalLength) {
                    sendHttp416(clientOut, session.totalLength)
                    return false
                }

                if (hasCachedMetadata && effectiveStart > 0L && !isHeadOnly) {
                    // Seek 延迟写头优化：立即并行拉取分片，待第 0 片成功后再写 206 响应头；若 403/502 仍可完整返回错误码
                    totalLength = session.totalLength
                    contentType = session.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                    firstChunk = null
                    currentOffset = effectiveStart
                    actualEnd = when {
                        effectiveEnd != null -> minOf(effectiveEnd, totalLength - 1L)
                        else -> totalLength - 1L
                    }
                    deferHeadersUntilFirstChunk = true
                } else {
                    // 首片 64KB 竞速探针 (多源并发对冲探针，快速返回 HTTP 响应头)
                    var outcome = probeMediaOutcome(
                        urls = candidateUrls,
                        session = session,
                        startOffset = effectiveStart,
                        chunkSize = initialProbeChunkSize,
                        userAgent = effectiveUserAgent,
                        referer = effectiveReferer,
                        concurrencyController = concurrencyController,
                        streamCallKey = streamCallKey
                    )
                    if (outcome.result == null &&
                        outcome.sawForbidden403 &&
                        BtrSessionManager.maybeTriggerProactiveRefresh(session, forceOnAuthFailure = true)
                    ) {
                        candidateUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
                        outcome = probeMediaOutcome(
                            urls = candidateUrls,
                            session = session,
                            startOffset = effectiveStart,
                            chunkSize = initialProbeChunkSize,
                            userAgent = effectiveUserAgent,
                            referer = effectiveReferer,
                            concurrencyController = concurrencyController,
                            streamCallKey = streamCallKey
                        )
                    }
                    val probeResult = outcome.result
                    if (probeResult == null) {
                        if (outcome.sawRangeNotSatisfiable416 && !outcome.sawForbidden403) {
                            val knownTotal = outcome.totalLengthFrom416?.takeIf { it > 0L }
                                ?: session.totalLength.takeIf { it > 0L }
                            if (knownTotal != null && session.totalLength <= 0L) {
                                session.totalLength = knownTotal
                            }
                            sendHttp416(clientOut, knownTotal)
                            return false
                        }
                        sendProbeFailureError(clientOut, session, outcome.sawForbidden403)
                        return false
                    }

                    if (probeResult.isNonRange) {
                        if (effectiveStart > 0L) {
                            sendHttpError(clientOut, 502, "Bad Gateway - Upstream Ignored Range Request")
                            return false
                        }
                        return pipeNonRangeStream(
                            url = probeResult.url,
                            session = session,
                            isHeadOnly = isHeadOnly,
                            userAgent = effectiveUserAgent,
                            referer = effectiveReferer,
                            clientOut = clientOut,
                            statsTracker = statsTracker,
                            streamCallKey = streamCallKey
                        )
                    }

                    totalLength = probeResult.totalLength
                    contentType = probeResult.contentType ?: if (isAudio) "audio/mp4" else "video/mp4"
                    probeServingHost = probeResult.servingHost.ifBlank { primaryHost }
                    probeLatencyMs = probeResult.probeLatencyMs
                    if (totalLength > 0L) {
                        session.totalLength = totalLength
                        session.contentType = contentType
                        if (effectiveStart >= totalLength) {
                            sendHttp416(clientOut, totalLength)
                            return false
                        }
                    }

                    firstChunk = probeResult.firstChunkData
                    actualEnd = when {
                        effectiveEnd != null -> if (totalLength > 0L) minOf(effectiveEnd, totalLength - 1L) else effectiveEnd
                        totalLength > 0L -> totalLength - 1L
                        else -> -1L
                    }
                    currentOffset = effectiveStart + (firstChunk?.size ?: 0)
                }
            }

            if (effectiveEnd != null && actualEnd >= 0L && actualEnd < effectiveStart) {
                sendHttp416(clientOut, totalLength.takeIf { it > 0L })
                return false
            }

            // 根据探针测得或已缓存的媒体总长度，自适应计算后续分片拉取的最佳 ChunkSize
            val activeChunkSize = calculateDynamicChunkSize(
                totalLength = totalLength,
                baseChunkSizeKb = chunkSizeKb,
                autoChunkSize = autoChunkSize,
                isAudio = isAudio
            )

            // 2. 构造 HTTP 206 Partial Content (或 200 OK) 响应头
            val isPartial = parsedRange.hasRange || effectiveStart > 0L || effectiveEnd != null
            val hasExactTotalRange = totalLength > 0L && actualEnd >= effectiveStart
            val hasBoundedClientRange = effectiveEnd != null && actualEnd >= effectiveStart
            val canKeepAliveResponse = keepAlive && hasExactTotalRange

            fun buildResponseHeadersBytes(): ByteArray {
                val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
                val sb = StringBuilder(statusLine)
                sb.append("Content-Type: $contentType\r\n")
                sb.append("Accept-Ranges: bytes\r\n")

                if (canKeepAliveResponse) {
                    sb.append("Connection: keep-alive\r\n")
                    sb.append("Keep-Alive: timeout=15, max=100\r\n")
                } else {
                    sb.append("Connection: close\r\n")
                }

                if (hasExactTotalRange) {
                    val contentLength = actualEnd - effectiveStart + 1L
                    sb.append("Content-Length: $contentLength\r\n")
                    if (isPartial) {
                        sb.append("Content-Range: bytes $effectiveStart-$actualEnd/$totalLength\r\n")
                    }
                } else if (hasBoundedClientRange) {
                    val contentLength = actualEnd - effectiveStart + 1L
                    sb.append("Content-Length: $contentLength\r\n")
                    if (isPartial) {
                        sb.append("Content-Range: bytes $effectiveStart-$actualEnd/*\r\n")
                    }
                } else if (totalLength > 0L && !isPartial) {
                    sb.append("Content-Length: $totalLength\r\n")
                }
                sb.append("\r\n")
                return sb.toString().toByteArray(Charsets.UTF_8)
            }

            var headersWritten = false
            if (!deferHeadersUntilFirstChunk) {
                clientOut.write(buildResponseHeadersBytes())
                clientOut.flush()
                headersWritten = true
            }

            if (isHeadOnly) return canKeepAliveResponse

            // 3. 写出首片探测数据（仅在未跳过探针且有首片时）
            if (firstChunk != null && firstChunk.isNotEmpty()) {
                val bytesToWrite = if (actualEnd >= effectiveStart) {
                    val needed = (actualEnd - effectiveStart + 1).toInt()
                    if (needed < firstChunk.size) firstChunk.copyOf(needed) else firstChunk
                } else firstChunk

                clientOut.write(bytesToWrite)
                clientOut.flush()
                statsTracker.recordChunk(probeServingHost, bytesToWrite.size.toLong(), probeLatencyMs)
                concurrencyController.recordProgress(bytesToWrite.size.toLong())
                currentOffset = effectiveStart + bytesToWrite.size
            }

            // 未知总长且无客户端结束边界：顺序流式拉取至上游 EOF，禁止 64KB 后静默截断
            if (actualEnd < 0L) {
                pipeRemainingSequential(
                    session = session,
                    startOffset = currentOffset,
                    chunkSize = activeChunkSize,
                    userAgent = effectiveUserAgent,
                    referer = effectiveReferer,
                    clientOut = clientOut,
                    statsTracker = statsTracker,
                    streamCallKey = streamCallKey
                )
                return false
            }

            // 已在首片探针中完成全部范围传输
            if (currentOffset > actualEnd) {
                return canKeepAliveResponse
            }

            // 4. 懒加载切片任务切分与自适应并发流水线
            val remainingBytes = actualEnd - currentOffset + 1L
            val totalTasks = (remainingBytes + activeChunkSize - 1L) / activeChunkSize
            val taskMap = ConcurrentHashMap<Long, ChunkTask>()
            val completionLock = Object()
            val workerGate = Object()
            val activeWorkers = AtomicInteger(0)
            val isClientAborted = AtomicBoolean(false)
            val deliveredIndex = AtomicLong(0L)
            val workerPool = workerPoolFor(isAudio)

            val maxWindowByBytes = (MAX_BUFFER_BYTES / activeChunkSize.coerceAtLeast(64 * 1024L))
                .toInt()
                .coerceIn(MAX_PREFETCH_CHUNKS, 32)
            val dispatchedIndex = AtomicLong(0L)

            fun computeQueuedPressure(limit: Int, dispIdx: Long, delivIdx: Long, active: Int): Int {
                val win = minOf(maxWindowByBytes, maxOf(12, limit * 2))
                val readyLimit = minOf(totalTasks, delivIdx + win)
                val ready = (readyLimit - dispIdx).coerceAtLeast(0L)
                return if (active >= limit && ready > 0L) ready.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
            }

            val dispatchRunnable = Runnable {
                var dispatchIdx = 0L
                var lastGateDemandMs = System.currentTimeMillis()
                while (dispatchIdx < totalTasks && !isClientAborted.get()) {
                    synchronized(workerGate) {
                        while (!isClientAborted.get() &&
                            (activeWorkers.get() >= concurrencyController.currentConcurrency ||
                                (dispatchIdx - deliveredIndex.get()) >= minOf(maxWindowByBytes, maxOf(12, concurrencyController.currentConcurrency * 2)))
                        ) {
                            val waitNow = System.currentTimeMillis()
                            if ((waitNow - lastGateDemandMs) >= 500L) {
                                lastGateDemandMs = waitNow
                                val lim = concurrencyController.currentConcurrency
                                val act = activeWorkers.get()
                                concurrencyController.recordDemand(
                                    active = act,
                                    limit = lim,
                                    queued = computeQueuedPressure(lim, dispatchIdx, deliveredIndex.get(), act),
                                    nowMs = waitNow
                                )
                            }
                            try {
                                workerGate.wait(25)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                    if (isClientAborted.get()) break

                    val chunkStart = currentOffset + dispatchIdx * activeChunkSize
                    val chunkEnd = minOf(chunkStart + activeChunkSize - 1L, actualEnd)
                    val task = ChunkTask(dispatchIdx, chunkStart, chunkEnd)
                    taskMap[dispatchIdx] = task

                    val now = System.currentTimeMillis()
                    lastGateDemandMs = now
                    val curLimit = concurrencyController.currentConcurrency
                    val curActive = activeWorkers.incrementAndGet()
                    dispatchIdx++
                    dispatchedIndex.set(dispatchIdx)
                    concurrencyController.recordDemand(
                        active = curActive,
                        limit = curLimit,
                        queued = computeQueuedPressure(curLimit, dispatchIdx, deliveredIndex.get(), curActive),
                        nowMs = now
                    )

                    try {
                        workerPool.execute {
                            try {
                                if (!isClientAborted.get()) {
                                    fetchChunkWithFailover(
                                        task = task,
                                        session = session,
                                        userAgent = effectiveUserAgent,
                                        referer = effectiveReferer,
                                        statsTracker = statsTracker,
                                        concurrencyController = concurrencyController,
                                        isClientAborted = isClientAborted,
                                        streamCallKey = streamCallKey,
                                        totalTasks = totalTasks
                                    )
                                }
                            } finally {
                                val remActive = activeWorkers.decrementAndGet()
                                val limAfter = concurrencyController.currentConcurrency
                                concurrencyController.recordDemand(
                                    active = remActive,
                                    limit = limAfter,
                                    queued = computeQueuedPressure(limAfter, dispatchedIndex.get(), deliveredIndex.get(), remActive),
                                    nowMs = System.currentTimeMillis()
                                )
                                synchronized(workerGate) {
                                    workerGate.notifyAll()
                                }
                                synchronized(completionLock) {
                                    completionLock.notifyAll()
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        val remActive = activeWorkers.decrementAndGet()
                        val limAfter = concurrencyController.currentConcurrency
                        concurrencyController.recordDemand(
                            active = remActive,
                            limit = limAfter,
                            queued = computeQueuedPressure(limAfter, dispatchedIndex.get(), deliveredIndex.get(), remActive),
                            nowMs = System.currentTimeMillis()
                        )
                        task.isFailed = true
                        task.errorMessage = "Worker pool rejection: ${t.message}"
                        synchronized(workerGate) {
                            workerGate.notifyAll()
                        }
                        synchronized(completionLock) {
                            completionLock.notifyAll()
                        }
                    }
                }
            }

            val dispatchFuture = try {
                globalDispatcherPool.submit(dispatchRunnable)
            } catch (_: RejectedExecutionException) {
                val t = Thread(dispatchRunnable, "BTR-Dispatcher-Overflow").apply { isDaemon = true; start() }
                CompletableFuture<Void?>().also { f ->
                    Thread { runCatching { t.join() }; f.complete(null) }.apply { isDaemon = true; start() }
                }
            }

            try {
                var nextDeliverIndex = 0L
                var waitingChunkIdx = -1L
                var waitChunkStartedMs = 0L
                var lastDeliveryDemandMs = System.currentTimeMillis()
                var lastHungerSignalMs = 0L
                while (nextDeliverIndex < totalTasks && !isClientAborted.get()) {
                    val currentTask = taskMap[nextDeliverIndex]
                    if (currentTask == null || (currentTask.data == null && !currentTask.isFailed)) {
                        val nowWait = System.currentTimeMillis()
                        if (waitingChunkIdx != nextDeliverIndex) {
                            waitingChunkIdx = nextDeliverIndex
                            waitChunkStartedMs = nowWait
                        }
                        if ((nowWait - lastDeliveryDemandMs) >= 500L) {
                            lastDeliveryDemandMs = nowWait
                            val lim = concurrencyController.currentConcurrency
                            val act = activeWorkers.get()
                            concurrencyController.recordDemand(
                                active = act,
                                limit = lim,
                                queued = computeQueuedPressure(lim, dispatchedIndex.get(), nextDeliverIndex, act),
                                nowMs = nowWait
                            )
                        }
                        if ((nowWait - waitChunkStartedMs) >= 1200L && (nowWait - lastHungerSignalMs) >= 1500L && activeWorkers.get() > 0) {
                            lastHungerSignalMs = nowWait
                            concurrencyController.onBufferHunger(0L, nowWait)
                            synchronized(workerGate) { workerGate.notifyAll() }
                        }
                        synchronized(completionLock) {
                            val task = taskMap[nextDeliverIndex]
                            if (!isClientAborted.get() && (task == null || (task.data == null && !task.isFailed))) {
                                completionLock.wait(400)
                            }
                        }
                        continue
                    }

                    if (currentTask.isFailed || currentTask.data == null) {
                        Log.e(TAG, "Chunk task #$nextDeliverIndex failed (${currentTask.errorMessage}). Aborting stream delivery.")
                        isClientAborted.set(true)
                        if (!headersWritten) {
                            sendProbeFailureError(
                                clientOut = clientOut,
                                session = session,
                                sawForbidden403 = currentTask.lastHttpCode == 403
                            )
                            headersWritten = true
                        }
                        return false
                    }

                    try {
                        if (!headersWritten) {
                            clientOut.write(buildResponseHeadersBytes())
                            headersWritten = true
                        }
                        val data = currentTask.data
                        if (data != null) {
                            clientOut.write(data)
                            clientOut.flush()
                            statsTracker.concurrency = concurrencyController.peakConcurrency
                        }
                        currentTask.releaseBuffers()
                        taskMap.remove(nextDeliverIndex)
                        nextDeliverIndex++
                        waitingChunkIdx = -1L
                        deliveredIndex.set(nextDeliverIndex)
                        val delivNow = System.currentTimeMillis()
                        lastDeliveryDemandMs = delivNow
                        val lim = concurrencyController.currentConcurrency
                        val act = activeWorkers.get()
                        concurrencyController.recordDemand(
                            active = act,
                            limit = lim,
                            queued = computeQueuedPressure(lim, dispatchedIndex.get(), nextDeliverIndex, act),
                            nowMs = delivNow
                        )
                        synchronized(workerGate) {
                            workerGate.notifyAll()
                        }
                    } catch (_: IOException) {
                        Log.i(TAG, "Client disconnected (Seek or stop). Session=${session.sessionId}")
                        isClientAborted.set(true)
                        return false
                    }
                }
                return (nextDeliverIndex >= totalTasks && !isClientAborted.get() && canKeepAliveResponse)
            } finally {
                isClientAborted.set(true)
                synchronized(workerGate) {
                    workerGate.notifyAll()
                }
                dispatchFuture.cancel(true)
                cancelStreamCalls(streamCallKey)
                taskMap.values.forEach { it.releaseBuffers() }
                taskMap.clear()
            }
        } finally {
            cancelStreamCalls(streamCallKey)
            statsTracker.finishAndReport()
        }
    }

    private data class ProbeResult(
        val url: String,
        val totalLength: Long,
        val contentLength: Long,
        val contentType: String?,
        val firstChunkData: ByteArray?,
        val servingHost: String,
        val probeLatencyMs: Long,
        val isNonRange: Boolean = false
    )

    private data class ProbeOutcome(
        val result: ProbeResult?,
        val sawForbidden403: Boolean,
        val sawRangeNotSatisfiable416: Boolean = false,
        val totalLengthFrom416: Long? = null
    )

    private data class ContentRangeInfo(
        val start: Long,
        val end: Long?,
        val total: Long?
    )

    private val attemptSeq = AtomicLong(0L)
    private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+|\*)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    private fun parseContentRange(cr: String?): ContentRangeInfo? {
        if (cr.isNullOrBlank()) return null
        val full = CONTENT_RANGE_REGEX.find(cr)
        if (full != null) {
            val start = full.groupValues[1].toLongOrNull() ?: return null
            val end = full.groupValues[2].toLongOrNull()
            val total = full.groupValues[3].toLongOrNull()
            return ContentRangeInfo(start, end, total)
        }
        val fallbackStart = parseRangeStart(cr) ?: return null
        val fallbackTotal = cr.substringAfterLast('/', "").trim().toLongOrNull()
        return ContentRangeInfo(fallbackStart, null, fallbackTotal)
    }

    internal fun isValidContentRange(
        code: Int,
        cr: String?,
        expectedStart: Long,
        session: BtrMediaSession,
        expectedEnd: Long? = null
    ): Boolean {
        if (code != 206) return cr.isNullOrBlank()
        if (cr.isNullOrBlank()) return false
        val info = parseContentRange(cr) ?: return false
        if (info.start != expectedStart) return false
        if (info.total != null && session.totalLength > 0L && info.total != session.totalLength) {
            return false
        }
        if (expectedEnd != null && info.end != null) {
            val eofEnd = info.total?.let { it - 1L }
            if (info.end != expectedEnd && info.end != eofEnd) {
                return false
            }
        }
        return true
    }

    private fun isUrlExpired(session: BtrMediaSession): Boolean {
        val dl = session.urlDeadlineEpochSec
        return dl > 0L && (System.currentTimeMillis() / 1000L) >= dl
    }

    private fun sendProbeFailureError(
        clientOut: OutputStream,
        session: BtrMediaSession,
        sawForbidden403: Boolean
    ) {
        if (sawForbidden403 || isUrlExpired(session)) {
            sendHttpError(clientOut, 403, "Forbidden - Upstream URL Expired or Refused")
        } else {
            sendHttpError(clientOut, 502, "Bad Gateway - Upstream CDN Unreachable")
        }
    }

    private fun pipeNonRangeStream(
        url: String,
        session: BtrMediaSession,
        isHeadOnly: Boolean,
        userAgent: String,
        referer: String,
        clientOut: OutputStream,
        statsTracker: StreamStatsTracker,
        streamCallKey: String
    ): Boolean {
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", userAgent)
            .header("Connection", "close")
        if (isHeadOnly) builder.head()
        val request = builder.build()

        val call = okClient.newCall(request)
        registerStreamCall(streamCallKey, session.sessionId, call)
        return try {
            call.execute().use { response ->
                val code = response.code
                val cr = response.header("Content-Range")
                if (code !in 200..299 || (code == 200 && !cr.isNullOrBlank())) {
                    val status = if (code == 403) 403 else 502
                    val msg = if (code == 403) "Forbidden" else "Bad Gateway"
                    sendHttpError(clientOut, status, msg)
                    return false
                }
                val cl = response.body?.contentLength() ?: -1L
                val ct = response.header("Content-Type") ?: if (session.isAudio) "audio/mp4" else "video/mp4"
                val reason = if (code == 206) "Partial Content" else "OK"

                val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
                sb.append("Content-Type: $ct\r\n")
                if (cl > 0L) sb.append("Content-Length: $cl\r\n")
                sb.append("Connection: close\r\n\r\n")
                clientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
                clientOut.flush()

                if (!isHeadOnly) {
                    var totalBytes = 0L
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(PROBE_PREFIX_BYTES)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            clientOut.write(buffer, 0, read)
                            totalBytes += read
                        }
                    }
                    clientOut.flush()
                    statsTracker.recordChunk(host, totalBytes, System.currentTimeMillis() - startTime)
                }
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            unregisterStreamCall(streamCallKey, call)
        }
    }

    private fun pipeRemainingSequential(
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long,
        userAgent: String,
        referer: String,
        clientOut: OutputStream,
        statsTracker: StreamStatsTracker,
        streamCallKey: String
    ) {
        var offset = startOffset
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
        val buffer = ByteArray(16 * 1024)
        var didRefresh403 = false

        while (true) {
            val reqEnd = offset + chunkSize - 1L
            val activeUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
            val candidates = sortUrlsByHealthAndSpeed(activeUrls, offset / chunkSize.coerceAtLeast(1L), session)
            var progressed = false
            var reachedEof = false
            var saw403 = false

            for (url in candidates) {
                val host = extractHostname(url)
                val startTime = System.currentTimeMillis()
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$offset-$reqEnd")
                    .header("Referer", referer)
                    .header("User-Agent", userAgent)
                    .header("Connection", "keep-alive")
                    .build()
                val call = okClient.newCall(req)
                registerStreamCall(streamCallKey, session.sessionId, call)
                var chunkBytes = 0L
                try {
                    call.execute().use { response ->
                        val code = response.code
                        if (code == 416) {
                            reachedEof = true
                            return@use
                        }
                        if (code != 206) {
                            if (code == 403) saw403 = true
                            session.banList.recordHttpFailure(url, host, if (code == 200) 410 else code)
                            return@use
                        }
                        val cr = response.header("Content-Range")
                        if (!isValidContentRange(code, cr, offset, session)) {
                            session.banList.recordHttpFailure(url, host, 410)
                            return@use
                        }
                        val crInfo = parseContentRange(cr)
                        val expectedBodyLen = response.body?.contentLength() ?: -1L
                        val bodyStream = response.body?.byteStream() ?: return@use
                        var read: Int
                        while (bodyStream.read(buffer).also { read = it } != -1) {
                            try {
                                clientOut.write(buffer, 0, read)
                            } catch (ce: IOException) {
                                throw ClientDisconnectException(ce)
                            }
                            chunkBytes += read
                        }
                        try {
                            clientOut.flush()
                        } catch (ce: IOException) {
                            throw ClientDisconnectException(ce)
                        }
                        if (chunkBytes == 0L) {
                            session.banList.recordTransportFailure(host, 15_000L, url = url, receivedBytes = 0L)
                            penalizeHost(host, 12_000L)
                            return@use
                        }
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        statsTracker.recordChunk(host, chunkBytes, latency)
                        offset += chunkBytes
                        progressed = true
                        val knownTotal = crInfo?.total
                        if (expectedBodyLen > 0L && chunkBytes < expectedBodyLen) {
                            session.banList.recordTransportFailure(host, 15_000L, url = url, receivedBytes = chunkBytes)
                            penalizeHost(host, 12_000L)
                        } else {
                            recordRouteSpeed(url, chunkBytes, latency)
                            session.banList.recordSuccess(url, host)
                            if (knownTotal != null && offset >= knownTotal) {
                                reachedEof = true
                            } else if (chunkBytes < chunkSize) {
                                reachedEof = true
                            }
                        }
                    }
                } catch (_: ClientDisconnectException) {
                    return
                } catch (_: IOException) {
                    if (chunkBytes > 0L) {
                        try {
                            clientOut.flush()
                        } catch (_: IOException) {
                            return
                        }
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        statsTracker.recordChunk(host, chunkBytes, latency)
                        if (chunkBytes >= CANCEL_SPEED_MIN_BYTES) {
                            recordRouteSpeed(url, chunkBytes, latency)
                        }
                        offset += chunkBytes
                        progressed = true
                    }
                    session.banList.recordTransportFailure(host, 15_000L, url = url, receivedBytes = chunkBytes)
                    penalizeHost(host, 12_000L)
                } catch (_: Exception) {
                    session.banList.recordTransportFailure(host, 15_000L, url = url, receivedBytes = chunkBytes)
                } finally {
                    unregisterStreamCall(streamCallKey, call)
                }
                if (reachedEof || progressed) break
            }

            if (!reachedEof && !progressed && saw403 && !didRefresh403 &&
                BtrSessionManager.maybeTriggerProactiveRefresh(session, forceOnAuthFailure = true)
            ) {
                didRefresh403 = true
                continue
            }
            if (reachedEof || !progressed) break
        }
    }

    private fun executeSingleProbe(
        url: String,
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long,
        userAgent: String,
        referer: String,
        okClient: okhttp3.OkHttpClient,
        primaryHost: String,
        concurrencyController: DynamicConcurrencyController?,
        activeCalls: MutableSet<Call>?,
        isDecided: AtomicBoolean?,
        streamCallKey: String?,
        sawForbidden403: AtomicBoolean?,
        sawRangeNotSatisfiable416: AtomicBoolean? = null,
        totalLengthFrom416: AtomicLong? = null
    ): ProbeResult? {
        if (isDecided?.get() == true) return null
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val endOffset = startOffset + chunkSize - 1
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$startOffset-$endOffset")
            .header("Referer", referer)
            .header("User-Agent", userAgent)
            .header("Connection", "keep-alive")
            .build()

        val call = okClient.newCall(request)
        activeCalls?.add(call)
        if (streamCallKey != null) {
            registerStreamCall(streamCallKey, session.sessionId, call)
        }
        return try {
            call.execute().use { response ->
                if (isDecided?.get() == true) return null
                val code = response.code
                if (code == 200 || code == 206) {
                    val cr = response.header("Content-Range")
                    if (code == 200 && !cr.isNullOrBlank()) {
                        session.banList.recordHttpFailure(url, host, 410)
                        if (host == primaryHost) {
                            reportFailover(host, "首片 HTTP 200 携带异常 Content-Range ($cr)", session.sessionId)
                        }
                        return null
                    }
                    val isNonRange = (code == 200 && cr.isNullOrBlank())
                    if (isNonRange && startOffset > 0L) {
                        session.banList.recordHttpFailure(url, host, 410)
                        return null
                    }
                    if (!isNonRange && !isValidContentRange(code, cr, startOffset, session)) {
                        session.banList.recordHttpFailure(url, host, 410)
                        if (host == primaryHost) {
                            reportFailover(host, "首片 Content-Range 异常 ($cr)", session.sessionId)
                        }
                        return null
                    }
                    val crInfo = parseContentRange(cr)
                    val cl = response.body?.contentLength() ?: -1L
                    val total = if (isNonRange) cl else (crInfo?.total ?: cr?.substringAfterLast("/")?.trim()?.toLongOrNull() ?: -1L)
                    val ct = response.header("Content-Type")?.takeIf { it.isNotBlank() }

                    val data = if (isNonRange) ByteArray(0) else (response.body?.bytes() ?: ByteArray(0))
                    if (!isNonRange && data.isEmpty()) {
                        session.banList.recordTransportFailure(host, 20_000L, url = url)
                        penalizeHost(host, 20_000L)
                        if (host == primaryHost) {
                            reportFailover(host, "首片探测返回 0KB 空数据熔断", session.sessionId)
                        }
                        return null
                    }
                    val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                    session.banList.recordSuccess(url, host)
                    if (data.isNotEmpty()) {
                        recordRouteSpeed(url, data.size.toLong(), latency)
                    }
                    ProbeResult(url, total, cl, ct, data, host, latency, isNonRange = isNonRange)
                } else if (code == 416) {
                    sawRangeNotSatisfiable416?.set(true)
                    val total416 = response.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                    if (total416 != null && total416 > 0L) {
                        totalLengthFrom416?.compareAndSet(-1L, total416)
                    }
                    null
                } else {
                    if (code == 403) sawForbidden403?.set(true)
                    val isNodeFault = session.banList.recordHttpFailure(url, host, code)
                    if (code == 412 || code == 429) {
                        concurrencyController?.onPushback(isHard = true)
                    }
                    if (isNodeFault) {
                        val penaltyTime = if (code == 429) 45_000L else if (code == 412) 30_000L else 20_000L
                        penalizeHost(host, penaltyTime)
                    }
                    if (host == primaryHost) {
                        reportFailover(host, "HTTP $code 错误", session.sessionId)
                    }
                    null
                }
            }
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                session.banList.recordTransportFailure(host, 15_000L, url = url)
                penalizeHost(host, 12_000L)
                if (host == primaryHost) {
                    reportFailover(host, e.message ?: "连接超时", session.sessionId)
                }
            }
            null
        } finally {
            activeCalls?.remove(call)
            if (streamCallKey != null) {
                unregisterStreamCall(streamCallKey, call)
            }
        }
    }

    private fun probeMediaOutcome(
        urls: List<String>,
        session: BtrMediaSession,
        startOffset: Long,
        chunkSize: Long,
        userAgent: String,
        referer: String,
        concurrencyController: DynamicConcurrencyController? = null,
        streamCallKey: String? = null
    ): ProbeOutcome {
        val sortedUrls = sortUrlsByHealthAndSpeed(urls, 0L, session)
        if (sortedUrls.isEmpty()) return ProbeOutcome(null, sawForbidden403 = false)
        val primaryHost = extractHostname(session.primaryUrl)
        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.probeClient
        val sawForbidden403 = AtomicBoolean(false)
        val saw416 = AtomicBoolean(false)
        val total416 = AtomicLong(-1L)

        val raceCandidates = sortedUrls.take(3)
        val fallbackCandidates = sortedUrls.drop(3)

        if (raceCandidates.size == 1) {
            executeSingleProbe(
                raceCandidates[0], session, startOffset, chunkSize, userAgent, referer,
                okClient, primaryHost, concurrencyController,
                activeCalls = null, isDecided = null, streamCallKey = streamCallKey,
                sawForbidden403 = sawForbidden403,
                sawRangeNotSatisfiable416 = saw416,
                totalLengthFrom416 = total416
            )?.let { return ProbeOutcome(it, sawForbidden403.get()) }
        } else {
            val isDecided = AtomicBoolean(false)
            val resultFuture = CompletableFuture<ProbeResult?>()
            val activeCalls = ConcurrentHashMap.newKeySet<Call>()
            val failedCount = AtomicInteger(0)
            val staggerLock = Object()
            val delaysMs = longArrayOf(0L, 120L, 300L)

            for (i in raceCandidates.indices) {
                val url = raceCandidates[i]
                val delayMs = delaysMs.getOrElse(i) { 300L }
                try {
                    hedgeWorkerPool.execute {
                        var outcomeRecorded = false
                        try {
                            if (delayMs > 0L) {
                                val deadline = System.currentTimeMillis() + delayMs
                                synchronized(staggerLock) {
                                    while (!isDecided.get() && failedCount.get() < i) {
                                        val rem = deadline - System.currentTimeMillis()
                                        if (rem <= 0L) break
                                        try {
                                            staggerLock.wait(rem)
                                        } catch (_: InterruptedException) {
                                            break
                                        }
                                    }
                                }
                            }
                            if (isDecided.get()) {
                                outcomeRecorded = true
                                return@execute
                            }

                            val probeRes = executeSingleProbe(
                                url, session, startOffset, chunkSize, userAgent, referer,
                                okClient, primaryHost, concurrencyController,
                                activeCalls, isDecided, streamCallKey, sawForbidden403,
                                sawRangeNotSatisfiable416 = saw416,
                                totalLengthFrom416 = total416
                            )
                            if (probeRes != null && isDecided.compareAndSet(false, true)) {
                                outcomeRecorded = true
                                resultFuture.complete(probeRes)
                                synchronized(staggerLock) { staggerLock.notifyAll() }
                                activeCalls.forEach { call -> runCatching { call.cancel() } }
                            } else if (!isDecided.get()) {
                                outcomeRecorded = true
                                val failures = failedCount.incrementAndGet()
                                synchronized(staggerLock) { staggerLock.notifyAll() }
                                if (failures >= raceCandidates.size && isDecided.compareAndSet(false, true)) {
                                    resultFuture.complete(null)
                                }
                            } else {
                                outcomeRecorded = true
                            }
                        } catch (_: Throwable) {
                        } finally {
                            if (!outcomeRecorded && !isDecided.get()) {
                                val failures = failedCount.incrementAndGet()
                                synchronized(staggerLock) { staggerLock.notifyAll() }
                                if (failures >= raceCandidates.size && isDecided.compareAndSet(false, true)) {
                                    resultFuture.complete(null)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    val failures = failedCount.incrementAndGet()
                    synchronized(staggerLock) { staggerLock.notifyAll() }
                    if (failures >= raceCandidates.size && isDecided.compareAndSet(false, true)) {
                        resultFuture.complete(null)
                    }
                }
            }

            try {
                val winner = resultFuture.get(15, TimeUnit.SECONDS)
                if (winner != null) return ProbeOutcome(winner, sawForbidden403.get())
            } catch (_: Exception) {
                isDecided.set(true)
                activeCalls.forEach { call -> runCatching { call.cancel() } }
            }
        }

        for (url in fallbackCandidates) {
            if (sawForbidden403.get() && isUrlExpired(session)) break
            val knownTotal416 = total416.get()
            if (saw416.get() && !sawForbidden403.get() && knownTotal416 > 0L && startOffset >= knownTotal416) break
            executeSingleProbe(
                url, session, startOffset, chunkSize, userAgent, referer,
                okClient, primaryHost, concurrencyController,
                activeCalls = null, isDecided = null, streamCallKey = streamCallKey,
                sawForbidden403 = sawForbidden403,
                sawRangeNotSatisfiable416 = saw416,
                totalLengthFrom416 = total416
            )?.let { return ProbeOutcome(it, sawForbidden403.get()) }
        }
        return ProbeOutcome(
            result = null,
            sawForbidden403 = sawForbidden403.get(),
            sawRangeNotSatisfiable416 = saw416.get(),
            totalLengthFrom416 = total416.get().takeIf { it > 0L }
        )
    }

    private fun fetchChunkWithFailover(
        task: ChunkTask,
        session: BtrMediaSession,
        userAgent: String,
        referer: String,
        statsTracker: StreamStatsTracker,
        concurrencyController: DynamicConcurrencyController?,
        isClientAborted: AtomicBoolean,
        streamCallKey: String,
        totalTasks: Long
    ) {
        if (isClientAborted.get()) return
        val allowUnmeasuredTrial = shouldAllowUnmeasuredTrial(task.index, totalTasks)
        val lastHttpCode = AtomicInteger(0)
        var didRefresh403 = false
        var round = 0

        while (round < PIECE_ROUNDS) {
            if (isClientAborted.get() || task.isSuccess) break
            if (round > 0 && lastHttpCode.get() != 403) {
                concurrencyController?.onSlow()
            }

            val activeUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
            val rotationIndex = session.globalChunkIndex.getAndIncrement()
            val sortedUrls = sortUrlsByHealthAndSpeed(activeUrls, rotationIndex, session, allowUnmeasuredTrial)
            if (sortedUrls.isEmpty()) break

            val now = System.currentTimeMillis()
            val unbannedUrls = sortedUrls.filter { url ->
                val host = extractHostname(url)
                !session.banList.isBanned(url, host, now) && !isPenalized(host)
            }
            val roundUrls = when {
                unbannedUrls.isNotEmpty() -> unbannedUrls
                else -> {
                    // 所有节点被临时封禁或惩罚时兜底尝试非永久封禁列表（对齐 BTR all-banned reset fallback）
                    sortedUrls.filter { url ->
                        !session.banList.isPermanentlyBanned(url, extractHostname(url))
                    }.ifEmpty { sortedUrls }
                }
            }

            val primaryUrl = roundUrls.first()
            val hedgeUrl = if (round == 0 && roundUrls.size > 1) roundUrls[1] else null
            val hedgeDelayMs = if (task.index <= 1L) 800L else DEFAULT_HEDGE_DELAY_MS

            val hasWinner = AtomicBoolean(false)
            val primaryFinished = AtomicBoolean(false)
            val hedgeLock = Object()
            registerHedgeLock(streamCallKey, hedgeLock)
            val primaryCallRef = AtomicReference<Call?>(null)
            val hedgeCallRef = AtomicReference<Call?>(null)

            val hedgeFuture = if (hedgeUrl != null && !isClientAborted.get()) {
                runCatching {
                    hedgeWorkerPool.submit {
                        try {
                            val deadline = System.currentTimeMillis() + hedgeDelayMs
                            synchronized(hedgeLock) {
                                while (!hasWinner.get() && !primaryFinished.get() && !isClientAborted.get()) {
                                    val rem = deadline - System.currentTimeMillis()
                                    if (rem <= 0L) break
                                    hedgeLock.wait(rem)
                                }
                            }
                            if (!hasWinner.get() && !isClientAborted.get() && !task.isSuccess) {
                                tryAttemptFetch(
                                    task = task,
                                    url = hedgeUrl,
                                    session = session,
                                    userAgent = userAgent,
                                    referer = referer,
                                    statsTracker = statsTracker,
                                    concurrencyController = concurrencyController,
                                    hasWinner = hasWinner,
                                    isHedge = true,
                                    callHolder = hedgeCallRef,
                                    counterpartCallHolder = primaryCallRef,
                                    streamCallKey = streamCallKey,
                                    lastHttpCode = lastHttpCode
                                )
                            }
                        } catch (_: InterruptedException) {}
                    }
                }.getOrNull()
            } else null

            try {
                tryAttemptFetch(
                    task = task,
                    url = primaryUrl,
                    session = session,
                    userAgent = userAgent,
                    referer = referer,
                    statsTracker = statsTracker,
                    concurrencyController = concurrencyController,
                    hasWinner = hasWinner,
                    isHedge = false,
                    callHolder = primaryCallRef,
                    counterpartCallHolder = hedgeCallRef,
                    streamCallKey = streamCallKey,
                    lastHttpCode = lastHttpCode
                )
            } finally {
                primaryFinished.set(true)
                synchronized(hedgeLock) { hedgeLock.notifyAll() }
                unregisterHedgeLock(streamCallKey, hedgeLock)
            }

            if (hedgeFuture != null) {
                if (task.isSuccess || isClientAborted.get()) {
                    hedgeFuture.cancel(true)
                    hedgeCallRef.get()?.let { slowHedge -> runCatching { slowHedge.cancel() } }
                } else {
                    runCatching { hedgeFuture.get(15, TimeUnit.SECONDS) }
                    if (!task.isSuccess) {
                        hedgeCallRef.get()?.let { slowHedge -> runCatching { slowHedge.cancel() } }
                    }
                }
            }

            if (!task.isSuccess && !isClientAborted.get()) {
                val startRemaining = if (hedgeUrl != null) 2 else 1
                val remainingUrls = roundUrls.drop(startRemaining)
                for (url in remainingUrls) {
                    if (isClientAborted.get() || task.isSuccess) break
                    if (lastHttpCode.get() == 403 && isUrlExpired(session) && didRefresh403) break
                    val singleCallRef = AtomicReference<Call?>(null)
                    tryAttemptFetch(
                        task = task,
                        url = url,
                        session = session,
                        userAgent = userAgent,
                        referer = referer,
                        statsTracker = statsTracker,
                        concurrencyController = concurrencyController,
                        hasWinner = hasWinner,
                        isHedge = false,
                        callHolder = singleCallRef,
                        counterpartCallHolder = null,
                        streamCallKey = streamCallKey,
                        lastHttpCode = lastHttpCode
                    )
                    if (task.isSuccess) break
                }
            }

            if (task.isSuccess || isClientAborted.get()) break

            if (lastHttpCode.get() == 403 && !didRefresh403 &&
                BtrSessionManager.maybeTriggerProactiveRefresh(session, forceOnAuthFailure = true)
            ) {
                didRefresh403 = true
                lastHttpCode.set(0)
                continue
            }

            if (round + 1 < PIECE_ROUNDS) {
                if (lastHttpCode.get() == 403 && isUrlExpired(session)) {
                    task.lastHttpCode = 403
                    break
                }
                val backoffMs = (250L * (round + 1)).coerceAtMost(750L)
                var slept = 0L
                while (slept < backoffMs && !isClientAborted.get()) {
                    try {
                        Thread.sleep(minOf(50L, backoffMs - slept))
                    } catch (_: InterruptedException) {
                        break
                    }
                    slept += 50L
                }
            }
            round++
        }

        // 最终直连兜底尝试
        val latestUrls = (listOf(session.primaryUrl) + session.backupUrls).distinct()
        if (!task.isSuccess && !isClientAborted.get() && latestUrls.isNotEmpty() && !(lastHttpCode.get() == 403 && isUrlExpired(session))) {
            val sortedFallback = sortUrlsByHealthAndSpeed(latestUrls, task.index, session)
            val fallbackCandidate = sortedFallback.firstOrNull { u ->
                val h = extractHostname(u)
                !session.banList.isBanned(u, h)
            } ?: latestUrls.first()
            tryDirectFetch(
                task = task,
                url = fallbackCandidate,
                session = session,
                userAgent = userAgent,
                referer = referer,
                statsTracker = statsTracker,
                concurrencyController = concurrencyController,
                streamCallKey = streamCallKey,
                lastHttpCode = lastHttpCode
            )
        }

        if (!task.isSuccess && !isClientAborted.get()) {
            task.lastHttpCode = lastHttpCode.get()
            task.isFailed = true
            task.errorMessage = "All candidate CDNs failed for range ${task.startByte}-${task.endByte}"
        }
    }

    private fun tryAttemptFetch(
        task: ChunkTask,
        url: String,
        session: BtrMediaSession,
        userAgent: String,
        referer: String,
        statsTracker: StreamStatsTracker,
        concurrencyController: DynamicConcurrencyController?,
        hasWinner: AtomicBoolean,
        isHedge: Boolean,
        callHolder: AtomicReference<Call?>,
        counterpartCallHolder: AtomicReference<Call?>?,
        streamCallKey: String,
        lastHttpCode: AtomicInteger? = null
    ) {
        val host = extractHostname(url)
        val attemptId = attemptSeq.incrementAndGet()
        val startTime = System.currentTimeMillis()
        val expectedTotal = (task.endByte - task.startByte + 1).toInt()
        if (expectedTotal <= 0) return

        // P1: 在途实时前缀共享 + 断点续传
        val existingPrefix = task.takePrefixSnapshot(RESUME_MIN_BYTES, expectedTotal)
        val resumeOffset = existingPrefix?.size ?: 0
        val requestStart = task.startByte + resumeOffset
        val needBytes = (task.endByte - requestStart + 1).toInt()
        if (needBytes <= 0) return

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$requestStart-${task.endByte}")
            .header("Referer", referer)
            .header("User-Agent", userAgent)
            .header("Connection", "keep-alive")
            .build()

        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.client
        val call = okClient.newCall(request)
        callHolder.set(call)
        registerStreamCall(streamCallKey, session.sessionId, call)

        var localBytesRead = 0L
        var completedNormally = false
        val isPrefixProducer = (resumeOffset == 0) && task.tryClaimPrefixProducer(attemptId)

        try {
            call.execute().use { response ->
                val code = response.code
                val cr = response.header("Content-Range")
                val cl = response.body?.contentLength() ?: -1L

                // 严格拒绝非 0 起始偏移的 HTTP 200 响应（否则从 0 字节返回导致音频/画面错位损坏），以及超大/未知长度/伪造 Content-Range 的 200 响应
                if (code == 200) {
                    if (!cr.isNullOrBlank() || requestStart > 0L || cl != expectedTotal.toLong()) {
                        session.banList.recordHttpFailure(url, host, 410, receivedBytes = 0L)
                        penalizeHost(host, 20_000L)
                        reportFailover(host, "HTTP 200 非 Range 错位/异常 Content-Range 拒绝 (reqStart=$requestStart, cl=$cl, cr=$cr)", session.sessionId)
                        return
                    }
                }

                if (code == 200 || code == 206) {
                    if (code == 206 && !isValidContentRange(code, cr, requestStart, session, expectedEnd = task.endByte)) {
                        session.banList.recordHttpFailure(url, host, 410, receivedBytes = 0L)
                        reportFailover(host, "分片 Content-Range 异常 ($cr vs reqStart=$requestStart-${task.endByte})", session.sessionId)
                        return
                    }

                    val body = response.body ?: return
                    val stream = body.byteStream()
                    var finalData: ByteArray? = null

                    if (isPrefixProducer) {
                        val tempBuf = ByteArray(16 * 1024)
                        while (localBytesRead < needBytes) {
                            val toRead = minOf(tempBuf.size, needBytes - localBytesRead.toInt())
                            val read = stream.read(tempBuf, 0, toRead)
                            if (read == -1) break
                            if (hasWinner.get() && task.isSuccess) return
                            task.appendLivePrefix(attemptId, tempBuf, read, expectedTotal)
                            localBytesRead += read
                        }
                        if (localBytesRead == needBytes.toLong() && stream.read() != -1) {
                            session.banList.recordHttpFailure(url, host, 410, receivedBytes = localBytesRead)
                            reportFailover(host, "分片响应体超出预期字节数", session.sessionId)
                            return
                        }
                        if (localBytesRead == 0L) {
                            session.banList.recordTransportFailure(host, 20_000L, url = url)
                            penalizeHost(host, 20_000L)
                            reportFailover(host, "分片拉取返回 0KB 空响应熔断", session.sessionId)
                            return
                        }
                        if (localBytesRead < needBytes.toLong()) {
                            session.banList.recordTransportFailure(host, 15_000L, url = url, receivedBytes = localBytesRead)
                            penalizeHost(host, 15_000L)
                            reportFailover(host, "分片数据截断 ($localBytesRead/$needBytes 字节)", session.sessionId)
                            return
                        }
                        finalData = task.consumeOwnedLiveBuffer(attemptId)
                    } else {
                        val resultBuf = ByteArray(expectedTotal)
                        if (resumeOffset > 0 && existingPrefix != null) {
                            System.arraycopy(existingPrefix, 0, resultBuf, 0, resumeOffset)
                        }
                        var writePos = resumeOffset
                        while (writePos < expectedTotal) {
                            val toRead = minOf(16 * 1024, expectedTotal - writePos)
                            val read = stream.read(resultBuf, writePos, toRead)
                            if (read == -1) break
                            if (hasWinner.get() && task.isSuccess) return
                            writePos += read
                            localBytesRead += read
                        }
                        if (localBytesRead == 0L) {
                            session.banList.recordTransportFailure(host, 20_000L, url = url)
                            penalizeHost(host, 20_000L)
                            reportFailover(host, "分片拉取返回 0KB 空响应熔断", session.sessionId)
                            return
                        }
                        if (writePos == expectedTotal && stream.read() != -1) {
                            session.banList.recordHttpFailure(url, host, 410, receivedBytes = localBytesRead)
                            reportFailover(host, "分片响应体超出预期字节数", session.sessionId)
                            return
                        }
                        if (writePos < expectedTotal) {
                            if (resumeOffset == 0 && writePos >= RESUME_MIN_BYTES && !hasWinner.get()) {
                                task.saveFallbackPrefixIfBetter(resultBuf, writePos)
                            }
                            session.banList.recordTransportFailure(host, 15_000L, url = url, receivedBytes = localBytesRead)
                            penalizeHost(host, 15_000L)
                            reportFailover(host, "分片数据截断 (${localBytesRead}/$needBytes 字节)", session.sessionId)
                            return
                        }
                        finalData = resultBuf
                    }

                    if (finalData == null || finalData.size != expectedTotal) return

                    completedNormally = true
                    if (hasWinner.compareAndSet(false, true)) {
                        task.data = finalData
                        task.isSuccess = true
                        task.clearPrefixBuffersOnWin()
                        session.banList.recordSuccess(url, host)
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        recordRouteSpeed(url, finalData.size.toLong(), latency)
                        statsTracker.recordChunk(host, finalData.size.toLong(), latency)
                        concurrencyController?.recordProgress(finalData.size.toLong())

                        counterpartCallHolder?.get()?.let { slowCall ->
                            runCatching { slowCall.cancel() }
                        }
                    }
                    return
                } else if (code >= 400) {
                    lastHttpCode?.set(code)
                    val isNodeFault = session.banList.recordHttpFailure(url, host, code)
                    if (code == 412 || code == 429) {
                        concurrencyController?.onPushback()
                    }
                    if (isNodeFault) {
                        val penaltyTime = if (code == 429) 45_000L else if (code == 412) 30_000L else 20_000L
                        penalizeHost(host, penaltyTime)
                    }
                    if (!isHedge || session.banList.isHostKnownGood(host)) {
                        reportFailover(host, "HTTP $code 熔断", session.sessionId)
                    }
                }
            }
        } catch (e: Exception) {
            if (!call.isCanceled()) {
                session.banList.recordTransportFailure(host, 15_000L, url = url, receivedBytes = localBytesRead)
                penalizeHost(host, 12_000L)
                reportFailover(host, "分片拉取异常: ${e.message}", session.sessionId)
            }
        } finally {
            if (!completedNormally && localBytesRead >= CANCEL_SPEED_MIN_BYTES) {
                val partialElapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                recordRouteSpeed(url, localBytesRead, partialElapsed)
            }
            if (isPrefixProducer && !task.isSuccess) {
                task.releasePrefixProducer(attemptId, keepPartial = !hasWinner.get())
            }
            unregisterStreamCall(streamCallKey, call)
        }
    }

    private val RANGE_START_REGEX = Regex("""bytes\s+(\d+)-""", RegexOption.IGNORE_CASE)

    internal fun parseRangeStart(cr: String?): Long? {
        if (cr.isNullOrBlank()) return null
        val match = RANGE_START_REGEX.find(cr)
        return match?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * 平滑加权轮询 (Nginx SWRR) 选择下一主路由：
     * 避免慢节点获得与极速节点同等比例的分片（木桶效应），且避免连续突发打满单一快节点。
     */
    private fun pickSmoothWeighted(
        sessionId: String,
        candidates: List<String>,
        speeds: Map<String, Double>
    ): String {
        if (candidates.size <= 1) return candidates.first()
        val stateMap = sessionSwrrWeights.computeIfAbsent(sessionId) { HashMap() }
        val totalSpeed = candidates.sumOf { speeds[it] ?: 0.0 }.coerceAtLeast(1.0)
        synchronized(stateMap) {
            var totalWeight = 0.0
            var bestUrl = candidates.first()
            var bestWeight = Double.NEGATIVE_INFINITY
            for (url in candidates) {
                val staticWeight = ((speeds[url] ?: 0.0) / totalSpeed).coerceAtLeast(0.05)
                val nextCur = (stateMap[url] ?: 0.0) + staticWeight
                stateMap[url] = nextCur
                totalWeight += staticWeight
                if (nextCur > bestWeight) {
                    bestWeight = nextCur
                    bestUrl = url
                }
            }
            stateMap[bestUrl] = (stateMap[bestUrl] ?: 0.0) - totalWeight
            return bestUrl
        }
    }

    internal fun sortUrlsByHealthAndSpeed(
        urls: List<String>,
        taskIndex: Long,
        session: BtrMediaSession? = null,
        allowUnmeasuredTrial: Boolean = false
    ): List<String> {
        if (urls.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        val sortedAll = urls.sortedBy { url ->
            val host = extractHostname(url)
            val isPermBanned = session?.banList?.isPermanentlyBanned(url, host) == true
            val isUrlBanned = session?.banList?.isBanned(url, host, now) == true
            val penaltyUntil = cdnPenalties[host] ?: 0L
            val globalPenalty = if (penaltyUntil > now) penaltyUntil - now else 0L
            (if (isPermBanned) 300_000L else 0L) + (if (isUrlBanned) 60_000L else 0L) + globalPenalty
        }

        val healthyUrls = sortedAll.filter { url ->
            val host = extractHostname(url)
            !isPenalized(host) && session?.banList?.isBanned(url, host, now) != true
        }

        if (healthyUrls.isEmpty()) {
            val offset = ((taskIndex % sortedAll.size).toInt() + sortedAll.size) % sortedAll.size
            return sortedAll.subList(offset, sortedAll.size) + sortedAll.subList(0, offset)
        }

        val speeds = healthyUrls.associateWith { getRouteSpeed(it) }
        val measured = healthyUrls.filter { (speeds[it] ?: 0.0) > 0.0 }
        val unmeasured = healthyUrls.filter { (speeds[it] ?: 0.0) <= 0.0 }

        val healthyOrdered = if (measured.isNotEmpty()) {
            val topSpeed = measured.maxOf { speeds[it] ?: 0.0 }
            val fastMeasured = measured.filter { (speeds[it] ?: 0.0) >= topSpeed / SPEED_CUTOFF_RATIO }
                .ifEmpty { measured }
                .sortedByDescending { speeds[it] ?: 0.0 }

            val swrrPrimary = if (session != null) {
                pickSmoothWeighted(session.sessionId, fastMeasured, speeds)
            } else {
                fastMeasured[(taskIndex % fastMeasured.size).toInt()]
            }

            // 未测速新节点不插入起播关键前段分片；仅在流尾部窗口按试探位前置一次，避免慢节点拖垮起播
            val trialPrimary = if (allowUnmeasuredTrial && unmeasured.isNotEmpty()) {
                unmeasured[(taskIndex % unmeasured.size).toInt()]
            } else null

            val headList = if (trialPrimary != null) {
                listOf(trialPrimary, swrrPrimary)
            } else {
                listOf(swrrPrimary)
            }

            val slowMeasured = (measured - fastMeasured.toSet()).sortedByDescending { speeds[it] ?: 0.0 }
            (headList + fastMeasured + unmeasured + slowMeasured).distinct()
        } else {
            val offset = ((taskIndex % healthyUrls.size).toInt() + healthyUrls.size) % healthyUrls.size
            healthyUrls.subList(offset, healthyUrls.size) + healthyUrls.subList(0, offset)
        }

        return (healthyOrdered + sortedAll).distinct()
    }

    private fun tryDirectFetch(
        task: ChunkTask,
        url: String,
        session: BtrMediaSession,
        userAgent: String,
        referer: String,
        statsTracker: StreamStatsTracker,
        concurrencyController: DynamicConcurrencyController? = null,
        streamCallKey: String? = null,
        lastHttpCode: AtomicInteger? = null
    ) {
        val host = extractHostname(url)
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${task.startByte}-${task.endByte}")
            .header("Referer", referer)
            .header("User-Agent", userAgent)
            .header("Connection", "keep-alive")
            .build()

        val okClient = if (session.isAudio) BtrHttpClient.audioClient else BtrHttpClient.fallbackClient
        val call = okClient.newCall(request)
        if (streamCallKey != null) {
            registerStreamCall(streamCallKey, session.sessionId, call)
        }
        try {
            call.execute().use { response ->
                val code = response.code
                val expectedBytes = (task.endByte - task.startByte + 1).toInt()
                val cr = response.header("Content-Range")
                val cl = response.body?.contentLength() ?: -1L

                if (code == 200) {
                    if (!cr.isNullOrBlank() || task.startByte > 0L || cl != expectedBytes.toLong()) {
                        session.banList.recordHttpFailure(url, host, 410, receivedBytes = 0L)
                        return@use
                    }
                }
                if (code == 206 && !isValidContentRange(code, cr, task.startByte, session, expectedEnd = task.endByte)) {
                    session.banList.recordHttpFailure(url, host, 410)
                    return@use
                }

                if (code == 200 || code == 206) {
                    val rawBytes = response.body?.bytes() ?: ByteArray(0)
                    if (rawBytes.size == expectedBytes && expectedBytes > 0) {
                        task.data = rawBytes
                        task.isSuccess = true
                        session.banList.recordSuccess(url, host)
                        val latency = maxOf(1L, System.currentTimeMillis() - startTime)
                        recordRouteSpeed(url, rawBytes.size.toLong(), latency)
                        statsTracker.recordChunk(host, rawBytes.size.toLong(), latency)
                        concurrencyController?.recordProgress(rawBytes.size.toLong())
                    }
                } else {
                    if (code >= 400) lastHttpCode?.set(code)
                    session.banList.recordHttpFailure(url, host, code)
                    if (code == 412 || code == 429) {
                        concurrencyController?.onPushback()
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            if (streamCallKey != null) {
                unregisterStreamCall(streamCallKey, call)
            }
        }
    }

    private fun extractHostname(url: String): String {
        return BiliAcceleratorCore.parseUri(url)?.host
            ?: runCatching { java.net.URI(url).host }.getOrNull()
            ?: ""
    }

    private fun isPenalized(host: String): Boolean {
        val penalty = cdnPenalties[host] ?: return false
        return penalty > System.currentTimeMillis()
    }

    private fun reportFailover(badHost: String, reason: String, sessionId: String) {
        val result = RewriteResult(
            changed = true,
            originalUrl = "",
            finalUrl = "",
            originalHost = badHost,
            targetHost = "备用镜像/CDN",
            reason = "BTR节点熔断: $reason",
            isPcdn = false,
            isMcdn = false,
            segmentKey = "btr_failover_${sessionId}_${System.currentTimeMillis()}",
            isBtr = true
        )
        RemoteClient.notifyRewrite(result)
    }

    private fun sendHttpError(out: OutputStream, code: Int, message: String) {
        try {
            val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {}
    }

    private fun sendHttp416(out: OutputStream, totalLength: Long?) {
        try {
            val totalStr = totalLength?.toString() ?: "*"
            val resp = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalStr\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(resp.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {}
    }
}
