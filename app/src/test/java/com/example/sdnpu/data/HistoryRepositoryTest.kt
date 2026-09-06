package com.example.sdnpu.data

import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineManager
import com.example.sdnpu.pipeline.PipelineState
import com.example.sdnpu.pipeline.UpscaleMode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

class HistoryRepositoryTest {

    private class InMemoryGenerationDao : GenerationDao {
        private val records = mutableListOf<GenerationEntity>()
        private var nextId = 1L

        override fun insert(entity: GenerationEntity): Long {
            val saved = entity.copy(id = nextId++)
            records.add(0, saved)
            return saved.id
        }

        override fun getAll(): List<GenerationEntity> = records.toList()

        override fun getById(id: Long): GenerationEntity? = records.find { it.id == id }

        override fun search(query: String): List<GenerationEntity> =
            records.filter {
                it.prompt.contains(query, ignoreCase = true) ||
                it.negativePrompt.contains(query, ignoreCase = true)
            }

        override fun delete(id: Long): Boolean = records.removeAll { it.id == id }

        override fun deleteBatch(ids: Set<Long>): Int {
            val initial = records.size
            records.removeAll { it.id in ids }
            return initial - records.size
        }

        override fun clearAll() = records.clear()
    }

    private lateinit var repository: HistoryRepository
    private lateinit var dao: InMemoryGenerationDao
    private lateinit var testImagesDir: File

    @Before
    fun setUp() {
        dao = InMemoryGenerationDao()
        testImagesDir = File(System.getProperty("java.io.tmpdir"), "test-gens-${System.currentTimeMillis()}")
        testImagesDir.mkdirs()
        repository = HistoryRepository(dao = dao, imagesDirProvider = { testImagesDir })
    }

    @After
    fun tearDown() {
        testImagesDir.deleteRecursively()
    }

    @Test
    fun testInsertAndRetrieveRecord() = runBlocking {
        val record = GenerationEntity(
            id = 0,
            prompt = "cyberpunk city, neon lights",
            negativePrompt = "blurry, low quality",
            modelName = "dreamshaper_v8",
            imagePath = "/tmp/test-gens/gen_1.png",
            seed = 42L,
            steps = 20,
            cfgScale = 7.5f,
            sampler = 0,
            upscaleMode = 2,
            timestamp = System.currentTimeMillis(),
            generationTimeMs = 4500L,
            width = 1024,
            height = 1024,
            fileSizeBytes = 1048576L
        )
        val id = repository.insert(record)
        assertTrue(id > 0)

        val list = repository.historyList.value
        assertEquals(1, list.size)
        assertEquals("cyberpunk city, neon lights", list[0].prompt)
        assertEquals(1024, list[0].width)
        assertEquals(2, list[0].upscaleMode)
    }

    @Test
    fun testSearchHistory() = runBlocking {
        repository.insert(GenerationEntity(id = 0, prompt = "apple on table", imagePath = "/tmp/1.png"))
        repository.insert(GenerationEntity(id = 0, prompt = "banana in basket", imagePath = "/tmp/2.png"))
        repository.insert(GenerationEntity(id = 0, prompt = "red apple watercolor", imagePath = "/tmp/3.png"))

        val results = repository.search("apple")
        assertEquals(2, results.size)
        assertTrue(results.all { it.prompt.contains("apple", ignoreCase = true) })
    }

    @Test
    fun testDeleteBatch() = runBlocking {
        val id1 = repository.insert(GenerationEntity(id = 0, prompt = "item 1", imagePath = "/tmp/1.png"))
        val id2 = repository.insert(GenerationEntity(id = 0, prompt = "item 2", imagePath = "/tmp/2.png"))
        val id3 = repository.insert(GenerationEntity(id = 0, prompt = "item 3", imagePath = "/tmp/3.png"))

        val deleted = repository.deleteBatch(setOf(id1, id3))
        assertEquals(2, deleted)
        val remaining = repository.historyList.value
        assertEquals(1, remaining.size)
        assertEquals(id2, remaining[0].id)
    }

    @Test
    fun testGetRecordByIdAndSingleDelete() = runBlocking {
        val id1 = repository.insert(GenerationEntity(id = 0, prompt = "sunset over ocean", imagePath = "/tmp/sunset.png"))
        val fetched = repository.getRecordById(id1)
        assertNotNull(fetched)
        assertEquals("sunset over ocean", fetched?.prompt)

        val deleted = repository.delete(id1)
        assertTrue(deleted)
        assertNull(repository.getRecordById(id1))
        assertEquals(0, repository.historyList.value.size)
    }

    @Test
    fun testClearAllRecords() = runBlocking {
        repository.insert(GenerationEntity(id = 0, prompt = "test 1", imagePath = "/tmp/1.png"))
        repository.insert(GenerationEntity(id = 0, prompt = "test 2", imagePath = "/tmp/2.png"))
        assertEquals(2, repository.historyList.value.size)

        repository.clearAll()
        assertEquals(0, repository.historyList.value.size)
    }

    @Test
    fun testDeleteWithPhysicalFileCleanup() = runBlocking {
        val dummyFile = File(testImagesDir, "test_delete.png")
        dummyFile.writeText("dummy png content")
        assertTrue(dummyFile.exists())

        val id = repository.insert(GenerationEntity(id = 0, prompt = "to delete", imagePath = dummyFile.absolutePath))
        val deleted = repository.delete(id, deleteFile = true)
        assertTrue(deleted)
        assertFalse("Physical file should be deleted", dummyFile.exists())
    }

    @Test
    fun testScanAndSyncFileSystemDiscoversUnindexedPngs() = runBlocking {
        // Create mock PNG files in testImagesDir
        val png1 = File(testImagesDir, "sd_1725612345678_0.png")
        writeMinimalPng(png1, 512, 512)

        val png2 = File(testImagesDir, "sd_1725612345679_0_x2.png")
        writeMinimalPng(png2, 1024, 1024)

        // Non-PNG file should be ignored
        val txt = File(testImagesDir, "notes.txt")
        txt.writeText("some notes")

        val newRecordsCount = repository.scanAndSyncFileSystem()
        assertEquals(2, newRecordsCount)

        val list = repository.historyList.value
        assertEquals(2, list.size)

        val unscaled = list.find { it.imagePath == png1.absolutePath }
        assertNotNull(unscaled)
        assertEquals(512, unscaled?.width)
        assertEquals(512, unscaled?.height)
        assertEquals(0, unscaled?.upscaleMode)

        val upscaled = list.find { it.imagePath == png2.absolutePath }
        assertNotNull(upscaled)
        assertEquals(1024, upscaled?.width)
        assertEquals(1024, upscaled?.height)
        assertEquals(2, upscaled?.upscaleMode)

        // Calling scanAndSyncFileSystem again should not duplicate records
        val rescannedCount = repository.scanAndSyncFileSystem()
        assertEquals(0, rescannedCount)
        assertEquals(2, repository.historyList.value.size)
    }

    @Test
    fun testPipelineManagerIntegrationWithHistoryRepository(): Unit = runBlocking {
        val testModelsDir = File(System.getProperty("java.io.tmpdir"), "test-models-${System.currentTimeMillis()}")
        testModelsDir.mkdirs()
        com.example.sdnpu.TestModelFixtures.stageModel(testModelsDir, "dreamshaper_v8_base")
        com.example.sdnpu.engine.OnnxDiffusionEngine.testSimulationEnabled = true

        try {
            val pipeline = PipelineManager(
                modelsDir = testModelsDir,
                outputDir = testImagesDir,
                historyRepository = repository
            )

            val params = GenerationParams(
                prompt = "a cute golden retriever puppy",
                steps = 10,
                upscaleMode = UpscaleMode.OFF
            )

            val states = pipeline.runGeneration(params).toList()
            val completed = states.filterIsInstance<PipelineState.Completed>()
            assertEquals(1, completed.size)

            // Verify that record was inserted into repository
            val history = repository.historyList.value
            assertEquals(1, history.size)
            assertEquals("a cute golden retriever puppy", history[0].prompt)
            assertEquals(512, history[0].width)
            assertEquals(512, history[0].height)
            assertTrue(File(history[0].imagePath).exists())
        } finally {
            com.example.sdnpu.engine.OnnxDiffusionEngine.testSimulationEnabled = false
            testModelsDir.deleteRecursively()
        }
    }

    private fun writeMinimalPng(file: File, width: Int, height: Int) {
        val rawScanlines = ByteArray(height * (1 + width * 4))
        val deflater = Deflater()
        deflater.setInput(rawScanlines)
        deflater.finish()
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            baos.write(buf, 0, count)
        }
        deflater.end()
        val idatData = baos.toByteArray()

        FileOutputStream(file).use { fos ->
            val dos = DataOutputStream(fos)
            dos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

            // IHDR
            val ihdr = ByteArrayOutputStream()
            val idos = DataOutputStream(ihdr)
            idos.writeInt(width)
            idos.writeInt(height)
            idos.writeByte(8)
            idos.writeByte(6)
            idos.writeByte(0)
            idos.writeByte(0)
            idos.writeByte(0)
            val ihdrBytes = ihdr.toByteArray()

            writeChunk(dos, "IHDR", ihdrBytes)
            writeChunk(dos, "IDAT", idatData)
            writeChunk(dos, "IEND", ByteArray(0))
        }
    }

    private fun writeChunk(dos: DataOutputStream, type: String, data: ByteArray) {
        dos.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
    }
}
