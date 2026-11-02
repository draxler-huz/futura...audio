// K-weighted, long-term loudness leveling with true-peak gain protection.
#ifndef FUTURA_ENGINE_DSP_AUTO_GAIN_CONTROL_ENGINE_H
#define FUTURA_ENGINE_DSP_AUTO_GAIN_CONTROL_ENGINE_H

#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

#include "dsp/Biquad.h"

namespace futura::engine::dsp {

class AutoGainControlEngine {
public:
    AutoGainControlEngine() noexcept { set_sample_rate(48000.0f); }

    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 8000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        for (int ch = 0; ch < 2; ++ch) {
            high_pass_[ch].set_cutoff(Biquad::Type::HighPass, 38.135f, 0.5f, sample_rate);
            high_shelf_[ch].set_peak_or_shelf(Biquad::Type::HighShelf,
                                               1681.974f, 0.707f, 4.0f, sample_rate);
        }
    }
    void set_enabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }
    void set_target_lufs(float lufs) noexcept {
        if (std::isfinite(lufs))
            target_lufs_.store(clamp(lufs, -36.0f, -10.0f), std::memory_order_release);
    }
    void reset_track() noexcept {
        integrated_energy_sum_ = 0.0;
        integrated_samples_ = 0;
        smoothed_gain_ = 1.0f;
        true_peak_ = 0.0f;
        gain_meter_.store(1.0f, std::memory_order_relaxed);
        true_peak_meter_.store(0.0f, std::memory_order_relaxed);
        integrated_lufs_meter_.store(-120.0f, std::memory_order_relaxed);
        previous_sample_[0] = previous_sample_[1] = 0.0f;
        previous_sample2_[0] = previous_sample2_[1] = 0.0f;
        for (int ch = 0; ch < 2; ++ch) {
            high_pass_[ch].reset();
            high_shelf_[ch].reset();
        }
    }

    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 1 ||
            !enabled_.load(std::memory_order_acquire)) return;
        const float sr = sample_rate_.load(std::memory_order_acquire);
        const int active_channels = channels > 1 ? 2 : 1;
        const float target = target_lufs_.load(std::memory_order_acquire);
        const double integrated = integrated_samples_ > 0
            ? integrated_energy_sum_ / static_cast<double>(integrated_samples_) : 0.0;
        float target_gain = 1.0f;
        if (integrated > kAbsoluteGateEnergy) {
            const double target_energy = std::pow(10.0, (target + 0.691) / 10.0);
            target_gain = static_cast<float>(std::sqrt(target_energy / integrated));
            target_gain = clamp(target_gain, 0.25f, 4.0f);
        }
        if (true_peak_ > 1e-6f) target_gain = std::min(target_gain, kPeakCeiling / true_peak_);
        const float tau = target_gain < smoothed_gain_ ? 1.8f : 5.0f;
        const float gain_coeff = std::exp(-1.0f / (sr * tau));

        for (size_t frame = 0; frame < frames; ++frame) {
            float weighted_energy = 0.0f, frame_peak = 0.0f;
            float current[2] = {0.0f, 0.0f};
            for (int ch = 0; ch < active_channels; ++ch) {
                float& sample = buffer[frame * static_cast<size_t>(channels) +
                                       static_cast<size_t>(ch)];
                if (!std::isfinite(sample)) sample = 0.0f;
                current[ch] = sample;
                const float k_weighted = high_shelf_[ch].process_sample(
                    high_pass_[ch].process_sample(sample));
                weighted_energy += k_weighted * k_weighted /
                                   static_cast<float>(active_channels);
                frame_peak = std::max(frame_peak, std::fabs(sample));
                // Four-phase cubic reconstruction estimate catches
                // inter-sample overshoot that sample-peak measurement misses.
                const float p0 = previous_sample2_[ch];
                const float p1 = previous_sample_[ch];
                const float p2 = sample;
                const float p3 = p2 + (p2 - p1);
                for (int phase = 1; phase < 4; ++phase) {
                    const float t = static_cast<float>(phase) * 0.25f;
                    const float t2 = t * t, t3 = t2 * t;
                    const float interpolated = 0.5f *
                        (2.0f * p1 + (-p0 + p2) * t +
                         (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
                         (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
                    frame_peak = std::max(frame_peak, std::fabs(interpolated));
                }
                previous_sample2_[ch] = p1;
                previous_sample_[ch] = sample;
            }
            true_peak_ = std::max(frame_peak, true_peak_ * 0.99998f);
            if (weighted_energy > kAbsoluteGateEnergy) {
                integrated_energy_sum_ += weighted_energy;
                ++integrated_samples_;
            }
            smoothed_gain_ = gain_coeff * smoothed_gain_ +
                             (1.0f - gain_coeff) * target_gain;
            for (int ch = 0; ch < active_channels; ++ch) {
                buffer[frame * static_cast<size_t>(channels) + static_cast<size_t>(ch)] =
                    current[ch] * smoothed_gain_;
            }
        }
        gain_meter_.store(smoothed_gain_, std::memory_order_relaxed);
        true_peak_meter_.store(true_peak_, std::memory_order_relaxed);
        integrated_lufs_meter_.store(integrated_lufs_audio(), std::memory_order_relaxed);
    }

    float integrated_lufs() const noexcept {
        return integrated_lufs_meter_.load(std::memory_order_relaxed);
    }
    float applied_gain() const noexcept { return gain_meter_.load(std::memory_order_relaxed); }
    float true_peak() const noexcept { return true_peak_meter_.load(std::memory_order_relaxed); }

private:
    float integrated_lufs_audio() const noexcept {
        if (integrated_samples_ == 0) return -120.0f;
        const double energy = integrated_energy_sum_ / static_cast<double>(integrated_samples_);
        return energy > 0.0 ? static_cast<float>(-0.691 + 10.0 * std::log10(energy)) : -120.0f;
    }
    static constexpr double kAbsoluteGateEnergy = 1.0e-7;
    static constexpr float kPeakCeiling = 0.89125094f; // -1 dBFS
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    std::atomic<bool> enabled_{false};
    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> target_lufs_{-18.0f};
    std::atomic<float> integrated_lufs_meter_{-120.0f};
    std::atomic<float> gain_meter_{1.0f}, true_peak_meter_{0.0f};
    double integrated_energy_sum_ = 0.0;
    std::uint64_t integrated_samples_ = 0;
    float smoothed_gain_ = 1.0f, true_peak_ = 0.0f;
    float previous_sample_[2] = {0.0f, 0.0f};
    float previous_sample2_[2] = {0.0f, 0.0f};
    Biquad high_pass_[2];
    Biquad high_shelf_[2];
};

}  // namespace futura::engine::dsp
#endif
