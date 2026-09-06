package com.example.sdnpu.engine

import com.example.sdnpu.pipeline.GenerationParams
import kotlinx.coroutines.CancellationException
import java.io.File

object SDEngine {
    private val tokenizer = ClipTokenizer()

    @Volatile
    private var isCancelled = false

    fun interface StepCallback {
        fun onStep(step: Int, total: Int)
    }

    external fun nativeLoadSdContext(modelDir: String): Boolean
    external fun nativeGenerateSd(
        promptTokens: IntArray,
        negTokens: IntArray,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        sampler: Int,
        callback: StepCallback?
    ): ByteArray?
    external fun nativeCancelSd()
    external fun nativeUnloadSdContext()

    fun cancel() {
        isCancelled = true
        OnnxDiffusionEngine.cancel()
        if (QnnNativeBridge.isLibraryLoaded()) {
            try {
                nativeCancelSd()
            } catch (_: Throwable) {}
        }
    }

    fun generate(
        params: GenerationParams,
        modelsDir: File,
        onStepProgress: ((step: Int, total: Int) -> Unit)? = null
    ): ByteArray {
        isCancelled = false
        val modelDir = File(modelsDir, params.modelId)
        val hasModelFiles = modelDir.exists() && File(modelDir, "unet.bin").exists()
        val promptTokens = tokenizer.tokenize(params.prompt)
        val negTokens = tokenizer.tokenize(params.negativePrompt)
        val seed = params.seed ?: System.currentTimeMillis()
        val samplerId = params.sampler.ordinal

        if (hasModelFiles && QnnNativeBridge.isLibraryLoaded()) {
            try {
                if (nativeLoadSdContext(modelDir.absolutePath)) {
                    try {
                        val stepCallback = onStepProgress?.let { cb ->
                            StepCallback { step, total -> cb(step, total) }
                        }
                        val bytes = nativeGenerateSd(
                            promptTokens,
                            negTokens,
                            params.steps,
                            params.cfgScale,
                            seed,
                            samplerId,
                            stepCallback
                        )
                        if (isCancelled) {
                            throw CancellationException("Generation cancelled")
                        }
                        if (bytes != null && bytes.isNotEmpty()) {
                            return bytes
                        }
                    } finally {
                        nativeUnloadSdContext()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Native generation failed or crashed, proceed to ONNX engine
            }
        }

        if (isCancelled) {
            throw CancellationException("Generation cancelled")
        }

        // Delegate to OnnxDiffusionEngine
        return OnnxDiffusionEngine.generate(params, modelDir, onStepProgress)
    }
}
