#include "vae_decoder.h"
#include <algorithm>

VaeDecoder::VaeDecoder() : isLoaded_(false) {}

VaeDecoder::~VaeDecoder() {
    unload();
}

bool VaeDecoder::loadModel(const std::string& modelPath) {
    modelPath_ = modelPath;
    isLoaded_ = true;
    return true;
}

bool VaeDecoder::decode(const float* latents, int latentSize, uint8_t* outRgb, int width, int height) {
    if (!isLoaded_ || !latents || !outRgb || width <= 0 || height <= 0) return false;
    const float scale = 1.0f / 0.18215f;
    const int latentArea = 64 * 64;

    for (int y = 0; y < height; ++y) {
        int ly = (y * 64) / height;
        for (int x = 0; x < width; ++x) {
            int lx = (x * 64) / width;
            int lIdx = ly * 64 + lx;

            float rF = (lIdx < latentSize) ? latents[lIdx] * scale : 0.0f;
            float gF = (lIdx + latentArea < latentSize) ? latents[lIdx + latentArea] * scale : 0.0f;
            float bF = (lIdx + 2 * latentArea < latentSize) ? latents[lIdx + 2 * latentArea] * scale : 0.0f;

            int r = static_cast<int>(std::clamp((rF + 1.0f) * 127.5f, 0.0f, 255.0f));
            int g = static_cast<int>(std::clamp((gF + 1.0f) * 127.5f, 0.0f, 255.0f));
            int b = static_cast<int>(std::clamp((bF + 1.0f) * 127.5f, 0.0f, 255.0f));

            int outIdx = (y * width + x) * 4;
            outRgb[outIdx] = static_cast<uint8_t>(r);
            outRgb[outIdx + 1] = static_cast<uint8_t>(g);
            outRgb[outIdx + 2] = static_cast<uint8_t>(b);
            outRgb[outIdx + 3] = 255;
        }
    }
    return true;
}

void VaeDecoder::unload() {
    isLoaded_ = false;
    modelPath_.clear();
}
