package com.example.sdnpu.engine.npu

enum class TensorLayout {
    NCHW,
    NHWC
}

enum class ModelPrecision {
    FP32,
    FP16,
    UINT16,
    INT8
}

data class QuantParams(
    val scale: Float = 1.0f,
    val zeroPoint: Int = 0
) {
    val isQuantized: Boolean
        get() = scale != 1.0f || zeroPoint != 0

    companion object {
        val IDENTITY = QuantParams(1.0f, 0)
    }
}
