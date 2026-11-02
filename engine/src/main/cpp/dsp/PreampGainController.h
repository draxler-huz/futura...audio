// futura-audio/engine/src/main/cpp/dsp/PreampGainController.h
//
// Phase 4 — Preamp gain stage (feature #80). Global gain applied before
// the EQ chain to compensate for EQ-boost volume increase.
//
// Range: -20dB to +20dB, matching DspChainConfig.preampDb and
// DspChain::set_preamp_db clamping.
//
// NaN/Inf handling: set_gain_db rejects non-finite inputs and keeps the
// previous value. NaN fails every comparison so `clamp` alone would let
// it through and pow(NaN) → NaN gain → permanent audio corruption (NaN
// contamination is sticky through multiplications). Rejected explicitly.

#ifndef FUTURA_ENGINE_DSP_PREAMP_GAIN_CONTROLLER_H
#define FUTURA_ENGINE_DSP_PREAMP_GAIN_CONTROLLER_H

#include <atomic>
#include <cmath>
#include <cstddef>
#include <exception>
#include <mutex>
#include <string>

namespace futura::engine::dsp {

class PreampGainController {
public:
    PreampGainController() noexcept = default;
    ~PreampGainController() = default;

    PreampGainController(const PreampGainController&) = delete;
    PreampGainController& operator=(const PreampGainController&) = delete;
    PreampGainController(PreampGainController&&) = delete;
    PreampGainController& operator=(PreampGainController&&) = delete;

    // Control thread. db is clamped to [-20, +20]. Non-finite input
    // (NaN, +Inf, -Inf) is rejected — the previous value is retained.
    void set_gain_db(float db) noexcept {
        try {
            if (!std::isfinite(db)) {
                // Log via last_error but do not throw — callers may be
                // sending values from a slider that occasionally emits
                // NaN during a drag.
                set_error("set_gain_db: non-finite input rejected");
                return;
            }
            if (db < MIN_DB) db = MIN_DB;
            if (db > MAX_DB) db = MAX_DB;

            const float linear = (db == 0.0f) ? 1.0f
                                : static_cast<float>(std::pow(10.0f, db * 0.05f));

            // Defensive: pow with a finite exponent in [-1, 1] is always
            // finite, but check anyway so a future range change cannot
            // accidentally publish NaN/Inf into the audio path.
            if (!std::isfinite(linear)) {
                set_error("set_gain_db: non-finite linear gain computed");
                return;
            }

            std::lock_guard<std::mutex> lock(setter_mutex_);
            gain_db_.store(db, std::memory_order_relaxed);
            gain_linear_.store(linear, std::memory_order_release);
        } catch (...) { /* noexcept */ }
    }

    // Any thread.
    float gain_db() const noexcept {
        return gain_db_.load(std::memory_order_acquire);
    }

    // Any thread.
    float gain_linear() const noexcept {
        return gain_linear_.load(std::memory_order_acquire);
    }

    // Audio thread. In-place multiply by current gain. Lock-free read.
    // No-op when gain == 1.0. Non-finite gain (should never occur, but
    // defensively guarded) is treated as unity to avoid contaminating
    // the buffer with NaN.
    void process(float* buffer, size_t total_samples) const noexcept {
        try {
            const float g = gain_linear_.load(std::memory_order_acquire);
            if (!std::isfinite(g)) return;      // unity pass-through
            if (g == 1.0f || !buffer || total_samples == 0) return;
            for (size_t i = 0; i < total_samples; ++i) {
                buffer[i] *= g;
            }
        } catch (...) {
            // Buffer left unchanged on exception.
        }
    }

private:
    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    static constexpr float MIN_DB = -20.0f;
    static constexpr float MAX_DB =  20.0f;

    mutable std::mutex setter_mutex_;
    std::atomic<float> gain_db_{0.0f};
    std::atomic<float> gain_linear_{1.0f};

    mutable std::mutex error_mutex_;
    std::string        last_error_;
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_PREAMP_GAIN_CONTROLLER_H