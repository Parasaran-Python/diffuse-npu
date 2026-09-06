package com.example.sdnpu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SdnpuTheme {
                var currentTab by rememberSaveable { mutableStateOf(NavTab.GENERATE) }
                var showDownloadDialog by rememberSaveable { mutableStateOf(false) }

                val params by viewModel.params.collectAsState()
                val pipelineState by viewModel.pipelineState.collectAsState()
                val backendStatus by viewModel.backendStatus.collectAsState()
                val localModels by viewModel.localModels.collectAsState()
                val downloadStatus by viewModel.downloadStatus.collectAsState()

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
                                onParamsChange = { viewModel.updateParams(it) },
                                onGenerate = { viewModel.startGeneration() }
                            )
                            NavTab.GALLERY -> GalleryScreen()
                            NavTab.SETTINGS -> SettingsScreen(
                                backendStatus = backendStatus,
                                localModels = localModels,
                                onOpenDownloadDialog = { showDownloadDialog = true }
                            )
                        }

                        if (showDownloadDialog) {
                            ModelDownloadDialog(
                                status = downloadStatus,
                                onDismiss = { showDownloadDialog = false },
                                onStartDownload = { url -> viewModel.downloadModelFromUrl(url) },
                                onCancelDownload = { viewModel.cancelDownload() }
                            )
                        }
                    }
                }
            }
        }
    }
}
