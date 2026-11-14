package io.github.jh_mmm.biliaccelerator.hook

import io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager
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

    @Test
    fun testBackupUrlsWithBlockPcdnDisabledAndForceUposEnabled() {
        val forceOnlyConfig = AcceleratorConfig(
            enabled = true,
            targetHost = "upos-sz-mirrorcos.bilivideo.com",
            blockPcdn = false,
            proxyMcdn = true,
            forceUpos = true
        )
        val rawList = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s",
            "https://xy1x2x3x4xy.mcdn.bilivideo.cn/upgcxcode/54/12/34567.m4s?os=mcdn",
            "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s"
        )
        val distinctList = PlayerHook.filterAndFillBackupUrls(rawList, forceOnlyConfig)
        assertTrue("mirrorali should be rewritten to mirrorcos when forceUpos=true", distinctList.contains("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s"))
        assertTrue("MCDN should be proxied when proxyMcdn=true", distinctList.any { it.startsWith("https://proxy-tf-all-ws.bilivideo.com/?url=") })
        assertTrue("PCDN URL should be preserved when blockPcdn=false", distinctList.contains("http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s"))
    }

    @Test
    fun testBtrAkamaiPreservedWithSynthesizedMirrors() {
        val rewrittenUpos = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?token=sig"
        val originalAkamai = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/54/12/34567.m4s?token=sig"
        val btrConfig = config.copy(enableBtr = true, rewriteAkamai = true, btrCdnPoolSize = 5)

        val sid = BtrSessionManager.register(
            primaryUrl = rewrittenUpos,
            backupUrls = listOf(originalAkamai),
            isAudio = false,
            config = btrConfig
        )
        val session = BtrSessionManager.get(sid)!!
        assertTrue("Preserved Akamai URL must stay in session backups as 403 fallback", session.backupUrls.contains(originalAkamai))
        assertTrue("Additional UPOS mirrors must still be synthesized alongside Akamai fallback", session.backupUrls.any { it.contains("mirrorali.bilivideo.com") })
    }

    @Test
    fun testUpdateBackupsByCandidateUrlsIsolatesConcurrentVideoAndAudioTracks() {
        val videoPrimary = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/11/22/99999-1-30080.m4s?tag=v"
        val audioPrimary = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/11/22/99999-1-30280.m4s?tag=a"
        val audioInitialBackup = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/11/22/99999-1-30280.m4s?tag=a"

        // 先注册视频轨，再注册音频轨（此时音频成为 latestSessionId）
        val videoSid = BtrSessionManager.register(videoPrimary, isAudio = false)
        val audioSid = BtrSessionManager.register(audioPrimary, listOf(audioInitialBackup), isAudio = true)

        // 即使路径前缀变化，通过文件关键帧及轨道隔离也不得覆盖音频轨备用源
        val newVideoBackups = listOf(
            "https://upos-sz-mirrorhw.bilivideo.com/altprefix/11/22/99999-1-30080.m4s?tag=v2"
        )
        BtrSessionManager.updateBackupsByCandidateUrls(newVideoBackups)

        val videoSession = BtrSessionManager.get(videoSid)!!
        val audioSession = BtrSessionManager.get(audioSid)!!
        assertEquals("Video backup must match video session by file key", newVideoBackups, videoSession.backupUrls)
        assertEquals("Audio backup must NOT be overwritten by video backups", listOf(audioInitialBackup), audioSession.backupUrls)

        // 完全无路径/文件名重合的视频备份下发时，同样必须隔离音频轨
        val unrelatedVideoBackups = listOf(
            "https://upos-sz-mirrorbos.bilivideo.com/upgcxcode/33/44/88888-1-30080.m4s"
        )
        BtrSessionManager.updateBackupsByCandidateUrls(unrelatedVideoBackups)
        assertEquals("Audio backup must stay isolated when unrelated video backups arrive", listOf(audioInitialBackup), BtrSessionManager.get(audioSid)!!.backupUrls)
    }
}
