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
        assertTrue(File(File(modelsDir, "test_model"), ".complete").exists())
        assertEquals(listOf("test_model"), modelManager.listLocalModels())
    }

    @Test
    fun testUrlNormalizationInFetchManifestAndDownloadModel() = runBlocking {
        val testContent = "sample model tensor weights"
        val expectedHash = ChecksumVerifier.calculateSha256(tempFolder.newFile().apply { writeText(testContent) })

        val manifestJson = """
        {
          "model_id": "url_norm_model",
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

        // Provide URL ending with /manifest.json and whitespace
        val manifestUrl = "  ${server.url("/").toString().removeSuffix("/")}/manifest.json  "
        val manifestResult = modelManager.fetchManifest(manifestUrl)
        assertTrue(manifestResult.isSuccess)
        val manifest = manifestResult.getOrThrow()
        assertEquals("url_norm_model", manifest.modelId)

        val statuses = modelManager.downloadModel(manifest, manifestUrl).toList()
        assertTrue(statuses.any { it is DownloadStatus.Completed })
        assertTrue(File(File(modelsDir, "url_norm_model"), ".complete").exists())
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

        val corruptModelDir = File(modelsDir, "corrupt_model")
        assertFalse(corruptModelDir.exists())
    }

    @Test
    fun testDownloadModelHttpErrorCleansUpDirectory() = runBlocking {
        val manifest = ModelManifest(
            modelId = "failed_model",
            version = "1.0",
            components = listOf(
                ModelComponent("unet", "unet.bin", "dummyhash")
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )

        server.enqueue(MockResponse().setResponseCode(500))
        val baseUrl = server.url("/").toString()

        val statuses = modelManager.downloadModel(manifest, baseUrl).toList()
        val failed = statuses.filterIsInstance<DownloadStatus.Failed>()
        assertTrue(failed.isNotEmpty())

        val failedModelDir = File(modelsDir, "failed_model")
        assertFalse(failedModelDir.exists())
    }

    @Test
    fun testDeleteModel() {
        val testModelDir = File(modelsDir, "to_delete").apply { mkdirs() }
        File(testModelDir, "dummy.bin").writeText("dummy")
        File(testModelDir, ".complete").createNewFile()
        assertTrue(modelManager.listLocalModels().contains("to_delete"))

        val deleted = modelManager.deleteModel("to_delete")
        assertTrue(deleted)
        assertFalse(testModelDir.exists())
        assertFalse(modelManager.listLocalModels().contains("to_delete"))

        val deletedNonExistent = modelManager.deleteModel("non_existent")
        assertFalse(deletedNonExistent)
    }

    @Test
    fun testListLocalModelsExcludesIncompleteDirectories() {
        val incompleteDir = File(modelsDir, "incomplete_dir").apply { mkdirs() }
        File(incompleteDir, "model.bin").writeText("data")
        assertFalse(modelManager.listLocalModels().contains("incomplete_dir"))

        File(incompleteDir, ".complete").createNewFile()
        assertTrue(modelManager.listLocalModels().contains("incomplete_dir"))
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
