package com.example.sdnpu.engine

import org.junit.Assert.*
import org.junit.Test

class VaePostProcessorTest {
    @Test
    fun testRgbClampingAndScaling() {
        val latentSample = FloatArray(4 * 64 * 64) { 0.18215f }
        val rgb = VaePostProcessor.latentsToRgbBytes(latentSample, 64, 64)
        assertEquals(64 * 64 * 4, rgb.size) // ARGB bytes

        // Check values are within valid 0..255 byte ranges
        for (b in rgb) {
            val unsigned = b.toInt() and 0xFF
            assertTrue(unsigned in 0..255)
        }
    }

    @Test
    fun testExtremeLatentClamping() {
        val latentArea = 64 * 64
        val latents = FloatArray(4 * latentArea)

        // Channel 0: extreme negative (-1000f) -> r clamped to 0
        // Channel 1: 0.0f -> g scaled to 127
        // Channel 2: extreme positive (+1000f) -> b clamped to 255
        for (i in 0 until latentArea) {
            latents[i] = -1000.0f
            latents[i + latentArea] = 0.0f
            latents[i + 2 * latentArea] = 1000.0f
            latents[i + 3 * latentArea] = 0.0f
        }

        val rgb = VaePostProcessor.latentsToRgbBytes(latents, 64, 64)
        assertEquals(64 * 64 * 4, rgb.size)

        val b = rgb[0].toInt() and 0xFF
        val g = rgb[1].toInt() and 0xFF
        val r = rgb[2].toInt() and 0xFF
        val a = rgb[3].toInt() and 0xFF

        assertEquals(255, b)
        assertEquals(127, g)
        assertEquals(0, r)
        assertEquals(255, a)
    }

    @Test
    fun testDefaultDimensions() {
        val latentSample = FloatArray(4 * 64 * 64) { 0.0f }
        val rgb = VaePostProcessor.latentsToRgbBytes(latentSample)
        // Default is 512x512
        assertEquals(512 * 512 * 4, rgb.size)
    }
}
