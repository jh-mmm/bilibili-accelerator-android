package io.github.jh_mmm.biliaccelerator.core

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
        assertEquals("live-skip", result.reason)
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
}
