package com.example.sdnpu.model

import com.google.gson.annotations.SerializedName

data class ModelComponent(
    @SerializedName("name") val name: String,
    @SerializedName("file") val file: String,
    @SerializedName("sha256") val sha256: String
)

data class ModelManifest(
    @SerializedName("model_id") val modelId: String,
    @SerializedName("version") val version: String,
    @SerializedName("components") val components: List<ModelComponent>,
    @SerializedName("qnn_sdk_version") val qnnSdkVersion: String,
    @SerializedName("target_htp") val targetHtp: String,
    @SerializedName("scale") val scale: Int = 1
) {
    companion object {
        fun realesrganX2Plus(sha256: String = ""): ModelManifest = ModelManifest(
            modelId = "realesrgan_x2plus",
            version = "1.0",
            components = listOf(
                ModelComponent(
                    name = "realesrgan_x2plus",
                    file = "model.bin",
                    sha256 = sha256
                )
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73",
            scale = 2
        )

        fun realesrganX4Plus(sha256: String = ""): ModelManifest = ModelManifest(
            modelId = "realesrgan_x4plus",
            version = "1.0",
            components = listOf(
                ModelComponent(
                    name = "realesrgan_x4plus",
                    file = "model.bin",
                    sha256 = sha256
                )
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73",
            scale = 4
        )

        fun realesrgan_x2plus(sha256: String = ""): ModelManifest = realesrganX2Plus(sha256)
        fun realesrgan_x4plus(sha256: String = ""): ModelManifest = realesrganX4Plus(sha256)
    }
}

val ModelManifest.isRealESRGAN: Boolean
    get() = modelId.startsWith("realesrgan")

val ModelManifest.isStableDiffusion: Boolean
    get() = !isRealESRGAN

