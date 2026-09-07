# Model Download Resilience, Pause/Resume & Full-Screen Zoomable Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add HTTP Range-based pause/resume model downloads, a background Foreground Service with notification controls to prevent OS download aborts, and an interactive full-screen zoomable image viewer with MediaStore public gallery export and sharing.

**Architecture:** 
- `ModelManager` supports HTTP `Range: bytes=X-` headers and `DownloadStatus.Paused` state, appending to `.part` files on HTTP 206 without deleting partial progress.
- `ModelDownloadService` runs as an Android Foreground Service with `dataSync` type, acquiring `WakeLock`/`WifiLock` and publishing an ongoing notification with Pause/Resume/Cancel action buttons.
- `FullScreenImageViewer` is a full-screen Compose dialog with pinch-to-zoom/double-tap gestures, MediaStore export to `Pictures/SD_NPU`, and FileProvider-based system share sheet.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp, Android Services & Notifications, MediaStore API, FileProvider.

**Spec:** `docs/superpowers/specs/2026-09-07-model-download-resilience-and-fullscreen-viewer-design.md`

## Global Constraints
- Target Device: Samsung Galaxy S23 Ultra (`SM-S918B`, Snapdragon 8 Gen 2 / SM8550).
- Git Branch: `fix/code-review-improvements` (STRICT: Do NOT merge to `master`).
- Release APK size constraint: Release APK must remain < 50MB.
- Preservation: All existing 151 unit tests must remain passing.

---

### Task 1: HTTP Range Request & Pause/Resume Support in `ModelManager.kt`

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/model/DownloadStatus.kt`
- Modify: `app/src/main/java/com/example/sdnpu/model/ModelManager.kt`
- Test: `app/src/test/java/com/example/sdnpu/model/ModelManagerTest.kt`

**Interfaces:**
- `DownloadStatus.Paused(modelId: String, componentName: String, downloadedBytes: Long, totalBytes: Long, percent: Int)`
- `ModelManager.pauseDownload()`
- `ModelManager.downloadModel(manifest, baseUrl)` respects `Range: bytes=${partFile.length()}-` on partial downloads.

- [ ] **Step 1: Write failing test in `ModelManagerTest.kt`**
  Add unit test testing that if `.part` file exists with initial bytes, `downloadModel` sends the `Range` header and appends response body to the `.part` file.
- [ ] **Step 2: Run test to verify failure**
  `./gradlew testDebugUnitTest --tests "com.example.sdnpu.model.ModelManagerTest"`
- [ ] **Step 3: Implement `DownloadStatus.Paused` and HTTP Range resume in `ModelManager.kt`**
  - Add `DownloadStatus.Paused` to `DownloadStatus.kt`.
  - In `ModelManager.downloadModel`: check `partFile.exists() && partFile.length() > 0`.
  - Send `Range: bytes=${partFile.length()}-`. If HTTP 206, open `FileOutputStream(partFile, true)`.
  - If paused, emit `DownloadStatus.Paused` and do not delete `partFile`.
- [ ] **Step 4: Verify test passes**
  `./gradlew testDebugUnitTest --tests "com.example.sdnpu.model.ModelManagerTest"`
- [ ] **Step 5: Commit changes**
  `git commit -am "feat(model): add HTTP Range resume and pause support in ModelManager"`

---

### Task 2: Background `ModelDownloadService` with Ongoing Notification

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/service/ModelDownloadService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/example/sdnpu/ui/MainViewModel.kt`

**Interfaces:**
- `ModelDownloadService`: Foreground service with `dataSync` type, managing ongoing notification and `WakeLock`.
- Actions: `ACTION_START`, `ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_CANCEL`.
- Shared state flow: `ModelDownloadService.downloadState`.

- [ ] **Step 1: Declare service & permissions in `AndroidManifest.xml`**
  Add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, and `<service android:name=".service.ModelDownloadService" android:foregroundServiceType="dataSync" android:exported="false" />`.
- [ ] **Step 2: Implement `ModelDownloadService.kt`**
  - Create `ModelDownloadService` handling download job in a Foreground Service with notification channel `sd_npu_downloads`.
  - Add Pause, Resume, and Cancel PendingIntents on the notification.
  - Acquire `PowerManager.WakeLock` with timeout during active download to prevent socket drop when phone is locked.
- [ ] **Step 3: Connect `MainViewModel` to `ModelDownloadService`**
  - Update `MainViewModel.downloadModelFromUrl()` to start `ModelDownloadService`.
  - Bind to `ModelDownloadService.downloadState` so the UI reflects service download progress.
- [ ] **Step 4: Run unit tests**
  `./gradlew testDebugUnitTest`
- [ ] **Step 5: Commit changes**
  `git commit -am "feat(service): add ModelDownloadService foreground service for resilient background downloads"`

---

### Task 3: Full-Screen Zoomable Image Viewer & MediaStore Public Export / Share

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/ui/components/FullScreenImageViewer.kt`
- Create: `app/src/main/java/com/example/sdnpu/util/MediaExporter.kt`
- Test: `app/src/test/java/com/example/sdnpu/util/MediaExporterTest.kt`

**Interfaces:**
- `MediaExporter.saveImageToPublicGallery(context: Context, sourceFile: File, title: String): Result<Uri>`
- `FullScreenImageViewer(imageFile: File, onDismiss: () -> Unit, onShare: () -> Unit)`

- [ ] **Step 1: Write unit test for `MediaExporter`**
  Verify filename sanitization, mime-type determination, and URI resolution logic.
- [ ] **Step 2: Implement `MediaExporter.kt`**
  Using Android `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = "Pictures/SD_NPU"`.
- [ ] **Step 3: Implement `FullScreenImageViewer.kt`**
  - Full-screen dark dialog.
  - Gesture handling with `pointerInput(Unit)`:
    - `detectTransformGestures` for pinch zoom (scale 1.0f to 5.0f) and panning.
    - `detectTapGestures` for double-tap zoom toggle (1.0f <-> 2.5f).
  - Floating top bar: close button and resolution badge.
  - Floating bottom bar: "Save to Gallery" and "Share" buttons.
- [ ] **Step 4: Run unit tests**
  `./gradlew testDebugUnitTest`
- [ ] **Step 5: Commit changes**
  `git commit -am "feat(ui): implement FullScreenImageViewer with pinch zoom, MediaStore export, and share"`

---

### Task 4: UI Integration & End-to-End Verification on Samsung Galaxy S23 Ultra

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/ModelDownloadDialog.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GalleryScreen.kt`

- [ ] **Step 1: Add Pause/Resume buttons to `ModelDownloadDialog.kt` and `GenerateScreen.kt` background card**
  - Show "Pause" button while active; show "Resume" button when paused.
- [ ] **Step 2: Connect FullScreenImageViewer to `GenerateScreen.kt`**
  - Tap on generated image opens `FullScreenImageViewer`.
  - Add quick "Save to Gallery" and "Share" action buttons directly on the Completed generation card.
- [ ] **Step 3: Connect FullScreenImageViewer to `GalleryScreen.kt`**
  - Detail dialog and item tap triggers `FullScreenImageViewer`.
  - Add "Save to Gallery" button to item details.
- [ ] **Step 4: Run full test suite & assemble release APK**
  `./gradlew testDebugUnitTest && ./gradlew :app:assembleRelease`
  Verify APK size < 50MB.
- [ ] **Step 5: Deploy & test on connected Samsung Galaxy S23 Ultra**
  - Install release APK: `adb install -r app/build/outputs/apk/release/app-release.apk`.
  - Verify fullscreen zoom viewer gestures and Save to Gallery on device.
- [ ] **Step 6: Commit and push changes**
  `git commit -am "feat(ui): integrate download pause/resume controls and fullscreen viewer across screens"`
  `git push origin fix/code-review-improvements`
