package com.example.sdnpu.pipeline

sealed class PipelineState {
    object Idle : PipelineState()
    data class LoadingModel(val modelId: String) : PipelineState()
    data class Generating(val step: Int, val totalSteps: Int, val message: String) : PipelineState()
    data class Upscaling(val scale: Int, val progressPercent: Int) : PipelineState()
    data class Completed(val message: String, val executionTimeMs: Long) : PipelineState()
    data class Error(val error: String) : PipelineState()
}
