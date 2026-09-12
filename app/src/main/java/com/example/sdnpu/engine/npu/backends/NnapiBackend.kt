package com.example.sdnpu.engine.npu.backends

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.util.Log
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.npu.NpuBackend
import com.example.sdnpu.engine.npu.TensorLayout
import com.example.sdnpu.model.ModelExecutionProfile
import java.io.File

class NnapiBackend : NpuBackend {
    override val type: BackendType = BackendType.NNAPI_NPU
    override val name: String = "Generic Android NNAPI"
    override val defaultLayout: TensorLayout = TensorLayout.NCHW

    override val isAvailable: Boolean
        get() {
            val providers = runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull() ?: emptySet()
            return providers.contains(OrtProvider.NNAPI)
        }

    override fun canExecute(modelFile: File, profile: ModelExecutionProfile?): Boolean {
        if (!isAvailable) return false
        // Exclude unoptimized float VAE if known to thrash on certain older NNAPI drivers
        val isFloatVae = (modelFile.name == "vae_decoder.onnx" || modelFile.name == "vae.onnx") && modelFile.length() > 80_000_000L
        return !isFloatVae
    }

    override fun createSessionOptions(
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        options.addConfigEntry("session.load_model_format", "ONNX")
        options.addNnapi()
        return options
    }

    override fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession {
        return createSessionOptions(modelFile, profile).use { opts ->
            Log.i(TAG, "Creating generic NNAPI session for ${modelFile.name}")
            env.createSession(modelFile.absolutePath, opts)
        }
    }

    companion object {
        private const val TAG = "NnapiBackend"
    }
}
