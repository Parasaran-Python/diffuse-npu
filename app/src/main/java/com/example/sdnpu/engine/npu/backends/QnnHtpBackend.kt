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

class QnnHtpBackend(
    private val nativeLibraryDirProvider: () -> String?
) : NpuBackend {
    override val type: BackendType = BackendType.QNN_HTP
    override val name: String = "Qualcomm Hexagon NPU (QNN HTP)"
    override val defaultLayout: TensorLayout = TensorLayout.NHWC

    var isAvailableOverride: Boolean? = null

    override val isAvailable: Boolean
        get() {
            isAvailableOverride?.let { return it }
            val providers = runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull() ?: emptySet()
            val hasLib = nativeLibraryDirProvider()?.let { File(it, "libQnnHtp.so").exists() } ?: false
            return providers.contains(OrtProvider.QNN) || hasLib
        }

    fun resolveBackendPath(): String {
        val dir = nativeLibraryDirProvider()
        if (dir != null) {
            val lib = File(dir, "libQnnHtp.so")
            if (lib.exists()) return lib.absolutePath
        }
        return "libQnnHtp.so"
    }

    override fun canExecute(modelFile: File, profile: ModelExecutionProfile?): Boolean {
        if (!modelFile.exists() || modelFile.length() == 0L) return false

        val contextOnnxFile = File(modelFile.parentFile, "${modelFile.nameWithoutExtension}_ctx.onnx")
        val qairtContextBin = File(modelFile.parentFile, "${modelFile.nameWithoutExtension}_qairt_context.bin")
        val hasPrecompiledContext = (contextOnnxFile.exists() && contextOnnxFile.length() > 0) ||
                (qairtContextBin.exists() && qairtContextBin.length() > 0) ||
                (profile?.isPrecompiledContext == true)

        // Raw unquantized models >1GB cannot be JIT-compiled on-device without triggering LMKD OOM
        val isUnquantizedLargeUnet = !hasPrecompiledContext && modelFile.name == "unet.onnx" && modelFile.length() > 1_000_000_000L
        val isUnoptimizedFloatVae = !hasPrecompiledContext && (modelFile.name == "vae_decoder.onnx" || modelFile.name == "vae.onnx") && modelFile.length() > 80_000_000L

        return !isUnquantizedLargeUnet && !isUnoptimizedFloatVae
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
            "backend_path" to resolveBackendPath(),
            "enable_htp_fp16_precision" to "1",
            "enable_htp_weight_sharing" to "1",
            "htp_performance_mode" to "burst",
            "htp_graph_finalization_optimization_mode" to "1"
        )
        options.addQnn(qnnProviderOptions)
        return options
    }

    override fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ModelExecutionProfile?
    ): OrtSession {
        val contextOnnxFile = File(modelFile.parentFile, "${modelFile.nameWithoutExtension}_ctx.onnx")
        val effectiveModel = if (contextOnnxFile.exists() && contextOnnxFile.length() > 0) contextOnnxFile else modelFile

        return createSessionOptions(effectiveModel, profile).use { opts ->
            Log.i(TAG, "Creating QNN HTP NPU session for ${effectiveModel.name}")
            env.createSession(effectiveModel.absolutePath, opts)
        }
    }

    companion object {
        private const val TAG = "QnnHtpBackend"
    }
}
