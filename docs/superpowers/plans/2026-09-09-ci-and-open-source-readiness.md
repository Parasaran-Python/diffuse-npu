# CI/CD Pipeline & Open Source Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish GitHub Actions CI/CD workflows for PR validation, release builds, and security scans, and prepare the `diffuse-npu` repository for open-source release with full Apache-2.0 licensing, comprehensive credits, community governance standards, and a Pull Request to `master`.

**Architecture:** Modular GitHub Actions workflows in `.github/workflows/` targeting Ubuntu with JDK 21, Android SDK 35, NDK `28.2.13676358`, and CMake `3.22.1`. Standard open-source governance suite (Apache-2.0 LICENSE, CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, Issue/PR templates) with extensive attributions for Qualcomm QNN, ONNX Runtime Mobile, Lykon/Stability AI, and Real-ESRGAN in `README.md`. Delivered via a dedicated feature branch with atomic commits and a PR raised via `gh`.

**Tech Stack:** GitHub Actions (YAML), OpenJDK 21, Android Gradle Plugin 9.3.2, Gradle 9.5.0, Android NDK 28.2.13676358, CMake 3.22.1, Markdown, Git, GitHub CLI (`gh`).

**Spec:** `docs/superpowers/specs/2026-09-09-ci-and-open-source-readiness-design.md`

## Global Constraints
- Target Android SDK: 35 (`compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`).
- NDK Version: `28.2.13676358`.
- CMake Version: `3.22.1`.
- Java Version: JDK 21.
- License: Apache License, Version 2.0. Copyright (c) 2026 Parasaran Vedanarayanan.
- Git Branch: `feature/ci-and-open-source-readiness`.
- Do NOT merge into master directly; raise a Pull Request targeting master and leave merging to the user.

---

### Task 1: GitHub Actions PR & Main Branch CI Workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `./gradlew lintDebug`, `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`
- Produces: GitHub Actions CI status check, debug APK artifact, test report artifacts

- [ ] **Step 1: Create `.github/workflows/ci.yml`**

Create the `.github/workflows/ci.yml` file with:
```yaml
name: CI

on:
  push:
    branches: [ master ]
  pull_request:
    branches: [ master ]

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-and-test:
    name: Build, Lint & Test
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Validate Gradle Wrapper
        uses: gradle/actions/wrapper-validation@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Set up Android SDK & Tools
        uses: android-actions/setup-android@v3

      - name: Install Android NDK & CMake
        run: |
          sdkmanager --install \
            "ndk;28.2.13676358" \
            "cmake;3.22.1" \
            "platforms;android-35" \
            "build-tools;35.0.0"

      - name: Cache Gradle Dependencies
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties', 'gradle/libs.versions.toml') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Run Android Lint (Debug)
        run: ./gradlew lintDebug --stacktrace

      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest --stacktrace

      - name: Assemble Debug APK
        run: ./gradlew assembleDebug --stacktrace

      - name: Upload Debug APK Artifact
        if: success()
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: warn

      - name: Upload Unit Test Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-reports
          path: app/build/reports/tests/testDebugUnitTest/
          if-no-files-found: ignore

      - name: Upload Lint Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-reports
          path: app/build/reports/lint-results-debug.html
          if-no-files-found: ignore
```

- [ ] **Step 2: Validate YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`
Expected: No syntax errors.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add main and pull request verification workflow"
```

---

### Task 2: GitHub Actions Release & Artifact Workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: Git tag `v*` or `workflow_dispatch`, `./gradlew assembleRelease bundleRelease`
- Produces: GitHub Release with attached `app-release.apk` and `app-release.aab`

- [ ] **Step 1: Create `.github/workflows/release.yml`**

Create the `.github/workflows/release.yml` file with:
```yaml
name: Release

on:
  push:
    tags:
      - 'v*'
  workflow_dispatch:
    inputs:
      release_tag:
        description: 'Release tag (e.g. v1.0.0)'
        required: true
        default: 'v1.0.0'

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: false

jobs:
  build-release:
    name: Build & Package Release
    runs-on: ubuntu-latest
    permissions:
      contents: write
    timeout-minutes: 45

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Validate Gradle Wrapper
        uses: gradle/actions/wrapper-validation@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Set up Android SDK & Tools
        uses: android-actions/setup-android@v3

      - name: Install Android NDK & CMake
        run: |
          sdkmanager --install \
            "ndk;28.2.13676358" \
            "cmake;3.22.1" \
            "platforms;android-35" \
            "build-tools;35.0.0"

      - name: Cache Gradle Dependencies
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties', 'gradle/libs.versions.toml') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Assemble Release APK & Bundle
        run: ./gradlew assembleRelease bundleRelease --stacktrace

      - name: Stage Release Assets
        run: |
          mkdir -p release-assets
          cp app/build/outputs/apk/release/app-release.apk release-assets/diffuse-npu-release.apk || cp app/build/outputs/apk/release/*.apk release-assets/
          cp app/build/outputs/bundle/release/app-release.aab release-assets/diffuse-npu-release.aab || cp app/build/outputs/bundle/release/*.aab release-assets/
          ls -lh release-assets/

      - name: Determine Tag
        id: tag_info
        run: |
          if [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
            echo "tag=${{ github.event.inputs.release_tag }}" >> $GITHUB_OUTPUT
          else
            echo "tag=${{ github.ref_name }}" >> $GITHUB_OUTPUT
          fi

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ steps.tag_info.outputs.tag }}
          name: Release ${{ steps.tag_info.outputs.tag }}
          files: release-assets/*
          generate_release_notes: true
          draft: false
          prerelease: false
```

- [ ] **Step 2: Validate YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"`
Expected: No syntax errors.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: add automated release build and publishing workflow"
```

---

### Task 3: Security & CodeQL Analysis Workflow

**Files:**
- Create: `.github/workflows/security.yml`

**Interfaces:**
- Consumes: Weekly schedule, push to `master`, PRs
- Produces: GitHub Security CodeQL alerts, dependency review

- [ ] **Step 1: Create `.github/workflows/security.yml`**

Create the `.github/workflows/security.yml` file with:
```yaml
name: Security Analysis

on:
  push:
    branches: [ master ]
  pull_request:
    branches: [ master ]
  schedule:
    - cron: '0 0 * * 0' # Weekly at 00:00 UTC on Sunday

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  dependency-review:
    name: Dependency Review
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: read
    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Dependency Review
        uses: actions/dependency-review-action@v4
        continue-on-error: true

  codeql:
    name: CodeQL Static Analysis
    runs-on: ubuntu-latest
    permissions:
      actions: read
      contents: read
      security-events: write

    strategy:
      fail-fast: false
      matrix:
        language: [ 'java-kotlin', 'c-cpp' ]

    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Initialize CodeQL
        uses: github/codeql-action/init@v3
        with:
          languages: ${{ matrix.language }}

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Build Code for CodeQL
        run: ./gradlew assembleDebug -x lint -x test

      - name: Perform CodeQL Analysis
        uses: github/codeql-action/analyze@v3
        with:
          category: "/language:${{ matrix.language }}"
```

- [ ] **Step 2: Validate YAML syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/security.yml'))"`
Expected: No syntax errors.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/security.yml
git commit -m "ci: add CodeQL static analysis and dependency review workflow"
```

---

### Task 4: Open Source License (`LICENSE`)

**Files:**
- Create: `LICENSE`

**Interfaces:**
- Produces: Official Apache License 2.0 with copyright notice for Parasaran Vedanarayanan (2026).

- [ ] **Step 1: Create `LICENSE`**

Write standard Apache License Version 2.0 with copyright notice:
```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION
   ...
   Copyright 2026 Parasaran Vedanarayanan
```

- [ ] **Step 2: Verify LICENSE exists and contains copyright header**

Run: `grep -q "Parasaran Vedanarayanan" LICENSE && echo "License verified"`
Expected: "License verified"

- [ ] **Step 3: Commit**

```bash
git add LICENSE
git commit -m "docs: add Apache License 2.0"
```

---

### Task 5: Community Guidelines & Governance

**Files:**
- Create: `CONTRIBUTING.md`
- Create: `CODE_OF_CONDUCT.md`
- Create: `SECURITY.md`

**Interfaces:**
- Produces: Contributor onboarding documentation, contributor covenant code of conduct, vulnerability disclosure policy.

- [ ] **Step 1: Create `CONTRIBUTING.md`**

Create `CONTRIBUTING.md` outlining development environment setup (Android Studio, JDK 21, Android SDK 35, NDK 28, CMake 3.22), code conventions (Kotlin, Compose, C++17), running tests, and PR procedures.

- [ ] **Step 2: Create `CODE_OF_CONDUCT.md`**

Create `CODE_OF_CONDUCT.md` using the Contributor Covenant v2.1.

- [ ] **Step 3: Create `SECURITY.md`**

Create `SECURITY.md` defining supported versions and responsible vulnerability disclosure process.

- [ ] **Step 4: Commit**

```bash
git add CONTRIBUTING.md CODE_OF_CONDUCT.md SECURITY.md
git commit -m "docs: add contributing guidelines, code of conduct, and security policy"
```

---

### Task 6: GitHub Issue & PR Templates

**Files:**
- Create: `.github/ISSUE_TEMPLATE/bug_report.md`
- Create: `.github/ISSUE_TEMPLATE/feature_request.md`
- Create: `.github/ISSUE_TEMPLATE/model_request.md`
- Create: `.github/pull_request_template.md`

**Interfaces:**
- Produces: Standardized issue reporting and PR submission checklists.

- [ ] **Step 1: Create `.github/ISSUE_TEMPLATE/` files**

Create structured bug report, feature request, and model support request templates.

- [ ] **Step 2: Create `.github/pull_request_template.md`**

Create PR template with description, type of change, related issue, and verification checklist.

- [ ] **Step 3: Commit**

```bash
git add .github/ISSUE_TEMPLATE/ .github/pull_request_template.md
git commit -m "docs: add GitHub issue templates and pull request template"
```

---

### Task 7: Modernize `README.md` with Badges, Comprehensive Credits & Licensing

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Project details, licenses, contributors, architectural features
- Produces: Polished open-source README with badges, full credits and third-party attributions, model license disclosures.

- [ ] **Step 1: Add status badges to `README.md`**

Add badges right beneath the top header:
- CI Workflow Status (`[![CI](...)]`)
- License (`[![License: Apache-2.0](...)]`)
- Platform / API 26+ (`[![API: 26+](...)]`)
- Kotlin 2.0 (`[![Kotlin](...)]`)
- Hardware acceleration (`[![NPU: Qualcomm Hexagon HTP](...)]`)

- [ ] **Step 2: Update Section 8 (License) & Add Section 9 (Acknowledgments & Credits)**

Replace Section 8 with explicit Apache License 2.0 terms and add detailed Acknowledgments & Credits crediting:
- Qualcomm Technologies (Qualcomm Neural Processing SDK / QNN & Hexagon HTP)
- Microsoft (ONNX Runtime Mobile)
- Lykon, RunwayML, Stability AI (DreamShaper v8 & Stable Diffusion)
- Xintao Wang et al. (Real-ESRGAN)
- Hugging Face (Diffusers & Model Hub)
- Google Android Open Source Project (Jetpack Compose & Material 3)
Also include explicit Third-Party Model Licensing Notice (CreativeML OpenRAIL-M for SD, BSD 3-Clause for RealESRGAN).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(readme): add CI badges, license details, and comprehensive credits"
```

---

### Task 8: Verification, Push & Pull Request Creation

**Files:**
- None created; operates on Git and GitHub CLI.

**Interfaces:**
- Consumes: Branch `feature/ci-and-open-source-readiness`, `gh pr create`
- Produces: Remote tracking branch and open Pull Request targeting `master`.

- [ ] **Step 1: Verify local tests pass**

Run: `./gradlew test --stacktrace`
Expected: 121 / 121 tests PASS, BUILD SUCCESSFUL.

- [ ] **Step 2: Push feature branch to origin**

Run: `git push -u origin feature/ci-and-open-source-readiness`

- [ ] **Step 3: Create Pull Request targeting master**

Run:
```bash
gh pr create \
  --base master \
  --head feature/ci-and-open-source-readiness \
  --title "ci: establish GitHub Actions CI/CD workflows and open source readiness" \
  --body "..."
```
Expected: PR URL returned. Merge left to the user.
