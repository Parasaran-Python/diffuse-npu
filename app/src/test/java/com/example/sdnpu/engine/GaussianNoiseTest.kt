package com.example.sdnpu.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GaussianNoiseTest {
    @Test
    fun testGeneratedLatentsHaveUnitVarianceAndZeroMean() {
        val seed = 42L
        val size = 1 * 4 * 64 * 64 // 16384 floats
        val noise = GaussianNoise.generate(size, seed)

        assertEquals(size, noise.size)

        var sum = 0.0
        for (v in noise) sum += v
        val mean = sum / size
        assertTrue("Mean should be near 0.0, was $mean", abs(mean) < 0.08)

        var varianceSum = 0.0
        for (v in noise) varianceSum += (v - mean) * (v - mean)
        val variance = varianceSum / size
        assertTrue("Variance should be near 1.0, was $variance", abs(variance - 1.0) < 0.1)
    }

    @Test
    fun testSameSeedProducesIdenticalNoise() {
        val a = GaussianNoise.generate(100, 12345L)
        val b = GaussianNoise.generate(100, 12345L)
        assertArrayEquals(a, b, 0.0001f)
    }

    @Test
    fun testDifferentSeedsProduceDifferentNoise() {
        val a = GaussianNoise.generate(100, 12345L)
        val b = GaussianNoise.generate(100, 54321L)
        var differs = false
        for (i in a.indices) {
            if (abs(a[i] - b[i]) > 1e-4f) {
                differs = true
                break
            }
        }
        assertTrue("Different seeds should produce different noise", differs)
    }

    @Test
    fun testUnseededProducesNoise() {
        val noise = GaussianNoise.generate(100)
        assertEquals(100, noise.size)
    }
}
