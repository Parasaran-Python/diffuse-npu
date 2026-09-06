package com.example.sdnpu.model

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelManager(
    private val baseStorageDir: File,
    private val secondaryStorageDir: File? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    init {
        if (!baseStorageDir.exists()) {
            baseStorageDir.mkdirs()
        }
        secondaryStorageDir?.let {
            if (!it.exists()) it.mkdirs()
        }
    }

    suspend fun fetchManifest(baseUrl: String): Result<ModelManifest> = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().removeSuffix("/manifest.json").removeSuffix("/")
        val url = "$cleanBase/manifest.json"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server returned HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty manifest body"))
                val manifest = gson.fromJson(body, ModelManifest::class.java)
                Result.success(manifest)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun downloadModel(manifest: ModelManifest, baseUrl: String): Flow<DownloadStatus> = flow {
        val cleanBase = baseUrl.trim().removeSuffix("/manifest.json").removeSuffix("/")
        val cleanBaseUrl = "$cleanBase/"
        val modelDir = File(baseStorageDir, manifest.modelId)
        val completeFile = File(modelDir, ".complete")

        fun cleanupIfIncomplete() {
            if (!completeFile.exists() && modelDir.exists()) {
                modelDir.deleteRecursively()
            }
        }

        try {
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            // Save manifest locally
            val manifestFile = File(modelDir, "manifest.json")
            manifestFile.writeText(gson.toJson(manifest))

            for (comp in manifest.components) {
                val targetFile = File(modelDir, comp.file)
                val candidateUrls = mutableListOf<String>()
                candidateUrls.add("$cleanBaseUrl${comp.file}")
                if (comp.file.endsWith(".onnx")) {
                    candidateUrls.add("$cleanBaseUrl${comp.name}/model.onnx")
                }
                if (!comp.file.endsWith(".bin")) {
                    candidateUrls.add("$cleanBaseUrl${comp.name}.bin")
                }

                emit(DownloadStatus.DownloadingComponent(comp.name, 0L, -1L, 0))

                var response: okhttp3.Response? = null
                var successfulCandidateUrl = ""
                var lastError: Exception? = null

                for (candidateUrl in candidateUrls) {
                    val request = Request.Builder().url(candidateUrl).build()
                    try {
                        val candidateResponse = client.newCall(request).execute()
                        if (candidateResponse.isSuccessful) {
                            response = candidateResponse
                            successfulCandidateUrl = candidateUrl
                            break
                        } else {
                            candidateResponse.close()
                        }
                    } catch (e: Exception) {
                        lastError = e
                    }
                }

                if (response == null) {
                    cleanupIfIncomplete()
                    val err = lastError?.message ?: "File not found at candidate URLs"
                    emit(DownloadStatus.Failed("Failed downloading ${comp.name}: $err"))
                    return@flow
                }

                val responseBody = response.body ?: run {
                    response.close()
                    cleanupIfIncomplete()
                    emit(DownloadStatus.Failed("Empty response body for ${comp.name}"))
                    return@flow
                }

                val totalBytes = responseBody.contentLength()
                var downloadedBytes = 0L

                try {
                    response.use {
                        responseBody.byteStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(32768)
                                var read: Int
                                var lastReportTime = System.currentTimeMillis()

                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportTime > 200 || downloadedBytes == totalBytes) {
                                        val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                                        emit(DownloadStatus.DownloadingComponent(comp.name, downloadedBytes, totalBytes, percent))
                                        lastReportTime = now
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    cleanupIfIncomplete()
                    emit(DownloadStatus.Failed("Failed reading ${comp.name}: ${e.message}"))
                    return@flow
                }

                val isSha256Provided = comp.sha256.isNotBlank() &&
                        comp.sha256.length == 64 &&
                        comp.sha256.all { it in "0123456789abcdefABCDEF" }

                if (isSha256Provided) {
                    emit(DownloadStatus.VerifyingChecksum(comp.name))
                    val valid = ChecksumVerifier.verifyFile(targetFile, comp.sha256)
                    if (!valid) {
                        cleanupIfIncomplete()
                        emit(DownloadStatus.Failed("Checksum verification failed for ${comp.name}"))
                        return@flow
                    }
                }
            }

            completeFile.createNewFile()
            emit(DownloadStatus.Completed(manifest.modelId, modelDir.absolutePath))
        } catch (e: CancellationException) {
            cleanupIfIncomplete()
            throw e
        } catch (e: Exception) {
            cleanupIfIncomplete()
            emit(DownloadStatus.Failed("Download failed: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    fun isModelComplete(modelId: String, manifest: ModelManifest): Boolean {
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir)
        return dirs.any { dir ->
            val modelDir = File(dir, modelId)
            if (modelDir.exists() && modelDir.isDirectory && File(modelDir, ".complete").exists()) {
                manifest.components.all { comp ->
                    val file = File(modelDir, comp.file)
                    val isSha256Provided = comp.sha256.isNotBlank() &&
                            comp.sha256.length == 64 &&
                            comp.sha256.all { it in "0123456789abcdefABCDEF" }
                    if (isSha256Provided) {
                        ChecksumVerifier.verifyFile(file, comp.sha256)
                    } else {
                        file.exists() && file.length() > 0
                    }
                }
            } else false
        }
    }

    companion object {
        val REQUIRED_DIFFUSION_ONNX_FILES = listOf("text_encoder.onnx", "unet.onnx", "vae_decoder.onnx")
    }

    fun listLocalModels(): List<String> {
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir)
        return dirs.flatMap { dir ->
            dir.listFiles { f ->
                f.isDirectory && (File(f, ".complete").exists() || REQUIRED_DIFFUSION_ONNX_FILES.all { File(f, it).exists() })
            }?.map { it.name } ?: emptyList()
        }.distinct()
    }

    fun listDiffusionModels(): List<String> {
        return listLocalModels().filter { !it.startsWith("realesrgan") }
    }

    fun listRealESRGANModels(): List<String> {
        return listLocalModels().filter { it.startsWith("realesrgan") }
    }

    fun isRealESRGANAvailable(scale: Int): Boolean {
        val modelId = "realesrgan_x${scale}plus"
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir)
        return dirs.any { dir ->
            val modelDir = File(dir, modelId)
            modelDir.exists() && File(modelDir, "model.bin").exists() && File(modelDir, ".complete").exists()
        }
    }

    fun loadLocalManifest(modelId: String): ModelManifest? {
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir)
        for (dir in dirs) {
            val manifestFile = File(File(dir, modelId), "manifest.json")
            if (manifestFile.exists()) {
                try {
                    return gson.fromJson(manifestFile.readText(), ModelManifest::class.java)
                } catch (e: Exception) {
                    // Fall through
                }
            }
        }
        return when (modelId) {
            "sdturbo" -> ModelManifest.sdturbo()
            "realesrgan_x2plus" -> ModelManifest.realesrgan_x2plus()
            "realesrgan_x4plus" -> ModelManifest.realesrgan_x4plus()
            else -> null
        }
    }

    fun deleteModel(modelId: String): Boolean {
        var deleted = false
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir)
        for (dir in dirs) {
            val modelDir = File(dir, modelId)
            if (modelDir.exists()) {
                deleted = modelDir.deleteRecursively() || deleted
            }
        }
        return deleted
    }
}
