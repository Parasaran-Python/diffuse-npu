package com.example.sdnpu.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class EulerDiscreteSchedulerTest {

    @Test
    fun testTrainSigmasBoundaryAndMonotonicity() {
        assertEquals(1000, EulerDiscreteScheduler.trainSigmas.size)
        assertEquals(0.029167f, EulerDiscreteScheduler.trainSigmas[0], 1e-4f)
        assertEquals(1.612886f, EulerDiscreteScheduler.trainSigmas[499], 1e-4f)
        assertEquals(14.614641f, EulerDiscreteScheduler.trainSigmas[999], 1e-4f)

        for (i in 0 until 999) {
            assertTrue(
                "trainSigmas must be monotonically increasing at $i",
                EulerDiscreteScheduler.trainSigmas[i] < EulerDiscreteScheduler.trainSigmas[i + 1]
            )
        }
    }

    @Test
    fun test20StepScheduleLeadingSpacing() {
        val schedule = EulerDiscreteScheduler.getSchedule(20)
        assertEquals(20, schedule.timesteps.size)
        assertEquals(21, schedule.sigmas.size)

        // Leading spacing: t[0] = 951, t[1] = 901, ..., t[19] = 1
        assertEquals(951.0f, schedule.timesteps[0], 1e-3f)
        assertEquals(901.0f, schedule.timesteps[1], 1e-3f)
        assertEquals(1.0f, schedule.timesteps[19], 1e-3f)

        // All timesteps fit within uint16 scale for QNN UNet (max scale ~0.01477, max qTime = 951 / 0.01477 = 64384 <= 65535)
        for (t in schedule.timesteps) {
            val qTime = (Math.round(t / 0.014770733192563057)).toInt()
            assertTrue("qTime $qTime must fit in uint16 [0, 65535]", qTime in 0..65535)
        }

        // Last sigma is 0
        assertEquals(0.0f, schedule.sigmas[20], 1e-6f)

        // initNoiseSigma for leading spacing = sqrt(sigma_0^2 + 1)
        val expectedInitNoiseSigma = sqrt(schedule.sigmas[0] * schedule.sigmas[0] + 1.0f)
        assertEquals(expectedInitNoiseSigma, schedule.initNoiseSigma, 1e-4f)
    }

    @Test
    fun testScaleModelInput() {
        val schedule = EulerDiscreteScheduler.getSchedule(20)
        val sample = floatArrayOf(1.0f, 2.0f, -3.0f)
        val scaled = schedule.scaleModelInput(sample, stepIndex = 0)
        val sigma0 = schedule.sigmas[0]
        val expectedFactor = 1.0f / sqrt(sigma0 * sigma0 + 1.0f)
        assertEquals(expectedFactor, scaled[0], 1e-5f)
        assertEquals(expectedFactor * 2.0f, scaled[1], 1e-5f)
        assertEquals(expectedFactor * -3.0f, scaled[2], 1e-5f)
    }

    @Test
    fun testStepUpdatesSample() {
        val schedule = EulerDiscreteScheduler.getSchedule(20)
        val sample = floatArrayOf(10.0f)
        val noisePred = floatArrayOf(1.0f)
        val outPrevSample = FloatArray(1)

        val dt = schedule.sigmas[1] - schedule.sigmas[0]
        schedule.step(sample, noisePred, stepIndex = 0, outPrevSample)
        val expected = 10.0f + dt * 1.0f
        assertEquals(expected, outPrevSample[0], 1e-4f)
    }
}
