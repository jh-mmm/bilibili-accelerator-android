package io.github.jh_mmm.biliaccelerator.hook

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHookBackupTest {

    private val config = AcceleratorConfig(
        enabled = true,
        targetHost = "upos-sz-mirrorcos.bilivideo.com",
        blockPcdn = true
    )

    @Test
    fun testBackupUrlsFilteringPcdnAndFillingMirrors() {
        // 模拟播放器下发的纯 PCDN 备用列表
        val rawList = listOf(
            "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s",
            "http://node.nexusedgeio.com/upgcxcode/54/12/34567.m4s"
        )

        val distinctList = PlayerHook.filterAndFillBackupUrls(rawList, config)

        assertTrue("Filtered backup mirrors must not be empty", distinctList.isNotEmpty())
        assertTrue("Must contain rewritten targetHost mirror", distinctList.any { it.contains(config.targetHost) })
        assertTrue("Must contain alternative mirror (e.g. mirrorali)", distinctList.any { it.contains("mirrorali") })
        assertFalse("Must not contain original bare IP", distinctList.any { it.contains("112.34.56.78") })
        assertFalse("Must not contain original nexusedgeio", distinctList.any { it.contains("nexusedgeio.com") })
    }

    @Test
    fun testBackupUrlsPreservingCleanOfficialMirrors() {
        val rawList = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s",
            "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s"
        )

        val distinctList = PlayerHook.filterAndFillBackupUrls(rawList, config)
        assertEquals(2, distinctList.size)
        assertTrue("Clean mirrorali must be preserved", distinctList.contains("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s"))
        assertTrue("PCDN IP must be rewritten to mirrorcos", distinctList.contains("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s"))
    }
}
