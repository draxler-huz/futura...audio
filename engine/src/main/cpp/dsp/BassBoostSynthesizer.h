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
#include <cmath>
#include <cstddef>
#include <exception>

namespace futura::engine::dsp {

class BassBoostSynthesizer {
public:
    BassBoostSynthesizer() noexcept = default;
    ~BassBoostSynthesizer() = default;

    BassBoostSynthesizer(const BassBoostSynthesizer&) = delete;
    BassBoostSynthesizer& operator=(const BassBoostSynthesizer&) = delete;
    BassBoostSynthesizer(BassBoostSynthesizer&&) = delete;
    BassBoostSynthesizer& operator=(BassBoostSynthesizer&&) = delete;

    // Control thread. Must be called before the first process().
    void set_sample_rate(float sample_rate) noexcept {
        try {
            if (!std::isfinite(sample_rate) || sample_rate <= 0.0f) return;
            lp_coeff_.store(std::exp(-kTwoPi * CROSSOVER_HZ / sample_rate),
                            std::memory_order_release);
            hp_coeff_.store(std::exp(-kTwoPi * HARMONIC_DC_HZ / sample_rate),
                            std::memory_order_release);
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
        try {
            if (!buffer || num_frames == 0) return;
            const float amount = amount_.load(std::memory_order_acquire);
            if (amount <= 0.0f) return;

            const float lpc = lp_coeff_.load(std::memory_order_acquire);
            const float hpc = hp_coeff_.load(std::memory_order_acquire);
            const float mix = amount * 0.5f;

            for (size_t i = 0; i < num_frames; ++i) {
                for (int ch = 0; ch < 2; ++ch) {
                    float& x = buffer[i * 2 + static_cast<size_t>(ch)];

                    // One-pole low-pass isolates the bass band.
                    lp_state_[ch] = lpc * lp_state_[ch] + (1.0f - lpc) * x;

                    // Even-order distortion: lp * |lp| is a full-wave
                    // harmonic source with energy at 2x the bass band.
                    const float lp = lp_state_[ch];
                    const float harmonic = lp * std::fabs(lp);

                    // DC-block the harmonic so it adds no offset.
                    const float blocked =
                        hpc * (hp_out_[ch] + harmonic - hp_in_[ch]);
                    hp_in_[ch] = harmonic;
                    hp_out_[ch] = blocked;

                    x += mix * blocked;
                }
            }
        } catch (...) {
            // Rule 6: buffer may be partially processed; it stays valid audio.
        }
    }

private:
    static constexpr float kTwoPi = 6.28318530717958647692f;
    static constexpr float CROSSOVER_HZ = 120.0f;
    static constexpr float HARMONIC_DC_HZ = 10.0f;

    std::atomic<float> amount_{0.0f};
    std::atomic<float> lp_coeff_{0.0f};
    std::atomic<float> hp_coeff_{0.0f};

    // Audio-thread-only state.
    float lp_state_[2] = {0.0f, 0.0f};
    float hp_in_[2] = {0.0f, 0.0f};
    float hp_out_[2] = {0.0f, 0.0f};
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_BASS_BOOST_SYNTHESIZER_H
