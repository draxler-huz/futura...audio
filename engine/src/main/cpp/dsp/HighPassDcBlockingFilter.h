// Adaptive DC servo followed by a low-cut biquad for sub-audio rumble.
#ifndef FUTURA_ENGINE_DSP_HIGH_PASS_DC_BLOCKING_FILTER_H
#define FUTURA_ENGINE_DSP_HIGH_PASS_DC_BLOCKING_FILTER_H

#include <atomic>
#include <cmath>
#include <cstddef>

#include "dsp/Biquad.h"

namespace futura::engine::dsp {

class HighPassDcBlockingFilter {
public:
    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 1000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        const float cutoff = base_cutoff_hz_.load(std::memory_order_relaxed);
        for (auto& filter : high_pass_) {
            filter.set_cutoff(Biquad::Type::HighPass, cutoff, 0.707f, sample_rate);
        }
    }

    void set_base_cutoff_hz(float hz) noexcept {
        if (!std::isfinite(hz)) return;
        hz = clamp(hz, 1.0f, 15.0f);
        base_cutoff_hz_.store(hz, std::memory_order_release);
        const float sr = sample_rate_.load(std::memory_order_acquire);
        if (sr > 0.0f) {
            for (auto& filter : high_pass_) {
                filter.set_cutoff(Biquad::Type::HighPass, hz, 0.707f, sr);
            }
        }
    }

    void reset() noexcept {
        dc_estimate_[0] = dc_estimate_[1] = 0.0f;
        high_pass_[0].reset();
        high_pass_[1].reset();
    }

    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 1) return;
        const float sr = sample_rate_.load(std::memory_order_acquire);
        const float base = base_cutoff_hz_.load(std::memory_order_acquire);
        for (size_t i = 0; i < frames; ++i) {
            for (int ch = 0; ch < (channels > 1 ? 2 : 1); ++ch) {
                float& sample = buffer[i * static_cast<size_t>(channels) +
                                       static_cast<size_t>(ch)];
                if (!std::isfinite(sample)) {
                    sample = 0.0f;
                    continue;
                }
                // Increase the servo corner briefly when a large offset is
                // detected, then settle back to the configured low cutoff.
                const float adaptive_hz = base + clamp(std::fabs(dc_estimate_[ch]) * 12.0f,
                                                       0.0f, 8.0f);
                const float alpha = 1.0f - std::exp(-kTwoPi * adaptive_hz / sr);
                dc_estimate_[ch] += alpha * (sample - dc_estimate_[ch]);
                sample = high_pass_[ch].process_sample(sample - dc_estimate_[ch]);
            }
        }
        dc_estimate_meter_[0].store(dc_estimate_[0], std::memory_order_relaxed);
        dc_estimate_meter_[1].store(dc_estimate_[1], std::memory_order_relaxed);
    }

    float dc_estimate(int channel) const noexcept {
        if (channel < 0 || channel > 1) return 0.0f;
        return dc_estimate_meter_[channel].load(std::memory_order_relaxed);
    }

private:
    static constexpr float kBaseCutoffHz = 5.0f;
    static constexpr float kTwoPi = 6.28318530717958647692f;
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> base_cutoff_hz_{kBaseCutoffHz};
    float dc_estimate_[2] = {0.0f, 0.0f};
    std::atomic<float> dc_estimate_meter_[2]{};
    Biquad high_pass_[2];
};

}  // namespace futura::engine::dsp
#endif
