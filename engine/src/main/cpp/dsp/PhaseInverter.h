// Independent left/right polarity control for diagnostic and routing use.
#ifndef FUTURA_ENGINE_DSP_PHASE_INVERTER_H
#define FUTURA_ENGINE_DSP_PHASE_INVERTER_H

#include <atomic>
#include <cmath>
#include <cstddef>

namespace futura::engine::dsp {

class PhaseInverter {
public:
    void set_inverted(bool left, bool right) noexcept {
        left_inverted_.store(left, std::memory_order_release);
        right_inverted_.store(right, std::memory_order_release);
    }
    void set_left_inverted(bool inverted) noexcept {
        left_inverted_.store(inverted, std::memory_order_release);
    }
    void set_right_inverted(bool inverted) noexcept {
        right_inverted_.store(inverted, std::memory_order_release);
    }
    bool left_inverted() const noexcept {
        return left_inverted_.load(std::memory_order_acquire);
    }
    bool right_inverted() const noexcept {
        return right_inverted_.load(std::memory_order_acquire);
    }

    void process(float* buffer, size_t frames, int channels = 2) const noexcept {
        if (!buffer || frames == 0 || channels < 1) return;
        const bool invert_left = left_inverted();
        const bool invert_right = channels > 1 && right_inverted();
        for (size_t i = 0; i < frames; ++i) {
            float& left = buffer[i * static_cast<size_t>(channels)];
            if (invert_left && std::isfinite(left)) left = -left;
            if (invert_right) {
                float& right = buffer[i * static_cast<size_t>(channels) + 1];
                if (std::isfinite(right)) right = -right;
            }
        }
    }

private:
    std::atomic<bool> left_inverted_{false};
    std::atomic<bool> right_inverted_{false};
};

}  // namespace futura::engine::dsp
#endif
