package com.example.sdnpu.model

import com.example.sdnpu.engine.npu.ModelPrecision
import com.example.sdnpu.engine.npu.QuantParams
import com.example.sdnpu.engine.npu.TensorLayout
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

data class ModelExecutionProfile(
    val modelId: String,
    val runtime: String,
    val targetHardware: String,
    val precision: ModelPrecision,
    val layout: TensorLayout,
    val textEncoderQuant: QuantParams,
    val unetLatentQuant: QuantParams,
    val unetTimestepQuant: QuantParams,
    val unetTextEmbQuant: QuantParams,
    val unetOutLatentQuant: QuantParams,
    val vaeLatentQuant: QuantParams,
    val vaeImageQuant: QuantParams
) {
    val isPrecompiledContext: Boolean
        get() = runtime.contains("precompiled", ignoreCase = true) || runtime.contains("qnn", ignoreCase = true)

    companion object {
        private val gson = Gson()

        // Qualcomm Snapdragon 8 Gen 2 / QCS8550 W8A16 defaults
        val SD15_QNN_PRECOMPILED = ModelExecutionProfile(
            modelId = "sd15_qnn_npu",
            runtime = "precompiled_qnn_onnx",
            targetHardware = "qualcomm-qcs8550-proxy",
            precision = ModelPrecision.UINT16,
            layout = TensorLayout.NHWC,
            textEncoderQuant = QuantParams(0.00093035854f, 30063),
            unetLatentQuant = QuantParams(0.00024176309f, 33983),
            unetTimestepQuant = QuantParams(0.014770733f, 0),
            unetTextEmbQuant = QuantParams(0.0009331561f, 30103),
            unetOutLatentQuant = QuantParams(0.00018817355f, 32340),
            vaeLatentQuant = QuantParams(0.00034003708f, 34382),
            vaeImageQuant = QuantParams(0.000015259022f, 0)
        )

        // Standard FP16 / NCHW baseline profile for generic Android, CPU, and non-quantized models
        val STANDARD_FP16_NCHW = ModelExecutionProfile(
            modelId = "standard_fp16",
            runtime = "onnx",
            targetHardware = "generic",
            precision = ModelPrecision.FP16,
            layout = TensorLayout.NCHW,
            textEncoderQuant = QuantParams.IDENTITY,
            unetLatentQuant = QuantParams.IDENTITY,
            unetTimestepQuant = QuantParams.IDENTITY,
            unetTextEmbQuant = QuantParams.IDENTITY,
            unetOutLatentQuant = QuantParams.IDENTITY,
            vaeLatentQuant = QuantParams.IDENTITY,
            vaeImageQuant = QuantParams.IDENTITY
        )

        fun fromModelDirectory(modelDir: File, fallbackModelId: String = ""): ModelExecutionProfile {
            val metadataFile = File(modelDir, "metadata.json")
            if (!metadataFile.exists() || metadataFile.length() == 0L) {
                if (fallbackModelId == "sd15_qnn_npu" || modelDir.name == "sd15_qnn_npu") {
                    return SD15_QNN_PRECOMPILED
                }
                return STANDARD_FP16_NCHW.copy(modelId = fallbackModelId.ifBlank { modelDir.name })
            }

            return try {
                val json = gson.fromJson(metadataFile.readText(), JsonObject::class.java)
                val modelId = json.get("model_id")?.asString ?: fallbackModelId.ifBlank { modelDir.name }
                val runtime = json.get("runtime")?.asString ?: "onnx"
                val precisionStr = json.get("precision")?.asString ?: "fp16"
                val precision = when (precisionStr.lowercase()) {
                    "w8a16", "uint16" -> ModelPrecision.UINT16
                    "int8", "w8a8" -> ModelPrecision.INT8
                    "fp32", "float32" -> ModelPrecision.FP32
                    else -> ModelPrecision.FP16
                }

                val targetHardware = json.getAsJsonObject("chipset_attributes")
                    ?.get("name")?.asString ?: "generic"

                val modelFiles = json.getAsJsonObject("model_files")

                // Detect layout from UNet input shape
                var detectedLayout = TensorLayout.NCHW
                modelFiles?.getAsJsonObject("unet.onnx")
                    ?.getAsJsonObject("inputs")
                    ?.getAsJsonObject("latent")
                    ?.getAsJsonArray("shape")?.let { shapeArr ->
                        if (shapeArr.size() == 4) {
                            if (shapeArr[3].asInt == 4) {
                                detectedLayout = TensorLayout.NHWC
                            } else if (shapeArr[1].asInt == 4) {
                                detectedLayout = TensorLayout.NCHW
                            }
                        }
                    }

                fun parseQuant(component: String, group: String, name: String, fallback: QuantParams): QuantParams {
                    return try {
                        val quantObj = modelFiles?.getAsJsonObject(component)
                            ?.getAsJsonObject(group)
                            ?.getAsJsonObject(name)
                            ?.getAsJsonObject("quantization_parameters") ?: return fallback
                        val scale = quantObj.get("scale")?.asFloat ?: fallback.scale
                        val zp = quantObj.get("zero_point")?.asInt ?: fallback.zeroPoint
                        QuantParams(scale, zp)
                    } catch (_: Throwable) {
                        fallback
                    }
                }

                val textEncQuant = parseQuant("text_encoder.onnx", "outputs", "text_embedding", SD15_QNN_PRECOMPILED.textEncoderQuant)
                val unetLatentQuant = parseQuant("unet.onnx", "inputs", "latent", SD15_QNN_PRECOMPILED.unetLatentQuant)
                val unetTimestepQuant = parseQuant("unet.onnx", "inputs", "timestep", SD15_QNN_PRECOMPILED.unetTimestepQuant)
                val unetTextEmbQuant = parseQuant("unet.onnx", "inputs", "text_emb", SD15_QNN_PRECOMPILED.unetTextEmbQuant)
                val unetOutLatentQuant = parseQuant("unet.onnx", "outputs", "output_latent", SD15_QNN_PRECOMPILED.unetOutLatentQuant)
                val vaeLatentQuant = parseQuant("vae.onnx", "inputs", "latent", SD15_QNN_PRECOMPILED.vaeLatentQuant)
                val vaeImageQuant = parseQuant("vae.onnx", "outputs", "image", SD15_QNN_PRECOMPILED.vaeImageQuant)

                ModelExecutionProfile(
                    modelId = modelId,
                    runtime = runtime,
                    targetHardware = targetHardware,
                    precision = precision,
                    layout = detectedLayout,
                    textEncoderQuant = textEncQuant,
                    unetLatentQuant = unetLatentQuant,
                    unetTimestepQuant = unetTimestepQuant,
                    unetTextEmbQuant = unetTextEmbQuant,
                    unetOutLatentQuant = unetOutLatentQuant,
                    vaeLatentQuant = vaeLatentQuant,
                    vaeImageQuant = vaeImageQuant
                )
            } catch (t: Throwable) {
                if (fallbackModelId == "sd15_qnn_npu" || modelDir.name == "sd15_qnn_npu") {
                    SD15_QNN_PRECOMPILED
                } else {
                    STANDARD_FP16_NCHW.copy(modelId = fallbackModelId.ifBlank { modelDir.name })
                }
            }
        }
    }
}
