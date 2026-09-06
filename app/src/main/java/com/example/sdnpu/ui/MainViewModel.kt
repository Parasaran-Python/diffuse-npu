package com.example.sdnpu.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sdnpu.data.AppSettings
import com.example.sdnpu.data.GenerationDao
import com.example.sdnpu.data.GenerationEntity
import com.example.sdnpu.data.HistoryRepository
import com.example.sdnpu.data.SettingsRepository
import com.example.sdnpu.engine.BackendStatus
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.QnnNativeBridge
import com.example.sdnpu.model.DownloadStatus
import com.example.sdnpu.model.ModelManager
import com.example.sdnpu.model.ModelManifest
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineManager
import com.example.sdnpu.pipeline.PipelineState
import com.example.sdnpu.pipeline.SamplerType
import com.example.sdnpu.pipeline.UpscaleMode
import com.example.sdnpu.system.DeviceMonitor
import com.example.sdnpu.system.GenerationNotificationManager
import com.example.sdnpu.system.ThermalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(
    application: Application? = null,
    private val modelManager: ModelManager = ModelManager(
        baseStorageDir = if (application != null) {
            application.getExternalFilesDir(null)?.let { File(it, "models") } ?: File(application.filesDir, "models")
        } else File(System.getProperty("java.io.tmpdir"), "models"),
        secondaryStorageDir = if (application != null) File(application.filesDir, "models") else null
    ),

    private val historyRepository: HistoryRepository = if (application != null) {
        HistoryRepository.getInstance(application)
    } else {
        HistoryRepository(
            dao = object : GenerationDao {
                private val records = mutableListOf<GenerationEntity>()
                override fun insert(entity: GenerationEntity) = 1L
                override fun getAll(): List<GenerationEntity> = records.toList()
                override fun getById(id: Long): GenerationEntity? = null
                override fun search(query: String): List<GenerationEntity> = emptyList()
                override fun delete(id: Long): Boolean = true
                override fun deleteBatch(ids: Set<Long>): Int = 0
                override fun clearAll() {}
            },
            imagesDirProvider = { File(System.getProperty("java.io.tmpdir"), "generations") }
        )
    },
    private val settingsRepository: SettingsRepository = if (application != null) {
        SettingsRepository.getInstance(application)
    } else {
        SettingsRepository(
            dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { File(System.getProperty("java.io.tmpdir"), "test_settings.preferences_pb") }
            )
        )
    },
    private val deviceMonitor: DeviceMonitor = if (application != null) {
        DeviceMonitor.getInstance(application)
    } else {
        DeviceMonitor()
    },
    private val notificationManager: GenerationNotificationManager = GenerationNotificationManager(application),
    private val pipelineManager: PipelineManager = PipelineManager(
        modelsDir = if (application != null) {
            application.getExternalFilesDir(null)?.let { File(it, "models") } ?: File(application.filesDir, "models")
        } else File(System.getProperty("java.io.tmpdir"), "models"),
        outputDir = if (application != null) File(application.filesDir, "generations")
        else File(System.getProperty("java.io.tmpdir"), "generations"),
        historyRepository = historyRepository,
        secondaryModelsDir = if (application != null) File(application.filesDir, "models") else null
    )
) : ViewModel() {

    // Secondary constructor for AndroidViewModelFactory compatibility
    constructor(application: Application) : this(
        application = application,
        modelManager = ModelManager(
            baseStorageDir = application.getExternalFilesDir(null)?.let { File(it, "models") } ?: File(application.filesDir, "models"),
            secondaryStorageDir = File(application.filesDir, "models")
        ),
        historyRepository = HistoryRepository.getInstance(application),
        settingsRepository = SettingsRepository.getInstance(application),
        deviceMonitor = DeviceMonitor.getInstance(application),
        notificationManager = GenerationNotificationManager(application)
    )

    private var generationJob: Job? = null
    private var downloadJob: Job? = null

    private val _params = MutableStateFlow(
        GenerationParams(
            prompt = "",
            modelId = "sdturbo",
            steps = 1,
            cfgScale = 1.0f,
            sampler = SamplerType.EULER_A,
            batchCount = 1,
            upscaleMode = UpscaleMode.OFF
        )
    )
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

    // Settings
    val appSettings: StateFlow<AppSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    // History & Gallery
    val historyList: StateFlow<List<GenerationEntity>> = historyRepository.historyList

    private val _historyQuery = MutableStateFlow("")
    val historyQuery: StateFlow<String> = _historyQuery.asStateFlow()

    val filteredHistory: StateFlow<List<GenerationEntity>> = combine(
        historyList,
        _historyQuery
    ) { list, query ->
        if (query.isBlank()) list
        else list.filter {
            it.prompt.contains(query, ignoreCase = true) ||
            it.negativePrompt.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedHistoryIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedHistoryIds: StateFlow<Set<Long>> = _selectedHistoryIds.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = combine(_selectedHistoryIds) {
        it[0].isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Device Health & Thermal
    val thermalStatus: StateFlow<ThermalStatus> = deviceMonitor.thermalStatus
    val batteryLevel: StateFlow<Int> = deviceMonitor.batteryLevel
    val isCharging: StateFlow<Boolean> = deviceMonitor.isCharging
    val isLowBattery: StateFlow<Boolean> = deviceMonitor.isLowBattery

    init {
        refreshBackend()
        refreshLocalModels()
        viewModelScope.launch {
            historyRepository.scanAndSyncFileSystem()
        }
        viewModelScope.launch {
            combine(appSettings, thermalStatus) { settings, thermal ->
                when {
                    thermal.isThrottlingSevere() -> com.example.sdnpu.model.HtpPowerProfile.POWER_SAVER
                    settings.highPerformanceMode -> com.example.sdnpu.model.HtpPowerProfile.HIGH_PERFORMANCE
                    else -> com.example.sdnpu.model.HtpPowerProfile.DEFAULT
                }
            }.collect { profile ->
                QnnNativeBridge.setHtpPerformanceProfile(profile)
            }
        }
    }

    fun updateParams(newParams: GenerationParams) {
        _params.value = newParams
    }

    fun populateParamsFromHistory(record: GenerationEntity) {
        val mode = when (record.upscaleMode) {
            4 -> UpscaleMode.X4
            2 -> UpscaleMode.X2
            else -> UpscaleMode.OFF
        }
        val samplerType = SamplerType.entries.getOrElse(record.sampler) { SamplerType.EULER_A }
        _params.value = _params.value.copy(
            prompt = record.prompt,
            negativePrompt = record.negativePrompt,
            modelId = record.modelName,
            seed = record.seed,
            steps = record.steps,
            cfgScale = record.cfgScale,
            sampler = samplerType,
            upscaleMode = mode
        )
    }

    fun setHistoryQuery(query: String) {
        _historyQuery.value = query
    }

    fun toggleHistorySelection(id: Long) {
        val current = _selectedHistoryIds.value
        _selectedHistoryIds.value = if (id in current) current - id else current + id
    }

    fun selectAllHistory() {
        _selectedHistoryIds.value = filteredHistory.value.map { it.id }.toSet()
    }

    fun clearHistorySelection() {
        _selectedHistoryIds.value = emptySet()
    }

    fun deleteSelectedHistory(deleteFiles: Boolean = true) {
        val ids = _selectedHistoryIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            deleteSelectedHistorySuspend(deleteFiles)
        }
    }

    suspend fun deleteSelectedHistorySuspend(deleteFiles: Boolean = true): Int {
        val ids = _selectedHistoryIds.value
        if (ids.isEmpty()) return 0
        val count = historyRepository.deleteBatch(ids, deleteFiles = deleteFiles)
        _selectedHistoryIds.value = emptySet()
        return count
    }

    fun deleteHistoryItem(id: Long, deleteFile: Boolean = true) {
        viewModelScope.launch {
            deleteHistoryItemSuspend(id, deleteFile)
        }
    }

    suspend fun deleteHistoryItemSuspend(id: Long, deleteFile: Boolean = true): Boolean {
        val success = historyRepository.delete(id, deleteFile = deleteFile)
        _selectedHistoryIds.value = _selectedHistoryIds.value - id
        return success
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
            val prompt = _params.value.prompt
            pipelineManager.runGeneration(_params.value).collect { state ->
                _pipelineState.value = state
                when (state) {
                    is PipelineState.Generating -> {
                        notificationManager.notifyGenerationProgress(state.step, state.totalSteps, prompt)
                    }
                    is PipelineState.Upscaling -> {
                        notificationManager.notifyGenerationProgress(
                            step = (state.progress * 100).toInt(),
                            total = 100,
                            prompt = "Upscaling: $prompt"
                        )
                    }
                    is PipelineState.Completed -> {
                        state.imagePath?.let { path ->
                            notificationManager.notifyGenerationCompleted(path, prompt)
                        }
                    }
                    is PipelineState.Error -> {
                        notificationManager.cancel()
                    }
                    else -> {}
                }
            }
        }
    }

    fun cancelGeneration() {
        pipelineManager.cancel()
        notificationManager.cancel()
        generationJob?.cancel()
        generationJob = null
        _pipelineState.value = PipelineState.Idle
    }

    fun downloadModelFromUrl(url: String, modelId: String? = null) {
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            val manifestRes = modelManager.fetchManifest(url)
            val manifest = if (manifestRes.isSuccess) {
                manifestRes.getOrThrow()
            } else {
                val resolvedId = modelId ?: when {
                    url.contains("sd-turbo", ignoreCase = true) || url.contains("sdturbo", ignoreCase = true) -> "sdturbo"
                    url.contains("anime", ignoreCase = true) -> "dreamshaper_v8_anime"
                    url.contains("realistic", ignoreCase = true) -> "dreamshaper_v8_realistic"
                    else -> "sdturbo"
                }
                when (resolvedId) {
                    "sdturbo" -> ModelManifest.sdturbo()
                    "dreamshaper_v8_base" -> ModelManifest.dreamshaper_v8_base()
                    "dreamshaper_v8_anime" -> ModelManifest.dreamshaper_v8_anime()
                    "dreamshaper_v8_realistic" -> ModelManifest.dreamshaper_v8_realistic()
                    else -> {
                        _downloadStatus.value = DownloadStatus.Failed("Cannot fetch manifest: ${manifestRes.exceptionOrNull()?.message}")
                        return@launch
                    }
                }
            }
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

    fun isRealESRGANAvailable(scale: Int): Boolean = modelManager.isRealESRGANAvailable(scale)

    fun deleteLocalModel(modelName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            modelManager.deleteModel(modelName)
            refreshLocalModels()
        }
    }

    fun clearGenerationCache() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.clearAll(deleteFiles = true)
        }
    }

    // Settings update functions
    fun updateDefaultSteps(steps: Int) {
        viewModelScope.launch { settingsRepository.updateSteps(steps) }
    }

    fun updateDefaultCfgScale(cfg: Float) {
        viewModelScope.launch { settingsRepository.updateCfgScale(cfg) }
    }

    fun updateDefaultSampler(sampler: Int) {
        viewModelScope.launch { settingsRepository.updateSampler(sampler) }
    }

    fun updateDefaultBatchCount(count: Int) {
        viewModelScope.launch { settingsRepository.updateBatchCount(count) }
    }

    fun updateDefaultUpscaleMode(mode: Int) {
        viewModelScope.launch { settingsRepository.updateUpscaleMode(mode) }
    }

    fun updateBackendPreference(backend: String) {
        viewModelScope.launch { settingsRepository.updateBackend(backend) }
    }

    fun updateThermalWarningEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateThermalWarning(enabled) }
    }

    fun updateHighPerformanceMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateHighPerformanceMode(enabled) }
    }

    fun updateDarkThemeMode(theme: String) {
        viewModelScope.launch { settingsRepository.updateDarkTheme(theme) }
    }
}
