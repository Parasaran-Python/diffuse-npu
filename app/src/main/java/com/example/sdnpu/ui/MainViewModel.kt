package com.example.sdnpu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sdnpu.engine.BackendStatus
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.QnnNativeBridge
import com.example.sdnpu.model.DownloadStatus
import com.example.sdnpu.model.ModelManager
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineManager
import com.example.sdnpu.pipeline.PipelineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelManager = ModelManager(File(application.filesDir, "models"))
    private val pipelineManager = PipelineManager()

    val params = MutableStateFlow(GenerationParams(prompt = ""))
    val pipelineState: MutableStateFlow<PipelineState> = MutableStateFlow(PipelineState.Idle)
    val downloadStatus: MutableStateFlow<DownloadStatus> = MutableStateFlow(DownloadStatus.Idle)
    val backendStatus = MutableStateFlow(
        BackendStatus("Reference CPU", false, true, "2.49.0.260730", "Ready")
    )
    val localModels = MutableStateFlow<List<String>>(emptyList())

    init {
        refreshBackend()
        refreshLocalModels()
    }

    fun refreshBackend() {
        if (QnnNativeBridge.isLibraryLoaded()) {
            QnnNativeBridge.nativeInitBackend(BackendType.HTP_NPU.id)
            backendStatus.value = QnnNativeBridge.nativeGetBackendStatus()
        }
    }

    fun refreshLocalModels() {
        localModels.value = modelManager.listLocalModels()
    }

    fun startGeneration() {
        viewModelScope.launch {
            pipelineManager.runGeneration(params.value).collect { state ->
                pipelineState.value = state
            }
        }
    }

    fun downloadModelFromUrl(url: String) {
        viewModelScope.launch {
            val manifestRes = modelManager.fetchManifest(url)
            if (manifestRes.isFailure) {
                downloadStatus.value = DownloadStatus.Failed("Cannot fetch manifest: ${manifestRes.exceptionOrNull()?.message}")
                return@launch
            }
            val manifest = manifestRes.getOrThrow()
            modelManager.downloadModel(manifest, url).collect { status ->
                downloadStatus.value = status
                if (status is DownloadStatus.Completed) {
                    refreshLocalModels()
                }
            }
        }
    }
}
