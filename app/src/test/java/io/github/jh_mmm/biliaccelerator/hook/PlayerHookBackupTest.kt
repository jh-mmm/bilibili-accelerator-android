package io.github.jh_mmm.biliaccelerator.hook

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.BiliAcceleratorCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHookBackupTest {

    private val config = AcceleratorConfig(
        enabled = true,
        targetHost = "upos-sz-mirrorcos.bilivideo.com",
        blockPcdn = true
    )

    @Test
    fun testBackupUrlsFilteringAllPcdnRewritesToTargetFallbackOnly() {
        // 真实调用 PlayerHook.filterAndFillBackupUrls：当备用列表全是 PCDN 时，仅回退重写至 targetHost，绝不凭空合成 CANDIDATE_POOL 未签发域名
        val rawList = listOf(
            "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s?sign=abc",
            "http://node.nexusedgeio.com/upgcxcode/54/12/34567.m4s?sign=abc"
        )

        val distinctList = PlayerHook.filterAndFillBackupUrls(rawList, config)
        assertEquals("Should only rewrite to configured targetHost without fabricating extra candidate mirrors", 1, distinctList.size)
        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?sign=abc", distinctList[0])
        assertFalse("Must not contain original bare IP", distinctList.any { it.contains("112.34.56.78") })
        assertFalse("Must not contain original nexusedgeio", distinctList.any { it.contains("nexusedgeio.com") })
    }

    @Test
    fun testBackupUrlsPreservingCleanOfficialMirrorsFirst() {
        val rawList = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s?sign=ali",
            "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s?sign=pcdn"
        )

        val distinctList = PlayerHook.filterAndFillBackupUrls(rawList, config)
        assertEquals(2, distinctList.size)
        assertEquals("Clean issued mirrorali must be prioritized first", "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s?sign=ali", distinctList[0])
        assertEquals("PCDN IP rewritten to mirrorcos as fallback", "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?sign=pcdn", distinctList[1])
    }

    @Test
    fun testPromoteIssuedPromotesCleanBackupOverPcdnPrimary() {
        val pcdnBase = "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s?sign=pcdn"
        val cleanIssuedBackup = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s?sign=issued_ali"
        val secondPcdnBackup = "http://node.nexusedgeio.com/upgcxcode/54/12/34567.m4s?sign=pcdn2"

        val result = PlayerHook.promoteIssued(
            baseUrl = pcdnBase,
            backupUrls = listOf(secondPcdnBackup, cleanIssuedBackup),
            config = config
        )

        assertTrue("Should promote Bilibili-issued clean backup URL", result.promoted)
        assertEquals("Base URL must be replaced with clean issued backup", cleanIssuedBackup, result.baseUrl)
        assertNotNull("Promote RewriteResult must be recorded", result.promoteResult)
        assertEquals("pcdn-promote", result.promoteResult?.reason)
        assertEquals("112.34.56.78", result.promoteResult?.originalHost)
        assertEquals("upos-sz-mirrorali.bilivideo.com", result.promoteResult?.targetHost)
        assertFalse("Promoted URL should be removed from remaining backups", result.backupUrls.contains(cleanIssuedBackup))
    }

    @Test
    fun testLiveBackupUrlsFiltersSlowPcdnButKeepsOfficialLiveHost() {
        val officialLive = "https://d1--cn-gotcha01.bilivideo.com/live-bvc/12345/live_123.flv?sign=ok"
        val pcdnLiveMcdn = "https://xy1x2x3x4xy.mcdn.bilivideo.cn:486/live-bvc/12345/live_123.flv?os=mcdn"
        val pcdnLivePort = "https://cn-gotcha01.bilivideo.com:8080/live-bvc/12345/live_123.flv"

        val filtered = PlayerHook.filterAndFillBackupUrls(
            listOf(pcdnLiveMcdn, officialLive, pcdnLivePort),
            config
        )

        assertEquals("Should only keep official live CDN host", listOf(officialLive), filtered)

        // 若全部直播候选均为慢速节点，绝不删空最后一个可用节点
        val allSlow = listOf(pcdnLiveMcdn, pcdnLivePort)
        val fallbackKept = PlayerHook.filterAndFillBackupUrls(allSlow, config)
        assertEquals("Must not remove all hosts when every live host looks slow", allSlow, fallbackKept)
    }

    @Test
    fun testSegmentReportingDeduplicationAndIgnoredSkip() {
        val builderObj = Any()
        val mediaUrl = "http://112.34.56.78:8080/upgcxcode/99/88/unique_seg_${System.nanoTime()}.m4s"
        val rewriteRes = BiliAcceleratorCore.rewriteUrl(mediaUrl, config)

        assertTrue("First report on segment builder should succeed", PlayerHook.shouldReportSegment(mediaUrl, rewriteRes, builderObj))
        assertFalse("Second report on same builder instance must be deduplicated", PlayerHook.shouldReportSegment(mediaUrl, rewriteRes, builderObj))
        assertFalse("Second report on same segment path within window must be deduplicated", PlayerHook.shouldReportSegment(mediaUrl, rewriteRes, Any()))

        val nonMediaUrl = "https://api.bilivideo.com/v2/status"
        val ignoredRes = BiliAcceleratorCore.rewriteUrl(nonMediaUrl, config)
        assertFalse("Ignored non-media URLs must never be reported", PlayerHook.shouldReportSegment(nonMediaUrl, ignoredRes))
    }
}
