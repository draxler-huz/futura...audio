// TPDF dither with high-pass error feedback for 16-/24-bit output reduction.
#ifndef FUTURA_ENGINE_DSP_DITHER_GENERATOR_H
#define FUTURA_ENGINE_DSP_DITHER_GENERATOR_H

#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace futura::engine::dsp {

class DitherGenerator {
public:
    static constexpr int MAX_CHANNELS = 8;

    void set_enabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }
    void set_output_bit_depth(int bits) noexcept {
        if (bits == 16 || bits == 24 || bits == 32) {
            bit_depth_.store(bits, std::memory_order_release);
        }
    }

    // Only quantizes when reducing below float32. Call immediately before
    // conversion to the selected integer PCM format.
    void process(float* buffer, size_t frames, int channels,
                 int output_bits = 32) noexcept {
        if (!buffer || frames == 0 || channels < 1 ||
            !enabled_.load(std::memory_order_acquire)) return;
        const int bits = output_bits == 32
                       ? bit_depth_.load(std::memory_order_acquire) : output_bits;
        if (bits >= 32 || (bits != 16 && bits != 24)) return;
        const int active_channels = channels < MAX_CHANNELS ? channels : MAX_CHANNELS;
        const double scale = static_cast<double>((std::uint64_t{1} << (bits - 1)) - 1);
        const double noise_amplitude = 1.0 / scale;

        for (size_t frame = 0; frame < frames; ++frame) {
            for (int ch = 0; ch < active_channels; ++ch) {
                float& sample = buffer[frame * static_cast<size_t>(channels) +
                                       static_cast<size_t>(ch)];
                if (!std::isfinite(sample)) {
                    sample = 0.0f;
                    continue;
                }
                const float a = uniform(ch);
                const float b = uniform(ch);
                const float tpdf = a - b;
                const float high_pass_noise = tpdf - previous_noise_[ch];
                previous_noise_[ch] = tpdf;

                // A stable second-order noise-transfer approximation pushes
                // quantization error upward in frequency without runaway feedback.
                const double shaped = static_cast<double>(sample) +
                    1.35 * error_1_[ch] - 0.45 * error_2_[ch] +
                    0.5 * noise_amplitude * high_pass_noise;
                const double limited = shaped < -1.0 ? -1.0 : (shaped > 1.0 ? 1.0 : shaped);
                const double quantized = std::nearbyint(limited * scale) / scale;
                error_2_[ch] = error_1_[ch];
                error_1_[ch] = clamp(static_cast<float>(shaped - quantized),
                                     -2.0f * static_cast<float>(noise_amplitude),
                                      2.0f * static_cast<float>(noise_amplitude));
                sample = static_cast<float>(quantized);
            }
        }
    }

private:
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }
    static std::uint32_t next(std::uint32_t& state) noexcept {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return state;
    }
    float uniform(int channel) noexcept {
        const std::uint32_t value = next(random_state_[channel]);
        return static_cast<float>(value >> 8) * (1.0f / 16777216.0f);
    }

    std::atomic<bool> enabled_{true};
    std::atomic<int> bit_depth_{32};
    std::array<std::uint32_t, MAX_CHANNELS> random_state_{
        0x1a2b3c4du, 0x5e6f7081u, 0x91a2b3c5u, 0xd4e5f607u,
        0x31415927u, 0x27182819u, 0xabcdef01u, 0x10203041u
    };
    std::array<float, MAX_CHANNELS> previous_noise_{};
    std::array<float, MAX_CHANNELS> error_1_{}, error_2_{};
};

}  // namespace futura::engine::dsp
#endif
