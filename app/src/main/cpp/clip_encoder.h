#pragma once
#include <vector>
#include <string>
#include <cstdint>

class ClipEncoder {
public:
    ClipEncoder();
    ~ClipEncoder();

    bool loadModel(const std::string& modelPath);
    bool encode(const std::vector<int32_t>& tokens, std::vector<float>& outEmbeddings);
    void unload();
    bool isLoaded() const { return isLoaded_; }

private:
    bool isLoaded_;
    std::string modelPath_;
    const int embeddingDim_ = 768;
    const int seqLength_ = 77;
};
