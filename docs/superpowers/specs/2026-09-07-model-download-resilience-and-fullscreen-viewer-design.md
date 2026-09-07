# Model Download Resilience, Pause/Resume, & Full-Screen Zoomable Image Viewer Design Spec

## Goal
Provide a resilient background model downloading system with pause/resume support via HTTP Range requests and an Android Foreground Service, alongside an interactive full-screen zoomable lightbox with Save to Gallery (MediaStore) and System Share integration.

---

## 1. Subsystem A: Resilient Download & HTTP Range-based Pause/Resume

### Problem Statement
Currently, `ModelManager.downloadModel()` downloads model component files to `.part` files. If cancelled, interrupted, or network dropped, `cleanupIfIncomplete()` purges partial files. When resuming, downloads must restart from 0 bytes. Furthermore, background coroutines in `MainViewModel` are killed or throttled by Android when the app is minimized.

### Design Details
1. **HTTP Range Requests**:
   - For each component (e.g. `unet.onnx` ~1.6GB), inspect if `${comp.file}.part` exists on disk.
   - If `partFile.exists() && partFile.length() > 0`:
     Add request header: `Range: bytes=${partFile.length()}-`.
   - **Server Responses**:
     - **HTTP 206 (Partial Content)**: Server supports byte ranges. Open `FileOutputStream(partFile, append = true)`. Read stream and accumulate downloaded bytes starting from `partFile.length()`.
     - **HTTP 200 (OK)**: Server does not support byte ranges or restarted stream. Open `FileOutputStream(partFile, append = false)`.
   - **Checksum Verification**:
     - Once downloaded bytes reach total bytes, compute SHA256 checksum on `partFile`.
     - Atomically rename `partFile` to `targetFile`.

2. **Pause / Resume Semantics**:
   - Extend `DownloadStatus` sealed interface/class with:
     ```kotlin
     data class Paused(
         val modelId: String,
         val componentName: String,
         val downloadedBytes: Long,
         val totalBytes: Long,
         val percent: Int
     ) : DownloadStatus
     ```
   - In `ModelManager`:
     - Add `pauseDownload()` function that sets an active pause flag or cancels active OkHttp call without deleting `.part` files.
     - Add `resumeDownload(manifest: ModelManifest, baseUrl: String): Flow<DownloadStatus>`.

---

## 2. Subsystem B: Background Download Foreground Service

### Problem Statement
On Android 13/14 (API 33/34+), background network tasks without a Foreground Service are throttled or terminated by the OS when the app is put in the background.

### Design Details
1. **`ModelDownloadService`**:
   - Inherits from `android.app.Service`.
   - Declared in `AndroidManifest.xml` with:
     ```xml
     <service
         android:name=".service.ModelDownloadService"
         android:exported="false"
         android:foregroundServiceType="dataSync" />
     ```
   - Uses `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions.
   - Manages a partial `PowerManager.WakeLock` and `WifiManager.WifiLock` to prevent CPU and radio sleep during large downloads.

2. **Ongoing Foreground Notification**:
   - Notification channel: `sd_npu_downloads` ("Model Downloads", importance `LOW` or `DEFAULT` to prevent intrusive sound loops).
   - Shows:
     - Title: "Downloading Model: ${manifest.modelId}"
     - Content: "Downloading ${comp.name} (${percent}%)"
     - Progress Bar: `setProgress(100, percent, false)`
     - Actions:
       - **Pause** (PendingIntent sending action `ACTION_PAUSE`)
       - **Resume** (PendingIntent sending action `ACTION_RESUME`)
       - **Cancel** (PendingIntent sending action `ACTION_CANCEL`)
   - Communicates reactive state to `MainViewModel` via a singleton repository or shared state flow (`DownloadProgressRepository` / `ModelDownloadService.downloadState`).

---

## 3. Subsystem C: Full-Screen Zoomable Image Viewer & MediaStore / Share

### Problem Statement
Generated images can only be seen in a fixed small card or inside a non-zoomable dialog. Users cannot inspect fine details (e.g. fur texture, eyes) up close. Users also need a one-tap way to save the image directly to their phone's device gallery (`Pictures/SD_NPU/`) and share it to other apps.

### Design Details
1. **Full-Screen Zoomable Lightbox (`FullScreenImageViewer.kt`)**:
   - Fullscreen `Dialog` using `DialogProperties(usePlatformDefaultWidth = false)` with `Color.Black` background.
   - **Gestures**:
     - `pointerInput` with `detectTransformGestures` supporting:
       - Zoom / Scale from 1.0f to 5.0f.
       - 2D translation (pan) bounded by viewport limits when scaled > 1.0f.
     - `pointerInput` with `detectTapGestures` supporting:
       - Double tap: toggles zoom between 1.0f (fit) and 2.5f (detail zoom).
   - **Overlays**:
     - Top bar with Back/Close button and image info chip.
     - Bottom bar with:
       - **Save to Gallery** button (`Icons.Default.Download` / `SaveAlt`)
       - **Share** button (`Icons.Default.Share`)

2. **Save to Gallery (MediaStore Integration)**:
   - Helper function `saveToPublicGallery(context: Context, sourceFile: File): Result<Uri>`:
     - Uses `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`.
     - In Android 10+ (API 29+): Sets `RELATIVE_PATH = "Pictures/SD_NPU"`, `MIME_TYPE = "image/png"`, and streams bytes via `ContentResolver.openOutputStream`.
     - Displays brief confirmation Toast / Snackbar: `"Saved to Gallery (Pictures/SD_NPU)"`.

3. **Share Integration**:
   - Uses `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)` with `Intent.ACTION_SEND` and `Intent.FLAG_GRANT_READ_URI_PERMISSION`.
   - Launches `Intent.createChooser`.

4. **GenerateScreen & GalleryScreen Integration**:
   - Clicking the generated image card on `GenerateScreen` opens the `FullScreenImageViewer`.
   - Completed card on `GenerateScreen` also displays quick-action buttons for "Save to Gallery" and "Share".
   - `GalleryScreen` item detail dialog adds "View Fullscreen" and "Save to Gallery" buttons.

---

## 4. Test Strategy
- `ModelManagerTest.kt`:
  - Test HTTP 206 Range request resume from partial byte offset.
  - Test pause preserves `.part` file.
  - Test cancel cleans up if requested.
- `MediaStoreHelperTest.kt` / `ShareHelperTest.kt`:
  - Test Uri generation and intent creation with valid permissions.
- Live device testing on Samsung Galaxy S23 Ultra:
  - Start download, pause midway, minimize app, lock phone, unlock phone, resume download, verify completion.
  - Generate image, tap to open fullscreen zoom viewer, pinch to zoom, double-tap to zoom, tap Save to Gallery and verify file in Samsung Gallery app.
