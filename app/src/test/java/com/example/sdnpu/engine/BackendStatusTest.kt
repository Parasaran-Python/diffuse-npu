package com.example.sdnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendStatusTest {
    @Test
    fun testBackendTypeValues() {
        assertEquals(0, BackendType.CPU.id)
        assertEquals(1, BackendType.GPU.id)
        assertEquals(2, BackendType.HTP_NPU.id)
    }

    @Test
    fun testBackendStatusInterpretation() {
        val status = BackendStatus(
            backendName = "HTP (Hexagon v73)",
            isHtpAvailable = true,
            isLoaded = true,
            versionString = "2.49.0.260730",
            statusMessage = "HTP backend initialized successfully"
        )
        assertTrue(status.isHtpAvailable)
        assertTrue(status.isLoaded)
        assertEquals("HTP (Hexagon v73)", status.backendName)
    }

    @Test
    fun testBackendTypeFromId() {
        assertEquals(BackendType.CPU, BackendType.fromId(0))
        assertEquals(BackendType.GPU, BackendType.fromId(1))
        assertEquals(BackendType.HTP_NPU, BackendType.fromId(2))
        // Fallback test for unknown / invalid IDs
        assertEquals(BackendType.CPU, BackendType.fromId(-1))
        assertEquals(BackendType.CPU, BackendType.fromId(999))
    }
}
