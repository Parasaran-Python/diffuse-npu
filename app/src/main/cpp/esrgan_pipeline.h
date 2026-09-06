#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <mutex>
#include <atomic>

class EsrganPipeline {
public:
    static EsrganPipeline& getInstance();

    bool loadContext(const std::string& modelPath, int scale);
    bool upscale(
        const uint8_t* inRgba,
        int inW,
        int inH,
        uint8_t* outRgba,
        int outW,
        int outH,
        int scale
    );
    void requestCancel();
    void unloadContext();
    bool isLoaded() const;

private:
    EsrganPipeline();
    ~EsrganPipeline() = default;
    EsrganPipeline(const EsrganPipeline&) = delete;
    EsrganPipeline& operator=(const EsrganPipeline&) = delete;

    std::string modelPath_;
    int scale_{1};
    bool isLoaded_{false};
    mutable std::mutex mutex_;
    std::atomic<bool> cancelRequested_{false};
};
