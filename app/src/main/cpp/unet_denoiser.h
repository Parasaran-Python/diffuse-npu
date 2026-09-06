#pragma once
#include <string>
#include <vector>

class UnetDenoiser {
public:
    UnetDenoiser();
    ~UnetDenoiser();

    bool loadModel(const std::string& modelPath);
    bool predictNoise(
        const float* latents,
        float timestep,
        const float* textEmbeddings,
        float* outNoise,
        int latentSize
    );
    void applyCfg(
        const float* uncondNoise,
        const float* condNoise,
        float cfgScale,
        float* outGuidedNoise,
        int size
    );
    void unload();
    bool isLoaded() const { return isLoaded_; }

private:
    bool isLoaded_;
    std::string modelPath_;
};
