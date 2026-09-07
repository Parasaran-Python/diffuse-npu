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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelManager(
    private val baseStorageDir: File,
    private val secondaryStorageDir: File? = null,
    private val fallbackStorageDir: File? = null,
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

    @Volatile
    var isPaused: Boolean = false

    @Volatile
    private var activeCall: okhttp3.Call? = null

    fun pauseDownload() {
        isPaused = true
        try {
            activeCall?.cancel()
        } catch (_: Exception) {}
    }

    fun resetPause() {
        isPaused = false
    }

    fun resumeDownload(manifest: ModelManifest, baseUrl: String): Flow<DownloadStatus> {
        isPaused = false
        return downloadModel(manifest, baseUrl)
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
        var currentPartFile: File? = null
        var currentComponentName = ""

        fun cleanupIfIncomplete() {
            if (isPaused) {
                return
            }
            try {
                currentPartFile?.let { if (it.exists()) it.delete() }
                if (!completeFile.exists() && modelDir.exists()) {
                    val hasCompletedComponent = manifest.components.any { comp ->
                        val f = File(modelDir, comp.file)
                        f.exists() && f.length() > 0
                    }
                    if (!hasCompletedComponent) {
                        modelDir.deleteRecursively()
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }

            // Save manifest locally
            val manifestFile = File(modelDir, "manifest.json")
            manifestFile.writeText(gson.toJson(manifest))

            for (comp in manifest.components) {
                currentComponentName = comp.name
                val targetFile = File(modelDir, comp.file)
                val partFile = File(modelDir, "${comp.file}.part")
                currentPartFile = partFile

                if (isPaused) {
                    val currentBytes = if (partFile.exists()) partFile.length() else 0L
                    emit(DownloadStatus.Paused(manifest.modelId, comp.name, currentBytes, -1L, 0))
                    return@flow
                }

                val isSha256Provided = comp.sha256.isNotBlank() &&
                        comp.sha256.length == 64 &&
                        comp.sha256.all { it in "0123456789abcdefABCDEF" }

                // Check if component is already completely downloaded and valid
                if (targetFile.exists() && targetFile.length() > 0) {
                    if (isSha256Provided && ChecksumVerifier.verifyFile(targetFile, comp.sha256)) {
                        emit(DownloadStatus.DownloadingComponent(comp.name, targetFile.length(), targetFile.length(), 100))
                        continue
                    }
                }

                val candidateUrls = mutableListOf<String>()
                candidateUrls.add("$cleanBaseUrl${comp.file}")
                if (comp.file.endsWith(".onnx")) {
                    candidateUrls.add("$cleanBaseUrl${comp.name}/model.onnx")
                }
                if (!comp.file.endsWith(".bin")) {
                    candidateUrls.add("$cleanBaseUrl${comp.name}.bin")
                }

                var existingBytes = if (partFile.exists()) partFile.length() else 0L
                emit(DownloadStatus.DownloadingComponent(comp.name, existingBytes, -1L, 0))

                var response: okhttp3.Response? = null
                var successfulCandidateUrl = ""
                var lastError: Exception? = null

                for (candidateUrl in candidateUrls) {
                    if (isPaused) break

                    val requestBuilder = Request.Builder().url(candidateUrl)
                    if (existingBytes > 0L) {
                        requestBuilder.header("Range", "bytes=$existingBytes-")
                    }
                    val request = requestBuilder.build()
                    try {
                        val call = client.newCall(request)
                        activeCall = call
                        val candidateResponse = call.execute()
                        if (candidateResponse.code == 416 && existingBytes > 0L) {
                            // Range Not Satisfiable: delete .part and retry from byte 0
                            candidateResponse.close()
                            if (partFile.exists()) {
                                partFile.delete()
                            }
                            existingBytes = 0L
                            val retryRequest = Request.Builder().url(candidateUrl).build()
                            val retryCall = client.newCall(retryRequest)
                            activeCall = retryCall
                            val retryResponse = retryCall.execute()
                            if (retryResponse.isSuccessful) {
                                response = retryResponse
                                successfulCandidateUrl = candidateUrl
                                break
                            } else {
                                retryResponse.close()
                            }
                        } else if (candidateResponse.isSuccessful) {
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

                if (isPaused) {
                    response?.close()
                    activeCall = null
                    val currentBytes = if (partFile.exists()) partFile.length() else existingBytes
                    emit(DownloadStatus.Paused(manifest.modelId, comp.name, currentBytes, -1L, 0))
                    return@flow
                }

                if (response == null) {
                    cleanupIfIncomplete()
                    val err = lastError?.message ?: "File not found at candidate URLs"
                    emit(DownloadStatus.Failed("Failed downloading ${comp.name}: $err"))
                    return@flow
                }

                val responseBody = response.body ?: run {
                    response.close()
                    activeCall = null
                    cleanupIfIncomplete()
                    emit(DownloadStatus.Failed("Empty response body for ${comp.name}"))
                    return@flow
                }

                val isAppend = response.code == 206
                val resumeOffset = if (isAppend) partFile.length() else 0L
                val totalBytes = if (isAppend) {
                    if (responseBody.contentLength() >= 0) {
                        resumeOffset + responseBody.contentLength()
                    } else {
                        val rangeHeader = response.header("Content-Range")
                        val parsedTotal = rangeHeader?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        parsedTotal ?: -1L
                    }
                } else {
                    responseBody.contentLength()
                }

                // If file already exists and length matches content length, skip re-downloading
                if (!isSha256Provided && totalBytes > 0 && targetFile.exists() && targetFile.length() == totalBytes) {
                    response.close()
                    activeCall = null
                    emit(DownloadStatus.DownloadingComponent(comp.name, totalBytes, totalBytes, 100))
                    continue
                }

                var downloadedBytes = resumeOffset

                try {
                    response.use {
                        responseBody.byteStream().use { input ->
                            BufferedOutputStream(FileOutputStream(partFile, isAppend), 262144).use { output ->
                                val buffer = ByteArray(262144) // 256 KB buffer for high-throughput mobile flash I/O
                                var read: Int
                                var lastReportTime = System.currentTimeMillis()

                                if (downloadedBytes > 0) {
                                    val initialPercent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                    emit(DownloadStatus.DownloadingComponent(comp.name, downloadedBytes, totalBytes, initialPercent))
                                }

                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReportTime > 200 || downloadedBytes == totalBytes) {
                                        val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                        emit(DownloadStatus.DownloadingComponent(comp.name, downloadedBytes, totalBytes, percent))
                                        lastReportTime = now
                                    }
                                    if (isPaused) {
                                        break
                                    }
                                }
                                output.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    activeCall = null
                    if (isPaused) {
                        val currentBytes = if (partFile.exists()) partFile.length() else downloadedBytes
                        val percent = if (totalBytes > 0) ((currentBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                        emit(DownloadStatus.Paused(manifest.modelId, comp.name, currentBytes, totalBytes, percent))
                        return@flow
                    }
                    cleanupIfIncomplete()
                    emit(DownloadStatus.Failed("Failed reading ${comp.name}: ${e.message}"))
                    return@flow
                } finally {
                    activeCall = null
                }

                if (isPaused) {
                    val currentBytes = if (partFile.exists()) partFile.length() else downloadedBytes
                    val percent = if (totalBytes > 0) ((currentBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                    emit(DownloadStatus.Paused(manifest.modelId, comp.name, currentBytes, totalBytes, percent))
                    return@flow
                }

                if (isSha256Provided) {
                    emit(DownloadStatus.VerifyingChecksum(comp.name))
                    val valid = ChecksumVerifier.verifyFile(partFile, comp.sha256)
                    if (!valid) {
                        cleanupIfIncomplete()
                        emit(DownloadStatus.Failed("Checksum verification failed for ${comp.name}"))
                        return@flow
                    }
                }

                // Promote partFile to targetFile atomically
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = partFile.renameTo(targetFile)
                if (!renamed) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                currentPartFile = null
            }

            isPaused = false
            completeFile.createNewFile()
            emit(DownloadStatus.Completed(manifest.modelId, modelDir.absolutePath))
        } catch (e: CancellationException) {
            if (!isPaused) {
                cleanupIfIncomplete()
            }
            throw e
        } catch (e: Exception) {
            if (isPaused) {
                val currentBytes = currentPartFile?.let { if (it.exists()) it.length() else 0L } ?: 0L
                emit(DownloadStatus.Paused(manifest.modelId, currentComponentName, currentBytes, -1L, 0))
            } else {
                cleanupIfIncomplete()
                emit(DownloadStatus.Failed("Download failed: ${e.message}"))
            }
        }
    }.flowOn(Dispatchers.IO)

    fun isModelComplete(modelId: String, manifest: ModelManifest): Boolean {
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir)
        return dirs.any { dir ->
            val modelDir = File(dir, modelId)
            if (modelDir.exists() && modelDir.isDirectory && File(modelDir, ".complete").exists()) {
                manifest.components.all { comp ->
                    val file = File(modelDir, comp.file)
                    val nested = File(File(modelDir, comp.name), "model.onnx")
                    val isSha256Provided = comp.sha256.isNotBlank() &&
                            comp.sha256.length == 64 &&
                            comp.sha256.all { it in "0123456789abcdefABCDEF" }
                    if (isSha256Provided) {
                        ChecksumVerifier.verifyFile(file, comp.sha256) || ChecksumVerifier.verifyFile(nested, comp.sha256)
                    } else if (comp.sha256.isNotBlank() && !comp.sha256.startsWith(".")) {
                        ChecksumVerifier.verifyFile(file, comp.sha256) || ChecksumVerifier.verifyFile(nested, comp.sha256)
                    } else {
                        (file.exists() && file.length() > 0) || (nested.exists() && nested.length() > 0)
                    }
                }
            } else false
        }
    }

    companion object {
        val REQUIRED_DIFFUSION_ONNX_FILES = listOf("text_encoder.onnx", "unet.onnx", "vae_decoder.onnx")
    }

    fun listLocalModels(): List<String> {
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir, fallbackStorageDir).distinct()
        return dirs.flatMap { dir ->
            dir.listFiles { f ->
                f.isDirectory && (
                    File(f, ".complete").exists() ||
                    REQUIRED_DIFFUSION_ONNX_FILES.all { comp ->
                        val baseComp = comp.removeSuffix(".onnx")
                        File(f, comp).exists() || File(File(f, baseComp), "model.onnx").exists()
                    }
                )
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
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir, fallbackStorageDir).distinct()
        return dirs.any { dir ->
            val modelDir = File(dir, modelId)
            modelDir.exists() && File(modelDir, "model.bin").exists() && File(modelDir, ".complete").exists()
        }
    }

    fun loadLocalManifest(modelId: String): ModelManifest? {
        val dirs = listOfNotNull(baseStorageDir, secondaryStorageDir, fallbackStorageDir).distinct()
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
