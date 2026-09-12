package com.example.sdnpu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.example.sdnpu.ui.MainViewModel
import com.example.sdnpu.ui.navigation.NavTab
import com.example.sdnpu.ui.screens.GalleryScreen
import com.example.sdnpu.ui.screens.GenerateScreen
import com.example.sdnpu.ui.screens.ModelDownloadDialog
import com.example.sdnpu.ui.screens.SettingsScreen
import com.example.sdnpu.ui.theme.SdnpuTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(application) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        com.example.sdnpu.engine.OnnxDiffusionEngine.initAdspLibraryPath(applicationInfo.nativeLibraryDir)
        handleAutoGenerate(intent)
        setContent {
            val appSettings by viewModel.appSettings.collectAsState()
            val isDark = when (appSettings.darkThemeMode.uppercase()) {
                "LIGHT" -> false
                "DARK" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            SdnpuTheme(darkTheme = isDark) {
                var currentTab by rememberSaveable { mutableStateOf(NavTab.GENERATE) }
                var showDownloadDialog by rememberSaveable { mutableStateOf(false) }
                var targetDownloadModelId by rememberSaveable { mutableStateOf("sd15_qnn_npu") }

                val params by viewModel.params.collectAsState()
                val pipelineState by viewModel.pipelineState.collectAsState()
                val backendStatus by viewModel.backendStatus.collectAsState()
                val localModels by viewModel.localModels.collectAsState()
                val downloadStatus by viewModel.downloadStatus.collectAsState()

                val filteredHistory by viewModel.filteredHistory.collectAsState()
                val historyQuery by viewModel.historyQuery.collectAsState()
                val selectedHistoryIds by viewModel.selectedHistoryIds.collectAsState()
                val isSelectionMode by viewModel.isSelectionMode.collectAsState()

                val thermalStatus by viewModel.thermalStatus.collectAsState()
                val batteryLevel by viewModel.batteryLevel.collectAsState()
                val isCharging by viewModel.isCharging.collectAsState()
                val isLowBattery by viewModel.isLowBattery.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = { Icon(tab.icon, contentDescription = tab.title) },
                                    label = { Text(tab.title) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            NavTab.GENERATE -> GenerateScreen(
                                params = params,
                                pipelineState = pipelineState,
                                localModels = localModels,
                                downloadStatus = downloadStatus,
                                thermalStatus = thermalStatus,
                                batteryLevel = batteryLevel,
                                isCharging = isCharging,
                                isLowBattery = isLowBattery,
                                thermalWarningEnabled = appSettings.thermalWarningEnabled,
                                onParamsChange = { viewModel.updateParams(it) },
                                onGenerate = { viewModel.startGeneration() },
                                onCancel = { viewModel.cancelGeneration() },
                                onOpenDownloadDialog = { modelId ->
                                    targetDownloadModelId = modelId
                                    showDownloadDialog = true
                                },
                                onPauseDownload = { viewModel.pauseDownload() },
                                onResumeDownload = { viewModel.resumeDownload() }
                            )
                            NavTab.GALLERY -> GalleryScreen(
                                historyList = filteredHistory,
                                searchQuery = historyQuery,
                                selectedIds = selectedHistoryIds,
                                isSelectionMode = isSelectionMode,
                                onQueryChange = { viewModel.setHistoryQuery(it) },
                                onToggleSelection = { viewModel.toggleHistorySelection(it) },
                                onSelectAll = { viewModel.selectAllHistory() },
                                onClearSelection = { viewModel.clearHistorySelection() },
                                onDeleteSelected = { viewModel.deleteSelectedHistory(deleteFiles = true) },
                                onDeleteSingle = { viewModel.deleteHistoryItem(it, deleteFile = true) },
                                onPopulateParams = {
                                    viewModel.populateParamsFromHistory(it)
                                    currentTab = NavTab.GENERATE
                                }
                            )
                            NavTab.SETTINGS -> SettingsScreen(
                                appSettings = appSettings,
                                backendStatus = backendStatus,
                                localModels = localModels,
                                onOpenDownloadDialog = {
                                    targetDownloadModelId = "sdturbo"
                                    showDownloadDialog = true
                                },
                                onUpdateSteps = { viewModel.updateDefaultSteps(it) },
                                onUpdateCfgScale = { viewModel.updateDefaultCfgScale(it) },
                                onUpdateSampler = { viewModel.updateDefaultSampler(it) },
                                onUpdateBatchCount = { viewModel.updateDefaultBatchCount(it) },
                                onUpdateUpscaleMode = { viewModel.updateDefaultUpscaleMode(it) },
                                onUpdateBackend = { viewModel.updateBackendPreference(it) },
                                onUpdateThermalWarning = { viewModel.updateThermalWarningEnabled(it) },
                                onUpdateHighPerformanceMode = { viewModel.updateHighPerformanceMode(it) },
                                onUpdateDarkTheme = { viewModel.updateDarkThemeMode(it) },
                                onDeleteModel = { viewModel.deleteLocalModel(it) },
                                onClearCache = { viewModel.clearGenerationCache() }
                            )
                        }

                        if (showDownloadDialog) {
                            ModelDownloadDialog(
                                status = downloadStatus,
                                initialModelId = targetDownloadModelId,
                                onDismiss = { showDownloadDialog = false },
                                onStartDownload = { url, modelId -> viewModel.downloadModelFromUrl(url, modelId) },
                                onCancelDownload = { viewModel.cancelDownload() },
                                onPauseDownload = { viewModel.pauseDownload() },
                                onResumeDownload = { viewModel.resumeDownload() }
                            )
                        }
                    }
                }
            }
        }
        handleAutoGenerate(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoGenerate(intent)
    }

    private fun handleAutoGenerate(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("auto_generate", false) == true) {
            val prompt = intent.getStringExtra("prompt") ?: "A serene Japanese garden"
            val negativePrompt = intent.getStringExtra("negative_prompt") ?: ""
            val modelId = intent.getStringExtra("model_id") ?: viewModel.params.value.modelId
            val defaultSteps = if (modelId == "sd15_qnn_npu") 20 else viewModel.params.value.steps
            val steps = intent.getIntExtra("steps", defaultSteps)
            val defaultCfg = if (modelId == "sd15_qnn_npu") 7.5f else viewModel.params.value.cfgScale
            val cfgScale = intent.getFloatExtra("cfg_scale", defaultCfg)
            val preferredBackend = intent.getStringExtra("preferred_backend")
            val seed = if (intent.hasExtra("seed")) intent.getLongExtra("seed", 0L) else viewModel.params.value.seed
            val batchCount = intent.getIntExtra("batch_count", viewModel.params.value.batchCount)

            if (preferredBackend != null) {
                viewModel.updateBackendPreference(preferredBackend)
            }

            android.util.Log.i("MainActivity", "handleAutoGenerate received: prompt='$prompt', modelId='$modelId', steps=$steps, cfgScale=$cfgScale, seed=$seed, batchCount=$batchCount")
            lifecycleScope.launch {
                delay(600)
                viewModel.updateParams(
                    viewModel.params.value.copy(
                        prompt = prompt,
                        negativePrompt = negativePrompt,
                        modelId = modelId,
                        steps = steps,
                        cfgScale = cfgScale,
                        preferredBackend = preferredBackend,
                        seed = seed,
                        batchCount = batchCount
                    )
                )
                viewModel.startGeneration()
            }
        }
    }
}
