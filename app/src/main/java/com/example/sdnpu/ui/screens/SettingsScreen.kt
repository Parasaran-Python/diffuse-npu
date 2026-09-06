package com.example.sdnpu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.data.AppSettings
import com.example.sdnpu.engine.BackendStatus
import com.example.sdnpu.pipeline.SamplerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appSettings: AppSettings = AppSettings(),
    backendStatus: BackendStatus,
    localModels: List<String>,
    onOpenDownloadDialog: () -> Unit,
    onUpdateSteps: (Int) -> Unit = {},
    onUpdateCfgScale: (Float) -> Unit = {},
    onUpdateSampler: (Int) -> Unit = {},
    onUpdateBatchCount: (Int) -> Unit = {},
    onUpdateUpscaleMode: (Int) -> Unit = {},
    onUpdateBackend: (String) -> Unit = {},
    onUpdateThermalWarning: (Boolean) -> Unit = {},
    onUpdateHighPerformanceMode: (Boolean) -> Unit = {},
    onUpdateDarkTheme: (String) -> Unit = {},
    onDeleteModel: (String) -> Unit = {},
    onClearCache: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var samplerExpanded by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Preferences & Engine Configuration", style = MaterialTheme.typography.titleLarge)

        // 1. Generation Defaults Card
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Generation Defaults", style = MaterialTheme.typography.titleMedium)
                }

                Text("Default Steps: ${appSettings.defaultSteps}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = appSettings.defaultSteps.toFloat(),
                    onValueChange = { onUpdateSteps(it.toInt()) },
                    valueRange = 10f..50f,
                    steps = 39
                )

                Text("Default CFG Scale: ${"%.1f".format(appSettings.defaultCfgScale)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = appSettings.defaultCfgScale,
                    onValueChange = { onUpdateCfgScale(it) },
                    valueRange = 1.0f..20.0f
                )

                // Sampler Dropdown
                val currentSampler = SamplerType.entries.getOrElse(appSettings.defaultSampler) { SamplerType.EULER_A }
                ExposedDropdownMenuBox(
                    expanded = samplerExpanded,
                    onExpandedChange = { samplerExpanded = !samplerExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = currentSampler.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default Sampler") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = samplerExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = samplerExpanded,
                        onDismissRequest = { samplerExpanded = false }
                    ) {
                        SamplerType.entries.forEachIndexed { index, sampler ->
                            DropdownMenuItem(
                                text = { Text(sampler.displayName) },
                                onClick = {
                                    onUpdateSampler(index)
                                    samplerExpanded = false
                                }
                            )
                        }
                    }
                }

                Text("Default Batch Count: ${appSettings.defaultBatchCount}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = appSettings.defaultBatchCount.toFloat(),
                    onValueChange = { onUpdateBatchCount(it.toInt()) },
                    valueRange = 1f..4f,
                    steps = 2
                )

                Text("Default Super-Resolution Mode:", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0 to "Off", 1 to "2x", 2 to "4x").forEach { (mode, label) ->
                        FilterChip(
                            selected = appSettings.defaultUpscaleMode == mode,
                            onClick = { onUpdateUpscaleMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Hardware & Backend Selection
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Hardware & Compute Backend", style = MaterialTheme.typography.titleMedium)
                }

                Text("Active Backend: ${backendStatus.backendName}", style = MaterialTheme.typography.bodyMedium)
                Text("HTP / NPU: ${if (backendStatus.isHtpAvailable) "Detected (Hexagon v73)" else "Emulated / CPU Fallback"}", style = MaterialTheme.typography.bodySmall)
                Text("QAIRT / QNN SDK: ${backendStatus.versionString}", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(4.dp))
                Text("Target Acceleration Backend:", style = MaterialTheme.typography.labelLarge)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("NPU", "GPU", "CPU").forEach { backend ->
                        FilterChip(
                            selected = appSettings.backendPreference.equals(backend, ignoreCase = true),
                            onClick = { onUpdateBackend(backend) },
                            label = { Text(backend) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Thermal & Performance Controls
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Thermostat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Thermal & Power Management", style = MaterialTheme.typography.titleMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Thermal Warnings", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Warn before starting generation if device is running hot",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = appSettings.thermalWarningEnabled,
                        onCheckedChange = onUpdateThermalWarning
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("High Performance Mode", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Boost HTP clock frequencies for maximum generation speed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = appSettings.highPerformanceMode,
                        onCheckedChange = onUpdateHighPerformanceMode
                    )
                }
            }
        }

        // 4. UI Appearance / Theme
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Theme Appearance", style = MaterialTheme.typography.titleMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "System").forEach { (key, label) ->
                        FilterChip(
                            selected = appSettings.darkThemeMode.equals(key, ignoreCase = true),
                            onClick = { onUpdateDarkTheme(key) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 5. Model Management & Storage
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model Storage (${localModels.size} installed)", style = MaterialTheme.typography.titleMedium)

                Button(onClick = onOpenDownloadDialog, modifier = Modifier.fillMaxWidth()) {
                    Text("Download Additional Models")
                }

                if (localModels.isEmpty()) {
                    Text(
                        "No local models installed in app internal storage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    localModels.forEach { modelName ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(modelName, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onDeleteModel(modelName) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete model",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Generation Cache & Images")
                }
            }
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = { Text("Are you sure you want to delete all saved generated images and reset gallery history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearCache()
                        showClearCacheDialog = false
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
