# ONNX Runtime Mobile + QNN EP SD Engine Design Specification

**Date:** 2026-09-06  
**Project:** stable-diffusion-on-phone  
**Target Device:** Samsung Galaxy S23 Ultra (SM-S918B, Snapdragon 8 Gen 2, Hexagon v73 HTP)  
**Branch:** `fix/code-review-improvements` (Do NOT merge)  
**Status:** Approved

---

## 1. Executive Summary

This specification defines the architectural migration of the on-device text-to-image inference subsystem from mock C++ arithmetic stubs to **Microsoft ONNX Runtime Mobile with Qualcomm QNN Execution Provider** (`onnxruntime-android-qnn`). 

The system targets **SD-Turbo / LCM (Latent Consistency Model)** in quantized INT8/FP16 format. This delivers high-fidelity 512x512 image generation in **1 to 4 steps** (~400–800ms total inference on Hexagon v73 HTP) with minimal battery drain and thermal impact.

---

## 2. Problem Statement & Root Cause

The initial implementation completed in ~38ms and returned colorful static / random pixel noise because:
1. **Missing Neural Network Inference**: The C++ components (`clip_encoder.cpp`, `unet_denoiser.cpp`, `vae_decoder.cpp`) contained mock mathematical formulas (`sin()`, `cos()`, `latent * 0.5f`).
2. **Missing Model Weights**: When no `.bin` models existed on disk, `SDEngine.kt` fell back to generating 16,384 Gaussian noise floats and calling `VaePostProcessor.latentsToRgbBytes()`.
3. **Pseudo-RGB VAE Mapping**: `VaePostProcessor.kt` and `vae_decoder.cpp` mapped raw noise latent channels 0, 1, and 2 directly to R, G, and B pixels with nearest-neighbor scaling.
4. **Linker Restrictions**: Android's linker namespace prevents unprivileged apps from directly calling `dlopen("/vendor/lib64/snap/libQnnHtp.so")` without bundling libraries in `jniLibs` or using a certified execution provider.

---

## 3. System Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI Layer                        │
│   GenerateScreen  │  GalleryScreen  │  SettingsScreen (Downloader)     │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ StateFlow / Coroutines
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           Pipeline Manager                             │
│   - Gating check: blocks generation with error if models missing       │
│   - Memory safety pre-check (>= 1000MB free RAM)                       │
│   - Step progress streaming via channelFlow                            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        OnnxDiffusionEngine (Kotlin)                    │
│   - OrtEnvironment & Sequential OrtSessions:                           │
│       1. text_encoder.onnx  -> [1, 77, 768] embeddings                 │
│       2. unet.onnx          -> 1-4 step latent denoising loop          │
│       3. vae_decoder.onnx   -> [1, 3, 512, 512] RGB tensor             │
│   - Execution Provider Configuration:                                  │
│       * Primary: QNN Execution Provider (HTP Backend, Hexagon v73)     │
│       * Fallback: NNAPI / CPU (XNNPACK)                                │
│   - Sequential Session Lifecycle (unload between stages to save RAM)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           Model Storage & Assets                       │
│   - Models Directory: context.filesDir/models/sdturbo/                 │
│   - Files: text_encoder.onnx, unet.onnx, vae_decoder.onnx, vocab.json  │
│   - Provisioning: In-app HTTP Downloader + ADB Push script             │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Component Details

### 4.1 Tokenizer: BPE with Vocabulary Assets
- **Asset**: Bundle official CLIP Byte-Pair Encoding assets (`bpe_simple_vocab_16e6.txt` or compact `vocab.json` / `merges.txt`) in `app/src/main/assets/`.
- **Implementation**: `ClipTokenizer.kt` parses text into true subword tokens bounded by `<|startoftext|>` (`49406`) and `<|endoftext|>` (`49407`), zero-padded to sequence length 77.

### 4.2 Diffusion Pipeline: SD-Turbo / LCM
- **Text Encoder**:
  - Input: `input_ids` shape `[1, 77]` (int32).
  - Output: `last_hidden_state` shape `[1, 77, 768]` (float32 / float16).
- **UNet Denoiser**:
  - Model: SD-Turbo / LCM (1–4 steps, CFG = 1.0).
  - Inputs:
    - `sample`: `[1, 4, 64, 64]`
    - `timestep`: `[1]`
    - `encoder_hidden_states`: `[1, 77, 768]`
  - Output: `out_sample` `[1, 4, 64, 64]`.
  - Scheduler: Euler or LCM multi-step solver.
- **VAE Decoder**:
  - Input: `latent_sample`: `[1, 4, 64, 64]` (divided by $0.18215$).
  - Output: `sample`: `[1, 3, 512, 512]` in range $[-1.0, 1.0]$.
  - Post-processing: Map planar RGB floats to standard ARGB Android `Bitmap`.

### 4.3 Execution Provider Strategy
1. Configure `OrtSession.SessionOptions`:
   - Add QNN Execution Provider with options:
     - `backend_type`: `HTP`
     - `htp_performance_mode`: `burst` / `high_performance`
   - Fallback if QNN EP fails: NNAPI or CPU with multi-threading (`intraOpNumThreads = 4`).
2. Sequential Lifecycle:
   - Load `text_encoder.onnx` $\rightarrow$ run inference $\rightarrow$ close session.
   - Load `unet.onnx` $\rightarrow$ run 1–4 diffusion steps $\rightarrow$ close session.
   - Load `vae_decoder.onnx` $\rightarrow$ run decode $\rightarrow$ close session.
   - This keeps peak memory within ~800MB–1.2GB, well within the S23 Ultra's 8GB/12GB RAM.

### 4.4 Model Provisioning & Missing Model Safety
1. **Pre-check Gate**:
   - If `text_encoder.onnx`, `unet.onnx`, or `vae_decoder.onnx` are missing, `PipelineManager` halts immediately with an informative error message:
     *"SD-Turbo models not found. Please download models in Settings or sideload them via ADB."*
   - In `GenerateScreen.kt`, the "Generate Image" button is disabled or triggers a download prompt if no model is installed.
2. **Hybrid Delivery**:
   - In-app: `ModelManager.kt` downloads model components with SHA-256 verification and progress updates.
   - Sideload: `scripts/sideload.sh` supports a `--push-models <dir>` flag to push ONNX models directly into `/sdcard/Android/data/com.example.sdnpu/files/models/sdturbo/`.

---

## 5. Implementation Boundaries & PR Constraints

- All changes remain on branch `fix/code-review-improvements`.
- Do **NOT** merge the PR to `master`.
- Build must maintain `< 50MB` APK size constraint when models are external (downloaded/sideloaded).
