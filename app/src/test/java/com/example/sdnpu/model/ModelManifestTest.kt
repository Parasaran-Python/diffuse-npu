package com.example.sdnpu.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ModelManifestTest {
    @Test
    fun testParseManifestJson() {
        val json = """
        {
          "model_id": "dreamshaper_v8",
          "version": "1.0",
          "components": [
            {"name": "clip_text_encoder", "file": "clip_text_encoder.bin", "sha256": "abcdef1234567890"},
            {"name": "unet", "file": "unet.bin", "sha256": "1234567890abcdef"},
            {"name": "vae_decoder", "file": "vae_decoder.bin", "sha256": "fedcba0987654321"}
          ],
          "qnn_sdk_version": "2.49.0",
          "target_htp": "v73"
        }
        """.trimIndent()

        val manifest = Gson().fromJson(json, ModelManifest::class.java)
        assertNotNull(manifest)
        assertEquals("dreamshaper_v8", manifest.modelId)
        assertEquals("1.0", manifest.version)
        assertEquals(3, manifest.components.size)
        assertEquals("clip_text_encoder.bin", manifest.components[0].file)
        assertEquals("v73", manifest.targetHtp)
    }

    @Test
    fun testRealESRGANx2PlusManifest() {
        val manifest = ModelManifest.realesrgan_x2plus("test_sha256_x2")
        assertEquals("realesrgan_x2plus", manifest.modelId)
        assertEquals(2, manifest.scale)
        assertEquals(1, manifest.components.size)
        assertEquals("model.bin", manifest.components[0].file)
        assertEquals("test_sha256_x2", manifest.components[0].sha256)
        assertEquals(true, manifest.isRealESRGAN)
        assertEquals(false, manifest.isStableDiffusion)

        // Test camelCase alias
        val manifestCamel = ModelManifest.realesrganX2Plus()
        assertEquals("realesrgan_x2plus", manifestCamel.modelId)
        assertEquals(2, manifestCamel.scale)
        assertEquals(1, manifestCamel.components.size)
        assertEquals("model.bin", manifestCamel.components[0].file)
    }

    @Test
    fun testRealESRGANx4PlusManifest() {
        val manifest = ModelManifest.realesrgan_x4plus("test_sha256_x4")
        assertEquals("realesrgan_x4plus", manifest.modelId)
        assertEquals(4, manifest.scale)
        assertEquals(1, manifest.components.size)
        assertEquals("model.bin", manifest.components[0].file)
        assertEquals("test_sha256_x4", manifest.components[0].sha256)
        assertEquals(true, manifest.isRealESRGAN)
        assertEquals(false, manifest.isStableDiffusion)

        // Test camelCase alias
        val manifestCamel = ModelManifest.realesrganX4Plus()
        assertEquals("realesrgan_x4plus", manifestCamel.modelId)
        assertEquals(4, manifestCamel.scale)
        assertEquals(1, manifestCamel.components.size)
        assertEquals("model.bin", manifestCamel.components[0].file)
    }

    @Test
    fun testStableDiffusionModelIdentification() {
        val sdManifest = ModelManifest(
            modelId = "dreamshaper_v8",
            version = "1.0",
            components = emptyList(),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )
        assertEquals(false, sdManifest.isRealESRGAN)
        assertEquals(true, sdManifest.isStableDiffusion)
    }

    @Test
    fun testRealESRGANSerializationDeserialization() {
        val manifest = ModelManifest.realesrganX2Plus("abc123hash")
        val json = Gson().toJson(manifest)
        val deserialized = Gson().fromJson(json, ModelManifest::class.java)

        assertEquals("realesrgan_x2plus", deserialized.modelId)
        assertEquals(2, deserialized.scale)
        assertEquals(1, deserialized.components.size)
        assertEquals("model.bin", deserialized.components[0].file)
        assertEquals("abc123hash", deserialized.components[0].sha256)
        assertEquals(true, deserialized.isRealESRGAN)
        assertEquals(false, deserialized.isStableDiffusion)
    }

    @Test
    fun testSdTurboManifestContainsRequiredOnnxComponents() {
        val manifest = ModelManifest.sdturbo()
        assertEquals("sdturbo", manifest.modelId)
        assertEquals("1.0", manifest.version)
        assertEquals("ort-1.20", manifest.qnnSdkVersion)
        assertEquals("v73", manifest.targetHtp)
        val files = manifest.components.map { it.file }
        assertTrue(files.contains("text_encoder.onnx"))
        assertTrue(files.contains("unet.onnx"))
        assertTrue(files.contains("vae_decoder.onnx"))
        assertEquals(3, manifest.components.size)
        assertEquals(false, manifest.isRealESRGAN)
        assertEquals(true, manifest.isStableDiffusion)

        // Test alias
        val manifestCamel = ModelManifest.sdTurbo()
        assertEquals("sdturbo", manifestCamel.modelId)
    }

    @Test
    fun testModelVariantsIncludesSdTurbo() {
        val variants = ModelVariants.getSdVariants()
        val sdturboVariant = variants.find { it.id == "sdturbo" }
        assertNotNull(sdturboVariant)
        assertEquals("SD-Turbo (ONNX / LCM)", sdturboVariant?.name)
        assertTrue(ModelVariants.isSdModel("sdturbo"))
    }

    @Test
    fun testModelDownloadPresets() {
        val presets = ModelDownloadPresets.getPresets()
        assertEquals(3, presets.size)

        val sdturboPreset = presets.find { it.id == "sdturbo" }
        assertNotNull(sdturboPreset)
        assertEquals("SD-Turbo (ONNX / LCM)", sdturboPreset?.name)
        assertTrue(sdturboPreset?.defaultUrl?.contains("Heliosoph/sd-turbo-onnx") == true)
        assertTrue(sdturboPreset?.isDefault == true)

        val customPreset = presets.find { it.id == "custom" }
        assertNotNull(customPreset)
        assertEquals("", customPreset?.defaultUrl)
    }
}

