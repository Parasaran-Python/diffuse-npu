package com.example.sdnpu.engine.npu

import com.example.sdnpu.engine.BackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuDeviceDetectorTest {

    @Test
    fun testDetectQualcommSnapdragon8Gen2() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "qcom",
            buildManufacturer = "Samsung",
            buildSocManufacturer = "Qualcomm",
            buildSocModel = "SM8550"
        )
        assertEquals(SocVendor.QUALCOMM, info.vendor)
        assertEquals("v73", info.htpVersion)
        assertTrue(info.supportedBackendTypes.contains(BackendType.QNN_HTP))
        assertTrue(info.supportedBackendTypes.contains(BackendType.NNAPI_NPU))
        assertTrue(info.supportedBackendTypes.contains(BackendType.CPU))
        assertEquals(BackendType.QNN_HTP, info.supportedBackendTypes.first())
    }

    @Test
    fun testDetectQualcommSnapdragon8Gen3() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "pineapple",
            buildManufacturer = "Qualcomm",
            buildSocManufacturer = "Qualcomm",
            buildSocModel = "SM8650"
        )
        assertEquals(SocVendor.QUALCOMM, info.vendor)
        assertEquals("v75", info.htpVersion)
        assertEquals(BackendType.QNN_HTP, info.supportedBackendTypes.first())
    }

    @Test
    fun testDetectQualcommSnapdragon8Elite() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "sun",
            buildManufacturer = "Qualcomm",
            buildSocManufacturer = "Qualcomm",
            buildSocModel = "SM8750"
        )
        assertEquals(SocVendor.QUALCOMM, info.vendor)
        assertEquals("v79", info.htpVersion)
    }

    @Test
    fun testDetectQualcommSnapdragon8Gen1() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "taro",
            buildManufacturer = "Qualcomm",
            buildSocManufacturer = "Qualcomm",
            buildSocModel = "SM8450"
        )
        assertEquals(SocVendor.QUALCOMM, info.vendor)
        assertEquals("v69", info.htpVersion)
    }

    @Test
    fun testDetectQualcommSnapdragon888() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "lahaina",
            buildManufacturer = "Qualcomm",
            buildSocManufacturer = "Qualcomm",
            buildSocModel = "SM8350"
        )
        assertEquals(SocVendor.QUALCOMM, info.vendor)
        assertEquals("v68", info.htpVersion)
    }

    @Test
    fun testDetectMediaTekDimensity() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "mt6989",
            buildManufacturer = "Xiaomi",
            buildSocManufacturer = "MediaTek",
            buildSocModel = "Dimensity 9300"
        )
        assertEquals(SocVendor.MEDIATEK, info.vendor)
        assertNull(info.htpVersion)
        assertEquals(BackendType.MEDIATEK_APU, info.supportedBackendTypes.first())
        assertTrue(info.supportedBackendTypes.contains(BackendType.NNAPI_NPU))
        assertTrue(info.supportedBackendTypes.contains(BackendType.CPU))
    }

    @Test
    fun testDetectGoogleTensor() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "zuma",
            buildManufacturer = "Google",
            buildSocManufacturer = "Google",
            buildSocModel = "Tensor G3"
        )
        assertEquals(SocVendor.GOOGLE, info.vendor)
        assertNull(info.htpVersion)
        assertEquals(BackendType.GOOGLE_TPU, info.supportedBackendTypes.first())
        assertTrue(info.supportedBackendTypes.contains(BackendType.NNAPI_NPU))
        assertTrue(info.supportedBackendTypes.contains(BackendType.CPU))
    }

    @Test
    fun testDetectSamsungExynos() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "s5e9945",
            buildManufacturer = "Samsung",
            buildSocManufacturer = "Samsung",
            buildSocModel = "Exynos 2400"
        )
        assertEquals(SocVendor.SAMSUNG, info.vendor)
        assertNull(info.htpVersion)
        assertEquals(BackendType.EXYNOS_NPU, info.supportedBackendTypes.first())
        assertTrue(info.supportedBackendTypes.contains(BackendType.NNAPI_NPU))
        assertTrue(info.supportedBackendTypes.contains(BackendType.CPU))
    }

    @Test
    fun testDetectGenericUnknownDevice() {
        val info = NpuDeviceDetector.detect(
            buildHardware = "goldfish",
            buildManufacturer = "Generic",
            buildSocManufacturer = "",
            buildSocModel = ""
        )
        assertEquals(SocVendor.UNKNOWN, info.vendor)
        assertNull(info.htpVersion)
        assertEquals(BackendType.NNAPI_NPU, info.supportedBackendTypes.first())
        assertTrue(info.supportedBackendTypes.contains(BackendType.CPU))
    }
}
