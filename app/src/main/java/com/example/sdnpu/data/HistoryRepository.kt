package com.example.sdnpu.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class HistoryRepository(
    private val dao: GenerationDao,
    private val imagesDirProvider: () -> File = { File(System.getProperty("java.io.tmpdir"), "generations") },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _historyList = MutableStateFlow<List<GenerationEntity>>(emptyList())
    val historyList: StateFlow<List<GenerationEntity>> = _historyList.asStateFlow()

    init {
        try {
            _historyList.value = dao.getAll()
        } catch (_: Throwable) {
            _historyList.value = emptyList()
        }
    }

    suspend fun insert(entity: GenerationEntity): Long = withContext(ioDispatcher) {
        val id = dao.insert(entity)
        _historyList.value = dao.getAll()
        id
    }

    suspend fun insertRecord(entity: GenerationEntity): Long = insert(entity)

    suspend fun getAll(): List<GenerationEntity> = withContext(ioDispatcher) {
        dao.getAll()
    }

    suspend fun getById(id: Long): GenerationEntity? = withContext(ioDispatcher) {
        dao.getById(id)
    }

    suspend fun getRecordById(id: Long): GenerationEntity? = getById(id)

    suspend fun search(query: String): List<GenerationEntity> = withContext(ioDispatcher) {
        dao.search(query)
    }

    suspend fun searchHistory(query: String): List<GenerationEntity> = search(query)

    suspend fun delete(id: Long, deleteFile: Boolean = false): Boolean = withContext(ioDispatcher) {
        if (deleteFile) {
            dao.getById(id)?.imagePath?.let { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {}
            }
        }
        val success = dao.delete(id)
        if (success) {
            _historyList.value = dao.getAll()
        }
        success
    }

    suspend fun deleteRecord(id: Long, deleteFile: Boolean = false): Boolean = delete(id, deleteFile)

    suspend fun deleteBatch(ids: Set<Long>, deleteFiles: Boolean = false): Int = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext 0
        if (deleteFiles) {
            ids.forEach { id ->
                dao.getById(id)?.imagePath?.let { path ->
                    try {
                        File(path).delete()
                    } catch (_: Exception) {}
                }
            }
        }
        val count = dao.deleteBatch(ids)
        if (count > 0) {
            _historyList.value = dao.getAll()
        }
        count
    }

    suspend fun deleteRecords(ids: Set<Long>, deleteFiles: Boolean = false): Int = deleteBatch(ids, deleteFiles)

    suspend fun clearAll(deleteFiles: Boolean = false) = withContext(ioDispatcher) {
        if (deleteFiles) {
            dao.getAll().forEach { record ->
                try {
                    File(record.imagePath).delete()
                } catch (_: Exception) {}
            }
        }
        dao.clearAll()
        _historyList.value = emptyList()
    }

    suspend fun refresh() = withContext(ioDispatcher) {
        _historyList.value = dao.getAll()
    }

    suspend fun scanAndSyncFileSystem(): Int = withContext(ioDispatcher) {
        val dir = imagesDirProvider()
        if (!dir.exists() || !dir.isDirectory) return@withContext 0

        val existingPaths = dao.getAll().map { it.imagePath }.toSet()
        val pngFiles = dir.listFiles { file ->
            file.isFile && file.extension.equals("png", ignoreCase = true)
        } ?: return@withContext 0

        var importedCount = 0
        for (file in pngFiles) {
            if (file.absolutePath !in existingPaths) {
                val (width, height) = readPngDimensions(file)
                val upscaleMode = when {
                    file.name.contains("_x4", ignoreCase = true) -> 4
                    file.name.contains("_x2", ignoreCase = true) -> 2
                    else -> 0
                }
                val record = GenerationEntity(
                    prompt = "Imported generation (${file.nameWithoutExtension})",
                    negativePrompt = "",
                    modelName = "dreamshaper_v8",
                    imagePath = file.absolutePath,
                    seed = 0L,
                    steps = 20,
                    cfgScale = 7.0f,
                    sampler = 0,
                    upscaleMode = upscaleMode,
                    timestamp = file.lastModified(),
                    generationTimeMs = 0L,
                    width = width,
                    height = height,
                    fileSizeBytes = file.length()
                )
                dao.insert(record)
                importedCount++
            }
        }

        if (importedCount > 0) {
            _historyList.value = dao.getAll()
        }
        return@withContext importedCount
    }

    private fun readPngDimensions(file: File): Pair<Int, Int> {
        try {
            file.inputStream().use { stream ->
                val header = ByteArray(24)
                val read = stream.read(header)
                if (read >= 24) {
                    if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                        header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
                    ) {
                        val width = ByteBuffer.wrap(header, 16, 4).int
                        val height = ByteBuffer.wrap(header, 20, 4).int
                        if (width > 0 && height > 0) {
                            return Pair(width, height)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return Pair(512, 512)
    }

    companion object {
        @Volatile
        private var INSTANCE: HistoryRepository? = null

        fun getInstance(context: Context): HistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HistoryRepository(
                    dao = SQLiteGenerationDao(context.applicationContext),
                    imagesDirProvider = { File(context.applicationContext.filesDir, "generations") }
                ).also { INSTANCE = it }
            }
        }
    }
}
