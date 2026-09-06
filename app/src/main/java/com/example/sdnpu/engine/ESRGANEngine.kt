package com.example.sdnpu.engine

import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

object ESRGANEngine {

    @Volatile
    private var isCancelled = false

    external fun nativeLoadEsrganContext(modelPath: String, scale: Int): Boolean
    external fun nativeUpscaleEsrgan(inRgba: ByteArray, inW: Int, inH: Int, scale: Int): ByteArray?
    external fun nativeCancelEsrgan()
    external fun nativeUnloadEsrganContext()

    fun cancel() {
        isCancelled = true
        if (QnnNativeBridge.isLibraryLoaded()) {
            try {
                nativeCancelEsrgan()
            } catch (_: Throwable) {}
        }
    }

    fun upscale(
        inputRgba: ByteArray,
        inWidth: Int,
        inHeight: Int,
        scale: Int,
        modelDir: File? = null,
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

        val hasModelFiles = modelDir != null && File(modelDir, "model.bin").exists()
        if (hasModelFiles && QnnNativeBridge.isLibraryLoaded()) {
            try {
                if (nativeLoadEsrganContext(modelDir!!.absolutePath, scale)) {
                    try {
                        val outBytes = nativeUpscaleEsrgan(inputRgba, inWidth, inHeight, scale)
                        if (isCancelled) {
                            throw CancellationException("Upscaling cancelled")
                        }
                        if (outBytes != null && outBytes.isNotEmpty()) {
                            onProgress?.invoke(1.0f)
                            return outBytes
                        }
                    } finally {
                        nativeUnloadEsrganContext()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Native execution failed or threw, fall through to pure-JVM bicubic fallback
            }
        }

        if (isCancelled) {
            throw CancellationException("Upscaling cancelled")
        }

        return upscaleBicubicJvm(inputRgba, inWidth, inHeight, scale, onProgress)
    }

    private fun bicubicWeight(x: Float): Float {
        val ax = abs(x)
        val a = -0.5f
        return when {
            ax <= 1.0f -> (a + 2.0f) * ax * ax * ax - (a + 3.0f) * ax * ax + 1.0f
            ax < 2.0f -> a * ax * ax * ax - 5.0f * a * ax * ax + 8.0f * a * ax - 4.0f * a
            else -> 0.0f
        }
    }

    private fun upscaleBicubicJvm(
        inputRgba: ByteArray,
        inWidth: Int,
        inHeight: Int,
        scale: Int,
        onProgress: ((Float) -> Unit)?
    ): ByteArray {
        val outWidth = inWidth * scale
        val outHeight = inHeight * scale
        val outBytes = ByteArray(outWidth * outHeight * 4)
        val invScale = 1.0f / scale.toFloat()

        // Precompute sample weights and clamped coordinates for columns
        val xIndices = IntArray(outWidth * 4)
        val xWeights = FloatArray(outWidth * 4)
        for (x in 0 until outWidth) {
            val srcX = (x.toFloat() + 0.5f) * invScale - 0.5f
            val i0 = floor(srcX).toInt()
            var sumW = 0.0f
            val base = x * 4
            for (k in 0 until 4) {
                val px = i0 - 1 + k
                xIndices[base + k] = px.coerceIn(0, inWidth - 1)
                val w = bicubicWeight(srcX - px.toFloat())
                xWeights[base + k] = w
                sumW += w
            }
            if (abs(sumW) > 1e-6f) {
                val invSumW = 1.0f / sumW
                for (k in 0 until 4) {
                    xWeights[base + k] *= invSumW
                }
            }
        }

        // Precompute sample weights and clamped row offsets for rows
        val yRowOffsets = IntArray(outHeight * 4)
        val yWeights = FloatArray(outHeight * 4)
        for (y in 0 until outHeight) {
            val srcY = (y.toFloat() + 0.5f) * invScale - 0.5f
            val j0 = floor(srcY).toInt()
            var sumW = 0.0f
            val base = y * 4
            for (m in 0 until 4) {
                val py = j0 - 1 + m
                val clampedPy = py.coerceIn(0, inHeight - 1)
                yRowOffsets[base + m] = clampedPy * inWidth * 4
                val w = bicubicWeight(srcY - py.toFloat())
                yWeights[base + m] = w
                sumW += w
            }
            if (abs(sumW) > 1e-6f) {
                val invSumW = 1.0f / sumW
                for (m in 0 until 4) {
                    yWeights[base + m] *= invSumW
                }
            }
        }

        // Bicubic convolution with cooperative cancellation
        for (y in 0 until outHeight) {
            if (isCancelled) {
                throw CancellationException("Upscaling cancelled")
            }
            onProgress?.invoke(y.toFloat() / outHeight.toFloat())

            val yBase = y * 4
            val rOff0 = yRowOffsets[yBase]
            val rOff1 = yRowOffsets[yBase + 1]
            val rOff2 = yRowOffsets[yBase + 2]
            val rOff3 = yRowOffsets[yBase + 3]

            val wy0 = yWeights[yBase]
            val wy1 = yWeights[yBase + 1]
            val wy2 = yWeights[yBase + 2]
            val wy3 = yWeights[yBase + 3]

            var outIdx = y * outWidth * 4

            for (x in 0 until outWidth) {
                val xBase = x * 4
                val px0 = xIndices[xBase] * 4
                val px1 = xIndices[xBase + 1] * 4
                val px2 = xIndices[xBase + 2] * 4
                val px3 = xIndices[xBase + 3] * 4

                val wx0 = xWeights[xBase]
                val wx1 = xWeights[xBase + 1]
                val wx2 = xWeights[xBase + 2]
                val wx3 = xWeights[xBase + 3]

                var r = 0.0f
                var g = 0.0f
                var b = 0.0f

                if (wy0 != 0.0f) {
                    val w00 = wy0 * wx0
                    val w01 = wy0 * wx1
                    val w02 = wy0 * wx2
                    val w03 = wy0 * wx3

                    val idx0 = rOff0 + px0
                    r += (inputRgba[idx0].toInt() and 0xFF) * w00
                    g += (inputRgba[idx0 + 1].toInt() and 0xFF) * w00
                    b += (inputRgba[idx0 + 2].toInt() and 0xFF) * w00

                    val idx1 = rOff0 + px1
                    r += (inputRgba[idx1].toInt() and 0xFF) * w01
                    g += (inputRgba[idx1 + 1].toInt() and 0xFF) * w01
                    b += (inputRgba[idx1 + 2].toInt() and 0xFF) * w01

                    val idx2 = rOff0 + px2
                    r += (inputRgba[idx2].toInt() and 0xFF) * w02
                    g += (inputRgba[idx2 + 1].toInt() and 0xFF) * w02
                    b += (inputRgba[idx2 + 2].toInt() and 0xFF) * w02

                    val idx3 = rOff0 + px3
                    r += (inputRgba[idx3].toInt() and 0xFF) * w03
                    g += (inputRgba[idx3 + 1].toInt() and 0xFF) * w03
                    b += (inputRgba[idx3 + 2].toInt() and 0xFF) * w03
                }

                if (wy1 != 0.0f) {
                    val w10 = wy1 * wx0
                    val w11 = wy1 * wx1
                    val w12 = wy1 * wx2
                    val w13 = wy1 * wx3

                    val idx0 = rOff1 + px0
                    r += (inputRgba[idx0].toInt() and 0xFF) * w10
                    g += (inputRgba[idx0 + 1].toInt() and 0xFF) * w10
                    b += (inputRgba[idx0 + 2].toInt() and 0xFF) * w10

                    val idx1 = rOff1 + px1
                    r += (inputRgba[idx1].toInt() and 0xFF) * w11
                    g += (inputRgba[idx1 + 1].toInt() and 0xFF) * w11
                    b += (inputRgba[idx1 + 2].toInt() and 0xFF) * w11

                    val idx2 = rOff1 + px2
                    r += (inputRgba[idx2].toInt() and 0xFF) * w12
                    g += (inputRgba[idx2 + 1].toInt() and 0xFF) * w12
                    b += (inputRgba[idx2 + 2].toInt() and 0xFF) * w12

                    val idx3 = rOff1 + px3
                    r += (inputRgba[idx3].toInt() and 0xFF) * w13
                    g += (inputRgba[idx3 + 1].toInt() and 0xFF) * w13
                    b += (inputRgba[idx3 + 2].toInt() and 0xFF) * w13
                }

                if (wy2 != 0.0f) {
                    val w20 = wy2 * wx0
                    val w21 = wy2 * wx1
                    val w22 = wy2 * wx2
                    val w23 = wy2 * wx3

                    val idx0 = rOff2 + px0
                    r += (inputRgba[idx0].toInt() and 0xFF) * w20
                    g += (inputRgba[idx0 + 1].toInt() and 0xFF) * w20
                    b += (inputRgba[idx0 + 2].toInt() and 0xFF) * w20

                    val idx1 = rOff2 + px1
                    r += (inputRgba[idx1].toInt() and 0xFF) * w21
                    g += (inputRgba[idx1 + 1].toInt() and 0xFF) * w21
                    b += (inputRgba[idx1 + 2].toInt() and 0xFF) * w21

                    val idx2 = rOff2 + px2
                    r += (inputRgba[idx2].toInt() and 0xFF) * w22
                    g += (inputRgba[idx2 + 1].toInt() and 0xFF) * w22
                    b += (inputRgba[idx2 + 2].toInt() and 0xFF) * w22

                    val idx3 = rOff2 + px3
                    r += (inputRgba[idx3].toInt() and 0xFF) * w23
                    g += (inputRgba[idx3 + 1].toInt() and 0xFF) * w23
                    b += (inputRgba[idx3 + 2].toInt() and 0xFF) * w23
                }

                if (wy3 != 0.0f) {
                    val w30 = wy3 * wx0
                    val w31 = wy3 * wx1
                    val w32 = wy3 * wx2
                    val w33 = wy3 * wx3

                    val idx0 = rOff3 + px0
                    r += (inputRgba[idx0].toInt() and 0xFF) * w30
                    g += (inputRgba[idx0 + 1].toInt() and 0xFF) * w30
                    b += (inputRgba[idx0 + 2].toInt() and 0xFF) * w30

                    val idx1 = rOff3 + px1
                    r += (inputRgba[idx1].toInt() and 0xFF) * w31
                    g += (inputRgba[idx1 + 1].toInt() and 0xFF) * w31
                    b += (inputRgba[idx1 + 2].toInt() and 0xFF) * w31

                    val idx2 = rOff3 + px2
                    r += (inputRgba[idx2].toInt() and 0xFF) * w32
                    g += (inputRgba[idx2 + 1].toInt() and 0xFF) * w32
                    b += (inputRgba[idx2 + 2].toInt() and 0xFF) * w32

                    val idx3 = rOff3 + px3
                    r += (inputRgba[idx3].toInt() and 0xFF) * w33
                    g += (inputRgba[idx3 + 1].toInt() and 0xFF) * w33
                    b += (inputRgba[idx3 + 2].toInt() and 0xFF) * w33
                }

                outBytes[outIdx]     = r.roundToInt().coerceIn(0, 255).toByte()
                outBytes[outIdx + 1] = g.roundToInt().coerceIn(0, 255).toByte()
                outBytes[outIdx + 2] = b.roundToInt().coerceIn(0, 255).toByte()
                outBytes[outIdx + 3] = 255.toByte()
                outIdx += 4
            }
        }

        if (isCancelled) {
            throw CancellationException("Upscaling cancelled")
        }

        onProgress?.invoke(1.0f)
        return outBytes
    }
}
