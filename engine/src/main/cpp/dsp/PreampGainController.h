// futura-audio/engine/src/main/cpp/dsp/PreampGainController.h
//
// Phase 4 — Preamp gain stage (feature #80). Global gain applied before
// the EQ chain to compensate for the volume increase produced by EQ
// boosts and to prevent downstream clipping.
//
// Range: -20dB to +20dB, matching DspBridge.kt's DspChainConfig.preampDb
// documented range and DspChain::set_preamp_db clamping.
//
// Not to be confused with:
//   • AutomaticVolumeLimiter  — feature #94, safety limiter (dsp/, future)
//   • AutoGainControlEngine   — feature #188, dynamic AGC (dsp/, future)
//   • Master volume           — Engine::setVolumeLinear / setVolumeDb,
//                                applied later in the chain

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

    // Control thread. db is clamped to [-20, +20].
    void set_gain_db(float db) noexcept {
        try {
            // NaN fails every comparison and would pass the clamp, then
            // turn the gain into NaN and corrupt the audio. Treat it as 0 dB.
            if (std::isnan(db)) db = 0.0f;
            if (db < MIN_DB) db = MIN_DB;
            if (db > MAX_DB) db = MAX_DB;

            // dB → linear: 10^(db/20). At db=0 this is exactly 1.0.
            const float linear = (db == 0.0f) ? 1.0f
                                : static_cast<float>(std::pow(10.0f, db * 0.05f));

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
    // No-op when gain_linear == 1.0f (unity pass-through, the default).
    void process(float* buffer, size_t total_samples) const noexcept {
        try {
            const float g = gain_linear_.load(std::memory_order_acquire);
            if (g == 1.0f || !buffer || total_samples == 0) return;
            for (size_t i = 0; i < total_samples; ++i) {
                buffer[i] *= g;
            }
        } catch (...) {
            // Rule 6. Buffer left unchanged on exception (safe — will be
            // audible as missing preamp, not as corruption).
        }
    }

private:
    static constexpr float MIN_DB = -20.0f;
    static constexpr float MAX_DB =  20.0f;

    // Rule 7: two atomics updated under setter_mutex_ so audio-thread
    // readers see a consistent (db, linear) pair. Uses release/acquire
    // ordering: the linear store's release pairs with the audio-thread
    // load's acquire, so once a new linear value is visible, so is the
    // corresponding db value.
    mutable std::mutex setter_mutex_;
    std::atomic<float> gain_db_{0.0f};
    std::atomic<float> gain_linear_{1.0f};
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_PREAMP_GAIN_CONTROLLER_H
