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
        fun dreamshaperV8Base(sha256Prefix: String = ""): ModelManifest = ModelManifest(
            modelId = "dreamshaper_v8_base",
            version = "1.0",
            components = listOf(
                ModelComponent(
                    name = "clip_text_encoder",
                    file = "clip_text_encoder.bin",
                    sha256 = "$sha256Prefix.clip_text_encoder"
                ),
                ModelComponent(
                    name = "unet",
                    file = "unet.bin",
                    sha256 = "$sha256Prefix.unet"
                ),
                ModelComponent(
                    name = "vae_decoder",
                    file = "vae_decoder.bin",
                    sha256 = "$sha256Prefix.vae_decoder"
                )
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )

        fun dreamshaperV8Anime(sha256Prefix: String = ""): ModelManifest = ModelManifest(
            modelId = "dreamshaper_v8_anime",
            version = "1.0",
            components = listOf(
                ModelComponent(
                    name = "clip_text_encoder",
                    file = "clip_text_encoder.bin",
                    sha256 = "$sha256Prefix.clip_text_encoder"
                ),
                ModelComponent(
                    name = "unet",
                    file = "unet.bin",
                    sha256 = "$sha256Prefix.unet"
                ),
                ModelComponent(
                    name = "vae_decoder",
                    file = "vae_decoder.bin",
                    sha256 = "$sha256Prefix.vae_decoder"
                )
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )

        fun dreamshaperV8Realistic(sha256Prefix: String = ""): ModelManifest = ModelManifest(
            modelId = "dreamshaper_v8_realistic",
            version = "1.0",
            components = listOf(
                ModelComponent(
                    name = "clip_text_encoder",
                    file = "clip_text_encoder.bin",
                    sha256 = "$sha256Prefix.clip_text_encoder"
                ),
                ModelComponent(
                    name = "unet",
                    file = "unet.bin",
                    sha256 = "$sha256Prefix.unet"
                ),
                ModelComponent(
                    name = "vae_decoder",
                    file = "vae_decoder.bin",
                    sha256 = "$sha256Prefix.vae_decoder"
                )
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )

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

        fun dreamshaper_v8_base(sha256Prefix: String = ""): ModelManifest = dreamshaperV8Base(sha256Prefix)
        fun dreamshaper_v8_anime(sha256Prefix: String = ""): ModelManifest = dreamshaperV8Anime(sha256Prefix)
        fun dreamshaper_v8_realistic(sha256Prefix: String = ""): ModelManifest = dreamshaperV8Realistic(sha256Prefix)

        fun sdturbo(sha256Prefix: String = ""): ModelManifest = ModelManifest(
            modelId = "sdturbo",
            version = "1.0",
            components = listOf(
                ModelComponent("text_encoder", "text_encoder.onnx", "$sha256Prefix.text_encoder"),
                ModelComponent("unet", "unet.onnx", "$sha256Prefix.unet"),
                ModelComponent("vae_decoder", "vae_decoder.onnx", "$sha256Prefix.vae_decoder")
            ),
            qnnSdkVersion = "ort-1.20",
            targetHtp = "v73"
        )

        fun sdTurbo(sha256Prefix: String = ""): ModelManifest = sdturbo(sha256Prefix)
    }
}

val ModelManifest.isRealESRGAN: Boolean
    get() = modelId.startsWith("realesrgan")

val ModelManifest.isStableDiffusion: Boolean
    get() = !isRealESRGAN

data class ModelVariant(
    val id: String,
    val name: String,
    val description: String,
    val isDefault: Boolean = false
)

object ModelVariants {
    val SD_VARIANTS = listOf(
        ModelVariant(
            id = "dreamshaper_v8_base",
            name = "DreamShaper v8 (General)",
            description = "Balanced photorealistic & artistic SD 1.5 model",
            isDefault = true
        ),
        ModelVariant(
            id = "dreamshaper_v8_anime",
            name = "DreamShaper v8 (Anime)",
            description = "Stylized anime and manga art checkpoint"
        ),
        ModelVariant(
            id = "dreamshaper_v8_realistic",
            name = "DreamShaper v8 (Realistic)",
            description = "Enhanced for lifelike human faces and natural scenes"
        ),
        ModelVariant(
            id = "sdturbo",
            name = "SD-Turbo (ONNX / LCM)",
            description = "Fast 1-4 step inference with ONNX Runtime & QNN NPU"
        )
    )

    fun getSdVariants(): List<ModelVariant> = SD_VARIANTS

    fun isSdModel(id: String): Boolean = !id.startsWith("realesrgan")
}

data class ModelDownloadPreset(
    val id: String,
    val name: String,
    val defaultUrl: String,
    val description: String,
    val isDefault: Boolean = false
)

object ModelDownloadPresets {
    val PRESETS = listOf(
        ModelDownloadPreset(
            id = "sdturbo",
            name = "SD-Turbo (ONNX / LCM)",
            defaultUrl = "https://huggingface.co/Heliosoph/sd-turbo-onnx/resolve/main/",
            description = "1-4 step inference with ONNX Runtime Mobile & Qualcomm NPU",
            isDefault = true
        ),
        ModelDownloadPreset(
            id = "dreamshaper_v8_base",
            name = "DreamShaper v8 (General)",
            defaultUrl = "http://192.168.1.100:8080/models/dreamshaper_v8_base/",
            description = "Standard SD 1.5 weights (Local Wi-Fi / Custom server)"
        ),
        ModelDownloadPreset(
            id = "custom",
            name = "Custom URL",
            defaultUrl = "",
            description = "Download from custom HTTP/HTTPS server or Hugging Face mirror"
        )
    )

    fun getPresets(): List<ModelDownloadPreset> = PRESETS
}