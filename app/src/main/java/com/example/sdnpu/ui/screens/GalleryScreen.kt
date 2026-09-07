package com.example.sdnpu.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.sdnpu.data.GenerationEntity
import com.example.sdnpu.ui.components.FullScreenImageViewer
import com.example.sdnpu.util.MediaExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    historyList: List<GenerationEntity>,
    searchQuery: String = "",
    selectedIds: Set<Long> = emptySet(),
    isSelectionMode: Boolean = false,
    onQueryChange: (String) -> Unit = {},
    onToggleSelection: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    onDeleteSingle: (Long) -> Unit = {},
    onPopulateParams: (GenerationEntity) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inspectingRecord by remember { mutableStateOf<GenerationEntity?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var fullScreenViewerFile by remember { mutableStateOf<Pair<File, String?>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Bar: Search or Selection Mode
        if (isSelectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row {
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Gallery (${historyList.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search by prompt...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (searchQuery.isNotEmpty()) "No generations matching \"$searchQuery\"" else "No images generated yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Run a prompt from the Generate tab to see your creations here!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                items(historyList, key = { it.id }) { record ->
                    val isSelected = record.id in selectedIds

                    GalleryItemCard(
                        record = record,
                        isSelected = isSelected,
                        isSelectionMode = isSelectionMode,
                        onClick = {
                            if (isSelectionMode) {
                                onToggleSelection(record.id)
                            } else {
                                inspectingRecord = record
                            }
                        },
                        onLongClick = {
                            onToggleSelection(record.id)
                        }
                    )
                }
            }
        }
    }

    // Full-screen / Dialog Inspection View
    inspectingRecord?.let { record ->
        Dialog(onDismissRequest = { inspectingRecord = null }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
            ) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = File(record.imagePath).name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { inspectingRecord = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val file = File(record.imagePath)
                    val bitmap = remember(record.imagePath) {
                        if (file.exists()) {
                            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                        } else null
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = record.prompt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    fullScreenViewerFile = Pair(file, record.prompt)
                                },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Image file unavailable", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text("${record.width}x${record.height}") }
                        )
                        if (record.upscaleMode > 0) {
                            AssistChip(
                                onClick = {},
                                label = { Text("${record.upscaleMode}x ESRGAN") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                        if (record.generationTimeMs > 0) {
                            AssistChip(
                                onClick = {},
                                label = { Text("${record.generationTimeMs} ms") }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Metadata details
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Prompt:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(record.prompt, style = MaterialTheme.typography.bodySmall)

                            if (record.negativePrompt.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("Negative Prompt:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(record.negativePrompt, style = MaterialTheme.typography.bodySmall)
                            }

                            Spacer(Modifier.height(4.dp))
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(record.timestamp))
                            Text("Model: ${record.modelName}", style = MaterialTheme.typography.bodySmall)
                            Text("Steps: ${record.steps} | CFG: ${record.cfgScale} | Seed: ${record.seed}", style = MaterialTheme.typography.bodySmall)
                            Text("Date: $dateStr", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Action buttons
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onPopulateParams(record)
                                    inspectingRecord = null
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Re-generate")
                            }

                            OutlinedButton(
                                onClick = {
                                    fullScreenViewerFile = Pair(file, record.prompt)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Fullscreen")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            MediaExporter.saveImageToPublicGallery(context, file, record.prompt)
                                        }
                                        result.onSuccess {
                                            Toast.makeText(context, "Saved to Pictures/SD_NPU", Toast.LENGTH_SHORT).show()
                                        }.onFailure { e ->
                                            Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Save to Gallery", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save to Gallery", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = {
                                    MediaExporter.shareImage(context, file, record.prompt)
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }

                            OutlinedButton(
                                onClick = {
                                    onDeleteSingle(record.id)
                                    inspectingRecord = null
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for Batch Delete
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Generations") },
            text = { Text("Are you sure you want to delete ${selectedIds.size} selected image(s)? This will remove them permanently from storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected()
                        showDeleteConfirmDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    fullScreenViewerFile?.let { (file, prompt) ->
        FullScreenImageViewer(
            imageFile = file,
            prompt = prompt,
            onDismiss = { fullScreenViewerFile = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryItemCard(
    record: GenerationEntity,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val file = File(record.imagePath)
    val bitmap = remember(record.imagePath) {
        if (file.exists()) {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            var sampleSize = 1
            while (boundsOptions.outWidth / (sampleSize * 2) >= 256) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)?.asImageBitmap()
        } else null
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = record.prompt,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No preview", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Selection check overlay
                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Resolution badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = if (record.upscaleMode > 0) "${record.width}p (${record.upscaleMode}x)" else "${record.width}p",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = record.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
