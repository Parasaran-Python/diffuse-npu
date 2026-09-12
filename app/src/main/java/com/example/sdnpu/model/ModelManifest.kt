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
                    name = "text_encoder",
                    file = "text_encoder.onnx",
                    sha256 = "$sha256Prefix.text_encoder"
                ),
                ModelComponent(
                    name = "unet",
                    file = "unet.onnx",
                    sha256 = "$sha256Prefix.unet"
                ),
                ModelComponent(
                    name = "vae_decoder",
                    file = "vae_decoder.onnx",
                    sha256 = "$sha256Prefix.vae_decoder"
                )
            ),
            qnnSdkVersion = "ort-1.20",
            targetHtp = "v73"
        )

        fun dreamshaperV8Anime(sha256Prefix: String = ""): ModelManifest = dreamshaperV8Base(sha256Prefix)

        fun dreamshaperV8Realistic(sha256Prefix: String = ""): ModelManifest = dreamshaperV8Base(sha256Prefix)

        fun realesrganX2Plus(sha256: String = ""): ModelManifest = ModelManifest(
            modelId = "realesrgan_x2plus",
            version = "1.0",
            components = listOf(
                ModelComponent(
                    name = "realesrgan_x2plus",
                    file = "model.onnx",
                    sha256 = sha256
                )
            ),
            qnnSdkVersion = "ort-1.20",
            targetHtp = "v73",
            scale = 2
        )

        fun realesrganX4Plus(sha256: String = ""): ModelManifest = ModelManifest(
            modelId = "realesrgan_x4plus",
            version = "1.0",
            components = listOf(
                ModelComponent(
                    name = "realesrgan_x4plus",
                    file = "model.onnx",
                    sha256 = sha256
                )
            ),
            qnnSdkVersion = "ort-1.20",
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

        fun sd15QnnNpu(sha256Prefix: String = ""): ModelManifest = ModelManifest(
            modelId = "sd15_qnn_npu",
            version = "1.5",
            components = listOf(
                ModelComponent("text_encoder", "text_encoder.onnx", "$sha256Prefix.text_encoder"),
                ModelComponent("text_encoder_context", "text_encoder_qairt_context.bin", "$sha256Prefix.text_encoder_context"),
                ModelComponent("unet", "unet.onnx", "$sha256Prefix.unet"),
                ModelComponent("unet_context", "unet_qairt_context.bin", "$sha256Prefix.unet_context"),
                ModelComponent("vae", "vae.onnx", "$sha256Prefix.vae"),
                ModelComponent("vae_context", "vae_qairt_context.bin", "$sha256Prefix.vae_context")
            ),
            qnnSdkVersion = "ort-1.29",
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
            id = "sd15_qnn_npu",
            name = "SD 1.5 (Snapdragon NPU / S23 Ultra)",
            description = "100% Hexagon NPU hardware acceleration (W8A16 ~2-3s inference)",
            isDefault = true
        ),
        ModelVariant(
            id = "sdturbo",
            name = "SD-Turbo (ONNX / LCM)",
            description = "Fast 1-4 step experimental model (requires FP32/QNN context)",
            isDefault = false
        ),
        ModelVariant(
            id = "dreamshaper_v8_base",
            name = "DreamShaper v8 (LCM / ONNX)",
            description = "High quality 4-8 step SD 1.5 LCM inference (requires FP32/QNN context)"
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
            id = "sd15_qnn_npu",
            name = "SD 1.5 (Qualcomm NPU / S8 Gen 2)",
            defaultUrl = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/stable_diffusion_v1_5/releases/v0.62.1/stable_diffusion_v1_5-precompiled_qnn_onnx-w8a16-qualcomm_qcs8550_proxy.zip",
            description = "100% Qualcomm Hexagon NPU hardware acceleration (Snapdragon 8 Gen 2 / W8A16)",
            isDefault = true
        ),
        ModelDownloadPreset(
            id = "sdturbo",
            name = "SD-Turbo (ONNX / LCM)",
            defaultUrl = "https://huggingface.co/microsoft/sd-turbo-webnn/resolve/main/",
            description = "1-4 step inference with ONNX Runtime Mobile & Qualcomm NPU (Official Microsoft Weights)",
            isDefault = false
        ),
        ModelDownloadPreset(
            id = "dreamshaper_v8_base",
            name = "DreamShaper v8 (LCM / ONNX)",
            defaultUrl = "https://huggingface.co/jdp8/lcm-dreamshaper-v7-onnx/resolve/main/",
            description = "High quality 4-8 step SD 1.5 LCM inference on Qualcomm NPU"
        ),
        ModelDownloadPreset(
            id = "realesrgan_x2plus",
            name = "RealESRGAN 2x Plus (ONNX)",
            defaultUrl = "https://huggingface.co/tamnvcc/RealESRGAN-onnx/resolve/main/onnx/",
            description = "Fast 2x super-resolution upscaler for Qualcomm NPU / ONNX Runtime"
        ),
        ModelDownloadPreset(
            id = "realesrgan_x4plus",
            name = "RealESRGAN 4x Plus (ONNX)",
            defaultUrl = "https://huggingface.co/tamnvcc/RealESRGAN-onnx/resolve/main/onnx/",
            description = "Ultra high detail 4x super-resolution upscaler for Qualcomm NPU / ONNX Runtime"
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