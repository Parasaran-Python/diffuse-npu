package com.example.sdnpu.ui

import com.example.sdnpu.data.AppSettings
import com.example.sdnpu.data.GenerationDao
import com.example.sdnpu.data.GenerationEntity
import com.example.sdnpu.data.HistoryRepository
import com.example.sdnpu.data.SettingsRepository
import com.example.sdnpu.model.ModelManager
import com.example.sdnpu.model.ModelVariants
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineManager
import com.example.sdnpu.pipeline.UpscaleMode
import com.example.sdnpu.system.DeviceMonitor
import com.example.sdnpu.system.GenerationNotificationManager
import com.example.sdnpu.system.ThermalStatus
import com.example.sdnpu.pipeline.SamplerType
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

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

    private lateinit var testDir: File
    private lateinit var historyRepository: HistoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var deviceMonitor: DeviceMonitor
    private lateinit var notificationManager: GenerationNotificationManager
    private lateinit var modelManager: ModelManager
    private lateinit var pipelineManager: PipelineManager
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testDir = File(System.getProperty("java.io.tmpdir"), "test-vm-${System.currentTimeMillis()}")
        testDir.mkdirs()

        val modelsDir = File(testDir, "models").apply { mkdirs() }
        val generationsDir = File(testDir, "generations").apply { mkdirs() }

        val dao = InMemoryGenerationDao()
        historyRepository = HistoryRepository(dao = dao, imagesDirProvider = { generationsDir })
        val testDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { File(testDir, "test_settings.preferences_pb") }
        )
        settingsRepository = SettingsRepository(testDataStore)
        deviceMonitor = DeviceMonitor()
        notificationManager = GenerationNotificationManager()
        modelManager = ModelManager(modelsDir)
        pipelineManager = PipelineManager(modelsDir, generationsDir, historyRepository)

        viewModel = MainViewModel(
            modelManager = modelManager,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            deviceMonitor = deviceMonitor,
            notificationManager = notificationManager,
            pipelineManager = pipelineManager
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            viewModel.viewModelScope.coroutineContext[Job]?.children?.forEach { it.cancelAndJoin() }
        }
        Dispatchers.resetMain()
        testDir.deleteRecursively()
    }

    @Test
    fun testInitialParamsAndSettings() = runBlocking {
        val params = viewModel.params.value
        assertNotNull(params)
        assertEquals("sdturbo", params.modelId)
        assertEquals(1, params.steps)
        assertEquals(1.0f, params.cfgScale, 0.001f)

        val settings = viewModel.appSettings.value
        assertEquals("NPU", settings.backendPreference)
        assertTrue(settings.thermalWarningEnabled)
    }

    @Test
    fun testUpdateParams() {
        val updated = GenerationParams(
            prompt = "A majestic mountain landscape",
            steps = 30,
            cfgScale = 8.5f,
            upscaleMode = UpscaleMode.X2
        )
        viewModel.updateParams(updated)
        assertEquals("A majestic mountain landscape", viewModel.params.value.prompt)
        assertEquals(30, viewModel.params.value.steps)
        assertEquals(8.5f, viewModel.params.value.cfgScale, 0.001f)
        assertEquals(UpscaleMode.X2, viewModel.params.value.upscaleMode)
    }

    @Test
    fun testPopulateParamsFromHistory() = runBlocking {
        val record = GenerationEntity(
            id = 1L,
            prompt = "neon cyber city",
            negativePrompt = "blurry",
            modelName = "dreamshaper_v8_anime",
            imagePath = "/tmp/image.png",
            seed = 12345L,
            steps = 25,
            cfgScale = 9.0f,
            sampler = 1,
            upscaleMode = 2
        )

        viewModel.populateParamsFromHistory(record)

        val currentParams = viewModel.params.value
        assertEquals("neon cyber city", currentParams.prompt)
        assertEquals("blurry", currentParams.negativePrompt)
        assertEquals(12345L, currentParams.seed)
        assertEquals(25, currentParams.steps)
        assertEquals(9.0f, currentParams.cfgScale, 0.001f)
        assertEquals(SamplerType.DPM_2M_KARRAS, currentParams.sampler)
        assertEquals(UpscaleMode.X2, currentParams.upscaleMode)
    }

    @Test
    fun testHistorySearchAndFiltering() = runBlocking {
        historyRepository.insert(GenerationEntity(prompt = "cyberpunk street", imagePath = "/tmp/1.png"))
        historyRepository.insert(GenerationEntity(prompt = "peaceful forest lake", imagePath = "/tmp/2.png"))
        historyRepository.insert(GenerationEntity(prompt = "futuristic cyberpunk vehicle", imagePath = "/tmp/3.png"))

        assertEquals(3, viewModel.historyList.value.size)
        assertEquals(3, viewModel.filteredHistory.value.size)

        viewModel.setHistoryQuery("cyberpunk")
        assertEquals(2, viewModel.filteredHistory.value.size)
        assertTrue(viewModel.filteredHistory.value.all { it.prompt.contains("cyberpunk") })

        viewModel.setHistoryQuery("")
        assertEquals(3, viewModel.filteredHistory.value.size)
    }

    @Test
    fun testHistoryMultiSelectionAndBatchDelete() = runBlocking {
        val id1 = historyRepository.insert(GenerationEntity(prompt = "img 1", imagePath = "/tmp/1.png"))
        val id2 = historyRepository.insert(GenerationEntity(prompt = "img 2", imagePath = "/tmp/2.png"))
        val id3 = historyRepository.insert(GenerationEntity(prompt = "img 3", imagePath = "/tmp/3.png"))

        assertFalse(viewModel.isSelectionMode.value)
        assertEquals(0, viewModel.selectedHistoryIds.value.size)

        viewModel.toggleHistorySelection(id1)
        assertTrue(viewModel.isSelectionMode.value)
        assertEquals(setOf(id1), viewModel.selectedHistoryIds.value)

        viewModel.toggleHistorySelection(id2)
        assertEquals(setOf(id1, id2), viewModel.selectedHistoryIds.value)

        viewModel.selectAllHistory()
        assertEquals(setOf(id1, id2, id3), viewModel.selectedHistoryIds.value)

        viewModel.deleteSelectedHistorySuspend(deleteFiles = false)
        assertEquals(0, viewModel.historyList.value.size)
        assertFalse(viewModel.isSelectionMode.value)
    }

    @Test
    fun testSettingsUpdates() = runBlocking {
        settingsRepository.updateSteps(35)
        settingsRepository.updateCfgScale(10.5f)
        settingsRepository.updateSampler(2)
        settingsRepository.updateBatchCount(3)
        settingsRepository.updateUpscaleMode(1)
        settingsRepository.updateBackend("GPU")
        settingsRepository.updateThermalWarning(false)
        settingsRepository.updateHighPerformanceMode(true)
        settingsRepository.updateDarkTheme("LIGHT")

        val settings = settingsRepository.getSettings()
        assertEquals(35, settings.defaultSteps)
        assertEquals(10.5f, settings.defaultCfgScale, 0.001f)
        assertEquals(2, settings.defaultSampler)
        assertEquals(3, settings.defaultBatchCount)
        assertEquals(1, settings.defaultUpscaleMode)
        assertEquals("GPU", settings.backendPreference)
        assertFalse(settings.thermalWarningEnabled)
        assertTrue(settings.highPerformanceMode)
        assertEquals("LIGHT", settings.darkThemeMode)
    }

    @Test
    fun testModelVariants() {
        val variants = ModelVariants.getSdVariants()
        assertEquals(4, variants.size)
        assertTrue(ModelVariants.isSdModel("dreamshaper_v8_base"))
        assertTrue(ModelVariants.isSdModel("dreamshaper_v8_anime"))
        assertTrue(ModelVariants.isSdModel("sdturbo"))
        assertFalse(ModelVariants.isSdModel("realesrgan_x2plus"))
    }

    @Test
    fun testDownloadModelFromUrlFallbackWhenManifestMissing() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            // Manifest request -> 404 Not Found
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            // Component requests -> 200 OK
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("component 1"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("component 2"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("component 3"))

            val baseUrl = server.url("/").toString()
            viewModel.downloadModelFromUrl(baseUrl, modelId = "sdturbo")

            val status = viewModel.downloadStatus.filter { it !is com.example.sdnpu.model.DownloadStatus.Idle }.first()
            assertTrue(status !is com.example.sdnpu.model.DownloadStatus.Idle)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testPauseAndResumeDownloadInProcess() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("bytes"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("bytes2"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("bytes3"))

            val baseUrl = server.url("/").toString()
            viewModel.downloadModelFromUrl(baseUrl, modelId = "sdturbo")

            val downloading = viewModel.downloadStatus.filter { it is com.example.sdnpu.model.DownloadStatus.DownloadingComponent }.first()
            assertNotNull(downloading)

            // Test pauseDownload()
            viewModel.pauseDownload()
            val paused = viewModel.downloadStatus.filter { it is com.example.sdnpu.model.DownloadStatus.Paused }.first()
            assertTrue(paused is com.example.sdnpu.model.DownloadStatus.Paused)
            assertTrue(modelManager.isPaused)

            // Test resumeDownload()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("resume_bytes"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("resume_bytes2"))
            viewModel.resumeDownload()
            assertFalse(modelManager.isPaused)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCancelDownloadInProcess() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("bytes"))

            val baseUrl = server.url("/").toString()
            viewModel.downloadModelFromUrl(baseUrl, modelId = "sdturbo")

            viewModel.cancelDownload()
            assertEquals(com.example.sdnpu.model.DownloadStatus.Idle, viewModel.downloadStatus.value)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testMainViewModelObservesModelDownloadServiceWhenApplicationPresent() = runBlocking {
        val dummyApp = android.app.Application()
        val vmWithApp = MainViewModel(
            application = dummyApp,
            modelManager = modelManager,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            deviceMonitor = deviceMonitor,
            notificationManager = notificationManager,
            pipelineManager = pipelineManager
        )

        try {
            val testStatus = com.example.sdnpu.model.DownloadStatus.DownloadingComponent(
                componentName = "unet.onnx",
                bytesRead = 500L,
                totalBytes = 1000L,
                progressPercent = 50
            )
            com.example.sdnpu.service.ModelDownloadService.downloadStatus.value = testStatus

            val observed = vmWithApp.downloadStatus.filter { it is com.example.sdnpu.model.DownloadStatus.DownloadingComponent }.first()
            assertEquals(testStatus, observed)
        } finally {
            com.example.sdnpu.service.ModelDownloadService.downloadStatus.value = com.example.sdnpu.model.DownloadStatus.Idle
            vmWithApp.viewModelScope.coroutineContext[Job]?.children?.forEach { it.cancelAndJoin() }
        }
    }

    @Test
    fun testModelDownloadServiceConstants() {
        assertEquals("com.example.sdnpu.service.ACTION_START", com.example.sdnpu.service.ModelDownloadService.ACTION_START)
        assertEquals("com.example.sdnpu.service.ACTION_PAUSE", com.example.sdnpu.service.ModelDownloadService.ACTION_PAUSE)
        assertEquals("com.example.sdnpu.service.ACTION_RESUME", com.example.sdnpu.service.ModelDownloadService.ACTION_RESUME)
        assertEquals("com.example.sdnpu.service.ACTION_CANCEL", com.example.sdnpu.service.ModelDownloadService.ACTION_CANCEL)
        assertEquals("com.example.sdnpu.service.EXTRA_URL", com.example.sdnpu.service.ModelDownloadService.EXTRA_URL)
        assertEquals("com.example.sdnpu.service.EXTRA_MODEL_ID", com.example.sdnpu.service.ModelDownloadService.EXTRA_MODEL_ID)
        assertEquals("sd_npu_downloads", com.example.sdnpu.service.ModelDownloadService.CHANNEL_ID)
    }
}
