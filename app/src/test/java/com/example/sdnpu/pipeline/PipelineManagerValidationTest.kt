package com.example.sdnpu.pipeline

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PipelineManagerValidationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidationFailsWhenModelFilesMissing() {
        val modelsDir = tempFolder.newFolder("models")
        val manager = PipelineManager(modelsDir = modelsDir)

        val result = manager.validateModelAvailability("sdturbo", modelsDir)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun testValidationSucceedsWhenAllRequiredOnnxModelsExist() {
        val modelsDir = tempFolder.newFolder("models")
        val modelDir = File(modelsDir, "sdturbo").apply { mkdirs() }
        File(modelDir, "text_encoder.onnx").createNewFile()
        File(modelDir, "unet.onnx").createNewFile()
        File(modelDir, "vae_decoder.onnx").createNewFile()

        val manager = PipelineManager(modelsDir = modelsDir)
        val result = manager.validateModelAvailability("sdturbo", modelsDir)
        assertTrue(result.isSuccess)
    }
}
