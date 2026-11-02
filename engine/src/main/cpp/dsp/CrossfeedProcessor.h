// Frequency-shaped headphone crossfeed with a small, azimuth-dependent ITD.
#ifndef FUTURA_ENGINE_DSP_CROSSFEED_PROCESSOR_H
#define FUTURA_ENGINE_DSP_CROSSFEED_PROCESSOR_H

#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>

#include "dsp/Biquad.h"

namespace futura::engine::dsp {

class CrossfeedProcessor {
public:
    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 1000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        const float azimuth = azimuth_degrees_.load(std::memory_order_relaxed);
        update_delay(sample_rate, azimuth);
        for (auto& filter : crossfeed_lowpass_) {
            filter.set_cutoff(Biquad::Type::LowPass, kPinnaCutoffHz, 0.707f, sample_rate);
        }
    }

    void set_enabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }

    void set_amount(float amount) noexcept {
        if (!std::isfinite(amount)) return;
        amount_.store(clamp(amount, 0.0f, 1.0f), std::memory_order_release);
    }

    void set_azimuth_degrees(float degrees) noexcept {
        if (!std::isfinite(degrees)) return;
        degrees = clamp(degrees, 0.0f, 90.0f);
        azimuth_degrees_.store(degrees, std::memory_order_release);
        const float sr = sample_rate_.load(std::memory_order_acquire);
        if (sr > 0.0f) update_delay(sr, degrees);
    }

    void reset() noexcept {
        delay_left_.fill(0.0f);
        delay_right_.fill(0.0f);
        crossfeed_lowpass_[0].reset();
        crossfeed_lowpass_[1].reset();
        write_index_ = 0;
    }

    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 2 ||
            !enabled_.load(std::memory_order_acquire)) return;
        const float amount = amount_.load(std::memory_order_acquire);
        if (amount <= 0.0f) return;
        const size_t delay = delay_samples_.load(std::memory_order_acquire);
        const float mix = amount * kMaxCrossfeed;

        for (size_t i = 0; i < frames; ++i) {
            float& left = buffer[i * static_cast<size_t>(channels)];
            float& right = buffer[i * static_cast<size_t>(channels) + 1];
            const float in_left = std::isfinite(left) ? left : 0.0f;
            const float in_right = std::isfinite(right) ? right : 0.0f;
            delay_left_[write_index_] = in_left;
            delay_right_[write_index_] = in_right;
            const size_t read = (write_index_ + kDelayCapacity - delay) % kDelayCapacity;
            const float into_left = crossfeed_lowpass_[0].process_sample(delay_right_[read]);
            const float into_right = crossfeed_lowpass_[1].process_sample(delay_left_[read]);
            left = in_left + mix * into_left;
            right = in_right + mix * into_right;
            write_index_ = (write_index_ + 1) % kDelayCapacity;
        }
    }

private:
    static constexpr size_t kDelayCapacity = 2048;
    static constexpr float kPinnaCutoffHz = 1800.0f;
    static constexpr float kMaxCrossfeed = 0.32f;
    static constexpr float kPi = 3.14159265358979323846f;

    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    void update_delay(float sample_rate, float azimuth) noexcept {
        const float seconds = 0.00012f + 0.00055f * std::sin(azimuth * kPi / 180.0f);
        size_t samples = static_cast<size_t>(std::lround(seconds * sample_rate));
        if (samples >= kDelayCapacity) samples = kDelayCapacity - 1;
        delay_samples_.store(samples, std::memory_order_release);
    }

    std::atomic<bool> enabled_{false};
    std::atomic<float> amount_{0.3f};
    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> azimuth_degrees_{35.0f};
    std::atomic<size_t> delay_samples_{16};
    std::array<float, kDelayCapacity> delay_left_{};
    std::array<float, kDelayCapacity> delay_right_{};
    size_t write_index_ = 0;
    Biquad crossfeed_lowpass_[2];
};

}  // namespace futura::engine::dsp
#endif
