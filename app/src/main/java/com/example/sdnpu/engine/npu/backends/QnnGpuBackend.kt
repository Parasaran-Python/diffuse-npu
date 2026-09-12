package com.example.sdnpu.engine.npu.backends

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.util.Log
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.npu.ModelPrecision
import com.example.sdnpu.engine.npu.NpuBackend
import com.example.sdnpu.engine.npu.TensorLayout
import com.example.sdnpu.model.ModelExecutionProfile
import java.io.File

class QnnGpuBackend(
    private val nativeLibraryDirProvider: () -> String?
) : NpuBackend {
    override val type: BackendType = BackendType.GPU
    override val name: String = "Qualcomm Adreno GPU (QNN GPU)"
    override val defaultLayout: TensorLayout = TensorLayout.NCHW

    var isAvailableOverride: Boolean? = null

    override val isAvailable: Boolean
        get() {
            isAvailableOverride?.let { return it }
            val providers = runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull() ?: emptySet()
            val hasLib = nativeLibraryDirProvider()?.let { File(it, "libQnnGpu.so").exists() } ?: false
            return providers.contains(OrtProvider.QNN) || hasLib
        }

    fun resolveBackendPath(): String {
        val dir = nativeLibraryDirProvider()
        if (dir != null) {
            val lib = File(dir, "libQnnGpu.so")
            if (lib.exists()) return lib.absolutePath
        }
        return "libQnnGpu.so"
    }

    override fun canExecute(modelFile: File, profile: ModelExecutionProfile?): Boolean {
        if (!modelFile.exists() || modelFile.length() == 0L) return false

        // HTP-specific precompiled context binaries and W8A16 models target Hexagon DSP and cannot execute on Adreno GPU
        val isExplicitContextBin = modelFile.extension.equals("bin", ignoreCase = true)
        val hasHtpMetadata = profile?.isPrecompiledContext == true ||
                profile?.precision == ModelPrecision.UINT16 ||
                profile?.targetHardware?.contains("htp", ignoreCase = true) == true ||
                profile?.targetHardware?.contains("qcs8550", ignoreCase = true) == true
        if (isExplicitContextBin || hasHtpMetadata) {
            return false
        }

        // Avoid large raw UNet JIT crash on GPU memory limits
        val isLargeUnet = modelFile.name == "unet.onnx" && modelFile.length() > 1_500_000_000L
        if (isLargeUnet) {
            return false
        }

        return true
    }

    override fun createSessionOptions(
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        options.addConfigEntry("session.load_model_format", "ONNX")
        options.addConfigEntry("session.disable_prepacking", "1")

        val qnnProviderOptions = mapOf(
            "backend_path" to resolveBackendPath()
        )
        options.addQnn(qnnProviderOptions)
        return options
    }

    override fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession {
        return createSessionOptions(modelFile, profile).use { opts ->
            Log.i(TAG, "Creating QNN GPU session for ${modelFile.name}")
            env.createSession(modelFile.absolutePath, opts)
        }
    }

    companion object {
        private const val TAG = "QnnGpuBackend"
    }
}
