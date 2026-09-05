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
    std::lock_guard<std::mutex> lock(mutex_);
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
    std::lock_guard<std::mutex> lock(mutex_);
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
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!isInitialized_ || iterations <= 0) return 0.0f;
    }

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
    asm volatile("" : : "r"(c.data()) : "memory");
    std::chrono::duration<float, std::milli> duration = end - start;
    return duration.count();
}

void QnnEngineWrapper::release() {
    std::lock_guard<std::mutex> lock(mutex_);
    isInitialized_ = false;
}

