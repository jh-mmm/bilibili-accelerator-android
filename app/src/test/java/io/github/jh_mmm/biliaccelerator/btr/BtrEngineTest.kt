package io.github.jh_mmm.biliaccelerator.btr

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRefreshCallback
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import io.github.jh_mmm.biliaccelerator.core.RewriteResult
import io.github.jh_mmm.biliaccelerator.core.StatsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

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
        assertTrue("Overseas Akamai stream should synthesize compatible mirrors", backups.isNotEmpty())
        assertTrue("Synthesized mirrors should prioritize overseas ov nodes", backups.any { it.contains("ov.bilivideo.com") })
        for (backup in backups) {
            assertFalse("Must exclude Akamai hosts", backup.contains("akamaized.net") || backup.contains("mirrorakam"))
            assertFalse("Must exclude HK-specific cn-hk-eq nodes", backup.contains("cn-hk-eq-"))
            assertFalse("Must exclude bstarstatic.com", backup.contains("bstarstatic.com"))
        }
    }

    @Test
    fun testSynthesizeCompatibleBackupsExcludesOverseasAndAkamai() {
        val primary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1700000000"
        val backups = BtrSessionManager.synthesizeCompatibleBackups(primary)

        assertTrue("Compatible backups should be generated", backups.isNotEmpty())
        assertTrue("Cross-tier pool should include ov mirrors for race selection", backups.any { it.contains("ov.bilivideo.com") })
        for (backup in backups) {
            assertFalse("Must not include primary host", backup.contains("upos-sz-mirrorali.bilivideo.com"))
            assertFalse("Must not include HK-specific cn-hk-eq mirrors", backup.contains("cn-hk-eq-"))
            assertFalse("Must not include bstarstatic.com mirrors", backup.contains("bstarstatic.com"))
            assertFalse("Must not include Akamai mirrors", backup.contains("akamaized.net") || backup.contains("mirrorakam"))
        }
    }

    @Test
    fun testSynthesizeCompatibleBackupsExcludesRelayAndMcdnUrls() {
        // 对齐 BTR Issue #47：中继/MCDN/P2P 流绝不合成普通 UPOS 镜像，杜绝 HTTP 403 熔断
        val relay1 = "http://xy123.mcdn.bilivideo.cn:4483/v1/resource/upgcxcode/54/12/12345.m4s"
        val relay2 = "https://proxy-tf-all-ws.bilivideo.com/?url=http%3A%2F%2Fxy.mcdn.bilivideo.cn"
        val relay3 = "https://edge.example.com/upgcxcode/54/12/12345.m4s?os=mcdn"

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
        // >= 250MB -> 2048KB (4K/8K/超高码率大视频自适应提升至 2MB，减少高吞吐请求开销)
        assertEquals(2048 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(500 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
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

        assertTrue("Overseas stream should synthesize compatible mirrors", backups.isNotEmpty())
        assertTrue("Overseas synthesis should include ov mirrors", backups.any { it.contains("ov.bilivideo.com") })
        for (backup in backups) {
            assertFalse("Must not include primary host", backup.contains("mirrorcosov"))
            assertFalse("Must not include Akamai hosts", backup.contains("akamaized.net") || backup.contains("mirrorakam"))
            assertFalse("Must not include HK-dedicated cn-hk-eq nodes", backup.contains("cn-hk-eq-"))
            assertFalse("Must not include bstarstatic.com", backup.contains("bstarstatic.com"))
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

    @Test
    fun testCdnSpeedTrackingAndEWMA() {
        val host = "upos-sz-mirrorcos.bilivideo.com"
        // 初始记录：1MB 数据，耗时 50ms -> 20MB/s (20,000,000 Bps)
        BtrChunkEngine.recordHostSpeed(host, 1024 * 1024L, 50L)
        val initialSpeed = BtrChunkEngine.getHostSpeed(host)
        assertTrue("Initial speed should be recorded", initialSpeed > 0)

        // 再次记录：1MB 数据，耗时 100ms -> 10MB/s
        BtrChunkEngine.recordHostSpeed(host, 1024 * 1024L, 100L)
        val ewmaSpeed = BtrChunkEngine.getHostSpeed(host)
        // EWMA: 20M * 0.7 + 10M * 0.3 = 17M
        assertTrue("Speed should be smoothed by EWMA", ewmaSpeed in 15_000_000.0..19_000_000.0)
    }

    @Test
    fun testChunkTaskProgressResumeSplicing() {
        val totalBytes = 512 * 1024
        val prefixBytes = 200 * 1024
        val prefix = ByteArray(prefixBytes) { 1.toByte() }
        val tail = ByteArray(totalBytes - prefixBytes) { 2.toByte() }

        val task = BtrChunkEngine.ChunkTask(
            index = 0,
            startByte = 0,
            endByte = totalBytes.toLong() - 1,
            receivedBytes = prefixBytes,
            prefixData = prefix
        )

        assertEquals(prefixBytes, task.receivedBytes)
        assertNotNull(task.prefixData)

        // 模拟拼接
        val combined = ByteArray(totalBytes)
        System.arraycopy(task.prefixData!!, 0, combined, 0, task.receivedBytes)
        System.arraycopy(tail, 0, combined, task.receivedBytes, tail.size)

        task.data = combined
        task.isSuccess = true

        assertEquals(totalBytes, task.data?.size)
        assertEquals(1.toByte(), task.data?.get(0))
        assertEquals(1.toByte(), task.data?.get(prefixBytes - 1))
        assertEquals(2.toByte(), task.data?.get(prefixBytes))
        assertEquals(2.toByte(), task.data?.get(totalBytes - 1))
    }

    @Test
    fun testLocalProxyParametersUpdate() {
        BtrLocalProxyServer.updateParameters(
            concurrency = 12,
            chunkSizeKb = 1024,
            autoConcurrency = false,
            autoChunkSize = false
        )
    }

    @Test
    fun testLiveCandidatePcdnFiltering() {
        val candidates = listOf(
            "https://xy123x456x789xy.mcdn.bilivideo.cn:4483/live-bvc/123.flv",
            "https://114.114.114.114:8080/live-bvc/123.flv",
            "https://upos-sz-mirror14b.bilivideo.com/live-bvc/123.flv",
            "https://d1--cn-gotcha208.bilivideo.com/live-bvc/123.flv"
        )
        val cleaned = io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.filterLiveCandidates(candidates)
        assertEquals(1, cleaned.size)
        assertTrue("Only official live CDN should be retained", cleaned[0].contains("cn-gotcha208.bilivideo.com"))
    }

    @Test
    fun testParseRangeHeaderStandard() {
        val range = BtrLocalProxyServer.parseRangeHeader("bytes=0-1024")
        assertTrue(range.hasRange)
        assertFalse(range.isSuffix)
        assertEquals(0L, range.start)
        assertEquals(1024L, range.end)
    }

    @Test
    fun testParseRangeHeaderOpenEnded() {
        val range = BtrLocalProxyServer.parseRangeHeader("bytes=524288-")
        assertTrue(range.hasRange)
        assertFalse(range.isSuffix)
        assertEquals(524288L, range.start)
        assertEquals(null, range.end)
    }

    @Test
    fun testParseRangeHeaderSingleByteProbe() {
        // 关键探针请求 bytes=0-0
        val range = BtrLocalProxyServer.parseRangeHeader("bytes=0-0")
        assertTrue(range.hasRange)
        assertFalse(range.isSuffix)
        assertEquals(0L, range.start)
        assertEquals(0L, range.end)
    }

    @Test
    fun testParseRangeHeaderSuffixRange() {
        // Suffix Range: bytes=-500 与 bytes=-1024
        val range500 = BtrLocalProxyServer.parseRangeHeader("bytes=-500")
        assertTrue("Must be identified as valid range", range500.hasRange)
        assertTrue("Must be marked as suffix range", range500.isSuffix)
        assertEquals(500L, range500.suffixLength)

        val range1024 = BtrLocalProxyServer.parseRangeHeader("bytes=-1024")
        assertTrue(range1024.hasRange)
        assertTrue(range1024.isSuffix)
        assertEquals(1024L, range1024.suffixLength)
    }

    @Test
    fun testParseRangeHeaderEmptyOrInvalid() {
        val rangeNull = BtrLocalProxyServer.parseRangeHeader(null)
        assertFalse(rangeNull.hasRange)
        assertEquals(0L, rangeNull.start)
        assertEquals(null, rangeNull.end)

        val rangeInvalid = BtrLocalProxyServer.parseRangeHeader("invalid_header")
        assertFalse(rangeInvalid.hasRange)
    }

    @Test
    fun testEpisodeMediaUrlRecognition() {
        val ep1 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/12345.m4s?ep_id=987654"
        val ep2 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/12345.m4s?season_id=123"
        val ep3 = "https://upos-sz-mirrorali.bilivideo.com/bangumi/play/ep12345.m4s"
        val ep4 = "https://upos-sz-mirrorali.bilivideo.com/ogv/playview/12345.m4s"
        val ep5 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/123.m4s?business=pgc"
        val ugc = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/12345.m4s?deadline=1700000000"

        assertTrue("ep_id URL should be recognized as episode", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodeMediaUrl(ep1))
        assertTrue("season_id URL should be recognized as episode", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodeMediaUrl(ep2))
        assertTrue("bangumi path should be recognized as episode", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodeMediaUrl(ep3))
        assertTrue("ogv path should be recognized as episode", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodeMediaUrl(ep4))
        assertTrue("business=pgc should be recognized as episode", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodeMediaUrl(ep5))
        assertFalse("Standard UGC stream should not be marked as episode", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodeMediaUrl(ugc))
    }

    @Test
    fun testEpisodePreviewDetection() {
        val preview1 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/12345.m4s?is_preview=1"
        val preview2 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/12345.m4s?preview=1"
        val preview3 = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/12345.m4s?trial=1"
        val normal = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/12345.m4s?is_preview=0"

        assertTrue("is_preview=1 must be recognized as preview", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodePreview(preview1))
        assertTrue("preview=1 must be recognized as preview", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodePreview(preview2))
        assertTrue("trial=1 must be recognized as preview", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodePreview(preview3))
        assertFalse("is_preview=0 must not be recognized as preview", io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore.isEpisodePreview(normal))
    }

    @Test
    fun testEpisodeSessionRegistrationAndRegionalIsolation() {
        // 1. bstarstatic.com 专属区域签名流严禁混入 bilivideo 镜像，防止签名 403
        val bstarUrl = "https://cdn-1.bstarstatic.com/upgcxcode/12/34/12345.m4s?ep_id=12345"
        val bstarSid = BtrSessionManager.register(primaryUrl = bstarUrl, isEpisode = true)
        val bstarSession = BtrSessionManager.get(bstarSid)
        assertNotNull(bstarSession)
        assertTrue("Session must be marked as episode", bstarSession?.isEpisode == true)
        assertEquals("https://www.bilibili.com/bangumi/play/", bstarSession?.referer)
        assertTrue("bstarstatic.com must NOT synthesize bilivideo mirrors", bstarSession?.backupUrls.isNullOrEmpty())

        // 2. cn-hk-eq 港澳台番剧流允许合成 HK 节点与海外/跨区官方镜像
        val hkEpisodeUrl = "https://cn-hk-eq-01-01.bilivideo.com/upgcxcode/12/34/12345.m4s?ep_id=12345"
        val hkSid = BtrSessionManager.register(primaryUrl = hkEpisodeUrl, isEpisode = true)
        val hkSession = BtrSessionManager.get(hkSid)
        val hkBackups = hkSession?.backupUrls.orEmpty()
        assertTrue("HK regional PGC must synthesize compatible backups", hkBackups.isNotEmpty())
        assertTrue("HK PGC backups should include cn-hk-eq-01-03 or ov nodes",
            hkBackups.any { it.contains("cn-hk-eq-01-03.bilivideo.com") || it.contains("ov.bilivideo.com") })
        assertFalse("HK backups must never include bstarstatic.com", hkBackups.any { it.contains("bstarstatic.com") })
    }

    @Test
    fun testLiveCoreUrlDetectionAndProxyUnwrap() {
        val segUrl = "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123456/live_123456_100.m4s"
        val playlistUrl = "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123456/live_123456.m3u8"
        val nonLiveUrl = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s"

        assertTrue("Valid live segment URL must be detected", BtrLiveCore.isLiveSegmentUrl(segUrl))
        assertTrue("Valid live playlist URL must be detected", BtrLiveCore.isLivePlaylistUrl(playlistUrl))
        assertFalse("VOD URL must not be detected as live segment", BtrLiveCore.isLiveSegmentUrl(nonLiveUrl))

        val wrappedUrl = "https://d1.smtcdns.net/d1--cn-gotcha204.bilivideo.com/live-bvc/123.m4s"
        val unwrapped = BtrLiveCore.unwrapProxyUrl(wrappedUrl)
        assertEquals("https://d1--cn-gotcha204.bilivideo.com/live-bvc/123.m4s", unwrapped)
    }

    @Test
    fun testLiveCoreParseM3u8() {
        val sampleM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:2
            #EXT-X-MAP:URI="init.m4s"
            #EXTINF:1.000,
            live_101.m4s
            #EXTINF:1.000,
            live_102.m4s
        """.trimIndent()

        val parsed = BtrLiveCore.parseM3u8(sampleM3u8, "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/live.m3u8")
        assertEquals("https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/init.m4s", parsed.mapUrl)
        assertEquals(2, parsed.segments.size)
        assertEquals(101L, parsed.segments[0].num)
        assertEquals("https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/live_101.m4s", parsed.segments[0].url)
        assertEquals(102L, parsed.segments[1].num)
        assertEquals(102L, parsed.lastNum)
    }

    @Test
    fun testLiveHostPoolRankingAndBanning() {
        var bannedHost = ""
        val pool = BtrLiveHostPool(onBan = { bannedHost = it })

        val host1 = "d1--cn-gotcha204.bilivideo.com"
        val host2 = "d1--cn-gotcha208.bilivideo.com"

        pool.add(host1, proven = false)
        pool.add(host2, proven = false)

        // host1 探测成功
        pool.success(host1, fbMs = 45.0, bps = 2_000_000.0)

        val topHosts = pool.pick(1)
        assertEquals(1, topHosts.size)
        assertEquals(host1, topHosts[0])

        // host2 两次空包失败 -> 自动熔断禁令 (banned)
        pool.failure(host2, receivedBytes = 0L)
        pool.failure(host2, receivedBytes = 0L)

        assertEquals(host2, bannedHost)
        val pickedAfterBan = pool.pick(2)
        assertFalse("Banned host must not be picked", pickedAfterBan.contains(host2))
        assertTrue("Healthy host must still be picked", pickedAfterBan.contains(host1))
    }

    @Test
    fun testLiveSessionRegistration() {
        val liveUrl = "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/live.m3u8"
        val sessionId = BtrSessionManager.register(
            primaryUrl = liveUrl,
            isLive = true
        )
        val session = BtrSessionManager.get(sessionId)
        assertNotNull(session)
        assertTrue("Session must be flagged as live", session?.isLive == true)
        assertEquals("https://live.bilibili.com/", session?.referer)
        assertNotNull("Live host pool must be initialized", session?.liveHostPool)

        val picked = session?.liveHostPool?.pick(3).orEmpty()
        assertTrue("Live host pool must contain primary host", picked.contains("d1--cn-gotcha204.bilivideo.com"))
    }

    @Test
    fun testSynthesizeUsesFullMainlandAndOverseasPools() {
        val mainlandPrimary = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1"
        val fullMainlandBackups = BtrSessionManager.synthesizeCompatibleBackups(
            mainlandPrimary,
            AcceleratorConfig(btrCdnPoolSize = 10)
        )
        assertTrue("Full synthesis should include ov and secondary mirrors",
            fullMainlandBackups.any { it.contains("ov.bilivideo.com") } &&
                fullMainlandBackups.any { it.contains("mirrorcoso1") || it.contains("mirrorbos") || it.contains("mirrorbd") })
        assertFalse("Regular stream synthesis must exclude HK-dedicated cn-hk-eq nodes",
            fullMainlandBackups.any { it.contains("cn-hk-eq-") })

        val overseasPrimary = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1"
        val fullOverseasBackups = BtrSessionManager.synthesizeCompatibleBackups(
            overseasPrimary,
            AcceleratorConfig(btrCdnPoolSize = 8)
        )
        assertTrue("Overseas synthesis supports cross-tier ov and mainland UPOS mirrors",
            fullOverseasBackups.any { it.contains("ov.bilivideo.com") } &&
                fullOverseasBackups.any { it.contains("mirrorcos.bilivideo.com") || it.contains("mirrorali.bilivideo.com") })
        assertFalse("Non-HK overseas stream must exclude HK-dedicated cn-hk-eq nodes",
            fullOverseasBackups.any { it.contains("cn-hk-eq-") })

        val hkPrimary = "https://cn-hk-eq-01-01.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1"
        val hkBackups = BtrSessionManager.synthesizeCompatibleBackups(hkPrimary, AcceleratorConfig(btrCdnPoolSize = 8))
        assertTrue("HK stream synthesis must include peer HK node", hkBackups.any { it.contains("cn-hk-eq-01-03.bilivideo.com") })
    }

    @Test
    fun testSessionBanListIsolatesUrlVsHostAcrossSessions() {
        val host = "upos-sz-mirrorcos.bilivideo.com"
        val sidA = BtrSessionManager.register("https://$host/upgcxcode/1/videoA.m4s?bad=1")
        val sidB = BtrSessionManager.register("https://$host/upgcxcode/2/videoB.m4s?good=1")
        val sessionA = BtrSessionManager.get(sidA)!!
        val sessionB = BtrSessionManager.get(sidB)!!

        val urlA1 = "https://$host/upgcxcode/1/videoA.m4s?bad=1"
        val urlA2 = "https://$host/upgcxcode/1/videoA.m4s?alt=2"

        // 先标记 host 在 sessionA 曾成功服务过 urlA2
        sessionA.banList.recordSuccess(urlA2, host)
        // urlA1 返回 HTTP 403，此时应仅封禁 urlA1（address 级），而保留已验证可用的节点 host
        val isNodeFault = sessionA.banList.recordHttpFailure(urlA1, host, 403)
        assertFalse("HTTP 403 must not trigger global node-level fault", isNodeFault)
        assertTrue("Failed URL must be banned inside session A", sessionA.banList.isBanned(urlA1, host))
        assertFalse("Known-good host must keep serving other URLs in session A", sessionA.banList.isBanned(urlA2, host))

        // 另一视频会话 sessionB 对同一健康 CDN host 绝不受 sessionA 403 污染
        assertFalse("Unrelated session B must not be penalized by session A's 403", sessionB.banList.isBanned(sessionB.primaryUrl, host))
    }

    @Test
    fun testChunkTaskLiveInFlightPrefixSharingSnapshot() {
        val expectedTotal = 512 * 1024
        val task = BtrChunkEngine.ChunkTask(index = 0L, startByte = 0L, endByte = expectedTotal - 1L)

        // 模拟主请求尚未结束、正在流式读取前 48KB（已超越 RESUME_MIN_BYTES 32KB 门槛）
        assertTrue(task.tryClaimPrefixProducer(attemptId = 101L))
        val chunk16k = ByteArray(16 * 1024) { 0x2A.toByte() }
        repeat(3) {
            task.appendLivePrefix(101L, chunk16k, chunk16k.size, expectedTotal)
        }

        assertEquals(48 * 1024, task.receivedBytes)
        val snapshot = task.takePrefixSnapshot(32 * 1024, expectedTotal)
        assertNotNull("In-flight prefix snapshot must be available immediately for hedged attempt", snapshot)
        assertEquals(48 * 1024, snapshot?.size)
        assertEquals(0x2A.toByte(), snapshot?.get(0))

        // 主路胜出后清理前缀缓存
        task.isSuccess = true
        task.clearPrefixBuffersOnWin()
        assertEquals(null, task.takePrefixSnapshot(32 * 1024, expectedTotal))
    }

    @Test
    fun testDynamicConcurrencyControllerScalingAndPushback() {
        var t = 1_000_000L
        val controller = BtrChunkEngine.DynamicConcurrencyController(
            initialConcurrency = 6,
            autoScale = true,
            minConcurrency = 2,
            maxConcurrency = 16,
            initialNowMs = t
        )
        assertEquals(6, controller.currentConcurrency)

        // 第一个 1500ms 周期记录基准吞吐并上探试跑 8，第二个 1500ms 周期确认增益 >= 8% 并保留 8
        controller.recordProgress(1024 * 1024L, nowMs = t)
        t += 1_500L
        controller.recordProgress(4 * 1024 * 1024L, nowMs = t)
        assertEquals("Should probe up from 6 to 8", 8, controller.currentConcurrency)

        t += 1_500L
        controller.recordProgress(6 * 1024 * 1024L, nowMs = t)
        assertEquals("Should commit 8 after >8% throughput gain", 8, controller.currentConcurrency)

        // 模拟上游返回 HTTP 429 Pushback -> 立即降档并进入冷却
        val steppedDown = controller.onPushback(nowMs = t + 100L)
        assertEquals("Pushback must step down concurrency rung from 8 to 6", 6, steppedDown)
        assertEquals(6, controller.currentConcurrency)
    }

    @Test
    fun testContentRangeStartAndTotalLengthValidation() {
        val sid = BtrSessionManager.register("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/12345.m4s")
        val session = BtrSessionManager.get(sid)!!
        session.totalLength = 10_000_000L

        assertTrue(BtrChunkEngine.isValidContentRange(206, "bytes 0-65535/10000000", 0L, session))
        assertFalse("Mismatched Content-Range start must be rejected",
            BtrChunkEngine.isValidContentRange(206, "bytes 128-65535/10000000", 0L, session))
        assertFalse("Mismatched total length across CDN mirrors must be rejected",
            BtrChunkEngine.isValidContentRange(206, "bytes 0-65535/9999999", 0L, session))
    }

    @Test
    fun testLiveMapUrlProtectionAndSpeculativeNextSegmentUrl() {
        BtrLiveEngine.clearCache()
        val mapUrl = "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/init.m4s"
        BtrLiveEngine.protectMapUrl(mapUrl)
        BtrLiveEngine.putCacheForTest(
            mapUrl,
            CachedLiveSegment(byteArrayOf(1, 2, 3), "video/mp4", "d1--cn-gotcha204.bilivideo.com", cachedAt = 1L)
        )

        // 塞入超过 CACHE_LIMIT (32) 个普通分片触发缓存淘汰
        for (i in 1..40) {
            val segUrl = "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/live_$i.m4s"
            BtrLiveEngine.putCacheForTest(
                segUrl,
                CachedLiveSegment(byteArrayOf(i.toByte()), "video/iso.segment", "d1--cn-gotcha204.bilivideo.com", cachedAt = 1000L + i)
            )
        }

        assertNotNull("Protected #EXT-X-MAP init segment must survive cache eviction", BtrLiveEngine.getCachedForTest(mapUrl))

        val lastSeg = LiveSegment(
            name = "live_105.m4s",
            url = "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/live_105.m4s?sign=abc",
            num = 105L,
            duration = 1.0
        )
        val guessed = BtrLiveCore.guessNextSegmentUrl(lastSeg)
        assertEquals("https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/live_106.m4s?sign=abc", guessed)
        BtrLiveEngine.clearCache()
    }

    @Test
    fun testChunkSizeScalingRespectsBasePreference() {
        // 音频始终保持 128KB
        assertEquals(128 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(300 * 1024 * 1024L, 1024, autoChunkSize = true, isAudio = true))

        // 标准基准 512KB 维持 256 / 512 / 1024 / 2048 阶梯
        assertEquals(256 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(10 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        assertEquals(512 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(30 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(100 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))
        assertEquals(2048 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(300 * 1024 * 1024L, 512, autoChunkSize = true, isAudio = false))

        // 较大基准 1024KB 在中等体积视频应维持 1024KB 而非退回 512KB
        assertEquals(512 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(10 * 1024 * 1024L, 1024, autoChunkSize = true, isAudio = false))
        assertEquals(1024 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(30 * 1024 * 1024L, 1024, autoChunkSize = true, isAudio = false))
        assertEquals(2048 * 1024L, BtrChunkEngine.calculateDynamicChunkSize(100 * 1024 * 1024L, 1024, autoChunkSize = true, isAudio = false))
    }

    @Test
    fun testStrictContentRangeValidationAndCaseInsensitiveParse() {
        val sid = BtrSessionManager.register("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/999.m4s")
        val session = BtrSessionManager.get(sid)!!
        session.totalLength = 10_000_000L

        assertFalse("206 without Content-Range header must be rejected",
            BtrChunkEngine.isValidContentRange(206, null, 0L, session))
        assertFalse("206 with blank Content-Range header must be rejected",
            BtrChunkEngine.isValidContentRange(206, "   ", 0L, session))
        assertFalse("206 with mismatched end byte must be rejected",
            BtrChunkEngine.isValidContentRange(206, "bytes 0-1000/10000000", 0L, session, expectedEnd = 65535L))
        assertTrue("206 matching exact start, end, and total must pass",
            BtrChunkEngine.isValidContentRange(206, "bytes 0-65535/10000000", 0L, session, expectedEnd = 65535L))
        assertEquals(1024L, BtrChunkEngine.parseRangeStart("BYTES 1024-2047/4096"))
    }

    @Test
    fun testRouteKeyIsolationAndSmoothWeightedRoundRobinAndTailTrial() {
        val fastA = "https://fast-a.bilivideo.com/upgcxcode/1/test_swrr.m4s?sign=a1"
        val medB = "https://med-b.bilivideo.com/upgcxcode/1/test_swrr.m4s?sign=b1"
        val slowC = "https://slow-c.bilivideo.com/upgcxcode/1/test_swrr.m4s?sign=c1"
        val unmeasuredD = "https://new-d.bilivideo.com/upgcxcode/1/test_swrr.m4s?sign=d1"

        assertEquals(
            BtrChunkEngine.routeKey(fastA),
            BtrChunkEngine.routeKey("https://fast-a.bilivideo.com/upgcxcode/1/test_swrr.m4s?sign=other")
        )
        assertFalse(
            BtrChunkEngine.routeKey(fastA) ==
                BtrChunkEngine.routeKey("https://fast-a.bilivideo.com/upgcxcode/2/other_video.m4s")
        )

        BtrChunkEngine.recordRouteSpeed(fastA, 20L * 1024 * 1024, 1000L) // 20 MB/s
        BtrChunkEngine.recordRouteSpeed(medB, 10L * 1024 * 1024, 1000L)  // 10 MB/s
        BtrChunkEngine.recordRouteSpeed(slowC, 1L * 1024 * 1024, 1000L)  // 1 MB/s (< 20/12 = 1.67 MB/s -> cutoff!)

        val sid = BtrSessionManager.register(fastA, listOf(medB, slowC, unmeasuredD))
        val session = BtrSessionManager.get(sid)!!
        val allUrls = listOf(fastA, medB, slowC, unmeasuredD)

        var picksA = 0
        var picksB = 0
        for (i in 0L until 12L) {
            val ordered = BtrChunkEngine.sortUrlsByHealthAndSpeed(allUrls, i, session, allowUnmeasuredTrial = false)
            val first = ordered.first()
            assertFalse("Unmeasured node must not take primary on startup chunks", first == unmeasuredD)
            assertFalse("Slow cutoff node must not take primary", first == slowC)
            if (first == fastA) picksA++ else if (first == medB) picksB++
        }
        assertTrue("SWRR must assign strictly more chunks to fastA ($picksA) than medB ($picksB)", picksA > picksB && picksB > 0)

        val tailOrdered = BtrChunkEngine.sortUrlsByHealthAndSpeed(allUrls, 12L, session, allowUnmeasuredTrial = true)
        assertEquals("Unmeasured node should receive tail trial slot", unmeasuredD, tailOrdered.first())
    }

    @Test
    fun testDynamicConcurrencyDemandGatingAndStepDown() {
        var t = 2_000_000L
        val controller = BtrChunkEngine.DynamicConcurrencyController(
            initialConcurrency = 4,
            autoScale = true,
            minConcurrency = 2,
            maxConcurrency = 8,
            initialNowMs = t
        )
        // 无队列饱和压力时不得盲目升档
        controller.recordDemand(active = 1, limit = 4, queued = 0, nowMs = t)
        t += 1_500L
        controller.recordProgress(4 * 1024 * 1024L, nowMs = t)
        assertEquals("Must NOT scale up without saturation pressure", 4, controller.currentConcurrency)

        // 存在持续饱和压力时应升档试探，且不超过用户上限 8
        controller.recordDemand(active = 4, limit = 4, queued = 5, nowMs = t)
        t += 1_500L
        controller.recordProgress(6 * 1024 * 1024L, nowMs = t)
        assertEquals("Should scale up from 4 to 6 under saturation", 6, controller.currentConcurrency)

        // 低需求持续 > 8 秒后应自动回落
        t += 9_000L
        controller.recordDemand(active = 0, limit = 6, queued = 0, nowMs = t)
        assertEquals("Should step down after sustained low demand", 4, controller.currentConcurrency)
    }

    @Test
    fun testStatsManagerAcceptsMultipleBtrTransfersPerSession() {
        val now = System.currentTimeMillis()
        val uniqueSuffix = System.nanoTime()
        val first = RewriteResult(
            changed = true, originalUrl = "", finalUrl = "", originalHost = "upos-sz-mirrorcos.bilivideo.com",
            targetHost = "127.0.0.1:18000", reason = "BTR并发加速 · 1分片(0.1MB)", isPcdn = false, isMcdn = false,
            segmentKey = "btr_stream_sess_${uniqueSuffix}_1", isBtr = true, btrChunks = 1, btrBytes = 64 * 1024L, btrConcurrency = 4, btrCdnCount = 1, btrAvgLatencyMs = 20L
        )
        val second = RewriteResult(
            changed = true, originalUrl = "", finalUrl = "", originalHost = "upos-sz-mirrorcos.bilivideo.com",
            targetHost = "127.0.0.1:18000", reason = "BTR并发加速 · 40分片(20.0MB)", isPcdn = false, isMcdn = false,
            segmentKey = "btr_stream_sess_${uniqueSuffix}_2", isBtr = true, btrChunks = 40, btrBytes = 20L * 1024 * 1024, btrConcurrency = 6, btrCdnCount = 2, btrAvgLatencyMs = 25L
        )
        assertTrue(StatsManager.shouldAcceptCrossProcessSegment(first, now))
        assertTrue(StatsManager.shouldAcceptCrossProcessSegment(second, now + 500L))
        assertFalse("Identical duplicate report within window must still dedup",
            StatsManager.shouldAcceptCrossProcessSegment(second, now + 1000L))
    }

    @Test
    fun testLiveCoreFmp4DetectionAndQuerySegmentNumber() {
        val baseUrl = "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123/index.m3u8"
        val fmp4M3u8 = "#EXTM3U\n#EXT-X-MAP:URI=\"init.m4s\"\n#EXTINF:1.0,\nlive_205.m4s?token=abc\n"
        val tsM3u8 = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1.0,\nlive_1.ts?token=abc\n"
        val parsedFmp4 = BtrLiveCore.parseM3u8(fmp4M3u8, baseUrl)
        val parsedTs = BtrLiveCore.parseM3u8(tsM3u8, baseUrl)

        assertTrue(BtrLiveCore.isFmp4Playlist(fmp4M3u8, parsedFmp4))
        assertFalse(BtrLiveCore.isFmp4Playlist(tsM3u8, parsedTs))
        assertEquals(205L, BtrLiveCore.segmentNumber("live_205.m4s?token=abc"))
    }

    @Test
    fun testLoopbackProxy416UnknownLengthAndKeepAliveFraming() {
        val totalPayload = ByteArray(150_000) { idx -> (idx % 251).toByte() }
        val hideTotalLength = java.util.concurrent.atomic.AtomicBoolean(false)
        val ignoreRangeWith200 = java.util.concurrent.atomic.AtomicBoolean(false)

        val mockCdn = java.net.ServerSocket(0)
        val cdnThread = Thread {
            while (!mockCdn.isClosed) {
                val client = try { mockCdn.accept() } catch (_: Exception) { break }
                Thread {
                    client.use { sock ->
                        val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                        val reqLine = reader.readLine() ?: return@use
                        var rangeHeader: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line.substringAfter(':').trim()
                            }
                        }
                        val out = sock.getOutputStream()
                        if (ignoreRangeWith200.get()) {
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${totalPayload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(totalPayload)
                            out.flush()
                            return@use
                        }
                        val rangeSpec = rangeHeader?.removePrefix("bytes=")?.split('-')
                        val start = rangeSpec?.getOrNull(0)?.toIntOrNull() ?: 0
                        val reqEnd = rangeSpec?.getOrNull(1)?.toIntOrNull() ?: (totalPayload.size - 1)
                        val actualEnd = minOf(reqEnd, totalPayload.size - 1)
                        if (start >= totalPayload.size) {
                            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${totalPayload.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            return@use
                        }
                        val len = actualEnd - start + 1
                        val totalTag = if (hideTotalLength.get()) "*" else totalPayload.size.toString()
                        val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$actualEnd/$totalTag\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray(Charsets.UTF_8))
                        out.write(totalPayload, start, len)
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        try {
            BtrLocalProxyServer.start()
            val port = BtrLocalProxyServer.port
            assertTrue("Proxy server should start", port > 0)

            // 1. 未知总长 (Content-Range: bytes .../*) 下严禁 64KB 截断，且 Keep-Alive 请求必须以 Connection: close 正确结束流而不挂死
            hideTotalLength.set(true)
            val unknownUrl = "http://127.0.0.1:${mockCdn.localPort}/test/1/unknown.m4s"
            val sid1 = BtrSessionManager.register(unknownUrl)
            val conn1 = java.net.URL("http://127.0.0.1:$port/stream/$sid1/media.m4s").openConnection() as java.net.HttpURLConnection
            conn1.connectTimeout = 5000
            conn1.readTimeout = 5000
            conn1.setRequestProperty("Connection", "keep-alive")
            assertEquals(200, conn1.responseCode)
            assertEquals("close", conn1.getHeaderField("Connection")?.lowercase())
            val received1 = conn1.inputStream.use { it.readBytes() }
            assertEquals("Unknown total length stream must not truncate at 64KB probe!", totalPayload.size, received1.size)
            assertTrue("All bytes must match", totalPayload.contentEquals(received1))
            BtrSessionManager.remove(sid1)

            // 2. 越界 Range 与倒置 Range 必须返回 416 Range Not Satisfiable
            hideTotalLength.set(false)
            val normalUrl = "http://127.0.0.1:${mockCdn.localPort}/test/1/normal.m4s"
            val sid2 = BtrSessionManager.register(normalUrl)
            val invConn = java.net.URL("http://127.0.0.1:$port/stream/$sid2/media.m4s").openConnection() as java.net.HttpURLConnection
            invConn.setRequestProperty("Range", "bytes=200-100")
            assertEquals("Inverted range must return 416", 416, invConn.responseCode)
            invConn.disconnect()

            // 正常探测一次写入 totalLength，再请求越界偏移
            val okConn = java.net.URL("http://127.0.0.1:$port/stream/$sid2/media.m4s").openConnection() as java.net.HttpURLConnection
            okConn.setRequestProperty("Range", "bytes=0-1023")
            assertEquals(206, okConn.responseCode)
            assertEquals(1024, okConn.inputStream.use { it.readBytes() }.size)

            val oobConn = java.net.URL("http://127.0.0.1:$port/stream/$sid2/media.m4s").openConnection() as java.net.HttpURLConnection
            oobConn.setRequestProperty("Range", "bytes=999999-")
            assertEquals("Out-of-bounds range must return 416", 416, oobConn.responseCode)
            oobConn.disconnect()

            // 3. Seek 到非 0 偏移时若上游忽略 Range 返回 200 全量数据，必须拒收错位字节并延迟返回 502（非 206 误导播放器）
            ignoreRangeWith200.set(true)
            val seekConn = java.net.URL("http://127.0.0.1:$port/stream/$sid2/media.m4s").openConnection() as java.net.HttpURLConnection
            seekConn.connectTimeout = 5000
            seekConn.readTimeout = 10000
            seekConn.setRequestProperty("Range", "bytes=70000-100000")
            assertEquals("Deferred Seek headers must report 502 when upstream returns 200 for non-zero offset", 502, seekConn.responseCode)
            seekConn.disconnect()
            BtrSessionManager.remove(sid2)
        } finally {
            runCatching { mockCdn.close() }
            cdnThread.interrupt()
        }
    }

    @Test
    fun testDynamicConcurrencyMidStreamStepDownStallPushbackAndCrossProcessPersistence() {
        val tempDir = java.nio.file.Files.createTempDirectory("btr_conc_persist").toFile()
        try {
            PlayinfoRegistry.resetForTesting(clearDir = true)
            PlayinfoRegistry.configureSharedStateDir(tempDir)
            BtrChunkEngine.resetConcurrencyPersistenceForTesting(clearDisk = true)

            var now = 1_000_000L
            val c1 = BtrChunkEngine.DynamicConcurrencyController(
                initialConcurrency = 8,
                autoScale = true,
                minConcurrency = 2,
                maxConcurrency = 16,
                initialNowMs = now,
                enablePersistence = true
            )
            assertEquals(8, c1.currentConcurrency)

            // Buffer window full / partial active without queued work (even active = limit - 1, queued = 0) -> after >= 8s must step down mid-stream
            c1.recordDemand(active = 7, limit = 8, queued = 0, nowMs = now)
            now += 8_500L
            c1.recordDemand(active = 1, limit = 8, queued = 0, nowMs = now)
            assertEquals("Idle buffer window with queued=0 must step down concurrency mid-stream", 6, c1.currentConcurrency)

            // Player stall must step UP concurrency (per idm-downloader.js) to rescue buffering
            now += 3_000L
            c1.onStall(nowMs = now)
            assertEquals("Player stall must step up concurrency", 8, c1.currentConcurrency)

            // Buffer hunger also steps UP after cooldown
            now += 3_000L
            c1.onBufferHunger(0L, nowMs = now)
            assertEquals("Buffer hunger must step up concurrency", 12, c1.currentConcurrency)

            // Hard 412/429 pushback drops concurrency and imposes hard cap that blocks even onStall
            now += 1_000L
            c1.onPushback(nowMs = now, isHard = true)
            assertEquals(8, c1.currentConcurrency)
            now += 4_000L
            c1.onStall(nowMs = now)
            assertEquals("Hard pushback resting cap must block stall step-up", 8, c1.currentConcurrency)

            // Drop further via second hard pushback to 6 and verify cross-process persistence
            now += 1_000L
            c1.onPushback(nowMs = now, isHard = true)
            assertEquals(6, c1.currentConcurrency)

            // Simulate second process: clear in-memory cache but preserve concurrency_state.json on disk
            BtrChunkEngine.resetConcurrencyPersistenceForTesting(clearDisk = false)
            val c2 = BtrChunkEngine.DynamicConcurrencyController(
                initialConcurrency = 12,
                autoScale = true,
                minConcurrency = 2,
                maxConcurrency = 16,
                initialNowMs = now + 2_000L,
                enablePersistence = true
            )
            assertEquals("New process/session must honor persisted hardCap ceiling", 6, c2.currentConcurrency)

            // Verify active=1, limit=2, queued=0 does NOT prevent idle step-down from 4 -> 2
            BtrChunkEngine.resetConcurrencyPersistenceForTesting(clearDisk = true)
            var t2 = 5_000_000L
            val c3 = BtrChunkEngine.DynamicConcurrencyController(
                initialConcurrency = 4,
                autoScale = true,
                minConcurrency = 2,
                maxConcurrency = 8,
                initialNowMs = t2,
                enablePersistence = true
            )
            c3.recordDemand(active = 3, limit = 4, queued = 0, nowMs = t2)
            t2 += 8_500L
            c3.recordDemand(active = 1, limit = 4, queued = 0, nowMs = t2)
            assertEquals("active < limit with queued=0 must step down 4 -> 2", 2, c3.currentConcurrency)
            // Idle step-down must NOT poison next session's learned baseline down to 2
            BtrChunkEngine.resetConcurrencyPersistenceForTesting(clearDisk = false)
            val c4 = BtrChunkEngine.DynamicConcurrencyController(
                initialConcurrency = 6,
                autoScale = true,
                minConcurrency = 2,
                maxConcurrency = 16,
                initialNowMs = t2 + 1_000L,
                enablePersistence = true
            )
            assertEquals("Idle step-down must not drag next session startup below initial", 6, c4.currentConcurrency)
        } finally {
            BtrChunkEngine.resetConcurrencyPersistenceForTesting(clearDisk = true)
            PlayinfoRegistry.resetForTesting(clearDir = true)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testProactiveSignatureRefreshWindowBackoffAnd403Recovery() {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L
        val nearExpiryUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/99/00/sigtest.m4s?deadline=${nowSec + 60}&token=old"
        val attempts = AtomicInteger(0)

        val sid = BtrSessionManager.register(primaryUrl = nearExpiryUrl, config = null)
        try {
            BtrSessionManager.attachRefreshProvider(sid) {
                attempts.incrementAndGet()
                false
            }
            val session = BtrSessionManager.get(sid)!!
            // Cancel any async timer race before deterministic calls
            session.proactiveRefreshTask?.cancel(false)

            val firstFail = BtrSessionManager.maybeTriggerProactiveRefresh(session, nowMs = nowMs)
            assertFalse(firstFail)
            assertEquals(1, attempts.get())

            // Within 45s exponential backoff cooldown: must NOT invoke provider again
            val duringCooldown = BtrSessionManager.maybeTriggerProactiveRefresh(session, nowMs = nowMs + 10_000L)
            assertFalse(duringCooldown)
            assertEquals("Backoff must suppress repeated refreshes during cooldown", 1, attempts.get())

            // After cooldown, return a fresh signed URL with deadline + 3600s
            val freshUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/99/00/sigtest.m4s?deadline=${nowSec + 3600}&token=fresh"
            BtrSessionManager.attachRefreshProvider(sid) {
                attempts.incrementAndGet()
                BtrSessionManager.refreshSignatureIfSamePath(freshUrl, emptyList())
                true
            }
            val recovered = BtrSessionManager.maybeTriggerProactiveRefresh(session, nowMs = nowMs + 50_000L)
            assertTrue("Proactive refresh must succeed once cooldown passes", recovered)
            assertEquals(freshUrl, session.primaryUrl)
            assertEquals(nowSec + 3600L, session.urlDeadlineEpochSec)
        } finally {
            BtrSessionManager.remove(sid)
        }

        // End-to-end BtrChunkEngine 403 -> forced refresh -> 200 recovery
        val payload = ByteArray(1024) { (it % 131).toByte() }
        val mockCdn = ServerSocket(0)
        val thread = Thread {
            while (!mockCdn.isClosed) {
                val sock = try { mockCdn.accept() } catch (_: Throwable) { break }
                sock.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val reqLine = reader.readLine() ?: return@use
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = s.getOutputStream()
                    if (!reqLine.contains("fresh=1")) {
                        out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes 0-${payload.size - 1}/${payload.size}\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray())
                        out.write(payload)
                    }
                    out.flush()
                }
            }
        }.apply { isDaemon = true; start() }

        var sid403 = ""
        try {
            BtrLocalProxyServer.start()
            val port = BtrLocalProxyServer.port
            val expiredUrl = "http://127.0.0.1:${mockCdn.localPort}/upgcxcode/88/77/403recover.m4s?deadline=100"
            val freshUrl = "http://127.0.0.1:${mockCdn.localPort}/upgcxcode/88/77/403recover.m4s?deadline=${(System.currentTimeMillis() / 1000L) + 3600}&fresh=1"
            sid403 = BtrSessionManager.register(expiredUrl)
            BtrSessionManager.attachRefreshProvider(sid403) {
                BtrSessionManager.refreshSignatureIfSamePath(freshUrl, emptyList())
                true
            }
            val conn = java.net.URL("http://127.0.0.1:$port/stream/$sid403/video.m4s").openConnection() as java.net.HttpURLConnection
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.use { it.readBytes() }
            assertTrue("Client must receive full recovered payload after 403 refresh", payload.contentEquals(body))
        } finally {
            if (sid403.isNotBlank()) BtrSessionManager.remove(sid403)
            runCatching { mockCdn.close() }
            thread.interrupt()
        }

        // End-to-end mid-stream/seek 403 recovery inside fetchChunkWithFailover (when cached totalLength skips probe)
        val fullVideo = ByteArray(150_000) { (it % 223).toByte() }
        val seekExpired = AtomicInteger(0)
        val mockSeekCdn = ServerSocket(0)
        val seekThread = Thread {
            while (!mockSeekCdn.isClosed) {
                val sock = try { mockSeekCdn.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val reqLine = reader.readLine() ?: return@use
                        var rangeHdr = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHdr = line.substringAfter(':').trim()
                            }
                        }
                        val out = s.getOutputStream()
                        if (seekExpired.get() == 1 && !reqLine.contains("fresh=2")) {
                            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            return@use
                        }
                        val parts = rangeHdr.removePrefix("bytes=").split("-")
                        val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val end = minOf(parts.getOrNull(1)?.toIntOrNull() ?: (fullVideo.size - 1), fullVideo.size - 1)
                        val len = (end - start + 1).coerceAtLeast(0)
                        val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$end/${fullVideo.size}\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray())
                        out.write(fullVideo, start, len)
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var sidSeek = ""
        try {
            BtrLocalProxyServer.start()
            val port = BtrLocalProxyServer.port
            val initialUrl = "http://127.0.0.1:${mockSeekCdn.localPort}/upgcxcode/88/77/seek403.m4s?deadline=${(System.currentTimeMillis() / 1000L) + 600}"
            val refreshedUrl = "http://127.0.0.1:${mockSeekCdn.localPort}/upgcxcode/88/77/seek403.m4s?deadline=${(System.currentTimeMillis() / 1000L) + 3600}&fresh=2"
            sidSeek = BtrSessionManager.register(initialUrl)
            BtrSessionManager.attachRefreshProvider(sidSeek) {
                BtrSessionManager.refreshSignatureIfSamePath(refreshedUrl, emptyList())
                true
            }
            // Request 1 caches totalLength metadata
            val cInit = java.net.URL("http://127.0.0.1:$port/stream/$sidSeek/seek.m4s").openConnection() as java.net.HttpURLConnection
            cInit.setRequestProperty("Range", "bytes=0-1023")
            assertEquals(206, cInit.responseCode)
            assertEquals(1024, cInit.inputStream.use { it.readBytes() }.size)

            // Now expire signature on CDN and seek (>0 offset, skips probe!) -> fetchChunkWithFailover must refresh on 403 and deliver exact slice
            seekExpired.set(1)
            val cSeek = java.net.URL("http://127.0.0.1:$port/stream/$sidSeek/seek.m4s").openConnection() as java.net.HttpURLConnection
            cSeek.setRequestProperty("Range", "bytes=70000-120000")
            assertEquals(206, cSeek.responseCode)
            val seekBytes = cSeek.inputStream.use { it.readBytes() }
            assertEquals(50_001, seekBytes.size)
            assertTrue(fullVideo.copyOfRange(70_000, 120_001).contentEquals(seekBytes))
        } finally {
            if (sidSeek.isNotBlank()) BtrSessionManager.remove(sidSeek)
            runCatching { mockSeekCdn.close() }
            seekThread.interrupt()
        }
    }

    @Test
    fun testLiveFlvStream404NoPenalty500SinglePenaltyAndTimeoutClosesResponse() {
        val mode = AtomicInteger(404)
        val mockFlv = ServerSocket(0)
        val serverThread = Thread {
            while (!mockFlv.isClosed) {
                val sock = try { mockFlv.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = s.getOutputStream()
                        when (mode.get()) {
                            404 -> {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                out.flush()
                            }
                            500 -> {
                                out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                out.flush()
                            }
                            else -> {
                                // Stall past FLV race timeout then send 200 OK
                                runCatching { Thread.sleep(220L) }
                                runCatching {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: video/x-flv\r\n\r\nFLVDATA".toByteArray())
                                    out.flush()
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        val banEvents = AtomicInteger(0)
        var sid = ""
        try {
            val url = "http://127.0.0.1:${mockFlv.localPort}/live-bvc/room1.flv"
            sid = BtrSessionManager.register(url, isLive = true)
            val session = BtrSessionManager.get(sid)!!
            val pool = BtrLiveHostPool { banEvents.incrementAndGet() }.apply {
                add("127.0.0.1", proven = false)
            }
            session.liveHostPool = pool

            // 1. 404 must NOT penalize host (state stays untested, emptyReplies/failures stay 0)
            mode.set(404)
            val out404 = ByteArrayOutputStream()
            assertFalse(BtrLiveEngine.pipeLiveFlvStream(session, emptyMap(), out404, timeoutMs = 1500L))
            val statusAfter404 = pool.status().first { it.host == "127.0.0.1" }
            assertEquals("FLV 404 must not block/penalize host", "untested", statusAfter404.state)
            assertEquals(0, banEvents.get())

            // 2. Single 500 must record at most ONE failure (emptyReplies < 2 -> NOT banned yet)
            mode.set(500)
            val out500 = ByteArrayOutputStream()
            assertFalse(BtrLiveEngine.pipeLiveFlvStream(session, emptyMap(), out500, timeoutMs = 1500L))
            val statusAfter500 = pool.status().first { it.host == "127.0.0.1" }
            assertEquals("Single FLV 500 must block host once, NOT double-penalize to banned", "blocked", statusAfter500.state)
            assertEquals(0, banEvents.get())

            // 3. Timeout race must complete cleanly and seal winnerFuture without leaking Response
            mode.set(999)
            val outTimeout = ByteArrayOutputStream()
            assertFalse(BtrLiveEngine.pipeLiveFlvStream(session, emptyMap(), outTimeout, timeoutMs = 90L))
            assertTrue(outTimeout.toString("UTF-8").contains("502"))
        } finally {
            if (sid.isNotBlank()) BtrSessionManager.remove(sid)
            runCatching { mockFlv.close() }
            serverThread.interrupt()
        }
    }

    @Test
    fun testBtrChunkEngineUncached416Malformed200SequentialFailoverTrialAndChunkTaskPrefix() {
        // 1. shouldAllowUnmeasuredTrial periodic exploration (protects startup chunks 0..5)
        assertFalse(BtrChunkEngine.shouldAllowUnmeasuredTrial(0L, 1000L))
        assertFalse(BtrChunkEngine.shouldAllowUnmeasuredTrial(1L, 1000L))
        assertFalse(BtrChunkEngine.shouldAllowUnmeasuredTrial(2L, 1000L))
        assertTrue(BtrChunkEngine.shouldAllowUnmeasuredTrial(8L, 1000L))
        assertFalse(BtrChunkEngine.shouldAllowUnmeasuredTrial(3L, 1000L))
        assertTrue(BtrChunkEngine.shouldAllowUnmeasuredTrial(997L, 1000L))

        // 2. HTTP 200 with Content-Range rejected by isValidContentRange
        val dummySession = BtrMediaSession("s1", "https://upos-sz-mirrorcos.bilivideo.com/a.m4s", emptyList(), null)
        assertFalse(BtrChunkEngine.isValidContentRange(200, "bytes 0-9/10", 0L, dummySession))
        assertTrue(BtrChunkEngine.isValidContentRange(200, null, 0L, dummySession))

        // 3. ChunkTask prefix contention: primary stalled <32KB, hedge saved 40KB, primary writes 2KB more then fails
        val task = BtrChunkEngine.ChunkTask(0L, 0L, 128 * 1024L - 1L)
        assertTrue(task.tryClaimPrefixProducer(1L))
        task.appendLivePrefix(1L, ByteArray(10 * 1024) { 1 }, 10 * 1024, 128 * 1024)
        val fallback40k = ByteArray(40 * 1024) { 7 }
        task.saveFallbackPrefixIfBetter(fallback40k, fallback40k.size)
        task.appendLivePrefix(1L, ByteArray(2 * 1024) { 1 }, 2 * 1024, 128 * 1024)
        task.releasePrefixProducer(1L, keepPartial = true)
        val snap = task.takePrefixSnapshot(32 * 1024, 128 * 1024)
        assertNotNull("Fallback 40KB prefix must NOT be hidden when primary releases <32KB buffer", snap)
        assertEquals(40 * 1024, snap!!.size)

        // 4. Mock servers for uncached 416 + malformed 200 + sequential IOException / clean-truncation failover
        val totalSeqPayload = ByteArray(150 * 1024) { (it % 241).toByte() }
        val scenarioMode = AtomicInteger(0) // 0=416, 1=malformed200, 2=seqPrimaryDropPartial, 3=seqPrimaryCleanTruncate
        val mockCdn1 = ServerSocket(0)
        val mockCdn2 = ServerSocket(0)

        fun serveCdn(ss: ServerSocket, isPrimary: Boolean): Thread = Thread {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        var rangeHeader = ""
                        reader.readLine() ?: return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line.substringAfter(':').trim()
                            }
                        }
                        val (start, end) = if (rangeHeader.startsWith("bytes=")) {
                            val parts = rangeHeader.removePrefix("bytes=").split("-")
                            (parts.getOrNull(0)?.toLongOrNull() ?: 0L).toInt() to (parts.getOrNull(1)?.toLongOrNull()?.toInt() ?: (totalSeqPayload.size - 1))
                        } else {
                            0 to (totalSeqPayload.size - 1)
                        }
                        val out = s.getOutputStream()
                        when (scenarioMode.get()) {
                            0 -> {
                                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */10240\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                out.flush()
                            }
                            1 -> {
                                val fakeBody = ByteArray(1024) { 9 }
                                out.write("HTTP/1.1 200 OK\r\nContent-Range: bytes 0-1023/1024\r\nContent-Length: ${fakeBody.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(fakeBody)
                                out.flush()
                            }
                            2 -> {
                                val cappedEnd = minOf(end, totalSeqPayload.size - 1)
                                val len = (cappedEnd - start + 1).coerceAtLeast(0)
                                val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$cappedEnd/*\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
                                out.write(hdr.toByteArray())
                                if (isPrimary && start > 0) {
                                    // Write partial 16KB then drop TCP abruptly (IOException) to trigger failover to mockCdn2
                                    val partial = minOf(len, 16 * 1024)
                                    out.write(totalSeqPayload, start, partial)
                                    out.flush()
                                    runCatching { s.setSoLinger(true, 0) }
                                } else {
                                    out.write(totalSeqPayload, start, len)
                                    out.flush()
                                }
                            }
                            else -> {
                                // Clean premature EOF (declared Content-Length larger than bytes sent, or 0 bytes) without RST
                                val cappedEnd = minOf(end, totalSeqPayload.size - 1)
                                val len = (cappedEnd - start + 1).coerceAtLeast(0)
                                if (isPrimary && start > 0) {
                                    val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$cappedEnd/*\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
                                    out.write(hdr.toByteArray())
                                    out.write(totalSeqPayload, start, minOf(len, 8192))
                                    out.flush()
                                } else {
                                    val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$cappedEnd/*\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
                                    out.write(hdr.toByteArray())
                                    out.write(totalSeqPayload, start, len)
                                    out.flush()
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        val t1 = serveCdn(mockCdn1, true)
        val t2 = serveCdn(mockCdn2, false)

        try {
            BtrLocalProxyServer.start()
            val port = BtrLocalProxyServer.port

            // (a) Uncached 416 propagates 416 instead of 502 without banning host
            scenarioMode.set(0)
            val url416 = "http://127.0.0.1:${mockCdn1.localPort}/test/oob.m4s"
            val sid416 = BtrSessionManager.register(url416)
            val conn416 = java.net.URL("http://127.0.0.1:$port/stream/$sid416/oob.m4s").openConnection() as java.net.HttpURLConnection
            conn416.setRequestProperty("Range", "bytes=50000-51000")
            assertEquals("Uncached upstream 416 must return 416 not 502", 416, conn416.responseCode)
            assertFalse("416 must not ban CDN host", BtrSessionManager.get(sid416)!!.banList.isBanned(url416, "127.0.0.1"))
            BtrSessionManager.remove(sid416)

            // (b) Malformed 200 with Content-Range is rejected with 502
            scenarioMode.set(1)
            val urlMalformed = "http://127.0.0.1:${mockCdn1.localPort}/test/bad200.m4s"
            val sidMalformed = BtrSessionManager.register(urlMalformed)
            val connMalformed = java.net.URL("http://127.0.0.1:$port/stream/$sidMalformed/bad200.m4s").openConnection() as java.net.HttpURLConnection
            connMalformed.setRequestProperty("Range", "bytes=0-1023")
            assertEquals("Malformed HTTP 200 with Content-Range must be rejected", 502, connMalformed.responseCode)
            BtrSessionManager.remove(sidMalformed)

            // (d) pipeRemainingSequential mid-transfer upstream IOException fails over to backup CDN2
            scenarioMode.set(2)
            val urlSeq1 = "http://127.0.0.1:${mockCdn1.localPort}/test/seq.m4s"
            val urlSeq2 = "http://127.0.0.1:${mockCdn2.localPort}/test/seq.m4s"
            val sidSeq = BtrSessionManager.register(urlSeq1, listOf(urlSeq2))
            val connSeq = java.net.URL("http://127.0.0.1:$port/stream/$sidSeq/seq.m4s").openConnection() as java.net.HttpURLConnection
            assertEquals(200, connSeq.responseCode)
            val receivedSeq = connSeq.inputStream.use { it.readBytes() }
            assertEquals("Sequential failover must deliver exact total bytes", totalSeqPayload.size, receivedSeq.size)
            assertTrue("Sequential failover bytes must match payload identically", totalSeqPayload.contentEquals(receivedSeq))
            BtrSessionManager.remove(sidSeq)

            // (d2) pipeRemainingSequential clean premature truncation (below Content-Length) must fail over, not stop as EOF
            scenarioMode.set(3)
            val sidSeqTrunc = BtrSessionManager.register(urlSeq1, listOf(urlSeq2))
            val connSeqTrunc = java.net.URL("http://127.0.0.1:$port/stream/$sidSeqTrunc/seq.m4s").openConnection() as java.net.HttpURLConnection
            assertEquals(200, connSeqTrunc.responseCode)
            val receivedSeqTrunc = connSeqTrunc.inputStream.use { it.readBytes() }
            assertEquals("Clean premature truncation must fail over to CDN2", totalSeqPayload.size, receivedSeqTrunc.size)
            assertTrue(totalSeqPayload.contentEquals(receivedSeqTrunc))
            BtrSessionManager.remove(sidSeqTrunc)
        } finally {
            runCatching { mockCdn1.close() }
            runCatching { mockCdn2.close() }
            t1.interrupt()
            t2.interrupt()
        }
    }

    @Test
    fun testLiveUnwrapGenericHostsRedirectBaseUrlIframeKeyAndDynamicHostProbe() {
        // 1. Generic unwrapProxyUrl (hostname, IPv4, IPv6, rejects version paths / invalid ports)
        assertEquals(
            "https://cdn.example.org:8443/live-bvc/room.flv?token=1",
            BtrLiveCore.unwrapProxyUrl("https://proxy.smtcdns.net/cdn.example.org:8443/live-bvc/room.flv?token=1")
        )
        assertEquals(
            "http://1.2.3.4:8080/live-bvc/room.m4s",
            BtrLiveCore.unwrapProxyUrl("http://proxy.smtcdns.com/1.2.3.4:8080/live-bvc/room.m4s")
        )
        assertEquals(
            "https://[2408:871a:10::1]:443/live-bvc/room.m4s",
            BtrLiveCore.unwrapProxyUrl("https://proxy.smtcdns.net/[2408:871a:10::1]:443/live-bvc/room.m4s")
        )
        assertEquals("", BtrLiveCore.unwrapProxyUrl("https://proxy.smtcdns.net/v1.0/live/room.m4s"))
        assertEquals("", BtrLiveCore.unwrapProxyUrl("https://proxy.smtcdns.net/api.v2/live/room.m4s"))
        assertEquals("", BtrLiveCore.unwrapProxyUrl("https://proxy.smtcdns.net/999.0.0.1/live/room.m4s"))
        assertEquals("", BtrLiveCore.unwrapProxyUrl("https://proxy.smtcdns.net/cdn.example.org:99999/live.m4s"))

        // 2. #EXT-X-I-FRAME-STREAM-INF in master playlist & #EXT-X-KEY in media playlist
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="aud",URI="audio/a.m3u8"
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=80000,URI="iframe/i.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1200000
            video/v.m3u8
        """.trimIndent()
        val rewrittenMaster = BtrLiveEngine.rewriteMasterPlaylistContent(
            master,
            "https://live.example.com/root/master.m3u8",
            "sidM",
            45678
        )
        assertTrue(rewrittenMaster.contains("http://127.0.0.1:45678/stream/sidM/playlist.m3u8?url=https%3A%2F%2Flive.example.com%2Froot%2Fiframe%2Fi.m3u8"))

        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="keys/k.key"
            #EXTINF:1.0,
            101.m4s
        """.trimIndent()
        val rewrittenMedia = BtrLiveEngine.rewritePlaylistContent(
            mediaPlaylist,
            "https://live.example.com/sub/dir/live.m3u8",
            "sidM",
            45678
        )
        assertTrue(rewrittenMedia.contains("""URI="https://live.example.com/sub/dir/keys/k.key""""))

        // 3. pipePlaylist uses post-redirect resp.request.url + probeCandidates probes dynamically added hosts
        BtrLiveEngine.clearCache()
        val mockLive = ServerSocket(0)
        val liveThread = Thread {
            while (!mockLive.isClosed) {
                val sock = try { mockLive.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val reqLine = reader.readLine() ?: return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = s.getOutputStream()
                        when {
                            reqLine.contains("/entry.m3u8") -> {
                                out.write("HTTP/1.1 302 Found\r\nLocation: /cdn/edge/sub/live.m3u8\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                            reqLine.contains("/cdn/edge/sub/live.m3u8") -> {
                                val m3u8 = "#EXTM3U\n#EXT-X-MAP:URI=\"h.mp4\"\n#EXTINF:1.0,\nseg100.m4s\n"
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${m3u8.length}\r\nConnection: close\r\n\r\n$m3u8".toByteArray())
                            }
                            else -> {
                                val seg = ByteArray(1024) { 4 }
                                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${seg.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(seg)
                            }
                        }
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var liveSid = ""
        try {
            val entryUrl = "http://127.0.0.1:${mockLive.localPort}/entry.m3u8"
            liveSid = BtrSessionManager.register(entryUrl, isLive = true)
            val liveSession = BtrSessionManager.get(liveSid)!!
            liveSession.liveHostPool = BtrLiveHostPool().apply { add("127.0.0.1", proven = false) }
            val outBuf = ByteArrayOutputStream()
            assertTrue(BtrLiveEngine.pipePlaylist(liveSession, "/stream/$liveSid/playlist.m3u8", emptyMap(), outBuf, 45678, keepAlive = true))
            val responseText = outBuf.toString("UTF-8")
            assertTrue("Response must be 200 OK", responseText.startsWith("HTTP/1.1 200 OK"))
            assertTrue(
                "Relative segments must resolve against post-redirect URL /cdn/edge/sub/",
                responseText.contains("http%3A%2F%2F127.0.0.1%3A${mockLive.localPort}%2Fcdn%2Fedge%2Fsub%2Fseg100.m4s")
            )

            // Add new unproven host dynamically later via updateBackups and verify probeCandidates probes it
            val pool = liveSession.liveHostPool!!
            BtrSessionManager.updateBackups(liveSid, listOf("https://nonexistent.invalid/cdn/edge/sub/seg100.m4s"))
            BtrLiveEngine.probeCandidates(liveSession, "http://127.0.0.1:${mockLive.localPort}/cdn/edge/sub/seg100.m4s")
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                val st = pool.status().firstOrNull { it.host == "nonexistent.invalid" }?.state
                if (st != null && st != "untested") break
                Thread.sleep(20L)
            }
            val lateHostState = pool.status().firstOrNull { it.host == "nonexistent.invalid" }?.state
            assertTrue("Dynamically added backup host must be probed instead of skipped (state=$lateHostState)", lateHostState != "untested")
        } finally {
            if (liveSid.isNotBlank()) BtrSessionManager.remove(liveSid)
            BtrLiveEngine.clearCache()
            runCatching { mockLive.close() }
            liveThread.interrupt()
        }
    }

    @Test
    fun testSessionManagerPromotesLateUposDonorForUpgcxcodeAndPreservesUposOnRefresh() {
        val mcdnUpgcxcode = "https://proxy-tf-all-ws.bilivideo.com/?url=https%3A%2F%2Fxy.mcdn.bilivideo.cn%2Fupgcxcode%2F99%2F11%2F555.m4s%3Fdeadline%3D1890000000"
        val cleanUpos = "https://upos-sz-estgoss.bilivideo.com/upgcxcode/99/11/555.m4s?deadline=1890000000"
        val sid = BtrSessionManager.register(
            mcdnUpgcxcode,
            config = AcceleratorConfig(enabled = true, enableBtr = true, targetHost = "upos-sz-estgoss.bilivideo.com")
        )
        try {
            // /upgcxcode/ relay + late official UPOS donor -> upgrades primaryUrl & unlocks mirrors
            BtrSessionManager.updateBackups(sid, listOf(cleanUpos))
            val upgraded = BtrSessionManager.get(sid)!!
            assertEquals(cleanUpos, upgraded.primaryUrl)
            assertTrue("Synthesized mirrors must unlock after UPOS donor promotion", upgraded.backupUrls.isNotEmpty())

            // Refresh with PCDN base_url only -> must not downgrade UPOS primary back to PCDN
            val pcdnFresh = "https://xy123.szbdyd.com:8080/upgcxcode/99/11/555.m4s?deadline=1960000000&auth=freshsig"
            BtrSessionManager.refreshSignatureIfSamePath(pcdnFresh, emptyList())
            val afterRefresh = BtrSessionManager.get(sid)!!
            assertFalse("Signature refresh must not revert UPOS primary to PCDN", afterRefresh.primaryUrl.contains("szbdyd.com"))
            assertEquals(1960000000L, afterRefresh.urlDeadlineEpochSec)
            assertTrue("Fresh query params must be preserved after refresh", afterRefresh.primaryUrl.contains("auth=freshsig"))
        } finally {
            BtrSessionManager.remove(sid)
        }
    }

    @Test
    fun testConcurrencySeekSprintAndUnmeasuredRotation() {
        var now = 3_000_000L
        val controller = BtrChunkEngine.DynamicConcurrencyController(
            initialConcurrency = 6,
            autoScale = true,
            minConcurrency = 2,
            maxConcurrency = 16,
            enablePersistence = false,
            initialNowMs = now
        )
        // Idle step down
        now += 25_000L
        controller.recordDemand(active = 0, limit = 6, queued = 0, nowMs = now)
        assertTrue("Expected idle step-down below 6, was ${controller.currentConcurrency}", controller.currentConcurrency < 6)

        // Seek sprint restores initial startup concurrency
        now += 1_000L
        controller.onSeekSprint(6, nowMs = now)
        assertEquals(6, controller.currentConcurrency)

        // Pushback hard cap still clamps Seek sprint
        now += 1_000L
        controller.onPushback(nowMs = now, isHard = true)
        val cappedLevel = controller.currentConcurrency
        now += 1_000L
        controller.onSeekSprint(12, nowMs = now)
        assertEquals("Hard cap must constrain Seek sprint", cappedLevel, controller.currentConcurrency)

        // sortUrlsByHealthAndSpeed rotates unmeasured nodes across taskIndex
        val fastUrl = "https://upos-sz-rot-fast.bilivideo.com/upgcxcode/91/92/rot_test.m4s"
        val unmeasuredA = "https://upos-sz-rot-a.bilivideo.com/upgcxcode/91/92/rot_test.m4s"
        val unmeasuredB = "https://upos-sz-rot-b.bilivideo.com/upgcxcode/91/92/rot_test.m4s"
        BtrChunkEngine.recordRouteSpeed(fastUrl, 1_000_000L, 100L)
        val order0 = BtrChunkEngine.sortUrlsByHealthAndSpeed(listOf(fastUrl, unmeasuredA, unmeasuredB), taskIndex = 0L, allowUnmeasuredTrial = true)
        val order1 = BtrChunkEngine.sortUrlsByHealthAndSpeed(listOf(fastUrl, unmeasuredA, unmeasuredB), taskIndex = 1L, allowUnmeasuredTrial = true)
        assertFalse("Trial primary should rotate across unmeasured nodes", order0.first() == order1.first())
    }

    @Test
    fun testCumulativeMultiAttemptResumeAndWarmEnd() {
        val totalLen = 64 * 1024 + 256 * 1024 // 320 KB = 64KB probe + 1x256KB chunk
        val fullPayload = ByteArray(totalLen) { idx -> (idx % 251).toByte() }
        val observedRanges = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0)
        val serverThread = Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        var reqRange = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                reqRange = line.substringAfter(":").trim()
                            }
                        }
                        if (reqRange.isNotBlank()) observedRanges.add(reqRange)
                        val match = Regex("""bytes=(\d+)-(\d+)?""").find(reqRange)
                        val start = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val end = match?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceAtMost(totalLen - 1) ?: (totalLen - 1)
                        val expectedLen = (end - start + 1).coerceAtLeast(0)
                        val out = s.getOutputStream()
                        out.write(
                            ("HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: video/mp4\r\n" +
                                "Content-Range: bytes $start-$end/$totalLen\r\n" +
                                "Content-Length: $expectedLen\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        val toSend = when {
                            start == 0 -> expectedLen // 64KB probe completes cleanly
                            start < 65536 + 81920 -> minOf(expectedLen, 40 * 1024) // Attempt 1 (65536) & Attempt 2 (106496) truncate at 40KB
                            else -> expectedLen // Attempt 3 (147456) completes!
                        }
                        out.write(fullPayload, start, toSend)
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var sid = ""
        try {
            val url = "http://127.0.0.1:${server.localPort}/upgcxcode/99/33/resume.m4s"
            sid = BtrSessionManager.register(url)
            val session = BtrSessionManager.get(sid)!!
            val outBuf = ByteArrayOutputStream()
            val ok = BtrChunkEngine.pipeStream(
                session = session,
                requestedStart = 0L,
                requestedEnd = null,
                clientOut = outBuf,
                concurrency = 2,
                chunkSizeKb = 256,
                autoConcurrency = false,
                autoChunkSize = false,
                keepAlive = false
            )
            assertFalse("non-keepAlive stream returns false on EOF close", ok)
            val rawResponse = outBuf.toByteArray()
            val headerEndIdx = run {
                var found = -1
                for (i in 0 until rawResponse.size - 3) {
                    if (rawResponse[i] == '\r'.code.toByte() &&
                        rawResponse[i + 1] == '\n'.code.toByte() &&
                        rawResponse[i + 2] == '\r'.code.toByte() &&
                        rawResponse[i + 3] == '\n'.code.toByte()
                    ) {
                        found = i + 4
                        break
                    }
                }
                found
            }
            assertTrue("Valid HTTP headers must be emitted", headerEndIdx > 0)
            val receivedBody = rawResponse.copyOfRange(headerEndIdx, rawResponse.size)
            assertEquals("Exact total bytes must be assembled across resumed attempts", totalLen, receivedBody.size)
            assertTrue("Assembled payload bytes must match source exactly", fullPayload.contentEquals(receivedBody))
            assertTrue(
                "Attempt 3 must resume cumulatively from 65536 + 81920 = 147456 (ranges=$observedRanges)",
                observedRanges.any { it.startsWith("bytes=147456-") }
            )
            assertEquals("warmEnd must advance to totalLen on completion", totalLen.toLong(), session.warmEnd.get())
        } finally {
            if (sid.isNotBlank()) BtrSessionManager.remove(sid)
            runCatching { server.close() }
            serverThread.interrupt()
        }
    }

    @Test
    fun testLiveIsP2pPoolCooldownFailoverAndProxyHttpMethods() {
        // Bare IP / P2P host detection without scheme
        assertTrue(BtrLiveCore.isP2pUrl("112.34.56.78"))
        assertTrue(BtrLiveCore.isP2pUrl("112.34.56.78:8080"))
        assertTrue(BtrLiveCore.isP2pUrl("xy123.mcdn.bilivideo.cn"))
        assertFalse(BtrLiveCore.isP2pUrl("cn-gd-fx-01-01.bilivideo.com"))

        // Pool unproven() filters out hosts still in blockedUntil cooldown
        val pool = BtrLiveHostPool()
        pool.add("a.bilivideo.com", proven = false)
        pool.add("b.bilivideo.com", proven = false)
        pool.failure("a.bilivideo.com", 0L)
        val unproven = pool.unproven()
        assertFalse("Cooldown host must not be returned by unproven()", unproven.contains("a.bilivideo.com"))
        assertTrue(unproven.contains("b.bilivideo.com"))

        // Proxy server: 405 Method Not Allowed on POST & HEAD returns headers without body for live playlist
        BtrLiveEngine.clearCache()
        val liveServer = ServerSocket(0)
        val playlistText = "#EXTM3U\n#EXTINF:1.0,\nseg1.m4s\n"
        val srvThread = Thread {
            while (!liveServer.isClosed) {
                val sock = try { liveServer.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = s.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                "Content-Length: ${playlistText.length}\r\n" +
                                "Connection: close\r\n\r\n$playlistText").toByteArray()
                        )
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var liveSid = ""
        try {
            BtrLocalProxyServer.start()
            val liveUrl = "http://127.0.0.1:${liveServer.localPort}/live/room.m3u8"
            liveSid = BtrSessionManager.register(liveUrl, isLive = true)

            // 1. POST -> 405
            val postConn = (java.net.URL("http://127.0.0.1:${BtrLocalProxyServer.port}/stream/$liveSid.m3u8").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
            }
            assertEquals(405, postConn.responseCode)
            postConn.disconnect()

            // 2. HEAD on live playlist -> 200 with Content-Length > 0 and empty body
            val headConn = (java.net.URL("http://127.0.0.1:${BtrLocalProxyServer.port}/stream/$liveSid.m3u8").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
            }
            assertEquals(200, headConn.responseCode)
            assertTrue("HEAD playlist must include Content-Length > 0", headConn.contentLengthLong > 0L)
            val headBody = headConn.inputStream.readBytes()
            assertEquals("HEAD response body must be empty", 0, headBody.size)
            headConn.disconnect()
        } finally {
            if (liveSid.isNotBlank()) BtrSessionManager.remove(liveSid)
            BtrLiveEngine.clearCache()
            runCatching { liveServer.close() }
            srvThread.interrupt()
        }
    }

    @Test
    fun testTrialRollbackBaselineAndNotPoisonedByHistoricalPeak() {
        var now = 4_000_000L
        val controller = BtrChunkEngine.DynamicConcurrencyController(
            initialConcurrency = 6,
            autoScale = true,
            minConcurrency = 2,
            maxConcurrency = 16,
            initialNowMs = now
        )
        // High historical peak: 20 MB/s at concurrency 6 -> saturate to step up 6 -> 8
        controller.recordDemand(active = 6, limit = 6, queued = 4, nowMs = now)
        now += 1_500L
        controller.recordProgress(30L * 1024 * 1024, nowMs = now) // 20 MB/s -> steps up to 8 (trialBaseline = 20MB/s)
        assertEquals(8, controller.currentConcurrency)

        // Before 5s trial window completes, must NOT roll back prematurely even with lower sample
        now += 2_000L
        controller.recordProgress(2L * 1024 * 1024, nowMs = now)
        assertEquals("Trial must not roll back before 5000ms trial window", 8, controller.currentConcurrency)

        // After >= 5000ms total trial window with lower throughput than trialBaseline (under saturation) -> rollback to 6
        now += 3_500L
        controller.recordDemand(active = 8, limit = 8, queued = 4, nowMs = now)
        controller.recordProgress(2L * 1024 * 1024, nowMs = now) // (4MB / 5.5s) < 20 MB/s
        assertEquals("No-gain saturated trial must roll back from 8 to 6", 6, controller.currentConcurrency)

        //Wait past 90s soft rest and 6s cooldown; network is now slower (2 MB/s) than historical peak (20 MB/s)
        now += 95_000L
        controller.recordDemand(active = 6, limit = 6, queued = 4, nowMs = now)
        controller.recordProgress(512 * 1024L, nowMs = now)
        now += 1_500L
        controller.recordProgress(3L * 1024 * 1024, nowMs = now) // ~2 MB/s -> steps up 6 -> 8 (trialBaseline = 2 MB/s)
        assertEquals(8, controller.currentConcurrency)

        // At 8 threads, throughput is 3 MB/s (>= 2 MB/s baseline, even though < 20 MB/s historical peak) -> must COMMIT 8!
        now += 5_500L
        controller.recordProgress(16L * 1024 * 1024, nowMs = now)
        assertEquals("Trial improving over pre-step baseline must commit despite lower than historical bestBps", 8, controller.currentConcurrency)
    }

    @Test
    fun testOpenEndedRangeWithUnknownTotalLengthAndKeepAliveParamUpdate() {
        val totalPayload = ByteArray(140_000) { (it % 239).toByte() }
        val server = ServerSocket(0)
        val serverThread = Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        var rangeHeader = ""
                        reader.readLine() ?: return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line.substringAfter(':').trim()
                            }
                        }
                        val parts = rangeHeader.removePrefix("bytes=").split("-")
                        val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val reqEnd = parts.getOrNull(1)?.toIntOrNull() ?: (totalPayload.size - 1)
                        val actualEnd = minOf(reqEnd, totalPayload.size - 1)
                        val len = (actualEnd - start + 1).coerceAtLeast(0)
                        val out = s.getOutputStream()
                        out.write(
                            ("HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: video/mp4\r\n" +
                                "Content-Range: bytes $start-$actualEnd/*\r\n" +
                                "Content-Length: $len\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        out.write(totalPayload, start, len)
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var sid = ""
        try {
            BtrLocalProxyServer.start(concurrency = 2, chunkSizeKb = 256, autoConcurrency = false, autoChunkSize = false)
            val port = BtrLocalProxyServer.port
            val url = "http://127.0.0.1:${server.localPort}/upgcxcode/12/34/unknown_open.m4s"
            sid = BtrSessionManager.register(url)

            // 1. Range: bytes=0- with upstream Content-Range: .../* -> must respond 200 OK (not headerless 206) and deliver full payload
            val conn0 = (java.net.URL("http://127.0.0.1:$port/stream/$sid/v.m4s").openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=0-")
            }
            assertEquals("Open-ended bytes=0- with unknown total must emit 200 OK, not headerless 206", 200, conn0.responseCode)
            val body0 = conn0.inputStream.use { it.readBytes() }
            assertEquals(totalPayload.size, body0.size)
            assertTrue(totalPayload.contentEquals(body0))

            // 2. Range: bytes=1000- with unknown total -> must emit 502 instead of headerless 206
            val connSeek = (java.net.URL("http://127.0.0.1:$port/stream/$sid/v.m4s").openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=1000-")
            }
            assertEquals("Open-ended non-zero range with unknown total must not emit headerless 206", 502, connSeek.responseCode)
            connSeek.disconnect()

            // 3. Keep-Alive socket observes live updateParameters() between sequential requests
            val boundedPayload = ByteArray(100_000) { (it % 199).toByte() }
            val boundedSrv = ServerSocket(0)
            val bThread = Thread {
                while (!boundedSrv.isClosed) {
                    val sock = try { boundedSrv.accept() } catch (_: Throwable) { break }
                    Thread {
                        sock.use { s ->
                            val r = s.getInputStream().bufferedReader()
                            var rh = ""
                            r.readLine() ?: return@use
                            while (true) {
                                val l = r.readLine() ?: break
                                if (l.isEmpty()) break
                                if (l.startsWith("Range:", ignoreCase = true)) rh = l.substringAfter(':').trim()
                            }
                            val p = rh.removePrefix("bytes=").split("-")
                            val st = p.getOrNull(0)?.toIntOrNull() ?: 0
                            val en = minOf(p.getOrNull(1)?.toIntOrNull() ?: (boundedPayload.size - 1), boundedPayload.size - 1)
                            val ln = (en - st + 1).coerceAtLeast(0)
                            val o = s.getOutputStream()
                            o.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $st-$en/${boundedPayload.size}\r\nContent-Length: $ln\r\nConnection: close\r\n\r\n").toByteArray())
                            o.write(boundedPayload, st, ln)
                            o.flush()
                        }
                    }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
            val bSid = BtrSessionManager.register("http://127.0.0.1:${boundedSrv.localPort}/upgcxcode/12/34/ka.m4s")
            try {
                java.net.Socket("127.0.0.1", port).use { sock ->
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()
                    fun readHttpResponse(): Pair<Int, ByteArray> {
                        val headerBuf = ByteArrayOutputStream()
                        var prev3 = 0; var prev2 = 0; var prev1 = 0
                        while (true) {
                            val b = inp.read()
                            if (b == -1) break
                            headerBuf.write(b)
                            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
                            prev3 = prev2; prev2 = prev1; prev1 = b
                        }
                        val hdrText = headerBuf.toString("US-ASCII")
                        val code = hdrText.lineSequence().first().split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                        val cl = hdrText.lineSequence()
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                        val body = ByteArray(cl)
                        var off = 0
                        while (off < cl) {
                            val n = inp.read(body, off, cl - off)
                            if (n <= 0) break
                            off += n
                        }
                        return code to body
                    }
                    out.write("GET /stream/$bSid/ka.m4s HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=0-999\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                    out.flush()
                    val (c1, b1) = readHttpResponse()
                    assertEquals(206, c1)
                    assertEquals(1000, b1.size)

                    BtrLocalProxyServer.updateParameters(concurrency = 4, chunkSizeKb = 128, autoConcurrency = false, autoChunkSize = false)
                    out.write("GET /stream/$bSid/ka.m4s HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nRange: bytes=1000-4999\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    val (c2, b2) = readHttpResponse()
                    assertEquals(206, c2)
                    assertEquals(4000, b2.size)
                    assertTrue(boundedPayload.copyOfRange(1000, 5000).contentEquals(b2))
                }
            } finally {
                BtrSessionManager.remove(bSid)
                runCatching { bThread.interrupt() }
                runCatching { boundedSrv.close() }
            }
        } finally {
            if (sid.isNotBlank()) BtrSessionManager.remove(sid)
            runCatching { server.close() }
            serverThread.interrupt()
        }
    }

    @Test
    fun testFlvHeadMethodBackupLineQueryPreservationAndDonorDeduplication() {
        // 1. Donor selection skips hdnts= and prefers isolated UPOS backups, and backupUrls deduplicates by host
        val hdntsAkamai = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/11/22/hd.m4s?hdnts=exp=1900000000~hmac=abc&deadline=1900000000"
        val cleanBackup = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/11/22/hd.m4s?deadline=1900000000"
        val dupHostBackup = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/11/22/hd.m4s?deadline=1900000000&extra=1"
        val sid = BtrSessionManager.register(
            primaryUrl = hdntsAkamai,
            backupUrls = listOf(cleanBackup, dupHostBackup),
            config = AcceleratorConfig(enabled = true, enableBtr = true, btrCdnPoolSize = 8)
        )
        try {
            val session = BtrSessionManager.get(sid)!!
            assertTrue("Synthesized mirrors must use clean UPOS donor (no hdnts=)", session.synthesizedMirrors.isNotEmpty())
            assertTrue("Synthesized mirrors must not inherit hdnts=", session.synthesizedMirrors.none { it.contains("hdnts=") })
            val hostCount = session.backupUrls.groupingBy { java.net.URI(it).host.lowercase() }.eachCount()
            assertTrue("backupUrls must deduplicate by host", hostCount.values.all { it == 1 })
        } finally {
            BtrSessionManager.remove(sid)
        }

        // 2. FLV HEAD returns headers with 0-byte body + liveHostUrls preserves per-line queries + eviction clears cache
        val observedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val flvServer = ServerSocket(0)
        val flvThread = Thread {
            while (!flvServer.isClosed) {
                val sock = try { flvServer.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val r = s.getInputStream().bufferedReader()
                        val reqLine = r.readLine() ?: return@use
                        while (true) {
                            val l = r.readLine() ?: break
                            if (l.isEmpty()) break
                        }
                        val path = reqLine.split(" ").getOrNull(1).orEmpty()
                        observedPaths.add(path)
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/x-flv\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write("FLVSTREAMBODY".toByteArray())
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var flvSid = ""
        try {
            BtrLocalProxyServer.start()
            val flvUrl1 = "http://127.0.0.1:${flvServer.localPort}/live-bvc/room.flv?line=primary_tok"
            val flvUrl2 = "http://localhost:${flvServer.localPort}/live-bvc/room.flv?line=backup_tok"
            flvSid = BtrSessionManager.register(flvUrl1, listOf(flvUrl2), isLive = true)
            val flvSession = BtrSessionManager.get(flvSid)!!
            assertEquals(flvUrl1, flvSession.liveHostUrls["127.0.0.1"])
            assertEquals(flvUrl2, flvSession.liveHostUrls["localhost"])

            val headConn = (java.net.URL("http://127.0.0.1:${BtrLocalProxyServer.port}/stream/$flvSid.flv").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"
            }
            assertEquals(200, headConn.responseCode)
            val body = headConn.inputStream.readBytes()
            assertEquals("HEAD /stream/{id}.flv must return empty body", 0, body.size)
            headConn.disconnect()
            assertTrue("FLV request must hit original primary query", observedPaths.any { it.contains("line=primary_tok") })

            // Per-session cache eviction check
            BtrLiveEngine.putCacheForTest("https://example.com/seg1.m4s", CachedLiveSegment(byteArrayOf(1), "video/mp4", "h"), sessionId = flvSid)
            assertNotNull(BtrLiveEngine.getCachedForTest("https://example.com/seg1.m4s", sessionId = flvSid))
            BtrSessionManager.remove(flvSid)
            flvSid = ""
            assertEquals(null, BtrLiveEngine.getCachedForTest("https://example.com/seg1.m4s", sessionId = flvSession.sessionId))
        } finally {
            if (flvSid.isNotBlank()) BtrSessionManager.remove(flvSid)
            BtrLiveEngine.clearCache()
            runCatching { flvServer.close() }
            flvThread.interrupt()
        }
    }

    @Test
    fun testProbeRecoversFromPrimary403UsingFallbackBatchAndKeepsRelayFallback() {
        val payload = ByteArray(96 * 1024) { (it and 0x7F).toByte() }

        // Mock primary server returning 403, and backup server at index >= 3 (fallback batch) returning 206
        val bad403Server = ServerSocket(0)
        val goodFallbackServer = ServerSocket(0)
        val tBad = Thread {
            while (!bad403Server.isClosed) {
                val sock = try { bad403Server.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val r = s.getInputStream().bufferedReader()
                        while (true) {
                            val l = r.readLine() ?: break
                            if (l.isEmpty()) break
                        }
                        s.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        s.getOutputStream().flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        val tGood = Thread {
            while (!goodFallbackServer.isClosed) {
                val sock = try { goodFallbackServer.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val r = s.getInputStream().bufferedReader()
                        var rangeHeader: String? = null
                        while (true) {
                            val l = r.readLine() ?: break
                            if (l.isEmpty()) break
                            if (l.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = l.substringAfter(":").trim()
                            }
                        }
                        val (start, end) = BtrLocalProxyServer.parseRangeHeaderPair(rangeHeader)
                        val actualEnd = minOf(end ?: (payload.size - 1L), payload.size - 1L)
                        val slice = payload.copyOfRange(start.toInt(), actualEnd.toInt() + 1)
                        val out = s.getOutputStream()
                        out.write(
                            ("HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: video/mp4\r\n" +
                                "Content-Range: bytes $start-$actualEnd/${payload.size}\r\n" +
                                "Content-Length: ${slice.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        out.write(slice)
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var sid = ""
        try {
            BtrLocalProxyServer.start()
            val primary403 = "http://127.0.0.1:${bad403Server.localPort}/upgcxcode/77/88/probe403.m4s?tag=p"
            val relayBackup = "https://proxy-tf-all-ws.bilivideo.com/?url=https%3A%2F%2Fxy.mcdn.bilivideo.cn%2Fupgcxcode%2F77%2F88%2Fprobe403.m4s"
            val nonRelayBad1 = "http://127.0.0.1:${bad403Server.localPort}/upgcxcode/77/88/probe403.m4s?tag=b1"
            val nonRelayBad2 = "http://127.0.0.1:${bad403Server.localPort}/upgcxcode/77/88/probe403.m4s?tag=b2"
            val goodFallback = "http://localhost:${goodFallbackServer.localPort}/upgcxcode/77/88/probe403.m4s?tag=good"

            // Verify non-relay session retains relay fallback at tail
            val relayCheckSid = BtrSessionManager.register(
                primaryUrl = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/77/88/probe403.m4s",
                backupUrls = listOf(relayBackup),
                config = null
            )
            val relayCheckSession = BtrSessionManager.get(relayCheckSid)!!
            assertTrue("Non-relay session must keep relay backup at tail as 403 fallback", relayCheckSession.backupUrls.contains(relayBackup))
            BtrSessionManager.remove(relayCheckSid)

            // Verify probe recovers across fallback batch when primary + first 2 backups return 403
            sid = BtrSessionManager.register(
                primaryUrl = primary403,
                backupUrls = listOf(nonRelayBad1, nonRelayBad2, goodFallback),
                config = null
            )
            // Inject nonRelayBad1/2 explicitly (deduplicateUrlsByHost keeps unique hosts; use distinct port/host keys via direct session list)
            val session = BtrSessionManager.get(sid)!!
            synchronized(session.urlLock) {
                session.backupUrls = listOf(nonRelayBad1, nonRelayBad2, goodFallback)
            }

            val conn = java.net.URL("http://127.0.0.1:${BtrLocalProxyServer.port}/stream/$sid.m4s").openConnection() as java.net.HttpURLConnection
            assertEquals(200, conn.responseCode)
            val received = conn.inputStream.readBytes()
            conn.disconnect()
            assertTrue("Fallback batch must recover from 403 and deliver exact payload", payload.contentEquals(received))
        } finally {
            if (sid.isNotBlank()) BtrSessionManager.remove(sid)
            runCatching { bad403Server.close() }
            runCatching { goodFallbackServer.close() }
            tBad.interrupt()
            tGood.interrupt()
        }
    }

    @Test
    fun testLiveSwapHostPreservesEncodedAuthParamsAndDomesticPoolOnly() {
        assertFalse(
            "Live default FMP4 pool must not contain overseas ov-gotcha hosts",
            BtrLiveCore.KNOWN_FMP4_HOSTS.any { it.contains("ov-gotcha", ignoreCase = true) }
        )
        assertTrue(BtrLiveCore.KNOWN_FMP4_HOSTS.contains("d1--cn-gotcha204.bilivideo.com"))
        assertTrue(BtrLiveCore.KNOWN_FMP4_HOSTS.contains("d1--cn-gotcha208.bilivideo.com"))

        val signedPlaylist =
            "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123456/live_123_1500/index.m3u8?expires=1700000000&sign=abc%2Fdef%2Bghi%3D%3D&sk=foo%26bar"
        assertEquals(
            "Same-host swap must return URL verbatim without decoding query",
            signedPlaylist,
            BtrLiveCore.swapHost(signedPlaylist, "d1--cn-gotcha204.bilivideo.com")
        )

        val swappedPlaylist = BtrLiveCore.swapHost(signedPlaylist, "d1--cn-gotcha208.bilivideo.com")
        assertEquals(
            "https://d1--cn-gotcha208.bilivideo.com/live-bvc/123456/live_123_1500/index.m3u8?expires=1700000000&sign=abc%2Fdef%2Bghi%3D%3D&sk=foo%26bar",
            swappedPlaylist
        )

        val signedSegment =
            "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123456/100.m4s?sign=a%2Fb%3Dc&sk=x%2By"
        val guessed = BtrLiveCore.guessNextSegmentUrl(
            LiveSegment(name = "100.m4s", url = signedSegment, num = 100L)
        )
        assertEquals(
            "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123456/101.m4s?sign=a%2Fb%3Dc&sk=x%2By",
            guessed
        )
        assertEquals(
            "https://d1--cn-gotcha208.bilivideo.com/live-bvc/123456/101.m4s?sign=a%2Fb%3Dc&sk=x%2By",
            BtrLiveCore.swapHost(guessed!!, "d1--cn-gotcha208.bilivideo.com")
        )
    }

    @Test
    fun testBtrHttpClientsUseHttp11AndRelaxedTimeouts() {
        val clients = listOf(
            BtrHttpClient.client,
            BtrHttpClient.audioClient,
            BtrHttpClient.chunkClient,
            BtrHttpClient.chunkAudioClient,
            BtrHttpClient.probeClient,
            BtrHttpClient.fallbackClient,
            BtrHttpClient.liveStreamClient
        )
        for (c in clients) {
            assertEquals("OkHttp client must use HTTP/1.1 only", listOf(okhttp3.Protocol.HTTP_1_1), c.protocols)
        }
        assertTrue(BtrHttpClient.client.connectTimeoutMillis >= 8_000)
        assertTrue(BtrHttpClient.client.readTimeoutMillis >= 20_000)
        assertTrue(BtrHttpClient.audioClient.connectTimeoutMillis >= 6_000)
        assertTrue(BtrHttpClient.audioClient.readTimeoutMillis >= 15_000)
        assertTrue(BtrHttpClient.liveStreamClient.readTimeoutMillis >= 30_000)
    }

    @Test
    fun testUpdateBackupsPreservesOriginalAndExistingFallbacks() {
        val rewrittenTarget = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/44401.m4s?deadline=1900000000"
        val origPcdn = "https://p2p.szbdyd.com:8080/upgcxcode/54/12/44401.m4s?deadline=1900000000"
        val incomingOfficial = "https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/54/12/44401.m4s?deadline=1900000000"
        val sid = BtrSessionManager.register(
            primaryUrl = rewrittenTarget,
            backupUrls = listOf(origPcdn),
            config = AcceleratorConfig(enableBtr = true, targetHost = "upos-sz-mirrorcos.bilivideo.com")
        )
        try {
            BtrSessionManager.updateBackups(sid, listOf(incomingOfficial))
            val session = BtrSessionManager.get(sid)!!
            assertTrue("Incoming official backup must be retained", session.backupUrls.contains(incomingOfficial))
            assertTrue("Original PCDN URL must NOT be overwritten by setBackupUrls()", session.backupUrls.contains(origPcdn))
            assertTrue("Official backup must precede PCDN fallback", session.backupUrls.indexOf(incomingOfficial) < session.backupUrls.indexOf(origPcdn))
        } finally {
            BtrSessionManager.remove(sid)
        }

        // Promoted MCDN/PCDN session keeps previous primary as tail fallback & merges earlier backups
        val mcdnPrimary = "https://xy1x2x3x4xy.mcdn.bilivideo.cn/upgcxcode/54/12/44402.m4s?os=mcdn&deadline=1900000000"
        val otherRelay = "https://xy9y8y7y6xy.mcdn.bilivideo.cn/upgcxcode/54/12/44402.m4s?os=mcdn&deadline=1900000000"
        val promotedUpos = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/44402.m4s?deadline=1900000000"
        val relaySid = BtrSessionManager.register(primaryUrl = mcdnPrimary, backupUrls = listOf(otherRelay))
        try {
            BtrSessionManager.updateBackups(relaySid, listOf("https://xy5z5z5z5xy.mcdn.bilivideo.cn/upgcxcode/54/12/44402.m4s?os=mcdn"))
            assertTrue(
                "Relay-only updateBackups must accumulate prior relay fallback",
                BtrSessionManager.get(relaySid)!!.backupUrls.contains(otherRelay)
            )
            BtrSessionManager.updateBackups(relaySid, listOf(promotedUpos))
            val promotedSession = BtrSessionManager.get(relaySid)!!
            assertEquals(promotedUpos, promotedSession.primaryUrl)
            assertTrue("Previous MCDN primary must stay preserved as tail fallback after promotion", promotedSession.backupUrls.contains(mcdnPrimary))
            assertTrue("Existing relay backup must stay preserved after promotion", promotedSession.backupUrls.contains(otherRelay))
        } finally {
            BtrSessionManager.remove(relaySid)
        }
    }

    @Test
    fun testLivePipePlaylistAndSegmentDoNotRetryOrSend502AfterHeadersSent() {
        val m3u8Body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:2.0,\nseg0.ts\n"
        val primaryHits = java.util.concurrent.atomic.AtomicInteger(0)
        val backupHits = java.util.concurrent.atomic.AtomicInteger(0)

        fun startM3u8Server(hits: java.util.concurrent.atomic.AtomicInteger): Pair<java.net.ServerSocket, Thread> {
            val ss = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
            val t = Thread {
                while (!ss.isClosed) {
                    try {
                        val s = ss.accept()
                        hits.incrementAndGet()
                        s.use {
                            val br = java.io.BufferedReader(java.io.InputStreamReader(it.getInputStream()))
                            while (true) {
                                val line = br.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val bodyBytes = m3u8Body.toByteArray(Charsets.UTF_8)
                            val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
                            val out = it.getOutputStream()
                            out.write(resp.toByteArray(Charsets.US_ASCII))
                            out.write(bodyBytes)
                            out.flush()
                        }
                    } catch (_: Throwable) {
                        break
                    }
                }
            }.apply { isDaemon = true; start() }
            return ss to t
        }

        val (primarySs, primaryThread) = startM3u8Server(primaryHits)
        val (backupSs, backupThread) = startM3u8Server(backupHits)
        var sid = ""
        try {
            val primaryUrl = "http://127.0.0.1:${primarySs.localPort}/live-bvc/100/index.m3u8"
            val backupUrl = "http://localhost:${backupSs.localPort}/live-bvc/100/index.m3u8"
            sid = BtrSessionManager.register(
                primaryUrl = primaryUrl,
                backupUrls = listOf(backupUrl),
                isLive = true
            )
            val session = BtrSessionManager.get(sid)!!
            session.liveHostPool!!.success("localhost", 10.0, 0.0)
            session.liveHostUrls["127.0.0.1"] = primaryUrl
            session.liveHostUrls["localhost"] = backupUrl

            val playlistCapture = java.io.ByteArrayOutputStream()
            val failOnSecondWritePlaylist = object : java.io.OutputStream() {
                private var writes = 0
                override fun write(b: Int) {
                    playlistCapture.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    writes++
                    if (writes == 2) {
                        throw java.io.IOException("Simulated client disconnect mid-playlist")
                    }
                    playlistCapture.write(b, off, len)
                }
            }

            val playlistKeepAlive = BtrLiveEngine.pipePlaylist(
                session = session,
                requestPath = "/stream/$sid.m3u8",
                headers = emptyMap(),
                clientOut = failOnSecondWritePlaylist,
                proxyPort = 18080,
                keepAlive = true
            )
            assertFalse("Client disconnect after headers must close connection", playlistKeepAlive)
            assertEquals("Primary host must be hit once", 1, primaryHits.get())
            assertEquals("Must NOT retry backup host after headers were sent", 0, backupHits.get())
            val primaryStatus = session.liveHostPool!!.status().firstOrNull { it.host == "127.0.0.1" }?.state
            assertEquals("Healthy upstream must NOT be penalized for client disconnect", "healthy", primaryStatus)
            val playlistText = playlistCapture.toString("US-ASCII")
            assertTrue("Playlist headers must contain 200 OK", playlistText.startsWith("HTTP/1.1 200 OK"))
            assertFalse("Must NOT append 502 after 200 OK headers", playlistText.contains("502"))

            // Part B: pipeSegment client disconnect after headers sent must NOT append 502
            val segUrl = "http://127.0.0.1:${primarySs.localPort}/live-bvc/100/seg1.m4s"
            BtrLiveEngine.putCacheForTest(
                segUrl,
                CachedLiveSegment(byteArrayOf(1, 2, 3, 4), "video/iso.segment", "127.0.0.1"),
                sid
            )
            val segCapture = java.io.ByteArrayOutputStream()
            val failOnSecondWriteSeg = object : java.io.OutputStream() {
                private var writes = 0
                override fun write(b: Int) {
                    segCapture.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    writes++
                    if (writes == 2) {
                        throw java.io.IOException("Simulated client disconnect mid-segment")
                    }
                    segCapture.write(b, off, len)
                }
            }
            val encodedSeg = java.net.URLEncoder.encode(segUrl, "UTF-8")
            val segKeepAlive = BtrLiveEngine.pipeSegment(
                session = session,
                requestPath = "/stream/$sid/segment.m4s?url=$encodedSeg",
                headers = emptyMap(),
                clientOut = failOnSecondWriteSeg,
                keepAlive = true
            )
            assertFalse("Segment disconnect after headers must close connection", segKeepAlive)
            val segText = segCapture.toString("US-ASCII")
            assertTrue("Segment headers must contain 200 OK", segText.startsWith("HTTP/1.1 200 OK"))
            assertFalse("Segment must NOT append 502 after 200 OK headers", segText.contains("502"))

            // Part C: pipeSegment upstream failure BEFORE headers sent MUST still return standard HTTP 502
            val errOut = java.io.ByteArrayOutputStream()
            val deadPort = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val missingSegUrl = "http://127.0.0.1:$deadPort/live-bvc/100/missing.m4s"
            val deadSid = BtrSessionManager.register(primaryUrl = missingSegUrl, isLive = true)
            try {
                val deadSession = BtrSessionManager.get(deadSid)!!
                val errKeepAlive = BtrLiveEngine.pipeSegment(
                    session = deadSession,
                    requestPath = "/stream/$deadSid/segment.m4s?url=${java.net.URLEncoder.encode(missingSegUrl, "UTF-8")}",
                    headers = emptyMap(),
                    clientOut = errOut,
                    keepAlive = true
                )
                assertFalse(errKeepAlive)
                assertTrue(
                    "Upstream failure before headers must return HTTP/1.1 502",
                    errOut.toString("US-ASCII").startsWith("HTTP/1.1 502")
                )
            } finally {
                BtrSessionManager.remove(deadSid)
            }
        } finally {
            if (sid.isNotBlank()) BtrSessionManager.remove(sid)
            runCatching { primarySs.close() }
            runCatching { backupSs.close() }
            primaryThread.interrupt()
            backupThread.interrupt()
        }
    }

    @Test
    fun testLoopbackDoesNotSynthesizePublicMirrorsAndChunk403FollowedByBackup404StillRefreshes() {
        assertTrue(
            "Loopback 127.0.0.1 must not synthesize public UPOS mirrors",
            BtrSessionManager.synthesizeCompatibleBackups("http://127.0.0.1:8080/upgcxcode/88/77/test.m4s?deadline=1900000000").isEmpty()
        )
        assertTrue(
            "Loopback localhost must not synthesize public UPOS mirrors",
            BtrSessionManager.synthesizeCompatibleBackups("http://localhost:8080/upgcxcode/88/77/test.m4s?deadline=1900000000").isEmpty()
        )

        val fullVideo = ByteArray(140_000) { ((it * 17 + 3) and 0xFF).toByte() }
        val expired = AtomicInteger(0)
        val primaryServer = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val backup404Server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))

        val tPrimary = Thread {
            while (!primaryServer.isClosed) {
                val sock = try { primaryServer.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        val reqLine = reader.readLine() ?: return@use
                        var rangeHdr = ""
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHdr = line.substringAfter(':').trim()
                            }
                        }
                        val out = s.getOutputStream()
                        if (expired.get() == 1 && !reqLine.contains("fresh=ok")) {
                            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            return@use
                        }
                        val parts = rangeHdr.removePrefix("bytes=").split("-")
                        val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val end = minOf(parts.getOrNull(1)?.toIntOrNull() ?: (fullVideo.size - 1), fullVideo.size - 1)
                        val len = (end - start + 1).coerceAtLeast(0)
                        val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$end/${fullVideo.size}\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
                        out.write(hdr.toByteArray())
                        out.write(fullVideo, start, len)
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        val tBackup404 = Thread {
            while (!backup404Server.isClosed) {
                val sock = try { backup404Server.accept() } catch (_: Throwable) { break }
                Thread {
                    sock.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        var sid = ""
        try {
            BtrLocalProxyServer.start()
            val port = BtrLocalProxyServer.port
            val nowSec = System.currentTimeMillis() / 1000L
            val initialPrimary = "http://127.0.0.1:${primaryServer.localPort}/upgcxcode/88/77/mixed403404.m4s?deadline=${nowSec + 600}"
            val backup404 = "http://localhost:${backup404Server.localPort}/upgcxcode/88/77/mixed403404.m4s?deadline=${nowSec + 600}"
            val refreshedPrimary = "http://127.0.0.1:${primaryServer.localPort}/upgcxcode/88/77/mixed403404.m4s?deadline=${nowSec + 3600}&fresh=ok"

            sid = BtrSessionManager.register(initialPrimary, listOf(backup404))
            BtrSessionManager.attachRefreshProvider(sid) {
                BtrSessionManager.refreshSignatureIfSamePath(refreshedPrimary, emptyList())
                true
            }

            // Warm totalLength metadata
            val cInit = java.net.URL("http://127.0.0.1:$port/stream/$sid/v.m4s").openConnection() as java.net.HttpURLConnection
            cInit.setRequestProperty("Range", "bytes=0-1023")
            assertEquals(206, cInit.responseCode)
            assertEquals(1024, cInit.inputStream.use { it.readBytes() }.size)

            // Expire primary (403) while backup returns 404 -> sawForbidden403 must still trigger refresh
            expired.set(1)
            val cSeek = java.net.URL("http://127.0.0.1:$port/stream/$sid/v.m4s").openConnection() as java.net.HttpURLConnection
            cSeek.setRequestProperty("Range", "bytes=70000-110000")
            assertEquals(206, cSeek.responseCode)
            val received = cSeek.inputStream.use { it.readBytes() }
            assertTrue(fullVideo.copyOfRange(70_000, 110_001).contentEquals(received))
        } finally {
            if (sid.isNotBlank()) BtrSessionManager.remove(sid)
            runCatching { primaryServer.close() }
            runCatching { backup404Server.close() }
            tPrimary.interrupt()
            tBackup404.interrupt()
        }
    }
}