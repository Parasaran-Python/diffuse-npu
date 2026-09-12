package com.example.sdnpu.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OnnxTensor
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.roundToInt

object OnnxEsrganEngine {
    private const val TAG = "OnnxEsrganEngine"

    @Volatile
    private var isCancelled = false

    @Volatile
    var testSimulationEnabled: Boolean = false

    fun cancel() {
        isCancelled = true
    }

    fun rgbaToPlanarRgbFloats(rgba: ByteArray, width: Int, height: Int): FloatArray {
        require(width > 0) { "Width must be positive: $width" }
        require(height > 0) { "Height must be positive: $height" }
        val planeSize = width * height
        val expectedSize = 4 * planeSize
        require(rgba.size == expectedSize) {
            "RGBA buffer size mismatch: expected $expectedSize, got ${rgba.size}"
        }

        val floats = FloatArray(3 * planeSize)
        for (i in 0 until planeSize) {
            val srcOffset = i * 4
            floats[0 * planeSize + i] = (rgba[srcOffset].toInt() and 0xFF) / 255.0f
            floats[1 * planeSize + i] = (rgba[srcOffset + 1].toInt() and 0xFF) / 255.0f
            floats[2 * planeSize + i] = (rgba[srcOffset + 2].toInt() and 0xFF) / 255.0f
        }
        return floats
    }

    fun planarRgbFloatsToRgba(floats: FloatArray, width: Int, height: Int): ByteArray {
        require(width > 0) { "Width must be positive: $width" }
        require(height > 0) { "Height must be positive: $height" }
        val planeSize = width * height
        val expectedSize = 3 * planeSize
        require(floats.size >= expectedSize) {
            "Float buffer size mismatch: expected at least $expectedSize, got ${floats.size}"
        }

        val rgba = ByteArray(4 * planeSize)
        for (i in 0 until planeSize) {
            val rVal = floats[0 * planeSize + i] * 255.0f
            val gVal = floats[1 * planeSize + i] * 255.0f
            val bVal = floats[2 * planeSize + i] * 255.0f
            val dstOffset = i * 4
            rgba[dstOffset]     = if (rVal.isNaN()) 0.toByte() else rVal.roundToInt().coerceIn(0, 255).toByte()
            rgba[dstOffset + 1] = if (gVal.isNaN()) 0.toByte() else gVal.roundToInt().coerceIn(0, 255).toByte()
            rgba[dstOffset + 2] = if (bVal.isNaN()) 0.toByte() else bVal.roundToInt().coerceIn(0, 255).toByte()
            rgba[dstOffset + 3] = 255.toByte()
        }
        return rgba
    }

    fun createSession(
        env: OrtEnvironment,
        modelFile: File,
        preferredBackendType: BackendType? = null
    ): OrtSession {
        val (session, _) = com.example.sdnpu.engine.npu.NpuBackendRegistry.createSession(
            env = env,
            modelFile = modelFile,
            profile = null,
            preferredBackendType = preferredBackendType
        )
        return session
    }

    fun upscale(
        inputRgba: ByteArray,
        inWidth: Int,
        inHeight: Int,
        scale: Int,
        modelDir: File,
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray {
        require(scale in listOf(2, 4)) {
            "Unsupported upscale scale factor: $scale. Only 2 and 4 are supported."
        }
        require(inWidth > 0) { "Input width must be positive: $inWidth" }
        require(inHeight > 0) { "Input height must be positive: $inHeight" }
        val expectedSize = inWidth * inHeight * 4
        require(inputRgba.size == expectedSize) {
            "Input RGBA buffer size mismatch: expected $expectedSize, got ${inputRgba.size}"
        }

        isCancelled = false

        val modelFile = listOf(
            File(modelDir, "model.onnx"),
            File(modelDir, "RealESRGAN_x${scale}plus.fp16.onnx")
        ).firstOrNull { it.exists() && it.length() > 0 }
            ?: throw IllegalStateException(
                "No valid ONNX model found in ${modelDir.absolutePath} for scale x$scale (expected model.onnx or RealESRGAN_x${scale}plus.fp16.onnx)"
            )

        if (testSimulationEnabled) {
            if (isCancelled) throw CancellationException("Upscaling cancelled")
            onProgress?.invoke(0.1f)
            if (isCancelled) throw CancellationException("Upscaling cancelled")
            onProgress?.invoke(0.5f)
            val result = ESRGANEngine.upscaleBicubicJvm(inputRgba, inWidth, inHeight, scale, onProgress)
            onProgress?.invoke(1.0f)
            return result
        }

        if (isCancelled) throw CancellationException("Upscaling cancelled")
        onProgress?.invoke(0.1f)

        val env = OrtEnvironment.getEnvironment()
        return createSession(env, modelFile).use { session ->
            if (isCancelled) throw CancellationException("Upscaling cancelled")
            onProgress?.invoke(0.2f)

            val inputFloats = rgbaToPlanarRgbFloats(inputRgba, inWidth, inHeight)
            val inputName = session.inputNames.firstOrNull() ?: throw IllegalStateException("Model has no inputs")
            val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
            val inputTensor = OnnxDiffusionEngine.createFloatTensor(
                env,
                inputInfo?.type,
                inputFloats,
                longArrayOf(1, 3, inHeight.toLong(), inWidth.toLong())
            )

            val outFloats = inputTensor.use { tensor ->
                if (isCancelled) throw CancellationException("Upscaling cancelled")
                onProgress?.invoke(0.4f)
                val result = session.run(mapOf(inputName to tensor))
                result.use { res ->
                    if (isCancelled) throw CancellationException("Upscaling cancelled")
                    val outTensor = (res.get(0) as? OnnxTensor) ?: (res.iterator().next().value as OnnxTensor)
                    OnnxDiffusionEngine.extractFloatsFromTensor(outTensor)
                }
            }

            if (isCancelled) throw CancellationException("Upscaling cancelled")
            onProgress?.invoke(0.9f)

            val outWidth = inWidth * scale
            val outHeight = inHeight * scale
            val outRgba = planarRgbFloatsToRgba(outFloats, outWidth, outHeight)
            onProgress?.invoke(1.0f)
            outRgba
        }
    }
}
