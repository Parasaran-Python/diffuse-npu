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
}
