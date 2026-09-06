# ONNX Runtime Mobile + QNN EP SD Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the simulated 38ms CPU noise generation with a real on-device ONNX Runtime Mobile inference pipeline targeting SD-Turbo / LCM on Snapdragon 8 Gen 2 (Samsung S23 Ultra) with Qualcomm QNN Execution Provider (Hexagon v73 HTP) and NNAPI/CPU fallback.

**Architecture:** Kotlin `OnnxDiffusionEngine` managing sequential `OrtSession` lifecycles (Text Encoder -> UNet 1-4 steps -> VAE Decoder) to fit within mobile RAM, true CLIP Byte-Pair Encoding from bundled vocabulary assets, robust pre-flight model validation, and hybrid model delivery (in-app download + ADB sideload script).

**Tech Stack:** Kotlin 2.0.21, ONNX Runtime Mobile (`onnxruntime-android`), Android Jetpack Compose, OkHttp, JUnit 4, ADB.

**Spec:** `docs/superpowers/specs/2026-09-06-onnx-runtime-qnn-sd-engine-design.md`

## Global Constraints
- Target device: Samsung Galaxy S23 Ultra (SM-S918B, Snapdragon 8 Gen 2, Hexagon v73 HTP)
- Git branch: `fix/code-review-improvements` (STRICT: Do NOT merge PR)
- Model architecture: SD-Turbo / LCM (1 to 4 steps, 512x512 native resolution, CFG = 1.0)
- Model components: `text_encoder.onnx`, `unet.onnx`, `vae_decoder.onnx`
- Models directory: `context.filesDir/models/sdturbo/` or `/sdcard/Android/data/com.example.sdnpu/files/models/sdturbo/`
- APK size constraint: Release APK must remain < 50MB (models are downloaded or sideloaded, not bundled in APK)

---

### Task 1: Add ONNX Runtime Dependency & Missing Model Safety Gate

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/example/sdnpu/pipeline/PipelineManager.kt`
- Modify: `app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt`
- Test: `app/src/test/java/com/example/sdnpu/pipeline/PipelineManagerValidationTest.kt`

**Interfaces:**
- Consumes: `GenerationParams`
- Produces: `PipelineManager.validateModelAvailability(modelId: String, modelsDir: File): Result<Unit>` ensuring missing models halt immediately with actionable error before running any generation.

- [ ] **Step 1: Write failing unit test for model availability validation**

`app/src/test/java/com/example/sdnpu/pipeline/PipelineManagerValidationTest.kt`:
```kotlin
package com.example.sdnpu.pipeline

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PipelineManagerValidationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidationFailsWhenModelFilesMissing() {
        val modelsDir = tempFolder.newFolder("models")
        val manager = PipelineManager(modelsDir = modelsDir)

        val result = manager.validateModelAvailability("sdturbo", modelsDir)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun testValidationSucceedsWhenAllRequiredOnnxModelsExist() {
        val modelsDir = tempFolder.newFolder("models")
        val modelDir = File(modelsDir, "sdturbo").apply { mkdirs() }
        File(modelDir, "text_encoder.onnx").createNewFile()
        File(modelDir, "unet.onnx").createNewFile()
        File(modelDir, "vae_decoder.onnx").createNewFile()

        val manager = PipelineManager(modelsDir = modelsDir)
        val result = manager.validateModelAvailability("sdturbo", modelsDir)
        assertTrue(result.isSuccess)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.pipeline.PipelineManagerValidationTest"`  
Expected: FAIL (method `validateModelAvailability` does not exist).

- [ ] **Step 3: Add ONNX Runtime Mobile dependency to `app/build.gradle.kts`**

In `app/build.gradle.kts`:
```kotlin
dependencies {
    // ONNX Runtime Mobile with QNN and NNAPI support
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    ...
}
```

- [ ] **Step 4: Implement `validateModelAvailability` in `PipelineManager.kt` and update UI gate**

In `app/src/main/java/com/example/sdnpu/pipeline/PipelineManager.kt`:
```kotlin
fun validateModelAvailability(modelId: String, modelsDir: File): Result<Unit> {
    val modelDir = File(modelsDir, modelId)
    val requiredFiles = listOf("text_encoder.onnx", "unet.onnx", "vae_decoder.onnx")
    val missing = requiredFiles.filter { !File(modelDir, it).exists() }
    return if (missing.isEmpty()) {
        Result.success(Unit)
    } else {
        Result.failure(IllegalStateException("Model '$modelId' is missing components: ${missing.joinToString(", ")}. Please download in Settings or sideload via ADB."))
    }
}
```
And check this in `runGeneration()` before starting inference. If failed, emit `PipelineState.Error(...)` and return immediately.

- [ ] **Step 5: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.pipeline.PipelineManagerValidationTest"`  
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/example/sdnpu/pipeline/PipelineManager.kt app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt app/src/test/java/com/example/sdnpu/pipeline/PipelineManagerValidationTest.kt
git commit -m "feat: add ONNX Runtime dependency and enforce model availability pre-check"
```

---

### Task 2: Implement True CLIP BPE Tokenizer with Bundled Vocabulary

**Files:**
- Create: `app/src/main/assets/bpe_simple_vocab_16e6.txt` (or compact vocabulary asset)
- Modify: `app/src/main/java/com/example/sdnpu/engine/ClipTokenizer.kt`
- Modify: `app/src/test/java/com/example/sdnpu/engine/ClipTokenizerTest.kt`

**Interfaces:**
- Consumes: CLIP vocabulary text asset from `Context.assets` or fallback string table.
- Produces: `ClipTokenizer.tokenize(text: String, maxLength: Int = 77): IntArray` returning exact standard CLIP token IDs.

- [ ] **Step 1: Write unit tests for real CLIP tokenization**

`app/src/test/java/com/example/sdnpu/engine/ClipTokenizerTest.kt`:
```kotlin
@Test
fun testTokenizeKnownPromptMatchesClipIds() {
    val tokenizer = ClipTokenizer()
    val tokens = tokenizer.tokenize("a photo of an astronaut")
    assertEquals(77, tokens.size)
    assertEquals(49406, tokens[0]) // <|startoftext|>
    // Verify tokens contains end-of-text marker
    assertTrue(tokens.contains(49407))
}
```

- [ ] **Step 2: Run test to check current failure or mismatch**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.ClipTokenizerTest"`

- [ ] **Step 3: Implement true BPE tokenization logic in `ClipTokenizer.kt`**

Add standard BPE merges processing and token dictionary so arbitrary prompts are split into subwords rather than arbitrary hash codes.

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.ClipTokenizerTest"`  
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/ app/src/main/java/com/example/sdnpu/engine/ClipTokenizer.kt app/src/test/java/com/example/sdnpu/engine/ClipTokenizerTest.kt
git commit -m "feat: implement standard CLIP BPE tokenization"
```

---

### Task 3: Implement ONNX Runtime Mobile Diffusion Engine

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/engine/OnnxDiffusionEngine.kt`
- Modify: `app/src/main/java/com/example/sdnpu/engine/SDEngine.kt`
- Create: `app/src/test/java/com/example/sdnpu/engine/OnnxDiffusionEngineTest.kt`

**Interfaces:**
- Consumes: Model directory with `text_encoder.onnx`, `unet.onnx`, `vae_decoder.onnx`.
- Produces: `OnnxDiffusionEngine.generate(params: GenerationParams, modelDir: File, onStep: (Int, Int) -> Unit): ByteArray` returning raw 512x512 ARGB byte array from real model outputs.

- [ ] **Step 1: Write unit test for `OnnxDiffusionEngine` scheduler stepping and tensor conversions**

`app/src/test/java/com/example/sdnpu/engine/OnnxDiffusionEngineTest.kt`:
```kotlin
package com.example.sdnpu.engine

import org.junit.Assert.*
import org.junit.Test

class OnnxDiffusionEngineTest {
    @Test
    fun testEulerStepUpdatesLatents() {
        val latents = FloatArray(16384) { 1.0f }
        val noisePred = FloatArray(16384) { 0.5f }
        val outLatents = FloatArray(16384)
        
        OnnxDiffusionEngine.eulerStep(latents, noisePred, sigma = 1.0f, nextSigma = 0.5f, outLatents)
        // out = sample + (nextSigma - sigma) * noise = 1.0 + (-0.5) * 0.5 = 0.75
        assertEquals(0.75f, outLatents[0], 1e-4f)
    }

    @Test
    fun testVaeRgbPostProcessingClamping() {
        val fakeRgbFloats = floatArrayOf(-1.0f, 0.0f, 1.0f) // Planar RGB [1, 3, 1, 1]
        val bytes = OnnxDiffusionEngine.planarRgbToArgbBytes(fakeRgbFloats, width = 1, height = 1)
        assertEquals(4, bytes.size)
        assertEquals(0.toByte(), bytes[0])   // R: (-1 + 1)*127.5 = 0
        assertEquals(127.toByte(), bytes[1]) // G: (0 + 1)*127.5 = 127
        assertEquals(255.toByte(), bytes[2]) // B: (1 + 1)*127.5 = 255
        assertEquals(255.toByte(), bytes[3]) // A: 255
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.OnnxDiffusionEngineTest"`  
Expected: FAIL (class `OnnxDiffusionEngine` not implemented).

- [ ] **Step 3: Implement `OnnxDiffusionEngine.kt`**

Implement:
1. `createSessionOptions()`:
   - Sets execution providers (tries QNN HTP options, falls back to NNAPI or CPU multi-threading).
2. `encodePrompt(textEncoderSession, promptTokens)`:
   - Feeds `[1, 77]` int32 tensor $\rightarrow$ gets `[1, 77, 768]` float tensor.
3. `denoiseLoop(unetSession, latents, textEmbeddings, steps, onStep)`:
   - 1 to 4 steps of SD-Turbo denoising with Euler / LCM scheduler.
4. `decodeVae(vaeSession, latents)`:
   - Scales latents by $1 / 0.18215$, runs VAE $\rightarrow$ outputs `[1, 3, 512, 512]`.
   - Maps planar RGB $[-1, 1]$ to standard ARGB byte array.
5. Session closure after each step to prevent NPU OOM.

- [ ] **Step 4: Update `SDEngine.kt` to delegate to `OnnxDiffusionEngine`**

In `app/src/main/java/com/example/sdnpu/engine/SDEngine.kt`, replace the mock CPU fallbacks with calls to `OnnxDiffusionEngine.generate()`. If model files do not exist, throw an explicit error rather than silently returning random noise.

- [ ] **Step 5: Run tests and verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.OnnxDiffusionEngineTest"`  
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/sdnpu/engine/OnnxDiffusionEngine.kt app/src/main/java/com/example/sdnpu/engine/SDEngine.kt app/src/test/java/com/example/sdnpu/engine/OnnxDiffusionEngineTest.kt
git commit -m "feat: implement ONNX Runtime Mobile diffusion engine for SD-Turbo"
```

---

### Task 4: Model Manifest & Sideload Script Automation for SD-Turbo ONNX Models

**Files:**
- Modify: `app/src/main/java/com/example/sdnpu/model/ModelManifest.kt`
- Modify: `scripts/sideload.sh`
- Test: `app/src/test/java/com/example/sdnpu/model/ModelManifestTest.kt`

**Interfaces:**
- Consumes: Target model folder containing `.onnx` files.
- Produces: `ModelManifest.sdturbo()` and `./scripts/sideload.sh --push-models <dir>` automation.

- [ ] **Step 1: Write test for `ModelManifest.sdturbo()`**

In `app/src/test/java/com/example/sdnpu/model/ModelManifestTest.kt`:
```kotlin
@Test
fun testSdTurboManifestContainsRequiredOnnxComponents() {
    val manifest = ModelManifest.sdturbo()
    assertEquals("sdturbo", manifest.modelId)
    val files = manifest.components.map { it.file }
    assertTrue(files.contains("text_encoder.onnx"))
    assertTrue(files.contains("unet.onnx"))
    assertTrue(files.contains("vae_decoder.onnx"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.model.ModelManifestTest"`  
Expected: FAIL (`sdturbo` manifest factory method not defined).

- [ ] **Step 3: Update `ModelManifest.kt` and `scripts/sideload.sh`**

1. Add `sdturbo()` to `ModelManifest.companion`:
```kotlin
fun sdturbo(sha256Prefix: String = ""): ModelManifest = ModelManifest(
    modelId = "sdturbo",
    version = "1.0",
    components = listOf(
        ModelComponent("text_encoder", "text_encoder.onnx", "$sha256Prefix.text_encoder"),
        ModelComponent("unet", "unet.onnx", "$sha256Prefix.unet"),
        ModelComponent("vae_decoder", "vae_decoder.onnx", "$sha256Prefix.vae_decoder")
    ),
    qnnSdkVersion = "ort-1.20",
    targetHtp = "v73"
)
```
2. In `scripts/sideload.sh`, add support for `--push-models <path_to_onnx_dir>` which executes:
```bash
adb -s "$TARGET_SERIAL" push "$MODELS_DIR"/* /sdcard/Android/data/com.example.sdnpu/files/models/sdturbo/
```

- [ ] **Step 4: Run unit tests and assemble release build to verify constraints**

Run:
```bash
./gradlew testDebugUnitTest
./gradlew :app:assembleRelease
```
Verify:
1. All unit tests pass.
2. `app-release.apk` is generated and remains < 50MB.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/sdnpu/model/ModelManifest.kt scripts/sideload.sh app/src/test/java/com/example/sdnpu/model/ModelManifestTest.kt
git commit -m "feat: add SD-Turbo model manifest and sideload push automation"
```
