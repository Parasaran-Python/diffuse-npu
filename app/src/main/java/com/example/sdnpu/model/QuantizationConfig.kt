package com.example.sdnpu.model

enum class QuantizationPrecision {
    INT8,
    INT16,
    FP16
}

enum class HtpPowerProfile(val displayName: String, val dcvsCorner: String) {
    DEFAULT("Default Balanced", "BALANCED"),
    HIGH_PERFORMANCE("High Performance", "TURBO"),
    BURST("Burst Maximum", "TURBO_BURST"),
    POWER_SAVER("Power Saver", "SVS2")
}

data class ModelQuantizationProfile(
    val modelName: String,
    val weightPrecision: QuantizationPrecision,
    val activationPrecision: QuantizationPrecision,
    val memoryFootprintMb: Int
)

object QuantizationConfig {
    fun getProfileForModel(modelName: String): ModelQuantizationProfile {
        return when {
            modelName.startsWith("realesrgan") -> ModelQuantizationProfile(
                modelName = modelName,
                weightPrecision = QuantizationPrecision.INT8,
                activationPrecision = QuantizationPrecision.INT8,
                memoryFootprintMb = 320
            )
            else -> ModelQuantizationProfile(
                modelName = modelName,
                weightPrecision = QuantizationPrecision.INT8,
                activationPrecision = QuantizationPrecision.INT8,
                memoryFootprintMb = 1850
            )
        }
    }
}
