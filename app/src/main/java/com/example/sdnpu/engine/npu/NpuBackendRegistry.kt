package com.example.sdnpu.engine.npu

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.OnnxDiffusionEngine
import com.example.sdnpu.engine.npu.backends.*
import com.example.sdnpu.model.ModelExecutionProfile
import java.io.File

object NpuBackendRegistry {
    private const val TAG = "NpuBackendRegistry"

    private val registeredBackends = mutableListOf<NpuBackend>()

    init {
        // Register all pluggable NPU and hardware backends
        registeredBackends.add(QnnHtpBackend { OnnxDiffusionEngine.nativeLibraryDir })
        registeredBackends.add(QnnGpuBackend { OnnxDiffusionEngine.nativeLibraryDir })
        registeredBackends.add(MediaTekApuBackend())
        registeredBackends.add(GoogleTensorBackend())
        registeredBackends.add(SamsungExynosBackend())
        registeredBackends.add(NnapiBackend())
        registeredBackends.add(CpuBackend())
    }

    fun getAvailableBackends(): List<NpuBackend> {
        return registeredBackends.filter { it.isAvailable }
    }

    fun getBackend(type: BackendType): NpuBackend? {
        return registeredBackends.firstOrNull { it.type == type }
    }

    fun getBestBackendForDevice(): NpuBackend {
        val deviceInfo = NpuDeviceDetector.detect()
        val preferredType = deviceInfo.supportedBackendTypes.firstOrNull() ?: BackendType.CPU
        return getBackend(preferredType)?.takeIf { it.isAvailable }
            ?: getAvailableBackends().firstOrNull { it.type != BackendType.CPU }
            ?: getBackend(BackendType.CPU)!!
    }

    /**
     * Resolves and creates an OrtSession using the most optimal available NPU backend
     * for the given model and execution profile, falling back down the hierarchy if necessary.
     */
    fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        profile: ModelExecutionProfile?,
        preferredBackendType: BackendType? = null
    ): Pair<OrtSession, NpuBackend> {
        val deviceInfo = NpuDeviceDetector.detect()

        // Prioritize: 1. User/caller preference (if available), 2. Device-specific NPU, 3. Generic NNAPI, 4. CPU
        val candidateBackends = mutableListOf<NpuBackend>()

        if (preferredBackendType != null) {
            getBackend(preferredBackendType)?.takeIf { it.isAvailable }?.let { candidateBackends.add(it) }
        }

        // Add device-supported backends in priority order
        for (backendType in deviceInfo.supportedBackendTypes) {
            getBackend(backendType)?.takeIf { it.isAvailable && !candidateBackends.contains(it) }?.let {
                candidateBackends.add(it)
            }
        }

        // Always ensure CPU is at the end of the candidate list as the definitive fallback
        getBackend(BackendType.CPU)?.let { cpu ->
            if (!candidateBackends.contains(cpu)) {
                candidateBackends.add(cpu)
            }
        }

        var lastError: Throwable? = null
        for (backend in candidateBackends) {
            if (backend.canExecute(modelFile, profile)) {
                try {
                    Log.i(TAG, "Attempting session creation for ${modelFile.name} on ${backend.name}")
                    val session = backend.createSession(env, modelFile, profile)
                    Log.i(TAG, "SUCCESS: Session created for ${modelFile.name} on ${backend.name}")
                    return Pair(session, backend)
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "${backend.name} session creation failed for ${modelFile.name}, falling back", t)
                }
            }
        }

        val errMsg = lastError?.message ?: "No compatible execution backend available"
        if (errMsg.contains("ORT_NOT_IMPLEMENTED") || errMsg.contains("BiasGelu") || errMsg.contains("Gelu")) {
            throw IllegalStateException(formatOrtErrorMessage(modelFile, lastError), lastError)
        }
        throw lastError ?: IllegalStateException("Failed to create inference session for ${modelFile.name}")
    }

    internal fun formatOrtErrorMessage(modelFile: File, error: Throwable?): String {
        val componentFolderNames = setOf("text_encoder", "unet", "vae", "vae_decoder")
        val modelDisplayName = if (modelFile.parentFile?.name in componentFolderNames) {
            modelFile.parentFile?.parentFile?.name ?: modelFile.name
        } else {
            modelFile.parentFile?.name ?: modelFile.name
        }
        return "Model '$modelDisplayName' contains unsupported FP16 operators in ONNX Runtime Mobile (BiasGelu/Gelu). Please select 'SD 1.5 (Snapdragon NPU)' which is 100% precompiled and verified for Hexagon NPU acceleration."
    }
}
