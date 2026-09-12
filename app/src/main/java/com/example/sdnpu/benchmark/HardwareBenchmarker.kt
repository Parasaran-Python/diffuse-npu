package com.example.sdnpu.benchmark

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.util.Log
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.GaussianNoise
import com.example.sdnpu.engine.OnnxDiffusionEngine
import com.example.sdnpu.engine.npu.NpuBackendRegistry
import com.example.sdnpu.model.ModelExecutionProfile
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object HardwareBenchmarker {
    private const val TAG = "HardwareBenchmarker"

    suspend fun runFullBenchmark(context: Context): String = withContext(Dispatchers.IO) {
        val env = OrtEnvironment.getEnvironment()
        val results = mutableMapOf<String, Any>()
        val modelsBaseDir = File(context.getExternalFilesDir(null), "models")

        Log.i(TAG, "=== Starting Hardware Benchmark on Device ===")

        val sd15Dir = File(modelsBaseDir, "sd15_qnn_npu")
        val dreamDir = File(modelsBaseDir, "dreamshaper_v8_base")

        // -------------------------------------------------------------
        // 1. QUALCOMM HEXAGON NPU BENCHMARK (QnnHtpBackend)
        // -------------------------------------------------------------
        val npuResults = mutableMapOf<String, Any>()
        if (sd15Dir.exists()) {
            val profile = ModelExecutionProfile.fromModelDirectory(sd15Dir, "sd15_qnn_npu")
            val htpBackend = NpuBackendRegistry.getBackend(BackendType.QNN_HTP) ?: NpuBackendRegistry.getBackend(BackendType.HTP_NPU)

            if (htpBackend != null && htpBackend.isAvailable) {
                // A. Text Encoder
                try {
                    val textEncFile = File(sd15Dir, "text_encoder.onnx")
                    val loadStart = System.nanoTime()
                    htpBackend.createSession(env, textEncFile, profile).use { textSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0
                        
                        val tokens = IntArray(77) { 49406 } // standard padding/prompt tokens
                        val infStart = System.nanoTime()
                        val textEmb = OnnxDiffusionEngine.encodePrompt(textSession, tokens, env, profile)
                        val infMs = (System.nanoTime() - infStart) / 1_000_000.0

                        npuResults["text_encoder"] = mapOf(
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "inference_ms" to infMs,
                            "output_dim" to textEmb.size
                        )
                    }
                } catch (t: Throwable) {
                    npuResults["text_encoder"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }

                // B. UNet (1-step pass & 20-step loop)
                try {
                    val unetFile = File(sd15Dir, "unet.onnx")
                    val loadStart = System.nanoTime()
                    htpBackend.createSession(env, unetFile, profile).use { unetSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

                        val dummyLatents = FloatArray(4 * 64 * 64) { 0.1f }
                        val dummyTextEmb = FloatArray(77 * 768) { 0.1f }
                        val dummyUncond = FloatArray(77 * 768) { 0.05f }

                        // Measure single step
                        val step1Start = System.nanoTime()
                        OnnxDiffusionEngine.denoiseLoop(
                            unetSession = unetSession,
                            latents = dummyLatents,
                            textEmbeddings = dummyTextEmb,
                            steps = 1,
                            modelId = "sd15_qnn_npu",
                            cfgScale = 7.5f,
                            env = env,
                            profile = profile,
                            uncondEmbeddings = dummyUncond
                        )
                        val singleStepMs = (System.nanoTime() - step1Start) / 1_000_000.0

                        // Measure 20 steps
                        val steps20Start = System.nanoTime()
                        OnnxDiffusionEngine.denoiseLoop(
                            unetSession = unetSession,
                            latents = dummyLatents,
                            textEmbeddings = dummyTextEmb,
                            steps = 20,
                            modelId = "sd15_qnn_npu",
                            cfgScale = 7.5f,
                            env = env,
                            profile = profile,
                            uncondEmbeddings = dummyUncond
                        )
                        val steps20Ms = (System.nanoTime() - steps20Start) / 1_000_000.0

                        npuResults["unet"] = mapOf(
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "single_step_ms" to singleStepMs,
                            "twenty_steps_total_ms" to steps20Ms,
                            "avg_step_ms" to (steps20Ms / 20.0)
                        )
                    }
                } catch (t: Throwable) {
                    npuResults["unet"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }

                // C. VAE Decoder
                try {
                    val vaeFile = File(sd15Dir, "vae.onnx")
                    val loadStart = System.nanoTime()
                    htpBackend.createSession(env, vaeFile, profile).use { vaeSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

                        val dummyLatents = FloatArray(4 * 64 * 64) { 0.1f }
                        val infStart = System.nanoTime()
                        val rgbBytes = OnnxDiffusionEngine.decodeVae(vaeSession, dummyLatents, 512, 512, env, profile)
                        val infMs = (System.nanoTime() - infStart) / 1_000_000.0

                        npuResults["vae_decoder"] = mapOf(
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "inference_ms" to infMs,
                            "output_bytes" to rgbBytes.size
                        )
                    }
                } catch (t: Throwable) {
                    npuResults["vae_decoder"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }
            } else {
                npuResults["error"] = "QNN HTP Backend not available on device"
            }
        }
        results["npu_qualcomm_htp"] = npuResults

        // -------------------------------------------------------------
        // 2. CPU BENCHMARK (CpuBackend)
        // -------------------------------------------------------------
        val cpuResults = mutableMapOf<String, Any>()
        val cpuBackend = NpuBackendRegistry.getBackend(BackendType.CPU)

        if (cpuBackend != null) {
            // Test standard model components on CPU
            if (dreamDir.exists()) {
                // A. Text Encoder
                try {
                    val textEncFile = File(dreamDir, "text_encoder.onnx")
                    val loadStart = System.nanoTime()
                    cpuBackend.createSession(env, textEncFile, null).use { textSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

                        val tokens = IntArray(77) { 49406 }
                        val infStart = System.nanoTime()
                        val textEmb = OnnxDiffusionEngine.encodePrompt(textSession, tokens, env, null)
                        val infMs = (System.nanoTime() - infStart) / 1_000_000.0

                        cpuResults["text_encoder"] = mapOf(
                            "model" to "dreamshaper_v8_base",
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "inference_ms" to infMs
                        )
                    }
                } catch (t: Throwable) {
                    cpuResults["text_encoder"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }

                // B. VAE Decoder
                try {
                    val vaeFile = File(dreamDir, "vae_decoder.onnx")
                    val loadStart = System.nanoTime()
                    cpuBackend.createSession(env, vaeFile, null).use { vaeSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

                        val dummyLatents = FloatArray(4 * 64 * 64) { 0.1f }
                        val infStart = System.nanoTime()
                        val rgbBytes = OnnxDiffusionEngine.decodeVae(vaeSession, dummyLatents, 512, 512, env, null)
                        val infMs = (System.nanoTime() - infStart) / 1_000_000.0

                        cpuResults["vae_decoder"] = mapOf(
                            "model" to "dreamshaper_v8_base",
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "inference_ms" to infMs,
                            "output_bytes" to rgbBytes.size
                        )
                    }
                } catch (t: Throwable) {
                    cpuResults["vae_decoder"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }

                // C. UNet (dreamshaper_v8_base)
                try {
                    val unetFile = File(dreamDir, "unet.onnx")
                    val loadStart = System.nanoTime()
                    cpuBackend.createSession(env, unetFile, null).use { unetSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

                        val dummyLatents = FloatArray(4 * 64 * 64) { 0.1f }
                        val dummyTextEmb = FloatArray(77 * 768) { 0.1f }

                        val stepStart = System.nanoTime()
                        OnnxDiffusionEngine.denoiseLoop(
                            unetSession = unetSession,
                            latents = dummyLatents,
                            textEmbeddings = dummyTextEmb,
                            steps = 1,
                            modelId = "dreamshaper_v8_base",
                            cfgScale = 1.0f,
                            env = env,
                            profile = null
                        )
                        val stepMs = (System.nanoTime() - stepStart) / 1_000_000.0

                        cpuResults["unet_dreamshaper"] = mapOf(
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "single_step_ms" to stepMs
                        )
                    }
                } catch (t: Throwable) {
                    cpuResults["unet_dreamshaper"] = mapOf(
                        "status" to "FAILED",
                        "error" to (t.message ?: t.toString())
                    )
                }
            }

            // D. Test QNN binary on CPU
            if (sd15Dir.exists()) {
                try {
                    val unetFile = File(sd15Dir, "unet.onnx")
                    cpuBackend.createSession(env, unetFile, null).use { unetSession ->
                        cpuResults["unet_sd15_qnn"] = mapOf("status" to "SUCCESS")
                    }
                } catch (t: Throwable) {
                    cpuResults["unet_sd15_qnn"] = mapOf(
                        "status" to "FAILED_AS_EXPECTED",
                        "reason" to "QNN context binary node unsupported by CPU EP",
                        "error" to (t.message ?: t.toString())
                    )
                }
            }
        }
        results["cpu_kryo_multithreaded"] = cpuResults

        // -------------------------------------------------------------
        // 3. QUALCOMM ADRENO GPU BENCHMARK (QnnGpuBackend)
        // -------------------------------------------------------------
        val gpuResults = mutableMapOf<String, Any>()
        val gpuBackend = NpuBackendRegistry.getBackend(BackendType.GPU)

        if (gpuBackend != null) {
            gpuResults["is_available"] = gpuBackend.isAvailable

            if (dreamDir.exists()) {
                // A. Text Encoder on GPU
                try {
                    val textEncFile = File(dreamDir, "text_encoder.onnx")
                    val loadStart = System.nanoTime()
                    gpuBackend.createSession(env, textEncFile, null).use { textSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

                        val tokens = IntArray(77) { 49406 }
                        val infStart = System.nanoTime()
                        val textEmb = OnnxDiffusionEngine.encodePrompt(textSession, tokens, env, null)
                        val infMs = (System.nanoTime() - infStart) / 1_000_000.0

                        gpuResults["text_encoder"] = mapOf(
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "inference_ms" to infMs
                        )
                    }
                } catch (t: Throwable) {
                    gpuResults["text_encoder"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }

                // B. VAE Decoder on GPU
                try {
                    val vaeFile = File(dreamDir, "vae_decoder.onnx")
                    val loadStart = System.nanoTime()
                    gpuBackend.createSession(env, vaeFile, null).use { vaeSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0

                        val dummyLatents = FloatArray(4 * 64 * 64) { 0.1f }
                        val infStart = System.nanoTime()
                        val rgbBytes = OnnxDiffusionEngine.decodeVae(vaeSession, dummyLatents, 512, 512, env, null)
                        val infMs = (System.nanoTime() - infStart) / 1_000_000.0

                        gpuResults["vae_decoder"] = mapOf(
                            "status" to "SUCCESS",
                            "session_init_ms" to loadMs,
                            "inference_ms" to infMs,
                            "output_bytes" to rgbBytes.size
                        )
                    }
                } catch (t: Throwable) {
                    gpuResults["vae_decoder"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }

                // C. UNet on GPU
                try {
                    val unetFile = File(dreamDir, "unet.onnx")
                    val loadStart = System.nanoTime()
                    gpuBackend.createSession(env, unetFile, null).use { unetSession ->
                        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0
                        gpuResults["unet"] = mapOf("status" to "SUCCESS", "session_init_ms" to loadMs)
                    }
                } catch (t: Throwable) {
                    gpuResults["unet"] = mapOf("status" to "FAILED", "error" to (t.message ?: t.toString()))
                }
            }

            // D. Test QNN binary on GPU
            if (sd15Dir.exists()) {
                try {
                    val unetFile = File(sd15Dir, "unet.onnx")
                    gpuBackend.createSession(env, unetFile, null).use { unetSession ->
                        gpuResults["unet_sd15_qnn"] = mapOf("status" to "SUCCESS")
                    }
                } catch (t: Throwable) {
                    gpuResults["unet_sd15_qnn"] = mapOf(
                        "status" to "FAILED_AS_EXPECTED",
                        "reason" to "QNN HTP context binary incompatible with GPU backend",
                        "error" to (t.message ?: t.toString())
                    )
                }
            }
        } else {
            gpuResults["error"] = "QNN GPU Backend not registered"
        }
        results["gpu_adreno_740"] = gpuResults

        val gson = GsonBuilder().setPrettyPrinting().create()
        val jsonOutput = gson.toJson(results)

        Log.i(TAG, "=== Benchmark Results Summary ===\n$jsonOutput")

        try {
            val outFile = File(context.getExternalFilesDir(null), "hardware_benchmark_results.json")
            outFile.writeText(jsonOutput)
            Log.i(TAG, "Wrote benchmark results to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write benchmark results file", e)
        }

        jsonOutput
    }
}
