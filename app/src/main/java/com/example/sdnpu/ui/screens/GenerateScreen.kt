package com.example.sdnpu.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineState
import com.example.sdnpu.pipeline.SamplerType
import com.example.sdnpu.pipeline.UpscaleMode
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    params: GenerationParams,
    pipelineState: PipelineState,
    localModels: List<String>,
    onParamsChange: (GenerationParams) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit = {}
) {
    var showNegativePrompt by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var samplerExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val availableModels = remember(localModels) {
        val sdModels = localModels.filter { !it.startsWith("realesrgan") }
        if (sdModels.isEmpty()) listOf("dreamshaper_v8") else sdModels
    }

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

        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = !modelExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = params.modelId,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                availableModels.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onParamsChange(params.copy(modelId = model))
                            modelExpanded = false
                        }
                    )
                }
            }
        }

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

        Text("Batch Count: ${params.batchCount}")
        Slider(
            value = params.batchCount.toFloat(),
            onValueChange = { onParamsChange(params.copy(batchCount = it.toInt())) },
            valueRange = 1f..4f,
            steps = 2
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = params.seed?.toString() ?: "",
                onValueChange = {
                    val clean = it.filter { ch -> ch.isDigit() }
                    val s = clean.toLongOrNull()
                    onParamsChange(params.copy(seed = s))
                },
                label = { Text("Seed (empty = random)") },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                onParamsChange(params.copy(seed = Random.nextLong(0, 1000000)))
            }) {
                Icon(Icons.Default.Casino, contentDescription = "Randomize Seed")
            }
        }

        Text("RealESRGAN Upscale:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpscaleMode.entries.forEach { mode ->
                FilterChip(
                    selected = params.upscaleMode == mode,
                    onClick = { onParamsChange(params.copy(upscaleMode = mode)) },
                    label = { Text(mode.displayName) }
                )
            }
        }

        Button(
            onClick = onGenerate,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            when (pipelineState) {
                is PipelineState.LoadingModel -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading model ${pipelineState.modelId}...")
                }
                is PipelineState.Generating -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating (${pipelineState.step}/${pipelineState.totalSteps})...")
                }
                is PipelineState.Upscaling -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Upscaling ${pipelineState.scale}x with RealESRGAN... ${(pipelineState.progress * 100).toInt()}%")
                }
                else -> Text("Generate Image")
            }
        }

        if (isProcessing) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Cancel Generation")
            }
        }

        when (pipelineState) {
            is PipelineState.Upscaling -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Upscaling ${pipelineState.scale}x with RealESRGAN... ${(pipelineState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        LinearProgressIndicator(
                            progress = { pipelineState.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            is PipelineState.Completed -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val bitmap = remember(pipelineState.imagePath) {
                            pipelineState.imagePath?.let { path ->
                                BitmapFactory.decodeFile(path)?.asImageBitmap()
                            }
                        }

                        val badgeText = when {
                            pipelineState.imagePath?.contains("_x4") == true || (bitmap != null && bitmap.width >= 2048) -> "2048x2048 (4x ESRGAN)"
                            pipelineState.imagePath?.contains("_x2") == true || (bitmap != null && bitmap.width >= 1024) -> "1024x1024 (2x ESRGAN)"
                            bitmap != null -> "${bitmap.width}x${bitmap.height}"
                            else -> "512x512"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Success! Time: ${pipelineState.executionTimeMs} ms",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            SuggestionChip(
                                onClick = {},
                                label = { Text(badgeText) }
                            )
                        }

                        if (bitmap != null) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Generated Image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(badgeText, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
            is PipelineState.Error -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = "Error: ${pipelineState.error}",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            else -> {}
        }
    }
}
