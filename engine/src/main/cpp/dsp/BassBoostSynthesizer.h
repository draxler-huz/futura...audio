// futura-audio/engine/src/main/cpp/dsp/BassBoostSynthesizer.h
//
// Phase 4 — bass boost stage (DspChain GROWTH seam, config bass_boost).
// Harmonic synthesis, not a low shelf: the low band is isolated, an
// even-order harmonic is generated from it, and the harmonic is mixed back
// in. Perceived bass gets fuller on small speakers without raising sub-bass
// energy.
//
// Input: interleaved stereo float32 only. Setters run on the control thread;
// process() runs on the audio thread and never allocates or locks.

#ifndef FUTURA_ENGINE_DSP_BASS_BOOST_SYNTHESIZER_H
#define FUTURA_ENGINE_DSP_BASS_BOOST_SYNTHESIZER_H

#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <exception>

#include "dsp/Biquad.h"

namespace futura::engine::dsp {

class BassBoostSynthesizer {
public:
    BassBoostSynthesizer() noexcept { set_sample_rate(48000.0f); }
    ~BassBoostSynthesizer() = default;

    BassBoostSynthesizer(const BassBoostSynthesizer&) = delete;
    BassBoostSynthesizer& operator=(const BassBoostSynthesizer&) = delete;
    BassBoostSynthesizer(BassBoostSynthesizer&&) = delete;
    BassBoostSynthesizer& operator=(BassBoostSynthesizer&&) = delete;

    // Control thread. Must be called before the first process().
    void set_sample_rate(float sample_rate) noexcept {
        try {
            if (!std::isfinite(sample_rate) || sample_rate <= 0.0f) return;
            sample_rate_.store(sample_rate, std::memory_order_release);
            for (int ch = 0; ch < 2; ++ch) {
                low_band_[ch].set_cutoff(Biquad::Type::LowPass, CROSSOVER_HZ, 0.707f, sample_rate);
                upper_band_[ch].set_cutoff(Biquad::Type::LowPass, UPPER_BAND_HZ, 0.707f, sample_rate);
                harmonic_hp_[ch].set_cutoff(Biquad::Type::HighPass, HARMONIC_DC_HZ, 0.707f, sample_rate);
            }
        } catch (...) { /* noexcept */ }
    }

    // Control thread. Amount is clamped to [0, 1]; 0 bypasses the stage.
    // Non-finite input is ignored.
    void set_amount(float amount) noexcept {
        try {
            if (!std::isfinite(amount)) return;
            if (amount < 0.0f) amount = 0.0f;
            if (amount > 1.0f) amount = 1.0f;
            amount_.store(amount, std::memory_order_release);
        } catch (...) { /* noexcept */ }
    }

    float amount() const noexcept {
        return amount_.load(std::memory_order_acquire);
    }

    // Audio thread. In-place on interleaved stereo frames.
    void process(float* buffer, size_t num_frames) noexcept {
        process(buffer, num_frames, 2);
    }

    void process(float* buffer, size_t num_frames, int channels) noexcept {
        try {
            if (!buffer || num_frames == 0 || channels < 1) return;
            const float amount = amount_.load(std::memory_order_acquire);
            if (amount <= 0.0f) return;

            const int active_channels = channels > 1 ? 2 : 1;
            const float mix = amount * 0.42f;

            for (size_t i = 0; i < num_frames; ++i) {
                for (int ch = 0; ch < active_channels; ++ch) {
                    float& x = buffer[i * static_cast<size_t>(channels) +
                                      static_cast<size_t>(ch)];
                    if (!std::isfinite(x)) { x = 0.0f; continue; }
                    const float low = low_band_[ch].process_sample(x);
                    const float upper = upper_band_[ch].process_sample(x);
                    const float fast = std::max(std::fabs(low), 0.65f * std::fabs(upper));
                    const float attack = 1.0f - std::exp(-1.0f /
                        (sample_rate_.load(std::memory_order_relaxed) * kAttackSeconds));
                    const float release = 1.0f - std::exp(-1.0f /
                        (sample_rate_.load(std::memory_order_relaxed) * kReleaseSeconds));
                    const float env_coeff = fast > envelope_[ch] ? attack : release;
                    envelope_[ch] += env_coeff * (fast - envelope_[ch]);
                    const float transient = clamp((fast - envelope_[ch]) /
                        std::max(fast, 1e-5f) * kTransientSensitivity, 0.0f, 1.0f);

                    // Rectification creates upper harmonics only while a
                    // genuine low-band transient is present; the HP filter
                    // removes the resulting DC and subsonic energy.
                    const float shaped = low * std::fabs(low) +
                                         0.45f * upper * std::fabs(upper);
                    const float harmonic = harmonic_hp_[ch].process_sample(shaped);
                    x += mix * transient * harmonic;
                }
            }
        } catch (...) {
            // Rule 6: buffer may be partially processed; it stays valid audio.
        }
    }

private:
    static constexpr float CROSSOVER_HZ = 120.0f;
    static constexpr float UPPER_BAND_HZ = 210.0f;
    static constexpr float HARMONIC_DC_HZ = 12.0f;
    static constexpr float kAttackSeconds = 0.008f;
    static constexpr float kReleaseSeconds = 0.12f;
    static constexpr float kTransientSensitivity = 4.0f;
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    std::atomic<float> amount_{0.0f};
    std::atomic<float> sample_rate_{48000.0f};
    float envelope_[2] = {0.0f, 0.0f};
    Biquad low_band_[2], upper_band_[2], harmonic_hp_[2];
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_BASS_BOOST_SYNTHESIZER_H
