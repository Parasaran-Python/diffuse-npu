package com.example.sdnpu.model

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    data class FetchingManifest(val url: String) : DownloadStatus()
    data class DownloadingComponent(
        val componentName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : DownloadStatus()
    data class Paused(
        val modelId: String,
        val componentName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int
    ) : DownloadStatus()
    data class VerifyingChecksum(val componentName: String) : DownloadStatus()
    data class Completed(val modelId: String, val modelDir: String) : DownloadStatus()
    data class Failed(val reason: String) : DownloadStatus()
}
