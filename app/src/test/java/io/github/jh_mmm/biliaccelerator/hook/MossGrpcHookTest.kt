package io.github.jh_mmm.biliaccelerator.hook

import io.github.jh_mmm.biliaccelerator.core.AcceleratorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class MossGrpcHookTest {

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
}
