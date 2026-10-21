package io.github.jh_mmm.biliaccelerator.core

import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliAcceleratorCoreTest {

    private val defaultConfig = AcceleratorConfig(
        enabled = true,
        targetHost = "upos-sz-mirrorcos.bilivideo.com",
        proxyHost = "proxy-tf-all-ws.bilivideo.com",
        blockPcdn = true,
        proxyMcdn = true,
        forceUpos = false,
        portHeuristic = true
    )

    @Test
    fun testSzbdydSchedulerSourceRecovery() {
        val original = "http://xy123.szbdyd.com:8080/upgcxcode/54/12/34567.m4s?e=123&deadline=456&xy_usource=upos-sz-mirrorali.bilivideo.com"
        val result = BiliAcceleratorCore.rewriteUrl(original, defaultConfig)

        assertTrue("Should be marked as changed", result.changed)
        assertTrue("Scheme should be upgraded to HTTPS", result.finalUrl.startsWith("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s"))
        assertEquals("szbdyd-source", result.reason)
        assertEquals("upos-sz-mirrorali.bilivideo.com", result.targetHost)
    }

    @Test
    fun testMountaintoysSchedulerSourceRecovery() {
        val original = "http://edge.mountaintoys.cn:8080/upgcxcode/54/12/34567.m4s?e=123&deadline=456&xy_usource=upos-tf-all-hw.bilivideo.com"
        val result = BiliAcceleratorCore.rewriteUrl(original, defaultConfig)

        assertTrue("Should be marked as changed", result.changed)
        assertTrue("Scheme should be HTTPS", result.finalUrl.startsWith("https://upos-tf-all-hw.bilivideo.com/upgcxcode/54/12/34567.m4s"))
        assertEquals("mountaintoys-source", result.reason)
        assertEquals("upos-tf-all-hw.bilivideo.com", result.targetHost)
    }

    @Test
    fun testMcdnProxyRelay() {
        val original = "https://xy1x2x3x4xy.mcdn.bilivideo.cn/upgcxcode/54/12/34567.m4s?os=mcdn"
        val result = BiliAcceleratorCore.rewriteUrl(original, defaultConfig)

        assertTrue("MCDN should be changed", result.changed)
        assertTrue("Should route through proxy-tf-all-ws", result.finalUrl.startsWith("https://proxy-tf-all-ws.bilivideo.com/?url="))
        assertEquals("mcdn-proxy", result.reason)
        assertTrue("isMcdn should be true", result.isMcdn)
    }

    @Test
    fun testPcdnIpIntercept() {
        val original = "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s?sign=abc"
        val result = BiliAcceleratorCore.rewriteUrl(original, defaultConfig)

        assertTrue("IP PCDN should be changed", result.changed)
        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?sign=abc", result.finalUrl)
        assertEquals("pcdn-host", result.reason)
        assertTrue("isPcdn should be true", result.isPcdn)
    }

    @Test
    fun testKnownP2pHosts() {
        val p2p1 = "http://upos-sz-mirror14b.bilivideo.com/upgcxcode/54/12/34567.m4s"
        val res1 = BiliAcceleratorCore.rewriteUrl(p2p1, defaultConfig)
        assertTrue("mirror14b is p2p", res1.changed)
        assertEquals("pcdn-host", res1.reason)

        val p2p2 = "http://upos-sz-302ppio.bilivideo.com/upgcxcode/54/12/34567.m4s"
        val res2 = BiliAcceleratorCore.rewriteUrl(p2p2, defaultConfig)
        assertTrue("302ppio is p2p", res2.changed)
        assertEquals("pcdn-host", res2.reason)

        val p2p3 = "http://node.nexusedgeio.com/upgcxcode/54/12/34567.m4s"
        val res3 = BiliAcceleratorCore.rewriteUrl(p2p3, defaultConfig)
        assertTrue("nexusedgeio is p2p", res3.changed)
        assertEquals("pcdn-host", res3.reason)
    }

    @Test
    fun testForceUposSameHostReturnsNotChanged() {
        // 当视频流本来就已经是目标镜像时，即使 forceUpos=true，也必须返回 changed=false，避免统计虚高和日志死循环
        val url = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?deadline=123"
        val forceConfig = defaultConfig.copy(forceUpos = true, targetHost = "upos-sz-mirrorcos.bilivideo.com")
        val result = BiliAcceleratorCore.rewriteUrl(url, forceConfig)

        assertFalse("Same host rewrite MUST NOT be marked as changed", result.changed)
        assertEquals(url, result.finalUrl)
        assertEquals("ok", result.reason)
    }

    @Test
    fun testLiveMediaIgnored() {
        val liveUrl = "https://d1--cn-gotcha01.bilivideo.com/live-bvc/12345/live_123.flv?sign=abc"
        val result = BiliAcceleratorCore.rewriteUrl(liveUrl, defaultConfig)

        assertFalse("Live media should not be rewritten", result.changed)
        assertEquals("live-ok", result.reason)
    }

    @Test
    fun testDisabledModuleIgnored() {
        val url = "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s"
        val result = BiliAcceleratorCore.rewriteUrl(url, defaultConfig.copy(enabled = false))

        assertFalse("Disabled module should ignore all URLs", result.changed)
        assertEquals("ignored", result.reason)
    }

    @Test
    fun testPortHeuristicToggle() {
        val urlWithPort = "http://upos-sz-mirrorali.bilivideo.com:8080/upgcxcode/test.m4s"

        // 当开启端口启发式时，非标端口视作 PCDN 节点被拦截重写
        val resWithHeuristic = BiliAcceleratorCore.rewriteUrl(urlWithPort, defaultConfig.copy(portHeuristic = true))
        assertTrue(resWithHeuristic.changed)
        assertEquals("pcdn-host", resWithHeuristic.reason)

        // 当关闭端口启发式时，合法的 bilivideo CDN 即使有端口也不视作 PCDN
        val resWithoutHeuristic = BiliAcceleratorCore.rewriteUrl(urlWithPort, defaultConfig.copy(portHeuristic = false))
        assertFalse(resWithoutHeuristic.changed)
        assertEquals("ok", resWithoutHeuristic.reason)
    }

    @Test
    fun testHasMediaSignal() {
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://example.com/video.m4s"))
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://example.com/audio.mp4?param=1"))
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://test.bilivideo.com/data"))
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://unknown.com/upgcxcode/123"))
        assertFalse(BiliAcceleratorCore.hasMediaSignal("http://api.bilibili.com/x/v2/reply"))
    }

    @Test
    fun testBstarOverseasNotRewrittenInForceMode() {
        val bstarUrl = "https://video-sea.bstarstatic.com/upgcxcode/54/12/34567.m4s?sign=abc"
        val forceConfig = defaultConfig.copy(forceUpos = true)
        val result = BiliAcceleratorCore.rewriteUrl(bstarUrl, forceConfig)
        assertFalse("Bstar overseas stream should not be rewritten to mainland UPOS in force mode", result.changed)
        assertEquals("ok", result.reason)
    }

    @Test
    fun testThirdPartyVideoWithPortNotRewritten() {
        // 第三方广告或外部视频即使走非标端口，只要不属于 B 站域名族且无 B 站路径签名，绝不误拦截
        val externalAd = "http://ad-server.example.com:8080/commercial/intro.mp4"
        val result = BiliAcceleratorCore.rewriteUrl(externalAd, defaultConfig)
        assertFalse("External third-party media should not be rewritten", result.changed)
        assertFalse("Should not be recognized as PCDN", result.isPcdn)
        assertEquals("ok", result.reason)
    }

    @Test
    fun testIpv6Detection() {
        assertTrue("IPv4 detection", BiliAcceleratorCore.isIpAddress("192.168.1.1"))
        assertTrue("IPv6 bracketed detection", BiliAcceleratorCore.isIpAddress("[2001:db8::1]"))
        assertTrue("IPv6 raw detection", BiliAcceleratorCore.isIpAddress("2001:db8::1"))
        assertFalse("Standard domain detection", BiliAcceleratorCore.isIpAddress("upos-sz-mirrorcos.bilivideo.com"))
    }

    @Test
    fun testAdditionalLiveMediaPatterns() {
        val livePatterns = listOf(
            "https://d1--cn-gotcha01.bilivideo.com/live-bvc/123/live.flv",
            "https://live.bilibili.com/live-stream/456/index.m3u8",
            "https://d1--cn-gotcha02.bilivideo.com/live-flv/789/stream.flv",
            "https://live-play.bilivideo.com/live/101/stream.m4s"
        )
        for (url in livePatterns) {
            val result = BiliAcceleratorCore.rewriteUrl(url, defaultConfig)
            assertFalse("Live URL must be skipped: $url", result.changed)
            assertEquals("live-ok", result.reason)
        }
    }

    @Test
    fun testIsTargetPackageGeneralization() {
        assertTrue(BiliAcceleratorCore.isTargetPackage("tv.danmaku.bili"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("com.bilibili.app.in"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("com.bstar.intl"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("com.bilibili.app.blue"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("tv.danmaku.bilibilihd"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("tv.danmaku.bilipr"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("com.bilibili.studio"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("com.bilibili.comic"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("tv.danmaku.bili.pad"))
        assertTrue(BiliAcceleratorCore.isTargetPackage("com.bstar.intl.play"))
        assertFalse(BiliAcceleratorCore.isTargetPackage("com.example.other"))
        assertFalse("Substring .bilibili. spoofing must be rejected", BiliAcceleratorCore.isTargetPackage("com.evil.bilibili.spoof"))
        assertFalse("Substring biliaccelerator spoofing must be rejected", BiliAcceleratorCore.isTargetPackage("com.fake.bilibiliaccelerator"))
        assertFalse("Module package itself is not a target host package", BiliAcceleratorCore.isTargetPackage("io.github.jh_mmm.biliaccelerator"))
    }

    @Test
    fun testSanitizeForIpcStripsSensitiveUrls() {
        val original = "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s?mid=99999&upsig=secret"
        val rewritten = BiliAcceleratorCore.rewriteUrl(original, defaultConfig)
        val sanitized = RemoteClient.sanitizeForIpc(rewritten)

        assertEquals("", sanitized.originalUrl)
        assertEquals("", sanitized.finalUrl)
        assertEquals(rewritten.originalHost, sanitized.originalHost)
        assertEquals(rewritten.targetHost, sanitized.targetHost)
        assertEquals(rewritten.reason, sanitized.reason)
        assertEquals(rewritten.isPcdn, sanitized.isPcdn)
    }

    @Test
    fun testCleanHostProtocolRelative() {
        assertEquals("upos-sz-mirrorali.bilivideo.com", BiliAcceleratorCore.cleanHost("//upos-sz-mirrorali.bilivideo.com/live/1.m4s"))
        assertEquals("upos-sz-mirrorali.bilivideo.com", BiliAcceleratorCore.cleanHost("https://upos-sz-mirrorali.bilivideo.com/live/1.m4s"))
        assertEquals("upos-sz-mirrorali.bilivideo.com", BiliAcceleratorCore.cleanHost("http://upos-sz-mirrorali.bilivideo.com/live/1.m4s"))
    }

    @Test
    fun testLiveMediaPcdnDetectionAndRecovery() {
        // 直播 PCDN 携带源站参数时恢复真源站
        val liveWithSource = "http://xy123.szbdyd.com:8080/live-bvc/123/live.flv?xy_usource=d1--cn-gotcha01.bilivideo.com"
        val resSource = BiliAcceleratorCore.rewriteUrl(liveWithSource, defaultConfig)
        assertTrue("Live stream with source parameter should recover source", resSource.changed)
        assertTrue(resSource.finalUrl.startsWith("https://d1--cn-gotcha01.bilivideo.com/live-bvc/123/live.flv"))
        assertEquals("live-szbdyd-source", resSource.reason)

        // 直播纯 PCDN 标记为 live-pcdn-detected
        val livePcdn = "http://112.34.56.78:8080/live-bvc/123/live.flv?sign=abc"
        val resPcdn = BiliAcceleratorCore.rewriteUrl(livePcdn, defaultConfig)
        assertFalse("Live PCDN without source should not be arbitrarily rewritten to VOD host", resPcdn.changed)
        assertEquals("live-pcdn-detected", resPcdn.reason)
        assertTrue("isPcdn flag should be true", resPcdn.isPcdn)
    }

    @Test
    fun testLiveFilterAndFillBackupUrls() {
        val liveUrl = "https://d1--cn-gotcha01.bilivideo.com/live-bvc/123/live.flv"
        val candidateBackups = listOf(
            "https://d1--cn-gotcha02.bilivideo.com/live-bvc/123/live.flv",
            "http://xy123.szbdyd.com:8080/live-bvc/123/live.flv",
            "https://xy1.mcdn.bilivideo.cn/live-bvc/123/live.flv"
        )
        val filtered = BiliAcceleratorCore.filterAndFillBackupUrls(liveUrl, candidateBackups, defaultConfig)
        assertEquals(1, filtered.size)
        assertEquals("https://d1--cn-gotcha02.bilivideo.com/live-bvc/123/live.flv", filtered[0])
    }

    @Test
    fun testMcdnStrategyVariants() {
        val mcdnUrl = "https://xy1x2x3x4xy.mcdn.bilivideo.cn/upgcxcode/54/12/34567.m4s?os=mcdn"

        // 1. proxy-all 策略
        val resProxyAll = BiliAcceleratorCore.rewriteUrl(mcdnUrl, defaultConfig.copy(mcdnStrategy = "proxy-all"))
        assertTrue(resProxyAll.changed)
        assertTrue(resProxyAll.finalUrl.startsWith("https://proxy-tf-all-ws.bilivideo.com/?url="))
        assertEquals("mcdn-proxy", resProxyAll.reason)

        // 2. proxy-v1 策略
        val resProxyV1 = BiliAcceleratorCore.rewriteUrl(mcdnUrl, defaultConfig.copy(mcdnStrategy = "proxy-v1"))
        assertTrue(resProxyV1.changed)
        assertTrue(resProxyV1.finalUrl.contains("mcdn.bilivideo.cn:8000") || resProxyV1.finalUrl.startsWith("https://proxy-tf-all-ws.bilivideo.com/?url="))
        assertEquals("mcdn-proxy", resProxyV1.reason)

        // 3. replace 策略（直接替换为目标 UPOS 镜像）
        val resReplace = BiliAcceleratorCore.rewriteUrl(mcdnUrl, defaultConfig.copy(mcdnStrategy = "replace", targetHost = "upos-sz-mirrorcos.bilivideo.com"))
        assertTrue(resReplace.changed)
        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?os=mcdn", resReplace.finalUrl)
        assertEquals("mcdn-replace", resReplace.reason)

        // 4. proxyMcdn = false 关闭 MCDN 处理
        val resDisabled = BiliAcceleratorCore.rewriteUrl(mcdnUrl, defaultConfig.copy(proxyMcdn = false))
        assertFalse(resDisabled.changed)
        assertEquals("ok", resDisabled.reason)
    }

    @Test
    fun testAkamaiHandling() {
        val akamaiUrl = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/54/12/34567.m4s?token=xyz"

        // rewriteAkamai = false 默认放行
        val resPass = BiliAcceleratorCore.rewriteUrl(akamaiUrl, defaultConfig.copy(rewriteAkamai = false))
        assertFalse("Akamai should pass through by default", resPass.changed)
        assertEquals("akamai-pass", resPass.reason)

        // rewriteAkamai = true 允许改写为 targetHost
        val resRewrite = BiliAcceleratorCore.rewriteUrl(akamaiUrl, defaultConfig.copy(rewriteAkamai = true, targetHost = "upos-sz-mirrorcos.bilivideo.com"))
        assertTrue("Akamai should be rewritten when enabled", resRewrite.changed)
        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?token=xyz", resRewrite.finalUrl)
        assertEquals("force-upos", resRewrite.reason)
    }

    @Test
    fun testAudioTrackDetection() {
        assertTrue(BiliAcceleratorCore.isAudioTrack("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567-30280.m4s"))
        assertTrue(BiliAcceleratorCore.isAudioTrack("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567-30216.m4s"))
        assertTrue(BiliAcceleratorCore.isAudioTrack("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s?rep_id=30280&mid=123"))
        assertFalse(BiliAcceleratorCore.isAudioTrack("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567-30080.m4s"))
    }

    @Test
    fun testResolveTargetHostAkamaiSafety() {
        // 如果用户配置的是 Akamai 域名或非法域名，resolveTargetHost 必须回退到安全的官方镜像
        val host = BiliAcceleratorCore.resolveTargetHost("upos-hz-mirrorakam.akamaized.net")
        assertFalse("Resolved target host must never be Akamai", BiliAcceleratorCore.isAkamaiHost(host))
        assertEquals("upos-sz-mirrorcos.bilivideo.com", host)
    }

    @Test
    fun testEffectiveCdnPoolBehavior() {
        val defaultConfig = AcceleratorConfig()
        val defaultPool = BiliAcceleratorCore.getEffectiveCdnPool(defaultConfig)
        assertEquals(8, defaultPool.size)
        assertTrue(defaultPool.contains("upos-sz-mirrorcos.bilivideo.com"))

        // 用户自定义白名单过滤非法与 Akamai 节点，上限 32
        val custom = listOf(
            "upos-sz-mirrorali.bilivideo.com",
            "upos-tf-all-hw.bilivideo.com",
            "invalid domain name",
            "upos-hz-mirrorakam.akamaized.net"
        )
        val customConfig = AcceleratorConfig(customCdnPool = custom)
        val customEffective = BiliAcceleratorCore.getEffectiveCdnPool(customConfig)
        assertEquals(2, customEffective.size)
        assertTrue(customEffective.contains("upos-sz-mirrorali.bilivideo.com"))
        assertTrue(customEffective.contains("upos-tf-all-hw.bilivideo.com"))
        assertFalse(customEffective.contains("upos-hz-mirrorakam.akamaized.net"))
    }
}
