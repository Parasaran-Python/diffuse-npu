#include "sd_pipeline.h"
#include <random>

SdPipeline& SdPipeline::getInstance() {
    static SdPipeline instance;
    return instance;
}

SdPipeline::SdPipeline() : isLoaded_(false) {}

bool SdPipeline::loadContext(const std::string& modelDir) {
    std::lock_guard<std::mutex> lock(mutex_);
    bool ok1 = clipEncoder_.loadModel(modelDir + "/clip_text_encoder.bin");
    bool ok2 = unetDenoiser_.loadModel(modelDir + "/unet.bin");
    bool ok3 = vaeDecoder_.loadModel(modelDir + "/vae_decoder.bin");
    if (!ok1 || !ok2 || !ok3) {
        unloadContextInternal();
        return false;
    }
    isLoaded_ = true;
    return true;
}

bool SdPipeline::generate(
    const std::vector<int32_t>& promptTokens,
    const std::vector<int32_t>& negTokens,
    int steps,
    float cfgScale,
    int64_t seed,
    SamplerAlgorithm sampler,
    std::function<void(int, int)> progressCallback,
    std::vector<uint8_t>& outImageBytes
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!isLoaded_ || steps <= 0) return false;

    // 1. CLIP text embeddings
    std::vector<float> condEmbeddings;
    std::vector<float> uncondEmbeddings;
    if (!clipEncoder_.encode(promptTokens, condEmbeddings) ||
        !clipEncoder_.encode(negTokens, uncondEmbeddings)) {
        return false;
    }

    // 2. Initial Latent noise (1 x 4 x 64 x 64)
    const int latentSize = 4 * 64 * 64;
    std::vector<float> latents(latentSize);
    std::mt19937_64 rng(static_cast<uint64_t>(seed));
    std::normal_distribution<float> norm(0.0f, 1.0f);
    for (int i = 0; i < latentSize; ++i) {
        latents[i] = norm(rng);
    }

    // 3. Diffusion loop
    DiffusionScheduler scheduler(sampler);
    scheduler.initTimesteps(steps);

    std::vector<float> condNoise(latentSize);
    std::vector<float> uncondNoise(latentSize);
    std::vector<float> guidedNoise(latentSize);
    std::vector<float> nextLatents(latentSize);

    for (int s = 0; s < steps; ++s) {
        float timestep = 999.0f * (1.0f - static_cast<float>(s) / static_cast<float>(steps));
        if (!unetDenoiser_.predictNoise(latents.data(), timestep, condEmbeddings.data(), condNoise.data(), latentSize) ||
            !unetDenoiser_.predictNoise(latents.data(), timestep, uncondEmbeddings.data(), uncondNoise.data(), latentSize)) {
            return false;
        }
        unetDenoiser_.applyCfg(uncondNoise.data(), condNoise.data(), cfgScale, guidedNoise.data(), latentSize);

        scheduler.step(latents.data(), guidedNoise.data(), s, nextLatents.data(), latentSize);
        latents.swap(nextLatents);

        if (progressCallback) {
            progressCallback(s + 1, steps);
        }
    }

    // 4. VAE Decode to 512x512 RGBA
    const int width = 512;
    const int height = 512;
    outImageBytes.resize(width * height * 4);
    if (!vaeDecoder_.decode(latents.data(), latentSize, outImageBytes.data(), width, height)) {
        return false;
    }

    return true;
}

void SdPipeline::unloadContextInternal() {
    clipEncoder_.unload();
    unetDenoiser_.unload();
    vaeDecoder_.unload();
    isLoaded_ = false;
}

void SdPipeline::unloadContext() {
    std::lock_guard<std::mutex> lock(mutex_);
    unloadContextInternal();
}

bool SdPipeline::isLoaded() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return isLoaded_;
}
