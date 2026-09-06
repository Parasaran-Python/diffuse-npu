#pragma once
#include <vector>
#include <cmath>
#include <algorithm>

enum class SamplerAlgorithm {
    EULER_A = 0,
    DPM_2M_KARRAS = 1,
    DPM_SDE_KARRAS = 2,
    DDIM = 3
};

class DiffusionScheduler {
public:
    DiffusionScheduler(SamplerAlgorithm algo);
    void initTimesteps(int steps);
    float getSigma(int stepIdx) const;
    float getTimestep(int stepIdx) const;
    void step(const float* sample, const float* modelOutput, int stepIdx, float* outSample, int size);

    SamplerAlgorithm getAlgorithm() const { return algo_; }
    int getNumSteps() const { return numSteps_; }
    const std::vector<float>& getTimesteps() const { return timesteps_; }
    const std::vector<float>& getSigmas() const { return sigmas_; }

private:
    SamplerAlgorithm algo_;
    int numSteps_;
    std::vector<float> timesteps_;
    std::vector<float> sigmas_;
    std::vector<float> prevSample_;
    std::vector<float> prevNoise_;

    void initKarrasSigmas(int steps);
    void initDDIMSigmas(int steps);
    void eulerStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size);
    void dpm2mStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size);
    void dpmSdeStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size);
    void ddimStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size);
};

inline DiffusionScheduler::DiffusionScheduler(SamplerAlgorithm algo)
    : algo_(algo), numSteps_(20) {}

inline void DiffusionScheduler::initTimesteps(int steps) {
    numSteps_ = steps;
    timesteps_.resize(steps);
    sigmas_.resize(steps + 1);
    prevSample_.clear();
    prevNoise_.clear();

    switch (algo_) {
        case SamplerAlgorithm::DPM_2M_KARRAS:
        case SamplerAlgorithm::DPM_SDE_KARRAS:
            initKarrasSigmas(steps);
            break;
        case SamplerAlgorithm::DDIM:
            initDDIMSigmas(steps);
            break;
        case SamplerAlgorithm::EULER_A:
        default:
            for (int i = 0; i < steps; ++i) {
                timesteps_[i] = 999.0f * (1.0f - static_cast<float>(i) / static_cast<float>(steps));
                sigmas_[i] = timesteps_[i] / 1000.0f * 14.0f;
            }
            sigmas_[steps] = 0.0f;
            break;
    }
}

inline void DiffusionScheduler::initKarrasSigmas(int steps) {
    const float sigmaMin = 0.0291675f;
    const float sigmaMax = 14.614642f;
    const float rho = 7.0f;

    for (int i = 0; i < steps; ++i) {
        float t = static_cast<float>(i) / static_cast<float>(steps - 1);
        float sigma = std::pow(
            std::pow(sigmaMax, 1.0f / rho) + t * (std::pow(sigmaMin, 1.0f / rho) - std::pow(sigmaMax, 1.0f / rho)),
            rho
        );
        sigmas_[i] = sigma;
        timesteps_[i] = sigma * 1000.0f / 14.0f * 999.0f;
    }
    sigmas_[steps] = 0.0f;
    timesteps_[steps] = 0.0f;
}

inline void DiffusionScheduler::initDDIMSigmas(int steps) {
    for (int i = 0; i < steps; ++i) {
        float t = static_cast<float>(i) / static_cast<float>(steps);
        float alpha = std::cos(t * M_PI_2);
        float sigma = std::sin(t * M_PI_2);
        sigmas_[i] = sigma / alpha;
        timesteps_[i] = 999.0f * (1.0f - t);
    }
    sigmas_[steps] = 0.0f;
    timesteps_[steps] = 0.0f;
}

inline float DiffusionScheduler::getSigma(int stepIdx) const {
    if (stepIdx < 0 || stepIdx >= static_cast<int>(sigmas_.size())) return 0.0f;
    return sigmas_[stepIdx];
}

inline float DiffusionScheduler::getTimestep(int stepIdx) const {
    if (stepIdx < 0 || stepIdx >= static_cast<int>(timesteps_.size())) return 0.0f;
    return timesteps_[stepIdx];
}

inline void DiffusionScheduler::step(const float* sample, const float* modelOutput, int stepIdx, float* outSample, int size) {
    if (!sample || !modelOutput || !outSample || size <= 0) return;
    if (stepIdx < 0 || stepIdx + 1 >= static_cast<int>(sigmas_.size())) {
        if (sample != outSample) std::copy(sample, sample + size, outSample);
        return;
    }

    switch (algo_) {
        case SamplerAlgorithm::DPM_2M_KARRAS:
            dpm2mStep(sample, modelOutput, stepIdx, outSample, size);
            break;
        case SamplerAlgorithm::DPM_SDE_KARRAS:
            dpmSdeStep(sample, modelOutput, stepIdx, outSample, size);
            break;
        case SamplerAlgorithm::DDIM:
            ddimStep(sample, modelOutput, stepIdx, outSample, size);
            break;
        case SamplerAlgorithm::EULER_A:
        default:
            eulerStep(sample, modelOutput, stepIdx, outSample, size);
            break;
    }
}

inline void DiffusionScheduler::eulerStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size) {
    float sigma = sigmas_[stepIdx];
    float nextSigma = sigmas_[stepIdx + 1];
    float dt = nextSigma - sigma;
    for (int i = 0; i < size; ++i) {
        outSample[i] = sample[i] + dt * noise[i];
    }
}

inline void DiffusionScheduler::dpm2mStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size) {
    float sigma = sigmas_[stepIdx];
    float nextSigma = sigmas_[stepIdx + 1];

    if (stepIdx == 0 || prevNoise_.empty()) {
        for (int i = 0; i < size; ++i) {
            outSample[i] = sample[i] + (nextSigma - sigma) * noise[i];
        }
        prevSample_.assign(sample, sample + size);
        prevNoise_.assign(noise, noise + size);
        return;
    }

    float h = nextSigma - sigma;
    float hPrev = sigma - (stepIdx > 0 ? sigmas_[stepIdx - 1] : sigma);
    float r = hPrev / h;

    for (int i = 0; i < size; ++i) {
        float d = (1.0f + 1.0f / (2.0f * r)) * noise[i] - (1.0f / (2.0f * r)) * prevNoise_[i];
        outSample[i] = sample[i] + h * d;
    }
    prevSample_.assign(sample, sample + size);
    prevNoise_.assign(noise, noise + size);
}

inline void DiffusionScheduler::dpmSdeStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size) {
    float sigma = sigmas_[stepIdx];
    float nextSigma = sigmas_[stepIdx + 1];
    float h = nextSigma - sigma;

    if (stepIdx == 0 || prevNoise_.empty()) {
        for (int i = 0; i < size; ++i) {
            outSample[i] = sample[i] + h * noise[i];
        }
        prevSample_.assign(sample, sample + size);
        prevNoise_.assign(noise, noise + size);
        return;
    }

    float hPrev = sigma - (stepIdx > 0 ? sigmas_[stepIdx - 1] : sigma);
    float r = hPrev / h;

    for (int i = 0; i < size; ++i) {
        float d = (1.0f + 1.0f / (2.0f * r)) * noise[i] - (1.0f / (2.0f * r)) * prevNoise_[i];
        float noiseTerm = std::sqrt(2.0f * h) * 0.0f;
        outSample[i] = sample[i] + h * d + noiseTerm;
    }
    prevSample_.assign(sample, sample + size);
    prevNoise_.assign(noise, noise + size);
}

inline void DiffusionScheduler::ddimStep(const float* sample, const float* noise, int stepIdx, float* outSample, int size) {
    float sigma = sigmas_[stepIdx];
    float nextSigma = sigmas_[stepIdx + 1];

    float alpha = 1.0f / std::sqrt(1.0f + sigma * sigma);
    float nextAlpha = 1.0f / std::sqrt(1.0f + nextSigma * nextSigma);

    for (int i = 0; i < size; ++i) {
        float predX0 = (sample[i] - sigma * noise[i]) / alpha;
        outSample[i] = nextAlpha * predX0 + nextSigma * noise[i];
    }
}