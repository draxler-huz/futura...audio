// Modulated Schroeder-Moorer reverb with stereo-decorrelated delay networks.
#ifndef FUTURA_ENGINE_DSP_FREEVERB_ENGINE_H
#define FUTURA_ENGINE_DSP_FREEVERB_ENGINE_H

#include <array>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>

namespace futura::engine::dsp {

class FreeverbEngine {
public:
    static constexpr size_t COMB_COUNT = 8;
    static constexpr size_t ALLPASS_COUNT = 4;
    static constexpr size_t MAX_COMB_DELAY = 8192;
    static constexpr size_t MAX_ALLPASS_DELAY = 2048;

    FreeverbEngine() noexcept { set_sample_rate(48000.0f); }

    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 1000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        for (int ch = 0; ch < 2; ++ch) {
            for (size_t i = 0; i < COMB_COUNT; ++i) {
                const float ms = 29.7f + static_cast<float>(i) * 1.13f;
                size_t length = static_cast<size_t>(std::lround(sample_rate * ms * 0.001f));
                length += static_cast<size_t>(ch) * 23;
                if (length < 1) length = 1;
                if (length >= MAX_COMB_DELAY) length = MAX_COMB_DELAY - 1;
                comb_length_[ch][i] = length;
            }
            for (size_t i = 0; i < ALLPASS_COUNT; ++i) {
                const float ms = 5.1f + static_cast<float>(i) * 0.83f;
                size_t length = static_cast<size_t>(std::lround(sample_rate * ms * 0.001f));
                length += static_cast<size_t>(ch) * 11;
                if (length < 1) length = 1;
                if (length >= MAX_ALLPASS_DELAY) length = MAX_ALLPASS_DELAY - 1;
                allpass_length_[ch][i] = length;
            }
        }
    }

    void set_enabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }
    void set_room_size(float value) noexcept {
        if (std::isfinite(value)) room_size_.store(clamp(value, 0.0f, 1.0f), std::memory_order_release);
    }
    void set_damping(float value) noexcept {
        if (std::isfinite(value)) damping_.store(clamp(value, 0.0f, 1.0f), std::memory_order_release);
    }
    void set_wet_level(float value) noexcept {
        if (std::isfinite(value)) wet_.store(clamp(value, 0.0f, 1.0f), std::memory_order_release);
    }
    void set_parameters(float room, float damping, float wet) noexcept {
        set_room_size(room);
        set_damping(damping);
        set_wet_level(wet);
    }

    void reset() noexcept {
        for (auto& channel : comb_buffer_)
            for (auto& line : channel) line.fill(0.0f);
        for (auto& channel : allpass_buffer_)
            for (auto& line : channel) line.fill(0.0f);
        for (auto& channel : comb_write_)
            for (auto& index : channel) index = 0;
        for (auto& channel : allpass_write_)
            for (auto& index : channel) index = 0;
        for (auto& channel : damping_state_)
            for (auto& value : channel) value = 0.0f;
        for (auto& channel : lfo_phase_)
            for (auto& value : channel) value = 0.0f;
    }

    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 1 ||
            !enabled_.load(std::memory_order_acquire)) return;
        const float wet = wet_.load(std::memory_order_acquire);
        if (wet <= 0.0f) return;
        const float feedback = 0.70f + 0.28f * room_size_.load(std::memory_order_acquire);
        const float damping = 0.04f + 0.86f * damping_.load(std::memory_order_acquire);
        const float sample_rate = sample_rate_.load(std::memory_order_relaxed);
        const int active_channels = channels > 1 ? 2 : 1;

        for (size_t frame = 0; frame < frames; ++frame) {
            float input[2] = {0.0f, 0.0f};
            for (int ch = 0; ch < active_channels; ++ch) {
                const float value = buffer[frame * static_cast<size_t>(channels) +
                                           static_cast<size_t>(ch)];
                input[ch] = std::isfinite(value) ? value : 0.0f;
            }
            float wet_out[2] = {0.0f, 0.0f};

            for (int ch = 0; ch < active_channels; ++ch) {
                const int other = active_channels > 1 ? 1 - ch : ch;
                for (size_t line = 0; line < COMB_COUNT; ++line) {
                    const float phase = lfo_phase_[ch][line];
                    const float modulation = sample_rate * 0.0004f * std::sin(phase);
                    size_t delay = comb_length_[ch][line] +
                                   static_cast<int>(std::lround(modulation));
                    if (delay < 1) delay = 1;
                    if (delay >= MAX_COMB_DELAY) delay = MAX_COMB_DELAY - 1;
                    const size_t read = (comb_write_[ch][line] +
                                         MAX_COMB_DELAY - delay) % MAX_COMB_DELAY;
                    const float delayed = comb_buffer_[ch][line][read];
                    float& filtered = damping_state_[ch][line];
                    filtered = delayed * (1.0f - damping) + filtered * damping;
                    const float decorrelated_input = input[ch] +
                        0.025f * (input[other] - input[ch]) * (line % 2 == 0 ? 1.0f : -1.0f);
                    comb_buffer_[ch][line][comb_write_[ch][line]] =
                        decorrelated_input + filtered * feedback;
                    comb_write_[ch][line] =
                        (comb_write_[ch][line] + 1) % MAX_COMB_DELAY;
                    wet_out[ch] += delayed;
                    lfo_phase_[ch][line] += 2.0f * kPi *
                        (0.07f + static_cast<float>(line) * 0.006f) /
                        sample_rate;
                    if (lfo_phase_[ch][line] >= 2.0f * kPi) {
                        lfo_phase_[ch][line] -= 2.0f * kPi;
                    }
                }

                float value = wet_out[ch] * (0.18f / static_cast<float>(COMB_COUNT));
                for (size_t line = 0; line < ALLPASS_COUNT; ++line) {
                    const size_t delay = allpass_length_[ch][line];
                    const size_t index = allpass_write_[ch][line];
                    const float delayed = allpass_buffer_[ch][line][index];
                    const float output = delayed - value;
                    allpass_buffer_[ch][line][index] = value + delayed * 0.5f;
                    allpass_write_[ch][line] = (index + 1) % delay;
                    value = output;
                }
                wet_out[ch] = value;
            }

            for (int ch = 0; ch < active_channels; ++ch) {
                float& out = buffer[frame * static_cast<size_t>(channels) +
                                    static_cast<size_t>(ch)];
                out = input[ch] * (1.0f - wet * 0.5f) + wet_out[ch] * wet;
            }
        }
    }

private:
    static constexpr float kPi = 3.14159265358979323846f;
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    std::atomic<bool> enabled_{false};
    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> room_size_{0.5f}, damping_{0.5f}, wet_{0.3f};
    size_t comb_length_[2][COMB_COUNT]{};
    size_t allpass_length_[2][ALLPASS_COUNT]{};
    size_t comb_write_[2][COMB_COUNT]{};
    size_t allpass_write_[2][ALLPASS_COUNT]{};
    float damping_state_[2][COMB_COUNT]{};
    float lfo_phase_[2][COMB_COUNT]{};
    std::array<std::array<std::array<float, MAX_COMB_DELAY>, COMB_COUNT>, 2> comb_buffer_{};
    std::array<std::array<std::array<float, MAX_ALLPASS_DELAY>, ALLPASS_COUNT>, 2> allpass_buffer_{};
};

}  // namespace futura::engine::dsp
#endif
