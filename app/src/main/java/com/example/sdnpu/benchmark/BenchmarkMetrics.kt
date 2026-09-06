package com.example.sdnpu.benchmark

data class StageLatency(
    val stageName: String,
    val durationMs: Long,
    val details: String = ""
)

data class BenchmarkReport(
    val backend: String,
    val totalDurationMs: Long,
    val stages: List<StageLatency>,
    val avgStepLatencyMs: Float,
    val memoryDeltaMb: Long,
    val success: Boolean,
    val errorMessage: String? = null
) {
    fun getStageLatency(stageName: String): Long {
        return stages.find { it.stageName.equals(stageName, ignoreCase = true) }?.durationMs ?: -1L
    }
}
