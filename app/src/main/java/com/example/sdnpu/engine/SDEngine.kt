package com.example.sdnpu.engine

import android.util.Log
import com.example.sdnpu.pipeline.GenerationParams
import kotlinx.coroutines.CancellationException
import java.io.File

object SDEngine {
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
            } catch (e: Throwable) {
                try {
                    Log.w("SDEngine", "Failed to cancel native SD execution", e)
                } catch (_: Throwable) {
                    System.err.println("SDEngine: Failed to cancel native SD execution: ${e.message}")
                }
            }
        }
    }

    fun generate(
        params: GenerationParams,
        modelsDir: File,
        onStepProgress: ((step: Int, total: Int) -> Unit)? = null
    ): ByteArray {
        isCancelled = false
        val modelDir = File(modelsDir, params.modelId)
        if (isCancelled) {
            throw CancellationException("Generation cancelled")
        }
        return OnnxDiffusionEngine.generate(params, modelDir, onStepProgress)
    }
}
