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
}
