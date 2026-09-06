#include "esrgan_pipeline.h"
#include <cmath>
#include <algorithm>

namespace {

inline float bicubicWeight(float x) {
    float ax = std::abs(x);
    const float a = -0.5f;
    if (ax <= 1.0f) {
        return (a + 2.0f) * ax * ax * ax - (a + 3.0f) * ax * ax + 1.0f;
    } else if (ax < 2.0f) {
        return a * ax * ax * ax - 5.0f * a * ax * ax + 8.0f * a * ax - 4.0f * a;
    }
    return 0.0f;
}

struct SampleWeight {
    int p[4];
    float w[4];
};

} // anonymous namespace

EsrganPipeline& EsrganPipeline::getInstance() {
    static EsrganPipeline instance;
    return instance;
}

EsrganPipeline::EsrganPipeline() : scale_(1), isLoaded_(false) {}

bool EsrganPipeline::loadContext(const std::string& modelPath, int scale) {
    std::lock_guard<std::mutex> lock(mutex_);
    modelPath_ = modelPath;
    scale_ = scale;
    isLoaded_ = true;
    return true;
}

void EsrganPipeline::requestCancel() {
    cancelRequested_ = true;
}

bool EsrganPipeline::upscale(
    const uint8_t* inRgba,
    int inW,
    int inH,
    uint8_t* outRgba,
    int outW,
    int outH,
    int scale
) {
    std::lock_guard<std::mutex> lock(mutex_);
    cancelRequested_ = false;

    if (!isLoaded_) {
        return false;
    }
    if (!inRgba || !outRgba) {
        return false;
    }
    if (inW <= 0 || inH <= 0 || scale <= 0) {
        return false;
    }
    if (outW != inW * scale || outH != inH * scale) {
        return false;
    }

    // Precompute sample weights and clamped coordinates for all columns
    std::vector<SampleWeight> xWeights(outW);
    for (int x = 0; x < outW; ++x) {
        float srcX = (static_cast<float>(x) + 0.5f) / static_cast<float>(scale) - 0.5f;
        int i0 = static_cast<int>(std::floor(srcX));
        float sumW = 0.0f;
        for (int k = 0; k < 4; ++k) {
            int px = i0 - 1 + k;
            xWeights[x].p[k] = std::clamp(px, 0, inW - 1);
            float w = bicubicWeight(srcX - static_cast<float>(px));
            xWeights[x].w[k] = w;
            sumW += w;
        }
        if (std::abs(sumW) > 1e-6f) {
            for (int k = 0; k < 4; ++k) {
                xWeights[x].w[k] /= sumW;
            }
        }
    }

    // Precompute sample weights and clamped coordinates for all rows
    std::vector<SampleWeight> yWeights(outH);
    for (int y = 0; y < outH; ++y) {
        float srcY = (static_cast<float>(y) + 0.5f) / static_cast<float>(scale) - 0.5f;
        int j0 = static_cast<int>(std::floor(srcY));
        float sumW = 0.0f;
        for (int m = 0; m < 4; ++m) {
            int py = j0 - 1 + m;
            yWeights[y].p[m] = std::clamp(py, 0, inH - 1);
            float w = bicubicWeight(srcY - static_cast<float>(py));
            yWeights[y].w[m] = w;
            sumW += w;
        }
        if (std::abs(sumW) > 1e-6f) {
            for (int m = 0; m < 4; ++m) {
                yWeights[y].w[m] /= sumW;
            }
        }
    }

    // Bicubic interpolation with cooperative cancellation per row
    for (int y = 0; y < outH; ++y) {
        if (cancelRequested_) {
            return false;
        }

        const auto& swY = yWeights[y];
        const int rowOffsets[4] = {
            swY.p[0] * inW,
            swY.p[1] * inW,
            swY.p[2] * inW,
            swY.p[3] * inW
        };
        const float wys[4] = {
            swY.w[0],
            swY.w[1],
            swY.w[2],
            swY.w[3]
        };

        for (int x = 0; x < outW; ++x) {
            const auto& swX = xWeights[x];
            float r = 0.0f;
            float g = 0.0f;
            float b = 0.0f;

            for (int m = 0; m < 4; ++m) {
                float wy = wys[m];
                int rOff = rowOffsets[m];
                for (int k = 0; k < 4; ++k) {
                    float w = wy * swX.w[k];
                    int inIdx = (rOff + swX.p[k]) * 4;
                    r += static_cast<float>(inRgba[inIdx]) * w;
                    g += static_cast<float>(inRgba[inIdx + 1]) * w;
                    b += static_cast<float>(inRgba[inIdx + 2]) * w;
                }
            }

            int outIdx = (y * outW + x) * 4;
            outRgba[outIdx]     = static_cast<uint8_t>(std::clamp(std::round(r), 0.0f, 255.0f));
            outRgba[outIdx + 1] = static_cast<uint8_t>(std::clamp(std::round(g), 0.0f, 255.0f));
            outRgba[outIdx + 2] = static_cast<uint8_t>(std::clamp(std::round(b), 0.0f, 255.0f));
            outRgba[outIdx + 3] = 255;
        }
    }

    return true;
}

void EsrganPipeline::unloadContext() {
    std::lock_guard<std::mutex> lock(mutex_);
    isLoaded_ = false;
    modelPath_.clear();
    scale_ = 1;
}

bool EsrganPipeline::isLoaded() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return isLoaded_;
}
