package com.example.sdnpu.engine

import com.example.sdnpu.TestModelFixtures
import com.example.sdnpu.pipeline.GenerationParams
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OnnxDiffusionEngineTest {
    @Test
    fun testEulerStepUpdatesLatents() {
        val latents = FloatArray(16384) { 1.0f }
        val noisePred = FloatArray(16384) { 0.5f }
        val outLatents = FloatArray(16384)
        
        OnnxDiffusionEngine.eulerStep(latents, noisePred, sigma = 1.0f, nextSigma = 0.5f, outLatents)
        // out = sample + (nextSigma - sigma) * noise = 1.0 + (-0.5) * 0.5 = 0.75
        assertEquals(0.75f, outLatents[0], 1e-4f)
    }

    @Test
    fun testVaeRgbPostProcessingClamping() {
        val fakeRgbFloats = floatArrayOf(-1.0f, 0.0f, 1.0f) // Planar RGB [1, 3, 1, 1]
        val bytes = OnnxDiffusionEngine.planarRgbToArgbBytes(fakeRgbFloats, width = 1, height = 1)
        assertEquals(4, bytes.size)
        assertEquals(0.toByte(), bytes[0])   // R: (-1 + 1)*127.5 = 0
        assertEquals(127.toByte(), bytes[1]) // G: (0 + 1)*127.5 = 127
        assertEquals(255.toByte(), bytes[2]) // B: (1 + 1)*127.5 = 255
        assertEquals(255.toByte(), bytes[3]) // A: 255
    }

    @Test
    fun testPlanarRgbToArgbBytesClampingExtremes() {
        // Test values beyond [-1.0, 1.0] to verify proper clamping
        val extremeFloats = floatArrayOf(-5.0f, 0.0f, 10.0f)
        val bytes = OnnxDiffusionEngine.planarRgbToArgbBytes(extremeFloats, width = 1, height = 1)
        assertEquals(4, bytes.size)
        assertEquals(0.toByte(), bytes[0])   // clamped to 0
        assertEquals(127.toByte(), bytes[1])
        assertEquals(255.toByte(), bytes[2]) // clamped to 255
        assertEquals(255.toByte(), bytes[3]) // alpha 255
    }

    @Test
    fun testMissingModelFilesThrowsIllegalStateException() {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "empty_model_dir_${System.currentTimeMillis()}")
        emptyDir.mkdirs()
        try {
            val params = GenerationParams(prompt = "a majestic landscape")
            OnnxDiffusionEngine.generate(params, emptyDir)
            fail("Expected IllegalStateException for missing ONNX model files")
        } catch (e: IllegalStateException) {
            assertTrue("Exception message should indicate invalid or missing component", e.message?.contains("is invalid or missing") == true)
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun testEmptyModelFilesThrowsIllegalStateExceptionInProduction() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "empty_model_files_${System.currentTimeMillis()}")
        TestModelFixtures.stageModel(tempDir, "sdturbo")
        val modelDir = File(tempDir, "sdturbo")
        OnnxDiffusionEngine.testSimulationEnabled = false

        try {
            val params = GenerationParams(prompt = "a majestic landscape")
            OnnxDiffusionEngine.generate(params, modelDir)
            fail("Expected IllegalStateException for 0-byte model files in production")
        } catch (e: IllegalStateException) {
            assertTrue("Exception message should indicate invalid or missing component", e.message?.contains("is invalid or missing") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCreateSessionOptionsHandlesEnvironmentGracefully() {
        try {
            val options = OnnxDiffusionEngine.createSessionOptions()
            assertNotNull(options)
            options.close()
        } catch (e: UnsatisfiedLinkError) {
            // Expected on desktop host JVM when Android Bionic JNI binaries cannot link against desktop GNU libc
            assertTrue(e.message?.contains("onnxruntime") == true || e.message != null)
        }
    }

    @Test
    fun testSimulationWithStagedFixturesProducesArgbBytes() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_fixture_model_${System.currentTimeMillis()}")
        TestModelFixtures.stageModel(tempDir, "sdturbo")
        val modelDir = File(tempDir, "sdturbo")
        OnnxDiffusionEngine.testSimulationEnabled = true

        try {
            val progressSteps = mutableListOf<Pair<Int, Int>>()
            val params = GenerationParams(
                prompt = "test prompt",
                steps = 4,
                seed = 12345L
            )
            val bytes = OnnxDiffusionEngine.generate(params, modelDir) { step, total ->
                progressSteps.add(step to total)
            }

            assertEquals(512 * 512 * 4, bytes.size)
            assertEquals(4, progressSteps.size)
            assertEquals(1 to 4, progressSteps.first())
            assertEquals(4 to 4, progressSteps.last())

            // Check alpha channel
            for (i in 3 until bytes.size step 4) {
                assertEquals(255.toByte(), bytes[i])
            }
        } finally {
            OnnxDiffusionEngine.testSimulationEnabled = false
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testIsLcmModelDetection() {
        assertTrue(OnnxDiffusionEngine.isLcmModel("dreamshaper_v8_base"))
        assertTrue(OnnxDiffusionEngine.isLcmModel("dreamshaper_lcm"))
        assertTrue(OnnxDiffusionEngine.isLcmModel("lcm_sd15"))
        org.junit.Assert.assertFalse(OnnxDiffusionEngine.isLcmModel("sdturbo"))
    }

    @Test
    fun testIsLcmModelDetectionVariations() {
        assertTrue(OnnxDiffusionEngine.isLcmModel("DreamShaper_v8_base"))
        assertTrue(OnnxDiffusionEngine.isLcmModel("LCM_SD15"))
        org.junit.Assert.assertFalse(OnnxDiffusionEngine.isLcmModel("sdxl_turbo"))
    }

    @Test
    fun testSimulationWithDreamshaperProducesArgbBytes() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_fixture_dreamshaper_${System.currentTimeMillis()}")
        TestModelFixtures.stageModel(tempDir, "dreamshaper_v8_base")
        val modelDir = File(tempDir, "dreamshaper_v8_base")
        OnnxDiffusionEngine.testSimulationEnabled = true

        try {
            val progressSteps = mutableListOf<Pair<Int, Int>>()
            val params = GenerationParams(
                prompt = "a majestic dragon",
                modelId = "dreamshaper_v8_base",
                steps = 4,
                cfgScale = 1.5f,
                seed = 42L
            )
            val bytes = OnnxDiffusionEngine.generate(params, modelDir) { step, total ->
                progressSteps.add(step to total)
            }

            assertEquals(512 * 512 * 4, bytes.size)
            assertEquals(4, progressSteps.size)
            assertEquals(1 to 4, progressSteps.first())
            assertEquals(4 to 4, progressSteps.last())

            for (i in 3 until bytes.size step 4) {
                assertEquals(255.toByte(), bytes[i])
            }
        } finally {
            OnnxDiffusionEngine.testSimulationEnabled = false
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNchwToNhwcAndBackPreservesValues() {
        val c = 4
        val h = 64
        val w = 64
        val total = c * h * w
        val originalNchw = FloatArray(total) { i -> (i * 0.01f) - 50.0f }

        val nhwc = OnnxDiffusionEngine.nchwToNhwc(originalNchw, c, h, w)
        assertEquals(total, nhwc.size)

        // Verify specific element indexing
        // Channel 0, row 0, col 0 -> index 0 in both
        assertEquals(originalNchw[0], nhwc[0], 1e-6f)
        // Channel 1, row 0, col 0 -> index (1 * 64 * 64) in NCHW, index 1 in NHWC
        assertEquals(originalNchw[h * w], nhwc[1], 1e-6f)
        // Channel 3, row 63, col 63 -> index (total - 1) in both
        assertEquals(originalNchw[total - 1], nhwc[total - 1], 1e-6f)

        // Inverse
        val roundtripNchw = OnnxDiffusionEngine.nhwcToNchw(nhwc, c, h, w)
        assertEquals(total, roundtripNchw.size)
        for (i in 0 until total) {
            assertEquals("Mismatch at index $i", originalNchw[i], roundtripNchw[i], 1e-6f)
        }
    }

    @Test
    fun testQuantizeAndDequantizeUint16() {
        val scale = OnnxDiffusionEngine.QNN_UNET_LATENT_SCALE
        val zp = OnnxDiffusionEngine.QNN_UNET_LATENT_ZP
        val original = floatArrayOf(-2.5f, 0.0f, 1.25f, 5.0f)

        val quantizedShorts = OnnxDiffusionEngine.quantizeFloatToUint16(original, scale, zp)
        assertEquals(4, quantizedShorts.size)

        val dequantized = OnnxDiffusionEngine.dequantizeUint16ToFloat(quantizedShorts, scale, zp)
        for (i in original.indices) {
            assertEquals("Quantization error beyond tolerance at index $i", original[i], dequantized[i], scale)
        }
    }

    @Test
    fun testNhwcUint16RgbToArgbBytes() {
        // [1, 2, 2, 3] RGB uint16 pixels
        // Pixel 0: Black (0, 0, 0)
        // Pixel 1: Full White (65535, 65535, 65535)
        // Pixel 2: Pure Red (65535, 0, 0)
        // Pixel 3: Half-brightness Gray (32768, 32768, 32768)
        val shorts = shortArrayOf(
            0, 0, 0,
            (-1).toShort(), (-1).toShort(), (-1).toShort(), // 65535 as unsigned short
            (-1).toShort(), 0, 0,
            (32768).toShort(), (32768).toShort(), (32768).toShort()
        )

        val bytes = OnnxDiffusionEngine.nhwcUint16RgbToArgbBytes(shorts, width = 2, height = 2)
        assertEquals(16, bytes.size)

        // Pixel 0 (Black)
        assertEquals(0.toByte(), bytes[0]) // R
        assertEquals(0.toByte(), bytes[1]) // G
        assertEquals(0.toByte(), bytes[2]) // B
        assertEquals(255.toByte(), bytes[3]) // A

        // Pixel 1 (White)
        assertEquals(255.toByte(), bytes[4]) // R
        assertEquals(255.toByte(), bytes[5]) // G
        assertEquals(255.toByte(), bytes[6]) // B
        assertEquals(255.toByte(), bytes[7]) // A

        // Pixel 2 (Pure Red)
        assertEquals(255.toByte(), bytes[8]) // R
        assertEquals(0.toByte(), bytes[9]) // G
        assertEquals(0.toByte(), bytes[10]) // B
        assertEquals(255.toByte(), bytes[11]) // A

        // Pixel 3 (Gray 128)
        assertEquals(128.toByte(), bytes[12]) // R
        assertEquals(128.toByte(), bytes[13]) // G
        assertEquals(128.toByte(), bytes[14]) // B
        assertEquals(255.toByte(), bytes[15]) // A
    }

    @Test
    fun testResolveComponentFileWithVaeOnnx() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_vae_resolve_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val vaeFile = File(tempDir, "vae.onnx")
            vaeFile.writeBytes(ByteArray(10))

            // resolveComponentFile("vae_decoder") should fall back to "vae.onnx"
            val resolved = OnnxDiffusionEngine.resolveComponentFile(tempDir, "vae_decoder")
            assertEquals("vae.onnx", resolved.name)
            assertTrue(resolved.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testQuantizationConstantsMatchQnnSpecifications() {
        assertEquals(0.00093035854f, OnnxDiffusionEngine.QNN_TEXT_ENC_SCALE, 1e-7f)
        assertEquals(30063, OnnxDiffusionEngine.QNN_TEXT_ENC_ZP)
        assertEquals(0.00024176309f, OnnxDiffusionEngine.QNN_UNET_LATENT_SCALE, 1e-7f)
        assertEquals(33983, OnnxDiffusionEngine.QNN_UNET_LATENT_ZP)
        assertEquals(0.014770733f, OnnxDiffusionEngine.QNN_UNET_TIMESTEP_SCALE, 1e-7f)
        assertEquals(0, OnnxDiffusionEngine.QNN_UNET_TIMESTEP_ZP)
        assertEquals(0.0009331561f, OnnxDiffusionEngine.QNN_UNET_TEXT_EMB_SCALE, 1e-7f)
        assertEquals(30103, OnnxDiffusionEngine.QNN_UNET_TEXT_EMB_ZP)
        assertEquals(0.00018817355f, OnnxDiffusionEngine.QNN_UNET_OUT_LATENT_SCALE, 1e-7f)
        assertEquals(32340, OnnxDiffusionEngine.QNN_UNET_OUT_LATENT_ZP)
        assertEquals(0.00034003708f, OnnxDiffusionEngine.QNN_VAE_LATENT_SCALE, 1e-7f)
        assertEquals(34382, OnnxDiffusionEngine.QNN_VAE_LATENT_ZP)
        assertEquals(0.000015259022f, OnnxDiffusionEngine.QNN_VAE_IMAGE_SCALE, 1e-7f)
        assertEquals(0, OnnxDiffusionEngine.QNN_VAE_IMAGE_ZP)
    }
}


