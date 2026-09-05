package com.example.sdnpu.model

import com.google.gson.annotations.SerializedName

data class ModelComponent(
    @SerializedName("name") val name: String,
    @SerializedName("file") val file: String,
    @SerializedName("sha256") val sha256: String
)

data class ModelManifest(
    @SerializedName("model_id") val modelId: String,
    @SerializedName("version") val version: String,
    @SerializedName("components") val components: List<ModelComponent>,
    @SerializedName("qnn_sdk_version") val qnnSdkVersion: String,
    @SerializedName("target_htp") val targetHtp: String
)
