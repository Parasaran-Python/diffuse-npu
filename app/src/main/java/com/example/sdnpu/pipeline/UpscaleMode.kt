package com.example.sdnpu.pipeline

enum class UpscaleMode(val scale: Int, val displayName: String) {
    OFF(1, "Off"),
    X2(2, "RealESRGAN 2x"),
    X4(4, "RealESRGAN 4x")
}
