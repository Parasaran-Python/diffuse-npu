# CI/CD Pipeline & Open Source Readiness Design Spec

## Goal
Establish a robust, automated continuous integration and release pipeline via GitHub Actions, and prepare the `diffuse-npu` repository for public open-source release with complete licensing, comprehensive attributions and credits, community governance standards, and PR delivery.

---

## 1. Subsystem A: GitHub Actions CI/CD Workflows (`.github/workflows/`)

### 1.1 `ci.yml` — Pull Request & Main Branch CI
- **Triggers**:
  - `pull_request` targeting `master`.
  - `push` to `master`.
- **Runner**: `ubuntu-latest`.
- **Environment & Tooling**:
  - Java: OpenJDK 21 via `actions/setup-java` (distribution: `temurin`).
  - Android SDK: Command-line tools, Android Platform 35 (`android-35`), Build-Tools `35.0.0`.
  - Android NDK: `28.2.13676358` (matching `app/build.gradle.kts`).
  - CMake: `3.22.1` (matching `app/build.gradle.kts`).
- **Pipeline Stages**:
  1. **Checkout**: `actions/checkout@v4` with `fetch-depth: 0`.
  2. **Gradle Wrapper Validation**: `gradle/actions/wrapper-validation@v4` to verify binary integrity.
  3. **Java Setup**: JDK 21 configured with Gradle caching (`cache: gradle`).
  4. **Android SDK & NDK Cache / Setup**: Set up NDK `28.2.13676358` and CMake `3.22.1` via `android-actions/setup-android@v3` or standard SDK manager.
  5. **Static Code Analysis**: `./gradlew lintDebug --stacktrace` to ensure zero fatal lint regressions.
  6. **Unit Tests**: `./gradlew testDebugUnitTest --stacktrace` to execute all 121 unit tests.
  7. **Debug Compilation & Assembly**: `./gradlew assembleDebug --stacktrace` to build native C++ binaries for `arm64-v8a` and `x86_64` and package the debug APK.
  8. **Artifact Uploads**:
     - Debug APK (`app-debug.apk`) via `actions/upload-artifact@v4`.
     - Test result XMLs / HTML reports on test failure or success.
     - Lint report HTML on lint failure.

### 1.2 `release.yml` — Production Release & Artifact Publishing
- **Triggers**:
  - `push` on tags matching `v*` (e.g. `v1.0.0`).
  - `workflow_dispatch` (manual dispatch with optional version input).
- **Pipeline Stages**:
  1. **Checkout & Environment Setup**: JDK 21, Android SDK 35, NDK `28.2.13676358`, CMake `3.22.1`.
  2. **Release Compilation**:
     - `./gradlew assembleRelease bundleRelease --stacktrace`
  3. **Binary Packaging**:
     - Generates `app-release.apk` (or minified APK with debug signing fallback if production secrets not configured).
     - Generates `app-release.aab` (Android App Bundle).
  4. **GitHub Release Publication**:
     - `softprops/action-gh-release@v2` or `gh release create`.
     - Auto-generates changelog / release notes.
     - Attaches APK and AAB binaries to the release.

### 1.3 `security.yml` — Security & CodeQL Analysis
- **Triggers**:
  - `schedule`: Weekly scan (Sunday midnight UTC).
  - `push` to `master` and `pull_request` touching dependencies or core engine.
- **Pipeline Stages**:
  1. **Dependency Vulnerability Review**: GitHub Dependency Review action for PRs.
  2. **CodeQL Static Analysis**: Multi-language static vulnerability scanning for Kotlin/Java and C/C++.

---

## 2. Subsystem B: Open Source Licensing, Attributions & README Updates

### 2.1 Repository License (`LICENSE`)
- Full text of the **Apache License, Version 2.0**.
- Explicit copyright notice:
  ```
  Copyright 2026 Parasaran Vedanarayanan

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0
  ```

### 2.2 `README.md` Modernization & Badges
- **Status Badges**:
  - CI Workflow Status (`[![CI](https://github.com/Parasaran-Python/diffuse-npu/actions/workflows/ci.yml/badge.svg)](https://github.com/Parasaran-Python/diffuse-npu/actions/workflows/ci.yml)`)
  - License (`[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)`)
  - Platform (`[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)`)
  - Target API (`[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg)](https://android-arsenal.com/api?level=26)`)
  - Kotlin (`[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)`)
  - NPU Acceleration (`[![NPU](https://img.shields.io/badge/Qualcomm-Hexagon%20HTP-orange.svg)](https://developer.qualcomm.com/software/qualcomm-neural-processing-sdk)`)
- **Credits & Acknowledgments Section**:
  - **Qualcomm Technologies**: Qualcomm Neural Processing SDK (QNN) & Hexagon v73 HTP NPU runtime.
  - **Microsoft**: ONNX Runtime and ONNX Runtime Mobile for on-device inference and QNN execution provider.
  - **Lykon, RunwayML, and Stability AI**: DreamShaper v8 and Stable Diffusion latent diffusion model architectures.
  - **Xintao Wang et al.**: Real-ESRGAN super-resolution deep neural network.
  - **Hugging Face**: Diffusers library and community ONNX quantized checkpoints.
  - **Google & Android Open Source Project**: Jetpack Compose, Material 3, Kotlin Coroutines, and Android Studio tooling.
- **Third-Party Model License Notice**:
  - DreamShaper / Stable Diffusion: CreativeML OpenRAIL-M.
  - Real-ESRGAN: BSD 3-Clause License.
  - ONNX Runtime: MIT / Apache 2.0 License.

---

## 3. Subsystem C: Community Health & Contribution Standards

### 3.1 `CONTRIBUTING.md`
- Prerequisites: Android Studio Ladybug/Meerkat, JDK 21, Android SDK 35, NDK `28.2.13676358`, CMake `3.22.1`.
- Project build and test instructions (`./gradlew test`, `./gradlew assembleDebug`).
- Architecture guidelines: Kotlin coroutine safety, native C++ memory discipline, zero JNI memory leaks.
- Pull request submission checklist and branch naming standards.

### 3.2 `CODE_OF_CONDUCT.md`
- Contributor Covenant Version 2.1 standard text.

### 3.3 `SECURITY.md`
- Vulnerability reporting procedures, supported versions, and coordinated disclosure timeline.

### 3.4 GitHub Issue & PR Templates
- `.github/ISSUE_TEMPLATE/bug_report.md`: Structured template capturing device model, Android version, NPU chipset, logs.
- `.github/ISSUE_TEMPLATE/feature_request.md`: Structured template for proposals, new model requests, or UI enhancements.
- `.github/pull_request_template.md`: Description, motivation, verification checklist, device testing status.

---

## 4. Subsystem D: Git Branching & PR Delivery

- **Branch Name**: `feature/ci-and-open-source-readiness`.
- **Commit History**: Clean atomic conventional commits (`ci: ...`, `docs: ...`).
- **Push Target**: `origin/feature/ci-and-open-source-readiness`.
- **Pull Request Creation**: Use GitHub CLI (`gh pr create`) targeting `master`.
- **Merge Ownership**: Retained strictly by the user (`leave merge to me`).
