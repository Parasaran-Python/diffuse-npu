#include "clip_encoder.h"
#include <cmath>
#include <cstring>

ClipEncoder::ClipEncoder() : isLoaded_(false) {}

ClipEncoder::~ClipEncoder() {
    unload();
}

bool ClipEncoder::loadModel(const std::string& modelPath) {
    modelPath_ = modelPath;
    isLoaded_ = true;
    return true;
}

bool ClipEncoder::encode(const std::vector<int32_t>& tokens, std::vector<float>& outEmbeddings) {
    if (!isLoaded_ || tokens.size() != static_cast<size_t>(seqLength_)) {
        return false;
    }
    outEmbeddings.resize(seqLength_ * embeddingDim_);

    // Project tokens to embedding vector space (simulated text feature projections)
    for (int i = 0; i < seqLength_; ++i) {
        float tokenVal = static_cast<float>(tokens[i]) / 50000.0f;
        for (int d = 0; d < embeddingDim_; ++d) {
            float freq = static_cast<float>(d) / static_cast<float>(embeddingDim_);
            outEmbeddings[i * embeddingDim_ + d] = std::sin(tokenVal * (d + 1)) * std::cos(freq);
        }
    }
    return true;
}

void ClipEncoder::unload() {
    isLoaded_ = false;
}
