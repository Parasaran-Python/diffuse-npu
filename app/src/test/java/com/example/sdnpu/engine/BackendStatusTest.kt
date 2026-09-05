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
}
