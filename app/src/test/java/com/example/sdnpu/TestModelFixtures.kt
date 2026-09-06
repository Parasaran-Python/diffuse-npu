package com.example.sdnpu

import java.io.File

object TestModelFixtures {
    fun stageModel(modelsDir: File, modelId: String = "dreamshaper_v8_base"): File {
        val modelDir = File(modelsDir, modelId).apply { mkdirs() }
        File(modelDir, "text_encoder.onnx").createNewFile()
        File(modelDir, "unet.onnx").createNewFile()
        File(modelDir, "vae_decoder.onnx").createNewFile()
        return modelDir
    }
}
