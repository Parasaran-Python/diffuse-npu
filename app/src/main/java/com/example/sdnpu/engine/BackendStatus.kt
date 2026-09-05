package com.example.sdnpu.engine

data class BackendStatus(
    val backendName: String,
    val isHtpAvailable: Boolean,
    val isLoaded: Boolean,
    val versionString: String,
    val statusMessage: String
)
