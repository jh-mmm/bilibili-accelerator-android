package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtrEngineTest {

    @Test
    fun testSessionManagerRegistrationAndRetrieval() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val backups = listOf(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/12345.m4s",
            "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/54/12/12345.m4s"
        )

        val sessionId = BtrSessionManager.register(primary, backups, isAudio = false)
        assertTrue("Session ID must not be blank", sessionId.isNotBlank())

        val session = BtrSessionManager.get(sessionId)
        assertNotNull("Session must be retrievable", session)
        assertEquals(primary, session?.primaryUrl)
        assertEquals(2, session?.backupUrls?.size)
        assertFalse("Session isAudio should be false", session?.isAudio ?: true)
    }

    @Test
    fun testAudioSessionRegistration() {
        val audioUrl = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345-30280.m4s"
        val sessionId = BtrSessionManager.register(audioUrl, isAudio = true)

        val session = BtrSessionManager.get(sessionId)
        assertNotNull("Audio session must be retrievable", session)
        assertTrue("Audio flag must be true", session?.isAudio ?: false)
    }

    @Test
    fun testBuilderSessionBindingAndDynamicBackupUpdate() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val dummyBuilder = Any()
        val sessionId = BtrSessionManager.register(primary)

        BtrSessionManager.bindBuilderSession(dummyBuilder, sessionId)
        assertEquals(sessionId, BtrSessionManager.getSessionForBuilder(dummyBuilder))

        val officialBackups = listOf(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/12345.m4s"
        )
        BtrSessionManager.updateBackups(sessionId, officialBackups)

        val session = BtrSessionManager.get(sessionId)
        assertEquals(1, session?.backupUrls?.size)
        assertEquals(officialBackups[0], session?.backupUrls?.firstOrNull())
    }

    @Test
    fun testSynthesizeCompatibleBackupsForOverseasAkamai() {
        val akamaiPrimary = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(akamaiPrimary)
        assertTrue("Overseas Akamai stream should synthesize overseas mirrors", backups.isNotEmpty())
        for (backup in backups) {
            assertTrue("Synthesized backup must be an overseas ov node", backup.contains("ov.bilivideo.com"))
        }
    }

    @Test
    fun testSynthesizeCompatibleBackupsExcludesOverseasAndAkamai() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(primary)

        assertTrue("Compatible backups should be generated", backups.isNotEmpty())
        for (backup in backups) {
            assertFalse("Must not include primary host", backup.contains("upos-sz-mirrorali.bilivideo.com"))
            assertFalse("Must not include overseas ov mirrors", backup.contains("ov.bilivideo.com"))
            assertFalse("Must not include Akamai mirrors", backup.contains("akamaized.net") || backup.contains("mirrorakam"))
        }
    }

    @Test
    fun testSynthesizeCompatibleBackupsExcludesRelayAndMcdnUrls() {
        // 对齐 BTR Issue #47：中继/MCDN/P2P 流绝不合成普通 UPOS 镜像，杜绝 HTTP 403 熔断
        val relay1 = "http://xy123.mcdn.bilivideo.cn:4483/v1/resource/upgcxcode/54/12/12345.m4s"
        val relay2 = "https://proxy-tf-all-ws.bilivideo.com/?url=http%3A%2F%2Fxy.mcdn.bilivideo.cn"
        val relay3 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?os=mcdn"

        assertTrue("Relay URL with /v1/resource/ must not synthesize UPOS mirrors", BtrSessionManager.synthesizeCompatibleBackups(relay1).isEmpty())
        assertTrue("Proxy URL must not synthesize UPOS mirrors", BtrSessionManager.synthesizeCompatibleBackups(relay2).isEmpty())
        assertTrue("URL with os=mcdn must not synthesize UPOS mirrors", BtrSessionManager.synthesizeCompatibleBackups(relay3).isEmpty())
    }

    @Test
    fun testSessionManagerIsolatesRelayAndOrdinaryBackups() {
        val relayPrimary = "https://proxy-tf-all-ws.bilivideo.com/?url=http%3A%2F%2Fxy.mcdn.bilivideo.cn%2Fv1%2Fresource%2F123.m4s"
        val mixedBackups = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/123.m4s",
            "https://xy2.mcdn.bilivideo.cn/v1/resource/123.m4s"
        )

        val sessionId = BtrSessionManager.register(relayPrimary, mixedBackups)
        val session = BtrSessionManager.get(sessionId)
        assertNotNull(session)
        // 普通 UPOS 镜像严禁混入中继 session
        assertFalse("Ordinary UPOS host must be filtered out from relay session", session?.backupUrls?.any { it.contains("mirrorali") } ?: true)
        assertTrue("Compatible relay backup should be retained", session?.backupUrls?.any { it.contains("xy2.mcdn") } ?: false)
    }

    @Test
    fun testChunkEngineAudioVsVideoConstants() {
        // 音频必须使用 128KB 极低时延切片与 2 线程隔离并发，防止与视频争夺带宽及产生音画不同步
        assertEquals(128 * 1024, BtrChunkEngine.CHUNK_SIZE_AUDIO)
        assertEquals(2, BtrChunkEngine.AUDIO_MAX_CONCURRENCY)

        // 视频使用 512KB 分片
        assertEquals(512 * 1024, BtrChunkEngine.CHUNK_SIZE_VIDEO)
        assertEquals(64 * 1024, BtrChunkEngine.PROBE_PREFIX_BYTES)
    }

    @Test
    fun testStatsManagerTracksBtrMetrics() {
        val btrResult = RewriteResult(
            changed = true,
            originalUrl = "http://112.34.56.78:8080/upgcxcode/54/12/12345.m4s",
            finalUrl = "http://127.0.0.1:38472/stream/abcdef123456.m4s",
            originalHost = "112.34.56.78",
            targetHost = "127.0.0.1:38472 (6线程)",
            reason = "BTR并发加速 · 24分片(12.0MB) · 3个CDN · 均时45ms",
            isPcdn = false,
            isMcdn = false,
            segmentKey = "btr_test_segment_1",
            isBtr = true,
            btrChunks = 24,
            btrBytes = 12 * 1024 * 1024L,
            btrConcurrency = 6,
            btrCdnCount = 3,
            btrAvgLatencyMs = 45L
        )

        val beforeSnapshot = StatsManager.getSnapshot()
        StatsManager.recordRequests(listOf(btrResult))
        val afterSnapshot = StatsManager.getSnapshot()

        assertEquals(beforeSnapshot.btrTotalChunks + 24, afterSnapshot.btrTotalChunks)
        assertEquals(beforeSnapshot.btrTotalBytes + (12 * 1024 * 1024L), afterSnapshot.btrTotalBytes)

        val latestLog = afterSnapshot.recentLogs.firstOrNull()
        assertNotNull("Recent logs must contain BTR entry", latestLog)
        assertTrue("Log entry must be flagged as BTR", latestLog?.isBtr == true)
        assertEquals(24, latestLog?.btrChunks)
        assertEquals(6, latestLog?.btrConcurrency)
    }

    @Test
    fun testBtrFailoverTracking() {
        val failoverResult = RewriteResult(
            changed = true,
            originalUrl = "",
            finalUrl = "",
            originalHost = "upos-sz-mirrorali.bilivideo.com",
            targetHost = "upos-sz-mirrorcos.bilivideo.com",
            reason = "BTR节点熔断: HTTP 403 熔断",
            isPcdn = false,
            isMcdn = false,
            segmentKey = "btr_failover_test_1",
            isBtr = true
        )

        val before = StatsManager.getSnapshot().btrFailoverCount
        StatsManager.recordRequests(listOf(failoverResult))
        val after = StatsManager.getSnapshot().btrFailoverCount

        assertEquals(before + 1, after)
    }

    @Test
    fun testCalculateDynamicChunkSize() {
        // 音频始终保持 128KB
        assertEquals(128 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(100 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = true))
        assertEquals(128 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(100 * 1024 * 1024L, 512, autoChunkSize = false, isAudio = true))

        // 关闭自适应切片时，使用基准大小
        assertEquals(512 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(300 * 1024 * 1024L, 512, autoChunkSize = false, isAudio = false))
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(5 * 1024 * 1024L, 1024, autoChunkSize = false, isAudio = false))

        // 开启自适应时按体量动态缩放
        // < 15MB -> 256KB
        assertEquals(256 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(10 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        // 15MB ~ 60MB -> 512KB
        assertEquals(512 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(40 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        // 60MB ~ 250MB -> 1024KB
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(150 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        // >= 250MB -> 1024KB (移动端 4K/8K 大文件切片上限收敛为 1MB 防 OOM)
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(500 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
    }

    @Test
    fun testSessionMetadataCachingForSeekBypass() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"
        val sessionId = BtrSessionManager.register(primary)
        val session = BtrSessionManager.get(sessionId)
        assertNotNull(session)

        assertEquals(-1L, session?.totalLength)
        session?.totalLength = 50_000_000L
        session?.contentType = "video/mp4"

        val retrieved = BtrSessionManager.get(sessionId)
        assertEquals(50_000_000L, retrieved?.totalLength)
        assertEquals("video/mp4", retrieved?.contentType)
    }

    @Test
    fun testOverseasStreamSynthesizesOverseasMirrors() {
        val overseasPrimary = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(overseasPrimary)

        assertTrue("Overseas stream should synthesize overseas mirrors", backups.isNotEmpty())
        for (backup in backups) {
            assertTrue("Synthesized backup must be an overseas ov node", backup.contains("ov.bilivideo.com"))
            assertFalse("Must not include primary host", backup.contains("mirrorcosov"))
        }
    }

    @Test
    fun testCustomCdnPoolUsageInSessionManager() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val customPool = listOf(
            "upos-tf-all-tx.bilivideo.com",
            "upos-tf-all-hw.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com"
        )
        val config = AcceleratorConfig(customCdnPool = customPool)
        val backups = BtrSessionManager.synthesizeCompatibleBackups(primary, config)

        assertTrue("Backups should be synthesized using custom pool", backups.isNotEmpty())
        for (backup in backups) {
            assertFalse("Must not include primary host", backup.contains("upos-sz-mirrorali.bilivideo.com"))
            val hasCustomHost = customPool.any { backup.contains(it) }
            assertTrue("Synthesized backup must belong to custom pool", hasCustomHost)
        }
    }
}
