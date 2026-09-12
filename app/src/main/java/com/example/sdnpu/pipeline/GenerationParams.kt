package com.example.sdnpu.pipeline

data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

data class GenerationParams(
    val prompt: String,
    val negativePrompt: String = "",
    val modelId: String = "sdturbo",
    val steps: Int = 1,
    val cfgScale: Float = 1.0f,
    val seed: Long? = null,
    val sampler: SamplerType = SamplerType.EULER_A,
    val batchCount: Int = 1,
    val upscaleMode: UpscaleMode = UpscaleMode.OFF,
    val preferredBackend: String? = null
) {
    fun validate(): ValidationResult {
        if (prompt.trim().isEmpty()) {
            return ValidationResult(false, "Prompt cannot be empty")
        }
        if (steps !in 1..50) {
            return ValidationResult(false, "Steps must be between 1 and 50")
        }
        if (cfgScale !in 0.0f..20.0f) {
            return ValidationResult(false, "CFG scale must be between 0.0 and 20.0")
        }
        if (batchCount !in 1..4) {
            return ValidationResult(false, "Batch count must be between 1 and 4")
        }
        return ValidationResult(true)
    }
}
