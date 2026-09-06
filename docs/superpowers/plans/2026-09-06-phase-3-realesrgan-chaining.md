# Phase 3: RealESRGAN + Pipeline Chaining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the on-device RealESRGAN super-resolution engine (2x and 4x upscaling) and pipeline chaining (SD 512x512 -> VAE -> RealESRGAN 1024x1024 / 2048x2048) with sequential NPU context memory management to eliminate OOM risks, progress callbacks, and UI integration.

**Architecture:** Layered super-resolution pipeline: `PipelineManager` generates 512x512 image via `SDEngine`, cleans up the SD context, transitions state to `PipelineState.Upscaling`, invokes `ESRGANEngine` (which orchestrates C++ `EsrganPipeline` for Qualcomm QNN HTP with pure-JVM bicubic interpolation fallback), and writes the upscaled 1024x1024 or 2048x2048 image to persistent storage.

**Tech Stack:** C++17, NDK r28, CMake 3.22, Qualcomm QNN C++ API (QAIRT 2.49), Kotlin 2.0.21, Jetpack Compose Material 3, Android Bitmap, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-05-stable-diffusion-npu-android-design.md`

## Global Constraints
- Target device: Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2 / Hexagon v73 HTP)
- Sequential NPU memory rule: SD QNN context must be fully unloaded before RealESRGAN QNN context is initialized, preventing concurrent NPU context memory spikes
- Input image size: 512x512 RGBA
- Supported upscale factors: 2x (output 1024x1024 RGBA) and 4x (output 2048x2048 RGBA)
- Thread safety: `EsrganPipeline` is a thread-safe singleton guarded by `std::mutex`
- Host safety: Full pure-JVM bicubic interpolation fallback ensuring 100% unit tests pass without Qualcomm proprietary blobs or missing model files
- Cooperative cancellation: Support instant abort during upscaling

---

### Task 1: RealESRGAN C++ Architecture & JNI Bridge

**Files:**
- Create: `app/src/main/cpp/esrgan_pipeline.h`
- Create: `app/src/main/cpp/esrgan_pipeline.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `app/src/main/cpp/jni_bridge.cpp`

**Interfaces:**
- Produces: C++ `EsrganPipeline` with `loadContext(modelPath, scale)`, `upscale(inputRgba, inWidth, inHeight, outRgba, outWidth, outHeight)`, `unloadContext()`, and `requestCancel()`.
- Produces: JNI functions:
  - `Java_com_example_sdnpu_engine_ESRGANEngine_nativeLoadEsrganContext`
  - `Java_com_example_sdnpu_engine_ESRGANEngine_nativeUpscaleEsrgan`
  - `Java_com_example_sdnpu_engine_ESRGANEngine_nativeUnloadEsrganContext`
  - `Java_com_example_sdnpu_engine_ESRGANEngine_nativeCancelEsrgan`

- [ ] **Step 1: Implement `app/src/main/cpp/esrgan_pipeline.h`**
Header defining `EsrganPipeline` with singleton accessor, mutex synchronization, atomic cancellation, and upscale method.

- [ ] **Step 2: Implement `app/src/main/cpp/esrgan_pipeline.cpp`**
Implementation providing thread-safe load, high-quality bicubic interpolation algorithm for host/simulated inference, tile processing, RGBA color clamping, and clean context unloading.

- [ ] **Step 3: Update `CMakeLists.txt` and `jni_bridge.cpp`**
Add `esrgan_pipeline.cpp` to `sdnpu_engine` library sources and expose native JNI methods with memory release safety (`ReleaseByteArrayElements` / `ReleaseStringUTFChars`).

- [ ] **Step 4: Verify C++ compilation**
Run `./gradlew :app:assembleDebug` to verify C++ builds cleanly across all target ABIs (`arm64-v8a`, `x86_64`).

- [ ] **Step 5: Commit Task 1**
Commit with `feat(esrgan): implement C++ EsrganPipeline and JNI bridge bindings`.

---

### Task 2: Kotlin ESRGANEngine & Upscaling Interpolation Fallback

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/engine/ESRGANEngine.kt`
- Create: `app/src/test/java/com/example/sdnpu/engine/ESRGANEngineTest.kt`

**Interfaces:**
- Consumes: JNI bridge from Task 1.
- Produces: `ESRGANEngine.upscale(inputRgba: ByteArray, width: Int, height: Int, scale: Int, modelsDir: File? = null): ByteArray` returning upscaled byte buffer (`(width * scale) * (height * scale) * 4` bytes).

- [ ] **Step 1: Write unit tests in `ESRGANEngineTest.kt`**
Test 2x upscaling from 512x512 produces 1024x1024x4 bytes; test 4x produces 2048x2048x4 bytes; test cancellation throws `CancellationException`; test channel integrity.

- [ ] **Step 2: Run test to verify failure**
Run `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.ESRGANEngineTest"`.

- [ ] **Step 3: Implement `ESRGANEngine.kt`**
Native JNI wrapper with file existence checks and pure-Kotlin bicubic interpolation fallback ensuring accurate pixel interpolation and zero external dependencies on host JVM.

- [ ] **Step 4: Run tests and verify PASS**
Run `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.ESRGANEngineTest"`.

- [ ] **Step 5: Commit Task 2**
Commit with `feat(esrgan): implement Kotlin ESRGANEngine with JVM bicubic interpolation fallback`.

---

### Task 3: Sequential Memory Management & Pipeline Chaining in PipelineManager

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/pipeline/PipelineManager.kt`
- Create: `app/src/test/java/com/example/sdnpu/pipeline/PipelineChainingTest.kt`

**Interfaces:**
- Consumes: `SDEngine`, `ESRGANEngine`, `UpscaleMode`.
- Produces: Unified pipeline chaining: SD generation -> SD context unload -> `PipelineState.Upscaling` -> `ESRGANEngine.upscale` -> save 1024x1024 or 2048x2048 image -> `PipelineState.Completed`.

- [ ] **Step 1: Write integration tests in `PipelineChainingTest.kt`**
Test pipeline chaining with `UpscaleMode.X2` and `UpscaleMode.X4`. Verify emission order (`LoadingModel` -> `Generating` -> `Upscaling` -> `Completed`) and verify output image dimensions match 1024x1024 (2x) and 2048x2048 (4x).

- [ ] **Step 2: Run test to verify failure**
Run `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.pipeline.PipelineChainingTest"`.

- [ ] **Step 3: Update `PipelineManager.kt`**
Wire `ESRGANEngine.upscale` into `runGeneration`. If `params.upscaleMode != UpscaleMode.OFF`, emit `PipelineState.Upscaling`, invoke `ESRGANEngine.upscale`, recycle intermediate 512x512 bitmap, encode upscaled image, and emit `PipelineState.Completed`.

- [ ] **Step 4: Run tests and verify PASS**
Run `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.pipeline.PipelineChainingTest"`.

- [ ] **Step 5: Commit Task 3**
Commit with `feat(pipeline): chain SD generation with RealESRGAN super-resolution`.

---

### Task 4: RealESRGAN Model Manifest & UI Progress Integration

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/model/ModelManifest.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GalleryScreen.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/MainViewModel.kt`

**Interfaces:**
- Consumes: `PipelineState.Upscaling`.
- Produces: UI states for upscaling (animated progress indicator and "Upscaling 2x/4x with RealESRGAN..."), resolution badge in image preview ("1024x1024" or "2048x2048"), model manifest definitions for RealESRGAN models.

- [ ] **Step 1: Update `ModelManifest.kt` and `ModelManager.kt`**
Add helper to check for and load RealESRGAN model manifests (`realesrgan_x2plus`, `realesrgan_x4plus`).

- [ ] **Step 2: Update `GenerateScreen.kt` & `GalleryScreen.kt`**
Show `PipelineState.Upscaling` in UI progress card with animated progress indicator. Display upscaled resolution badge in image preview and gallery inspection sheet.

- [ ] **Step 3: Verify Compose compilation & unit tests**
Run `./gradlew testDebugUnitTest` and `./gradlew :app:assembleDebug`.

- [ ] **Step 4: Commit Task 4**
Commit with `feat(ui): integrate RealESRGAN upscaling status and resolution badges in Compose UI`.

---

### Task 5: End-to-End Build, Test Verification & Documentation

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Run complete test suite**
Run `./gradlew testDebugUnitTest --rerun-tasks` (confirm 50+ unit tests pass 100%).

- [ ] **Step 2: Build assembleDebug APK**
Run `./gradlew :app:assembleDebug` and verify APK output.

- [ ] **Step 3: Update documentation in `README.md`**
Document RealESRGAN architecture, sequential context memory rule, tile-based upscaling, and mark Phase 3 as COMPLETE in roadmap.

- [ ] **Step 4: Commit Task 5**
Commit with `docs: document Phase 3 RealESRGAN chaining completion and verify build`.
