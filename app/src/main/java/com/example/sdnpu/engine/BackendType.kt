package com.example.sdnpu.engine

enum class BackendType(val id: Int, val displayName: String) {
    CPU(0, "CPU (Multi-threaded)"),
    GPU(1, "GPU (Adreno / Mali)"),
    QNN_HTP(2, "Qualcomm Hexagon NPU (QNN HTP)"),
    MEDIATEK_APU(3, "MediaTek APU (NeuroPilot)"),
    GOOGLE_TPU(4, "Google Tensor TPU"),
    EXYNOS_NPU(5, "Samsung Exynos NPU"),
    NNAPI_NPU(6, "Generic Android NNAPI");

    companion object {
        // Full backward-compatibility alias for existing code and tests
        @JvmField
        val HTP_NPU: BackendType = QNN_HTP

        fun fromId(id: Int): BackendType = entries.firstOrNull { it.id == id } ?: CPU
    }
}
