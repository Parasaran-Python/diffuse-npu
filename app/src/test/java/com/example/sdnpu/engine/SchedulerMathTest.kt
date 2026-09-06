package com.example.sdnpu.engine

import org.junit.Assert.*
import org.junit.Test

class SchedulerMathTest {
    @Test
    fun testEulerTimestepsCalculation() {
        val steps = 20
        val timesteps = FloatArray(steps) { i -> 999f - (i * (1000f / steps)) }
        assertEquals(20, timesteps.size)
        assertTrue(timesteps[0] > timesteps[19])
        assertTrue(timesteps[19] >= 0f)
    }

    @Test
    fun testTimestepsMonotonicallyDecreasing() {
        val steps = 20
        val timesteps = FloatArray(steps) { i -> 999f * (1.0f - i.toFloat() / steps.toFloat()) }
        assertEquals(20, timesteps.size)
        for (i in 0 until steps - 1) {
            assertTrue(
                "Timestep at $i (${timesteps[i]}) should be greater than at ${i + 1} (${timesteps[i + 1]})",
                timesteps[i] > timesteps[i + 1]
            )
        }
        assertTrue("Final timestep should be non-negative", timesteps[steps - 1] >= 0f)
    }

    @Test
    fun testClassifierFreeGuidanceFormula() {
        val uncond = floatArrayOf(0.1f, -0.2f, 0.5f)
        val cond = floatArrayOf(0.3f, 0.4f, -0.1f)
        val cfgScale = 7.5f

        val guided = FloatArray(uncond.size) { i ->
            uncond[i] + cfgScale * (cond[i] - uncond[i])
        }

        // guided[0] = 0.1 + 7.5 * (0.3 - 0.1) = 0.1 + 1.5 = 1.6
        assertEquals(1.6f, guided[0], 1e-4f)
        // guided[1] = -0.2 + 7.5 * (0.4 - (-0.2)) = -0.2 + 7.5 * 0.6 = -0.2 + 4.5 = 4.3
        assertEquals(4.3f, guided[1], 1e-4f)
        // guided[2] = 0.5 + 7.5 * (-0.1 - 0.5) = 0.5 + 7.5 * (-0.6) = 0.5 - 4.5 = -4.0
        assertEquals(-4.0f, guided[2], 1e-4f)
    }
}
