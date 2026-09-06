#pragma once
#include <vector>
#include <cmath>

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
};
