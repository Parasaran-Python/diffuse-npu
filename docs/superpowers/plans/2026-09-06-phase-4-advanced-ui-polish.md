# Phase 4: Advanced UI & Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build comprehensive generation history persistence with SQLite database, Jetpack DataStore user settings, thermal and battery health monitoring, background notifications, multi-variant model selection, and an advanced Material 3 gallery and settings UI.

**Architecture:** A clean-architecture Android application stack with an indexed SQLite persistence layer (`HistoryRepository` / `GenerationDao`), a reactive `DataStore` preferences manager (`SettingsRepository`), hardware health observers (`DeviceMonitor` for thermal and battery states), and rich Jetpack Compose UI screens (`GenerateScreen`, `GalleryScreen`, `SettingsScreen`) backed by `MainViewModel`.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose Material 3, AndroidX DataStore Preferences 1.1.1, Android SQLite (`android.database.sqlite`), Kotlin Coroutines & Flow, AndroidX Core / Notifications, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-05-stable-diffusion-npu-android-design.md`

## Global Constraints

- Android API: `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`.
- Database: Built-in Android SQLite via `SQLiteOpenHelper` with transactional integrity and indexed queries; DAO interface with in-memory test implementation for 100% JVM unit test pass without Android stub failures.
- Preferences: `androidx.datastore:datastore-preferences:1.1.1`.
- Clean compilation across both `arm64-v8a` and `x86_64` ABIs.
- Zero crashes, no main-thread UI jank during image loading or database queries (all DB/IO work on `Dispatchers.IO`).
- All existing 67 unit tests must continue to pass with 100% success rate.

---

### Task 1: History Database & Generation Records (`GenerationEntity`, `GenerationDao`, `HistoryRepository`)

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/data/GenerationEntity.kt`
- Create: `app/src/main/java/com/example/sdnpu/data/GenerationDao.kt`
- Create: `app/src/main/java/com/example/sdnpu/data/SQLiteGenerationDao.kt`
- Create: `app/src/main/java/com/example/sdnpu/data/HistoryRepository.kt`
- Create: `app/src/test/java/com/example/sdnpu/data/HistoryRepositoryTest.kt`
- Modify: `app/src/main/java/com/example/sdnpu/pipeline/PipelineManager.kt`

**Interfaces:**
- Consumes: `GenerationParams`, `File` outputs from `PipelineManager.generateImage()`.
- Produces: `HistoryRepository` exposing `historyList: StateFlow<List<GenerationEntity>>`, `searchHistory(query: String)`, `insertRecord(...)`, `deleteRecord(id: Long)`, `deleteRecords(ids: Set<Long>)`, `getRecordById(id: Long)`.

- [ ] **Step 1: Write failing test in `HistoryRepositoryTest.kt`**

```kotlin
package com.example.sdnpu.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

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
            records.filter { it.prompt.contains(query, ignoreCase = true) || it.negativePrompt.contains(query, ignoreCase = true) }

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

    @Before
    fun setUp() {
        dao = InMemoryGenerationDao()
        repository = HistoryRepository(dao = dao, imagesDirProvider = { File("/tmp/test-gens") })
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.data.HistoryRepositoryTest"`
Expected: FAIL (unresolved references `GenerationEntity`, `GenerationDao`, `HistoryRepository`).

- [ ] **Step 3: Implement `GenerationEntity`, `GenerationDao`, `SQLiteGenerationDao`, and `HistoryRepository`**

1. Create `GenerationEntity.kt`:
```kotlin
package com.example.sdnpu.data

data class GenerationEntity(
    val id: Long = 0,
    val prompt: String,
    val negativePrompt: String = "",
    val modelName: String = "dreamshaper_v8",
    val imagePath: String,
    val seed: Long = 0L,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val sampler: Int = 0,
    val upscaleMode: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val generationTimeMs: Long = 0L,
    val width: Int = 512,
    val height: Int = 512,
    val fileSizeBytes: Long = 0L
)
```

2. Create `GenerationDao.kt`:
```kotlin
package com.example.sdnpu.data

interface GenerationDao {
    fun insert(entity: GenerationEntity): Long
    fun getAll(): List<GenerationEntity>
    fun getById(id: Long): GenerationEntity?
    fun search(query: String): List<GenerationEntity>
    fun delete(id: Long): Boolean
    fun deleteBatch(ids: Set<Long>): Int
    fun clearAll()
}
```

3. Create `SQLiteGenerationDao.kt` using `android.database.sqlite.SQLiteOpenHelper`:
- Table `generations` (`id INTEGER PRIMARY KEY AUTOINCREMENT, prompt TEXT, negative_prompt TEXT, model_name TEXT, image_path TEXT, seed INTEGER, steps INTEGER, cfg_scale REAL, sampler INTEGER, upscale_mode INTEGER, timestamp INTEGER, generation_time_ms INTEGER, width INTEGER, height INTEGER, file_size_bytes INTEGER`).
- Index on `timestamp DESC` and index on `prompt`.

4. Create `HistoryRepository.kt`:
- Holds `MutableStateFlow<List<GenerationEntity>>`.
- Methods: `insert()`, `delete()`, `deleteBatch()`, `search()`, `scanAndSyncFileSystem()` which scans `imagesDir` and automatically indexes any PNG files created in previous sessions that aren't yet in the database.

5. Update `PipelineManager.kt` to record every successfully completed generation into `HistoryRepository`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.data.HistoryRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sdnpu/data/ app/src/test/java/com/example/sdnpu/data/ app/src/main/java/com/example/sdnpu/pipeline/
git commit -m "feat(data): implement GenerationEntity, GenerationDao, and HistoryRepository"
```

---

### Task 2: Settings DataStore & User Preferences (`SettingsRepository`)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/example/sdnpu/data/SettingsRepository.kt`
- Create: `app/src/test/java/com/example/sdnpu/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: Android `Context.dataStore`, `Preferences`.
- Produces: `SettingsRepository` with `AppSettings` data class and `settingsFlow: Flow<AppSettings>`, `updateSteps(steps: Int)`, `updateCfgScale(cfg: Float)`, `updateSampler(sampler: Int)`, `updateBatchCount(batchCount: Int)`, `updateUpscaleMode(mode: Int)`, `updateBackend(backend: String)`, `updateThermalWarning(enabled: Boolean)`, `updateHighPerformanceMode(enabled: Boolean)`, `updateDarkTheme(theme: String)`.

- [ ] **Step 1: Add DataStore to version catalog and app dependencies**

In `gradle/libs.versions.toml`:
```toml
datastore = "1.1.1"
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```
In `app/build.gradle.kts`:
```kotlin
implementation(libs.androidx.datastore.preferences)
```

- [ ] **Step 2: Write failing test in `SettingsRepositoryTest.kt`**

```kotlin
package com.example.sdnpu.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryTest {

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
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.data.SettingsRepositoryTest"`
Expected: FAIL (`AppSettings` not defined).

- [ ] **Step 4: Implement `AppSettings` and `SettingsRepository`**

1. Define `AppSettings` data class with clamped validation:
```kotlin
package com.example.sdnpu.data

data class AppSettings(
    val defaultSteps: Int = 20,
    val defaultCfgScale: Float = 7.0f,
    val defaultSampler: Int = 0,
    val defaultBatchCount: Int = 1,
    val defaultUpscaleMode: Int = 0,
    val backendPreference: String = "NPU",
    val thermalWarningEnabled: Boolean = true,
    val highPerformanceMode: Boolean = false,
    val darkThemeMode: String = "DARK"
) {
    fun sanitized(): AppSettings = copy(
        defaultSteps = defaultSteps.coerceIn(10, 50),
        defaultCfgScale = defaultCfgScale.coerceIn(1.0f, 20.0f),
        defaultBatchCount = defaultBatchCount.coerceIn(1, 4),
        defaultUpscaleMode = defaultUpscaleMode.coerceIn(0, 2)
    )
}
```

2. Implement `SettingsRepository` with DataStore preference keys, providing flow-based updates and suspend update functions.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.data.SettingsRepositoryTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/example/sdnpu/data/ app/src/test/java/com/example/sdnpu/data/
git commit -m "feat(settings): implement DataStore AppSettings and SettingsRepository"
```

---

### Task 3: Thermal & Battery Health Monitoring & App Notifications

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/system/DeviceMonitor.kt`
- Create: `app/src/main/java/com/example/sdnpu/system/GenerationNotificationManager.kt`
- Create: `app/src/test/java/com/example/sdnpu/system/DeviceMonitorTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Android `PowerManager`, `BatteryManager`, `NotificationManager`.
- Produces:
  - `DeviceMonitor` exposing `thermalStatus: StateFlow<ThermalStatus>`, `batteryLevel: StateFlow<Int>`, `isCharging: StateFlow<Boolean>`, `isLowBattery: StateFlow<Boolean>`.
  - `GenerationNotificationManager` with `notifyGenerationProgress(step: Int, total: Int)`, `notifyGenerationCompleted(imagePath: String)`, `cancel()`.

- [ ] **Step 1: Write failing test in `DeviceMonitorTest.kt`**

```kotlin
package com.example.sdnpu.system

import org.junit.Assert.*
import org.junit.Test

class DeviceMonitorTest {

    @Test
    fun testThermalStatusSeverity() {
        assertTrue(ThermalStatus.CRITICAL.isThrottlingSevere())
        assertTrue(ThermalStatus.SEVERE.isThrottlingSevere())
        assertFalse(ThermalStatus.MODERATE.isThrottlingSevere())
        assertFalse(ThermalStatus.NONE.isThrottlingSevere())
    }

    @Test
    fun testLowBatteryThreshold() {
        assertTrue(DeviceMonitor.isBatteryCriticallyLow(level = 10, isCharging = false))
        assertFalse(DeviceMonitor.isBatteryCriticallyLow(level = 10, isCharging = true))
        assertFalse(DeviceMonitor.isBatteryCriticallyLow(level = 50, isCharging = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.system.DeviceMonitorTest"`
Expected: FAIL (`ThermalStatus` not found).

- [ ] **Step 3: Implement `DeviceMonitor` and `GenerationNotificationManager`**

1. Create `ThermalStatus` enum: `NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN` with helper methods (`isThrottlingSevere()`).
2. Create `DeviceMonitor.kt`:
- Registered listeners for thermal status and battery level broadcast.
- Companion object helper methods for threshold evaluations.
3. Create `GenerationNotificationManager.kt`:
- Create notification channel `sd_npu_generation` with `IMPORTANCE_LOW` for progress.
- Post notification with `setOngoing(true)` during diffusion/upscaling, and `setOngoing(false)` on completion with image preview.
4. Add permissions in `AndroidManifest.xml`:
- `POST_NOTIFICATIONS` (for Android 13+)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.system.DeviceMonitorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sdnpu/system/ app/src/test/java/com/example/sdnpu/system/ app/src/main/AndroidManifest.xml
git commit -m "feat(system): add DeviceMonitor for thermal/battery state and GenerationNotificationManager"
```

---

### Task 4: Advanced UI & Screens (`GenerateScreen`, `GalleryScreen`, `SettingsScreen`)

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/model/ModelManifest.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GalleryScreen.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/SettingsScreen.kt`
- Create: `app/src/test/java/com/example/sdnpu/ui/MainViewModelTest.kt`

**Interfaces:**
- Consumes: `HistoryRepository`, `SettingsRepository`, `DeviceMonitor`, `ModelManager`.
- Produces: Updated Compose UI screens reacting to device thermal state, persisting user settings, offering model variant selection, and providing a gallery with selection, prompt search, and re-generation actions.

- [ ] **Step 1: Write test for ViewModel settings and history integration in `MainViewModelTest.kt`**

Verify:
- ViewModel loads default parameters from `SettingsRepository`.
- ViewModel exposes `historyList` from `HistoryRepository`.
- ViewModel supports searching and multi-deletion.

- [ ] **Step 2: Run test to verify it fails or needs updates**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.ui.MainViewModelTest"`

- [ ] **Step 3: Update `ModelManifest.kt` with DreamShaper variants**

Add variant models:
- `dreamshaper_v8_base`: Default general purpose SD 1.5 model.
- `dreamshaper_v8_anime`: Anime & illustration stylized variant.
- `dreamshaper_v8_realistic`: Photorealistic stylized variant.

- [ ] **Step 4: Update `GenerateScreen.kt`**

- Add Model Variant Dropdown selector showing all available variants with download status badge.
- Add Thermal/Battery warning chip at top of screen if device is throttling (`SEVERE`/`CRITICAL`) or battery < 15% and discharging.
- Connect defaults to `AppSettings`.
- Keep screen on during generation when enabled in settings.

- [ ] **Step 5: Update `GalleryScreen.kt`**

- Search TextField to filter history records by prompt query.
- Multi-selection mode: toggle selection with check icons, batch delete action with confirmation dialog, and share intent for selected images.
- Full inspection dialog: adds "Re-generate with these params" button (populates prompt, negative prompt, seed, steps, cfg into `GenerateScreen`), share button, delete button.
- Asynchronous thumbnail loading with cached Bitmaps to eliminate scroll jank.

- [ ] **Step 6: Update `SettingsScreen.kt`**

- Generation defaults sliders and dropdowns connected to `SettingsRepository`.
- Hardware & Backend options (NPU/HTP, GPU/OpenCL, CPU) with active chip info.
- Thermal throttle warning toggle and high performance mode switch.
- Model storage management: list models with byte sizes and delete action.
- App Diagnostics: QAIRT SDK version 2.49.0, NPU driver status, clear generation cache button.

- [ ] **Step 7: Run tests and assemble APK**

Run: `./gradlew testDebugUnitTest`
Run: `./gradlew :app:assembleDebug`
Expected: All tests pass, APK builds cleanly.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/sdnpu/ app/src/test/java/com/example/sdnpu/
git commit -m "feat(ui): complete advanced UI with model variants, gallery selection, thermal alerts, and settings"
```

---

### Task 5: End-to-End Build, Test Verification & Documentation

**Files:**
- Modify: `README.md`
- Run test suite & build debug APK.

**Interfaces:**
- Consumes: All Phase 1, 2, 3, and 4 deliverables.
- Produces: Verified `app-debug.apk`, passing test suite (75+ unit tests), comprehensive documentation.

- [ ] **Step 1: Run complete unit test suite**

Run: `./gradlew testDebugUnitTest --rerun-tasks`
Expected: 100% tests pass.

- [ ] **Step 2: Build debug APK across all ABIs**

Run: `./gradlew :app:assembleDebug`
Verify: `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 3: Update `README.md`**

- Document Phase 4 architecture: SQLite History Database, DataStore Preferences, Device Thermal & Battery Monitoring, Notification Manager, Advanced Compose UI.
- Update Project Roadmap marking Phase 4 as COMPLETE.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document Phase 4 advanced UI and polish completion and verify build"
```

---
