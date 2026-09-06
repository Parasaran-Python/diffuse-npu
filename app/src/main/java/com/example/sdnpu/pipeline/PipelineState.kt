package com.example.sdnpu.pipeline

import java.io.File

sealed class PipelineState {
    object Idle : PipelineState()
    data class LoadingModel(val modelId: String) : PipelineState()
    data class Generating(val step: Int, val totalSteps: Int, val message: String) : PipelineState()
    data class Upscaling(
        val scale: Int = 2,
        val progressPercent: Int = 0
    ) : PipelineState() {
        val progress: Float get() = progressPercent / 100f

        constructor(progress: Float, scale: Int) : this(
            scale = scale,
            progressPercent = (progress * 100).toInt().coerceIn(0, 100)
        )
    }
    data class Completed(
        val message: String = "Generation finished successfully",
        val executionTimeMs: Long = 0,
        val imagePath: String? = null
    ) : PipelineState() {
        constructor(savedFile: File, totalDurationMs: Long) : this(
            message = "Generation finished successfully",
            executionTimeMs = totalDurationMs,
            imagePath = savedFile.absolutePath
        )
    }
    data class Error(val error: String) : PipelineState()
}
