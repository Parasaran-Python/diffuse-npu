package com.example.sdnpu.engine

import org.junit.Assert.*
import org.junit.Test

class SdTurboSchedulerTest {

    @Test
    fun testTrainSigmasBoundaryAndMonotonicity() {
        assertEquals(1000, SdTurboScheduler.trainSigmas.size)
        // Sigma at t=0 should be ~0.029167
        assertEquals(0.029167f, SdTurboScheduler.trainSigmas[0], 1e-4f)
        // Sigma at t=499 should be ~1.612886
        assertEquals(1.612886f, SdTurboScheduler.trainSigmas[499], 1e-4f)
        // Sigma at t=999 should be ~14.614641
        assertEquals(14.614641f, SdTurboScheduler.trainSigmas[999], 1e-4f)

        // Monotonically increasing
        for (i in 0 until 999) {
            assertTrue(
                "trainSigmas must be monotonically increasing at $i",
                SdTurboScheduler.trainSigmas[i] < SdTurboScheduler.trainSigmas[i + 1]
            )
        }
    }

    @Test
    fun test1StepScheduleMatchesDiffusers() {
        val schedule = SdTurboScheduler.getSchedule(1)
        assertEquals(1, schedule.timesteps.size)
        assertEquals(2, schedule.sigmas.size)

        // Timestep [999.0]
        assertEquals(999.0f, schedule.timesteps[0], 1e-3f)

        // Sigmas [14.614641, 0.0]
        assertEquals(14.614641f, schedule.sigmas[0], 1e-4f)
        assertEquals(0.0f, schedule.sigmas[1], 1e-6f)
        assertEquals(14.614641f, schedule.initNoiseSigma, 1e-4f)

        // scaleModelInput: 1 / sqrt(14.614641^2 + 1) = 0.068265
        val sample = floatArrayOf(1.0f, 2.0f, -3.0f)
        val scaled = schedule.scaleModelInput(sample, stepIndex = 0)
        val expectedFactor = 1.0f / kotlin.math.sqrt(14.614641f * 14.614641f + 1.0f)
        assertEquals(expectedFactor, scaled[0], 1e-5f)
        assertEquals(expectedFactor * 2.0f, scaled[1], 1e-5f)
        assertEquals(expectedFactor * -3.0f, scaled[2], 1e-5f)
    }

    @Test
    fun test2StepScheduleMatchesDiffusers() {
        val schedule = SdTurboScheduler.getSchedule(2)
        assertEquals(2, schedule.timesteps.size)
        assertEquals(3, schedule.sigmas.size)

        // Timesteps [999.0, 499.0]
        assertEquals(999.0f, schedule.timesteps[0], 1e-3f)
        assertEquals(499.0f, schedule.timesteps[1], 1e-3f)

        // Sigmas [14.614641, 1.612886, 0.0]
        assertEquals(14.614641f, schedule.sigmas[0], 1e-4f)
        assertEquals(1.612886f, schedule.sigmas[1], 1e-4f)
        assertEquals(0.0f, schedule.sigmas[2], 1e-6f)
    }

    @Test
    fun test4StepScheduleMatchesDiffusers() {
        val schedule = SdTurboScheduler.getSchedule(4)
        assertEquals(4, schedule.timesteps.size)
        assertEquals(5, schedule.sigmas.size)

        // Timesteps [999.0, 749.0, 499.0, 249.0]
        assertEquals(999.0f, schedule.timesteps[0], 1e-3f)
        assertEquals(749.0f, schedule.timesteps[1], 1e-3f)
        assertEquals(499.0f, schedule.timesteps[2], 1e-3f)
        assertEquals(249.0f, schedule.timesteps[3], 1e-3f)

        // Sigmas [14.614641, 4.081729, 1.612886, 0.693205, 0.0]
        assertEquals(14.614641f, schedule.sigmas[0], 1e-4f)
        assertEquals(4.081729f, schedule.sigmas[1], 1e-4f)
        assertEquals(1.612886f, schedule.sigmas[2], 1e-4f)
        assertEquals(0.693205f, schedule.sigmas[3], 1e-4f)
        assertEquals(0.0f, schedule.sigmas[4], 1e-6f)
    }

    @Test
    fun testStepUpdatesSample() {
        val schedule = SdTurboScheduler.getSchedule(1)
        val sample = floatArrayOf(14.614641f)
        val noisePred = floatArrayOf(1.0f)
        val outPrevSample = FloatArray(1)

        schedule.step(sample, noisePred, stepIndex = 0, outPrevSample)
        // prev = sample + (nextSigma - sigma) * noise = 14.614641 + (0 - 14.614641) * 1.0 = 0.0
        assertEquals(0.0f, outPrevSample[0], 1e-4f)
    }
}
