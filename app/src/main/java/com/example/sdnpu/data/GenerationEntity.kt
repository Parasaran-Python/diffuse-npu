package com.example.sdnpu.data

data class GenerationEntity(
    val id: Long = 0,
    val prompt: String,
    val negativePrompt: String = "",
    val modelName: String = "dreamshaper_v8",
    val imagePath: String,
    val seed: Long = 0L,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val sampler: Int = 0,
    val upscaleMode: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val generationTimeMs: Long = 0L,
    val width: Int = 512,
    val height: Int = 512,
    val fileSizeBytes: Long = 0L
)
