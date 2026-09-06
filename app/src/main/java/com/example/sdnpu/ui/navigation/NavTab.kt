package com.example.sdnpu.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavTab(val title: String, val icon: ImageVector) {
    GENERATE("Generate", Icons.Default.ElectricBolt),
    GALLERY("Gallery", Icons.Default.Collections),
    SETTINGS("Settings", Icons.Default.Settings)
}
