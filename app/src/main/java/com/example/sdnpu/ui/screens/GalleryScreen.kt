package com.example.sdnpu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GalleryScreen() {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Generated Images", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("No images generated yet. Run a prompt from Generate tab!", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
