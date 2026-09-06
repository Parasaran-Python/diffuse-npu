package com.example.sdnpu.pipeline

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PipelineManagerTest {
    private val pipelineManager = PipelineManager()

    @Test
    fun testSuccessfulGenerationFlowWithoutUpscale() = runBlocking {
        val params = GenerationParams(
            prompt = "a serene mountain lake",
            steps = 10,
            upscaleMode = UpscaleMode.OFF
        )

        val states = pipelineManager.runGeneration(params).toList()

        assertTrue(states.isNotEmpty())
        assertTrue("First state should be LoadingModel", states.first() is PipelineState.LoadingModel)
        assertEquals("dreamshaper_v8", (states.first() as PipelineState.LoadingModel).modelId)

        val generatingStates = states.filterIsInstance<PipelineState.Generating>()
        assertEquals(10, generatingStates.size)
        assertEquals(1, generatingStates.first().step)
        assertEquals(10, generatingStates.last().step)

        val upscaleStates = states.filterIsInstance<PipelineState.Upscaling>()
        assertTrue("No upscaling expected when OFF", upscaleStates.isEmpty())

        assertTrue("Last state should be Completed", states.last() is PipelineState.Completed)
        val completed = states.last() as PipelineState.Completed
        assertEquals("Generation finished successfully", completed.message)
        assertTrue(completed.executionTimeMs >= 0)
    }

    @Test
    fun testSuccessfulGenerationFlowWithUpscale() = runBlocking {
        val params = GenerationParams(
            prompt = "a majestic eagle in flight",
            steps = 10,
            upscaleMode = UpscaleMode.X2
        )

        val states = pipelineManager.generate(params).toList()

        val upscaleStates = states.filterIsInstance<PipelineState.Upscaling>()
        assertEquals(2, upscaleStates.size)
        assertEquals(2, upscaleStates[0].scale)
        assertEquals(0, upscaleStates[0].progressPercent)
        assertEquals(2, upscaleStates[1].scale)
        assertEquals(100, upscaleStates[1].progressPercent)

        assertTrue(states.last() is PipelineState.Completed)
    }

    @Test
    fun testGenerationWithInvalidParamsEmitsError() = runBlocking {
        val invalidParams = GenerationParams(
            prompt = "   ",
            steps = 20
        )

        val states = pipelineManager.runGeneration(invalidParams).toList()

        assertEquals(1, states.size)
        assertTrue(states[0] is PipelineState.Error)
        val error = states[0] as PipelineState.Error
        assertEquals("Prompt cannot be empty", error.error)
    }

    @Test
    fun testGenerateImageSavesFileAndEmitsCompleted(): Unit = runBlocking {
        val testOutputDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_pipeline_gen_${System.currentTimeMillis()}")
        val customPipelineManager = PipelineManager(outputDir = testOutputDir)

        val params = GenerationParams(
            prompt = "a cute puppy running on grass",
            steps = 10,
            upscaleMode = UpscaleMode.OFF
        )

        val states = customPipelineManager.generateImage(params).toList()
        val completed = states.last() as PipelineState.Completed

        assertNotNull(completed.imagePath)
        val file = java.io.File(completed.imagePath!!)
        assertTrue("Generated image file should exist on disk", file.exists())
        assertTrue("Generated image file should have non-zero size", file.length() > 0)

        // Clean up
        file.delete()
        testOutputDir.delete()
    }

    @Test
    fun testBatchGenerationProducesMultipleImages(): Unit = runBlocking {
        val testOutputDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_pipeline_batch_${System.currentTimeMillis()}")
        val customPipelineManager = PipelineManager(outputDir = testOutputDir)

        val params = GenerationParams(
            prompt = "a majestic lion in savanna",
            steps = 10,
            batchCount = 2,
            upscaleMode = UpscaleMode.OFF
        )

        val states = customPipelineManager.runGeneration(params).toList()
        val completedStates = states.filterIsInstance<PipelineState.Completed>()
        assertEquals(2, completedStates.size)

        val imagePaths = completedStates.mapNotNull { it.imagePath }
        assertEquals(2, imagePaths.size)
        assertNotEquals(imagePaths[0], imagePaths[1])

        imagePaths.forEach { path ->
            val f = java.io.File(path)
            assertTrue("Generated batch file should exist", f.exists())
            assertTrue("Generated batch file should have content", f.length() > 0)
            f.delete()
        }
        testOutputDir.delete()
    }
}
