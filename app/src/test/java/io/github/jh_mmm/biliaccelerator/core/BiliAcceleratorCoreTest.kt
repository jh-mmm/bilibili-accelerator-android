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
    fun testHasMediaSignalAndIsMediaUrlGate() {
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://example.com/video.m4s"))
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://example.com/audio.mp4?param=1"))
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://test.bilivideo.com/data"))
        assertTrue(BiliAcceleratorCore.hasMediaSignal("http://unknown.com/upgcxcode/123"))
        assertFalse(BiliAcceleratorCore.hasMediaSignal("http://api.bilibili.com/x/v2/reply"))
        assertFalse("Loose 'bstar' must not trigger media signal", BiliAcceleratorCore.hasMediaSignal("https://www.bilibili.com/bstar/topic"))
        assertFalse("Extension in query param must not match media path", BiliAcceleratorCore.hasMediaSignal("https://api.bilibili.com/login?redirect=x.mp4"))

        // Force 模式下，非媒体路径的 bilivideo URL 必须被 isMediaUrl 门槛拦截，绝不误改写
        val nonMediaBilivideo = "https://api.bilivideo.com/v2/report?redirect=video.mp4"
        val forceRes = BiliAcceleratorCore.rewriteUrl(nonMediaBilivideo, defaultConfig.copy(forceUpos = true))
        assertFalse("Non-media bilivideo URL must not be rewritten in force mode", forceRes.changed)
        assertEquals("ignored", forceRes.reason)
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
    fun testThirdPartyVideoAndAkamaiWithPortNotRewritten() {
        // 第三方广告或外部视频（包括非 B 站旗下的第三方 akamaized.net 节点）即使走非标端口，也绝不误判为 PCDN
        val externalAd = "http://ad-server.example.com:8080/commercial/intro.mp4"
        val result = BiliAcceleratorCore.rewriteUrl(externalAd, defaultConfig)
        assertFalse("External third-party media should not be rewritten", result.changed)
        assertFalse("Should not be recognized as PCDN", result.isPcdn)
        assertEquals("ok", result.reason)

        val thirdPartyAkamai = "https://thirdparty-media.akamaized.net:8443/stream/clip.mp4"
        val akamaiRes = BiliAcceleratorCore.rewriteUrl(thirdPartyAkamai, defaultConfig)
        assertFalse("Third-party Akamai on custom port should not be misclassified as Bili PCDN", akamaiRes.changed)
        assertFalse(akamaiRes.isPcdn)

        val biliAkamai = "https://upos-hz-mirrorakam.akamaized.net:8443/upgcxcode/12/34/56.m4s"
        val biliAkamaiRes = BiliAcceleratorCore.rewriteUrl(biliAkamai, defaultConfig)
        assertTrue("Bili Akamai host with non-default port should be recognized", biliAkamaiRes.changed)
    }

    @Test
    fun testIpv6DetectionStrictness() {
        assertTrue("IPv4 detection", BiliAcceleratorCore.isIpAddress("192.168.1.1"))
        assertTrue("IPv6 bracketed detection", BiliAcceleratorCore.isIpAddress("[2001:db8::1]"))
        assertTrue("IPv6 raw detection", BiliAcceleratorCore.isIpAddress("2001:db8::1"))
        assertFalse("Standard domain detection", BiliAcceleratorCore.isIpAddress("upos-sz-mirrorcos.bilivideo.com"))
        assertFalse("Single colon hex-like string 'a:b' must not match as IPv6", BiliAcceleratorCore.isIpAddress("a:b"))
        assertFalse("Single colon 'dead:beef' must not match as IPv6", BiliAcceleratorCore.isIpAddress("dead:beef"))
        assertFalse("Too many colons must not match as IPv6", BiliAcceleratorCore.isIpAddress("1:2:3:4:5:6:7:8:9"))
    }

    @Test
    fun testLiveMediaDetectionAndVodWithLiveInPath() {
        // /live-bvc/ 直播流必须跳过单 URL 域名替换
        val liveBvc = "https://d1--cn-gotcha01.bilivideo.com/live-bvc/123/live.flv"
        val liveRes = BiliAcceleratorCore.rewriteUrl(liveBvc, defaultConfig)
        assertFalse("Live /live-bvc/ URL must be skipped", liveRes.changed)
        assertEquals("live-skip", liveRes.reason)

        // 点播路径中若恰好包含 /live/（非 /live-bvc/），绝不能被误跳过
        val vodWithLiveDir = "http://112.34.56.78:8080/upgcxcode/live/101/stream.m4s"
        val vodRes = BiliAcceleratorCore.rewriteUrl(vodWithLiveDir, defaultConfig)
        assertTrue("VOD URL containing /live/ must still be accelerated", vodRes.changed)
        assertEquals("pcdn-host", vodRes.reason)
    }

    @Test
    fun testFilterLiveUrlInfoGetRoomPlayInfo() {
        val getRoomPlayInfoJson = """
            {
              "code": 0,
              "data": {
                "playurl_info": {
                  "playurl": {
                    "stream": [
                      {
                        "format": [
                          {
                            "codec": [
                              {
                                "base_url": "/live-bvc/123456/live_999.flv?",
                                "url_info": [
                                  { "host": "https://xy112x34x56x78xy.mcdn.bilivideo.cn:486", "extra": "?os=mcdn&sign=1" },
                                  { "host": "https://d1--cn-gotcha04.bilivideo.com", "extra": "?os=gotcha&sign=2" }
                                ]
                              }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val filtered = BiliAcceleratorCore.filterLiveUrlInfo(getRoomPlayInfoJson, defaultConfig)
        assertTrue("getRoomPlayInfo url_info should filter out slow MCDN/PCDN host", filtered.changed)
        assertFalse("Filtered JSON must not contain mcdn host", filtered.json.contains("mcdn.bilivideo.cn"))
        assertTrue("Filtered JSON must retain official live CDN host", filtered.json.contains("d1--cn-gotcha04.bilivideo.com"))
        assertEquals(1, filtered.rewrites.size)
        assertEquals("live-pcdn-filter", filtered.rewrites[0].reason)

        // 当所有 url_info 都是慢速节点时，绝不删空最后一个可用主机
        val allSlowJson = """
            {
              "url_info": [
                { "host": "https://xy1x1x1x1xy.mcdn.bilivideo.cn:486", "extra": "?os=mcdn" },
                { "host": "http://112.34.56.78:8080", "extra": "?sign=1" }
              ]
            }
        """.trimIndent()
        val untouched = BiliAcceleratorCore.filterLiveUrlInfo(allSlowJson, defaultConfig)
        assertFalse("Must never remove the last usable host if all look slow", untouched.changed)
    }

    @Test
    fun testRouteEngineSessionAndDecisions() {
        RouteEngine.clearSessions()
        val playurlJson = """
            {
              "data": {
                "dash": {
                  "video": [
                    {
                      "id": 80,
                      "bandwidth": 4000000,
                      "codecs": "avc1.640028",
                      "baseUrl": "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/11/22/42231991605-1-30080.m4s?e=ig8euxZM2rNcNbdlhoNvNC8BqJIzNbfq9rVEuxTEnE8L5F6VnEsSTx0vkX8fqJeYTj_lta53NCM=&oi=123",
                      "backupUrl": [
                        "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/11/22/42231991605-1-30080.m4s?hdnts=exp=999~hmac=abc"
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val session = RouteEngine.ingestPlayurlJson(playurlJson, defaultConfig)
        assertTrue("Session should be created from DASH playurl JSON", session != null)
        assertEquals("42231991605", session!!.cid)

        val rep = session.table.reps["42231991605-1-30080.m4s"]!!
        assertEquals(2, rep.issued.size)
        // Akamai has its own issued URL with hdnts token
        val akamUrl = RouteEngine.urlFor(rep, "upos-hz-mirrorakam.akamaized.net")
        assertTrue(akamUrl!!.contains("hdnts="))
        // Other UPOS mirror borrows signature from issued UPOS URL
        val aliUrl = RouteEngine.urlFor(rep, "upos-sz-mirrorali.bilivideo.com")
        assertTrue(aliUrl!!.startsWith("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/11/22/42231991605-1-30080.m4s?e="))

        // Session active routing
        session.active = "upos-sz-mirrorali.bilivideo.com"
        val routed = RouteEngine.engineRoute(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/11/22/42231991605-1-30080.m4s?e=1",
            defaultConfig
        )
        assertTrue(routed.startsWith("https://upos-sz-mirrorali.bilivideo.com/"))

        // Race verdict & EWMA estimator
        val est = RouteEngine.createEstimator()
        assertTrue(est.sample(durationMs = 1000L, numBytes = 200_000L))
        assertTrue((est.estimate() ?: 0.0) > 1_000_000.0)

        val verdict = RouteEngine.raceVerdict(
            results = listOf(
                RouteEngine.RaceSample(host = "upos-sz-mirrorali.bilivideo.com", ok = true, ms = 400L),
                RouteEngine.RaceSample(host = "upos-sz-mirrorhw.bilivideo.com", ok = true, ms = 700L)
            ),
            currentRateBps = 2_000_000.0
        )
        assertEquals("upos-sz-mirrorali.bilivideo.com", verdict.winner)
        assertEquals("upos-sz-mirrorali.bilivideo.com", verdict.switchTo)
        RouteEngine.clearSessions()
    }
}

