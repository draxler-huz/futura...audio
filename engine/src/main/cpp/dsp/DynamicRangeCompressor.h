// Feed-forward RMS/peak compressor with filtered sidechain and adaptive release.
#ifndef FUTURA_ENGINE_DSP_DYNAMIC_RANGE_COMPRESSOR_H
#define FUTURA_ENGINE_DSP_DYNAMIC_RANGE_COMPRESSOR_H

#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstddef>

#include "dsp/Biquad.h"

namespace futura::engine::dsp {

class DynamicRangeCompressor {
public:
    DynamicRangeCompressor() noexcept { set_sample_rate(48000.0f); }

    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 1000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        const float cutoff = sidechain_hpf_hz_.load(std::memory_order_relaxed);
        for (auto& filter : sidechain_hpf_) {
            filter.set_cutoff(Biquad::Type::HighPass, cutoff, 0.707f, sample_rate);
        }
    }
    void set_enabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }
    void set_parameters(float threshold_db, float ratio,
                        float attack_ms, float release_ms) noexcept {
        if (std::isfinite(threshold_db))
            threshold_db_.store(clamp(threshold_db, -60.0f, 0.0f), std::memory_order_release);
        if (std::isfinite(ratio))
            ratio_.store(clamp(ratio, 1.0f, 20.0f), std::memory_order_release);
        if (std::isfinite(attack_ms))
            attack_ms_.store(clamp(attack_ms, 0.1f, 200.0f), std::memory_order_release);
        if (std::isfinite(release_ms))
            release_ms_.store(clamp(release_ms, 10.0f, 1000.0f), std::memory_order_release);
    }
    void set_sidechain_highpass_hz(float hz) noexcept {
        if (!std::isfinite(hz)) return;
        const float sr = sample_rate_.load(std::memory_order_acquire);
        hz = clamp(hz, 20.0f, sr > 0.0f ? sr * 0.4f : 500.0f);
        sidechain_hpf_hz_.store(hz, std::memory_order_release);
        for (auto& filter : sidechain_hpf_) {
            if (sr > 0.0f) filter.set_cutoff(Biquad::Type::HighPass, hz, 0.707f, sr);
        }
    }
    void set_rms_mix(float mix) noexcept {
        if (std::isfinite(mix)) rms_mix_.store(clamp(mix, 0.0f, 1.0f), std::memory_order_release);
    }

    void reset() noexcept {
        rms_power_ = peak_envelope_ = gain_ = previous_detector_ = 0.0f;
        gain_ = 1.0f;
        sidechain_hpf_[0].reset();
        sidechain_hpf_[1].reset();
    }

    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 1 ||
            !enabled_.load(std::memory_order_acquire)) return;
        const float sr = sample_rate_.load(std::memory_order_acquire);
        const float threshold = threshold_db_.load(std::memory_order_acquire);
        const float ratio = ratio_.load(std::memory_order_acquire);
        const float attack_ms = attack_ms_.load(std::memory_order_acquire);
        const float release_ms = release_ms_.load(std::memory_order_acquire);
        const float rms_mix = rms_mix_.load(std::memory_order_acquire);
        const int active_channels = channels > 1 ? 2 : 1;
        const float attack_coeff = std::exp(-1.0f / (sr * attack_ms * 0.001f));

        for (size_t frame = 0; frame < frames; ++frame) {
            float peak = 0.0f, power = 0.0f;
            for (int ch = 0; ch < active_channels; ++ch) {
                float& sample = buffer[frame * static_cast<size_t>(channels) +
                                       static_cast<size_t>(ch)];
                if (!std::isfinite(sample)) sample = 0.0f;
                const float detector = sidechain_hpf_[ch].process_sample(sample);
                power += detector * detector;
                peak = std::max(peak, std::fabs(detector));
            }
            power /= static_cast<float>(active_channels);
            const float transient = clamp(std::fabs(peak - previous_detector_) * 8.0f, 0.0f, 1.0f);
            previous_detector_ = peak;
            const float adaptive_release_ms = release_ms * (1.0f - 0.55f * transient);
            const float release_coeff = std::exp(-1.0f /
                (sr * adaptive_release_ms * 0.001f));
            const float detector_coeff = power > rms_power_ ? attack_coeff : release_coeff;
            rms_power_ = detector_coeff * rms_power_ + (1.0f - detector_coeff) * power;
            const float peak_coeff = peak > peak_envelope_ ? attack_coeff : release_coeff;
            peak_envelope_ = peak_coeff * peak_envelope_ + (1.0f - peak_coeff) * peak;
            const float rms = std::sqrt(std::max(0.0f, rms_power_));
            const float level = rms_mix * rms + (1.0f - rms_mix) * peak_envelope_;
            const float level_db = level > 1e-12f ? 20.0f * std::log10(level) : -120.0f;
            float target_gain = 1.0f;
            if (ratio > 1.0f && level_db > threshold) {
                const float reduction_db = (level_db - threshold) * (1.0f - 1.0f / ratio);
                target_gain = std::pow(10.0f, -reduction_db * 0.05f);
            }
            const float gain_coeff = target_gain < gain_ ? attack_coeff : release_coeff;
            gain_ = gain_coeff * gain_ + (1.0f - gain_coeff) * target_gain;
            for (int ch = 0; ch < active_channels; ++ch) {
                buffer[frame * static_cast<size_t>(channels) + static_cast<size_t>(ch)] *= gain_;
            }
        }
    }

private:
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    std::atomic<bool> enabled_{false};
    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> threshold_db_{-18.0f}, ratio_{4.0f};
    std::atomic<float> attack_ms_{10.0f}, release_ms_{100.0f};
    std::atomic<float> sidechain_hpf_hz_{100.0f}, rms_mix_{0.7f};
    float rms_power_ = 0.0f, peak_envelope_ = 0.0f;
    float gain_ = 1.0f, previous_detector_ = 0.0f;
    Biquad sidechain_hpf_[2];
};

}  // namespace futura::engine::dsp
#endif
