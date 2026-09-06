package com.example.sdnpu

import java.io.File

object TestModelFixtures {
    fun stageModel(modelsDir: File, modelId: String = "sdturbo"): File {
        val modelDir = File(modelsDir, modelId).apply { mkdirs() }
        File(modelDir, "text_encoder.onnx").createNewFile()
        File(modelDir, "unet.onnx").createNewFile()
        File(modelDir, "vae_decoder.onnx").createNewFile()
        if (modelId != "sdturbo") {
            val turboDir = File(modelsDir, "sdturbo").apply { mkdirs() }
            File(turboDir, "text_encoder.onnx").createNewFile()
            File(turboDir, "unet.onnx").createNewFile()
            File(turboDir, "vae_decoder.onnx").createNewFile()
        }
        return modelDir
    }
}
