package com.example.sdnpu.ui.screens

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sdnpu.model.DownloadStatus
import com.example.sdnpu.model.ModelVariants
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineState
import com.example.sdnpu.pipeline.SamplerType
import com.example.sdnpu.pipeline.UpscaleMode
import com.example.sdnpu.system.ThermalStatus
import com.example.sdnpu.ui.components.FullScreenImageViewer
import com.example.sdnpu.util.MediaExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    params: GenerationParams,
    pipelineState: PipelineState,
    localModels: List<String>,
    downloadStatus: DownloadStatus = DownloadStatus.Idle,
    thermalStatus: ThermalStatus = ThermalStatus.NONE,
    batteryLevel: Int = 100,
    isCharging: Boolean = true,
    isLowBattery: Boolean = false,
    thermalWarningEnabled: Boolean = true,
    onParamsChange: (GenerationParams) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit = {},
    onOpenDownloadDialog: ((String) -> Unit)? = null,
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullscreenViewerFile by remember { mutableStateOf<Pair<File, String?>?>(null) }
    var isSavingImage by remember { mutableStateOf(false) }

    var showNegativePrompt by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var samplerExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val sdVariants = remember { ModelVariants.getSdVariants() }

    val isProcessing = pipelineState is PipelineState.LoadingModel ||
            pipelineState is PipelineState.Generating ||
            pipelineState is PipelineState.Upscaling

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Stable Diffusion on NPU", style = MaterialTheme.typography.titleLarge)

        // Download in background status banner
        val isDownloading = downloadStatus is DownloadStatus.FetchingManifest ||
                downloadStatus is DownloadStatus.DownloadingComponent ||
                downloadStatus is DownloadStatus.VerifyingChecksum
        val isPaused = downloadStatus is DownloadStatus.Paused
        val showDownloadBanner = isDownloading || isPaused
        if (showDownloadBanner) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isPaused) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDownloadDialog?.invoke(params.modelId) }
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isPaused) "Model Download Paused" else "Model Download in Progress",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isPaused) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isDownloading) {
                                OutlinedButton(
                                    onClick = onPauseDownload,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Pause", style = MaterialTheme.typography.labelSmall)
                                }
                            } else if (isPaused) {
                                Button(
                                    onClick = onResumeDownload,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("Resume", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(
                                text = "Details",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isPaused) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    when (downloadStatus) {
                        is DownloadStatus.DownloadingComponent -> {
                            val downloadedMb = downloadStatus.bytesRead / (1024 * 1024)
                            val totalMb = if (downloadStatus.totalBytes > 0) downloadStatus.totalBytes / (1024 * 1024) else 0
                            val progressText = if (totalMb > 0) {
                                "${downloadStatus.componentName}: ${downloadStatus.progressPercent}% ($downloadedMb / $totalMb MB)"
                            } else {
                                "${downloadStatus.componentName} (${downloadStatus.progressPercent}%)"
                            }
                            Text(progressText, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(
                                progress = { downloadStatus.progressPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        is DownloadStatus.Paused -> {
                            val downloadedMb = downloadStatus.downloadedBytes / (1024 * 1024)
                            val totalMb = if (downloadStatus.totalBytes > 0) downloadStatus.totalBytes / (1024 * 1024) else 0
                            val progressText = if (totalMb > 0) {
                                "Paused - ${downloadStatus.componentName}: ${downloadStatus.percent}% ($downloadedMb / $totalMb MB)"
                            } else {
                                "Paused - ${downloadStatus.componentName} (${downloadStatus.percent}%)"
                            }
                            Text(progressText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            LinearProgressIndicator(
                                progress = { downloadStatus.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        is DownloadStatus.FetchingManifest -> {
                            Text("Connecting & checking manifest...", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is DownloadStatus.VerifyingChecksum -> {
                            Text("Verifying integrity for ${downloadStatus.componentName}...", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        else -> {}
                    }
                }
            }
        }

        // Thermal / Battery Alert Banner
        if (thermalWarningEnabled && (thermalStatus.isThrottlingSevere() || (isLowBattery && !isCharging))) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (thermalStatus.isThrottlingSevere()) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Thermal Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                "Thermal Throttling Alert (${thermalStatus.name})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Device is warm. NPU generation speed may temporarily decrease.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    } else if (isLowBattery && !isCharging) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = "Low Battery",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                "Low Battery Warning ($batteryLevel%)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Connect phone to charger to avoid device shutdown during heavy inference.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = params.prompt,
            onValueChange = { onParamsChange(params.copy(prompt = it)) },
            label = { Text("Prompt") },
            placeholder = { Text("A serene Japanese garden with cherry blossoms, 8k...") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )

        TextButton(onClick = { showNegativePrompt = !showNegativePrompt }) {
            Text(if (showNegativePrompt) "Hide Negative Prompt" else "+ Add Negative Prompt")
        }

        AnimatedVisibility(visible = showNegativePrompt) {
            OutlinedTextField(
                value = params.negativePrompt,
                onValueChange = { onParamsChange(params.copy(negativePrompt = it)) },
                label = { Text("Negative Prompt") },
                placeholder = { Text("blurry, low quality, distorted") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Model Variant Selector Dropdown
        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            val selectedVariant = sdVariants.find { it.id == params.modelId }
            val displayName = selectedVariant?.name ?: params.modelId

            OutlinedTextField(
                value = displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model Variant") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                sdVariants.forEach { variant ->
                    val isDownloaded = localModels.contains(variant.id)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(variant.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    variant.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingIcon = {
                            SuggestionChip(
                                onClick = {},
                                label = { Text(if (isDownloaded) "Ready" else "Online") },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (isDownloaded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        },
                        onClick = {
                            val newSteps = when (variant.id) {
                                "sd15_qnn_npu" -> if (params.steps <= 4) 20 else params.steps
                                "sdturbo" -> if (params.steps > 4) 1 else params.steps
                                "dreamshaper_v8_base" -> if (params.steps < 4) 4 else params.steps
                                else -> params.steps
                            }
                            val newCfg = when (variant.id) {
                                "sd15_qnn_npu" -> if (params.cfgScale <= 1.0f) 7.5f else params.cfgScale
                                "sdturbo" -> 1.0f
                                "dreamshaper_v8_base" -> 1.0f
                                else -> params.cfgScale
                            }
                            onParamsChange(params.copy(modelId = variant.id, steps = newSteps, cfgScale = newCfg))
                            modelExpanded = false
                        }
                    )
                }
            }
        }

        // Sampler Dropdown
        ExposedDropdownMenuBox(
            expanded = samplerExpanded,
            onExpandedChange = { samplerExpanded = !samplerExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = params.sampler.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Sampler") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = samplerExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = samplerExpanded,
                onDismissRequest = { samplerExpanded = false }
            ) {
                SamplerType.entries.forEach { sampler ->
                    DropdownMenuItem(
                        text = { Text(sampler.displayName) },
                        onClick = {
                            onParamsChange(params.copy(sampler = sampler))
                            samplerExpanded = false
                        }
                    )
                }
            }
        }

        Text("Steps: ${params.steps}")
        Slider(
            value = params.steps.toFloat(),
            onValueChange = { onParamsChange(params.copy(steps = it.toInt())) },
            valueRange = 10f..50f,
            steps = 39
        )

        Text("CFG Scale: ${"%.1f".format(params.cfgScale)}")
        Slider(
            value = params.cfgScale,
            onValueChange = { onParamsChange(params.copy(cfgScale = it)) },
            valueRange = 1.0f..20.0f
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = params.seed?.toString() ?: "",
                onValueChange = { onParamsChange(params.copy(seed = it.toLongOrNull())) },
                label = { Text("Seed (empty = random)") },
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onParamsChange(params.copy(seed = Random.nextLong(0, Long.MAX_VALUE))) }
            ) {
                Icon(Icons.Default.Casino, contentDescription = "Random Seed")
            }
        }

        Text("Batch Count: ${params.batchCount}")
        Slider(
            value = params.batchCount.toFloat(),
            onValueChange = { onParamsChange(params.copy(batchCount = it.toInt())) },
            valueRange = 1f..4f,
            steps = 2
        )

        // RealESRGAN Super-Resolution
        Text("Super-Resolution (RealESRGAN):", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UpscaleMode.entries.forEach { mode ->
                FilterChip(
                    selected = params.upscaleMode == mode,
                    onClick = { onParamsChange(params.copy(upscaleMode = mode)) },
                    label = { Text(mode.displayName) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (params.upscaleMode != UpscaleMode.OFF) {
            val upscalerModelId = if (params.upscaleMode == UpscaleMode.X4) "realesrgan_x4plus" else "realesrgan_x2plus"
            val isUpscalerAvailable = localModels.contains(upscalerModelId)
            if (!isUpscalerAvailable) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${if (params.upscaleMode == UpscaleMode.X4) "4x" else "2x"} ESRGAN model not installed",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Download $upscalerModelId for super-resolution inference.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (onOpenDownloadDialog != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onOpenDownloadDialog(upscalerModelId) }
                            ) {
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }

        // Live Upscaling Progress Indicator Card
        AnimatedVisibility(visible = pipelineState is PipelineState.Upscaling) {
            if (pipelineState is PipelineState.Upscaling) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "RealESRGAN ${pipelineState.scale}x Upscaling...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "${pipelineState.progressPercent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        LinearProgressIndicator(
                            progress = { pipelineState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            "Output resolution: ${if (pipelineState.scale == 4) "2048x2048" else "1024x1024"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel Upscaling")
                        }
                    }
                }
            }
        }

        // Live Step Progress Indicator Card
        AnimatedVisibility(visible = pipelineState is PipelineState.Generating) {
            if (pipelineState is PipelineState.Generating) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Sampling Diffusion Latents...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Step ${pipelineState.step}/${pipelineState.totalSteps}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        val progressVal = if (pipelineState.totalSteps > 0) {
                            pipelineState.step.toFloat() / pipelineState.totalSteps.toFloat()
                        } else 0f

                        LinearProgressIndicator(
                            progress = { progressVal },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            pipelineState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        val isModelAvailable = localModels.contains(params.modelId)

        if (!isModelAvailable) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Model Missing",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Model '${params.modelId}' is not installed.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Download over Wi-Fi directly in-app or sideload via ADB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (onOpenDownloadDialog != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onOpenDownloadDialog(params.modelId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download Model (${params.modelId})")
                        }
                    }
                }
            }
        }

        // Generate and Cancel buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onGenerate,
                enabled = !isProcessing && params.prompt.isNotBlank() && isModelAvailable,
                modifier = Modifier.weight(1f)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (pipelineState) {
                            is PipelineState.LoadingModel -> "Loading Model..."
                            is PipelineState.Generating -> "Sampling ${pipelineState.step}/${pipelineState.totalSteps}..."
                            is PipelineState.Upscaling -> "Upscaling ${pipelineState.progressPercent}%..."
                            else -> "Generating..."
                        }
                    )
                } else {
                    Text(if (!isModelAvailable && params.prompt.isNotBlank()) "Model Not Installed" else "Generate Image")
                }
            }

            if (isProcessing) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel")
                }
            }
        }

        if (pipelineState is PipelineState.Completed) {
            val completedPrompt = pipelineState.prompt.ifBlank { params.prompt }

            Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pipelineState.imagePath?.let { path ->
                                    val f = File(path)
                                    if (f.exists()) fullscreenViewerFile = Pair(f, completedPrompt)
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Generation Complete in ${pipelineState.executionTimeMs} ms!",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            pipelineState.imagePath?.let { path ->
                                val file = File(path)
                                val bitmap = remember(path) {
                                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                                }
                                if (bitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                fullscreenViewerFile = Pair(file, completedPrompt)
                                            }
                                    ) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "Generated Image",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f),
                                            contentScale = ContentScale.Fit
                                        )
                                        // Resolution and upscale badges
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            val resText = "${bitmap.width}x${bitmap.height}"
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(resText, style = MaterialTheme.typography.labelSmall) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                                )
                                            )
                                            if (file.name.contains("_x2") || file.name.contains("_x4")) {
                                                val upscaleText = if (file.name.contains("_x4")) "4x ESRGAN" else "2x ESRGAN"
                                                AssistChip(
                                                    onClick = {},
                                                    label = { Text(upscaleText, style = MaterialTheme.typography.labelSmall) },
                                                    colors = AssistChipDefaults.assistChipColors(
                                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                Text(
                                    "Saved to: ${file.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Action buttons: Fullscreen, Save to Gallery, Share
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { fullscreenViewerFile = Pair(file, completedPrompt) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Fullscreen", maxLines = 1)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            if (isSavingImage) return@FilledTonalButton
                                            isSavingImage = true
                                            scope.launch {
                                                try {
                                                    val result = withContext(Dispatchers.IO) {
                                                        MediaExporter.saveImageToPublicGallery(context, file, completedPrompt)
                                                    }
                                                    result.onSuccess {
                                                        Toast.makeText(context, "Saved to Pictures/SD_NPU", Toast.LENGTH_SHORT).show()
                                                    }.onFailure { e ->
                                                        Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                } finally {
                                                    isSavingImage = false
                                                }
                                            }
                                        },
                                        enabled = !isSavingImage,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = "Save to Gallery", modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save", maxLines = 1)
                                    }

                                    Button(
                                        onClick = {
                                            MediaExporter.shareImage(context, file, completedPrompt)
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Share", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                if (pipelineState is PipelineState.Error) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Error: ${pipelineState.error}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            fullscreenViewerFile?.let { (file, prompt) ->
                FullScreenImageViewer(
                    imageFile = file,
                    prompt = prompt,
                    onDismiss = { fullscreenViewerFile = null }
                )
            }
}
