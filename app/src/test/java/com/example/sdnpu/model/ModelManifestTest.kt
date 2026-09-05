package com.example.sdnpu.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
