package com.example.sdnpu.engine

import kotlin.math.sqrt

/**
 * Mathematical implementation of Hugging Face Diffusers EulerDiscreteScheduler
 * configured for Stable Diffusion 1.5:
 * - beta_start: 0.00085
 * - beta_end: 0.012
 * - beta_schedule: scaled_linear
 * - num_train_timesteps: 1000
 * - steps_offset: 1
 * - prediction_type: epsilon
 * - timestep_spacing: leading
 */
object EulerDiscreteScheduler {
    const val NUM_TRAIN_TIMESTEPS = 1000
    const val BETA_START = 0.00085
    const val BETA_END = 0.012
    const val STEPS_OFFSET = 1

    data class Schedule(
        override val timesteps: FloatArray,
        override val sigmas: FloatArray
    ) : DiffusionSchedule {
        override val initNoiseSigma: Float
            get() = sqrt(sigmas[0] * sigmas[0] + 1.0f)

        override fun scaleModelInput(sample: FloatArray, stepIndex: Int): FloatArray {
            val sigma = sigmas[stepIndex]
            val scaleFactor = 1.0f / sqrt(sigma * sigma + 1.0f)
            val scaled = FloatArray(sample.size)
            for (i in sample.indices) {
                scaled[i] = sample[i] * scaleFactor
            }
            return scaled
        }

        override fun step(
            sample: FloatArray,
            modelOutput: FloatArray,
            stepIndex: Int,
            outPrevSample: FloatArray
        ) {
            val sigma = sigmas[stepIndex]
            val nextSigma = sigmas[stepIndex + 1]
            val dt = nextSigma - sigma
            val count = minOf(sample.size, modelOutput.size, outPrevSample.size)
            for (i in 0 until count) {
                outPrevSample[i] = sample[i] + dt * modelOutput[i]
            }
        }
    }

    val trainSigmas: FloatArray = FloatArray(NUM_TRAIN_TIMESTEPS).also { sigmas ->
        val startSqrt = sqrt(BETA_START)
        val endSqrt = sqrt(BETA_END)
        var alphaCumprod = 1.0
        for (i in 0 until NUM_TRAIN_TIMESTEPS) {
            val betaSqrt = startSqrt + i.toDouble() * (endSqrt - startSqrt) / (NUM_TRAIN_TIMESTEPS - 1)
            val beta = betaSqrt * betaSqrt
            val alpha = 1.0 - beta
            alphaCumprod *= alpha
            sigmas[i] = sqrt((1.0 - alphaCumprod) / alphaCumprod).toFloat()
        }
    }

    /**
     * Compute discrete timesteps and interpolated sigmas for [numInferenceSteps] with leading spacing and offset.
     */
    fun getSchedule(numInferenceSteps: Int): Schedule {
        val steps = numInferenceSteps.coerceIn(1, NUM_TRAIN_TIMESTEPS)
        val stepRatio = NUM_TRAIN_TIMESTEPS / steps
        val timesteps = FloatArray(steps)
        val sigmas = FloatArray(steps + 1)

        for (i in 0 until steps) {
            val tVal = ((steps - 1 - i) * stepRatio + STEPS_OFFSET).toFloat()
            val clampedT = tVal.coerceIn(0f, (NUM_TRAIN_TIMESTEPS - 1).toFloat())
            timesteps[i] = clampedT

            val tLow = clampedT.toInt()
            val tHigh = minOf(tLow + 1, NUM_TRAIN_TIMESTEPS - 1)
            val frac = clampedT - tLow
            sigmas[i] = trainSigmas[tLow] * (1f - frac) + trainSigmas[tHigh] * frac
        }
        sigmas[steps] = 0.0f
        return Schedule(timesteps, sigmas)
    }
}
