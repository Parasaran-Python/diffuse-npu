package com.example.sdnpu.engine.npu

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.model.ModelExecutionProfile
import java.io.File

interface NpuBackend {
    val type: BackendType
    val name: String
    val isAvailable: Boolean
    val defaultLayout: TensorLayout

    fun canExecute(modelFile: File, profile: ModelExecutionProfile?): Boolean

    fun createSessionOptions(
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession.SessionOptions

    fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession
}
