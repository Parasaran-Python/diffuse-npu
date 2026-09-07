package com.example.sdnpu.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.example.sdnpu.pipeline.GenerationParams
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

object OnnxDiffusionEngine {
    private const val TAG = "OnnxDiffusionEngine"
    private val tokenizer = ClipTokenizer()

    @Volatile
    private var isCancelled = false

    /**
     * Test-only toggle for unit test environments where native Android NPU/QNN/ORT
     * binaries and multi-gigabyte models are not loaded.
     */
    @Volatile
    var testSimulationEnabled: Boolean = false

    const val VAE_SCALE_FACTOR = 0.18215f

    fun cancel() {
        isCancelled = true
    }

    fun floatToFp16(f: Float): Short {
        val fbits = java.lang.Float.floatToIntBits(f)
        val sign = (fbits ushr 16) and 0x8000
        val valExp = ((fbits ushr 23) and 0xff) - 127 + 15
        if (valExp <= 0) {
            return sign.toShort()
        } else if (valExp >= 31) {
            return (sign or 0x7c00).toShort()
        }
        val mant = (fbits and 0x007fffff) ushr 13
        return (sign or (valExp shl 10) or mant).toShort()
    }

    fun fp16ToFloat(h: Short): Float {
        val hInt = h.toInt() and 0xffff
        val sign = (hInt and 0x8000) shl 16
        val exp = (hInt and 0x7c00) ushr 10
        val mant = hInt and 0x03ff
        if (exp == 0) {
            return java.lang.Float.intBitsToFloat(sign)
        }
        if (exp == 31) {
            return if (mant == 0) {
                if (sign == 0) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY
            } else {
                Float.NaN
            }
        }
        val fExp = (exp - 15 + 127) shl 23
        val fMant = mant shl 13
        return java.lang.Float.intBitsToFloat(sign or fExp or fMant)
    }

    fun createFloatTensor(
        env: OrtEnvironment,
        targetType: OnnxJavaType?,
        floats: FloatArray,
        shape: LongArray
    ): OnnxTensor {
        return if (targetType == OnnxJavaType.FLOAT16) {
            val shortBuf = ShortBuffer.allocate(floats.size)
            for (f in floats) {
                shortBuf.put(floatToFp16(f))
            }
            shortBuf.flip()
            OnnxTensor.createTensor(env, shortBuf, shape, OnnxJavaType.FLOAT16)
        } else {
            val floatBuf = FloatBuffer.wrap(floats)
            OnnxTensor.createTensor(env, floatBuf, shape)
        }
    }

    fun extractFloatsFromTensor(tensor: OnnxTensor): FloatArray {
        val info = tensor.info
        return if (info.type == OnnxJavaType.FLOAT16) {
            val shortBuf = tensor.shortBuffer
            val floats = FloatArray(shortBuf.remaining())
            for (i in floats.indices) {
                floats[i] = fp16ToFloat(shortBuf.get())
            }
            floats
        } else {
            val floatBuf = tensor.floatBuffer
            val floats = FloatArray(floatBuf.remaining())
            floatBuf.get(floats)
            floats
        }
    }

    fun createSessionOptions(): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(4)
        options.addConfigEntry("session.load_model_format", "ONNX")
        val availableProviders = runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull() ?: emptySet()
        if (availableProviders.contains(OrtProvider.QNN)) {
            try {
                val qnnOptions = mapOf(
                    "backend_type" to "HTP",
                    "htp_performance_mode" to "burst",
                    "htp_graph_finalization_optimization_mode" to "3"
                )
                options.addQnn(qnnOptions)
                return options
            } catch (t: Throwable) {
                Log.w(TAG, "QNN provider option configuration failed", t)
            }
        }
        if (availableProviders.contains(OrtProvider.NNAPI)) {
            try {
                options.addNnapi()
                return options
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI provider option configuration failed", t)
            }
        }
        return options
    }

    fun createSession(env: OrtEnvironment, modelFile: File): OrtSession {
        val availableProviders = runCatching { OrtEnvironment.getAvailableProviders() }.getOrNull() ?: emptySet()

        // Tier 1: Try Qualcomm QNN Execution Provider (HTP Backend) if supported in current build
        if (availableProviders.contains(OrtProvider.QNN)) {
            try {
                OrtSession.SessionOptions().use { qnnOptions ->
                    qnnOptions.setIntraOpNumThreads(4)
                    qnnOptions.addConfigEntry("session.load_model_format", "ONNX")
                    val qnnProviderOptions = mapOf(
                        "backend_type" to "HTP",
                        "htp_performance_mode" to "burst",
                        "htp_graph_finalization_optimization_mode" to "3"
                    )
                    qnnOptions.addQnn(qnnProviderOptions)
                    return env.createSession(modelFile.absolutePath, qnnOptions)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Tier 1 (QNN) session creation failed for ${modelFile.name}, falling back", t)
            }
        }

        // Tier 2: Try Android NNAPI Execution Provider if supported in current build
        if (availableProviders.contains(OrtProvider.NNAPI)) {
            try {
                OrtSession.SessionOptions().use { nnapiOptions ->
                    nnapiOptions.setIntraOpNumThreads(4)
                    nnapiOptions.addNnapi()
                    return env.createSession(modelFile.absolutePath, nnapiOptions)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Tier 2 (NNAPI) session creation failed for ${modelFile.name}, falling back", t)
            }
        }

        // Tier 3: Fallback to CPU with multi-threading
        OrtSession.SessionOptions().use { cpuOptions ->
            cpuOptions.setIntraOpNumThreads(4)
            return env.createSession(modelFile.absolutePath, cpuOptions)
        }
    }

    fun eulerStep(
        sample: FloatArray,
        noise: FloatArray,
        sigma: Float,
        nextSigma: Float,
        outSample: FloatArray
    ) {
        val dt = nextSigma - sigma
        val count = minOf(sample.size, noise.size, outSample.size)
        for (i in 0 until count) {
            outSample[i] = sample[i] + dt * noise[i]
        }
    }

    fun planarRgbToArgbBytes(rgbFloats: FloatArray, width: Int, height: Int): ByteArray {
        val pixelCount = width * height
        val bytes = ByteArray(pixelCount * 4)
        val rOffset = 0
        val gOffset = pixelCount
        val bOffset = 2 * pixelCount

        for (i in 0 until pixelCount) {
            val rVal = if (rOffset + i < rgbFloats.size) rgbFloats[rOffset + i] else 0f
            val gVal = if (gOffset + i < rgbFloats.size) rgbFloats[gOffset + i] else 0f
            val bVal = if (bOffset + i < rgbFloats.size) rgbFloats[bOffset + i] else 0f

            val r = clampPixel(rVal)
            val g = clampPixel(gVal)
            val b = clampPixel(bVal)

            val outIdx = i * 4
            bytes[outIdx] = r
            bytes[outIdx + 1] = g
            bytes[outIdx + 2] = b
            bytes[outIdx + 3] = 255.toByte()
        }
        return bytes
    }

    private fun clampPixel(v: Float): Byte {
        val scaled = (v + 1.0f) * 127.5f
        if (scaled.isNaN() || scaled <= 0f) return 0
        if (scaled >= 255f) return 255.toByte()
        return scaled.toInt().toByte()
    }

    fun encodePrompt(
        textEncoderSession: OrtSession,
        promptTokens: IntArray,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): FloatArray {
        val inputName = textEncoderSession.inputNames.firstOrNull { it.contains("input_ids") }
            ?: textEncoderSession.inputNames.iterator().next()

        val inputInfo = textEncoderSession.inputInfo[inputName]?.info as? TensorInfo
        val isInputInt64 = inputInfo?.type == OnnxJavaType.INT64

        val inputTensor = if (isInputInt64) {
            val longBuffer = LongBuffer.allocate(promptTokens.size)
            promptTokens.forEach { longBuffer.put(it.toLong()) }
            longBuffer.flip()
            OnnxTensor.createTensor(env, longBuffer, longArrayOf(1, promptTokens.size.toLong()))
        } else {
            val intBuffer = IntBuffer.wrap(promptTokens)
            OnnxTensor.createTensor(env, intBuffer, longArrayOf(1, promptTokens.size.toLong()))
        }

        // If session expects attention_mask, bind an all-ones [1, 77] tensor
        val maskName = textEncoderSession.inputNames.firstOrNull { it.contains("attention_mask") }
        val maskTensor = maskName?.let { name ->
            val maskInfo = textEncoderSession.inputInfo[name]?.info as? TensorInfo
            if (maskInfo?.type == OnnxJavaType.INT64) {
                val longBuf = LongBuffer.allocate(promptTokens.size)
                repeat(promptTokens.size) { longBuf.put(1L) }
                longBuf.flip()
                OnnxTensor.createTensor(env, longBuf, longArrayOf(1, promptTokens.size.toLong()))
            } else {
                val intBuf = IntBuffer.allocate(promptTokens.size)
                repeat(promptTokens.size) { intBuf.put(1) }
                intBuf.flip()
                OnnxTensor.createTensor(env, intBuf, longArrayOf(1, promptTokens.size.toLong()))
            }
        }

        val inputs = mutableMapOf<String, OnnxTensor>(inputName to inputTensor)
        if (maskName != null && maskTensor != null) {
            inputs[maskName] = maskTensor
        }

        try {
            val result = textEncoderSession.run(inputs)
            result.use { res ->
                var chosenTensor: OnnxTensor? = null
                for (entry in res) {
                    val tensor = entry.value as? OnnxTensor ?: continue
                    if (entry.key.contains("last_hidden_state")) {
                        chosenTensor = tensor
                        break
                    }
                    if (tensor.info.shape.size >= 3 && tensor.info.shape[1] == 77L) {
                        chosenTensor = tensor
                    }
                }
                if (chosenTensor == null) {
                    chosenTensor = (res.get(0) as? OnnxTensor) ?: (res.iterator().next().value as OnnxTensor)
                }

                return extractFloatsFromTensor(chosenTensor)
            }
        } finally {
            inputTensor.close()
            maskTensor?.close()
        }
    }

    fun denoiseLoop(
        unetSession: OrtSession,
        latents: FloatArray,
        textEmbeddings: FloatArray,
        steps: Int,
        onStep: ((Int, Int) -> Unit)? = null,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): FloatArray {
        val schedule = SdTurboScheduler.getSchedule(steps)
        val currentLatents = latents.clone()
        val tempLatents = FloatArray(currentLatents.size)
        val numSteps = schedule.timesteps.size

        val hiddenName = unetSession.inputNames.firstOrNull {
            it.contains("hidden") || it.contains("context")
        } ?: "encoder_hidden_states"
        val hiddenInfo = unetSession.inputInfo[hiddenName]?.info as? TensorInfo
        val hiddenTensor = createFloatTensor(
            env,
            hiddenInfo?.type,
            textEmbeddings,
            longArrayOf(1, 77, textEmbeddings.size / 77L)
        )

        try {
            for (stepIndex in 0 until numSteps) {
                if (isCancelled) {
                    throw CancellationException("Generation cancelled")
                }

                val sigma = schedule.sigmas[stepIndex]
                val nextSigma = schedule.sigmas[stepIndex + 1]
                val timestepVal = schedule.timesteps[stepIndex]

                val scaledLatents = schedule.scaleModelInput(currentLatents, stepIndex)

                val sampleName = unetSession.inputNames.firstOrNull {
                    it.contains("sample") || it.contains("latent")
                } ?: "sample"
                val sampleInfo = unetSession.inputInfo[sampleName]?.info as? TensorInfo
                val sampleTensor = createFloatTensor(
                    env,
                    sampleInfo?.type,
                    scaledLatents,
                    longArrayOf(1, 4, 64, 64)
                )

                val timestepName = unetSession.inputNames.firstOrNull { it.contains("time") } ?: "timestep"
                val timeInfo = unetSession.inputInfo[timestepName]?.info as? TensorInfo
                val timestepTensor = if (timeInfo?.type == OnnxJavaType.INT64) {
                    val buf = LongBuffer.wrap(longArrayOf(timestepVal.toLong()))
                    OnnxTensor.createTensor(env, buf, longArrayOf(1))
                } else if (timeInfo?.type == OnnxJavaType.FLOAT16) {
                    val buf = ShortBuffer.wrap(shortArrayOf(floatToFp16(timestepVal)))
                    OnnxTensor.createTensor(env, buf, longArrayOf(1), OnnxJavaType.FLOAT16)
                } else {
                    val buf = FloatBuffer.wrap(floatArrayOf(timestepVal))
                    OnnxTensor.createTensor(env, buf, longArrayOf(1))
                }

                val inputs = mapOf(
                    sampleName to sampleTensor,
                    timestepName to timestepTensor,
                    hiddenName to hiddenTensor
                )

                try {
                    val result = unetSession.run(inputs)
                    result.use { res ->
                        val noisePredTensor = (res.get(0) as? OnnxTensor) ?: (res.iterator().next().value as OnnxTensor)
                        val noisePred = extractFloatsFromTensor(noisePredTensor)

                        schedule.step(currentLatents, noisePred, stepIndex, tempLatents)
                        System.arraycopy(tempLatents, 0, currentLatents, 0, currentLatents.size)
                    }
                } finally {
                    sampleTensor.close()
                    timestepTensor.close()
                }

                onStep?.invoke(stepIndex + 1, numSteps)
            }
        } finally {
            hiddenTensor.close()
        }

        return currentLatents
    }

    fun decodeVae(
        vaeSession: OrtSession,
        latents: FloatArray,
        width: Int = 512,
        height: Int = 512,
        env: OrtEnvironment = OrtEnvironment.getEnvironment()
    ): ByteArray {
        val scaledLatents = FloatArray(latents.size) { i ->
            latents[i] / VAE_SCALE_FACTOR
        }

        val inputName = vaeSession.inputNames.firstOrNull {
            it.contains("latent") || it.contains("sample")
        } ?: vaeSession.inputNames.iterator().next()

        val latentInfo = vaeSession.inputInfo[inputName]?.info as? TensorInfo
        val latentTensor = createFloatTensor(
            env,
            latentInfo?.type,
            scaledLatents,
            longArrayOf(1, 4, 64, 64)
        )

        val rgbFloats = latentTensor.use { tensor ->
            val result = vaeSession.run(mapOf(inputName to tensor))
            result.use { res ->
                val outTensor = (res.get(0) as? OnnxTensor) ?: (res.iterator().next().value as OnnxTensor)
                extractFloatsFromTensor(outTensor)
            }
        }

        return planarRgbToArgbBytes(rgbFloats, width, height)
    }

    fun resolveComponentFile(modelDir: File, componentName: String): File {
        val flat = File(modelDir, "$componentName.onnx")
        if (flat.exists()) return flat
        val nested = File(File(modelDir, componentName), "model.onnx")
        if (nested.exists()) return nested
        return flat
    }

    fun generate(
        params: GenerationParams,
        modelDir: File,
        onStep: ((Int, Int) -> Unit)? = null
    ): ByteArray {
        isCancelled = false
        val textEncoderFile = resolveComponentFile(modelDir, "text_encoder")
        val unetFile = resolveComponentFile(modelDir, "unet")
        val vaeDecoderFile = resolveComponentFile(modelDir, "vae_decoder")

        val requiredFiles = listOf(textEncoderFile, unetFile, vaeDecoderFile)
        for (file in requiredFiles) {
            if (!file.exists()) {
                throw IllegalStateException("Model component '${file.name}' is invalid or missing in ${modelDir.absolutePath}")
            }
        }

        if (!testSimulationEnabled) {
            for (file in requiredFiles) {
                if (file.length() == 0L) {
                    throw IllegalStateException("Model component '${file.name}' is invalid or missing in ${modelDir.absolutePath}")
                }
            }
        } else {
            return runTestSimulation(params, onStep)
        }

        val env = OrtEnvironment.getEnvironment()
        val promptTokens = tokenizer.tokenize(params.prompt)
        val seed = params.seed ?: System.currentTimeMillis()

        // 1. Text Encoder Session
        val textEmbeddings = createSession(env, textEncoderFile).use { textEncoderSession ->
            encodePrompt(textEncoderSession, promptTokens, env)
        }

        if (isCancelled) throw CancellationException("Generation cancelled")

        // 2. Initial Latents
        val schedule = SdTurboScheduler.getSchedule(params.steps)
        val rawNoise = GaussianNoise.generate(4 * 64 * 64, seed)
        val initialLatents = FloatArray(rawNoise.size) { i -> rawNoise[i] * schedule.initNoiseSigma }

        // 3. UNet Session (1-4 steps SD-Turbo)
        val denoisedLatents = createSession(env, unetFile).use { unetSession ->
            denoiseLoop(unetSession, initialLatents, textEmbeddings, params.steps, onStep, env)
        }

        if (isCancelled) throw CancellationException("Generation cancelled")

        // 4. VAE Decoder Session
        return createSession(env, vaeDecoderFile).use { vaeSession ->
            decodeVae(vaeSession, denoisedLatents, 512, 512, env)
        }
    }

    fun runTestSimulation(
        params: GenerationParams,
        onStep: ((Int, Int) -> Unit)?
    ): ByteArray {
        for (step in 1..params.steps) {
            if (isCancelled) {
                throw CancellationException("Generation cancelled")
            }
            onStep?.invoke(step, params.steps)
        }
        val seed = params.seed ?: System.currentTimeMillis()
        val latents = GaussianNoise.generate(4 * 64 * 64, seed)
        return VaePostProcessor.latentsToRgbBytes(latents, 512, 512)
    }
}
