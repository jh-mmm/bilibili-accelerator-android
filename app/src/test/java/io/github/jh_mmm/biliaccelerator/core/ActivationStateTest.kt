package io.github.jh_mmm.biliaccelerator.core

import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import io.github.jh_mmm.biliaccelerator.provider.RewriteResultDto
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import io.github.jh_mmm.biliaccelerator.ui.MainActivity
import io.github.jh_mmm.biliaccelerator.ui.MainActivity.ActivationState
import io.github.jh_mmm.biliaccelerator.ui.dashboard.logItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationStateTest {

    private val gson = Gson()

    @Test
    fun testStatsSnapshotSerializationWithHeartbeat() {
        val snapshot = StatsSnapshot(
            totalRequests = 100,
            totalRewrites = 20,
            pcdnBlocked = 10,
            mcdnProxied = 5,
            avoidedHosts = listOf("pcdn.sz.bili.com"),
            recentLogs = emptyList(),
            installedAt = "2026-10-07 18:00:00",
            lastHeartbeatTimestamp = 1760000000000L
        )

        val json = gson.toJson(snapshot)
        val deserialized = gson.fromJson(json, StatsSnapshot::class.java)

        assertEquals(snapshot.totalRequests, deserialized.totalRequests)
        assertEquals(snapshot.totalRewrites, deserialized.totalRewrites)
        assertEquals(snapshot.lastHeartbeatTimestamp, deserialized.lastHeartbeatTimestamp)
    }

    @Test
    fun testLegacySnapshotDeserializationCompatibility() {
        val legacyJson = """
            {
                "totalRequests": 50,
                "totalRewrites": 10,
                "pcdnBlocked": 2,
                "mcdnProxied": 1,
                "avoidedHosts": [],
                "recentLogs": [],
                "installedAt": "2026-10-01 10:00:00"
            }
        """.trimIndent()

        val deserialized = gson.fromJson(legacyJson, StatsSnapshot::class.java)
        assertEquals(50L, deserialized.totalRequests)
        assertEquals(10L, deserialized.totalRewrites)
        assertEquals(0L, deserialized.lastHeartbeatTimestamp)
    }

    @Test
    fun testCalculateActivationStateDowngradesWhenInactiveEvenWithHistoricalRewrites() {
        val now = 1_760_000_000_000L
        // 历史有 totalRewrites = 100，但当前未绑定框架服务且心跳已过期（或为 0）-> 必须降级为 INACTIVE
        val stateWhenNoHeartbeat = MainActivity.calculateActivationState(
            totalRewrites = 100L,
            isServiceBound = false,
            lastHeartbeat = 0L,
            now = now
        )
        assertEquals("Historical rewrites without active service or heartbeat must be INACTIVE", ActivationState.INACTIVE, stateWhenNoHeartbeat)

        val expiredHeartbeat = now - MainActivity.DEFAULT_HEARTBEAT_THRESHOLD_MS - 1_000L
        val stateWhenHeartbeatExpired = MainActivity.calculateActivationState(
            totalRewrites = 100L,
            isServiceBound = false,
            lastHeartbeat = expiredHeartbeat,
            now = now
        )
        assertEquals("Expired heartbeat without service binding must downgrade to INACTIVE", ActivationState.INACTIVE, stateWhenHeartbeatExpired)
    }

    @Test
    fun testCalculateActivationStateActiveEffectiveAndHeartbeat() {
        val now = 1_760_000_000_000L
        val recentHeartbeat = now - 60_000L // 1 分钟前

        // 服务已绑定但尚无重写 -> ACTIVE_HEARTBEAT
        assertEquals(
            ActivationState.ACTIVE_HEARTBEAT,
            MainActivity.calculateActivationState(totalRewrites = 0L, isServiceBound = true, lastHeartbeat = 0L, now = now)
        )

        // 近期有心跳但尚无重写 -> ACTIVE_HEARTBEAT
        assertEquals(
            ActivationState.ACTIVE_HEARTBEAT,
            MainActivity.calculateActivationState(totalRewrites = 0L, isServiceBound = false, lastHeartbeat = recentHeartbeat, now = now)
        )

        // 近期有心跳且有重写 -> ACTIVE_EFFECTIVE
        assertEquals(
            ActivationState.ACTIVE_EFFECTIVE,
            MainActivity.calculateActivationState(totalRewrites = 5L, isServiceBound = false, lastHeartbeat = recentHeartbeat, now = now)
        )

        // 服务已绑定且有重写 -> ACTIVE_EFFECTIVE
        assertEquals(
            ActivationState.ACTIVE_EFFECTIVE,
            MainActivity.calculateActivationState(totalRewrites = 5L, isServiceBound = true, lastHeartbeat = 0L, now = now)
        )
    }

    @Test
    fun testFrameworkServiceBindingDefaultState() {
        assertFalse("Initial framework service binding state must be false", XposedServiceProvider.isServiceBound)
    }

    @Test
    fun testLogItemKeyIsStableAcrossInsertion() {
        val log1 = RewriteLogEntry(
            id = "uuid-1",
            timestamp = 1760000000000L,
            timeFormatted = "10-09 18:00:00",
            originalHost = "112.34.56.78",
            targetHost = "upos-sz-mirrorcos.bilivideo.com",
            reason = "pcdn-host",
            isPcdn = true,
            isMcdn = false
        )
        val log2 = RewriteLogEntry(
            id = "uuid-2",
            timestamp = 1760000001000L,
            timeFormatted = "10-09 18:00:01",
            originalHost = "112.34.56.78",
            targetHost = "upos-sz-mirrorcos.bilivideo.com",
            reason = "pcdn-host",
            isPcdn = true,
            isMcdn = false
        )

        // 插入新日志到列表头部后，log1 的 UI key 必须保持完全不变（不随下标偏移改变）
        val keyBeforeInsert = logItemKey(log1)
        val listAfterInsert = listOf(log2, log1)
        val keyAfterInsert = logItemKey(listAfterInsert[1])

        assertEquals("uuid-1", keyBeforeInsert)
        assertEquals("Key must remain stable regardless of list index", keyBeforeInsert, keyAfterInsert)
    }

    @Test
    fun testDuplicateLogsDeserializationSanitizationViaStatsManager() {
        val legacyJson = """
            [
                {
                    "timestamp": 1760000000000,
                    "timeFormatted": "10-09 18:00:00",
                    "originalHost": "112.34.56.78",
                    "targetHost": "upos-sz-mirrorcos.bilivideo.com",
                    "reason": "pcdn-host",
                    "isPcdn": true,
                    "isMcdn": false
                },
                {
                    "timestamp": 1760000000000,
                    "timeFormatted": "10-09 18:00:00",
                    "originalHost": "112.34.56.78",
                    "targetHost": "upos-sz-mirrorcos.bilivideo.com",
                    "reason": "pcdn-host",
                    "isPcdn": true,
                    "isMcdn": false
                }
            ]
        """.trimIndent()

        val listType = object : com.google.gson.reflect.TypeToken<List<RewriteLogEntry>>() {}.type
        val list: List<RewriteLogEntry> = gson.fromJson(legacyJson, listType)

        // 直接调用 StatsManager.sanitizeLogEntries 生产方法
        val sanitizedList = StatsManager.sanitizeLogEntries(list)

        assertEquals(2, sanitizedList.size)
        assertTrue("Sanitized ID must not be blank", sanitizedList[0].id.isNotBlank())
        assertTrue("Sanitized ID must not be blank", sanitizedList[1].id.isNotBlank())
        assertTrue("IDs must be distinct to prevent collisions", sanitizedList[0].id != sanitizedList[1].id)
        assertTrue("UI keys must be distinct", logItemKey(sanitizedList[0]) != logItemKey(sanitizedList[1]))
    }

    @Test
    fun testIpcPrivacyRedactsFullSignedUrls() {
        val sensitiveUrl = "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s?mid=99999&buvid=XY123&upsig=secret_token"
        val res = BiliAcceleratorCore.rewriteUrl(sensitiveUrl, AcceleratorConfig())
        assertTrue(res.originalUrl.contains("secret_token"))

        val sanitized = RemoteClient.sanitizeForIpc(res)
        assertEquals("originalUrl must be stripped before IPC", "", sanitized.originalUrl)
        assertEquals("finalUrl must be stripped before IPC", "", sanitized.finalUrl)
        assertEquals("112.34.56.78", sanitized.originalHost)
        assertEquals("upos-sz-mirrorcos.bilivideo.com", sanitized.targetHost)

        val json = gson.toJson(sanitized)
        assertFalse("Serialized IPC payload must never contain token or mid", json.contains("secret_token") || json.contains("99999"))

        val dto = gson.fromJson(json, RewriteResultDto::class.java)
        val domain = dto.toDomain()!!
        assertEquals("", domain.originalUrl)
        assertEquals("", domain.finalUrl)
        assertEquals("112.34.56.78", domain.originalHost)
        assertEquals("34567.m4s", domain.segmentKey)
    }

    @Test
    fun testMultiProcessFilteringAndCrossProcessStatsDedup() {
        val pkg = "tv.danmaku.bili"
        assertTrue(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, pkg))
        assertTrue(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:ijkservice"))
        assertTrue(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:player"))
        assertFalse(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:push"))
        assertFalse(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:crash"))
        assertFalse(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:miniapp"))

        // Cross-process segment deduplication within 10s window
        val segUrl = "http://112.34.56.78:8080/upgcxcode/11/22/999888-1-30080.m4s?sign=1"
        val res = BiliAcceleratorCore.rewriteUrl(segUrl, AcceleratorConfig())
        val now = 1_760_000_000_000L
        assertTrue(StatsManager.shouldAcceptCrossProcessSegment(res, now))
        assertFalse(
            "Duplicate segment from secondary process within 10s window must be deduplicated",
            StatsManager.shouldAcceptCrossProcessSegment(res, now + 1500L)
        )
        assertTrue(
            "Segment after 10s window should be accepted again",
            StatsManager.shouldAcceptCrossProcessSegment(res, now + 15_000L)
        )

        // Hook status protection: secondary process reporting "未找到" must not overwrite main process success
        val merged = StatsManager.mergeHookStatusEntry(
            existing = "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder (6 hooks)",
            incoming = "未找到 (版本不兼容)"
        )
        assertTrue(merged.contains("6 hooks"))
    }
}

