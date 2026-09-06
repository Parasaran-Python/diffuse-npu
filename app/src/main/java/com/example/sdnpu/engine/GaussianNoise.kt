package com.example.sdnpu.engine

import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

object GaussianNoise {
    fun generate(size: Int, seed: Long? = null): FloatArray {
        val rng = if (seed != null) Random(seed) else Random()
        val result = FloatArray(size)

        var i = 0
        while (i < size) {
            // Box-Muller transform
            var u1 = rng.nextDouble()
            while (u1 <= 1e-15) u1 = rng.nextDouble()
            val u2 = rng.nextDouble()

            val radius = sqrt(-2.0 * ln(u1))
            val theta = 2.0 * Math.PI * u2

            val z0 = (radius * cos(theta)).toFloat()
            val z1 = (radius * sin(theta)).toFloat()

            result[i++] = z0
            if (i < size) {
                result[i++] = z1
            }
        }
        return result
    }
}
