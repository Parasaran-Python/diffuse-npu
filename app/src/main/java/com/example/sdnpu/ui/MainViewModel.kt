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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelManager = ModelManager(File(application.filesDir, "models"))
    private val pipelineManager = PipelineManager(
        modelsDir = File(application.filesDir, "models"),
        outputDir = File(application.filesDir, "generations")
    )

    private var generationJob: Job? = null
    private var downloadJob: Job? = null

    private val _params = MutableStateFlow(GenerationParams(prompt = ""))
    val params: StateFlow<GenerationParams> = _params.asStateFlow()

    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    private val _backendStatus = MutableStateFlow(
        BackendStatus("Reference CPU", false, true, "2.49.0.260730", "Ready")
    )
    val backendStatus: StateFlow<BackendStatus> = _backendStatus.asStateFlow()

    private val _localModels = MutableStateFlow<List<String>>(emptyList())
    val localModels: StateFlow<List<String>> = _localModels.asStateFlow()

    init {
        refreshBackend()
        refreshLocalModels()
    }

    fun updateParams(newParams: GenerationParams) {
        _params.value = newParams
    }

    fun refreshBackend() {
        viewModelScope.launch(Dispatchers.IO) {
            if (QnnNativeBridge.isLibraryLoaded()) {
                QnnNativeBridge.nativeInitBackend(BackendType.HTP_NPU.id)
                val status = QnnNativeBridge.nativeGetBackendStatus()
                _backendStatus.value = status
            }
        }
    }

    fun refreshLocalModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val models = modelManager.listLocalModels()
            _localModels.value = models
        }
    }

    fun startGeneration() {
        if (generationJob?.isActive == true) return

        generationJob = viewModelScope.launch {
            pipelineManager.runGeneration(_params.value).collect { state ->
                _pipelineState.value = state
            }
        }
    }

    fun downloadModelFromUrl(url: String) {
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            val manifestRes = modelManager.fetchManifest(url)
            if (manifestRes.isFailure) {
                _downloadStatus.value = DownloadStatus.Failed("Cannot fetch manifest: ${manifestRes.exceptionOrNull()?.message}")
                return@launch
            }
            val manifest = manifestRes.getOrThrow()
            modelManager.downloadModel(manifest, url).collect { status ->
                _downloadStatus.value = status
                if (status is DownloadStatus.Completed) {
                    refreshLocalModels()
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadStatus.value = DownloadStatus.Idle
    }
}
