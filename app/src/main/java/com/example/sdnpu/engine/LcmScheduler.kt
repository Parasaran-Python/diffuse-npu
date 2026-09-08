package com.example.sdnpu.engine

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

object LcmScheduler {
    const val NUM_TRAIN_TIMESTEPS = 1000
    const val BETA_START = 0.00085
    const val BETA_END = 0.012
    const val ORIGINAL_INFERENCE_STEPS = 50
    const val TIMESTEP_SCALING = 10.0f
    const val SIGMA_DATA = 0.5f

    data class Schedule(val timesteps: LongArray, val numInferenceSteps: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Schedule
            if (!timesteps.contentEquals(other.timesteps)) return false
            if (numInferenceSteps != other.numInferenceSteps) return false
            return true
        }

        override fun hashCode(): Int {
            var result = timesteps.contentHashCode()
            result = 31 * result + numInferenceSteps
            return result
        }
    }

    val alphasCumprod: FloatArray = FloatArray(NUM_TRAIN_TIMESTEPS).also { cumprod ->
        val startSqrt = sqrt(BETA_START)
        val endSqrt = sqrt(BETA_END)
        var currentAlphaCumprod = 1.0
        for (i in 0 until NUM_TRAIN_TIMESTEPS) {
            val betaSqrt = startSqrt + i.toDouble() * (endSqrt - startSqrt) / (NUM_TRAIN_TIMESTEPS - 1)
            val beta = betaSqrt * betaSqrt
            val alpha = 1.0 - beta
            currentAlphaCumprod *= alpha
            cumprod[i] = currentAlphaCumprod.toFloat()
        }
    }

    fun getSchedule(numInferenceSteps: Int): Schedule {
        val steps = numInferenceSteps.coerceIn(1, ORIGINAL_INFERENCE_STEPS)
        val k = NUM_TRAIN_TIMESTEPS / ORIGINAL_INFERENCE_STEPS // 20
        // lcm_origin_timesteps in descending order: [999, 979, ..., 19]
        val lcmOriginTimesteps = LongArray(ORIGINAL_INFERENCE_STEPS) { idx ->
            ((ORIGINAL_INFERENCE_STEPS - idx) * k - 1).toLong()
        }

        // Evenly spaced indices
        val timesteps = LongArray(steps)
        for (i in 0 until steps) {
            val index = floor((i.toDouble() * ORIGINAL_INFERENCE_STEPS.toDouble()) / steps.toDouble()).toInt()
            timesteps[i] = lcmOriginTimesteps[index.coerceIn(0, ORIGINAL_INFERENCE_STEPS - 1)]
        }
        return Schedule(timesteps, steps)
    }

    fun getBoundaryScalings(timestep: Long): Pair<Float, Float> {
        val scaledTimestep = timestep.toFloat() * TIMESTEP_SCALING
        val sigmaSq = SIGMA_DATA * SIGMA_DATA
        val cSkip = sigmaSq / (scaledTimestep * scaledTimestep + sigmaSq)
        val cOut = scaledTimestep / sqrt(scaledTimestep * scaledTimestep + sigmaSq)
        return Pair(cSkip, cOut)
    }

    fun getGuidanceEmbedding(guidanceScale: Float, embeddingDim: Int = 256): FloatArray {
        require(embeddingDim >= 4 && embeddingDim % 2 == 0) { "embeddingDim must be an even integer >= 4" }
        val embedding = FloatArray(embeddingDim)
        val w = (guidanceScale - 1.0f) * 1000.0f
        val halfDim = embeddingDim / 2
        val logScale = ln(10000.0) / (halfDim - 1).toDouble()

        for (i in 0 until halfDim) {
            val freq = exp(-i.toDouble() * logScale)
            val angle = w.toDouble() * freq
            embedding[i] = sin(angle).toFloat()
            embedding[i + halfDim] = cos(angle).toFloat()
        }
        return embedding
    }

    fun step(
        sample: FloatArray,
        modelOutput: FloatArray,
        stepIndex: Int,
        schedule: Schedule,
        generatorNoise: FloatArray?,
        outSample: FloatArray
    ) {
        val timestep = schedule.timesteps[stepIndex]
        val prevTimestep = if (stepIndex + 1 < schedule.timesteps.size) {
            schedule.timesteps[stepIndex + 1]
        } else {
            timestep
        }

        val alphaProdT = alphasCumprod[timestep.toInt().coerceIn(0, NUM_TRAIN_TIMESTEPS - 1)]
        val alphaProdTPrev = if (prevTimestep >= 0) {
            alphasCumprod[prevTimestep.toInt().coerceIn(0, NUM_TRAIN_TIMESTEPS - 1)]
        } else {
            1.0f
        }

        val betaProdT = (1.0f - alphaProdT).coerceAtLeast(0f)
        val betaProdTPrev = (1.0f - alphaProdTPrev).coerceAtLeast(0f)

        val sqrtAlphaT = sqrt(alphaProdT)
        val sqrtBetaT = sqrt(betaProdT)
        val sqrtAlphaPrev = sqrt(alphaProdTPrev)
        val sqrtBetaPrev = sqrt(betaProdTPrev)

        val (cSkip, cOut) = getBoundaryScalings(timestep)
        val count = minOf(sample.size, modelOutput.size, outSample.size)
        require(generatorNoise == null || generatorNoise.size >= count) { "generatorNoise size must be >= count" }
        val isFinalStep = stepIndex == schedule.numInferenceSteps - 1


        for (i in 0 until count) {
            // 1. Predicted original sample x_0
            val predX0 = (sample[i] - sqrtBetaT * modelOutput[i]) / sqrtAlphaT
            // 2. Denoised estimate
            val denoised = cOut * predX0 + cSkip * sample[i]

            // 3. Noise injection for intermediate steps
            if (!isFinalStep && generatorNoise != null && i < generatorNoise.size) {
                outSample[i] = sqrtAlphaPrev * denoised + sqrtBetaPrev * generatorNoise[i]
            } else {
                outSample[i] = denoised
            }
        }
    }
}
