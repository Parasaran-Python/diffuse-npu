#!/usr/bin/env bash
# ==============================================================================
# Sideload Script: Stable Diffusion on NPU (Samsung Galaxy S23 Ultra / Snapdragon 8 Gen 2)
# ==============================================================================
# This script:
# 1. Verifies ADB installation and connected Android device(s).
# 2. Builds the minified, debug-signed release APK via Gradle.
# 3. Verifies that the release APK exists and satisfies the size constraint (<50MB).
# 4. Sideloads (installs) the release APK to the connected device.
# 5. Grants necessary runtime permissions (e.g. POST_NOTIFICATIONS).
# 6. Launches the application and verifies process startup.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PACKAGE_NAME="com.example.sdnpu"
ACTIVITY_NAME="${PACKAGE_NAME}/.MainActivity"
APK_PATH="${PROJECT_ROOT}/app/build/outputs/apk/release/app-release.apk"
MAX_SIZE_MB=50

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# 1. Locate and verify adb
if command -v adb >/dev/null 2>&1; then
    ADB_BIN="adb"
elif [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/platform-tools/adb" ]]; then
    ADB_BIN="${ANDROID_HOME}/platform-tools/adb"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]]; then
    ADB_BIN="${ANDROID_SDK_ROOT}/platform-tools/adb"
else
    log_error "ADB not found. Please install Android SDK Platform-Tools or set ANDROID_HOME."
    exit 1
fi

log_info "Using ADB: $(${ADB_BIN} version | head -n 1)"

# 2. Verify connected device
log_info "Checking for connected Android devices..."
DEVICES_OUTPUT=$("${ADB_BIN}" devices | grep -v "List of devices attached" | grep -v "^$" || true)

if [[ -z "${DEVICES_OUTPUT}" ]]; then
    log_error "No Android devices connected via ADB. Please connect your device with USB debugging enabled."
    exit 1
fi

DEVICE_COUNT=$(echo "${DEVICES_OUTPUT}" | grep -c "device$" || true)
if [[ "${DEVICE_COUNT}" -eq 0 ]]; then
    log_error "Devices detected, but none are authorized or ready:"
    echo "${DEVICES_OUTPUT}"
    exit 1
fi

TARGET_SERIAL="${ADB_SERIAL:-}"
if [[ -z "${TARGET_SERIAL}" ]]; then
    TARGET_SERIAL=$(echo "${DEVICES_OUTPUT}" | grep "device$" | head -n 1 | awk '{print $1}')
fi

log_info "Target device serial: ${TARGET_SERIAL}"
DEVICE_MODEL=$("${ADB_BIN}" -s "${TARGET_SERIAL}" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || echo "Unknown")
DEVICE_MANUF=$("${ADB_BIN}" -s "${TARGET_SERIAL}" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r\n' || echo "Unknown")
DEVICE_SOC=$("${ADB_BIN}" -s "${TARGET_SERIAL}" shell getprop ro.soc.model 2>/dev/null | tr -d '\r\n' || echo "Unknown")
log_info "Target device info: ${DEVICE_MANUF} ${DEVICE_MODEL} (SoC: ${DEVICE_SOC})"

# 3. Build release APK
log_info "Building optimized release APK (:app:assembleRelease)..."
cd "${PROJECT_ROOT}"
./gradlew :app:assembleRelease --no-daemon

if [[ ! -f "${APK_PATH}" ]]; then
    log_error "Release APK not found at ${APK_PATH} after assembleRelease."
    exit 1
fi

# 4. Check APK size
APK_SIZE_BYTES=$(stat -c%s "${APK_PATH}" 2>/dev/null || stat -f%z "${APK_PATH}" 2>/dev/null || wc -c < "${APK_PATH}")
APK_SIZE_MB=$(awk "BEGIN {printf \"%.2f\", ${APK_SIZE_BYTES}/1048576}")
log_info "Release APK size: ${APK_SIZE_MB} MB (${APK_SIZE_BYTES} bytes)"

# Size check (< 50MB)
MAX_SIZE_BYTES=$((MAX_SIZE_MB * 1024 * 1024))
if [[ "${APK_SIZE_BYTES}" -ge "${MAX_SIZE_BYTES}" ]]; then
    log_error "Release APK size (${APK_SIZE_MB} MB) exceeds maximum allowed threshold of ${MAX_SIZE_MB} MB!"
    exit 1
fi
log_success "Release APK size check passed: ${APK_SIZE_MB} MB < ${MAX_SIZE_MB} MB"

# 5. Sideload APK to device
log_info "Installing ${APK_PATH} to ${TARGET_SERIAL}..."
"${ADB_BIN}" -s "${TARGET_SERIAL}" install -r -d "${APK_PATH}"
log_success "APK successfully installed."

# 6. Grant runtime permissions (POST_NOTIFICATIONS for Android 13+)
log_info "Configuring runtime permissions..."
"${ADB_BIN}" -s "${TARGET_SERIAL}" shell pm grant "${PACKAGE_NAME}" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 7. Launch application
log_info "Launching application (${ACTIVITY_NAME})..."
"${ADB_BIN}" -s "${TARGET_SERIAL}" shell am start -n "${ACTIVITY_NAME}"

# 8. Verify launch
log_info "Verifying application launch..."
sleep 2
PID=$("${ADB_BIN}" -s "${TARGET_SERIAL}" shell pidof "${PACKAGE_NAME}" 2>/dev/null | tr -d '\r\n' || true)
if [[ -n "${PID}" ]]; then
    log_success "Application is running (PID: ${PID}) on ${TARGET_SERIAL}."
else
    log_warn "Application launched but pidof did not report an active PID (process may still be starting or in background)."
fi

log_success "Sideload and launch workflow completed successfully!"
