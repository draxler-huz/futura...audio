// futura-audio/engine/src/main/cpp/dsp/Biquad.h
//
// Phase 4 — Biquad filter (feature #78). Single 2nd-order IIR section.
// The building block for GraphicEq10Band, ParametricFilterDesign, and
// the crossfeed/DC-block stages.
//
//   y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2]
//                  − a1·y[n-1] − a2·y[n-2]
//
// Coefficients are normalised to a0 = 1 before storage.
//
// Types (matching the standard RBJ audio EQ cookbook):
//   LowShelf, HighShelf, Peaking, LowPass, HighPass, BandPass, Notch
//
// Reconfiguration: control thread calls set_* which populates a
// `pending_` slot; the audio thread applies it at the top of the next
// process() call via try_lock. The audio path therefore never waits for
// the control thread, and never sees a half-updated coefficient set.
//
// NaN/Inf: all coefficient math is guarded by isfinite(). A NaN input
// to set_* leaves the previous coefficients in place.
//
// Denormal protection: flushed to zero at each stage output via the
// standard DC-offset trick (add/subtract tiny anti-denormal). Without
// this, sustained near-silent passages can pin a core at 100% on some
// ARM cores.

#ifndef FUTURA_ENGINE_DSP_BIQUAD_H
#define FUTURA_ENGINE_DSP_BIQUAD_H

#include <atomic>
#include <cmath>
#include <cstddef>
#include <exception>
#include <mutex>
#include <string>

namespace futura::engine::dsp {

class Biquad {
public:
    enum class Type {
        LowShelf,
        HighShelf,
        Peaking,
        LowPass,
        HighPass,
        BandPass,
        Notch,
    };

    Biquad() noexcept = default;
    ~Biquad() = default;

    Biquad(const Biquad&) = delete;
    Biquad& operator=(const Biquad&) = delete;
    Biquad(Biquad&&) = delete;
    Biquad& operator=(Biquad&&) = delete;

    // ── Control thread: coefficient setters ───────────────────────
    //
    // All frequencies in Hz. Frequencies are clamped to (0, nyquist).
    // Q must be > 0. gain_db is ignored for non-shelf/peaking types.
    // Non-finite inputs are rejected; existing coefficients preserved.

    // Peak/shelf EQ filter (RBJ "peakingEQ", "lowShelf", "highShelf").
    void set_peak_or_shelf(Type type, float freq_hz, float q,
                           float gain_db, float sample_rate) noexcept {
        if (type != Type::Peaking &&
            type != Type::LowShelf &&
            type != Type::HighShelf) return;
        configure(type, freq_hz, q, gain_db, sample_rate);
    }

    // Cutoff-style filters (LP, HP, BP, Notch).
    void set_cutoff(Type type, float freq_hz, float q,
                    float sample_rate) noexcept {
        if (type == Type::Peaking ||
            type == Type::LowShelf ||
            type == Type::HighShelf) return;
        configure(type, freq_hz, q, 0.0f, sample_rate);
    }

    // Reset filter history. Safe anytime; pending coefficients if any
    // are preserved.
    void reset() noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);
            z1_ = z2_ = 0.0f;
        } catch (...) { /* noexcept */ }
    }

    // ── Audio thread: per-sample processing ───────────────────────
    //
    // Applies the current coefficients to `buffer` in place. Mono only —
    // for stereo, call this twice with separate Biquad instances.
    void process(float* buffer, size_t num_samples) noexcept {
        try {
            if (!buffer || num_samples == 0) return;

            // Non-blocking apply of pending coefficients.
            apply_pending();

            if (!active_.load(std::memory_order_acquire)) return;

            // Load local copies of coefficients and history for the loop.
            // Writing them back at the end avoids per-sample atomic reads.
            const float b0 = b0_, b1 = b1_, b2 = b2_, a1 = a1_, a2 = a2_;
            float z1 = z1_, z2 = z2_;

            // Anti-denormal bias — flushed at each sample.
            constexpr float kAntiDenormal = 1e-20f;
            constexpr float kDenormalFlush = 1e-15f;

            for (size_t i = 0; i < num_samples; ++i) {
                const float x = buffer[i] + kAntiDenormal;
                const float y = b0 * x + z1;
                z1 = b1 * x - a1 * y + z2 + kAntiDenormal;
                z2 = b2 * x - a2 * y;

                // Flush-to-zero on sub-denormal magnitudes.
                if (z1 > -kDenormalFlush && z1 < kDenormalFlush) z1 = 0.0f;
                if (z2 > -kDenormalFlush && z2 < kDenormalFlush) z2 = 0.0f;

                buffer[i] = y;
            }

            z1_ = z1;
            z2_ = z2;
        } catch (...) {
            // Buffer left unchanged on exception.
        }
    }

    // Any thread — last applied coefficients, exposed for inspection.
    float b0() const noexcept { return b0_; }
    float b1() const noexcept { return b1_; }
    float b2() const noexcept { return b2_; }
    float a1() const noexcept { return a1_; }
    float a2() const noexcept { return a2_; }
    bool  is_active() const noexcept {
        return active_.load(std::memory_order_acquire);
    }

private:
    struct Coeffs {
        float b0, b1, b2, a1, a2;
        bool  active;
    };

    // Control-thread-only. Guards the coefficient calculation, then
    // hands the result to the audio thread via pending_.
    void configure(Type type, float freq_hz, float q, float gain_db,
                   float sample_rate) noexcept {
        try {
            if (!std::isfinite(freq_hz) || !std::isfinite(q) ||
                !std::isfinite(gain_db) || !std::isfinite(sample_rate)) {
                set_error("configure: non-finite input");
                return;
            }
            if (sample_rate <= 0.0f) {
                set_error("configure: invalid sample rate");
                return;
            }
            const float nyquist = sample_rate * 0.5f;
            if (freq_hz <= 0.0f) freq_hz = 1.0f;
            if (freq_hz >= nyquist) freq_hz = nyquist * 0.999f;
            if (q <= 0.0f) q = 0.707f;

            const float A     = std::pow(10.0f, gain_db * 0.025f);   // 10^(dB/40)
            const float w0    = 2.0f * static_cast<float>(M_PI) * freq_hz / sample_rate;
            const float cos_w0 = std::cos(w0);
            const float sin_w0 = std::sin(w0);
            const float alpha = sin_w0 / (2.0f * q);

            float b0, b1, b2, a0, a1, a2;

            switch (type) {
                case Type::LowShelf: {
                    const float sqrtA = std::sqrt(A);
                    const float twoSqrtAAlpha =
                        2.0f * sqrtA * alpha;
                    b0 =    A * ((A + 1.0f) - (A - 1.0f) * cos_w0 + twoSqrtAAlpha);
                    b1 =  2.0f * A * ((A - 1.0f) - (A + 1.0f) * cos_w0);
                    b2 =    A * ((A + 1.0f) - (A - 1.0f) * cos_w0 - twoSqrtAAlpha);
                    a0 =        (A + 1.0f) + (A - 1.0f) * cos_w0 + twoSqrtAAlpha;
                    a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cos_w0);
                    a2 =        (A + 1.0f) + (A - 1.0f) * cos_w0 - twoSqrtAAlpha;
                    break;
                }
                case Type::HighShelf: {
                    const float sqrtA = std::sqrt(A);
                    const float twoSqrtAAlpha =
                        2.0f * sqrtA * alpha;
                    b0 =    A * ((A + 1.0f) + (A - 1.0f) * cos_w0 + twoSqrtAAlpha);
                    b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cos_w0);
                    b2 =    A * ((A + 1.0f) + (A - 1.0f) * cos_w0 - twoSqrtAAlpha);
                    a0 =        (A + 1.0f) - (A - 1.0f) * cos_w0 + twoSqrtAAlpha;
                    a1 =  2.0f * ((A - 1.0f) - (A + 1.0f) * cos_w0);
                    a2 =        (A + 1.0f) - (A - 1.0f) * cos_w0 - twoSqrtAAlpha;
                    break;
                }
                case Type::Peaking: {
                    b0 = 1.0f + alpha * A;
                    b1 = -2.0f * cos_w0;
                    b2 = 1.0f - alpha * A;
                    a0 = 1.0f + alpha / A;
                    a1 = -2.0f * cos_w0;
                    a2 = 1.0f - alpha / A;
                    break;
                }
                case Type::LowPass: {
                    b0 = (1.0f - cos_w0) * 0.5f;
                    b1 =  1.0f - cos_w0;
                    b2 = (1.0f - cos_w0) * 0.5f;
                    a0 =  1.0f + alpha;
                    a1 = -2.0f * cos_w0;
                    a2 =  1.0f - alpha;
                    break;
                }
                case Type::HighPass: {
                    b0 =  (1.0f + cos_w0) * 0.5f;
                    b1 = -(1.0f + cos_w0);
                    b2 =  (1.0f + cos_w0) * 0.5f;
                    a0 =   1.0f + alpha;
                    a1 =  -2.0f * cos_w0;
                    a2 =   1.0f - alpha;
                    break;
                }
                case Type::BandPass: {
                    b0 =  alpha;
                    b1 =  0.0f;
                    b2 = -alpha;
                    a0 =  1.0f + alpha;
                    a1 = -2.0f * cos_w0;
                    a2 =  1.0f - alpha;
                    break;
                }
                case Type::Notch: {
                    b0 =  1.0f;
                    b1 = -2.0f * cos_w0;
                    b2 =  1.0f;
                    a0 =  1.0f + alpha;
                    a1 = -2.0f * cos_w0;
                    a2 =  1.0f - alpha;
                    break;
                }
                default:
                    set_error("configure: unsupported type");
                    return;
            }

            if (a0 == 0.0f) { set_error("configure: a0 == 0"); return; }

            Coeffs c;
            c.b0 = b0 / a0;
            c.b1 = b1 / a0;
            c.b2 = b2 / a0;
            c.a1 = a1 / a0;
            c.a2 = a2 / a0;
            c.active = true;

            if (!std::isfinite(c.b0) || !std::isfinite(c.b1) ||
                !std::isfinite(c.b2) || !std::isfinite(c.a1) ||
                !std::isfinite(c.a2)) {
                set_error("configure: non-finite coefficients");
                return;
            }

            std::lock_guard<std::mutex> lock(state_mutex_);
            pending_ = c;
            has_pending_.store(true, std::memory_order_release);
        } catch (const std::exception& e) {
            set_error(std::string("configure: ") + e.what());
        } catch (...) {
            set_error("configure: unknown exception");
        }
    }

    // Audio thread. Applies pending coefficients if any, without
    // blocking on the control thread. On contention, does nothing —
    // the coefficients will be picked up on the next process() call.
    void apply_pending() noexcept {
        try {
            if (!has_pending_.load(std::memory_order_acquire)) return;
            std::unique_lock<std::mutex> lock(state_mutex_, std::try_to_lock);
            if (!lock.owns_lock()) return;
            if (!has_pending_.load(std::memory_order_relaxed)) return;
            b0_ = pending_.b0;
            b1_ = pending_.b1;
            b2_ = pending_.b2;
            a1_ = pending_.a1;
            a2_ = pending_.a2;
            active_.store(pending_.active, std::memory_order_release);
            has_pending_.store(false, std::memory_order_release);
        } catch (...) { /* noexcept */ }
    }

    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    // Rule 7: pending_ + has_pending_ are the cross-thread handoff.
    // Applied b0..a2 + active_ are read on the audio thread — written
    // only under state_mutex_ in apply_pending(); read without lock in
    // process() because the writes happen-before subsequent reads via
    // has_pending_'s release/acquire pairing.
    mutable std::mutex state_mutex_;
    Coeffs             pending_{0,0,0,0,0,false};
    std::atomic<bool>  has_pending_{false};

    // Current applied coefficients (written under state_mutex_, read
    // without lock in process() — see note above).
    float b0_ = 1.0f;
    float b1_ = 0.0f;
    float b2_ = 0.0f;
    float a1_ = 0.0f;
    float a2_ = 0.0f;

    // Filter history — audio-thread-only, no mutex needed.
    float z1_ = 0.0f;
    float z2_ = 0.0f;

    std::atomic<bool>  active_{false};

    mutable std::mutex error_mutex_;
    std::string        last_error_;
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_BIQUAD_H