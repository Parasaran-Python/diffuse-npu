package com.example.sdnpu.data

/**
 * User application settings model with validated and sanitized bounds.
 */
data class AppSettings(
    val defaultSteps: Int = 20,
    val defaultCfgScale: Float = 7.0f,
    val defaultSampler: Int = 0,
    val defaultBatchCount: Int = 1,
    val defaultUpscaleMode: Int = 0,
    val backendPreference: String = "NPU",
    val thermalWarningEnabled: Boolean = true,
    val highPerformanceMode: Boolean = false,
    val darkThemeMode: String = "DARK"
) {
    fun sanitized(): AppSettings = copy(
        defaultSteps = defaultSteps.coerceIn(10, 50),
        defaultCfgScale = defaultCfgScale.coerceIn(1.0f, 20.0f),
        defaultSampler = defaultSampler.coerceIn(0, 3),
        defaultBatchCount = defaultBatchCount.coerceIn(1, 4),
        defaultUpscaleMode = defaultUpscaleMode.coerceIn(0, 2),
        backendPreference = if (backendPreference in VALID_BACKENDS) backendPreference else DEFAULT_BACKEND,
        darkThemeMode = if (darkThemeMode in VALID_THEMES) darkThemeMode else DEFAULT_DARK_THEME
    )

    companion object {
        val VALID_BACKENDS = setOf("NPU", "GPU", "CPU")
        val VALID_THEMES = setOf("SYSTEM", "DARK", "LIGHT")
        const val DEFAULT_BACKEND = "NPU"
        const val DEFAULT_DARK_THEME = "DARK"
    }
}
