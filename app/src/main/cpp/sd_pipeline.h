#pragma once

#include "clip_encoder.h"
#include "unet_denoiser.h"
#include "vae_decoder.h"
#include "scheduler.h"
#include <string>
#include <vector>
#include <functional>
#include <cstdint>

class SdPipeline {
public:
    static SdPipeline& getInstance();

    bool loadContext(const std::string& modelDir);
    bool generate(
        const std::vector<int32_t>& promptTokens,
        const std::vector<int32_t>& negTokens,
        int steps,
        float cfgScale,
        int64_t seed,
        SamplerAlgorithm sampler,
        std::function<void(int, int)> progressCallback,
        std::vector<uint8_t>& outImageBytes
    );
    void unloadContext();
    bool isLoaded() const { return isLoaded_; }

private:
    SdPipeline();
    ~SdPipeline() = default;
    SdPipeline(const SdPipeline&) = delete;
    SdPipeline& operator=(const SdPipeline&) = delete;

    ClipEncoder clipEncoder_;
    UnetDenoiser unetDenoiser_;
    VaeDecoder vaeDecoder_;
    bool isLoaded_;
};
