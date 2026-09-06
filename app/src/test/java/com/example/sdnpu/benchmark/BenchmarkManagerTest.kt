package com.example.sdnpu.benchmark

import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.SamplerType
import com.example.sdnpu.pipeline.UpscaleMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkManagerTest {

    @Test
    fun testBenchmarkReportCalculations() {
        val stages = listOf(
            StageLatency("ClipEncoding", 45L),
            StageLatency("UnetDenoising", 1200L, "20 steps"),
            StageLatency("VaeDecoding", 250L),
            StageLatency("RealESRGAN", 400L, "2x upscale")
        )
        val report = BenchmarkReport(
            backend = "NPU",
            totalDurationMs = 1895L,
            stages = stages,
            avgStepLatencyMs = 60.0f,
            memoryDeltaMb = 120L,
            success = true
        )

        assertEquals("NPU", report.backend)
        assertEquals(1895L, report.totalDurationMs)
        assertEquals(4, report.stages.size)
        assertEquals(60.0f, report.avgStepLatencyMs, 0.001f)
        assertEquals(120L, report.memoryDeltaMb)
        assertTrue(report.success)
        assertNull(report.errorMessage)
        assertEquals(45L, report.getStageLatency("ClipEncoding"))
        assertEquals(1200L, report.getStageLatency("UnetDenoising"))
        assertEquals(250L, report.getStageLatency("vaedecoding"))
        assertEquals(-1L, report.getStageLatency("NonExistent"))
    }

    @Test
    fun testBenchmarkReportFailureState() {
        val report = BenchmarkReport(
            backend = "NPU",
            totalDurationMs = 0L,
            stages = emptyList(),
            avgStepLatencyMs = 0f,
            memoryDeltaMb = 0L,
            success = false,
            errorMessage = "NPU driver initialization timeout"
        )

        assertFalse(report.success)
        assertEquals("NPU driver initialization timeout", report.errorMessage)
        assertEquals(-1L, report.getStageLatency("ClipEncoding"))
    }

    @Test
    fun testRunSimulatedBenchmarkWithUpscaleX2() = runBlocking {
        val manager = BenchmarkManager()
        val params = GenerationParams(
            prompt = "A high-tech laboratory benchmark test",
            steps = 15,
            sampler = SamplerType.EULER_A,
            upscaleMode = UpscaleMode.X2
        )

        val report = manager.runBenchmark(params, simulate = true)
        assertTrue(report.success)
        assertTrue(report.totalDurationMs > 0)
        assertEquals(15, params.steps)
        assertTrue(report.stages.any { it.stageName == "ClipEncoding" })
        assertTrue(report.stages.any { it.stageName == "UnetDenoising" })
        assertTrue(report.stages.any { it.stageName == "VaeDecoding" })
        assertTrue(report.stages.any { it.stageName == "RealESRGAN" })
        assertTrue(report.avgStepLatencyMs >= 0f)
        assertEquals(300L, report.getStageLatency("RealESRGAN"))
    }

    @Test
    fun testRunSimulatedBenchmarkWithoutUpscale() = runBlocking {
        val manager = BenchmarkManager()
        val params = GenerationParams(
            prompt = "Quick preview generation",
            steps = 20,
            sampler = SamplerType.DPM_2M_KARRAS,
            upscaleMode = UpscaleMode.OFF
        )

        val report = manager.runBenchmark(params, simulate = true)
        assertTrue(report.success)
        assertTrue(report.stages.any { it.stageName == "ClipEncoding" })
        assertTrue(report.stages.any { it.stageName == "UnetDenoising" })
        assertTrue(report.stages.any { it.stageName == "VaeDecoding" })
        assertFalse(report.stages.any { it.stageName == "RealESRGAN" })
        assertEquals(-1L, report.getStageLatency("RealESRGAN"))
    }

    @Test
    fun testRunSimulatedBenchmarkWithUpscaleX4() = runBlocking {
        val manager = BenchmarkManager()
        val params = GenerationParams(
            prompt = "High resolution generation",
            steps = 25,
            sampler = SamplerType.DDIM,
            upscaleMode = UpscaleMode.X4
        )

        val report = manager.runBenchmark(params, simulate = true)
        assertTrue(report.success)
        assertEquals(900L, report.getStageLatency("RealESRGAN"))
    }

    @Test
    fun testCompareBackends() = runBlocking {
        val manager = BenchmarkManager()
        val params = GenerationParams(
            prompt = "Backend comparison test",
            steps = 10,
            upscaleMode = UpscaleMode.OFF
        )
        val comparison = manager.compareBackends(params, simulate = true)

        assertEquals(3, comparison.size)
        assertTrue(comparison.containsKey("NPU"))
        assertTrue(comparison.containsKey("GPU"))
        assertTrue(comparison.containsKey("CPU"))

        val npuReport = comparison["NPU"]
        val gpuReport = comparison["GPU"]
        val cpuReport = comparison["CPU"]

        assertNotNull(npuReport)
        assertNotNull(gpuReport)
        assertNotNull(cpuReport)

        assertTrue(npuReport!!.totalDurationMs <= gpuReport!!.totalDurationMs)
        assertTrue(gpuReport.totalDurationMs <= cpuReport!!.totalDurationMs)
        assertTrue(npuReport.avgStepLatencyMs <= gpuReport.avgStepLatencyMs)
        assertTrue(gpuReport.avgStepLatencyMs <= cpuReport.avgStepLatencyMs)

        // Verify stage latencies are also scaled
        assertEquals((npuReport.getStageLatency("ClipEncoding") * 1.8f).toLong(), gpuReport.getStageLatency("ClipEncoding"))
        assertEquals((npuReport.getStageLatency("ClipEncoding") * 6.5f).toLong(), cpuReport.getStageLatency("ClipEncoding"))
    }

    @Test
    fun testLiveBenchmarkWithPipelineManager() = runBlocking {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "bench-test-${System.currentTimeMillis()}").apply { mkdirs() }
        val modelsDir = java.io.File(tempDir, "models").apply { mkdirs() }
        val gensDir = java.io.File(tempDir, "generations").apply { mkdirs() }
        try {
            val pipeline = com.example.sdnpu.pipeline.PipelineManager(modelsDir, gensDir)
            val manager = BenchmarkManager(pipeline)
            val params = GenerationParams(
                prompt = "A live test prompt",
                steps = 10,
                upscaleMode = UpscaleMode.OFF
            )
            val report = manager.runBenchmark(params, simulate = false)
            assertTrue("Expected success but failed with: ${report.errorMessage}", report.success)
            assertTrue(report.totalDurationMs >= 0)
            assertTrue(report.stages.isNotEmpty())
            assertTrue(report.getStageLatency("ClipEncoding") >= 0)
            assertTrue(report.getStageLatency("UnetDenoising") >= 0)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
