package com.example.sdnpu.engine

import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.SamplerType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SDEngineTest {
    @Test
    fun testGenerateReturnsCorrectByteSize() {
        val params = GenerationParams(
            prompt = "a majestic mountain landscape at sunset",
            steps = 20,
            cfgScale = 7.5f,
            seed = 42L,
            sampler = SamplerType.EULER_A
        )
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_models")
        val bytes = SDEngine.generate(params, tempDir)

        assertNotNull(bytes)
        assertEquals(512 * 512 * 4, bytes.size)

        // Verify alpha channel is 255 for all pixels
        for (i in 3 until bytes.size step 4) {
            assertEquals(255.toByte(), bytes[i])
        }
    }

    @Test
    fun testGenerateStepProgressCallback() {
        val steps = 15
        val params = GenerationParams(
            prompt = "cyberpunk city in neon rain",
            steps = steps,
            seed = 100L
        )
        val progressList = mutableListOf<Pair<Int, Int>>()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_models")

        val bytes = SDEngine.generate(params, tempDir) { step, total ->
            progressList.add(step to total)
        }

        assertNotNull(bytes)
        assertEquals(512 * 512 * 4, bytes.size)
        assertEquals(steps, progressList.size)
        assertEquals(1 to steps, progressList.first())
        assertEquals(steps to steps, progressList.last())
    }

    @Test
    fun testDeterministicGenerationWithSeed() {
        val params = GenerationParams(
            prompt = "a cute golden retriever puppy",
            steps = 10,
            seed = 9999L
        )
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_models")

        val bytes1 = SDEngine.generate(params, tempDir)
        val bytes2 = SDEngine.generate(params, tempDir)

        assertArrayEquals("Same seed must produce identical outputs in simulation", bytes1, bytes2)
    }

    @Test
    fun testDifferentSeedsProduceDifferentOutputs() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_models")
        val params1 = GenerationParams(
            prompt = "photorealistic portrait of an astronaut",
            steps = 10,
            seed = 1111L
        )
        val params2 = params1.copy(seed = 2222L)

        val bytes1 = SDEngine.generate(params1, tempDir)
        val bytes2 = SDEngine.generate(params2, tempDir)

        assertFalse("Different seeds must produce different latent/image outputs", bytes1.contentEquals(bytes2))
    }
}
