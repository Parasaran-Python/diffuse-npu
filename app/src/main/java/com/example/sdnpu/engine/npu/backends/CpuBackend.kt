package com.example.sdnpu.engine.npu.backends

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.npu.NpuBackend
import com.example.sdnpu.engine.npu.TensorLayout
import com.example.sdnpu.model.ModelExecutionProfile
import java.io.File

class CpuBackend : NpuBackend {
    override val type: BackendType = BackendType.CPU
    override val name: String = "CPU (Multi-threaded)"
    override val isAvailable: Boolean = true
    override val defaultLayout: TensorLayout = TensorLayout.NCHW

    override fun canExecute(modelFile: File, profile: ModelExecutionProfile?): Boolean = true

    override fun createSessionOptions(
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        return options
    }

    override fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession {
        return createSessionOptions(modelFile, profile).use { opts ->
            Log.i(TAG, "Creating CPU session for ${modelFile.name}")
            env.createSession(modelFile.absolutePath, opts)
        }
    }

    companion object {
        private const val TAG = "CpuBackend"
    }
}
