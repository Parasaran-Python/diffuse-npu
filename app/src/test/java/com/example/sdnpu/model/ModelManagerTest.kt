package com.example.sdnpu.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var modelManager: ModelManager
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        modelsDir = tempFolder.newFolder("models")
        modelManager = ModelManager(modelsDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testFetchManifestAndDownloadModel() = runBlocking {
        val testContent = "sample model tensor weights"
        val expectedHash = ChecksumVerifier.calculateSha256(tempFolder.newFile().apply { writeText(testContent) })

        val manifestJson = """
        {
          "model_id": "test_model",
          "version": "1.0",
          "components": [
            {"name": "unet", "file": "unet.bin", "sha256": "$expectedHash"}
          ],
          "qnn_sdk_version": "2.49.0",
          "target_htp": "v73"
        }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(manifestJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(testContent))

        val baseUrl = server.url("/").toString()
        val manifestResult = modelManager.fetchManifest(baseUrl)
        assertTrue(manifestResult.isSuccess)
        val manifest = manifestResult.getOrThrow()

        val statuses = modelManager.downloadModel(manifest, baseUrl).toList()
        assertTrue(statuses.any { it is DownloadStatus.Completed })
        assertTrue(modelManager.isModelComplete("test_model", manifest))
        assertEquals(listOf("test_model"), modelManager.listLocalModels())
    }

    @Test
    fun testFetchManifestHttpError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val baseUrl = server.url("/").toString()
        val manifestResult = modelManager.fetchManifest(baseUrl)
        assertTrue(manifestResult.isFailure)
    }

    @Test
    fun testDownloadModelChecksumMismatch() = runBlocking {
        val expectedHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val corruptContent = "corrupted data payload"

        val manifest = ModelManifest(
            modelId = "corrupt_model",
            version = "1.0",
            components = listOf(
                ModelComponent("unet", "unet.bin", expectedHash)
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody(corruptContent))
        val baseUrl = server.url("/").toString()

        val statuses = modelManager.downloadModel(manifest, baseUrl).toList()
        val failed = statuses.filterIsInstance<DownloadStatus.Failed>()
        assertTrue(failed.isNotEmpty())
        assertTrue(failed.first().reason.contains("Checksum verification failed"))

        val corruptFile = File(File(modelsDir, "corrupt_model"), "unet.bin")
        assertFalse(corruptFile.exists())
    }

    @Test
    fun testDeleteModel() {
        val testModelDir = File(modelsDir, "to_delete").apply { mkdirs() }
        File(testModelDir, "dummy.bin").writeText("dummy")
        assertTrue(modelManager.listLocalModels().contains("to_delete"))

        val deleted = modelManager.deleteModel("to_delete")
        assertTrue(deleted)
        assertFalse(testModelDir.exists())
        assertFalse(modelManager.listLocalModels().contains("to_delete"))

        val deletedNonExistent = modelManager.deleteModel("non_existent")
        assertFalse(deletedNonExistent)
    }

    @Test
    fun testIsModelCompleteReturnsFalseWhenMissingOrIncomplete() {
        val manifest = ModelManifest(
            modelId = "incomplete_model",
            version = "1.0",
            components = listOf(
                ModelComponent("unet", "unet.bin", "somehash")
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )

        assertFalse(modelManager.isModelComplete("incomplete_model", manifest))

        val modelDir = File(modelsDir, "incomplete_model").apply { mkdirs() }
        assertFalse(modelManager.isModelComplete("incomplete_model", manifest))

        val wrongHashFile = File(modelDir, "unet.bin").apply { writeText("wrong") }
        assertFalse(modelManager.isModelComplete("incomplete_model", manifest))
    }
}
