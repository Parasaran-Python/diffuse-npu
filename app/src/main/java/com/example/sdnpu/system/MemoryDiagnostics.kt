package com.example.sdnpu.system

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

data class MemorySnapshot(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val usedRamMb: Long,
    val nativeHeapAllocatedMb: Long,
    val jvmHeapAllocatedMb: Long,
    val isLowMemory: Boolean
)

object MemoryDiagnostics {
    const val MAX_PEAK_RAM_MB = 4096L

    fun getMemorySnapshot(context: Context? = null): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val jvmHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        var nativeHeapMb = 0L
        try {
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        } catch (_: Throwable) {}

        if (context != null) {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (actManager != null) {
                val memInfo = ActivityManager.MemoryInfo()
                actManager.getMemoryInfo(memInfo)
                val totalMb = memInfo.totalMem / (1024 * 1024)
                val availMb = memInfo.availMem / (1024 * 1024)
                if (totalMb > 0) {
                    val usedMb = maxOf(0L, totalMb - availMb)
                    return MemorySnapshot(
                        totalRamMb = totalMb,
                        availableRamMb = availMb,
                        usedRamMb = usedMb,
                        nativeHeapAllocatedMb = nativeHeapMb,
                        jvmHeapAllocatedMb = jvmHeapMb,
                        isLowMemory = memInfo.lowMemory
                    )
                }
            }
        }

        val maxJvmMb = runtime.maxMemory() / (1024 * 1024)
        val freeJvmMb = runtime.freeMemory() / (1024 * 1024)
        return MemorySnapshot(
            totalRamMb = maxOf(4096L, maxJvmMb * 2),
            availableRamMb = maxOf(1024L, freeJvmMb),
            usedRamMb = jvmHeapMb + nativeHeapMb,
            nativeHeapAllocatedMb = nativeHeapMb,
            jvmHeapAllocatedMb = jvmHeapMb,
            isLowMemory = false
        )
    }

    fun isMemorySafeForGeneration(requiredFreeMb: Long = 1500L, context: Context? = null): Boolean {
        val snapshot = getMemorySnapshot(context)
        val appAllocatedMb = snapshot.nativeHeapAllocatedMb + snapshot.jvmHeapAllocatedMb
        return snapshot.availableRamMb >= requiredFreeMb && !snapshot.isLowMemory && appAllocatedMb < MAX_PEAK_RAM_MB
    }

    fun verifySequentialContextLifecycle(sdLoaded: Boolean, esrganLoaded: Boolean): Boolean {
        return !(sdLoaded && esrganLoaded)
    }
}
