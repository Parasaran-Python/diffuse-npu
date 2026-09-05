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
