package com.example.sdnpu.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.model.DownloadStatus
import com.example.sdnpu.model.ModelDownloadPresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadDialog(
    status: DownloadStatus,
    onDismiss: () -> Unit,
    onStartDownload: (url: String, modelId: String) -> Unit,
    onCancelDownload: () -> Unit = {},
    initialModelId: String = "sdturbo"
) {
    val presets = remember { ModelDownloadPresets.getPresets() }
    var selectedPresetId by remember {
        mutableStateOf(
            presets.find { it.id == initialModelId }?.id
                ?: presets.firstOrNull()?.id
                ?: "sdturbo"
        )
    }

    val selectedPreset = presets.find { it.id == selectedPresetId } ?: presets.first()
    var serverUrl by remember(selectedPresetId) {
        mutableStateOf(selectedPreset.defaultUrl)
    }

    val isDownloading = status is DownloadStatus.FetchingManifest ||
            status is DownloadStatus.DownloadingComponent ||
            status is DownloadStatus.VerifyingChecksum

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Download Model")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Select a recommended model preset or paste a custom Hugging Face / HTTP URL to override:",
                    style = MaterialTheme.typography.bodyMedium
                )

                // Presets Chip Row
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = (selectedPresetId == preset.id),
                            onClick = {
                                if (!isDownloading) {
                                    selectedPresetId = preset.id
                                    serverUrl = preset.defaultUrl
                                }
                            },
                            label = { Text(preset.name) },
                            enabled = !isDownloading
                        )
                    }
                }

                if (selectedPreset.description.isNotBlank()) {
                    Text(
                        text = selectedPreset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Editable Override URL text box
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                    },
                    label = { Text("Model URL (Override / Custom)") },
                    placeholder = { Text("https://huggingface.co/... or http://...") },
                    enabled = !isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    supportingText = {
                        Text("You can edit or paste any mirror/server URL directly.")
                    }
                )

                // Status displays
                when (status) {
                    is DownloadStatus.FetchingManifest -> {
                        Text("Connecting & checking model manifest...")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is DownloadStatus.DownloadingComponent -> {
                        val downloadedMb = status.bytesRead / (1024 * 1024)
                        val totalMb = if (status.totalBytes > 0) status.totalBytes / (1024 * 1024) else 0
                        val progressText = if (totalMb > 0) {
                            "${status.componentName}: ${status.progressPercent}% ($downloadedMb MB / $totalMb MB)"
                        } else {
                            "${status.componentName} (${status.progressPercent}%)"
                        }
                        Text(progressText, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { status.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is DownloadStatus.VerifyingChecksum -> {
                        Text("Verifying integrity for ${status.componentName}...", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is DownloadStatus.Completed -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Download complete! Model '${status.modelId}' is ready for generation.",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    is DownloadStatus.Failed -> {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Error: ${status.reason}",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (isDownloading) {
                Button(
                    onClick = onCancelDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Cancel Download")
                }
            } else {
                Button(
                    onClick = { onStartDownload(serverUrl.trim(), selectedPreset.id) },
                    enabled = serverUrl.isNotBlank()
                ) {
                    Text("Download")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(
                    when {
                        status is DownloadStatus.Completed -> "Done"
                        isDownloading -> "Hide / Background"
                        else -> "Close"
                    }
                )
            }
        }
    )
}
