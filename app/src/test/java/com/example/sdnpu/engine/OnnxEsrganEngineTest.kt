package com.example.sdnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlinx.coroutines.CancellationException

class OnnxEsrganEngineTest {

    @Test
    fun testRgbaToPlanarFloatAndBack() {
        val width = 2
        val height = 2
        val rgba = byteArrayOf(
            255.toByte(), 0, 0, 255.toByte(),
            0, 255.toByte(), 0, 255.toByte(),
            0, 0, 255.toByte(), 255.toByte(),
            128.toByte(), 128.toByte(), 128.toByte(), 255.toByte()
        )
        val floats = OnnxEsrganEngine.rgbaToPlanarRgbFloats(rgba, width, height)
        assertEquals(3 * width * height, floats.size)
        // Red channel of first pixel should be 1.0f
        assertEquals(1.0f, floats[0], 0.01f)

        val reconstructed = OnnxEsrganEngine.planarRgbFloatsToRgba(floats, width, height)
        assertEquals(rgba.size, reconstructed.size)
        assertEquals(rgba[0], reconstructed[0])
        assertEquals(rgba[1], reconstructed[1])
        assertEquals(rgba[2], reconstructed[2])
        assertEquals(rgba[3], reconstructed[3])
    }

    @Test
    fun testRgbaToPlanarFloatInvalidBufferSize() {
        val invalidRgba = ByteArray(10)
        try {
            OnnxEsrganEngine.rgbaToPlanarRgbFloats(invalidRgba, 2, 2)
            fail("Expected IllegalArgumentException for mismatched buffer size")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("mismatch") == true)
        }
    }

    @Test
    fun testPlanarFloatToRgbaInvalidBufferSize() {
        val invalidFloats = FloatArray(5)
        try {
            OnnxEsrganEngine.planarRgbFloatsToRgba(invalidFloats, 2, 2)
            fail("Expected IllegalArgumentException for undersized float buffer")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("mismatch") == true)
        }
    }

    @Test
    fun testPlanarFloatClampingExtremes() {
        val floats = floatArrayOf(
            -1.0f, 0.5f,
            2.0f, Float.NaN,
            -0.5f, 0.0f,
            1.5f, 1.0f,
            0.0f, 1.0f,
            0.0f, 1.0f
        )
        val rgba = OnnxEsrganEngine.planarRgbFloatsToRgba(floats, 2, 2)
        assertEquals(16, rgba.size)
        // Pixel 0: R = -1.0f -> clamped to 0
        assertEquals(0.toByte(), rgba[0])
        // Pixel 1: R = 0.5f -> ~128
        assertEquals(128.toByte(), rgba[4])
        // Pixel 2: R = 2.0f -> clamped to 255
        assertEquals(255.toByte(), rgba[8])
        // Pixel 3: R = NaN -> 0
        assertEquals(0.toByte(), rgba[12])
        // Alpha must always be 255
        for (i in 3 until rgba.size step 4) {
            assertEquals(255.toByte(), rgba[i])
        }
    }

    @Test
    fun testUpscaleInvalidScaleThrowsIllegalArgumentException() {
        val bytes = ByteArray(4 * 4 * 4)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_esrgan_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            OnnxEsrganEngine.upscale(bytes, 4, 4, 3, tempDir)
            fail("Expected IllegalArgumentException for scale 3")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("scale factor") == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testUpscaleMissingModelThrowsIllegalStateException() {
        val bytes = ByteArray(4 * 4 * 4)
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "empty_esrgan_${System.currentTimeMillis()}")
        emptyDir.mkdirs()
        try {
            OnnxEsrganEngine.upscale(bytes, 4, 4, 2, emptyDir)
            fail("Expected IllegalStateException for missing ONNX model file")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No valid ONNX model found") == true)
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun testUpscaleSimulationProducesCorrectDimensions() {
        val inW = 16
        val inH = 16
        val scale = 2
        val inputBytes = ByteArray(inW * inH * 4) { (it % 256).toByte() }
        val tempDir = File(System.getProperty("java.io.tmpdir"), "staged_esrgan_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        File(tempDir, "model.onnx").writeBytes(ByteArray(100))

        OnnxEsrganEngine.testSimulationEnabled = true
        try {
            val progressList = mutableListOf<Float>()
            val outBytes = OnnxEsrganEngine.upscale(inputBytes, inW, inH, scale, tempDir) { progress ->
                progressList.add(progress)
            }

            assertEquals((inW * scale) * (inH * scale) * 4, outBytes.size)
            assertTrue(progressList.isNotEmpty())
            assertEquals(1.0f, progressList.last(), 0.01f)
        } finally {
            OnnxEsrganEngine.testSimulationEnabled = false
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testUpscaleSimulationCancellation() {
        val inW = 16
        val inH = 16
        val scale = 2
        val inputBytes = ByteArray(inW * inH * 4)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "staged_esrgan_cancel_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        File(tempDir, "model.onnx").writeBytes(ByteArray(100))

        OnnxEsrganEngine.testSimulationEnabled = true
        try {
            OnnxEsrganEngine.upscale(inputBytes, inW, inH, scale, tempDir) { progress ->
                if (progress >= 0.1f) {
                    OnnxEsrganEngine.cancel()
                }
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            // Expected
        } finally {
            OnnxEsrganEngine.testSimulationEnabled = false
            tempDir.deleteRecursively()
        }
    }
}
