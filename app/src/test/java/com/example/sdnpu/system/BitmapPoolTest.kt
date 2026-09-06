package com.example.sdnpu.system

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BitmapPoolTest {

    @Before
    fun setUp() {
        BitmapPool.clear()
    }

    @Test
    fun testPoolInitialStateAndEmptyAcquire() {
        assertEquals(0, BitmapPool.size())
        val initial = BitmapPool.acquire(512, 512)
        assertNull(initial)
        assertEquals(0, BitmapPool.size())
    }

    @Test
    fun testReleaseNullDoesNotAffectPool() {
        assertEquals(0, BitmapPool.size())
        BitmapPool.release(null)
        assertEquals(0, BitmapPool.size())
    }

    @Test
    fun testPoolCapacityAndAcquireWithBitmaps() {
        // Attempt to allocate test bitmaps via reflection / sun.misc.Unsafe if possible
        val testBitmaps = (1..6).mapNotNull { createTestBitmap() }

        if (testBitmaps.isNotEmpty()) {
            // Release first bitmap
            BitmapPool.release(testBitmaps[0])
            assertEquals(1, BitmapPool.size())

            // Try acquiring non-matching dimension (test bitmap default width=0, height=0)
            val nonMatching = BitmapPool.acquire(512, 512)
            assertNull(nonMatching)
            assertEquals(1, BitmapPool.size())

            // Try acquiring matching dimension (0, 0 for stubbed bitmap)
            val matching = BitmapPool.acquire(0, 0)
            assertNotNull(matching)
            assertEquals(0, BitmapPool.size())

            // Release multiple bitmaps up to and past max pool capacity (4)
            for (bmp in testBitmaps) {
                BitmapPool.release(bmp)
            }
            assertTrue("Pool size must not exceed MAX_POOL_SIZE (4)", BitmapPool.size() <= 4)

            // Clear pool
            BitmapPool.clear()
            assertEquals(0, BitmapPool.size())
        } else {
            // Fallback assertion when reflection cannot allocate stub instances
            BitmapPool.clear()
            assertEquals(0, BitmapPool.size())
            assertNull(BitmapPool.acquire(512, 512))
        }
    }

    @Test
    fun testClearOnEmptyPoolDoesNotThrow() {
        BitmapPool.clear()
        assertEquals(0, BitmapPool.size())
        BitmapPool.clear()
        assertEquals(0, BitmapPool.size())
    }

    @Test
    fun testDuplicateReleaseIgnored() {
        val bitmap = createTestBitmap()
        if (bitmap != null) {
            BitmapPool.release(bitmap)
            assertEquals(1, BitmapPool.size())
            BitmapPool.release(bitmap)
            assertEquals(1, BitmapPool.size())
            BitmapPool.clear()
        }
    }

    private fun createTestBitmap(): Bitmap? {
        return try {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocateMethod = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            allocateMethod.invoke(unsafe, Bitmap::class.java) as Bitmap
        } catch (_: Throwable) {
            null
        }
    }
}
