package io.github.jh_mmm.biliaccelerator.hook

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import io.github.jh_mmm.biliaccelerator.external.PackagePrivateNetworkFakes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

class MossGrpcHookTest {

    private class FakePlayViewReply(
        private val baseUrl: String,
        private val backups: List<String> = emptyList(),
        private val isDrm: Boolean = false
    ) {
        fun getBaseUrl(): String = baseUrl
        fun getBackupUrlList(): List<String> = backups
        fun getIsDrm(): Boolean = isDrm
    }

    private class FakeOkHttpRequest(
        private val methodName: String,
        private val requestUrl: String
    ) {
        fun method(): String = methodName
        fun url(): String = requestUrl
    }

    private class FakeResponseBody(private val text: String) {
        fun string(): String = text
    }

    private class FakeOkHttpResponse(
        private val ok: Boolean,
        private val bodyText: String
    ) {
        var closed: Boolean = false
        fun isSuccessful(): Boolean = ok
        fun body(): FakeResponseBody = FakeResponseBody(bodyText)
        fun close() {
            closed = true
        }
    }

    private class FakeOkHttpCall(private val response: FakeOkHttpResponse) {
        fun execute(): FakeOkHttpResponse = response
    }

    private class FakeOkHttpClient(private val refreshedJson: String) {
        var callCount: Int = 0
        var lastResponse: FakeOkHttpResponse? = null
        fun newCall(request: Any): FakeOkHttpCall {
            callCount++
            val resp = FakeOkHttpResponse(ok = true, bodyText = refreshedJson)
            lastResponse = resp
            return FakeOkHttpCall(resp)
        }
    }

    @After
    fun tearDown() {
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true), 0L)
        PlayinfoRegistry.resetForTesting()
        MossGrpcHook.resetPlayinfoCaptureForTesting()
    }

    @Test
    fun testShouldInterceptRespectsEnableMossHookToggle() {
        val disabledByDefault = AcceleratorConfig(enabled = true, blockPcdn = true, enableMossHook = false)
        assertFalse(
            "MossGrpcHook must NOT intercept when enableMossHook is false",
            MossGrpcHook.shouldIntercept(disabledByDefault, Any())
        )

        val enabledMoss = AcceleratorConfig(enabled = true, blockPcdn = true, enableMossHook = true)
        assertTrue(
            "MossGrpcHook should intercept when enabled, blockPcdn, and enableMossHook are all true",
            MossGrpcHook.shouldIntercept(enabledMoss, Any())
        )
        assertFalse(
            "MossGrpcHook must NOT intercept when result is null",
            MossGrpcHook.shouldIntercept(enabledMoss, null)
        )
        assertFalse(
            "MossGrpcHook must NOT intercept when blockPcdn is false",
            MossGrpcHook.shouldIntercept(enabledMoss.copy(blockPcdn = false), Any())
        )
        assertFalse(
            "MossGrpcHook must NOT intercept when module is globally disabled",
            MossGrpcHook.shouldIntercept(enabledMoss.copy(enabled = false), Any())
        )
    }

    @Test
    fun testConvertNumberToTfOneFloat() {
        val result = MossGrpcHook.convertNumberToTfOne(java.lang.Float.TYPE, 0.0f)
        assertTrue("Float primitive type must produce Float instance", result is Float)
        assertEquals(1.0f, result)
    }

    @Test
    fun testConvertNumberToTfOneDouble() {
        val result = MossGrpcHook.convertNumberToTfOne(java.lang.Double.TYPE, 0.0)
        assertTrue("Double primitive type must produce Double instance", result is Double)
        assertEquals(1.0, result)
    }

    @Test
    fun testConvertNumberToTfOneLong() {
        val result = MossGrpcHook.convertNumberToTfOne(java.lang.Long.TYPE, 0L)
        assertTrue("Long primitive type must produce Long instance", result is Long)
        assertEquals(1L, result)
    }

    @Test
    fun testConvertNumberToTfOneInt() {
        val result = MossGrpcHook.convertNumberToTfOne(java.lang.Integer.TYPE, 0)
        assertTrue("Int primitive type must produce Int instance", result is Int)
        assertEquals(1, result)
    }

    @Test
    fun testConvertNumberToTfOneShort() {
        val result = MossGrpcHook.convertNumberToTfOne(java.lang.Short.TYPE, 0.toShort())
        assertTrue("Short primitive type must produce Short instance", result is Short)
        assertEquals(1.toShort(), result)
    }

    @Test
    fun testConvertNumberToTfOneByte() {
        val result = MossGrpcHook.convertNumberToTfOne(java.lang.Byte.TYPE, 0.toByte())
        assertTrue("Byte primitive type must produce Byte instance", result is Byte)
        assertEquals(1.toByte(), result)
    }

    @Test
    fun testConvertNumberToTfOneBigNumbers() {
        val decResult = MossGrpcHook.convertNumberToTfOne(BigDecimal::class.java, BigDecimal.ZERO)
        assertEquals(BigDecimal.ONE, decResult)

        val intResult = MossGrpcHook.convertNumberToTfOne(BigInteger::class.java, BigInteger.ZERO)
        assertEquals(BigInteger.ONE, intResult)
    }

    @Test
    fun testPlayinfoSignalAndRelevantProcessFiltering() {
        assertTrue(
            MossGrpcHook.fastLooksLikePlayinfoJson(
                """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/3.m4s","is_drm":false}]}}}"""
            )
        )
        assertTrue(
            MossGrpcHook.fastLooksLikePlayinfoJson(
                """{"code":0,"vod_info":{"stream_list":[{"dash_video":{"base_url":"https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/3.m4s"}}]}}"""
            )
        )
        assertFalse(MossGrpcHook.fastLooksLikePlayinfoJson("#EXTM3U\n#EXT-X-TARGETDURATION:2\nsegment1.m4s"))
        assertFalse(MossGrpcHook.fastLooksLikePlayinfoJson("""{"code":0,"data":{"user":"ok"}}"""))

        assertTrue(MossGrpcHook.isPlayurlApiUrl("https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1xx"))
        assertTrue(MossGrpcHook.isPlayurlApiUrl("https://api.bilibili.com/pgc/player/web/playurl?ep_id=123"))
        assertFalse(MossGrpcHook.isPlayurlApiUrl("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/3.m4s"))

        assertTrue(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili"))
        assertTrue(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili:ijkservice"))
        assertTrue(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili:live"))
        assertFalse(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili:sandboxed_process0"))
        assertFalse(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili:webview_service"))
        assertFalse(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili:isolated_process0"))
        assertFalse(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili:report"))
        assertFalse(HookEntry.isRelevantProcess("tv.danmaku.bili", "tv.danmaku.bili:monitor"))
    }

    @Test
    fun testPlayinfoHooksSkipInspectionAndCaptureWhenModuleDisabled() {
        val grpcUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/90001.m4s?deadline=1899999999"
        val jsonUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/90002.m4s?deadline=1899999999"
        val fakeReply = FakePlayViewReply(
            baseUrl = grpcUrl,
            backups = listOf("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/90001.m4s?deadline=1899999999")
        )
        val playinfoJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$jsonUrl","backupUrl":["https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/90002.m4s"],"is_drm":false}]}}}"""
        val fakeRequest = FakeOkHttpRequest("GET", "https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1disabled")

        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = false, enableMossHook = true))

        assertFalse(
            "PlayViewReply inspection must skip when module is disabled",
            MossGrpcHook.inspectPlayViewReplyIfEnabled(fakeReply, sourceTag = "grpc-blocking")
        )
        assertNull("PlayinfoRegistry must not ingest PlayViewReply when disabled", PlayinfoRegistry.lookup(grpcUrl))

        assertFalse(
            "OkHttp playurl call capture must skip when module is disabled",
            MossGrpcHook.captureHttpPlayurlCallIfEnabled(Any(), fakeRequest)
        )
        assertFalse(
            "lastHttpPlayurlCall must remain empty when disabled",
            MossGrpcHook.hasCapturedHttpPlayurlCallForTesting()
        )

        assertFalse(
            "ResponseBody playinfo JSON inspection must skip when module is disabled",
            MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(playinfoJson, allowRefreshCallback = true)
        )
        assertFalse(
            "JSONObject playinfo JSON inspection must skip when module is disabled",
            MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(playinfoJson, allowRefreshCallback = false)
        )
        assertNull("PlayinfoRegistry must not ingest JSON when disabled", PlayinfoRegistry.lookup(jsonUrl))
    }

    @Test
    fun testPlayinfoHooksInspectAndCaptureWhenModuleEnabledEvenIfMossHookDisabled() {
        val grpcUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/90003.m4s?deadline=1899999999"
        val jsonUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/90004.m4s?deadline=1899999999"
        val fakeReply = FakePlayViewReply(
            baseUrl = grpcUrl,
            backups = listOf("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/90003.m4s?deadline=1899999999")
        )
        val playinfoJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$jsonUrl","backupUrl":["https://upos-sz-mirrorali.bilivideo.com/upgcxcode/12/34/90004.m4s"],"is_drm":false}]}}}"""
        val fakeRequest = FakeOkHttpRequest("GET", "https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1enabled")

        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true, enableMossHook = false))

        assertTrue(
            "PlayViewReply inspection must run when module is enabled (independent of enableMossHook)",
            MossGrpcHook.inspectPlayViewReplyIfEnabled(fakeReply, sourceTag = "grpc-blocking")
        )
        assertNotNull("PlayinfoRegistry must record PlayViewReply when enabled", PlayinfoRegistry.lookup(grpcUrl))

        assertTrue(
            "OkHttp playurl call capture must run when module is enabled",
            MossGrpcHook.captureHttpPlayurlCallIfEnabled(Any(), fakeRequest)
        )
        assertTrue(MossGrpcHook.hasCapturedHttpPlayurlCallForTesting())

        assertTrue(
            "HTTP playinfo JSON inspection must run when module is enabled",
            MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(playinfoJson, allowRefreshCallback = true)
        )
        assertNotNull("PlayinfoRegistry must record JSON track when enabled", PlayinfoRegistry.lookup(jsonUrl))
        val refreshCb = PlayinfoRegistry.findRefreshCallback(jsonUrl)
        assertNotNull("Captured HTTP call should attach refresh callback when enabled", refreshCb)

        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = false))
        assertFalse(
            "Refresh callback must abort without issuing network call when module is later disabled",
            refreshCb!!.refresh()
        )
    }

    @Test
    fun testFastLooksLikePlayinfoJsonSupportsAkamaiAndUpgcxcode() {
        assertTrue(
            MossGrpcHook.fastLooksLikePlayinfoJson(
                """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"https://upos-hz-mirrorakam.akamaized.net/stream/1/2/3.m4s?deadline=1899999999&trid=123456"}]}}}"""
            )
        )
        assertTrue(
            MossGrpcHook.fastLooksLikePlayinfoJson(
                """{"code":0,"data":{"durl":[{"order":1,"length":10000,"url":"https://custom-mirror.example.com/upgcxcode/1/2/3.mp4?deadline=1899999999"}]}}"""
            )
        )
        assertFalse(
            "Tiny JSON below 128 bytes should be skipped by fast path",
            MossGrpcHook.fastLooksLikePlayinfoJson("""{"code":0,"data":{"dash":{"video":[{"baseUrl":"https://upos-hz-mirrorakam.akamaized.net/1.m4s"}]}}}""")
        )
    }

    @Test
    fun testCaptureFiltersNonGetOrNonPlayurlAndStaleCallerDoesNotPolluteJson() {
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true))

        assertFalse(
            "POST /playurl must not be captured as refreshable GET",
            MossGrpcHook.captureHttpPlayurlCallIfEnabled(
                Any(),
                FakeOkHttpRequest("POST", "https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1post")
            )
        )
        assertFalse(
            "Non-playurl GET must not be captured",
            MossGrpcHook.captureHttpPlayurlCallIfEnabled(
                Any(),
                FakeOkHttpRequest("GET", "https://api.bilibili.com/x/v2/reply?oid=1")
            )
        )
        assertFalse(MossGrpcHook.hasCapturedHttpPlayurlCallForTesting())

        // Capture a PGC playurl call at t0
        val t0 = 100_000L
        assertTrue(
            MossGrpcHook.captureHttpPlayurlCallIfEnabled(
                Any(),
                FakeOkHttpRequest("GET", "https://api.bilibili.com/pgc/player/web/playurl?ep_id=555"),
                nowMs = t0
            )
        )

        // allowRefreshCallback=false (JSONObject fallback path) must NOT attach PGC sourceUrl or refresh callback
        val jsonObjUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/91001.m4s?deadline=1899999999"
        val jsonObjText = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$jsonObjUrl","is_drm":false}]}}}"""
        assertTrue(
            MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(
                jsonObjText,
                allowRefreshCallback = false,
                nowMs = t0 + 1_000L
            )
        )
        assertFalse("JSONObject fallback must not mark UGC track as PGC from captured OkHttp call", PlayinfoRegistry.lookup(jsonObjUrl)!!.isEpisode)
        assertNull("JSONObject fallback must not attach HTTP refresh callback", PlayinfoRegistry.findRefreshCallback(jsonObjUrl))

        // Stale caller (>10s old) must NOT attach refreshCallback OR taint UGC track with old PGC sourceUrl
        val staleUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/91002.m4s?deadline=1899999999"
        val staleText = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$staleUrl","is_drm":false}]}}}"""
        assertTrue(
            MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(
                staleText,
                allowRefreshCallback = true,
                nowMs = t0 + 15_000L
            )
        )
        assertFalse("Stale captured PGC URL must not taint subsequent UGC track", PlayinfoRegistry.lookup(staleUrl)!!.isEpisode)
        assertNull("Stale captured call must not attach refresh callback", PlayinfoRegistry.findRefreshCallback(staleUrl))
    }

    @Test
    fun testHttpRefreshCallbackExecutesWhenEnabledAndAbortsWithoutNetworkWhenDisabled() {
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true))
        val trackUrlInitial = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/92001.m4s?deadline=1800000000"
        val trackUrlFresh = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/92001.m4s?deadline=1900000000"
        val initialJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$trackUrlInitial","is_drm":false}]}}}"""
        val refreshedJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$trackUrlFresh","is_drm":false}]}}}"""

        val fakeClient = FakeOkHttpClient(refreshedJson)
        val fakeRequest = FakeOkHttpRequest("GET", "https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1refresh")
        val t0 = 200_000L

        assertTrue(MossGrpcHook.captureHttpPlayurlCallIfEnabled(fakeClient, fakeRequest, nowMs = t0))
        assertTrue(MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(initialJson, allowRefreshCallback = true, nowMs = t0 + 500L))
        assertEquals(1800000000L, PlayinfoRegistry.lookup(trackUrlInitial)!!.deadlineEpochSec)

        val cb = PlayinfoRegistry.findRefreshCallback(trackUrlInitial)!!
        assertTrue("HTTP refresh callback must succeed when enabled", cb.refresh())
        assertEquals(1, fakeClient.callCount)
        assertTrue("HTTP refresh must close response", fakeClient.lastResponse!!.closed)
        assertEquals(1900000000L, PlayinfoRegistry.lookup(trackUrlInitial)!!.deadlineEpochSec)

        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = false))
        assertFalse("Disabled module must abort refresh without calling newCall", cb.refresh())
        assertEquals(1, fakeClient.callCount)
    }

    @Test
    fun testConsumedHttpPlayurlCallDoesNotLeakIntoSubsequentUgcWithin10sAndHandlesCrossPackageFakes() {
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true))
        val trackInitial = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/93001.m4s?deadline=1800000000"
        val trackFresh = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/93001.m4s?deadline=1950000000"
        val pgcJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$trackInitial","is_drm":false}]}}}"""
        val refreshedPgcJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$trackFresh","is_drm":false}]}}}"""

        val extClient = PackagePrivateNetworkFakes.Client(refreshedPgcJson)
        val extReq = PackagePrivateNetworkFakes.newRequest("GET", "https://api.bilibili.com/pgc/player/web/playurl?ep_id=777")
        val t0 = 300_000L

        assertTrue(MossGrpcHook.captureHttpPlayurlCallIfEnabled(extClient, extReq, nowMs = t0))
        assertTrue(MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(pgcJson, allowRefreshCallback = true, nowMs = t0 + 200L))
        assertTrue("PGC sourceUrl must mark track as episode", PlayinfoRegistry.lookup(trackInitial)!!.isEpisode)

        val cb = PlayinfoRegistry.findRefreshCallback(trackInitial)!!
        assertTrue("Cross-package package-private OkHttp refresh must succeed without IllegalAccessException", cb.refresh())
        assertEquals(1, extClient.callCount)
        assertTrue(extClient.isLastResponseClosed())
        assertEquals(1950000000L, PlayinfoRegistry.lookup(trackInitial)!!.deadlineEpochSec)

        // Subsequent uncaptured UGC playinfo within 10s must NOT inherit the already-consumed PGC caller
        val ugcUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/93002.m4s?deadline=1800000000"
        val ugcJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$ugcUrl","is_drm":false}]}}}"""
        assertTrue(MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(ugcJson, allowRefreshCallback = true, nowMs = t0 + 2_000L))
        assertFalse("Consumed PGC call must not taint subsequent UGC track within 10s", PlayinfoRegistry.lookup(ugcUrl)!!.isEpisode)
        assertNull("Consumed PGC call must not attach refresh callback to subsequent UGC track", PlayinfoRegistry.findRefreshCallback(ugcUrl))
    }

    @Test
    fun testRealOkHttp4ClientCaptureAndRefreshEndToEnd() {
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true))
        val initialUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/94001.m4s?deadline=1800000000"
        val freshUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/94001.m4s?deadline=1960000000"
        val initialJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$initialUrl","is_drm":false}]}}}"""
        val freshJson = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$freshUrl","is_drm":false}]}}}"""

        val hitCount = AtomicInteger(0)
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                hitCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(freshJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val okRequest = Request.Builder()
            .url("https://api.biliintl.com/intl/gateway/v2/ogv/playurl?ep_id=888")
            .get()
            .build()

        assertTrue(MossGrpcHook.isPlayurlApiUrl("https://api.biliintl.com/intl/gateway/v2/ogv/playurl?ep_id=888"))
        assertFalse(MossGrpcHook.isPlayurlApiUrl("https://evil.com/playurl?ref=bilibili.com"))
        assertTrue(MossGrpcHook.captureHttpPlayurlCallIfEnabled(okClient, okRequest, nowMs = 400_000L))
        assertTrue(MossGrpcHook.inspectHttpPlayinfoJsonIfEnabled(initialJson, allowRefreshCallback = true, nowMs = 400_500L))
        assertTrue(PlayinfoRegistry.lookup(initialUrl)!!.isEpisode)

        val cb = PlayinfoRegistry.findRefreshCallback(initialUrl)!!
        assertTrue("Real OkHttp4 RealCall + RealResponseBody refresh must succeed", cb.refresh())
        assertEquals(1, hitCount.get())
        assertEquals(1960000000L, PlayinfoRegistry.lookup(initialUrl)!!.deadlineEpochSec)
    }

    private interface FakeStreamObserver<T> {
        fun onNext(value: T)
        fun onError(t: Throwable)
        fun onCompleted()
    }

    private class FakeGrpcClientCallsHelper {
        companion object {
            var lastDeadlineSeen: Long = -1L
            var callCount: Int = 0
            var replyToReturn: FakePlayViewReply? = null

            @JvmStatic
            fun blockingUnaryCall(
                channel: Any,
                methodDesc: Any,
                callOptions: Any,
                reqMsg: Any
            ): FakePlayViewReply? {
                callCount++
                lastDeadlineSeen = PackagePrivateNetworkFakes.readCallOptionsDeadline(callOptions)
                return replyToReturn
            }
        }
    }

    @Test
    fun testGrpcBlockingRefreshFutureListenerAndAsyncStreamObserverProxy() {
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true))

        // 1. buildGrpcRefreshCallback: ignores non-PlayView methods, executes PlayView with 10s deadline & cross-package CallOptions
        val blockingMethod = FakeGrpcClientCallsHelper::class.java.getMethod(
            "blockingUnaryCall",
            Any::class.java,
            Any::class.java,
            Any::class.java,
            Any::class.java
        )
        val nonPlayDesc = PackagePrivateNetworkFakes.newMethodDescriptor("bilibili.app.view.v1.View/View")
        assertNull(
            MossGrpcHook.buildGrpcRefreshCallback(
                blockingMethod,
                listOf(Any(), nonPlayDesc, PackagePrivateNetworkFakes.newCallOptions(), Any())
            )
        )

        val grpcUrlOld = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/95001.m4s?deadline=1800000000"
        val grpcUrlFresh = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/95001.m4s?deadline=1970000000"
        assertTrue(
            MossGrpcHook.inspectPlayViewReplyIfEnabled(
                FakePlayViewReply(baseUrl = grpcUrlOld),
                sourceTag = "grpc-blocking"
            )
        )
        FakeGrpcClientCallsHelper.callCount = 0
        FakeGrpcClientCallsHelper.lastDeadlineSeen = -1L
        FakeGrpcClientCallsHelper.replyToReturn = FakePlayViewReply(baseUrl = grpcUrlFresh)

        val playViewDesc = PackagePrivateNetworkFakes.newMethodDescriptor("bilibili.app.playerunite.v1.Player/PlayViewUnite")
        val grpcRefreshCb = MossGrpcHook.buildGrpcRefreshCallback(
            blockingMethod,
            listOf(Any(), playViewDesc, PackagePrivateNetworkFakes.newCallOptions(), Any())
        )!!
        assertTrue(grpcRefreshCb.refresh())
        assertEquals(1, FakeGrpcClientCallsHelper.callCount)
        assertEquals(10L, FakeGrpcClientCallsHelper.lastDeadlineSeen)
        assertEquals(1970000000L, PlayinfoRegistry.lookup(grpcUrlOld)!!.deadlineEpochSec)

        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = false))
        assertFalse(grpcRefreshCb.refresh())
        assertEquals(1, FakeGrpcClientCallsHelper.callCount)

        // 2. attachFuturePlayViewListener on cross-package package-private ListenableFuture
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true))
        val futureUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/95002.m4s?deadline=1899999999"
        val future = PackagePrivateNetworkFakes.newListenableFuture<FakePlayViewReply>()
        val directExec = Executor { it.run() }
        assertTrue(MossGrpcHook.attachFuturePlayViewListener(future, directExec))
        future.complete(FakePlayViewReply(baseUrl = futureUrl))
        assertNotNull("Future listener must inspect completed PlayViewReply", PlayinfoRegistry.lookup(futureUrl))

        val disabledFutureUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/95003.m4s?deadline=1899999999"
        val future2 = PackagePrivateNetworkFakes.newListenableFuture<FakePlayViewReply>()
        assertTrue(MossGrpcHook.attachFuturePlayViewListener(future2, directExec))
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = false))
        future2.complete(FakePlayViewReply(baseUrl = disabledFutureUrl))
        assertNull("Future listener must skip inspection when module is disabled", PlayinfoRegistry.lookup(disabledFutureUrl))

        // 3. wrapAsyncStreamObserver: inspects PlayViewReply on onNext, respects disabled flag, and unwraps InvocationTargetException
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true))
        val asyncUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/12/34/95004.m4s?deadline=1899999999"
        var completedCalled = false
        var shouldThrowOnNext = false
        val origObserver = object : FakeStreamObserver<FakePlayViewReply> {
            override fun onNext(value: FakePlayViewReply) {
                if (shouldThrowOnNext) {
                    throw IllegalStateException("observer boom")
                }
            }
            override fun onError(t: Throwable) {}
            override fun onCompleted() {
                completedCalled = true
            }
        }
        @Suppress("UNCHECKED_CAST")
        val proxied = MossGrpcHook.wrapAsyncStreamObserver(
            FakeStreamObserver::class.java,
            origObserver
        ) as FakeStreamObserver<FakePlayViewReply>
        proxied.onNext(FakePlayViewReply(baseUrl = asyncUrl))
        assertNotNull("Async observer proxy must inspect PlayViewReply", PlayinfoRegistry.lookup(asyncUrl))
        proxied.onCompleted()
        assertTrue(completedCalled)

        shouldThrowOnNext = true
        try {
            proxied.onNext(FakePlayViewReply(baseUrl = asyncUrl))
            fail("Expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("observer boom", e.message)
        }
    }
}
