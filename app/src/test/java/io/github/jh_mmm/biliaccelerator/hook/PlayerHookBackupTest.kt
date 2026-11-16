package io.github.jh_mmm.biliaccelerator.hook

import io.github.jh_mmm.biliaccelerator.btr.BtrLocalProxyServer
import io.github.jh_mmm.biliaccelerator.btr.BtrSessionManager
import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import io.github.jh_mmm.biliaccelerator.core.PlayinfoRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun testComputeNativeBackupsConvergesVodAndLiveWhenBtrActive() {
        val btrUrl = "http://127.0.0.1:45678/stream/abc123.flv"
        val rawBackups = listOf(
            "https://d1--cn-gotcha204.bilivideo.com/live-bvc/123.flv",
            "https://d1--cn-gotcha07.bilivideo.com/live-bvc/123.flv"
        )
        assertEquals(
            listOf(btrUrl),
            PlayerHook.computeNativeBackupsForSession(btrUrl, rawBackups, btrConfigEnabled = true)
        )
        assertEquals(
            emptyList<String>(),
            PlayerHook.computeNativeBackupsForSession(null, rawBackups, btrConfigEnabled = true)
        )
        assertEquals(
            rawBackups,
            PlayerHook.computeNativeBackupsForSession(btrUrl, rawBackups, btrConfigEnabled = false)
        )
    }

    private class FakeSegmentBuilder {
        var url: String = ""
        var backups: List<String> = emptyList()
        fun setBackupUrls(list: List<String>): FakeSegmentBuilder {
            backups = list
            return this
        }
    }

    private class FakeArraySegmentBuilder {
        var backups: Array<String> = emptyArray()
        fun setBackupUrls(urls: Array<String>) {
            backups = urls
        }
    }

    private class FakeSetSegmentBuilder {
        var backups: Set<String> = emptySet()
        fun setBackupUrls(urls: Set<String>): FakeSetSegmentBuilder {
            backups = urls
            return this
        }
    }

    private class FakeHashSetSegmentBuilder {
        var backups: HashSet<String> = HashSet()
        fun setBackupUrls(urls: HashSet<String>) {
            backups = urls
        }
    }

    private class FakeTreeSetSegmentBuilder {
        var backups: java.util.TreeSet<String> = java.util.TreeSet()
        fun setBackupUrls(urls: java.util.TreeSet<String>) {
            backups = urls
        }
    }

    private class FakeLinkedListSegmentBuilder {
        var backups: java.util.LinkedList<String> = java.util.LinkedList()
        fun setBackupUrls(urls: java.util.LinkedList<String>) {
            backups = urls
        }
    }

    private class FakeDelegatingSegmentBuilder {
        var backups: List<String> = emptyList()
        var innerHookInvocations = 0
        var lastInnerArg: List<String> = emptyList()

        fun setBackupUrls(urls: Array<String>) {
            val listArg = urls.toList()
            val method = FakeDelegatingSegmentBuilder::class.java.getMethod("setBackupUrls", List::class.java)
            PlayerHook.interceptSetBackupUrls(
                builder = this,
                method = method,
                rawArg = listArg,
                onRewrite = null,
                proceedOriginal = { setBackupUrls(listArg) },
                proceedWithArg = { arg ->
                    innerHookInvocations++
                    @Suppress("UNCHECKED_CAST")
                    lastInnerArg = arg as List<String>
                    setBackupUrls(lastInnerArg)
                }
            )
        }

        fun setBackupUrls(urls: List<String>) {
            backups = urls
        }
    }

    @After
    fun tearDown() {
        BtrLocalProxyServer.stop()
        PlayinfoRegistry.resetForTesting()
        RemoteClient.setCachedConfigForTesting(AcceleratorConfig(enabled = true), 0L)
    }

    @Test
    fun testApplyBuilderBackupUrlsUpdatesListSetAndArrayTargets() {
        val btrUrl = "http://127.0.0.1:41234/stream/sid001.m4s"

        val listBuilder = FakeSegmentBuilder()
        assertTrue(PlayerHook.applyBuilderBackupUrls(listBuilder, listOf(btrUrl)))
        assertEquals(listOf(btrUrl), listBuilder.backups)

        val setBuilder = FakeSetSegmentBuilder()
        assertTrue(PlayerHook.applyBuilderBackupUrls(setBuilder, listOf(btrUrl)))
        assertEquals(setOf(btrUrl), setBuilder.backups)

        val hashSetBuilder = FakeHashSetSegmentBuilder()
        assertTrue(PlayerHook.applyBuilderBackupUrls(hashSetBuilder, listOf(btrUrl)))
        assertEquals(hashSetOf(btrUrl), hashSetBuilder.backups)

        val treeSetBuilder = FakeTreeSetSegmentBuilder()
        assertTrue(PlayerHook.applyBuilderBackupUrls(treeSetBuilder, listOf(btrUrl)))
        assertEquals(sortedSetOf(btrUrl), treeSetBuilder.backups)

        val linkedListBuilder = FakeLinkedListSegmentBuilder()
        assertTrue(PlayerHook.applyBuilderBackupUrls(linkedListBuilder, listOf(btrUrl)))
        assertEquals(java.util.LinkedList(listOf(btrUrl)), linkedListBuilder.backups)

        val arrayBuilder = FakeArraySegmentBuilder()
        assertTrue(PlayerHook.applyBuilderBackupUrls(arrayBuilder, listOf(btrUrl)))
        assertEquals(listOf(btrUrl), arrayBuilder.backups.toList())
    }

    @Test
    fun testUpdateBackupsPreservesSynthesizedMirrorsWhenAkamaiOnlyBackupsArriveWithNullConfig() {
        val akamaiUrl = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/77/88/55555.m4s?deadline=1899999999"
        val sid = BtrSessionManager.register(primaryUrl = akamaiUrl, backupUrls = emptyList(), config = null)
        val initialSession = BtrSessionManager.get(sid)!!
        assertTrue("Synthesized mirrors must be populated for Akamai even when config is null", initialSession.synthesizedMirrors.isNotEmpty())

        val newAkamaiBackup = listOf("https://upos-sz-mirrorakam.akamaized.net/upgcxcode/77/88/55555.m4s?deadline=1899999999")
        BtrSessionManager.updateBackups(sid, newAkamaiBackup)
        val updatedBackups = BtrSessionManager.get(sid)!!.backupUrls
        assertTrue("New Akamai backup must be included", updatedBackups.containsAll(newAkamaiBackup))
        assertTrue(
            "Synthesized UPOS mirrors must stay preserved when player only supplies Akamai backups",
            updatedBackups.any { it.contains("bilivideo.com") }
        )
        BtrSessionManager.remove(sid)
    }

    @Test
    fun testReusedBuilderOutOfBtrRestoresPendingBackupsWhenSetBackupUrlsRanWhileBound() {
        val btrConfig = config.copy(enableBtr = true, enableEpisodeAcceleration = false)
        val vodUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/34567.m4s?deadline=1899999999"
        val drmUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/99999.m4s?drm_type=1"
        val rawBackups = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/34567.m4s",
            "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s"
        )
        val expectedRestored = PlayerHook.filterAndFillBackupUrls(rawBackups, btrConfig)
        val builder = FakeSegmentBuilder()
        val setBackupMethod = FakeSegmentBuilder::class.java.getMethod("setBackupUrls", List::class.java)

        val rewrittenArgs = PlayerHook.rewriteArgIfMedia(
            args = listOf(vodUrl),
            builderInstance = builder,
            configOverride = btrConfig,
            onRewrite = null
        )
        val btrUrl = rewrittenArgs?.get(0) as? String
        assertTrue("VOD URL must rewrite to local BTR proxy", btrUrl?.startsWith("http://127.0.0.1:") == true)
        val sid = BtrSessionManager.getSessionForBuilder(builder)!!

        try {
            @Suppress("UNCHECKED_CAST")
            PlayerHook.interceptSetBackupUrls(
                builder = builder,
                method = setBackupMethod,
                rawArg = rawBackups,
                config = btrConfig,
                onRewrite = null,
                proceedOriginal = { builder.setBackupUrls(rawBackups) },
                proceedWithArg = { arg -> builder.setBackupUrls(arg as List<String>) }
            )
            assertEquals("Native backups must converge to BTR URL while bound", listOf(btrUrl!!), builder.backups)

            // Reusing builder with DRM URL transitions out of BTR and must restore clean non-BTR backups
            val drmResult = PlayerHook.rewriteArgIfMedia(
                args = listOf(drmUrl),
                builderInstance = builder,
                configOverride = btrConfig,
                onRewrite = null
            )
            assertNull("DRM URL must bypass BTR", drmResult)
            assertNull("Builder must be unbound after transitioning out of BTR", BtrSessionManager.getSessionForBuilder(builder))
            assertFalse(
                "Stale BTR loopback URL must NOT remain in native backups",
                builder.backups.any { it.contains("127.0.0.1:") }
            )
            assertEquals("Clean filtered backups must be restored to native builder", expectedRestored, builder.backups)

            PlayerHook.flushDeferredBackupsBeforeBuild(builder)
            assertEquals("build() flush must preserve restored non-BTR backups", expectedRestored, builder.backups)
        } finally {
            BtrSessionManager.remove(sid)
        }
    }

    @Test
    fun testReusedBuilderOutOfBtrRestoresPendingBackupsWhenSetBackupUrlsPrecededVodSetUrl() {
        val btrConfig = config.copy(enableBtr = true, enableEpisodeAcceleration = false)
        val vodUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/45678.m4s?deadline=1899999999"
        val episodeUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/45679.m4s?ep_id=12345"
        val rawBackups = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/45678.m4s",
            "http://112.34.56.78:8080/upgcxcode/54/12/45678.m4s"
        )
        val expectedRestored = PlayerHook.filterAndFillBackupUrls(rawBackups, btrConfig)
        val builder = FakeArraySegmentBuilder()
        val setBackupMethod = FakeArraySegmentBuilder::class.java.getMethod("setBackupUrls", Array<String>::class.java)

        @Suppress("UNCHECKED_CAST")
        PlayerHook.interceptSetBackupUrls(
            builder = builder,
            method = setBackupMethod,
            rawArg = rawBackups.toTypedArray(),
            config = btrConfig,
            onRewrite = null,
            proceedOriginal = { builder.setBackupUrls(rawBackups.toTypedArray()) },
            proceedWithArg = { arg -> builder.setBackupUrls(arg as Array<String>) }
        )
        assertEquals("Deferred setBackupUrls before setUrl must not invoke native setter yet", 0, builder.backups.size)

        val rewrittenArgs = PlayerHook.rewriteArgIfMedia(
            args = listOf(vodUrl),
            builderInstance = builder,
            configOverride = btrConfig,
            onRewrite = null
        )
        val btrUrl = rewrittenArgs?.get(0) as? String
        assertTrue("VOD URL must rewrite to local BTR proxy", btrUrl?.startsWith("http://127.0.0.1:") == true)
        val sid = BtrSessionManager.getSessionForBuilder(builder)!!

        try {
            assertEquals("Deferred backups must converge to BTR URL when VOD URL enters BTR", listOf(btrUrl!!), builder.backups.toList())

            // Reusing builder with local file URI / non-media URL transitions out of BTR (deferredMethod was already consumed on VOD setUrl)
            val nonHttpMediaArgs = PlayerHook.rewriteArgIfMedia(
                args = listOf("file:///sdcard/local_cache.mp4"),
                builderInstance = builder,
                configOverride = btrConfig,
                onRewrite = null
            )
            assertNull("Local file:// path must not be rewritten to BTR", nonHttpMediaArgs)
            assertFalse("Stale BTR loopback URL must NOT remain after local file transition", builder.backups.any { it.contains("127.0.0.1:") })
            assertEquals("Native array backups must be restored", expectedRestored, builder.backups.toList())

            // Re-enter BTR, then transition out via non-media string (strIndex == -1)
            val rewrittenArgsMid = PlayerHook.rewriteArgIfMedia(
                args = listOf(vodUrl),
                builderInstance = builder,
                configOverride = btrConfig,
                onRewrite = null
            )
            val btrUrlMid = rewrittenArgsMid?.get(0) as? String
            assertTrue(btrUrlMid?.startsWith("http://127.0.0.1:") == true)
            val sidMid = BtrSessionManager.getSessionForBuilder(builder)!!
            try {
                assertEquals(listOf(btrUrlMid!!), builder.backups.toList())
                val nonMediaArgs = PlayerHook.rewriteArgIfMedia(
                    args = listOf("https://example.com/unrelated"),
                    builderInstance = builder,
                    configOverride = btrConfig,
                    onRewrite = null
                )
                assertNull(nonMediaArgs)
                assertFalse("Stale BTR loopback URL must NOT remain after non-media transition", builder.backups.any { it.contains("127.0.0.1:") })
                assertEquals(expectedRestored, builder.backups.toList())
            } finally {
                BtrSessionManager.remove(sidMid)
            }

            // Transition back into BTR, then transition out via non-BTR episode URL
            val rewrittenArgs2 = PlayerHook.rewriteArgIfMedia(
                args = listOf(vodUrl),
                builderInstance = builder,
                configOverride = btrConfig,
                onRewrite = null
            )
            val btrUrl2 = rewrittenArgs2?.get(0) as? String
            assertTrue(btrUrl2?.startsWith("http://127.0.0.1:") == true)
            val sid2 = BtrSessionManager.getSessionForBuilder(builder)!!
            try {
                assertEquals(listOf(btrUrl2!!), builder.backups.toList())

                val episodeArgs = PlayerHook.rewriteArgIfMedia(
                    args = listOf(episodeUrl),
                    builderInstance = builder,
                    configOverride = btrConfig,
                    onRewrite = null
                )
                assertNull("Episode URL with enableEpisodeAcceleration=false must bypass BTR", episodeArgs)
                assertFalse("Stale BTR URL must NOT remain after non-BTR episode transition", builder.backups.any { it.contains("127.0.0.1:") })
                assertEquals(expectedRestored, builder.backups.toList())
            } finally {
                BtrSessionManager.remove(sid2)
            }
        } finally {
            BtrSessionManager.remove(sid)
        }
    }

    @Test
    fun testEmptySetBackupUrlsClearsStaleConvergenceAndPreventsResurrection() {
        val btrConfig = config.copy(enableBtr = true)
        val vodUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/56789.m4s?deadline=1899999999"
        val drmUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/56789.m4s?drm_type=1"
        val rawBackups = listOf("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/56789.m4s")
        val builder = FakeSegmentBuilder()
        val setBackupMethod = FakeSegmentBuilder::class.java.getMethod("setBackupUrls", List::class.java)

        val rewrittenArgs = PlayerHook.rewriteArgIfMedia(
            args = listOf(vodUrl),
            builderInstance = builder,
            configOverride = btrConfig,
            onRewrite = null
        )
        val btrUrl = rewrittenArgs?.get(0) as? String
        assertTrue(btrUrl?.startsWith("http://127.0.0.1:") == true)
        val sid = BtrSessionManager.getSessionForBuilder(builder)!!

        try {
            @Suppress("UNCHECKED_CAST")
            PlayerHook.interceptSetBackupUrls(
                builder = builder,
                method = setBackupMethod,
                rawArg = rawBackups,
                config = btrConfig,
                onRewrite = null,
                proceedOriginal = { builder.setBackupUrls(rawBackups) },
                proceedWithArg = { arg -> builder.setBackupUrls(arg as List<String>) }
            )
            assertEquals(listOf(btrUrl!!), builder.backups)

            // Explicit empty list clears native backups
            @Suppress("UNCHECKED_CAST")
            PlayerHook.interceptSetBackupUrls(
                builder = builder,
                method = setBackupMethod,
                rawArg = emptyList<String>(),
                config = btrConfig,
                onRewrite = null,
                proceedOriginal = { builder.setBackupUrls(emptyList()) },
                proceedWithArg = { arg -> builder.setBackupUrls(arg as List<String>) }
            )
            assertEquals(emptyList<String>(), builder.backups)

            // Transitioning out of BTR must NOT overwrite emptyList with stale rawBackups
            PlayerHook.rewriteArgIfMedia(
                args = listOf(drmUrl),
                builderInstance = builder,
                configOverride = btrConfig,
                onRewrite = null
            )
            assertEquals(emptyList<String>(), builder.backups)
        } finally {
            BtrSessionManager.remove(sid)
        }
    }

    @Test
    fun testNonBtrSetUrlFollowedBySetBackupUrlsAppliesImmediatelyWithoutBuildAndCanReenterBtr() {
        val btrConfig = config.copy(enableBtr = true, enableEpisodeAcceleration = false)
        val drmUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/67890.m4s?drm_type=1"
        val vodUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/67891.m4s?deadline=1899999999"
        val episodeUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/67892.m4s?ep_id=987"
        val rawBackups = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/67891.m4s",
            "http://112.34.56.78:8080/upgcxcode/54/12/67891.m4s"
        )
        val expectedRestored = PlayerHook.filterAndFillBackupUrls(rawBackups, btrConfig)
        val builder = FakeSegmentBuilder()
        val setBackupMethod = FakeSegmentBuilder::class.java.getMethod("setBackupUrls", List::class.java)

        // setUrl(drm) runs BEFORE setBackupUrls: builder resolved non-BTR
        assertNull(
            PlayerHook.rewriteArgIfMedia(
                args = listOf(drmUrl),
                builderInstance = builder,
                configOverride = btrConfig,
                onRewrite = null
            )
        )

        // setBackupUrls without build() must apply immediately instead of staying deferred
        @Suppress("UNCHECKED_CAST")
        PlayerHook.interceptSetBackupUrls(
            builder = builder,
            method = setBackupMethod,
            rawArg = rawBackups,
            config = btrConfig,
            onRewrite = null,
            proceedOriginal = { builder.setBackupUrls(rawBackups) },
            proceedWithArg = { arg -> builder.setBackupUrls(arg as List<String>) }
        )
        assertEquals(expectedRestored, builder.backups)

        // Reusing same builder with VOD setUrl must still converge native backups to BTR
        val rewrittenArgs = PlayerHook.rewriteArgIfMedia(
            args = listOf(vodUrl),
            builderInstance = builder,
            configOverride = btrConfig,
            onRewrite = null
        )
        val btrUrl = rewrittenArgs?.get(0) as? String
        assertTrue(btrUrl?.startsWith("http://127.0.0.1:") == true)
        val sid = BtrSessionManager.getSessionForBuilder(builder)!!
        try {
            assertEquals(listOf(btrUrl!!), builder.backups)

            // Transitioning back out via disabled episode URL restores clean backups
            assertNull(
                PlayerHook.rewriteArgIfMedia(
                    args = listOf(episodeUrl),
                    builderInstance = builder,
                    configOverride = btrConfig,
                    onRewrite = null
                )
            )
            assertEquals(expectedRestored, builder.backups)
        } finally {
            BtrSessionManager.remove(sid)
        }
    }

    @Test
    fun testDeferredSetBackupUrlsFlushesOnBuildAndWhenDisabledOrNonBtrBeforeSetUrl() {
        val btrConfig = config.copy(enableBtr = true)
        val vodUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/71111.m4s?deadline=1899999999"
        val rawBackups = listOf("http://112.34.56.78:8080/upgcxcode/54/12/71111.m4s")
        val expectedRestored = PlayerHook.filterAndFillBackupUrls(rawBackups, btrConfig)
        val setBackupMethod = FakeSegmentBuilder::class.java.getMethod("setBackupUrls", List::class.java)

        // 1. build() flushes deferred backups when setUrl was never called
        val buildOnlyBuilder = FakeSegmentBuilder()
        @Suppress("UNCHECKED_CAST")
        PlayerHook.interceptSetBackupUrls(
            builder = buildOnlyBuilder,
            method = setBackupMethod,
            rawArg = rawBackups,
            config = btrConfig,
            onRewrite = null,
            proceedOriginal = { buildOnlyBuilder.setBackupUrls(rawBackups) },
            proceedWithArg = { arg -> buildOnlyBuilder.setBackupUrls(arg as List<String>) }
        )
        assertEquals(emptyList<String>(), buildOnlyBuilder.backups)
        PlayerHook.flushDeferredBackupsBeforeBuild(buildOnlyBuilder)
        assertEquals(expectedRestored, buildOnlyBuilder.backups)

        // 2. Module disabled before setUrl flushes deferred backups
        val disabledBuilder = FakeSegmentBuilder()
        @Suppress("UNCHECKED_CAST")
        PlayerHook.interceptSetBackupUrls(
            builder = disabledBuilder,
            method = setBackupMethod,
            rawArg = rawBackups,
            config = btrConfig,
            onRewrite = null,
            proceedOriginal = { disabledBuilder.setBackupUrls(rawBackups) },
            proceedWithArg = { arg -> disabledBuilder.setBackupUrls(arg as List<String>) }
        )
        assertNull(
            PlayerHook.rewriteArgIfMedia(
                args = listOf(vodUrl),
                builderInstance = disabledBuilder,
                configOverride = btrConfig.copy(enabled = false),
                onRewrite = null
            )
        )
        assertEquals(expectedRestored, disabledBuilder.backups)

        // 3. BTR disabled before setUrl flushes deferred backups
        val nonBtrBuilder = FakeSegmentBuilder()
        @Suppress("UNCHECKED_CAST")
        PlayerHook.interceptSetBackupUrls(
            builder = nonBtrBuilder,
            method = setBackupMethod,
            rawArg = rawBackups,
            config = btrConfig,
            onRewrite = null,
            proceedOriginal = { nonBtrBuilder.setBackupUrls(rawBackups) },
            proceedWithArg = { arg -> nonBtrBuilder.setBackupUrls(arg as List<String>) }
        )
        PlayerHook.rewriteArgIfMedia(
            args = listOf(vodUrl),
            builderInstance = nonBtrBuilder,
            configOverride = btrConfig.copy(enableBtr = false),
            onRewrite = null
        )
        assertEquals(expectedRestored, nonBtrBuilder.backups)
    }

    @Test
    fun testReusedBuilderSwitchingVodBtrAndLiveNonBtrUpdatesAndRestoresBackups() {
        val btrConfig = config.copy(enableBtr = true, enableLiveAcceleration = false)
        val vodUrl1 = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/81111.m4s?deadline=1899999999"
        val vodUrl2 = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/82222.m4s?deadline=1899999999"
        val liveUrl = "https://d1--cn-gotcha01.bilivideo.com/live-bvc/123/live.flv?sign=abc"
        val rawBackups = listOf("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/81111.m4s")
        val expectedRestored = PlayerHook.filterAndFillBackupUrls(rawBackups, btrConfig)
        val builder = FakeSegmentBuilder()
        val setBackupMethod = FakeSegmentBuilder::class.java.getMethod("setBackupUrls", List::class.java)

        val args1 = PlayerHook.rewriteArgIfMedia(listOf(vodUrl1), builder, btrConfig, onRewrite = null)
        val btrUrl1 = args1?.get(0) as? String
        assertTrue(btrUrl1?.startsWith("http://127.0.0.1:") == true)
        val sid1 = BtrSessionManager.getSessionForBuilder(builder)!!

        try {
            @Suppress("UNCHECKED_CAST")
            PlayerHook.interceptSetBackupUrls(
                builder = builder,
                method = setBackupMethod,
                rawArg = rawBackups,
                config = btrConfig,
                onRewrite = null,
                proceedOriginal = { builder.setBackupUrls(rawBackups) },
                proceedWithArg = { arg -> builder.setBackupUrls(arg as List<String>) }
            )
            assertEquals(listOf(btrUrl1!!), builder.backups)

            // Switching to a second VOD URL must update native backups from btrUrl1 to btrUrl2
            val args2 = PlayerHook.rewriteArgIfMedia(listOf(vodUrl2), builder, btrConfig, onRewrite = null)
            val btrUrl2 = args2?.get(0) as? String
            assertTrue(btrUrl2?.startsWith("http://127.0.0.1:") == true)
            assertFalse(btrUrl1 == btrUrl2)
            val sid2 = BtrSessionManager.getSessionForBuilder(builder)!!
            try {
                assertEquals(listOf(btrUrl2!!), builder.backups)

                // Switching to live with enableLiveAcceleration=false must restore clean backups
                assertNull(PlayerHook.rewriteArgIfMedia(listOf(liveUrl), builder, btrConfig, onRewrite = null))
                assertNull(BtrSessionManager.getSessionForBuilder(builder))
                assertFalse(builder.backups.any { it.contains("127.0.0.1:") })
                assertEquals(expectedRestored, builder.backups)
            } finally {
                BtrSessionManager.remove(sid2)
            }
        } finally {
            BtrSessionManager.remove(sid1)
        }
    }

    @Test
    fun testSmtcdnsWrappedPlayinfoDrmBypassesBtrAndRewrite() {
        val unwrappedDrmUrl = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/91111.m4s?deadline=1899999999"
        val wrappedDrmUrl = "https://cdn-edge.smtcdns.net/upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/91111.m4s?deadline=1899999999"
        val json = """{"code":0,"data":{"dash":{"video":[{"id":80,"baseUrl":"$unwrappedDrmUrl","is_drm":true}]}}}"""
        assertTrue(PlayinfoRegistry.ingestJson(json).isNotEmpty())

        val builder = FakeSegmentBuilder()
        val res = PlayerHook.rewriteArgIfMedia(
            args = listOf(wrappedDrmUrl),
            builderInstance = builder,
            configOverride = config.copy(enableBtr = true),
            onRewrite = null
        )
        assertNull("Unwrapped smtcdns URL matching Playinfo DRM must be left untouched", res)
        assertNull(BtrSessionManager.getSessionForBuilder(builder))
    }

    @Test
    fun testReentrantSetBackupUrlsDelegationDoesNotPollutePendingBackupsWithBtrLoopback() {
        val btrConfig = config.copy(enableBtr = true)
        val vodUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/92222.m4s?deadline=1899999999"
        val drmUrl = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/92223.m4s?drm_type=1"
        val rawBackups = listOf(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/92222.m4s",
            "http://112.34.56.78:8080/upgcxcode/54/12/92222.m4s"
        )
        val expectedRestored = PlayerHook.filterAndFillBackupUrls(rawBackups, btrConfig)
        val builder = FakeDelegatingSegmentBuilder()
        val arrayMethod = FakeDelegatingSegmentBuilder::class.java.getMethod("setBackupUrls", Array<String>::class.java)

        val rewrittenArgs = PlayerHook.rewriteArgIfMedia(listOf(vodUrl), builder, btrConfig, onRewrite = null)
        val btrUrl = rewrittenArgs?.get(0) as? String
        assertTrue(btrUrl?.startsWith("http://127.0.0.1:") == true)
        val sid = BtrSessionManager.getSessionForBuilder(builder)!!

        try {
            @Suppress("UNCHECKED_CAST")
            PlayerHook.interceptSetBackupUrls(
                builder = builder,
                method = arrayMethod,
                rawArg = rawBackups.toTypedArray(),
                config = btrConfig,
                onRewrite = null,
                proceedOriginal = { builder.setBackupUrls(rawBackups.toTypedArray()) },
                proceedWithArg = { arg -> builder.setBackupUrls(arg as Array<String>) }
            )
            assertEquals("Inner hooked overload must be suppressed during re-entrant delegation", 0, builder.innerHookInvocations)
            assertEquals(listOf(btrUrl!!), builder.backups)
            assertFalse(
                "Session backups must never be polluted by local BTR loopback URL",
                BtrSessionManager.get(sid)!!.backupUrls.any { it.contains("127.0.0.1:") }
            )

            // Transitioning out to DRM must restore original clean backups, not 127.0.0.1
            assertNull(PlayerHook.rewriteArgIfMedia(listOf(drmUrl), builder, btrConfig, onRewrite = null))
            assertFalse(builder.backups.any { it.contains("127.0.0.1:") })
            assertEquals(expectedRestored, builder.backups)
        } finally {
            BtrSessionManager.remove(sid)
        }
    }

    @Test
    fun testPlayinfoDrmBackupsAndProxyWrappedFallbackDataSourceRemainUntouched() {
        val drmBaseUrl = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/93333.m4s?deadline=1899999999"
        val drmPcdnBackup = "http://112.34.56.78:8080/upgcxcode/54/12/93333.m4s?deadline=1899999999"
        val smtcdnsWrappedDrm = "https://cdn-edge.smtcdns.net/upos-sz-mirrorali.bilivideo.com/upgcxcode/54/12/93333.m4s?deadline=1899999999"
        val mcdnDrmUrl = "https://xy1x2x3x4xy.mcdn.bilivideo.cn/v1/resource/93334.m4s?deadline=1899999999"
        val mcdnProxyDrm = "https://proxy-tf-all-ws.bilivideo.com/?url=https%3A%2F%2Fxy1x2x3x4xy.mcdn.bilivideo.cn%2Fv1%2Fresource%2F93334.m4s%3Fdeadline%3D1899999999"

        val json = """
            {"code":0,"data":{"dash":{"video":[
                {"id":80,"baseUrl":"$drmBaseUrl","backupUrl":["$drmPcdnBackup"],"is_drm":true},
                {"id":64,"baseUrl":"$mcdnDrmUrl","is_drm":true}
            ]}}}
        """.trimIndent()
        assertTrue(PlayinfoRegistry.ingestJson(json).isNotEmpty())
        assertTrue(PlayinfoRegistry.lookup(mcdnProxyDrm)?.hasDrm == true)
        assertTrue(PlayinfoRegistry.lookup(smtcdnsWrappedDrm)?.hasDrm == true)

        val builder = FakeSegmentBuilder()
        val setBackupMethod = FakeSegmentBuilder::class.java.getMethod("setBackupUrls", List::class.java)
        val rawBackups = listOf(drmBaseUrl, drmPcdnBackup)
        @Suppress("UNCHECKED_CAST")
        PlayerHook.interceptSetBackupUrls(
            builder = builder,
            method = setBackupMethod,
            rawArg = rawBackups,
            config = config.copy(enableBtr = true, forceUpos = true),
            onRewrite = null,
            proceedOriginal = { builder.setBackupUrls(rawBackups) },
            proceedWithArg = { arg -> builder.setBackupUrls(arg as List<String>) }
        )
        assertEquals("Playinfo DRM backups must not be rewritten, synthesized, or deferred", rawBackups, builder.backups)

        // Fallback IjkMediaPlayer path: smtcdns-wrapped and proxy-tf-all-wrapped Playinfo DRM must bypass BTR & rewrite
        val fakePlayer = Any()
        assertNull(
            PlayerHook.rewriteFallbackDataSource(
                original = smtcdnsWrappedDrm,
                playerInstance = fakePlayer,
                configOverride = config.copy(enableBtr = true),
                onRewrite = null
            )
        )
        assertNull(
            PlayerHook.rewriteFallbackDataSource(
                original = mcdnProxyDrm,
                playerInstance = fakePlayer,
                configOverride = config.copy(enableBtr = true),
                onRewrite = null
            )
        )

        // Non-DRM fallback VOD rewrites to BTR, and recycling same player for DRM unbinds it
        val vodFallback = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/54/12/93335.m4s?deadline=1899999999"
        val rewritten = PlayerHook.rewriteFallbackDataSource(
            original = vodFallback,
            playerInstance = fakePlayer,
            configOverride = config.copy(enableBtr = true),
            onRewrite = null
        )
        assertTrue(rewritten?.startsWith("http://127.0.0.1:") == true)
        val sid = BtrSessionManager.getSessionForBuilder(fakePlayer)!!
        try {
            assertNull(
                PlayerHook.rewriteFallbackDataSource(
                    original = smtcdnsWrappedDrm,
                    playerInstance = fakePlayer,
                    configOverride = config.copy(enableBtr = true),
                    onRewrite = null
                )
            )
            assertNull("Recycled fallback player must unbind BTR session on DRM", BtrSessionManager.getSessionForBuilder(fakePlayer))
        } finally {
            BtrSessionManager.remove(sid)
        }
    }
}
