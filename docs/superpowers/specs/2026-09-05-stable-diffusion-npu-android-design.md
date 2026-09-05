# Android Stable Diffusion + RealESRGAN on NPU — Design Specification

**Date:** 2026-09-05  
**Project:** stable-diffusion-on-phone  
**Target Device:** Samsung Galaxy S23 Ultra (SM-S918B, Snapdragon 8 Gen 2, Hexagon v73 / HTP)  
**Status:** Approved for implementation

---

## 1. Project Overview

Build an Android application that runs **Stable Diffusion (DreamShaper v8)** and **RealESRGAN** locally on the Samsung S23 Ultra's NPU (Hexagon v73 / HTP) using Qualcomm's QNN (Qualcomm Neural Processing SDK), with zero internet dependency after initial model download.

### 1.1 Goals
- Generate images from text prompts using DreamShaper v8 on NPU
- Upscale generated images using RealESRGAN (2x/4x) on NPU
- Advanced UI: model selector, pre-merged LoRA variants, batch generation, full parameter controls
- Models downloaded at runtime from local HTTP server (not bundled in APK)
- Standard Gradle + NDK (CMake) build system

### 1.2 Non-Goals
- Training or fine-tuning on device
- Runtime LoRA injection (pre-merged variants only)
- Cloud/internet-based inference
- Play Store distribution (sideload only)

---

## 2. Architecture

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App (Kotlin)                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │   UI Layer  │  │  Pipeline   │  │  Settings   │              │
│  │ (Compose)   │──│  Manager    │──│  & History  │              │
│  └─────────────┘  └──────┬──────┘  └─────────────┘              │
│                         │                                        │
│         ┌───────────────┼───────────────┐                        │
│         ▼               ▼               ▼                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │
│  │  SD Engine  │  │ RealESRGAN  │  │  Model Mgr  │              │
│  │  (QNN/HTP)  │  │  (QNN/HTP)  │  │ (Download)  │              │
│  └─────────────┘  └─────────────┘  └─────────────┘              │
└─────────────────────────────────────────────────────────────────┘
         │               │               │
         ▼               ▼               ▼
   ┌─────────┐     ┌─────────┐     ┌─────────┐
   │ SD ONNX │     │  ESRGAN │     │ Model   │
   │ → QNN   │     │ → QNN   │     │ Files   │
   │ Context │     │ Context │     │ (Local) │
   └─────────┘     └─────────┘     └─────────┘
```

### 2.2 Core Components

| Component | Responsibility | Technology |
|-----------|---------------|------------|
| **UI Layer** | Prompt input, parameters, gallery, settings | Jetpack Compose, Material 3 |
| **Pipeline Manager** | Orchestrates SD → RealESRGAN chain, batch execution, progress | Kotlin Coroutines, Flow |
| **SD Engine** | Loads QNN context, runs text-to-image inference | JNI → QNN C++ API |
| **RealESRGAN Engine** | Loads QNN context, runs super-resolution | JNI → QNN C++ API |
| **Model Manager** | Downloads, verifies, caches models; handles switching | OkHttp, Kotlinx Serialization |

---

## 3. QNN Integration Strategy

### 3.1 NDK Layer Architecture

```
JNI Bridge (Kotlin ↔ C++)
├── QNN SDK Wrapper
│   ├── QnnSystemInterface  (libQnnSystem.so)
│   ├── QnnHtpDevice        (libQnnHtp.so) — NPU backend
│   ├── QnnModel            (graph loading, tensor setup)
│   ├── QnnContext          (execution context, memory mgmt)
│   └── QnnTensor           (input/output tensor management)
├── Model-Specific Logic
│   ├── SDPipeline: CLIP text encoder → UNet → VAE decoder
│   │   (3 separate QNN graphs or 1 fused graph)
│   └── ESRGANPipeline: Single super-resolution graph
```

### 3.2 Key QNN Decisions

- **Backend**: HTP (`--device HTP0`) for NPU acceleration
- **Context per model**: Each model component = separate QNN context
- **Memory management**: `Qnn_Tensor_t` with shared buffers where possible; explicit `QnnContext_Free` between pipeline stages
- **Async execution**: `QnnContext_ExecuteAsync` + callback for UI responsiveness
- **Quantization**: INT8/INT16 via QNN converter for NPU efficiency

---

## 4. Model Pipeline

### 4.1 Stable Diffusion (DreamShaper v8)

| Stage | Description | QNN Graphs |
|-------|-------------|------------|
| CLIP Text Encoder | Tokenizes prompt → text embeddings | 1 graph |
| UNet (Denoiser) | Iterative denoising in latent space | 1 graph (re-executed per step) |
| VAE Decoder | Latent → RGB image | 1 graph |

**Export Pipeline:**
1. PyTorch → ONNX (via `optimum[exporters]` or manual `torch.onnx.export`)
2. ONNX → QNN via `qnn-onnx-converter` (QAIRT SDK 2.49+)
3. Quantization: INT8 for NPU

**Native Resolution:** 512×512 or 768×768

### 4.2 RealESRGAN

| Variant | Scale | Parameters |
|---------|-------|------------|
| RealESRGAN_x2plus | 2x | ~6.7M params |
| RealESRGAN_x4plus | 4x | ~16.7M params |

**Export Pipeline:** Same as SD (PyTorch → ONNX → QNN)
**Input:** SD output (512×512 RGB)
**Output:** 1024×1024 (2x) or 2048×2048 (4x)

### 4.3 Chaining Strategy

```
Prompt → CLIP → UNet (N steps) → VAE Decode → [RealESRGAN 2x/4x] → Final Image
                                                              │
                                              (optional, user toggle)
```

- Sequential execution: SD completes fully before RealESRGAN starts
- Contexts loaded/unloaded sequentially to manage NPU memory
- Progress callbacks at each stage for UI updates

---

## 5. LoRA Strategy: Pre-Merged Variants

**Decision:** Pre-merge LoRA weights at export time (build-time), not runtime.

**Workflow:**
1. Base model: DreamShaper v8
2. LoRA candidates: anime, realistic, artistic styles, etc.
3. Merge using `kohya-ss` / `sd-scripts`: `python merge_lora.py --base dreamshaper_v8 --lora anime_lora --alpha 0.8`
4. Export each merged variant through full ONNX → QNN pipeline
5. App treats each variant as independent model in selector

**Trade-offs:**
- ✅ Zero runtime complexity; QNN sees standard model
- ✅ No dynamic graph manipulation needed
- ❌ Larger total storage (each variant = full model size)
- ❌ Fixed set; users can't add arbitrary LoRAs

---

## 6. Model Storage & Download

### 6.1 Storage Location
- **Path:** `context.filesDir/models/` (app-private, no permissions needed)
- **Structure:**
  ```
  models/
  ├── dreamshaper_v8/
  │   ├── clip_text_encoder.bin
  │   ├── unet.bin
  │   ├── vae_decoder.bin
  │   └── manifest.json
  ├── dreamshaper_v8_anime/
  │   └── ...
  ├── realesrgan_x2plus/
  │   └── model.bin
  └── realesrgan_x4plus/
      └── model.bin
  ```

### 6.2 Download Strategy
- **Source:** Local HTTP server (laptop) — e.g., `http://192.168.x.x:8080/models/`
- **Protocol:** HTTP/1.1 with range requests (resumable)
- **Client:** OkHttp with interceptors for progress
- **Verification:** SHA256 checksum in `manifest.json` validated post-download
- **Trigger:** First launch + model selector change (if not cached)
- **UI:** Notification channel with progress; "Download Models" screen in settings

### 6.3 Model Manifest (`manifest.json`)
```json
{
  "model_id": "dreamshaper_v8",
  "version": "1.0",
  "components": [
    {"name": "clip_text_encoder", "file": "clip_text_encoder.bin", "sha256": "..."},
    {"name": "unet", "file": "unet.bin", "sha256": "..."},
    {"name": "vae_decoder", "file": "vae_decoder.bin", "sha256": "..."}
  ],
  "qnn_sdk_version": "2.49.0",
  "target_htp": "v73"
}
```

---

## 7. UI/UX Specification (Advanced Scope)

### 7.1 Main Generation Screen

| Element | Specification |
|---------|---------------|
| Prompt | Multi-line TextField, hint "A beautiful landscape..." |
| Negative Prompt | Collapsible multi-line TextField |
| Model Selector | Dropdown: DreamShaper variants (base, anime, realistic, etc.) |
| RealESRGAN | Toggle + Radio: Off / 2x / 4x |
| Steps | Slider 10–50, default 20 |
| CFG Scale | Slider 1.0–20.0, default 7.0 |
| Seed | TextField (empty = random), "🎲" button for random |
| Sampler | Dropdown: Euler a, DPM++ 2M Karras, DPM++ SDE Karras, DDIM |
| Batch Count | Slider 1–4 |
| Generate Button | Primary, shows progress ring during generation |

### 7.2 Gallery / History Screen
- LazyVerticalGrid of generated images
- Each item: thumbnail, prompt truncation, timestamp, model badge
- Tap → Full-screen viewer with metadata sheet
- Long-press → Selection mode (multi-delete, share)
- Pull-to-refresh

### 7.3 Settings Screen
- **Model Management**: List downloaded models, re-download, delete, show size
- **Backend Preference**: NPU (HTP) / GPU (OpenCL) / CPU — with current device detection
- **Defaults**: Default steps, CFG, sampler, batch count, RealESRGAN setting
- **Output**: Save to app-private / Pictures/StableDiffusion / custom directory (SAF)
- **Advanced**: Log level, thermal throttle warning, warm-up on start

---

## 8. Build System

### 8.1 Gradle Configuration
- **AGP:** 8.5+
- **Kotlin:** 2.0+
- **NDK:** r26+ (matching your existing setup)
- **CMake:** 3.22+
- **ABI:** `arm64-v8a` only
- **Min SDK:** 24, **Target SDK:** 34, **Compile SDK:** 34

### 8.2 QAIRT SDK Integration
- **SDK Path:** `/run/media/parasaran/Dev/SDK/qairt/2.49.0.260730/` (existing)
- **Libraries:** `libQnnSystem.so`, `libQnnHtp.so`, `libQnnHtpV73Stub.so`
- **Headers:** `QnnTypes.h`, `QnnSystem.h`, `QnnHtpDevice.h`, etc.
- **CMake:** `find_library` for QNN libs; `target_link_libraries` in native lib

### 8.3 Module Structure
```
app/
├── src/main/
│   ├── java/com/example/sdnpu/
│   │   ├── ui/           # Compose screens
│   │   ├── pipeline/     # PipelineManager, GenerationParams
│   │   ├── engine/       # SDEngine, ESRGANEngine (JNI wrappers)
│   │   ├── model/        # ModelManager, DownloadService
│   │   └── data/         # Repository, Room DB for history
│   ├── cpp/
│   │   ├── qnn_wrapper.cpp/h    # QNN C++ API wrapper
│   │   ├── sd_pipeline.cpp/h    # SD-specific logic
│   │   ├── esrgan_pipeline.cpp/h
│   │   └── jni_bridge.cpp       # JNIEXPORT functions
│   └── assets/         # Empty (models downloaded)
├── build.gradle.kts
└── CMakeLists.txt
```

---

## 9. Key Technical Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| QNN model conversion fails | Medium | High | Test conversion on laptop first; keep ONNX as fallback; document exact converter flags |
| NPU OOM (SD + ESRGAN contexts) | High | High | Load/unload sequentially; `QnnContext_Free` between stages; monitor `QnnContext_GetMemoryUsage` |
| Slow first inference (cold start) | High | Medium | Warm-up run on app start (1×1 dummy); show splash; cache compiled QNN binary |
| Thermal throttling on battery | Medium | High | Detect `PowerManager.isPowerSaveMode`; warn user; offer "Performance Mode" (charger required) |
| Large model download failures | Medium | Medium | Resumable download (Range header); exponential backoff retry; foreground service for large files |
| QNN API version mismatch | Low | High | Pin QAIRT SDK 2.49.0; vendor libs in repo; CI verification |

---

## 10. Development Phases

### Phase 1: Foundation (Week 1-2)
- Android project setup + Gradle + NDK + CMake
- QNN SDK integration (hello-world: load graph, run dummy tensor)
- Model download manager + manifest verification
- Basic Compose UI skeleton

### Phase 2: SD Engine (Week 2-4)
- CLIP text encoder QNN integration
- UNet iterative denoising loop
- VAE decoder
- End-to-end SD generation (512×512)
- Parameter controls (steps, CFG, seed, sampler)

### Phase 3: RealESRGAN + Chaining (Week 4-5)
- RealESRGAN QNN integration (2x, 4x)
- Pipeline chaining: SD → VAE → RealESRGAN
- Batch generation support
- Progress callbacks to UI

### Phase 4: Advanced UI & Polish (Week 5-6)
- Gallery/history with Room DB
- Model selector with pre-merged variants
- Settings screen + persistence (DataStore)
- Thermal/performance monitoring
- Icon, splash, notifications

### Phase 5: Testing & Optimization (Week 6-7)
- Device testing on S23 Ultra
- Benchmark: NPU vs GPU vs CPU
- Memory profiling, leak detection
- Model quantization tuning (INT8 vs INT16)
- Release build + sideload package

---

## 11. Success Criteria

| Metric | Target |
|--------|--------|
| SD 512×512 generation (20 steps) | < 30 seconds on NPU |
| RealESRGAN 2x upscale | < 5 seconds on NPU |
| App cold start → first image | < 45 seconds (incl. model load) |
| Peak RAM usage | < 4 GB |
| APK size (no models) | < 50 MB |
| Zero crashes in 100 generations | ✅ |

---

## 12. References

- **QAIRT SDK:** `/run/media/parasaran/Dev/SDK/qairt/2.49.0.260730/`
- **LLM NPU Prior Work:** `/run/media/parasaran/Dev/Code/claude-chats-no-files/SESSION_HANDOFF.md`
- **QNN Documentation:** QAIRT SDK `docs/` + `examples/`
- **Model Conversion:** `optimum[exporters]`, `qnn-onnx-converter`

---

**Spec Version:** 1.0  
**Approved By:** User  
**Next Step:** Invoke `writing-plans` skill to create implementation plan