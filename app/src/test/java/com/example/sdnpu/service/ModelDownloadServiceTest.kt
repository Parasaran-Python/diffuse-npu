package com.example.sdnpu.service

import android.app.Application
import com.example.sdnpu.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadServiceTest {

    @Test
    fun testInitialStatusIsIdle() {
        assertEquals(DownloadStatus.Idle, ModelDownloadService.downloadStatus.value)
    }

    @Test
    fun testStatusTransitions() {
        val fetching = DownloadStatus.FetchingManifest("https://example.com")
        ModelDownloadService.downloadStatus.value = fetching
        assertEquals(fetching, ModelDownloadService.downloadStatus.value)

        val downloading = DownloadStatus.DownloadingComponent("unet.onnx", 100, 1000, 10)
        ModelDownloadService.downloadStatus.value = downloading
        assertEquals(downloading, ModelDownloadService.downloadStatus.value)

        val paused = DownloadStatus.Paused("sdturbo", "unet.onnx", 100, 1000, 10)
        ModelDownloadService.downloadStatus.value = paused
        assertEquals(paused, ModelDownloadService.downloadStatus.value)

        val completed = DownloadStatus.Completed("sdturbo", "/path/to/sdturbo")
        ModelDownloadService.downloadStatus.value = completed
        assertEquals(completed, ModelDownloadService.downloadStatus.value)

        val failed = DownloadStatus.Failed("Network error")
        ModelDownloadService.downloadStatus.value = failed
        assertEquals(failed, ModelDownloadService.downloadStatus.value)

        ModelDownloadService.downloadStatus.value = DownloadStatus.Idle
        assertEquals(DownloadStatus.Idle, ModelDownloadService.downloadStatus.value)
    }

    @Test
    fun testStaticHelperMethodsDoNotThrow() {
        val app = Application()
        ModelDownloadService.startDownload(app, "https://example.com/sd", "sdturbo")
        ModelDownloadService.pauseDownload(app)
        ModelDownloadService.resumeDownload(app)
        ModelDownloadService.cancelDownload(app)
    }
}
