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

    @Test
    fun testListDiffusionModelsAndRealESRGANModels() {
        val sdDir = File(modelsDir, "dreamshaper_v8").apply { mkdirs() }
        File(sdDir, ".complete").createNewFile()

        val esrgan2xDir = File(modelsDir, "realesrgan_x2plus").apply { mkdirs() }
        File(esrgan2xDir, ".complete").createNewFile()

        val esrgan4xDir = File(modelsDir, "realesrgan_x4plus").apply { mkdirs() }
        File(esrgan4xDir, ".complete").createNewFile()

        val allModels = modelManager.listLocalModels()
        assertEquals(3, allModels.size)

        val diffusionModels = modelManager.listDiffusionModels()
        assertEquals(listOf("dreamshaper_v8"), diffusionModels)

        val esrganModels = modelManager.listRealESRGANModels()
        assertEquals(listOf("realesrgan_x2plus", "realesrgan_x4plus"), esrganModels.sorted())
    }

    @Test
    fun testIsRealESRGANAvailable() {
        assertFalse(modelManager.isRealESRGANAvailable(2))
        assertFalse(modelManager.isRealESRGANAvailable(4))

        val esrgan2xDir = File(modelsDir, "realesrgan_x2plus").apply { mkdirs() }
        assertFalse(modelManager.isRealESRGANAvailable(2))

        File(esrgan2xDir, "model.bin").writeText("dummy weights")
        assertFalse(modelManager.isRealESRGANAvailable(2)) // missing .complete

        File(esrgan2xDir, ".complete").createNewFile()
        assertTrue(modelManager.isRealESRGANAvailable(2))
        assertFalse(modelManager.isRealESRGANAvailable(4))
    }

    @Test
    fun testListLocalModelsDetectsOnnxModelsWithoutCompleteMarker() {
        val sdturboDir = File(modelsDir, "sdturbo").apply { mkdirs() }
        File(sdturboDir, "text_encoder.onnx").createNewFile()
        File(sdturboDir, "unet.onnx").createNewFile()
        File(sdturboDir, "vae_decoder.onnx").createNewFile()

        val localModels = modelManager.listLocalModels()
        assertTrue(localModels.contains("sdturbo"))

        val diffusionModels = modelManager.listDiffusionModels()
        assertTrue(diffusionModels.contains("sdturbo"))
    }

    @Test
    fun testListLocalModelsExcludesIncompleteOnnxDirectory() {
        val incompleteOnnxDir = File(modelsDir, "incomplete_onnx").apply { mkdirs() }
        File(incompleteOnnxDir, "text_encoder.onnx").createNewFile()
        File(incompleteOnnxDir, "unet.onnx").createNewFile()
        // Missing vae_decoder.onnx and no .complete
        assertFalse(modelManager.listLocalModels().contains("incomplete_onnx"))
        assertFalse(modelManager.listDiffusionModels().contains("incomplete_onnx"))
    }

    @Test
    fun testLoadLocalManifestForSdTurboFallback() {
        File(modelsDir, "sdturbo").apply { mkdirs() }
        val manifest = modelManager.loadLocalManifest("sdturbo")
        assertNotNull(manifest)
        assertEquals("sdturbo", manifest?.modelId)
        assertEquals(3, manifest?.components?.size)
    }

    @Test
    fun testDownloadModelCandidateUrlFallback() = runBlocking {
        val testContent = "huggingface optimum onnx weights"
        val manifest = ModelManifest(
            modelId = "hf_model",
            version = "1.0",
            components = listOf(
                ModelComponent("text_encoder", "text_encoder.onnx", "")
            ),
            qnnSdkVersion = "ort-1.20",
            targetHtp = "v73"
        )

        // First candidate: /text_encoder.onnx -> 404
        server.enqueue(MockResponse().setResponseCode(404))
        // Second candidate: /text_encoder/model.onnx -> 200
        server.enqueue(MockResponse().setResponseCode(200).setBody(testContent))

        val baseUrl = server.url("/").toString()
        val statuses = modelManager.downloadModel(manifest, baseUrl).toList()

        assertTrue(statuses.any { it is DownloadStatus.Completed })
        val downloadedFile = File(File(modelsDir, "hf_model"), "text_encoder.onnx")
        assertTrue(downloadedFile.exists())
        assertEquals(testContent, downloadedFile.readText())
    }

    @Test
    fun testDownloadModelSkipsChecksumWhenShaNotProvided() = runBlocking {
        val testContent = "sample data without strict sha256"
        val manifest = ModelManifest(
            modelId = "loose_sha_model",
            version = "1.0",
            components = listOf(
                ModelComponent("unet", "unet.bin", ".unet") // prefix dummy hash
            ),
            qnnSdkVersion = "2.49.0",
            targetHtp = "v73"
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody(testContent))
        val baseUrl = server.url("/").toString()
        val statuses = modelManager.downloadModel(manifest, baseUrl).toList()

        assertTrue(statuses.any { it is DownloadStatus.Completed })
        val targetFile = File(File(modelsDir, "loose_sha_model"), "unet.bin")
        assertTrue(targetFile.exists())
    }
}
