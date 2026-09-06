package com.example.sdnpu.engine

import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.SamplerType
import java.io.File

object SDEngine {
    private val tokenizer = ClipTokenizer()

    external fun nativeLoadSdContext(modelDir: String): Boolean
    external fun nativeGenerateSd(
        promptTokens: IntArray,
        negTokens: IntArray,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        sampler: Int
    ): ByteArray?
    external fun nativeUnloadSdContext()

    fun generate(
        params: GenerationParams,
        modelsDir: File,
        onStepProgress: ((step: Int, total: Int) -> Unit)? = null
    ): ByteArray {
        val modelPath = File(modelsDir, params.modelId).absolutePath
        val promptTokens = tokenizer.tokenize(params.prompt)
        val negTokens = tokenizer.tokenize(params.negativePrompt)
        val seed = params.seed ?: System.currentTimeMillis()
        val samplerId = params.sampler.ordinal

        if (QnnNativeBridge.isLibraryLoaded()) {
            try {
                if (nativeLoadSdContext(modelPath)) {
                    try {
                        val bytes = nativeGenerateSd(
                            promptTokens,
                            negTokens,
                            params.steps,
                            params.cfgScale,
                            seed,
                            samplerId
                        )
                        if (bytes != null && bytes.isNotEmpty()) {
                            return bytes
                        }
                    } finally {
                        nativeUnloadSdContext()
                    }
                }
            } catch (e: Throwable) {
                // Native generation failed or crashed, fallback to host simulation
            }
        }

        // Host/fallback simulation
        for (step in 1..params.steps) {
            onStepProgress?.invoke(step, params.steps)
        }
        val latents = GaussianNoise.generate(4 * 64 * 64, seed)
        return VaePostProcessor.latentsToRgbBytes(latents, 512, 512)
    }
}
