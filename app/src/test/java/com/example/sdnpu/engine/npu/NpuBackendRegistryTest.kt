package com.example.sdnpu.engine.npu

import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.npu.backends.*
import com.example.sdnpu.model.ModelExecutionProfile
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NpuBackendRegistryTest {

    @Test
    fun testAllBackendsRegistered() {
        assertNotNull(NpuBackendRegistry.getBackend(BackendType.QNN_HTP))
        assertNotNull(NpuBackendRegistry.getBackend(BackendType.MEDIATEK_APU))
        assertNotNull(NpuBackendRegistry.getBackend(BackendType.GOOGLE_TPU))
        assertNotNull(NpuBackendRegistry.getBackend(BackendType.EXYNOS_NPU))
        assertNotNull(NpuBackendRegistry.getBackend(BackendType.NNAPI_NPU))
        assertNotNull(NpuBackendRegistry.getBackend(BackendType.CPU))

        assertTrue(NpuBackendRegistry.getBackend(BackendType.QNN_HTP) is QnnHtpBackend)
        assertTrue(NpuBackendRegistry.getBackend(BackendType.MEDIATEK_APU) is MediaTekApuBackend)
        assertTrue(NpuBackendRegistry.getBackend(BackendType.GOOGLE_TPU) is GoogleTensorBackend)
        assertTrue(NpuBackendRegistry.getBackend(BackendType.EXYNOS_NPU) is SamsungExynosBackend)
        assertTrue(NpuBackendRegistry.getBackend(BackendType.NNAPI_NPU) is NnapiBackend)
        assertTrue(NpuBackendRegistry.getBackend(BackendType.CPU) is CpuBackend)
    }

    @Test
    fun testCpuBackendAlwaysAvailable() {
        val cpuBackend = NpuBackendRegistry.getBackend(BackendType.CPU)
        assertNotNull(cpuBackend)
        assertTrue(cpuBackend!!.isAvailable)
        assertEquals(TensorLayout.NCHW, cpuBackend.defaultLayout)
    }

    @Test
    fun testQnnHtpBackendDefaultLayoutIsNhwc() {
        val qnnBackend = NpuBackendRegistry.getBackend(BackendType.QNN_HTP)
        assertNotNull(qnnBackend)
        assertEquals(TensorLayout.NHWC, qnnBackend!!.defaultLayout)
    }

    @Test
    fun testQnnHtpCanExecuteValidatesModelFile() {
        val qnnBackend = NpuBackendRegistry.getBackend(BackendType.QNN_HTP) as QnnHtpBackend
        val nonExistent = File("/does/not/exist/model.onnx")
        assertFalse(qnnBackend.canExecute(nonExistent, null))

        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_qnn_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val smallFile = File(tempDir, "model.onnx")
            smallFile.writeBytes(ByteArray(1024))
            assertTrue(qnnBackend.canExecute(smallFile, null))

            val largeUncompiled = File(tempDir, "text_encoder.onnx")
            java.io.RandomAccessFile(largeUncompiled, "rw").use { it.setLength(60_000_000L) }
            assertFalse(qnnBackend.canExecute(largeUncompiled, null))
            assertTrue(qnnBackend.canExecute(largeUncompiled, ModelExecutionProfile.SD15_QNN_PRECOMPILED))

            val teBin = File(tempDir, "text_encoder_qairt_context.bin")
            teBin.writeBytes(ByteArray(100))
            assertTrue(qnnBackend.canExecute(largeUncompiled, null))

            val ctxFile = File(tempDir, "unet.onnx")
            val binFile = File(tempDir, "unet_qairt_context.bin")
            ctxFile.writeBytes(ByteArray(100))
            binFile.writeBytes(ByteArray(100))
            assertTrue(qnnBackend.canExecute(ctxFile, ModelExecutionProfile.SD15_QNN_PRECOMPILED))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGetBestBackendForDeviceReturnsNonNull() {
        val bestBackend = NpuBackendRegistry.getBestBackendForDevice()
        assertNotNull(bestBackend)
        assertTrue(bestBackend.isAvailable)
    }

    @Test
    fun testUserSelectedCpuBackendTakesPriority() {
        val cpuBackend = NpuBackendRegistry.getBackend(BackendType.CPU)
        assertNotNull(cpuBackend)
        assertTrue(cpuBackend!!.isAvailable)
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_user_cpu_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val modelFile = File(tempDir, "test.onnx")
            modelFile.writeBytes(ByteArray(100))
            assertTrue(cpuBackend.canExecute(modelFile, null))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFormatOrtErrorMessageTranslatesBiasGelu() {
        val flatModelFile = File("/models/sdturbo/text_encoder.onnx")
        val fakeException = RuntimeException("Failed to find kernel for com.microsoft.BiasGelu(1) (node:'BiasGelu' ep:'CPUExecutionProvider')")
        val msg1 = NpuBackendRegistry.formatOrtErrorMessage(flatModelFile, fakeException)
        assertTrue(msg1.contains("Model 'sdturbo'"))
        assertTrue(msg1.contains("BiasGelu/Gelu"))
        assertTrue(msg1.contains("SD 1.5 (Snapdragon NPU)"))

        val nestedModelFile = File("/models/sdturbo/text_encoder/model.onnx")
        val msg2 = NpuBackendRegistry.formatOrtErrorMessage(nestedModelFile, fakeException)
        assertTrue(msg2.contains("Model 'sdturbo'"))
    }
}
