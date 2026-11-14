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
            val isOverseasNode = backup.contains("ov.bilivideo.com") || backup.contains("cn-hk-eq-")
            assertTrue("Synthesized backup must be an overseas node (ov or hk): $backup", isOverseasNode)
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

        assertTrue("Overseas stream should synthesize overseas mirrors", backups.isNotEmpty())
        for (backup in backups) {
            val isOverseasNode = backup.contains("ov.bilivideo.com") || backup.contains("cn-hk-eq-")
            assertTrue("Synthesized backup must be an overseas node (ov or hk): $backup", isOverseasNode)
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
        val regionalEpisodeUrl = "https://bstarstatic.com/upgcxcode/12/34/12345.m4s?ep_id=12345"
        val sessionId = BtrSessionManager.register(
            primaryUrl = regionalEpisodeUrl,
            isEpisode = true
        )
        val session = BtrSessionManager.get(sessionId)
        assertNotNull(session)
        assertTrue("Session must be marked as episode", session?.isEpisode == true)
        assertEquals("https://www.bilibili.com/bangumi/play/", session?.referer)

        val backups = session?.backupUrls.orEmpty()
        assertTrue("Regional PGC must synthesize backups", backups.isNotEmpty())
        for (backup in backups) {
            val isOverseasNode = backup.contains("ov.bilivideo.com") || backup.contains("cn-hk-eq-")
            assertTrue("Regional PGC must strictly use overseas/hk mirrors, never mainland UPOS: $backup", isOverseasNode)
        }
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
        assertTrue("Full mainland synthesis should include mirrorcoso1/mirrorbos/mirrorbd",
            fullMainlandBackups.any { it.contains("mirrorcoso1") || it.contains("mirrorbos") || it.contains("mirrorbd") })
        assertTrue("Mainland pool should include > 5 mirrors", fullMainlandBackups.size > 5)

        val overseasPrimary = "https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/54/12/12345.m4s?deadline=1"
        val fullOverseasBackups = BtrSessionManager.synthesizeCompatibleBackups(
            overseasPrimary,
            AcceleratorConfig(btrCdnPoolSize = 8)
        )
        assertTrue("Overseas synthesis must include HK nodes cn-hk-eq-01-01/03",
            fullOverseasBackups.any { it.contains("cn-hk-eq-01-01.bilivideo.com") } &&
                fullOverseasBackups.any { it.contains("cn-hk-eq-01-03.bilivideo.com") })
        assertFalse("Overseas synthesis must exclude mainland nodes",
            fullOverseasBackups.any { it.contains("mirrorcos.bilivideo.com") || it.contains("mirrorali.bilivideo.com") })
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
}