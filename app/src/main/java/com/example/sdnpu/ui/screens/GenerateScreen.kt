package com.example.sdnpu.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineState
import com.example.sdnpu.pipeline.UpscaleMode
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    params: GenerationParams,
    pipelineState: PipelineState,
    localModels: List<String>,
    onParamsChange: (GenerationParams) -> Unit,
    onGenerate: () -> Unit
) {
    var showNegativePrompt by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

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

        Text("Model: ${params.modelId}", style = MaterialTheme.typography.bodyMedium)

        Text("Steps: ${params.steps}")
        Slider(
            value = params.steps.toFloat(),
            onValueChange = { onParamsChange(params.copy(steps = it.toInt())) },
            valueRange = 10f..50f,
            steps = 40
        )

        Text("CFG Scale: ${"%.1f".format(params.cfgScale)}")
        Slider(
            value = params.cfgScale,
            onValueChange = { onParamsChange(params.copy(cfgScale = it)) },
            valueRange = 1.0f..20.0f
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = params.seed?.toString() ?: "",
                onValueChange = {
                    val s = it.toLongOrNull()
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
            enabled = pipelineState !is PipelineState.Generating && pipelineState !is PipelineState.Upscaling,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            when (pipelineState) {
                is PipelineState.Generating -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating (${pipelineState.step}/${pipelineState.totalSteps})...")
                }
                is PipelineState.Upscaling -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Upscaling ${pipelineState.scale}x...")
                }
                else -> Text("Generate Image")
            }
        }

        when (pipelineState) {
            is PipelineState.Completed -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(
                        text = "Success! Time: ${pipelineState.executionTimeMs} ms",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
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
