package com.example.sdnpu.pipeline

enum class SamplerType(val displayName: String) {
    EULER_A("Euler a"),
    DPM_2M_KARRAS("DPM++ 2M Karras"),
    DPM_SDE_KARRAS("DPM++ SDE Karras"),
    DDIM("DDIM")
}
