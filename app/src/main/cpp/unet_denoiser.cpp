#include "unet_denoiser.h"
#include <cmath>

UnetDenoiser::UnetDenoiser() : isLoaded_(false) {}

UnetDenoiser::~UnetDenoiser() {
    unload();
}

bool UnetDenoiser::loadModel(const std::string& modelPath) {
    modelPath_ = modelPath;
    isLoaded_ = true;
    return true;
}

bool UnetDenoiser::predictNoise(
    const float* latents,
    float timestep,
    const float* textEmbeddings,
    float* outNoise,
    int latentSize) {
    if (!isLoaded_ || !latents || !outNoise || latentSize <= 0) return false;

    // Simulate UNet residual diffusion evaluation
    float factor = std::sin(timestep / 1000.0f);
    for (int i = 0; i < latentSize; ++i) {
        float embedFactor = textEmbeddings ? textEmbeddings[i % 768] * 0.1f : 0.0f;
        outNoise[i] = latents[i] * 0.5f + factor * 0.2f + embedFactor;
    }
    return true;
}

void UnetDenoiser::applyCfg(
    const float* uncondNoise,
    const float* condNoise,
    float cfgScale,
    float* outGuidedNoise,
    int size) {
    if (!uncondNoise || !condNoise || !outGuidedNoise || size <= 0) return;
    for (int i = 0; i < size; ++i) {
        // CFG: uncond + cfgScale * (cond - uncond)
        outGuidedNoise[i] = uncondNoise[i] + cfgScale * (condNoise[i] - uncondNoise[i]);
    }
}

void UnetDenoiser::unload() {
    isLoaded_ = false;
    modelPath_.clear();
}
