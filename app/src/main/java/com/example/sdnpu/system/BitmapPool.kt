package com.example.sdnpu.system

import android.graphics.Bitmap

object BitmapPool {
    private val pool = mutableListOf<Bitmap>()
    const val MAX_POOL_SIZE = 4

    @Synchronized
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

    @Synchronized
    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (pool.contains(bitmap)) return
        if (pool.size < MAX_POOL_SIZE) {
            pool.add(bitmap)
        } else {
            try {
                bitmap.recycle()
            } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun size(): Int = pool.size

    @Synchronized
    fun clear() {
        for (b in pool) {
            if (!b.isRecycled) {
                try {
                    b.recycle()
                } catch (_: Throwable) {}
            }
        }
        pool.clear()
    }
}
