#include "scheduler.h"
#include <algorithm>

DiffusionScheduler::DiffusionScheduler(SamplerAlgorithm algo)
    : algo_(algo), numSteps_(20) {}

void DiffusionScheduler::initTimesteps(int steps) {
    numSteps_ = steps;
    timesteps_.resize(steps);
    sigmas_.resize(steps + 1);

    for (int i = 0; i < steps; ++i) {
        timesteps_[i] = 999.0f * (1.0f - static_cast<float>(i) / static_cast<float>(steps));
        // Linear sigma schedule approximation
        sigmas_[i] = timesteps_[i] / 1000.0f * 14.0f;
    }
    sigmas_[steps] = 0.0f;
}

float DiffusionScheduler::getSigma(int stepIdx) const {
    if (stepIdx < 0 || stepIdx >= static_cast<int>(sigmas_.size())) return 0.0f;
    return sigmas_[stepIdx];
}

float DiffusionScheduler::getTimestep(int stepIdx) const {
    if (stepIdx < 0 || stepIdx >= static_cast<int>(timesteps_.size())) return 0.0f;
    return timesteps_[stepIdx];
}

void DiffusionScheduler::step(const float* sample, const float* modelOutput, int stepIdx, float* outSample, int size) {
    if (!sample || !modelOutput || !outSample || size <= 0) return;
    if (stepIdx < 0 || stepIdx + 1 >= static_cast<int>(sigmas_.size())) {
        if (sample != outSample) {
            std::copy(sample, sample + size, outSample);
        }
        return;
    }

    float sigma = sigmas_[stepIdx];
    float nextSigma = sigmas_[stepIdx + 1];
    float dt = nextSigma - sigma;

    for (int i = 0; i < size; ++i) {
        // Euler step: x_{t-1} = x_t + dt * d
        outSample[i] = sample[i] + dt * modelOutput[i];
    }
}
