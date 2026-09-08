package com.example.sdnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LcmSchedulerTest {

    @Test
    fun testScheduleTimestepsDescendingFor4Steps() {
        val schedule = LcmScheduler.getSchedule(4)
        assertEquals(4, schedule.timesteps.size)
        // Check timesteps are strictly descending
        for (i in 0 until schedule.timesteps.size - 1) {
            assertTrue("Timesteps must be descending", schedule.timesteps[i] > schedule.timesteps[i + 1])
        }
        assertEquals(999L, schedule.timesteps[0])
    }

    @Test
    fun testBoundaryConditionScalings() {
        // At t = 0, cSkip -> 1.0, cOut -> 0.0
        val (cSkipZero, cOutZero) = LcmScheduler.getBoundaryScalings(0L)
        assertEquals(1.0f, cSkipZero, 0.001f)
        assertEquals(0.0f, cOutZero, 0.001f)

        // At large t, cSkip -> 0.0, cOut -> 1.0
        val (cSkipLarge, cOutLarge) = LcmScheduler.getBoundaryScalings(999L)
        assertTrue(cSkipLarge < 0.01f)
        assertTrue(cOutLarge > 0.99f)
    }

    @Test
    fun testGuidanceEmbeddingDimension() {
        val emb = LcmScheduler.getGuidanceEmbedding(1.0f, 256)
        assertEquals(256, emb.size)
        // Verify values are bounded in [-1.0, 1.0]
        assertTrue(emb.all { it in -1.0f..1.0f })
    }

    @Test
    fun testGuidanceEmbeddingScale1Values() {
        val emb = LcmScheduler.getGuidanceEmbedding(1.0f, 256)
        // For guidanceScale = 1.0f, w = (1.0 - 1.0) * 1000 = 0
        // sin(0) = 0, cos(0) = 1
        for (i in 0 until 128) {
            assertEquals(0.0f, emb[i], 1e-5f)
            assertEquals(1.0f, emb[i + 128], 1e-5f)
        }
    }

    @Test
    fun testStepCalculationFinalStepOmitsNoise() {
        val schedule = LcmScheduler.getSchedule(4)
        val sample = FloatArray(16) { 1.0f }
        val modelOutput = FloatArray(16) { 0.1f }
        val outSample = FloatArray(16)
        val noise = FloatArray(16) { 999.0f } // Extreme noise that should NOT be used on final step

        // Step index 3 is final step for 4-step schedule
        LcmScheduler.step(sample, modelOutput, 3, schedule, noise, outSample)

        // Verify output is finite and did not inject the extreme noise
        assertTrue(outSample.none { abs(it) > 100f })
    }

    @Test
    fun testStepCalculationIntermediateStepInjectsNoise() {
        val schedule = LcmScheduler.getSchedule(4)
        val sample = FloatArray(16) { 1.0f }
        val modelOutput = FloatArray(16) { 0.1f }
        val outSampleWithNoise = FloatArray(16)
        val outSampleWithoutNoise = FloatArray(16)
        val noise = FloatArray(16) { 5.0f }

        // Step index 0 is intermediate step
        LcmScheduler.step(sample, modelOutput, 0, schedule, noise, outSampleWithNoise)
        LcmScheduler.step(sample, modelOutput, 0, schedule, null, outSampleWithoutNoise)

        // With noise, values should be significantly different from without noise
        for (i in 0 until 16) {
            assertTrue(abs(outSampleWithNoise[i] - outSampleWithoutNoise[i]) > 0.1f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testGuidanceEmbeddingOddDimensionThrows() {
        LcmScheduler.getGuidanceEmbedding(1.0f, 255)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testGuidanceEmbeddingTooSmallDimensionThrows() {
        LcmScheduler.getGuidanceEmbedding(1.0f, 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testStepInsufficientNoiseSizeThrows() {
        val schedule = LcmScheduler.getSchedule(4)
        val sample = FloatArray(16) { 1.0f }
        val modelOutput = FloatArray(16) { 0.1f }
        val outSample = FloatArray(16)
        val insufficientNoise = FloatArray(8) { 1.0f } // size 8 < count 16

        LcmScheduler.step(sample, modelOutput, 0, schedule, insufficientNoise, outSample)
    }
}

