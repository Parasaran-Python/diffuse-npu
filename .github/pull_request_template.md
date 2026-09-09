## Description
<!-- Provide a brief description of what this PR introduces or fixes. -->

## Type of Change
- [ ] Bug fix (non-breaking change fixing an issue)
- [ ] New feature (non-breaking change adding functionality)
- [ ] Performance improvement (speed, memory, NPU efficiency)
- [ ] Documentation update (README, specs, guides)
- [ ] CI/CD or build system change

## Architecture & Subsystem Impact
- [ ] UI Layer (`ui/`)
- [ ] Pipeline & Orchestrator (`pipeline/`)
- [ ] Model Management & Download (`model/`)
- [ ] ONNX / QNN Inference Engines (`engine/`)
- [ ] Native C++ Core (`src/main/cpp/`)
- [ ] Build Scripts & CI Workflows

## Verification Checklist
- [ ] `./gradlew lintDebug` passes with zero fatal errors.
- [ ] `./gradlew testDebugUnitTest` passes (100% unit test success rate).
- [ ] `./gradlew assembleDebug` builds successfully for dual ABIs (`arm64-v8a`, `x86_64`).
- [ ] Verified on physical hardware or emulator (indicate device below).
- [ ] No native memory leaks introduced (sequential context lifecycle preserved).
- [ ] Peak memory ceiling maintained (< 4 GB RAM).

## Device Testing Details
- **Test Device**: [e.g. Samsung Galaxy S23 Ultra / Android Emulator x86_64]
- **Hardware Acceleration Verified**: [Hexagon HTP NPU / NNAPI / CPU Stub]
- **Generation Time / Steps**: [e.g. 4 steps in ~1.2s]
