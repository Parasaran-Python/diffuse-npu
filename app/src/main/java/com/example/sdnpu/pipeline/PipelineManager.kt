package com.example.sdnpu.pipeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class PipelineManager {
    fun runGeneration(params: GenerationParams): Flow<PipelineState> = flow {
        val validation = params.validate()
        if (!validation.isValid) {
            emit(PipelineState.Error(validation.errorMessage ?: "Invalid parameters"))
            return@flow
        }

        val startTime = System.currentTimeMillis()
        emit(PipelineState.LoadingModel(params.modelId))
        delay(100) // Context loading delay

        for (step in 1..params.steps) {
            emit(PipelineState.Generating(step, params.steps, "Denoising step $step/${params.steps}"))
            delay(30) // Simulated step
        }

        if (params.upscaleMode != UpscaleMode.OFF) {
            emit(PipelineState.Upscaling(params.upscaleMode.scale, 0))
            delay(150)
            emit(PipelineState.Upscaling(params.upscaleMode.scale, 100))
        }

        val totalTime = System.currentTimeMillis() - startTime
        emit(PipelineState.Completed("Generation finished successfully", totalTime))
    }

    fun generate(params: GenerationParams): Flow<PipelineState> = runGeneration(params)
}
