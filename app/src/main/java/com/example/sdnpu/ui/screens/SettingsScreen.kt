package com.example.sdnpu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.engine.BackendStatus

@Composable
fun SettingsScreen(
    backendStatus: BackendStatus,
    localModels: List<String>,
    onOpenDownloadDialog: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Hardware & Engine Status", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Active Backend: ${backendStatus.backendName}", style = MaterialTheme.typography.titleMedium)
                Text("HTP / NPU Available: ${if (backendStatus.isHtpAvailable) "Yes (Hexagon v73)" else "No / Emulated"}")
                Text("QNN SDK Version: ${backendStatus.versionString}")
                Text("Status: ${backendStatus.statusMessage}", style = MaterialTheme.typography.bodySmall)
            }
        }

        HorizontalDivider()

        Text("Model Management", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onOpenDownloadDialog, modifier = Modifier.fillMaxWidth()) {
            Text("Download Model from Local Server")
        }

        Text("Downloaded Models (${localModels.size}):", style = MaterialTheme.typography.titleSmall)
        if (localModels.isEmpty()) {
            Text("None found in internal storage.", style = MaterialTheme.typography.bodyMedium)
        } else {
            localModels.forEach { modelName ->
                ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(modelName, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}
