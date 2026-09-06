package com.example.sdnpu.model

import com.example.sdnpu.engine.QnnNativeBridge
import org.junit.Assert.*
import org.junit.Test

class QuantizationConfigTest {

    @Test
    fun testDefaultQuantizationProfiles() {
        val sdBase = QuantizationConfig.getProfileForModel("dreamshaper_v8_base")
        assertEquals("dreamshaper_v8_base", sdBase.modelName)
        assertEquals(QuantizationPrecision.INT8, sdBase.weightPrecision)
        assertEquals(QuantizationPrecision.INT8, sdBase.activationPrecision)
        assertTrue("SD base memory footprint should be 1500-2000MB", sdBase.memoryFootprintMb in 1500..2000)

        val esrgan = QuantizationConfig.getProfileForModel("realesrgan_x2plus")
        assertEquals("realesrgan_x2plus", esrgan.modelName)
        assertEquals(QuantizationPrecision.INT8, esrgan.weightPrecision)
        assertEquals(QuantizationPrecision.INT8, esrgan.activationPrecision)
        assertTrue("RealESRGAN memory footprint should be < 400MB", esrgan.memoryFootprintMb < 400)
    }

    @Test
    fun testHtpPowerProfileMapping() {
        assertEquals(0, HtpPowerProfile.DEFAULT.ordinal)
        assertEquals(1, HtpPowerProfile.HIGH_PERFORMANCE.ordinal)
        assertEquals(2, HtpPowerProfile.BURST.ordinal)
        assertEquals(3, HtpPowerProfile.POWER_SAVER.ordinal)

        assertEquals("Default Balanced", HtpPowerProfile.DEFAULT.displayName)
        assertEquals("BALANCED", HtpPowerProfile.DEFAULT.dcvsCorner)

        assertEquals("High Performance", HtpPowerProfile.HIGH_PERFORMANCE.displayName)
        assertEquals("TURBO", HtpPowerProfile.HIGH_PERFORMANCE.dcvsCorner)

        assertEquals("Burst Maximum", HtpPowerProfile.BURST.displayName)
        assertEquals("TURBO_BURST", HtpPowerProfile.BURST.dcvsCorner)

        assertEquals("Power Saver", HtpPowerProfile.POWER_SAVER.displayName)
        assertEquals("SVS2", HtpPowerProfile.POWER_SAVER.dcvsCorner)
    }

    @Test
    fun testQuantizationPrecisionValues() {
        val precisions = QuantizationPrecision.values().map { it.name }
        assertTrue(precisions.contains("INT8"))
        assertTrue(precisions.contains("INT16"))
        assertTrue(precisions.contains("FP16"))
        assertEquals(3, precisions.size)
    }

    @Test
    fun testNativeBridgePerformanceProfileFallback() {
        // When running on standard JVM without native library loaded, bridge gracefully falls back to true
        val resultInt = QnnNativeBridge.setHtpPerformanceProfile(1)
        assertTrue(resultInt)

        val resultEnum = QnnNativeBridge.setHtpPerformanceProfile(HtpPowerProfile.BURST)
        assertTrue(resultEnum)
    }
}
