// Compact, profile-selectable virtual-speaker FIR rendering for headphones.
#ifndef FUTURA_ENGINE_DSP_STEREO_TO_BINAURAL_CONVERTER_H
#define FUTURA_ENGINE_DSP_STEREO_TO_BINAURAL_CONVERTER_H

#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <mutex>

namespace futura::engine::dsp {

class StereoToBinauralConverter {
public:
    static constexpr size_t IR_TAPS = 256;
    enum class Profile : int { MixingStudio = 0, MasteringRoom = 1, IntimateClub = 2 };

    StereoToBinauralConverter() noexcept { build_impulses(); }

    void set_sample_rate(float sample_rate) noexcept {
        if (!std::isfinite(sample_rate) || sample_rate < 8000.0f) return;
        sample_rate_.store(sample_rate, std::memory_order_release);
        rebuild_pending();
    }
    void set_enabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }
    void set_profile(Profile profile) noexcept {
        const int value = static_cast<int>(profile);
        if (value < 0 || value > 2) return;
        profile_.store(value, std::memory_order_release);
        rebuild_pending();
    }
    void set_azimuth_degrees(float degrees) noexcept {
        if (!std::isfinite(degrees)) return;
        azimuth_.store(clamp(degrees, -90.0f, 90.0f), std::memory_order_release);
        rebuild_pending();
    }
    void set_distance_meters(float meters) noexcept {
        if (!std::isfinite(meters)) return;
        distance_.store(clamp(meters, 0.5f, 5.0f), std::memory_order_release);
        rebuild_pending();
    }

    void reset() noexcept {
        for (auto& channel : history_) channel.fill(0.0f);
        write_index_ = 0;
    }

    void process(float* buffer, size_t frames, int channels = 2) noexcept {
        if (!buffer || frames == 0 || channels < 2 ||
            !enabled_.load(std::memory_order_acquire)) return;
        apply_pending();
        for (size_t frame = 0; frame < frames; ++frame) {
            const size_t base = frame * static_cast<size_t>(channels);
            const float left = std::isfinite(buffer[base]) ? buffer[base] : 0.0f;
            const float right = std::isfinite(buffer[base + 1]) ? buffer[base + 1] : 0.0f;
            history_[0][write_index_] = left;
            history_[1][write_index_] = right;
            double out_left = 0.0, out_right = 0.0;
            size_t read = write_index_;
            for (size_t tap = 0; tap < IR_TAPS; ++tap) {
                out_left += static_cast<double>(ir_[0][0][tap]) * history_[0][read] +
                            static_cast<double>(ir_[1][0][tap]) * history_[1][read];
                out_right += static_cast<double>(ir_[0][1][tap]) * history_[0][read] +
                             static_cast<double>(ir_[1][1][tap]) * history_[1][read];
                read = read == 0 ? IR_TAPS - 1 : read - 1;
            }
            buffer[base] = static_cast<float>(out_left);
            buffer[base + 1] = static_cast<float>(out_right);
            write_index_ = (write_index_ + 1) % IR_TAPS;
        }
    }

private:
    using ImpulseSet = std::array<std::array<std::array<float, IR_TAPS>, 2>, 2>;
    static constexpr float kPi = 3.14159265358979323846f;

    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }
    void rebuild_pending() noexcept {
        try {
            ImpulseSet generated{};
            const float sr = sample_rate_.load(std::memory_order_acquire);
            const float azimuth = azimuth_.load(std::memory_order_acquire);
            const float distance = distance_.load(std::memory_order_acquire);
            const int profile = profile_.load(std::memory_order_acquire);
            const float side = std::sin(std::fabs(azimuth) * kPi / 180.0f);
            const size_t itd = static_cast<size_t>(clamp(
                std::round((0.00018f + 0.00048f * side) * sr), 1.0f, 40.0f));
            const float distance_gain = 1.0f / (1.0f + 0.12f * (distance - 0.5f));
            const float cross_gain = 0.20f + 0.08f * (1.0f - side);
            const float room_decay = profile == 0 ? 0.90f : (profile == 1 ? 0.72f : 0.96f);
            const float room_level = profile == 0 ? 0.08f : (profile == 1 ? 0.035f : 0.12f);

            for (int source = 0; source < 2; ++source) {
                for (int ear = 0; ear < 2; ++ear) {
                    auto& ir = generated[source][ear];
                    const bool near_ear = source == ear;
                    const size_t direct_delay = near_ear ? 0 : itd;
                    const float direct = distance_gain * (near_ear ? 0.78f : cross_gain);
                    for (size_t n = 0; n < IR_TAPS; ++n) {
                        const float t = static_cast<float>(n) / static_cast<float>(IR_TAPS);
                        const float pinna = 1.0f - (near_ear ? 0.16f : 0.45f) *
                                            std::pow(t, 1.2f);
                        const float room = room_level * std::pow(room_decay, static_cast<float>(n)) *
                                           std::sin(0.17f * static_cast<float>(n) +
                                                    static_cast<float>(source + ear));
                        ir[n] = room * pinna;
                        if (n == direct_delay) ir[n] += direct * pinna;
                        // Small early reflection provides profile-dependent
                        // enclosure without allocating an external HRTF asset.
                        const size_t reflection = direct_delay + 17 +
                            static_cast<size_t>(profile) * 5;
                        if (n == reflection && n < IR_TAPS) ir[n] += 0.055f * room_decay;
                    }
                    // A short pinna notch/high-frequency contour in the taps.
                    for (size_t n = 1; n < IR_TAPS; ++n) {
                        ir[n] -= 0.12f * ir[n - 1] *
                                 std::cos(2.0f * kPi * 0.22f);
                    }
                }
            }
            std::lock_guard<std::mutex> lock(pending_mutex_);
            pending_ir_ = generated;
            pending_.store(true, std::memory_order_release);
        } catch (...) { /* retain the last valid response */ }
    }

    void apply_pending() noexcept {
        if (!pending_.load(std::memory_order_acquire)) return;
        std::unique_lock<std::mutex> lock(pending_mutex_, std::try_to_lock);
        if (!lock.owns_lock() || !pending_.load(std::memory_order_relaxed)) return;
        ir_ = pending_ir_;
        pending_.store(false, std::memory_order_release);
    }

    void build_impulses() noexcept {
        rebuild_pending();
        apply_pending();
    }

    std::atomic<bool> enabled_{false};
    std::atomic<int> profile_{0};
    std::atomic<float> sample_rate_{48000.0f};
    std::atomic<float> azimuth_{30.0f}, distance_{1.5f};
    std::array<std::array<float, IR_TAPS>, 2> history_{};
    size_t write_index_ = 0;
    ImpulseSet ir_{};
    ImpulseSet pending_ir_{};
    std::mutex pending_mutex_;
    std::atomic<bool> pending_{false};
};

}  // namespace futura::engine::dsp
#endif
