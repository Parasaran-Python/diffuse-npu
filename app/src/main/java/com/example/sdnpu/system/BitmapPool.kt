package com.example.sdnpu.system

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentLinkedQueue

object BitmapPool {
    private val pool = ConcurrentLinkedQueue<Bitmap>()
    private const val MAX_POOL_SIZE = 4

    fun acquire(width: Int, height: Int): Bitmap? {
        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val bitmap = iterator.next()
            if (bitmap.isRecycled) {
                iterator.remove()
                continue
            }
            if (bitmap.width == width && bitmap.height == height) {
                iterator.remove()
                return bitmap
            }
        }
        return null
    }

    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (pool.size < MAX_POOL_SIZE) {
            pool.offer(bitmap)
        } else {
            try {
                bitmap.recycle()
            } catch (_: Throwable) {}
        }
    }

    fun size(): Int = pool.size

    fun clear() {
        while (pool.isNotEmpty()) {
            val b = pool.poll()
            if (b != null && !b.isRecycled) {
                try {
                    b.recycle()
                } catch (_: Throwable) {}
            }
        }
    }
}
