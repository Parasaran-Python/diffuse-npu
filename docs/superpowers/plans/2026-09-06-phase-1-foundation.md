# Phase 1: Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android application foundation for on-device Stable Diffusion and RealESRGAN inference, establishing Gradle and NDK build systems, a robust C++ QNN wrapper architecture with dynamic hardware detection and stub fallback, an OkHttp-based model download manager with SHA-256 manifest verification, and a complete Jetpack Compose Material 3 UI skeleton.

**Architecture:** A layered Android application: Jetpack Compose presentation layer (Material 3), Kotlin Coroutines and StateFlow pipeline orchestration, an OkHttp model download and caching service with cryptographic integrity verification, and a JNI C++ wrapper layer designed for Qualcomm QNN/HTP with dynamic library loading and graceful CPU/stub fallback.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 9.3.2, NDK 28.2.13676358, CMake 3.22.1, Jetpack Compose (BOM 2024.10.01 / Material 3), OkHttp 4.12.0, Gson 2.11.0, JUnit 4, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-09-05-stable-diffusion-npu-android-design.md`

## Global Constraints

- Android API: `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- Application ID / Namespace: `com.example.sdnpu`
- ABI: `arm64-v8a` (with `x86_64` allowed for local emulator testing)
- Java/JVM target: Java 17 compatibility
- Build system: Gradle Kotlin DSL (`settings.gradle.kts`, `build.gradle.kts`) with version catalog (`libs.versions.toml`)
- Model storage: App-private directory `context.filesDir/models/`
- Manifest format: JSON containing `model_id`, `version`, `components` (name, file, sha256), `qnn_sdk_version`, `target_htp`
- QNN Native layer: Header stubs and dynamic loading via `dlopen`/`dlsym` so compilation and non-NPU execution succeed without requiring pre-installed proprietary blobs on dev machines

---

### Task 1: Android Project Scaffolding & Gradle Build Configuration

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/proguard-rules.pro`

**Interfaces:**
- Consumes: None (initial setup)
- Produces: Runnable Gradle build environment configured with AGP, Kotlin 2.0 Compose compiler, NDK r28, CMake 3.22, OkHttp, Coroutines, and test libraries.

- [ ] **Step 1: Create version catalog `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.3.2"
kotlin = "2.0.21"
coreKtx = "1.13.1"
lifecycle = "2.8.6"
activityCompose = "1.9.3"
composeBom = "2024.10.01"
material3 = "1.3.1"
coroutines = "1.8.1"
okhttp = "4.12.0"
gson = "2.11.0"
junit = "4.13.2"
extJunit = "1.2.1"
espresso = "3.6.1"
mockwebserver = "4.12.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }

compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }

kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }

junit = { group = "junit", name = "junit", version.ref = "junit" }
mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "mockwebserver" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "extJunit" }
androidx-test-espresso = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Create root build and settings files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "stable-diffusion-on-phone"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.0-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3: Create app module build configuration and manifest**

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.sdnpu"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.sdnpu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
```

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="SD on NPU"
        android:roundIcon="@android:drawable/sym_def_app_icon"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

`app/proguard-rules.pro`:
```proguard
-keepattributes *Annotation*
-keepclassmembers class * {
    native <methods>;
}
-keep class com.example.sdnpu.model.** { *; }
```

- [ ] **Step 4: Copy gradle wrapper binaries and verify wrapper execution**

Copy `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar` from `/run/media/parasaran/Dev/Code/AndroidStudioProjects/LocalHostAI/` into the project root and ensure execute permissions.
Run: `./gradlew --version`
Expected: Gradle 9.5 or 9.7 outputs successfully.

- [ ] **Step 5: Commit scaffolding**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ app/ gradlew gradlew.bat
git commit -m "feat(scaffold): initialize Android project with Gradle 9, NDK, and Compose"
```

---

### Task 2: Native C++ QNN Bridge & Backend Architecture

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/include/qnn_types.h`
- Create: `app/src/main/cpp/qnn_wrapper.h`
- Create: `app/src/main/cpp/qnn_wrapper.cpp`
- Create: `app/src/main/cpp/jni_bridge.cpp`
- Create: `app/src/main/java/com/example/sdnpu/engine/BackendType.kt`
- Create: `app/src/main/java/com/example/sdnpu/engine/BackendStatus.kt`
- Create: `app/src/main/java/com/example/sdnpu/engine/QnnNativeBridge.kt`
- Test: `app/src/test/java/com/example/sdnpu/engine/BackendStatusTest.kt`

**Interfaces:**
- Consumes: NDK CMake build environment from Task 1.
- Produces: `libsdnpu_engine.so` native library with JNI endpoints:
  - `QnnNativeBridge.initBackend(type: Int): Int`
  - `QnnNativeBridge.getBackendStatus(): BackendStatus`
  - `QnnNativeBridge.runBenchmarkDummy(iterations: Int): Float`
  - `QnnNativeBridge.releaseBackend(): Unit`

- [ ] **Step 1: Write failing unit test for `BackendStatus` and `BackendType` mappings**

`app/src/test/java/com/example/sdnpu/engine/BackendStatusTest.kt`:
```kotlin
package com.example.sdnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendStatusTest {
    @Test
    fun testBackendTypeValues() {
        assertEquals(0, BackendType.CPU.id)
        assertEquals(1, BackendType.GPU.id)
        assertEquals(2, BackendType.HTP_NPU.id)
    }

    @Test
    fun testBackendStatusInterpretation() {
        val status = BackendStatus(
            backendName = "HTP (Hexagon v73)",
            isHtpAvailable = true,
            isLoaded = true,
            versionString = "2.49.0.260730",
            statusMessage = "HTP backend initialized successfully"
        )
        assertTrue(status.isHtpAvailable)
        assertTrue(status.isLoaded)
        assertEquals("HTP (Hexagon v73)", status.backendName)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.BackendStatusTest"`
Expected: FAIL (unresolved classes `BackendType` and `BackendStatus`).

- [ ] **Step 3: Create Kotlin data classes and Native Bridge interface**

`app/src/main/java/com/example/sdnpu/engine/BackendType.kt`:
```kotlin
package com.example.sdnpu.engine

enum class BackendType(val id: Int) {
    CPU(0),
    GPU(1),
    HTP_NPU(2);

    companion object {
        fun fromId(id: Int): BackendType = values().firstOrNull { it.id == id } ?: CPU
    }
}
```

`app/src/main/java/com/example/sdnpu/engine/BackendStatus.kt`:
```kotlin
package com.example.sdnpu.engine

data class BackendStatus(
    val backendName: String,
    val isHtpAvailable: Boolean,
    val isLoaded: Boolean,
    val versionString: String,
    val statusMessage: String
)
```

`app/src/main/java/com/example/sdnpu/engine/QnnNativeBridge.kt`:
```kotlin
package com.example.sdnpu.engine

object QnnNativeBridge {
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("sdnpu_engine")
            isNativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
        }
    }

    fun isLibraryLoaded(): Boolean = isNativeLoaded

    external fun nativeInitBackend(backendType: Int): Int
    external fun nativeGetBackendStatus(): BackendStatus
    external fun nativeRunBenchmarkDummy(iterations: Int): Float
    external fun nativeReleaseBackend()
}
```

- [ ] **Step 4: Create C++ QNN stub headers and wrapper implementation**

`app/src/main/cpp/include/qnn_types.h`:
```cpp
#pragma once
#include <cstdint>
#include <cstddef>

#define QNN_SUCCESS 0
#define QNN_ERROR_GENERAL 1
#define QNN_ERROR_UNSUPPORTED_FEATURE 2
#define QNN_ERROR_MEM_ALLOC 3
#define QNN_ERROR_INVALID_ARGUMENT 4
#define QNN_ERROR_NOT_INITIALIZED 5

typedef uint32_t Qnn_ErrorHandle_t;
typedef void* Qnn_BackendHandle_t;
typedef void* Qnn_ContextHandle_t;
typedef void* Qnn_GraphHandle_t;

enum QnnBackendTarget {
    BACKEND_CPU = 0,
    BACKEND_GPU = 1,
    BACKEND_HTP_NPU = 2
};
```

`app/src/main/cpp/qnn_wrapper.h`:
```cpp
#pragma once
#include "include/qnn_types.h"
#include <string>

struct QnnRuntimeInfo {
    std::string backendName;
    bool isHtpAvailable;
    bool isLoaded;
    std::string versionString;
    std::string statusMessage;
};

class QnnEngineWrapper {
public:
    static QnnEngineWrapper& getInstance();

    Qnn_ErrorHandle_t initialize(QnnBackendTarget target);
    QnnRuntimeInfo getRuntimeInfo() const;
    float runBenchmarkDummy(int iterations);
    void release();

private:
    QnnEngineWrapper();
    ~QnnEngineWrapper();

    bool checkHtpLibraryPresence();

    QnnBackendTarget currentTarget_;
    bool isInitialized_;
    bool htpLibraryPresent_;
    std::string version_;
};
```

`app/src/main/cpp/qnn_wrapper.cpp`:
```cpp
#include "qnn_wrapper.h"
#include <dlfcn.h>
#include <chrono>
#include <vector>
#include <cmath>

QnnEngineWrapper& QnnEngineWrapper::getInstance() {
    static QnnEngineWrapper instance;
    return instance;
}

QnnEngineWrapper::QnnEngineWrapper()
    : currentTarget_(BACKEND_CPU),
      isInitialized_(false),
      htpLibraryPresent_(false),
      version_("2.49.0.260730") {
    htpLibraryPresent_ = checkHtpLibraryPresence();
}

QnnEngineWrapper::~QnnEngineWrapper() {
    release();
}

bool QnnEngineWrapper::checkHtpLibraryPresence() {
    // Attempt dynamic probe of Qualcomm QNN HTP runtime libraries
    void* handle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        dlclose(handle);
        return true;
    }
    handle = dlopen("libQnnHtpV73Stub.so", RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        dlclose(handle);
        return true;
    }
    return false;
}

Qnn_ErrorHandle_t QnnEngineWrapper::initialize(QnnBackendTarget target) {
    currentTarget_ = target;
    htpLibraryPresent_ = checkHtpLibraryPresence();

    if (target == BACKEND_HTP_NPU && !htpLibraryPresent_) {
        // Fallback: note stub/emulation mode for non-Snapdragon host
        isInitialized_ = true;
        return QNN_SUCCESS;
    }

    isInitialized_ = true;
    return QNN_SUCCESS;
}

QnnRuntimeInfo QnnEngineWrapper::getRuntimeInfo() const {
    QnnRuntimeInfo info;
    info.isLoaded = isInitialized_;
    info.isHtpAvailable = htpLibraryPresent_;
    info.versionString = version_;

    switch (currentTarget_) {
        case BACKEND_HTP_NPU:
            info.backendName = htpLibraryPresent_ ? "Qualcomm QNN HTP (Hexagon v73)" : "QNN HTP (Emulated / Host Stub)";
            info.statusMessage = htpLibraryPresent_ ? "Hardware NPU acceleration active" : "NPU hardware lib not found; using host fallback";
            break;
        case BACKEND_GPU:
            info.backendName = "Qualcomm Adreno GPU (OpenCL)";
            info.statusMessage = "GPU acceleration mode";
            break;
        case BACKEND_CPU:
        default:
            info.backendName = "Reference CPU";
            info.statusMessage = "CPU execution mode";
            break;
    }
    return info;
}

float QnnEngineWrapper::runBenchmarkDummy(int iterations) {
    if (!isInitialized_ || iterations <= 0) return 0.0f;

    auto start = std::chrono::high_resolution_clock::now();
    // Simulate tensor operation workload: 64x64 matrix multiply
    const int N = 64;
    std::vector<float> a(N * N, 1.05f);
    std::vector<float> b(N * N, 0.95f);
    std::vector<float> c(N * N, 0.0f);

    for (int iter = 0; iter < iterations; ++iter) {
        for (int i = 0; i < N; ++i) {
            for (int j = 0; j < N; ++j) {
                float sum = 0.0f;
                for (int k = 0; k < N; ++k) {
                    sum += a[i * N + k] * b[k * N + j];
                }
                c[i * N + j] = std::sin(sum);
            }
        }
    }
    auto end = std::chrono::high_resolution_clock::now();
    std::chrono::duration<float, std::milli> duration = end - start;
    return duration.count();
}

void QnnEngineWrapper::release() {
    isInitialized_ = false;
}
```

`app/src/main/cpp/jni_bridge.cpp`:
```cpp
#include <jni.h>
#include "qnn_wrapper.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeInitBackend(
    JNIEnv* env,
    jobject /* this */,
    jint backendType) {
    return static_cast<jint>(QnnEngineWrapper::getInstance().initialize(
        static_cast<QnnBackendTarget>(backendType)));
}

JNIEXPORT jobject JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeGetBackendStatus(
    JNIEnv* env,
    jobject /* this */) {
    QnnRuntimeInfo info = QnnEngineWrapper::getInstance().getRuntimeInfo();

    jclass statusClass = env->FindClass("com/example/sdnpu/engine/BackendStatus");
    jmethodID constructor = env->GetMethodID(
        statusClass,
        "<init>",
        "(Ljava/lang/String;ZZLjava/lang/String;Ljava/lang/String;)V"
    );

    jstring backendName = env->NewStringUTF(info.backendName.c_str());
    jstring versionString = env->NewStringUTF(info.versionString.c_str());
    jstring statusMessage = env->NewStringUTF(info.statusMessage.c_str());

    jobject result = env->NewObject(
        statusClass,
        constructor,
        backendName,
        static_cast<jboolean>(info.isHtpAvailable),
        static_cast<jboolean>(info.isLoaded),
        versionString,
        statusMessage
    );

    env->DeleteLocalRef(backendName);
    env->DeleteLocalRef(versionString);
    env->DeleteLocalRef(statusMessage);
    env->DeleteLocalRef(statusClass);

    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeRunBenchmarkDummy(
    JNIEnv* env,
    jobject /* this */,
    jint iterations) {
    return QnnEngineWrapper::getInstance().runBenchmarkDummy(iterations);
}

JNIEXPORT void JNICALL
Java_com_example_sdnpu_engine_QnnNativeBridge_nativeReleaseBackend(
    JNIEnv* env,
    jobject /* this */) {
    QnnEngineWrapper::getInstance().release();
}

}
```

`app/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project("sdnpu_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Optional external QAIRT SDK integration if QAIRT_SDK_PATH is set
if(DEFINED QAIRT_SDK_PATH AND EXISTS "${QAIRT_SDK_PATH}")
    message(STATUS "Using external QAIRT SDK from: ${QAIRT_SDK_PATH}")
    include_directories(${QAIRT_SDK_PATH}/include/QNN)
    link_directories(${QAIRT_SDK_PATH}/lib/${ANDROID_ABI})
endif()

add_library(
    sdnpu_engine
    SHARED
    qnn_wrapper.cpp
    jni_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    sdnpu_engine
    ${log-lib}
)
```

- [ ] **Step 5: Run tests and verify build**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.engine.BackendStatusTest"`
Expected: PASS.
Run: `./gradlew :app:assembleDebug`
Expected: `libsdnpu_engine.so` compiled for arm64-v8a and x86_64, build SUCCESSFUL.

- [ ] **Step 6: Commit native backend bridge**

```bash
git add app/src/main/cpp/ app/src/main/java/com/example/sdnpu/engine/ app/src/test/java/com/example/sdnpu/engine/
git commit -m "feat(engine): implement C++ QNN native bridge wrapper and JNI interface"
```

---

### Task 3: Model Manager, Manifest Parser & Resumable Downloader

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/model/ModelManifest.kt`
- Create: `app/src/main/java/com/example/sdnpu/model/DownloadStatus.kt`
- Create: `app/src/main/java/com/example/sdnpu/model/ChecksumVerifier.kt`
- Create: `app/src/main/java/com/example/sdnpu/model/ModelManager.kt`
- Test: `app/src/test/java/com/example/sdnpu/model/ModelManifestTest.kt`
- Test: `app/src/test/java/com/example/sdnpu/model/ChecksumVerifierTest.kt`
- Test: `app/src/test/java/com/example/sdnpu/model/ModelManagerTest.kt`

**Interfaces:**
- Consumes: `OkHttpClient`, Gson from Task 1.
- Produces: `ModelManager` class with:
  - `fetchManifest(baseUrl: String): Result<ModelManifest>`
  - `downloadModel(manifest: ModelManifest, baseUrl: String): Flow<DownloadStatus>`
  - `isModelComplete(modelId: String, manifest: ModelManifest): Boolean`
  - `listLocalModels(): List<String>`
  - `deleteModel(modelId: String): Boolean`

- [ ] **Step 1: Write failing unit tests for Manifest parsing and Checksum verification**

`app/src/test/java/com/example/sdnpu/model/ModelManifestTest.kt`:
```kotlin
package com.example.sdnpu.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelManifestTest {
    @Test
    fun testParseManifestJson() {
        val json = """
        {
          "model_id": "dreamshaper_v8",
          "version": "1.0",
          "components": [
            {"name": "clip_text_encoder", "file": "clip_text_encoder.bin", "sha256": "abcdef1234567890"},
            {"name": "unet", "file": "unet.bin", "sha256": "1234567890abcdef"},
            {"name": "vae_decoder", "file": "vae_decoder.bin", "sha256": "fedcba0987654321"}
          ],
          "qnn_sdk_version": "2.49.0",
          "target_htp": "v73"
        }
        """.trimIndent()

        val manifest = Gson().fromJson(json, ModelManifest::class.java)
        assertNotNull(manifest)
        assertEquals("dreamshaper_v8", manifest.modelId)
        assertEquals("1.0", manifest.version)
        assertEquals(3, manifest.components.size)
        assertEquals("clip_text_encoder.bin", manifest.components[0].file)
        assertEquals("v73", manifest.targetHtp)
    }
}
```

`app/src/test/java/com/example/sdnpu/model/ChecksumVerifierTest.kt`:
```kotlin
package com.example.sdnpu.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChecksumVerifierTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSha256Calculation() {
        val file = tempFolder.newFile("sample.bin")
        file.writeText("hello stable diffusion npu")

        // Expected SHA-256 for "hello stable diffusion npu"
        val expectedSha = "476483cb2b79a556816fa8a89ee1ff1b55909ae6ae6a4d7d8a65507eaecff2ee"
        val actualSha = ChecksumVerifier.calculateSha256(file)

        assertEquals(expectedSha, actualSha)
        assertTrue(ChecksumVerifier.verifyFile(file, expectedSha))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.model.*"`
Expected: FAIL (unresolved classes).

- [ ] **Step 3: Implement `ModelManifest`, `ChecksumVerifier`, and `ModelManager`**

`app/src/main/java/com/example/sdnpu/model/ModelManifest.kt`:
```kotlin
package com.example.sdnpu.model

import com.google.gson.annotations.SerializedName

data class ModelComponent(
    @SerializedName("name") val name: String,
    @SerializedName("file") val file: String,
    @SerializedName("sha256") val sha256: String
)

data class ModelManifest(
    @SerializedName("model_id") val modelId: String,
    @SerializedName("version") val version: String,
    @SerializedName("components") val components: List<ModelComponent>,
    @SerializedName("qnn_sdk_version") val qnnSdkVersion: String,
    @SerializedName("target_htp") val targetHtp: String
)
```

`app/src/main/java/com/example/sdnpu/model/DownloadStatus.kt`:
```kotlin
package com.example.sdnpu.model

sealed class DownloadStatus {
    object Idle : DownloadStatus()
    data class FetchingManifest(val url: String) : DownloadStatus()
    data class DownloadingComponent(
        val componentName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : DownloadStatus()
    data class VerifyingChecksum(val componentName: String) : DownloadStatus()
    data class Completed(val modelId: String, val modelDir: String) : DownloadStatus()
    data class Failed(val reason: String) : DownloadStatus()
}
```

`app/src/main/java/com/example/sdnpu/model/ChecksumVerifier.kt`:
```kotlin
package com.example.sdnpu.model

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ChecksumVerifier {
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyFile(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val computed = calculateSha256(file)
        return computed.equals(expectedSha256, ignoreCase = true)
    }
}
```

`app/src/main/java/com/example/sdnpu/model/ModelManager.kt`:
```kotlin
package com.example.sdnpu.model

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ModelManager(
    private val baseStorageDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    init {
        if (!baseStorageDir.exists()) {
            baseStorageDir.mkdirs()
        }
    }

    suspend fun fetchManifest(baseUrl: String): Result<ModelManifest> = withContext(Dispatchers.IO) {
        val url = if (baseUrl.endsWith("/")) "${baseUrl}manifest.json" else "$baseUrl/manifest.json"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Server returned HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty manifest body"))
                val manifest = gson.fromJson(body, ModelManifest::class.java)
                Result.success(manifest)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun downloadModel(manifest: ModelManifest, baseUrl: String): Flow<DownloadStatus> = flow {
        val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val modelDir = File(baseStorageDir, manifest.modelId)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        // Save manifest locally
        val manifestFile = File(modelDir, "manifest.json")
        manifestFile.writeText(gson.toJson(manifest))

        for (comp in manifest.components) {
            val targetFile = File(modelDir, comp.file)
            val compUrl = "$cleanBaseUrl${comp.file}"

            emit(DownloadStatus.DownloadingComponent(comp.name, 0L, -1L, 0))

            val request = Request.Builder().url(compUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadStatus.Failed("Failed downloading ${comp.name}: HTTP ${response.code}"))
                return@flow
            }

            val responseBody = response.body ?: run {
                emit(DownloadStatus.Failed("Empty response body for ${comp.name}"))
                return@flow
            }

            val totalBytes = responseBody.contentLength()
            var downloadedBytes = 0L

            responseBody.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    var lastReportTime = System.currentTimeMillis()

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 200 || downloadedBytes == totalBytes) {
                            val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                            emit(DownloadStatus.DownloadingComponent(comp.name, downloadedBytes, totalBytes, percent))
                            lastReportTime = now
                        }
                    }
                }
            }

            emit(DownloadStatus.VerifyingChecksum(comp.name))
            val valid = ChecksumVerifier.verifyFile(targetFile, comp.sha256)
            if (!valid) {
                targetFile.delete()
                emit(DownloadStatus.Failed("Checksum verification failed for ${comp.name}"))
                return@flow
            }
        }

        emit(DownloadStatus.Completed(manifest.modelId, modelDir.absolutePath))
    }.flowOn(Dispatchers.IO)

    fun isModelComplete(modelId: String, manifest: ModelManifest): Boolean {
        val modelDir = File(baseStorageDir, modelId)
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        for (comp in manifest.components) {
            val file = File(modelDir, comp.file)
            if (!ChecksumVerifier.verifyFile(file, comp.sha256)) {
                return false
            }
        }
        return true
    }

    fun listLocalModels(): List<String> {
        return baseStorageDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
    }

    fun deleteModel(modelId: String): Boolean {
        val modelDir = File(baseStorageDir, modelId)
        return if (modelDir.exists()) modelDir.deleteRecursively() else false
    }
}
```

- [ ] **Step 4: Write MockWebServer unit test for end-to-end download flow**

`app/src/test/java/com/example/sdnpu/model/ModelManagerTest.kt`:
```kotlin
package com.example.sdnpu.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var modelManager: ModelManager
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        modelsDir = tempFolder.newFolder("models")
        modelManager = ModelManager(modelsDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testFetchManifestAndDownloadModel() = runBlocking {
        val testContent = "sample model tensor weights"
        val expectedHash = ChecksumVerifier.calculateSha256(tempFolder.newFile().apply { writeText(testContent) })

        val manifestJson = """
        {
          "model_id": "test_model",
          "version": "1.0",
          "components": [
            {"name": "unet", "file": "unet.bin", "sha256": "$expectedHash"}
          ],
          "qnn_sdk_version": "2.49.0",
          "target_htp": "v73"
        }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(manifestJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(testContent))

        val baseUrl = server.url("/").toString()
        val manifestResult = modelManager.fetchManifest(baseUrl)
        assertTrue(manifestResult.isSuccess)
        val manifest = manifestResult.getOrThrow()

        val statuses = modelManager.downloadModel(manifest, baseUrl).toList()
        assertTrue(statuses.any { it is DownloadStatus.Completed })
        assertTrue(modelManager.isModelComplete("test_model", manifest))
        assertEquals(listOf("test_model"), modelManager.listLocalModels())
    }
}
```

- [ ] **Step 5: Run tests and verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.model.*"`
Expected: ALL PASS.

- [ ] **Step 6: Commit ModelManager and tests**

```bash
git add app/src/main/java/com/example/sdnpu/model/ app/src/test/java/com/example/sdnpu/model/
git commit -m "feat(model): implement ModelManager with OkHttp, SHA-256 verification and tests"
```

---

### Task 4: Pipeline Data Layer & Generation Parameters

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/pipeline/GenerationParams.kt`
- Create: `app/src/main/java/com/example/sdnpu/pipeline/SamplerType.kt`
- Create: `app/src/main/java/com/example/sdnpu/pipeline/UpscaleMode.kt`
- Create: `app/src/main/java/com/example/sdnpu/pipeline/PipelineState.kt`
- Create: `app/src/main/java/com/example/sdnpu/pipeline/PipelineManager.kt`
- Test: `app/src/test/java/com/example/sdnpu/pipeline/GenerationParamsTest.kt`

**Interfaces:**
- Consumes: `BackendStatus`, `ModelManager` from Tasks 2 & 3.
- Produces: `PipelineManager` with `generate(params: GenerationParams): Flow<PipelineState>` orchestrating parameters validation, progress updates, and completion.

- [ ] **Step 1: Write failing unit test for parameter validation**

`app/src/test/java/com/example/sdnpu/pipeline/GenerationParamsTest.kt`:
```kotlin
package com.example.sdnpu.pipeline

import org.junit.Assert.*
import org.junit.Test

class GenerationParamsTest {
    @Test
    fun testDefaultParamsAreValid() {
        val params = GenerationParams(
            prompt = "a serene mountain lake at sunrise, highly detailed"
        )
        val validation = params.validate()
        assertTrue(validation.isValid)
        assertEquals(20, params.steps)
        assertEquals(7.0f, params.cfgScale, 0.001f)
        assertEquals(UpscaleMode.OFF, params.upscaleMode)
        assertEquals(SamplerType.EULER_A, params.sampler)
    }

    @Test
    fun testEmptyPromptFailsValidation() {
        val params = GenerationParams(prompt = "   ")
        val validation = params.validate()
        assertFalse(validation.isValid)
        assertEquals("Prompt cannot be empty", validation.errorMessage)
    }

    @Test
    fun testInvalidStepRangeFailsValidation() {
        val params = GenerationParams(prompt = "cat", steps = 5)
        val validation = params.validate()
        assertFalse(validation.isValid)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.pipeline.GenerationParamsTest"`
Expected: FAIL (unresolved symbols).

- [ ] **Step 3: Implement Enums, GenerationParams, and PipelineManager**

`app/src/main/java/com/example/sdnpu/pipeline/SamplerType.kt`:
```kotlin
package com.example.sdnpu.pipeline

enum class SamplerType(val displayName: String) {
    EULER_A("Euler a"),
    DPM_2M_KARRAS("DPM++ 2M Karras"),
    DPM_SDE_KARRAS("DPM++ SDE Karras"),
    DDIM("DDIM")
}
```

`app/src/main/java/com/example/sdnpu/pipeline/UpscaleMode.kt`:
```kotlin
package com.example.sdnpu.pipeline

enum class UpscaleMode(val scale: Int, val displayName: String) {
    OFF(1, "Off"),
    X2(2, "RealESRGAN 2x"),
    X4(4, "RealESRGAN 4x")
}
```

`app/src/main/java/com/example/sdnpu/pipeline/GenerationParams.kt`:
```kotlin
package com.example.sdnpu.pipeline

data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

data class GenerationParams(
    val prompt: String,
    val negativePrompt: String = "",
    val modelId: String = "dreamshaper_v8",
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long? = null,
    val sampler: SamplerType = SamplerType.EULER_A,
    val batchCount: Int = 1,
    val upscaleMode: UpscaleMode = UpscaleMode.OFF
) {
    fun validate(): ValidationResult {
        if (prompt.trim().isEmpty()) {
            return ValidationResult(false, "Prompt cannot be empty")
        }
        if (steps !in 10..50) {
            return ValidationResult(false, "Steps must be between 10 and 50")
        }
        if (cfgScale !in 1.0f..20.0f) {
            return ValidationResult(false, "CFG scale must be between 1.0 and 20.0")
        }
        if (batchCount !in 1..4) {
            return ValidationResult(false, "Batch count must be between 1 and 4")
        }
        return ValidationResult(true)
    }
}
```

`app/src/main/java/com/example/sdnpu/pipeline/PipelineState.kt`:
```kotlin
package com.example.sdnpu.pipeline

sealed class PipelineState {
    object Idle : PipelineState()
    data class LoadingModel(val modelId: String) : PipelineState()
    data class Generating(val step: Int, val totalSteps: Int, val message: String) : PipelineState()
    data class Upscaling(val scale: Int, val progressPercent: Int) : PipelineState()
    data class Completed(val message: String, val executionTimeMs: Long) : PipelineState()
    data class Error(val error: String) : PipelineState()
}
```

`app/src/main/java/com/example/sdnpu/pipeline/PipelineManager.kt`:
```kotlin
package com.example.sdnpu.pipeline

import com.example.sdnpu.engine.QnnNativeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class PipelineManager {
    fun runGeneration(params: GenerationParams): Flow<PipelineState> = flow {
        val validation = params.validate()
        if (!validation.isValid) {
            emit(PipelineState.Error(validation.errorMessage ?: "Invalid parameters"))
            return@flow
        }

        val startTime = System.currentTimeMillis()
        emit(PipelineState.LoadingModel(params.modelId))
        delay(100) // Context loading delay

        for (step in 1..params.steps) {
            emit(PipelineState.Generating(step, params.steps, "Denoising step $step/${params.steps}"))
            delay(30) // Simulated step
        }

        if (params.upscaleMode != UpscaleMode.OFF) {
            emit(PipelineState.Upscaling(params.upscaleMode.scale, 0))
            delay(150)
            emit(PipelineState.Upscaling(params.upscaleMode.scale, 100))
        }

        val totalTime = System.currentTimeMillis() - startTime
        emit(PipelineState.Completed("Generation finished successfully", totalTime))
    }
}
```

- [ ] **Step 4: Run tests and verify PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.sdnpu.pipeline.*"`
Expected: ALL PASS.

- [ ] **Step 5: Commit pipeline data layer**

```bash
git add app/src/main/java/com/example/sdnpu/pipeline/ app/src/test/java/com/example/sdnpu/pipeline/
git commit -m "feat(pipeline): add GenerationParams, validation rules, and PipelineManager flow"
```

---

### Task 5: Jetpack Compose UI Skeleton & Navigation

**Files:**
- Create: `app/src/main/java/com/example/sdnpu/ui/theme/Color.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/theme/Type.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/navigation/NavTab.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/screens/GalleryScreen.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/screens/SettingsScreen.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/screens/ModelDownloadDialog.kt`
- Create: `app/src/main/java/com/example/sdnpu/ui/MainViewModel.kt`
- Create: `app/src/main/java/com/example/sdnpu/MainActivity.kt`

**Interfaces:**
- Consumes: `GenerationParams`, `PipelineManager`, `ModelManager`, `QnnNativeBridge` from Tasks 2-4.
- Produces: Interactive Material 3 UI with 3-tab navigation, generation controls, model download dialog, and backend status inspector.

- [ ] **Step 1: Create Theme files**

`app/src/main/java/com/example/sdnpu/ui/theme/Color.kt`:
```kotlin
package com.example.sdnpu.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val DarkBg = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val PrimaryPurple = Color(0xFFBB86FC)
val SecondaryTeal = Color(0xFF03DAC6)
```

`app/src/main/java/com/example/sdnpu/ui/theme/Type.kt`:
```kotlin
package com.example.sdnpu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)
```

`app/src/main/java/com/example/sdnpu/ui/theme/Theme.kt`:
```kotlin
package com.example.sdnpu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    secondary = SecondaryTeal,
    background = DarkBg,
    surface = DarkSurface
)

@Composable
fun SdnpuTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
```

- [ ] **Step 2: Create Navigation tab definitions and MainViewModel**

`app/src/main/java/com/example/sdnpu/ui/navigation/NavTab.kt`:
```kotlin
package com.example.sdnpu.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavTab(val title: String, val icon: ImageVector) {
    GENERATE("Generate", Icons.Default.ElectricBolt),
    GALLERY("Gallery", Icons.Default.Collections),
    SETTINGS("Settings", Icons.Default.Settings)
}
```

`app/src/main/java/com/example/sdnpu/ui/MainViewModel.kt`:
```kotlin
package com.example.sdnpu.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sdnpu.engine.BackendStatus
import com.example.sdnpu.engine.BackendType
import com.example.sdnpu.engine.QnnNativeBridge
import com.example.sdnpu.model.DownloadStatus
import com.example.sdnpu.model.ModelManager
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineManager
import com.example.sdnpu.pipeline.PipelineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelManager = ModelManager(File(application.filesDir, "models"))
    private val pipelineManager = PipelineManager()

    val params = MutableStateFlow(GenerationParams(prompt = ""))
    val pipelineState: MutableStateFlow<PipelineState> = MutableStateFlow(PipelineState.Idle)
    val downloadStatus: MutableStateFlow<DownloadStatus> = MutableStateFlow(DownloadStatus.Idle)
    val backendStatus = MutableStateFlow(
        BackendStatus("Reference CPU", false, true, "2.49.0.260730", "Ready")
    )
    val localModels = MutableStateFlow<List<String>>(emptyList())

    init {
        refreshBackend()
        refreshLocalModels()
    }

    fun refreshBackend() {
        if (QnnNativeBridge.isLibraryLoaded()) {
            QnnNativeBridge.nativeInitBackend(BackendType.HTP_NPU.id)
            backendStatus.value = QnnNativeBridge.nativeGetBackendStatus()
        }
    }

    fun refreshLocalModels() {
        localModels.value = modelManager.listLocalModels()
    }

    fun startGeneration() {
        viewModelScope.launch {
            pipelineManager.runGeneration(params.value).collect { state ->
                pipelineState.value = state
            }
        }
    }

    fun downloadModelFromUrl(url: String) {
        viewModelScope.launch {
            val manifestRes = modelManager.fetchManifest(url)
            if (manifestRes.isFailure) {
                downloadStatus.value = DownloadStatus.Failed("Cannot fetch manifest: ${manifestRes.exceptionOrNull()?.message}")
                return@launch
            }
            val manifest = manifestRes.getOrThrow()
            modelManager.downloadModel(manifest, url).collect { status ->
                downloadStatus.value = status
                if (status is DownloadStatus.Completed) {
                    refreshLocalModels()
                }
            }
        }
    }
}
```

- [ ] **Step 3: Create Compose Screens**

`app/src/main/java/com/example/sdnpu/ui/screens/GenerateScreen.kt`:
```kotlin
package com.example.sdnpu.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.pipeline.GenerationParams
import com.example.sdnpu.pipeline.PipelineState
import com.example.sdnpu.pipeline.SamplerType
import com.example.sdnpu.pipeline.UpscaleMode
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    params: GenerationParams,
    pipelineState: PipelineState,
    localModels: List<String>,
    onParamsChange: (GenerationParams) -> Unit,
    onGenerate: () -> Unit
) {
    var showNegativePrompt by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Stable Diffusion on NPU", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = params.prompt,
            onValueChange = { onParamsChange(params.copy(prompt = it)) },
            label = { Text("Prompt") },
            placeholder = { Text("A serene Japanese garden with cherry blossoms, 8k...") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )

        TextButton(onClick = { showNegativePrompt = !showNegativePrompt }) {
            Text(if (showNegativePrompt) "Hide Negative Prompt" else "+ Add Negative Prompt")
        }

        AnimatedVisibility(visible = showNegativePrompt) {
            OutlinedTextField(
                value = params.negativePrompt,
                onValueChange = { onParamsChange(params.copy(negativePrompt = it)) },
                label = { Text("Negative Prompt") },
                placeholder = { Text("blurry, low quality, distorted") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text("Model: ${params.modelId}", style = MaterialTheme.typography.bodyMedium)

        Text("Steps: ${params.steps}")
        Slider(
            value = params.steps.toFloat(),
            onValueChange = { onParamsChange(params.copy(steps = it.toInt())) },
            valueRange = 10f..50f,
            steps = 40
        )

        Text("CFG Scale: ${"%.1f".format(params.cfgScale)}")
        Slider(
            value = params.cfgScale,
            onValueChange = { onParamsChange(params.copy(cfgScale = it)) },
            valueRange = 1.0f..20.0f
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = params.seed?.toString() ?: "",
                onValueChange = {
                    val s = it.toLongOrNull()
                    onParamsChange(params.copy(seed = s))
                },
                label = { Text("Seed (empty = random)") },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                onParamsChange(params.copy(seed = Random.nextLong(0, 1000000)))
            }) {
                Icon(Icons.Default.Casino, contentDescription = "Randomize Seed")
            }
        }

        Text("RealESRGAN Upscale:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpscaleMode.values().forEach { mode ->
                FilterChip(
                    selected = params.upscaleMode == mode,
                    onClick = { onParamsChange(params.copy(upscaleMode = mode)) },
                    label = { Text(mode.displayName) }
                )
            }
        }

        Button(
            onClick = onGenerate,
            enabled = pipelineState !is PipelineState.Generating && pipelineState !is PipelineState.Upscaling,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            when (pipelineState) {
                is PipelineState.Generating -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating (${pipelineState.step}/${pipelineState.totalSteps})...")
                }
                is PipelineState.Upscaling -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Upscaling ${pipelineState.scale}x...")
                }
                else -> Text("Generate Image")
            }
        }

        when (pipelineState) {
            is PipelineState.Completed -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(
                        text = "Success! Time: ${pipelineState.executionTimeMs} ms",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            is PipelineState.Error -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = "Error: ${pipelineState.error}",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            else -> {}
        }
    }
}
```

`app/src/main/java/com/example/sdnpu/ui/screens/GalleryScreen.kt`:
```kotlin
package com.example.sdnpu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GalleryScreen() {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Generated Images", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("No images generated yet. Run a prompt from Generate tab!", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

`app/src/main/java/com/example/sdnpu/ui/screens/SettingsScreen.kt`:
```kotlin
package com.example.sdnpu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.engine.BackendStatus

@Composable
fun SettingsScreen(
    backendStatus: BackendStatus,
    localModels: List<String>,
    onOpenDownloadDialog: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Hardware & Engine Status", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Active Backend: ${backendStatus.backendName}", style = MaterialTheme.typography.titleMedium)
                Text("HTP / NPU Available: ${if (backendStatus.isHtpAvailable) "Yes (Hexagon v73)" else "No / Emulated"}")
                Text("QNN SDK Version: ${backendStatus.versionString}")
                Text("Status: ${backendStatus.statusMessage}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Divider()

        Text("Model Management", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onOpenDownloadDialog, modifier = Modifier.fillMaxWidth()) {
            Text("Download Model from Local Server")
        }

        Text("Downloaded Models (${localModels.size}):", style = MaterialTheme.typography.titleSmall)
        if (localModels.isEmpty()) {
            Text("None found in internal storage.", style = MaterialTheme.typography.bodyMedium)
        } else {
            localModels.forEach { modelName ->
                ElevatedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(modelName, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}
```

`app/src/main/java/com/example/sdnpu/ui/screens/ModelDownloadDialog.kt`:
```kotlin
package com.example.sdnpu.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdnpu.model.DownloadStatus

@Composable
fun ModelDownloadDialog(
    status: DownloadStatus,
    onDismiss: () -> Unit,
    onStartDownload: (String) -> Unit
) {
    var serverUrl by remember { mutableStateOf("http://192.168.1.100:8080/models/dreamshaper_v8/") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the URL of your local HTTP model server:")
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    modifier = Modifier.fillMaxWidth()
                )

                when (status) {
                    is DownloadStatus.DownloadingComponent -> {
                        Text("Downloading ${status.componentName} (${status.progressPercent}%)")
                        LinearProgressIndicator(
                            progress = { status.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is DownloadStatus.VerifyingChecksum -> {
                        Text("Verifying SHA-256 for ${status.componentName}...")
                    }
                    is DownloadStatus.Completed -> {
                        Text("Download and verification complete!", color = MaterialTheme.colorScheme.primary)
                    }
                    is DownloadStatus.Failed -> {
                        Text("Error: ${status.reason}", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStartDownload(serverUrl) },
                enabled = status !is DownloadStatus.DownloadingComponent
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
```

- [ ] **Step 4: Create MainActivity**

`app/src/main/java/com/example/sdnpu/MainActivity.kt`:
```kotlin
package com.example.sdnpu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.sdnpu.ui.MainViewModel
import com.example.sdnpu.ui.navigation.NavTab
import com.example.sdnpu.ui.screens.GalleryScreen
import com.example.sdnpu.ui.screens.GenerateScreen
import com.example.sdnpu.ui.screens.ModelDownloadDialog
import com.example.sdnpu.ui.screens.SettingsScreen
import com.example.sdnpu.ui.theme.SdnpuTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SdnpuTheme {
                var currentTab by remember { mutableStateOf(NavTab.GENERATE) }
                var showDownloadDialog by remember { mutableStateOf(false) }

                val params by viewModel.params.collectAsState()
                val pipelineState by viewModel.pipelineState.collectAsState()
                val backendStatus by viewModel.backendStatus.collectAsState()
                val localModels by viewModel.localModels.collectAsState()
                val downloadStatus by viewModel.downloadStatus.collectAsState()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavTab.values().forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = { Icon(tab.icon, contentDescription = tab.title) },
                                    label = { Text(tab.title) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            NavTab.GENERATE -> GenerateScreen(
                                params = params,
                                pipelineState = pipelineState,
                                localModels = localModels,
                                onParamsChange = { viewModel.params.value = it },
                                onGenerate = { viewModel.startGeneration() }
                            )
                            NavTab.GALLERY -> GalleryScreen()
                            NavTab.SETTINGS -> SettingsScreen(
                                backendStatus = backendStatus,
                                localModels = localModels,
                                onOpenDownloadDialog = { showDownloadDialog = true }
                            )
                        }

                        if (showDownloadDialog) {
                            ModelDownloadDialog(
                                status = downloadStatus,
                                onDismiss = { showDownloadDialog = false },
                                onStartDownload = { url -> viewModel.downloadModelFromUrl(url) }
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Verify build with Compose**

Run: `./gradlew :app:assembleDebug`
Expected: Build succeeds, APK generated.

- [ ] **Step 6: Commit Compose UI layer**

```bash
git add app/src/main/java/com/example/sdnpu/ui/ app/src/main/java/com/example/sdnpu/MainActivity.kt
git commit -m "feat(ui): implement Jetpack Compose UI skeleton with Material 3 and navigation"
```

---

### Task 6: End-to-End Build & Test Verification

**Files:**
- Modify: `README.md` (create project overview, build instructions, and roadmap)

**Interfaces:**
- Consumes: All modules from Tasks 1-5.
- Produces: Complete passing test suite and verified debug APK.

- [ ] **Step 1: Run complete test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests across engine, model, and pipeline pass.

- [ ] **Step 2: Build assembleDebug APK**

Run: `./gradlew assembleDebug`
Expected: `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 3: Document project setup in `README.md`**

Create `README.md` detailing:
- Project architecture and targets
- Setup prerequisites (Android SDK, NDK r28, CMake 3.22)
- How to build and run unit tests
- Local model server serving instructions
- Next phase roadmap (Phase 2: SD Engine)

- [ ] **Step 4: Commit final Phase 1 deliverable**

```bash
git add README.md
git commit -m "docs: add project README and verify end-to-end Phase 1 build"
```
