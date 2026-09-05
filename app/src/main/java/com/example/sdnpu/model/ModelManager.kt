package com.example.sdnpu.model

import com.google.gson.Gson
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    init {
        if (!baseStorageDir.exists()) {
            baseStorageDir.mkdirs()
        }
    }

    suspend fun fetchManifest(baseUrl: String): Result<ModelManifest> = withContext(Dispatchers.IO) {
        val url = if (baseUrl.endsWith("/")) "${baseUrl}manifest.json" else "$baseUrl/manifest.json"
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
        val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val modelDir = File(baseStorageDir, manifest.modelId)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        // Save manifest locally
        val manifestFile = File(modelDir, "manifest.json")
        manifestFile.writeText(gson.toJson(manifest))

        for (comp in manifest.components) {
            val targetFile = File(modelDir, comp.file)
            val compUrl = "$cleanBaseUrl${comp.file}"

            emit(DownloadStatus.DownloadingComponent(comp.name, 0L, -1L, 0))

            val request = Request.Builder().url(compUrl).build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                emit(DownloadStatus.Failed("Failed downloading ${comp.name}: ${e.message}"))
                return@flow
            }

            if (!response.isSuccessful) {
                response.close()
                emit(DownloadStatus.Failed("Failed downloading ${comp.name}: HTTP ${response.code}"))
                return@flow
            }

            val responseBody = response.body ?: run {
                response.close()
                emit(DownloadStatus.Failed("Empty response body for ${comp.name}"))
                return@flow
            }

            val totalBytes = responseBody.contentLength()
            var downloadedBytes = 0L

            try {
                response.use {
                    responseBody.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(16384)
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
                targetFile.delete()
                emit(DownloadStatus.Failed("Failed reading ${comp.name}: ${e.message}"))
                return@flow
            }

            emit(DownloadStatus.VerifyingChecksum(comp.name))
            val valid = ChecksumVerifier.verifyFile(targetFile, comp.sha256)
            if (!valid) {
                targetFile.delete()
                emit(DownloadStatus.Failed("Checksum verification failed for ${comp.name}"))
                return@flow
            }
        }

        emit(DownloadStatus.Completed(manifest.modelId, modelDir.absolutePath))
    }.flowOn(Dispatchers.IO)

    fun isModelComplete(modelId: String, manifest: ModelManifest): Boolean {
        val modelDir = File(baseStorageDir, modelId)
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        for (comp in manifest.components) {
            val file = File(modelDir, comp.file)
            if (!ChecksumVerifier.verifyFile(file, comp.sha256)) {
                return false
            }
        }
        return true
    }

    fun listLocalModels(): List<String> {
        return baseStorageDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
    }

    fun deleteModel(modelId: String): Boolean {
        val modelDir = File(baseStorageDir, modelId)
        return if (modelDir.exists()) modelDir.deleteRecursively() else false
    }
}
