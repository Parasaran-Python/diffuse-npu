package com.example.sdnpu.pipeline

import org.junit.Assert.*
import org.junit.Test

class GenerationParamsTest {
    @Test
    fun testDefaultParamsAreValid() {
        val params = GenerationParams(
            prompt = "a serene mountain lake at sunrise, highly detailed"
        )
        val validation = params.validate()
        assertTrue(validation.isValid)
        assertEquals("sdturbo", params.modelId)
        assertEquals(1, params.steps)
        assertEquals(1.0f, params.cfgScale, 0.001f)
        assertEquals(UpscaleMode.OFF, params.upscaleMode)
        assertEquals(SamplerType.EULER_A, params.sampler)
    }

    @Test
    fun testEmptyPromptFailsValidation() {
        val params = GenerationParams(prompt = "   ")
        val validation = params.validate()
        assertFalse(validation.isValid)
        assertEquals("Prompt cannot be empty", validation.errorMessage)
    }

    @Test
    fun testInvalidStepRangeFailsValidation() {
        val paramsLow = GenerationParams(prompt = "cat", steps = 0)
        val validationLow = paramsLow.validate()
        assertFalse(validationLow.isValid)
        assertEquals("Steps must be between 1 and 50", validationLow.errorMessage)

        val paramsHigh = GenerationParams(prompt = "cat", steps = 55)
        val validationHigh = paramsHigh.validate()
        assertFalse(validationHigh.isValid)
        assertEquals("Steps must be between 1 and 50", validationHigh.errorMessage)
    }

    @Test
    fun testInvalidCfgScaleFailsValidation() {
        val paramsLow = GenerationParams(prompt = "cat", cfgScale = -0.5f)
        val validationLow = paramsLow.validate()
        assertFalse(validationLow.isValid)
        assertEquals("CFG scale must be between 0.0 and 20.0", validationLow.errorMessage)

        val paramsHigh = GenerationParams(prompt = "cat", cfgScale = 25.0f)
        val validationHigh = paramsHigh.validate()
        assertFalse(validationHigh.isValid)
        assertEquals("CFG scale must be between 0.0 and 20.0", validationHigh.errorMessage)
    }

    @Test
    fun testInvalidBatchCountFailsValidation() {
        val paramsZero = GenerationParams(prompt = "cat", batchCount = 0)
        val validationZero = paramsZero.validate()
        assertFalse(validationZero.isValid)
        assertEquals("Batch count must be between 1 and 4", validationZero.errorMessage)

        val paramsHigh = GenerationParams(prompt = "cat", batchCount = 5)
        val validationHigh = paramsHigh.validate()
        assertFalse(validationHigh.isValid)
        assertEquals("Batch count must be between 1 and 4", validationHigh.errorMessage)
    }

    @Test
    fun testBoundaryConditionsAreValid() {
        val minBoundary = GenerationParams(
            prompt = "cat",
            steps = 1,
            cfgScale = 0.0f,
            batchCount = 1
        )
        assertTrue(minBoundary.validate().isValid)

        val maxBoundary = GenerationParams(
            prompt = "cat",
            steps = 50,
            cfgScale = 20.0f,
            batchCount = 4
        )
        assertTrue(maxBoundary.validate().isValid)
    }

    @Test
    fun testSamplerTypeDisplayNames() {
        assertEquals("Euler a", SamplerType.EULER_A.displayName)
        assertEquals("DPM++ 2M Karras", SamplerType.DPM_2M_KARRAS.displayName)
        assertEquals("DPM++ SDE Karras", SamplerType.DPM_SDE_KARRAS.displayName)
        assertEquals("DDIM", SamplerType.DDIM.displayName)
    }

    @Test
    fun testUpscaleModeProperties() {
        assertEquals(1, UpscaleMode.OFF.scale)
        assertEquals("Off", UpscaleMode.OFF.displayName)
        assertEquals(2, UpscaleMode.X2.scale)
        assertEquals("RealESRGAN 2x", UpscaleMode.X2.displayName)
        assertEquals(4, UpscaleMode.X4.scale)
        assertEquals("RealESRGAN 4x", UpscaleMode.X4.displayName)
    }
}
