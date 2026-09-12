package com.example.sdnpu.engine.npu.backends

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.util.Log
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.npu.NpuBackend
import com.example.sdnpu.engine.npu.NpuDeviceDetector
import com.example.sdnpu.engine.npu.SocVendor
import com.example.sdnpu.engine.npu.TensorLayout
import com.example.sdnpu.model.ModelExecutionProfile
import java.io.File

class MediaTekApuBackend : NpuBackend {
    override val type: BackendType = BackendType.MEDIATEK_APU
    override val name: String = "MediaTek APU (NeuroPilot)"
    override val defaultLayout: TensorLayout = TensorLayout.NCHW

    override val isAvailable: Boolean
        get() {
            val isMtkDevice = NpuDeviceDetector.detect().vendor == SocVendor.MEDIATEK
            val providers = runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull() ?: emptySet()
            return isMtkDevice && providers.contains(OrtProvider.NNAPI)
        }

    override fun canExecute(modelFile: File, profile: ModelExecutionProfile?): Boolean {
        if (!isAvailable) return false
        // NeuroPilot through NNAPI supports standard ONNX and quantized INT8 models
        return true
    }

    override fun createSessionOptions(
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        options.addConfigEntry("session.load_model_format", "ONNX")
        // Target NNAPI execution provider which bridges to MTK Neuron Runtime on MediaTek Dimensity
        options.addNnapi()
        return options
    }

    override fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession {
        return createSessionOptions(modelFile, profile).use { opts ->
            Log.i(TAG, "Creating MediaTek APU (NeuroPilot) session for ${modelFile.name}")
            env.createSession(modelFile.absolutePath, opts)
        }
    }

    companion object {
        private const val TAG = "MediaTekApuBackend"
    }
}
