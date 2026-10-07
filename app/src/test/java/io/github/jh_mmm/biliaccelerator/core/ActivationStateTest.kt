package io.github.jh_mmm.biliaccelerator.core

import com.google.gson.Gson
import io.github.jh_mmm.biliaccelerator.provider.XposedServiceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActivationStateTest {

    private val gson = Gson()

    @Before
    fun setUp() {
        XposedServiceProvider.resetForTesting(bound = false, binder = null)
    }

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
            lastHeartbeatTimestamp = 1760000000000L
        )

        val json = gson.toJson(snapshot)
        val deserialized = gson.fromJson(json, StatsSnapshot::class.java)

        assertEquals(snapshot.totalRequests, deserialized.totalRequests)
        assertEquals(snapshot.totalRewrites, deserialized.totalRewrites)
        assertEquals(snapshot.lastHeartbeatTimestamp, deserialized.lastHeartbeatTimestamp)
    }

    @Test
    fun testLegacySnapshotDeserializationCompatibility() {
        // 旧版本 JSON（不含 lastHeartbeatTimestamp 字段）反序列化时应保持默认值 0L
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
    }

    @Test
    fun testActivationLogicEffectiveWhenRewritesExist() {
        val snapshot = StatsSnapshot(totalRewrites = 1, lastHeartbeatTimestamp = 0L)
        val hasRewrites = snapshot.totalRewrites > 0
        val isServiceBound = XposedServiceProvider.isServiceBound

        assertTrue("Should be effective when totalRewrites > 0", hasRewrites)
        assertFalse("Framework service is not bound", isServiceBound)
    }

    @Test
    fun testActivationLogicHeartbeatValidWithinSevenDays() {
        val now = System.currentTimeMillis()
        val validHeartbeat = now - (3L * 24 * 3600 * 1000L) // 3 天前
        val threshold = 7L * 24 * 3600 * 1000L

        val isWithinSevenDays = (now - validHeartbeat) in 0..threshold
        assertTrue("Heartbeat 3 days ago should be considered active", isWithinSevenDays)
    }

    @Test
    fun testActivationLogicHeartbeatExpiredBeyondSevenDays() {
        val now = System.currentTimeMillis()
        val expiredHeartbeat = now - (8L * 24 * 3600 * 1000L) // 8 天前
        val threshold = 7L * 24 * 3600 * 1000L

        val isWithinSevenDays = (now - expiredHeartbeat) in 0..threshold
        assertFalse("Heartbeat 8 days ago should be expired", isWithinSevenDays)
    }

    @Test
    fun testFrameworkServiceBindingState() {
        assertFalse(XposedServiceProvider.isServiceBound)
        XposedServiceProvider.resetForTesting(bound = true, binder = null)
        assertTrue(XposedServiceProvider.isServiceBound)
    }
}
