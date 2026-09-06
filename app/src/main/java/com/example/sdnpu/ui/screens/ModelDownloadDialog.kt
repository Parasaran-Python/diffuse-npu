package com.example.sdnpu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.model.DownloadStatus

@Composable
fun ModelDownloadDialog(
    status: DownloadStatus,
    onDismiss: () -> Unit,
    onStartDownload: (String) -> Unit
) {
    var serverUrl by remember { mutableStateOf("http://192.168.1.100:8080/models/dreamshaper_v8/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the URL of your local HTTP model server:")
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    modifier = Modifier.fillMaxWidth()
                )

                when (status) {
                    is DownloadStatus.DownloadingComponent -> {
                        Text("Downloading ${status.componentName} (${status.progressPercent}%)")
                        LinearProgressIndicator(
                            progress = { status.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is DownloadStatus.VerifyingChecksum -> {
                        Text("Verifying SHA-256 for ${status.componentName}...")
                    }
                    is DownloadStatus.Completed -> {
                        Text("Download and verification complete!", color = MaterialTheme.colorScheme.primary)
                    }
                    is DownloadStatus.Failed -> {
                        Text("Error: ${status.reason}", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStartDownload(serverUrl) },
                enabled = status !is DownloadStatus.DownloadingComponent
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
