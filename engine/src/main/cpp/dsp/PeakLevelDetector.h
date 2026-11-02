// Peak hold, stereo phase correlation, and mid/side goniometer telemetry.
#ifndef FUTURA_ENGINE_DSP_PEAK_LEVEL_DETECTOR_H
#define FUTURA_ENGINE_DSP_PEAK_LEVEL_DETECTOR_H

#include <atomic>
#include <cmath>
#include <cstddef>

namespace futura::engine::dsp {

class PeakLevelDetector {
public:
    void set_sample_rate(float sample_rate) noexcept {
        if (std::isfinite(sample_rate) && sample_rate >= 1000.0f) {
            sample_rate_.store(sample_rate, std::memory_order_release);
        }
    }

    // Read-only meter stage. Release is applied per frame, so meter decay is
    // stable across different render block sizes.
    void process(const float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 1) return;
        const float sr = sample_rate_.load(std::memory_order_acquire);
        const float decay = std::exp(-static_cast<float>(frames) / (sr * kReleaseSeconds));
        float block_left = 0.0f, block_right = 0.0f;
        double cross = 0.0, energy_left = 0.0, energy_right = 0.0;
        double mid_energy = 0.0, side_energy = 0.0;
        size_t valid = 0;
        for (size_t i = 0; i < frames; ++i) {
            const size_t base = i * static_cast<size_t>(channels);
            const float left = std::isfinite(buffer[base]) ? buffer[base] : 0.0f;
            const float right = channels > 1 && std::isfinite(buffer[base + 1])
                              ? buffer[base + 1] : left;
            const float al = std::fabs(left), ar = std::fabs(right);
            if (al > block_left) block_left = al;
            if (ar > block_right) block_right = ar;
            cross += static_cast<double>(left) * right;
            energy_left += static_cast<double>(left) * left;
            energy_right += static_cast<double>(right) * right;
            const double mid = 0.5 * (left + right);
            const double side = 0.5 * (left - right);
            mid_energy += mid * mid;
            side_energy += side * side;
            ++valid;
        }

        float held_left = peak_left_.load(std::memory_order_relaxed) * decay;
        float held_right = peak_right_.load(std::memory_order_relaxed) * decay;
        if (block_left > held_left) held_left = block_left;
        if (block_right > held_right) held_right = block_right;
        peak_left_.store(held_left, std::memory_order_relaxed);
        peak_right_.store(held_right, std::memory_order_relaxed);

        const double denom = std::sqrt(energy_left * energy_right);
        const float corr = denom > 1e-20
                         ? clamp(static_cast<float>(cross / denom), -1.0f, 1.0f)
                         : 0.0f;
        const float alpha = 1.0f - std::exp(-static_cast<float>(valid) / (sr * 0.18f));
        const float old_corr = correlation_.load(std::memory_order_relaxed);
        correlation_.store(old_corr + alpha * (corr - old_corr), std::memory_order_relaxed);
        const double count = static_cast<double>(valid);
        mid_rms_.store(static_cast<float>(std::sqrt(mid_energy / count)), std::memory_order_relaxed);
        side_rms_.store(static_cast<float>(std::sqrt(side_energy / count)), std::memory_order_relaxed);
    }

    float peak_left() const noexcept { return peak_left_.load(std::memory_order_relaxed); }
    float peak_right() const noexcept { return peak_right_.load(std::memory_order_relaxed); }
    float phase_correlation() const noexcept { return correlation_.load(std::memory_order_relaxed); }
    float goniometer_mid_rms() const noexcept { return mid_rms_.load(std::memory_order_relaxed); }
    float goniometer_side_rms() const noexcept { return side_rms_.load(std::memory_order_relaxed); }

private:
    static constexpr float kReleaseSeconds = 0.35f;
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> peak_left_{0.0f}, peak_right_{0.0f};
    std::atomic<float> correlation_{0.0f};
    std::atomic<float> mid_rms_{0.0f}, side_rms_{0.0f};
};

}  // namespace futura::engine::dsp
#endif
