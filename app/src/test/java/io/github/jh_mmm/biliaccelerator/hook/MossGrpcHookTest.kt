package io.github.jh_mmm.biliaccelerator.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class MossGrpcHookTest {

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

    private enum class DummyTfEnum {
        DEFAULT, MIRROR
    }

    private class DummyMossContext

    private class DummyRuntimeHelper {
        fun tf(featureKey: String): Boolean = false
        fun tf(flagId: Int): Boolean = false
        fun tf(ctx: DummyMossContext): DummyTfEnum = DummyTfEnum.DEFAULT
    }

    @Test
    fun testIsSupportedReturnTypeAndNarrowedParameterCheck() {
        assertTrue("Boolean primitive", MossGrpcHook.isSupportedReturnType(java.lang.Boolean.TYPE))
        assertTrue("Boolean boxed", MossGrpcHook.isSupportedReturnType(java.lang.Boolean::class.java))
        assertTrue("String", MossGrpcHook.isSupportedReturnType(String::class.java))
        assertTrue("Int primitive", MossGrpcHook.isSupportedReturnType(java.lang.Integer.TYPE))
        assertTrue("Long boxed", MossGrpcHook.isSupportedReturnType(java.lang.Long::class.java))
        assertTrue("Enum", MossGrpcHook.isSupportedReturnType(DummyTfEnum::class.java))
        assertFalse("Void", MossGrpcHook.isSupportedReturnType(java.lang.Void.TYPE))
        assertFalse("Object", MossGrpcHook.isSupportedReturnType(Any::class.java))

        // 验证通用 Feature Flag 参数类型（String / Int / Object）被严格排除，仅允许非通用业务上下文对象
        val stringMethod = DummyRuntimeHelper::class.java.getDeclaredMethod("tf", String::class.java)
        val intMethod = DummyRuntimeHelper::class.java.getDeclaredMethod("tf", Int::class.javaPrimitiveType)
        val ctxMethod = DummyRuntimeHelper::class.java.getDeclaredMethod("tf", DummyMossContext::class.java)

        assertFalse("tf(String) feature flag lookup must be rejected by strict candidate check", MossGrpcHook.isCandidateTfMethod(stringMethod, DummyRuntimeHelper::class.java))
        assertTrue("tf(String) fallback check matches when no domain overload exists", MossGrpcHook.isStringKeyedTfMethod(stringMethod, DummyRuntimeHelper::class.java))
        assertFalse("tf(int) primitive lookup must be rejected", MossGrpcHook.isCandidateTfMethod(intMethod, DummyRuntimeHelper::class.java))
        assertTrue("tf(CustomContext) with enum return must be accepted", MossGrpcHook.isCandidateTfMethod(ctxMethod, DummyRuntimeHelper::class.java))

        // 验证运行期入参过滤：String 仅放行明确携带 tf/mirror/upos/pcdn 等流量关键字的 key，拒绝通用 UI/AB 开关
        assertTrue(MossGrpcHook.shouldAllowRuntimeArg("player.upos.mirror_tf"))
        assertTrue(MossGrpcHook.shouldAllowRuntimeArg("moss_traffic_pcdn"))
        assertFalse(MossGrpcHook.shouldAllowRuntimeArg("ab_test_home_feed_redesign"))
        assertFalse(MossGrpcHook.shouldAllowRuntimeArg("dark_mode_enabled"))
        assertTrue(MossGrpcHook.shouldAllowRuntimeArg(DummyMossContext()))
    }
}

