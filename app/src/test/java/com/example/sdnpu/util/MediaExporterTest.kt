package com.example.sdnpu.util

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class MediaExporterTest {

    @Test
    fun testSanitizeFilename_normalString() {
        val input = "Cyberpunk City Sunset"
        val result = MediaExporter.sanitizeFilename(input)
        assertEquals("Cyberpunk City Sunset", result)
    }

    @Test
    fun testSanitizeFilename_illegalCharacters() {
        val input = "invalid/file:name*with?chars<and>pipes|quotes\""
        val result = MediaExporter.sanitizeFilename(input)
        assertEquals("invalid_file_name_with_chars_and_pipes_quotes_", result)
    }

    @Test
    fun testSanitizeFilename_removesPngExtension() {
        val input = "landscape_artwork.png"
        val result = MediaExporter.sanitizeFilename(input)
        assertEquals("landscape_artwork", result)

        val inputUpper = "portrait.PNG"
        val resultUpper = MediaExporter.sanitizeFilename(inputUpper)
        assertEquals("portrait", resultUpper)
    }

    @Test
    fun testSanitizeFilename_emptyFallback() {
        val result = MediaExporter.sanitizeFilename("   ")
        assertTrue(result.startsWith("sd_image_"))
    }

    @Test
    fun testSanitizeFilename_truncatesLongNames() {
        val longName = "a".repeat(200)
        val result = MediaExporter.sanitizeFilename(longName)
        assertTrue(result.length <= 120)
    }

    @Test
    fun testGetExportFilename_withTitle() {
        val file = File("/dummy/path/sd_turbo_12345.png")
        val filename = MediaExporter.getExportFilename(file, "Golden Retriever Playing")
        assertEquals("Golden Retriever Playing.png", filename)
    }

    @Test
    fun testGetExportFilename_withoutTitle() {
        val file = File("/dummy/path/sd_turbo_12345.png")
        val filename = MediaExporter.getExportFilename(file, null)
        assertEquals("sd_turbo_12345.png", filename)

        val filenameBlank = MediaExporter.getExportFilename(file, "   ")
        assertEquals("sd_turbo_12345.png", filenameBlank)
    }

    @Test
    fun testGetExportFilename_sanitizesInvalidCharsInTitle() {
        val file = File("/dummy/path/sd_turbo_12345.png")
        val filename = MediaExporter.getExportFilename(file, "Cat: 4k/HDR")
        assertEquals("Cat_ 4k_HDR.png", filename)
    }

    @Test
    fun testGetMimeType() {
        assertEquals("image/png", MediaExporter.getMimeType(File("image.png")))
        assertEquals("image/jpeg", MediaExporter.getMimeType(File("photo.jpg")))
        assertEquals("image/jpeg", MediaExporter.getMimeType(File("photo.jpeg")))
        assertEquals("image/webp", MediaExporter.getMimeType(File("photo.webp")))
        assertEquals("image/png", MediaExporter.getMimeType(File("unknown.ext")))
    }

    @Test
    fun testCreateShareIntent_withoutPrompt() {
        val app = Application()
        val tempFile = File.createTempFile("test_export", ".png")
        try {
            val intent = MediaExporter.createShareIntent(app, tempFile, null)
            assertNotNull(intent)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCreateShareIntent_withPrompt() {
        val app = Application()
        val tempFile = File.createTempFile("test_export", ".png")
        try {
            val prompt = "A neon-lit futuristic street"
            val intent = MediaExporter.createShareIntent(app, tempFile, prompt)
            assertNotNull(intent)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testSaveImageToPublicGallery_nonExistentFile() {
        val app = Application()
        val missingFile = File("/non/existent/path/image_${System.currentTimeMillis()}.png")
        val result = MediaExporter.saveImageToPublicGallery(app, missingFile)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FileNotFoundException)
    }

    @Test
    fun testSaveImageToPublicGallery_handlesResolverFailureGracefully() {
        val app = Application()
        val tempFile = File.createTempFile("test_export", ".png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            // Under unit test environment without real MediaStore provider,
            // insert returns null and it should return a failure Result, not throw
            val result = MediaExporter.saveImageToPublicGallery(app, tempFile)
            assertTrue(result.isFailure)
        } finally {
            tempFile.delete()
        }
    }
}
