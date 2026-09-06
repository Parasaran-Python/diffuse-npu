package com.example.sdnpu.system

import org.junit.Assert.*
import org.junit.Test

class MemoryDiagnosticsTest {

    @Test
    fun testGetMemorySnapshotWithoutContext() {
        val snapshot = MemoryDiagnostics.getMemorySnapshot(null)
        assertTrue("totalRamMb must be positive", snapshot.totalRamMb > 0)
        assertTrue("availableRamMb must be positive", snapshot.availableRamMb > 0)
        assertTrue("jvmHeapAllocatedMb must be non-negative", snapshot.jvmHeapAllocatedMb >= 0)
        assertTrue("usedRamMb must be non-negative", snapshot.usedRamMb >= 0)
        assertFalse("isLowMemory should default to false in JVM test", snapshot.isLowMemory)
    }

    @Test
    fun testIsMemorySafeForGeneration() {
        val isSafe = MemoryDiagnostics.isMemorySafeForGeneration(requiredFreeMb = 100L, context = null)
        assertTrue("Modest 100MB requirement should be safe", isSafe)

        val isUnsafe = MemoryDiagnostics.isMemorySafeForGeneration(requiredFreeMb = 1_000_000L, context = null)
        assertFalse("Absurdly high 1TB requirement should be unsafe", isUnsafe)
    }

    @Test
    fun testSequentialContextLifecycleEnforcement() {
        // Safe cases: neither loaded or only one loaded
        assertTrue(MemoryDiagnostics.verifySequentialContextLifecycle(sdLoaded = false, esrganLoaded = false))
        assertTrue(MemoryDiagnostics.verifySequentialContextLifecycle(sdLoaded = true, esrganLoaded = false))
        assertTrue(MemoryDiagnostics.verifySequentialContextLifecycle(sdLoaded = false, esrganLoaded = true))

        // Unsafe case: both SD and ESRGAN loaded simultaneously (violates sequential lifecycle)
        assertFalse(MemoryDiagnostics.verifySequentialContextLifecycle(sdLoaded = true, esrganLoaded = true))
    }

    @Test
    fun testMemorySnapshotDataClassIntegrity() {
        val snapshot = MemorySnapshot(
            totalRamMb = 8192L,
            availableRamMb = 4096L,
            usedRamMb = 4096L,
            nativeHeapAllocatedMb = 1200L,
            jvmHeapAllocatedMb = 250L,
            isLowMemory = false
        )

        assertEquals(8192L, snapshot.totalRamMb)
        assertEquals(4096L, snapshot.availableRamMb)
        assertEquals(4096L, snapshot.usedRamMb)
        assertEquals(1200L, snapshot.nativeHeapAllocatedMb)
        assertEquals(250L, snapshot.jvmHeapAllocatedMb)
        assertFalse(snapshot.isLowMemory)
    }
}
