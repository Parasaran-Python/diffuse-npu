package com.example.sdnpu.engine

import kotlin.math.max
import kotlin.math.min

object VaePostProcessor {
    const val VAE_SCALE_FACTOR = 0.18215f

    fun latentsToRgbBytes(latents: FloatArray, width: Int = 512, height: Int = 512): ByteArray {
        val pixelCount = width * height
        val bytes = ByteArray(pixelCount * 4) // ARGB_8888 byte array

        val scale = 1.0f / VAE_SCALE_FACTOR
        val latentArea = 64 * 64

        for (y in 0 until height) {
            val ly = (y * 64) / height
            for (x in 0 until width) {
                val lx = (x * 64) / width
                val latentIdx = ly * 64 + lx

                // Extract pseudo-RGB from latent channels 0, 1, 2
                val rFloat = if (latentIdx < latents.size) latents[latentIdx] * scale else 0f
                val gFloat = if (latentIdx + latentArea < latents.size) latents[latentIdx + latentArea] * scale else 0f
                val bFloat = if (latentIdx + 2 * latentArea < latents.size) latents[latentIdx + 2 * latentArea] * scale else 0f

                val r = clamp((rFloat + 1.0f) * 127.5f)
                val g = clamp((gFloat + 1.0f) * 127.5f)
                val b = clamp((bFloat + 1.0f) * 127.5f)

                val outIdx = (y * width + x) * 4
                bytes[outIdx] = b.toByte()
                bytes[outIdx + 1] = g.toByte()
                bytes[outIdx + 2] = r.toByte()
                bytes[outIdx + 3] = 255.toByte() // Alpha
            }
        }
        return bytes
    }

    private fun clamp(v: Float): Int = max(0, min(255, v.toInt()))
}
