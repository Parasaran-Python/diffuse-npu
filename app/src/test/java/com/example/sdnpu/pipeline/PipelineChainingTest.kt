package com.example.sdnpu.pipeline

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class PipelineChainingTest {

    private lateinit var testOutputDir: File
    private lateinit var testModelsDir: File
    private lateinit var pipelineManager: PipelineManager

    @Before
    fun setUp() {
        val testId = System.currentTimeMillis()
        testOutputDir = File(System.getProperty("java.io.tmpdir"), "test_chaining_out_$testId")
        testModelsDir = File(System.getProperty("java.io.tmpdir"), "test_chaining_models_$testId")
        testOutputDir.mkdirs()
        testModelsDir.mkdirs()
        pipelineManager = PipelineManager(modelsDir = testModelsDir, outputDir = testOutputDir)
    }

    @After
    fun tearDown() {
        testOutputDir.deleteRecursively()
        testModelsDir.deleteRecursively()
    }

    @Test
    fun testPipelineChainingUpscaleX2Produces1024x1024Png() = runBlocking {
        val params = GenerationParams(
            prompt = "a majestic snow leopard in high altitude",
            steps = 10,
            upscaleMode = UpscaleMode.X2
        )

        val states = pipelineManager.runGeneration(params).toList()

        // 1. Verify sequence of states
        assertTrue("Flow states should not be empty", states.isNotEmpty())
        assertTrue("First state should be LoadingModel", states.first() is PipelineState.LoadingModel)

        val generatingStates = states.filterIsInstance<PipelineState.Generating>()
        assertEquals("Should emit 10 generating steps", 10, generatingStates.size)

        val upscaleStates = states.filterIsInstance<PipelineState.Upscaling>()
        assertTrue("Should emit upscaling states for X2", upscaleStates.isNotEmpty())
        for (st in upscaleStates) {
            assertEquals("Scale factor must be 2", 2, st.scale)
        }
        assertEquals("Initial upscale progress must start at 0", 0, upscaleStates.first().progressPercent)
        assertEquals("Final upscale progress must reach 100", 100, upscaleStates.last().progressPercent)

        assertTrue("Last state must be Completed", states.last() is PipelineState.Completed)
        val completed = states.last() as PipelineState.Completed
        assertNotNull("Completed state must include image path", completed.imagePath)

        val imageFile = File(completed.imagePath!!)
        assertTrue("Upscaled image file must exist on disk", imageFile.exists())
        assertTrue("File name must reflect x2 scaling", imageFile.name.contains("_x2.png"))

        // 2. Verify PNG dimensions match 1024x1024
        val (width, height) = readPngDimensions(imageFile)
        assertEquals("Width must be 1024 for 2x upscale", 1024, width)
        assertEquals("Height must be 1024 for 2x upscale", 1024, height)
    }

    @Test
    fun testPipelineChainingUpscaleX4Produces2048x2048Png() = runBlocking {
        val params = GenerationParams(
            prompt = "a cybernetic warrior portrait detailed",
            steps = 10,
            upscaleMode = UpscaleMode.X4
        )

        val states = pipelineManager.runGeneration(params).toList()

        val upscaleStates = states.filterIsInstance<PipelineState.Upscaling>()
        assertTrue("Should emit upscaling states for X4", upscaleStates.isNotEmpty())
        for (st in upscaleStates) {
            assertEquals("Scale factor must be 4", 4, st.scale)
        }
        assertEquals("Initial upscale progress must start at 0", 0, upscaleStates.first().progressPercent)
        assertEquals("Final upscale progress must reach 100", 100, upscaleStates.last().progressPercent)

        assertTrue("Last state must be Completed", states.last() is PipelineState.Completed)
        val completed = states.last() as PipelineState.Completed
        assertNotNull("Completed state must include image path", completed.imagePath)

        val imageFile = File(completed.imagePath!!)
        assertTrue("Upscaled image file must exist on disk", imageFile.exists())
        assertTrue("File name must reflect x4 scaling", imageFile.name.contains("_x4.png"))

        // 2. Verify PNG dimensions match 2048x2048
        val (width, height) = readPngDimensions(imageFile)
        assertEquals("Width must be 2048 for 4x upscale", 2048, width)
        assertEquals("Height must be 2048 for 4x upscale", 2048, height)
    }

    @Test
    fun testPipelineChainingUpscaleOffProduces512x512PngWithoutUpscaleStates() = runBlocking {
        val params = GenerationParams(
            prompt = "a lush green forest with waterfalls",
            steps = 10,
            upscaleMode = UpscaleMode.OFF
        )

        val states = pipelineManager.runGeneration(params).toList()

        val upscaleStates = states.filterIsInstance<PipelineState.Upscaling>()
        assertTrue("No upscaling states should be emitted when OFF", upscaleStates.isEmpty())

        assertTrue("Last state must be Completed", states.last() is PipelineState.Completed)
        val completed = states.last() as PipelineState.Completed
        assertNotNull("Completed state must include image path", completed.imagePath)

        val imageFile = File(completed.imagePath!!)
        assertTrue("Output image file must exist on disk", imageFile.exists())
        assertTrue("File name should not contain _x for unscaled output", !imageFile.name.contains("_x"))

        // 2. Verify PNG dimensions match 512x512
        val (width, height) = readPngDimensions(imageFile)
        assertEquals("Width must be 512 when upscaling is OFF", 512, width)
        assertEquals("Height must be 512 when upscaling is OFF", 512, height)
    }

    @Test
    fun testCancelStopsPipelineExecution() {
        pipelineManager.cancel()
    }

    @Test
    fun testBatchGenerationWithUpscalingX2ProducesMultiple1024x1024Images() = runBlocking {
        val params = GenerationParams(
            prompt = "cyberpunk city skyline",
            steps = 10,
            batchCount = 2,
            upscaleMode = UpscaleMode.X2
        )

        val states = pipelineManager.runGeneration(params).toList()
        val completedStates = states.filterIsInstance<PipelineState.Completed>()
        assertEquals(2, completedStates.size)

        for (comp in completedStates) {
            val file = File(comp.imagePath!!)
            assertTrue(file.exists())
            assertTrue(file.name.contains("_x2.png"))
            val (width, height) = readPngDimensions(file)
            assertEquals(1024, width)
            assertEquals(1024, height)
        }
    }

    private fun readPngDimensions(file: File): Pair<Int, Int> {
        require(file.exists()) { "PNG file does not exist: ${file.absolutePath}" }
        DataInputStream(file.inputStream().buffered()).use { dis ->
            val signature = ByteArray(8)
            dis.readFully(signature)
            val expectedSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertArrayEquals("Invalid PNG header signature", expectedSig, signature)

            val ihdrLength = dis.readInt()
            val ihdrType = ByteArray(4)
            dis.readFully(ihdrType)
            assertEquals("Expected IHDR chunk", "IHDR", String(ihdrType, Charsets.US_ASCII))

            val width = dis.readInt()
            val height = dis.readInt()
            return Pair(width, height)
        }
    }
}
