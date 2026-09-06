#pragma once
#include <string>
#include <vector>
#include <cstdint>

class VaeDecoder {
public:
    VaeDecoder();
    ~VaeDecoder();

    bool loadModel(const std::string& modelPath);
    bool decode(const float* latents, int latentSize, uint8_t* outRgb, int width, int height);
    void unload();
    bool isLoaded() const { return isLoaded_; }

private:
    bool isLoaded_;
    std::string modelPath_;
};
