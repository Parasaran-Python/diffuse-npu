package com.example.sdnpu.pipeline

data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

data class GenerationParams(
    val prompt: String,
    val negativePrompt: String = "",
    val modelId: String = "dreamshaper_v8_base",
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long? = null,
    val sampler: SamplerType = SamplerType.EULER_A,
    val batchCount: Int = 1,
    val upscaleMode: UpscaleMode = UpscaleMode.OFF
) {
    fun validate(): ValidationResult {
        if (prompt.trim().isEmpty()) {
            return ValidationResult(false, "Prompt cannot be empty")
        }
        if (steps !in 10..50) {
            return ValidationResult(false, "Steps must be between 10 and 50")
        }
        if (cfgScale !in 1.0f..20.0f) {
            return ValidationResult(false, "CFG scale must be between 1.0 and 20.0")
        }
        if (batchCount !in 1..4) {
            return ValidationResult(false, "Batch count must be between 1 and 4")
        }
        return ValidationResult(true)
    }
}
