// Frequency-dependent mid/side width control. The side signal is high-pass
// crossed over so sub-bass remains mono; the mid signal is never filtered.
#ifndef FUTURA_ENGINE_DSP_STEREO_WIDTH_EXPANDER_H
#define FUTURA_ENGINE_DSP_STEREO_WIDTH_EXPANDER_H

#include <atomic>
#include <cmath>
#include <cstddef>

#include "dsp/Biquad.h"

namespace futura::engine::dsp {

class StereoWidthExpander {
public:
    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 1000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        high_pass_.set_cutoff(Biquad::Type::HighPass,
                              crossover_hz_.load(std::memory_order_relaxed),
                              0.707f, sample_rate);
    }

    void set_crossover_hz(float hz) noexcept {
        if (!std::isfinite(hz)) return;
        const float sr = sample_rate_.load(std::memory_order_acquire);
        hz = clamp(hz, 40.0f, sr > 0.0f ? sr * 0.4f : 400.0f);
        crossover_hz_.store(hz, std::memory_order_release);
        if (sr > 0.0f) high_pass_.set_cutoff(Biquad::Type::HighPass, hz, 0.707f, sr);
    }

    void set_width(float width) noexcept {
        if (!std::isfinite(width)) return;
        width_.store(clamp(width, 0.0f, 2.0f), std::memory_order_release);
    }

    float width() const noexcept {
        return width_.load(std::memory_order_acquire);
    }

    // Interleaved audio. Mono and extra channels are left untouched.
    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 2) return;
        const float width = width_.load(std::memory_order_acquire);
        if (width == 1.0f && !high_pass_.is_active()) return;
        for (size_t i = 0; i < frames; ++i) {
            float& left = buffer[i * static_cast<size_t>(channels)];
            float& right = buffer[i * static_cast<size_t>(channels) + 1];
            const float mid = 0.5f * (left + right);
            const float side = 0.5f * (left - right);
            const float high_side = high_pass_.process_sample(side);
            left = mid + width * high_side;
            right = mid - width * high_side;
        }
    }

private:
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> crossover_hz_{300.0f};
    std::atomic<float> width_{1.0f};
    Biquad high_pass_;
};

}  // namespace futura::engine::dsp
#endif
