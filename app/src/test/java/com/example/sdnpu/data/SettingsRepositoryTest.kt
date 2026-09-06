package com.example.sdnpu.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: CoroutineScope
    private lateinit var testFile: File
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.IO + Job())
        testFile = tempFolder.newFile("test_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { testFile }
        )
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun testDefaultAppSettings() {
        val settings = AppSettings()
        assertEquals(20, settings.defaultSteps)
        assertEquals(7.0f, settings.defaultCfgScale, 0.001f)
        assertEquals(0, settings.defaultSampler)
        assertEquals(1, settings.defaultBatchCount)
        assertEquals(0, settings.defaultUpscaleMode)
        assertEquals("NPU", settings.backendPreference)
        assertTrue(settings.thermalWarningEnabled)
        assertFalse(settings.highPerformanceMode)
        assertEquals("DARK", settings.darkThemeMode)
    }

    @Test
    fun testSettingsSanitization() {
        val clamped = AppSettings(
            defaultSteps = 100, // should clamp to 10..50
            defaultCfgScale = 50.0f, // should clamp to 1.0..20.0
            defaultBatchCount = 10 // should clamp to 1..4
        ).sanitized()

        assertEquals(50, clamped.defaultSteps)
        assertEquals(20.0f, clamped.defaultCfgScale, 0.001f)
        assertEquals(4, clamped.defaultBatchCount)
    }

    @Test
    fun testSettingsSanitizationLowerAndCategoricalBounds() {
        val clamped = AppSettings(
            defaultSteps = 5,
            defaultCfgScale = 0.5f,
            defaultSampler = -1,
            defaultBatchCount = -2,
            defaultUpscaleMode = 5,
            backendPreference = "INVALID",
            darkThemeMode = "BLUE"
        ).sanitized()

        assertEquals(10, clamped.defaultSteps)
        assertEquals(1.0f, clamped.defaultCfgScale, 0.001f)
        assertEquals(0, clamped.defaultSampler)
        assertEquals(1, clamped.defaultBatchCount)
        assertEquals(2, clamped.defaultUpscaleMode)
        assertEquals("NPU", clamped.backendPreference)
        assertEquals("DARK", clamped.darkThemeMode)
    }

    @Test
    fun testRepositoryDefaultValues() = runBlocking {
        val initial = repository.settingsFlow.first()
        assertEquals(20, initial.defaultSteps)
        assertEquals(7.0f, initial.defaultCfgScale, 0.001f)
        assertEquals(0, initial.defaultSampler)
        assertEquals(1, initial.defaultBatchCount)
        assertEquals(0, initial.defaultUpscaleMode)
        assertEquals("NPU", initial.backendPreference)
        assertTrue(initial.thermalWarningEnabled)
        assertFalse(initial.highPerformanceMode)
        assertEquals("DARK", initial.darkThemeMode)
    }

    @Test
    fun testUpdateSteps() = runBlocking {
        repository.updateSteps(35)
        assertEquals(35, repository.settingsFlow.first().defaultSteps)

        // Clamped bounds
        repository.updateSteps(100)
        assertEquals(50, repository.settingsFlow.first().defaultSteps)

        repository.updateSteps(1)
        assertEquals(10, repository.settingsFlow.first().defaultSteps)
    }

    @Test
    fun testUpdateCfgScale() = runBlocking {
        repository.updateCfgScale(12.5f)
        assertEquals(12.5f, repository.settingsFlow.first().defaultCfgScale, 0.001f)

        repository.updateCfgScale(30.0f)
        assertEquals(20.0f, repository.settingsFlow.first().defaultCfgScale, 0.001f)

        repository.updateCfgScale(0.1f)
        assertEquals(1.0f, repository.settingsFlow.first().defaultCfgScale, 0.001f)
    }

    @Test
    fun testUpdateSampler() = runBlocking {
        repository.updateSampler(2)
        assertEquals(2, repository.settingsFlow.first().defaultSampler)

        repository.updateSampler(99)
        assertEquals(3, repository.settingsFlow.first().defaultSampler)

        repository.updateSampler(-5)
        assertEquals(0, repository.settingsFlow.first().defaultSampler)
    }

    @Test
    fun testUpdateBatchCount() = runBlocking {
        repository.updateBatchCount(3)
        assertEquals(3, repository.settingsFlow.first().defaultBatchCount)

        repository.updateBatchCount(10)
        assertEquals(4, repository.settingsFlow.first().defaultBatchCount)

        repository.updateBatchCount(0)
        assertEquals(1, repository.settingsFlow.first().defaultBatchCount)
    }

    @Test
    fun testUpdateUpscaleMode() = runBlocking {
        repository.updateUpscaleMode(2)
        assertEquals(2, repository.settingsFlow.first().defaultUpscaleMode)

        repository.updateUpscaleMode(5)
        assertEquals(2, repository.settingsFlow.first().defaultUpscaleMode)

        repository.updateUpscaleMode(-1)
        assertEquals(0, repository.settingsFlow.first().defaultUpscaleMode)
    }

    @Test
    fun testUpdateBackend() = runBlocking {
        repository.updateBackend("GPU")
        assertEquals("GPU", repository.settingsFlow.first().backendPreference)

        repository.updateBackend("CPU")
        assertEquals("CPU", repository.settingsFlow.first().backendPreference)

        repository.updateBackend("UNKNOWN")
        assertEquals("NPU", repository.settingsFlow.first().backendPreference)
    }

    @Test
    fun testUpdateThermalWarning() = runBlocking {
        repository.updateThermalWarning(false)
        assertFalse(repository.settingsFlow.first().thermalWarningEnabled)

        repository.updateThermalWarning(true)
        assertTrue(repository.settingsFlow.first().thermalWarningEnabled)
    }

    @Test
    fun testUpdateHighPerformanceMode() = runBlocking {
        repository.updateHighPerformanceMode(true)
        assertTrue(repository.settingsFlow.first().highPerformanceMode)

        repository.updateHighPerformanceMode(false)
        assertFalse(repository.settingsFlow.first().highPerformanceMode)
    }

    @Test
    fun testUpdateDarkTheme() = runBlocking {
        repository.updateDarkTheme("LIGHT")
        assertEquals("LIGHT", repository.settingsFlow.first().darkThemeMode)

        repository.updateDarkTheme("SYSTEM")
        assertEquals("SYSTEM", repository.settingsFlow.first().darkThemeMode)

        repository.updateDarkTheme("INVALID_THEME")
        assertEquals("DARK", repository.settingsFlow.first().darkThemeMode)
    }
}
