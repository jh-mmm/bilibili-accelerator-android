package io.github.jh_mmm.biliaccelerator.core

import android.os.IBinder
import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.hook.RemoteClient
import io.github.jh_mmm.biliaccelerator.provider.RewriteResultDto
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import io.github.jh_mmm.biliaccelerator.ui.MainActivity
import io.github.jh_mmm.biliaccelerator.ui.MainActivity.ActivationState
import io.github.jh_mmm.biliaccelerator.ui.dashboard.logItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationStateTest {

    private val gson = Gson()

    @Test
    fun testStatsSnapshotSerializationWithHeartbeat() {
        val snapshot = StatsSnapshot(
            totalRequests = 100,
            totalRewrites = 20,
            pcdnBlocked = 10,
            mcdnProxied = 5,
            avoidedHosts = listOf("pcdn.sz.bili.com"),
            recentLogs = emptyList(),
            installedAt = "2026-10-07 18:00:00",
            lastHeartbeatTimestamp = 1760000000000L,
            isFrameworkActivated = true
        )

        val json = gson.toJson(snapshot)
        val deserialized = gson.fromJson(json, StatsSnapshot::class.java)

        assertEquals(snapshot.totalRequests, deserialized.totalRequests)
        assertEquals(snapshot.totalRewrites, deserialized.totalRewrites)
        assertEquals(snapshot.lastHeartbeatTimestamp, deserialized.lastHeartbeatTimestamp)
        assertTrue(deserialized.isFrameworkActivated)
    }

    @Test
    fun testLegacySnapshotDeserializationCompatibility() {
        val legacyJson = """
            {
                "totalRequests": 50,
                "totalRewrites": 10,
                "pcdnBlocked": 2,
                "mcdnProxied": 1,
                "avoidedHosts": [],
                "recentLogs": [],
                "installedAt": "2026-10-01 10:00:00"
            }
        """.trimIndent()

        val deserialized = gson.fromJson(legacyJson, StatsSnapshot::class.java)
        assertEquals(50L, deserialized.totalRequests)
        assertEquals(10L, deserialized.totalRewrites)
        assertEquals(0L, deserialized.lastHeartbeatTimestamp)
        assertFalse(deserialized.isFrameworkActivated)
    }

    @Test
    fun testCalculateActivationStateInactiveOnlyWhenNoCredentials() {
        val now = 1_760_000_000_000L
        // 全新状态：无自注入、无框架服务、无心跳、无历史激活记录与重写 -> 才是真正 INACTIVE
        val stateClean = MainActivity.calculateActivationState(
            totalRewrites = 0L,
            isServiceBound = false,
            lastHeartbeat = 0L,
            isSelfInjected = false,
            now = now
        )
        assertEquals("Clean installation without framework credentials must be INACTIVE", ActivationState.INACTIVE, stateClean)
    }

    @Test
    fun testCalculateActivationStateSelfInjectedInLsposedScope() {
        val now = 1_760_000_000_000L
        // LSPosed 开启并勾选作用域后自注入生效：即使尚未运行哔哩哔哩且无流量 -> 必须直接处于激活待命中状态 (ACTIVE_HEARTBEAT)
        val stateSelfInjected = MainActivity.calculateActivationState(
            totalRewrites = 0L,
            isServiceBound = false,
            lastHeartbeat = 0L,
            isSelfInjected = true,
            now = now
        )
        assertEquals(
            "LSPosed scope enabled with self-injection must show activated state",
            ActivationState.ACTIVE_HEARTBEAT,
            stateSelfInjected
        )
    }

    @Test
    fun testCalculateActivationStateLsposedScopeBoundWaitingForTraffic() {
        val now = 1_760_000_000_000L

        // 勾选即等待拦截：LSPosed 中启用模块后，服务实时在线绑定且作用域包含目标应用 (isServiceBound = true, hasFrameworkScope = true)，
        // 在未产生视频重写时必须判定为“等待拦截” (ACTIVE_HEARTBEAT)
        val stateWaitingForTraffic = MainActivity.calculateActivationState(
            totalRewrites = 0L,
            isServiceBound = true,
            lastHeartbeat = 0L,
            isSelfInjected = false,
            hasFrameworkScope = true,
            now = now
        )
        assertEquals(
            "LSPosed enabled with service bound and target scope must be ACTIVE_HEARTBEAT (等待拦截)",
            ActivationState.ACTIVE_HEARTBEAT,
            stateWaitingForTraffic
        )

        // 产生视频重定向后，状态流转为已生效 (ACTIVE_EFFECTIVE)
        val stateEffective = MainActivity.calculateActivationState(
            totalRewrites = 10L,
            isServiceBound = true,
            lastHeartbeat = 0L,
            isSelfInjected = false,
            hasFrameworkScope = true,
            now = now
        )
        assertEquals(
            "Service bound with rewrites must be ACTIVE_EFFECTIVE (已生效)",
            ActivationState.ACTIVE_EFFECTIVE,
            stateEffective
        )

        // 若服务在线但作用域未勾选目标应用 (hasFrameworkScope = false)，无其他凭据时必须回落至未激活 (INACTIVE)
        val stateWithoutTargetScope = MainActivity.calculateActivationState(
            totalRewrites = 0L,
            isServiceBound = true,
            lastHeartbeat = 0L,
            isSelfInjected = false,
            hasFrameworkScope = false,
            now = now
        )
        assertEquals(
            "Service bound without target scope must be INACTIVE (未激活)",
            ActivationState.INACTIVE,
            stateWithoutTargetScope
        )
    }

    @Test
    fun testCalculateActivationStateModuleDisabledInLsposedBecomesInactiveWithoutHistoricalPersistence() {
        val now = 1_760_000_000_000L
        val expiredHeartbeat = now - MainActivity.DEFAULT_HEARTBEAT_THRESHOLD_MS - 1_000L

        // 关闭即未激活：在 LSPosed 中禁用模块后，服务断开 (isServiceBound = false)，
        // 即使本地曾有历史重定向记录，也不得误判为已激活，必须立即判定为未激活 (INACTIVE)
        val stateDisabledWithHistory = MainActivity.calculateActivationState(
            totalRewrites = 10L,
            isServiceBound = false,
            lastHeartbeat = expiredHeartbeat,
            isSelfInjected = false,
            now = now
        )
        assertEquals(
            "Disabled module must show INACTIVE even if historical rewrites exist",
            ActivationState.INACTIVE,
            stateDisabledWithHistory
        )

        val stateDisabledWithoutRewrites = MainActivity.calculateActivationState(
            totalRewrites = 0L,
            isServiceBound = false,
            lastHeartbeat = expiredHeartbeat,
            isSelfInjected = false,
            now = now
        )
        assertEquals(
            "Disabled module must show INACTIVE regardless of historical state",
            ActivationState.INACTIVE,
            stateDisabledWithoutRewrites
        )
    }

    @Test
    fun testCalculateActivationStateLiveWithRewritesMaintainsEffective() {
        val now = 1_760_000_000_000L
        val recentHeartbeat = now - 60_000L // 1 分钟前

        // 宿主在近期心跳窗口内活跃且有重定向 -> ACTIVE_EFFECTIVE
        val stateRecentHb = MainActivity.calculateActivationState(
            totalRewrites = 20L,
            isServiceBound = false,
            lastHeartbeat = recentHeartbeat,
            isSelfInjected = false,
            now = now
        )
        assertEquals("Recent heartbeat with rewrites must be ACTIVE_EFFECTIVE", ActivationState.ACTIVE_EFFECTIVE, stateRecentHb)

        // 模块自身被框架注入且有历史重定向 -> ACTIVE_EFFECTIVE
        val stateSelfInjectedWithRewrites = MainActivity.calculateActivationState(
            totalRewrites = 20L,
            isServiceBound = false,
            lastHeartbeat = 0L,
            isSelfInjected = true,
            now = now
        )
        assertEquals("Self-injected module with rewrites must be ACTIVE_EFFECTIVE", ActivationState.ACTIVE_EFFECTIVE, stateSelfInjectedWithRewrites)
    }

    @Test
    fun testCalculateActivationStateActiveEffectiveAndHeartbeat() {
        val now = 1_760_000_000_000L
        val recentHeartbeat = now - 60_000L // 1 分钟前

        // 服务已绑定但尚无重写 -> ACTIVE_HEARTBEAT
        assertEquals(
            ActivationState.ACTIVE_HEARTBEAT,
            MainActivity.calculateActivationState(totalRewrites = 0L, isServiceBound = true, lastHeartbeat = 0L, now = now)
        )

        // 近期有心跳但尚无重写 -> ACTIVE_HEARTBEAT
        assertEquals(
            ActivationState.ACTIVE_HEARTBEAT,
            MainActivity.calculateActivationState(totalRewrites = 0L, isServiceBound = false, lastHeartbeat = recentHeartbeat, now = now)
        )

        // 近期有心跳且有重写 -> ACTIVE_EFFECTIVE
        assertEquals(
            ActivationState.ACTIVE_EFFECTIVE,
            MainActivity.calculateActivationState(totalRewrites = 5L, isServiceBound = false, lastHeartbeat = recentHeartbeat, now = now)
        )

        // 服务已绑定且有重写 -> ACTIVE_EFFECTIVE
        assertEquals(
            ActivationState.ACTIVE_EFFECTIVE,
            MainActivity.calculateActivationState(totalRewrites = 5L, isServiceBound = true, lastHeartbeat = 0L, now = now)
        )
    }

    @Test
    fun testFrameworkServiceBindingDefaultState() {
        XposedServiceProvider.resetForTesting()
        assertFalse("Initial framework service binding state must be false", XposedServiceProvider.isServiceBound)
    }

    @Test
    fun testXposedServiceProviderCallerAuthorization() {
        // 关键改动：放行 root (uid = 0，LSPosed 守护进程/系统服务)
        assertTrue("Root caller (uid=0) must be authorized", XposedServiceProvider.isCallerAuthorized(null, 0))

        // 放行 system_server (uid = 1000)
        assertTrue("System caller (uid=1000) must be authorized", XposedServiceProvider.isCallerAuthorized(null, 1000))

        // 拒绝任意未授权第三方普通应用 UID
        assertFalse("Unauthorized uid must be rejected", XposedServiceProvider.isCallerAuthorized(null, 99999))
    }

    @Test
    fun testXposedServiceProviderBinderVerificationAndLifecycle() {
        XposedServiceProvider.resetForTesting()
        assertFalse("Initially unbound", XposedServiceProvider.isServiceBound)

        val validBinder = java.lang.reflect.Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getInterfaceDescriptor" -> XposedServiceProvider.DESCRIPTOR
                "isBinderAlive" -> true
                "pingBinder" -> true
                else -> null
            }
        } as IBinder

        val invalidBinder = java.lang.reflect.Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getInterfaceDescriptor" -> "com.invalid.service.Fake"
                "isBinderAlive" -> true
                "pingBinder" -> true
                else -> null
            }
        } as IBinder

        val deadBinder = java.lang.reflect.Proxy.newProxyInstance(
            IBinder::class.java.classLoader,
            arrayOf(IBinder::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getInterfaceDescriptor" -> XposedServiceProvider.DESCRIPTOR
                "isBinderAlive" -> false
                "pingBinder" -> false
                else -> null
            }
        } as IBinder

        // 校验合法的 IXposedService Binder (descriptor == io.github.libxposed.service.IXposedService)
        assertTrue("Valid IXposedService binder must pass verification", XposedServiceProvider.verifyLibXposedBinder(validBinder))

        // 拒绝非法 descriptor
        assertFalse("Invalid descriptor binder must be rejected", XposedServiceProvider.verifyLibXposedBinder(invalidBinder))

        // 拒绝死亡 binder
        assertFalse("Dead binder must be rejected", XposedServiceProvider.verifyLibXposedBinder(deadBinder))

        // 测试绑定状态流转：设置存活 binder 后 isServiceBound 为 true，并同步作用域
        XposedServiceProvider.setServiceBoundForTesting(true, validBinder, listOf("tv.danmaku.bili"))
        assertTrue("Service bound state must be true", XposedServiceProvider.isServiceBound)
        val snapshotWithScope = StatsManager.getSnapshot()
        assertTrue("Scope must be marked queried", snapshotWithScope.hasQueriedScope)
        assertTrue("Scope must detect target package", snapshotWithScope.hasTargetScope)
        assertEquals(listOf("tv.danmaku.bili"), snapshotWithScope.frameworkScope)

        // 测试 Binder 死亡回调：重置 isServiceBound 并清空作用域
        XposedServiceProvider.triggerBinderDiedForTesting()
        assertFalse("Service bound state must be false after binder died", XposedServiceProvider.isServiceBound)
        val snapshotAfterDeath = StatsManager.getSnapshot()
        assertFalse("Scope queried flag must be false after binder died", snapshotAfterDeath.hasQueriedScope)
        assertFalse("Target scope flag must be false after binder died", snapshotAfterDeath.hasTargetScope)
        assertTrue("Scope list must be empty after binder died", snapshotAfterDeath.frameworkScope.isEmpty())

        // 重置/解绑后置回 false
        XposedServiceProvider.resetForTesting()
        assertFalse("Service bound state must be false after reset", XposedServiceProvider.isServiceBound)
    }

    @Test
    fun testStatsManagerFrameworkScopeManagement() {
        StatsManager.clearFrameworkScope()
        var snapshot = StatsManager.getSnapshot()
        assertFalse(snapshot.hasQueriedScope)
        assertFalse(snapshot.hasTargetScope)
        assertTrue(snapshot.frameworkScope.isEmpty())

        // 记录包含 B 站的作用域列表
        StatsManager.recordFrameworkScope(listOf("com.other.app", "tv.danmaku.bili"))
        snapshot = StatsManager.getSnapshot()
        assertTrue(snapshot.hasQueriedScope)
        assertTrue("Must detect tv.danmaku.bili as target package", snapshot.hasTargetScope)
        assertEquals(listOf("com.other.app", "tv.danmaku.bili"), snapshot.frameworkScope)

        // 记录不包含 B 站的作用域列表
        StatsManager.recordFrameworkScope(listOf("com.other.app"))
        snapshot = StatsManager.getSnapshot()
        assertTrue(snapshot.hasQueriedScope)
        assertFalse("com.other.app is not a target package", snapshot.hasTargetScope)

        // 清空作用域
        StatsManager.clearFrameworkScope()
        snapshot = StatsManager.getSnapshot()
        assertFalse(snapshot.hasQueriedScope)
        assertFalse(snapshot.hasTargetScope)
        assertTrue(snapshot.frameworkScope.isEmpty())
    }

    @Test
    fun testLogItemKeyIsStableAcrossInsertion() {
        val log1 = RewriteLogEntry(
            id = "uuid-1",
            timestamp = 1760000000000L,
            timeFormatted = "10-09 18:00:00",
            originalHost = "112.34.56.78",
            targetHost = "upos-sz-mirrorcos.bilivideo.com",
            reason = "pcdn-host",
            isPcdn = true,
            isMcdn = false
        )
        val log2 = RewriteLogEntry(
            id = "uuid-2",
            timestamp = 1760000001000L,
            timeFormatted = "10-09 18:00:01",
            originalHost = "112.34.56.78",
            targetHost = "upos-sz-mirrorcos.bilivideo.com",
            reason = "pcdn-host",
            isPcdn = true,
            isMcdn = false
        )

        // 插入新日志到列表头部后，log1 的 UI key 必须保持完全不变（不随下标偏移改变）
        val keyBeforeInsert = logItemKey(log1)
        val listAfterInsert = listOf(log2, log1)
        val keyAfterInsert = logItemKey(listAfterInsert[1])

        assertEquals("uuid-1", keyBeforeInsert)
        assertEquals("Key must remain stable regardless of list index", keyBeforeInsert, keyAfterInsert)
    }

    @Test
    fun testDuplicateLogsDeserializationSanitizationViaStatsManager() {
        val legacyJson = """
            [
                {
                    "timestamp": 1760000000000,
                    "timeFormatted": "10-09 18:00:00",
                    "originalHost": "112.34.56.78",
                    "targetHost": "upos-sz-mirrorcos.bilivideo.com",
                    "reason": "pcdn-host",
                    "isPcdn": true,
                    "isMcdn": false
                },
                {
                    "timestamp": 1760000000000,
                    "timeFormatted": "10-09 18:00:00",
                    "originalHost": "112.34.56.78",
                    "targetHost": "upos-sz-mirrorcos.bilivideo.com",
                    "reason": "pcdn-host",
                    "isPcdn": true,
                    "isMcdn": false
                }
            ]
        """.trimIndent()

        val listType = object : com.google.gson.reflect.TypeToken<List<RewriteLogEntry>>() {}.type
        val list: List<RewriteLogEntry> = gson.fromJson(legacyJson, listType)

        // 直接调用 StatsManager.sanitizeLogEntries 生产方法
        val sanitizedList = StatsManager.sanitizeLogEntries(list)

        assertEquals(2, sanitizedList.size)
        assertTrue("Sanitized ID must not be blank", sanitizedList[0].id.isNotBlank())
        assertTrue("Sanitized ID must not be blank", sanitizedList[1].id.isNotBlank())
        assertTrue("IDs must be distinct to prevent collisions", sanitizedList[0].id != sanitizedList[1].id)
        assertTrue("UI keys must be distinct", logItemKey(sanitizedList[0]) != logItemKey(sanitizedList[1]))
    }

    @Test
    fun testIpcPrivacyRedactsFullSignedUrls() {
        val sensitiveUrl = "http://112.34.56.78:8080/upgcxcode/54/12/34567.m4s?mid=99999&buvid=XY123&upsig=secret_token"
        val res = BiliAcceleratorCore.rewriteUrl(sensitiveUrl, AcceleratorConfig())
        assertTrue(res.originalUrl.contains("secret_token"))

        val sanitized = RemoteClient.sanitizeForIpc(res)
        assertEquals("originalUrl must be stripped before IPC", "", sanitized.originalUrl)
        assertEquals("finalUrl must be stripped before IPC", "", sanitized.finalUrl)
        assertEquals("112.34.56.78", sanitized.originalHost)
        assertEquals("upos-sz-mirrorcos.bilivideo.com", sanitized.targetHost)

        val json = gson.toJson(sanitized)
        assertFalse("Serialized IPC payload must never contain token or mid", json.contains("secret_token") || json.contains("99999"))

        val dto = gson.fromJson(json, RewriteResultDto::class.java)
        val domain = dto.toDomain()!!
        assertEquals("", domain.originalUrl)
        assertEquals("", domain.finalUrl)
        assertEquals("112.34.56.78", domain.originalHost)
        assertEquals("34567.m4s", domain.segmentKey)
    }

    @Test
    fun testMultiProcessFilteringAndCrossProcessStatsDedup() {
        val pkg = "tv.danmaku.bili"
        assertTrue(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, pkg))
        assertTrue(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:ijkservice"))
        assertTrue(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:player"))
        assertFalse(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:push"))
        assertFalse(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:crash"))
        assertFalse(io.github.jh_mmm.biliaccelerator.hook.HookEntry.isRelevantProcess(pkg, "$pkg:miniapp"))

        // Cross-process segment deduplication within 10s window
        val segUrl = "http://112.34.56.78:8080/upgcxcode/11/22/999888-1-30080.m4s?sign=1"
        val res = BiliAcceleratorCore.rewriteUrl(segUrl, AcceleratorConfig())
        val now = 1_760_000_000_000L
        assertTrue(StatsManager.shouldAcceptCrossProcessSegment(res, now))
        assertFalse(
            "Duplicate segment from secondary process within 10s window must be deduplicated",
            StatsManager.shouldAcceptCrossProcessSegment(res, now + 1500L)
        )
        assertTrue(
            "Segment after 10s window should be accepted again",
            StatsManager.shouldAcceptCrossProcessSegment(res, now + 15_000L)
        )

        // Hook status protection: secondary process reporting "未找到" must not overwrite main process success
        val merged = StatsManager.mergeHookStatusEntry(
            existing = "tv.danmaku.ijk.media.player.IjkMediaAsset\$MediaAssertSegment\$Builder (6 hooks)",
            incoming = "未找到 (版本不兼容)"
        )
        assertTrue(merged.contains("6 hooks"))
    }

    @Test
    fun testUposDisplayNameAndUiStyleLabel() {
        val entries = listOf(
            "腾讯云 (国内) - upos-sz-mirrorcos",
            "阿里云 (国内) - upos-sz-mirrorali",
            "华为云 (国内) - upos-sz-mirrorhw",
            "华为云 (全国混流) - upos-tf-all-hw"
        )
        val values = listOf(
            "upos-sz-mirrorcos.bilivideo.com",
            "upos-sz-mirrorali.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com",
            "upos-tf-all-hw.bilivideo.com"
        )

        assertEquals(
            "阿里云国内",
            io.github.jh_mmm.biliaccelerator.ui.dashboard.resolveUposDisplayName(
                "upos-sz-mirrorali.bilivideo.com",
                entries,
                values
            )
        )
        assertEquals(
            "华为云国内",
            io.github.jh_mmm.biliaccelerator.ui.dashboard.resolveUposDisplayName(
                "upos-sz-mirrorhw.bilivideo.com",
                entries,
                values
            )
        )
        assertEquals(
            "腾讯云国内",
            io.github.jh_mmm.biliaccelerator.ui.dashboard.resolveUposDisplayName(
                "upos-sz-mirrorcos.bilivideo.com"
            )
        )
        assertEquals(
            "MIUIX",
            io.github.jh_mmm.biliaccelerator.ui.theme.UiStyle.MIUIX.label
        )
    }
}

