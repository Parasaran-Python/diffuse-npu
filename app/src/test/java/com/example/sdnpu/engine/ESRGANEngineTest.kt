package com.example.sdnpu.engine

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ESRGANEngineTest {

    @Test
    fun testUpscale2xFrom64x64ProducesCorrectDimensions() {
        val inW = 64
        val inH = 64
        val scale = 2
        val inputBytes = ByteArray(inW * inH * 4) { (it % 256).toByte() }

        val outputBytes = ESRGANEngine.upscale(inputBytes, inW, inH, scale)

        assertNotNull(outputBytes)
        val expectedSize = (inW * scale) * (inH * scale) * 4
        assertEquals(expectedSize, outputBytes.size)
    }

    @Test
    fun testUpscale4xFrom64x64ProducesCorrectDimensions() {
        val inW = 64
        val inH = 64
        val scale = 4
        val inputBytes = ByteArray(inW * inH * 4) { (it % 256).toByte() }

        val outputBytes = ESRGANEngine.upscale(inputBytes, inW, inH, scale)

        assertNotNull(outputBytes)
        val expectedSize = (inW * scale) * (inH * scale) * 4
        assertEquals(expectedSize, outputBytes.size)
    }

    @Test
    fun testUpscale2xFrom512x512ProducesCorrectDimensions() {
        val inW = 512
        val inH = 512
        val scale = 2
        val inputBytes = ByteArray(inW * inH * 4)

        val outputBytes = ESRGANEngine.upscale(inputBytes, inW, inH, scale)

        assertNotNull(outputBytes)
        val expectedSize = 1024 * 1024 * 4
        assertEquals(expectedSize, outputBytes.size)
    }

    @Test
    fun testChannelIntegrityAndAlphaChannel() {
        val inW = 8
        val inH = 8
        val scale = 2
        val inputBytes = ByteArray(inW * inH * 4)
        val testR = 120
        val testG = 180
        val testB = 60
        val testA = 255

        for (i in 0 until (inW * inH)) {
            inputBytes[i * 4] = testR.toByte()
            inputBytes[i * 4 + 1] = testG.toByte()
            inputBytes[i * 4 + 2] = testB.toByte()
            inputBytes[i * 4 + 3] = testA.toByte()
        }

        val outputBytes = ESRGANEngine.upscale(inputBytes, inW, inH, scale)
        val outPixels = (inW * scale) * (inH * scale)

        for (i in 0 until outPixels) {
            val r = outputBytes[i * 4].toInt() and 0xFF
            val g = outputBytes[i * 4 + 1].toInt() and 0xFF
            val b = outputBytes[i * 4 + 2].toInt() and 0xFF
            val a = outputBytes[i * 4 + 3].toInt() and 0xFF

            assertEquals("Alpha channel must always be 255", 255, a)
            assertTrue("Red channel must preserve constant color ($r vs $testR)", abs(r - testR) <= 1)
            assertTrue("Green channel must preserve constant color ($g vs $testG)", abs(g - testG) <= 1)
            assertTrue("Blue channel must preserve constant color ($b vs $testB)", abs(b - testB) <= 1)
        }
    }

    @Test
    fun testCancellationThrowsException() {
        val inW = 128
        val inH = 128
        val scale = 2
        val inputBytes = ByteArray(inW * inH * 4)

        var calls = 0
        try {
            ESRGANEngine.upscale(inputBytes, inW, inH, scale) { progress ->
                calls++
                if (progress > 0.05f) {
                    ESRGANEngine.cancel()
                }
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertTrue("Progress should have been reported before cancellation", calls > 0)
        }
    }

    @Test
    fun testInvalidScaleThrowsIllegalArgumentException() {
        val inputBytes = ByteArray(16 * 16 * 4)
        for (invalidScale in listOf(0, 1, 3, 5, 8)) {
            try {
                ESRGANEngine.upscale(inputBytes, 16, 16, invalidScale)
                fail("Expected IllegalArgumentException for scale=$invalidScale")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun testInvalidDimensionsThrowIllegalArgumentException() {
        val inputBytes = ByteArray(16 * 16 * 4)
        try {
            ESRGANEngine.upscale(inputBytes, 0, 16, 2)
            fail("Expected IllegalArgumentException for inWidth <= 0")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            ESRGANEngine.upscale(inputBytes, 16, -1, 2)
            fail("Expected IllegalArgumentException for inHeight <= 0")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testMismatchedByteArrayThrowsIllegalArgumentException() {
        val smallBytes = ByteArray(100)
        try {
            ESRGANEngine.upscale(smallBytes, 16, 16, 2)
            fail("Expected IllegalArgumentException for mismatched buffer length")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testProgressCallbackReportsProgress() {
        val inW = 32
        val inH = 32
        val scale = 2
        val inputBytes = ByteArray(inW * inH * 4)
        val progressValues = mutableListOf<Float>()

        ESRGANEngine.upscale(inputBytes, inW, inH, scale) { progress ->
            progressValues.add(progress)
        }

        assertTrue("Progress callback should be invoked", progressValues.isNotEmpty())
        assertTrue("Initial progress should be >= 0.0f", progressValues.first() >= 0.0f)
        assertTrue("Final progress should reach 1.0f", progressValues.last() >= 0.99f)
    }
}
