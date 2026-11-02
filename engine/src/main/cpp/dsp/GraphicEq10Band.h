// futura-audio/engine/src/main/cpp/dsp/GraphicEq10Band.h
//
// Phase 4 — 10-band graphic equalizer (feature #76). Chains ten Biquad
// peaking filters at the standard graphic-EQ frequencies:
//
//     31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
//
// Per-band gain range: -12dB to +12dB, matching DspBridge.kt's
// DspChainConfig.eqBandGainsDb documented range and DspChain's per-band
// clamping.
//
// Q value: 0.707 (Butterworth / critically-damped) — the standard for
// graphic EQs, where adjacent bands overlap smoothly and the "peaking"
// profile is broad rather than notched. Change requires a coordinated
// update in DspChain's coefficient recompute seam.
//
// Threading: set_band_gain is control-thread; process is audio-thread.
// Each Biquad applies its own pending coefficients at the top of its
// process() call (via try_lock in Biquad::apply_pending) — so
    // GraphicEq10Band itself needs no audio-thread mutex.
//
// Sample rate: a band whose centre frequency is at or above 0.98 × nyquist
// is held at 0 dB (identity). At 44.1kHz the 16kHz band stays active; at
// 32kHz it is bypassed. See configure_band.

#ifndef FUTURA_ENGINE_DSP_GRAPHIC_EQ_10_BAND_H
#define FUTURA_ENGINE_DSP_GRAPHIC_EQ_10_BAND_H

#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <exception>

#include "dsp/Biquad.h"

namespace futura::engine::dsp {

class GraphicEq10Band {
public:
    static constexpr int BAND_COUNT = 10;
    static constexpr float Q = 0.707f;
    static constexpr float MIN_GAIN_DB = -12.0f;
    static constexpr float MAX_GAIN_DB =  12.0f;

    GraphicEq10Band() noexcept = default;
    ~GraphicEq10Band() = default;

    GraphicEq10Band(const GraphicEq10Band&) = delete;
    GraphicEq10Band& operator=(const GraphicEq10Band&) = delete;
    GraphicEq10Band(GraphicEq10Band&&) = delete;
    GraphicEq10Band& operator=(GraphicEq10Band&&) = delete;

    // Standard graphic EQ center frequencies in Hz. Public so DspChain
    // can display them in UI without duplicating the array.
    static const float* center_frequencies() noexcept {
        static const float kFreqs[BAND_COUNT] = {
            31.0f, 62.0f, 125.0f, 250.0f, 500.0f,
            1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
        };
        return kFreqs;
    }

    // Must be called once before the first process(). Sets up all ten
    // band biquads with their current gains against the given sample
    // rate. Reconfigures everything.
    void set_sample_rate(float sample_rate) noexcept {
        try {
            if (!std::isfinite(sample_rate) || sample_rate <= 0.0f) return;
            sample_rate_ = sample_rate;
            recompute_bands();
        } catch (...) { /* noexcept */ }
    }

    // Control thread. Gain is clamped to [-12, +12]. Non-finite input is
    // ignored (previous gain retained). Idempotent — calling with the
    // same gain produces no change.
    void set_band_gain(int band_index, float gain_db) noexcept {
        try {
            if (band_index < 0 || band_index >= BAND_COUNT) return;
            if (!std::isfinite(gain_db)) return;
            if (gain_db < MIN_GAIN_DB) gain_db = MIN_GAIN_DB;
            if (gain_db > MAX_GAIN_DB) gain_db = MAX_GAIN_DB;
            if (gain_db == gains_[band_index]) return;   // no-op
            gains_[band_index] = gain_db;
            recompute_bands();
        } catch (...) { /* noexcept */ }
    }

    // Control-thread controls for the psychoacoustic curve and broad-band
    // loudness compensation. A value of zero restores uncoupled EQ behavior.
    void set_tilt_coupling(float amount) noexcept {
        if (!std::isfinite(amount)) return;
        tilt_coupling_.store(clamp(amount, 0.0f, 1.0f), std::memory_order_release);
        recompute_bands();
    }
    void set_auto_compensation(float amount) noexcept {
        if (!std::isfinite(amount)) return;
        auto_compensation_.store(clamp(amount, 0.0f, 1.0f), std::memory_order_release);
        recompute_bands();
    }

    // Any thread.
    float get_band_gain(int band_index) const noexcept {
        if (band_index < 0 || band_index >= BAND_COUNT) return 0.0f;
        return gains_[band_index];
    }

    // Audio thread. In-place process `num_samples` mono float32 samples
    // through all ten bands in order. Order matters: 31Hz → 16kHz
    // matching the standard graphic EQ frequency response. Bands whose
    // gain is 0 and whose biquad is inactive are skipped (cheap).
    void process(float* buffer, size_t num_samples) noexcept {
        try {
            if (!buffer || num_samples == 0) return;
            for (int i = 0; i < BAND_COUNT; ++i) {
                // A 0 dB peaking biquad is an exact identity, so flat bands
                // are skipped. A band that goes from non-zero to zero stops
                // being processed at once, so its filter tail is cut off
                // rather than ringing out; at EQ settings this is inaudible.
                if (effective_gains_[i].load(std::memory_order_relaxed) == 0.0f) continue;
                bands_[0][i].process(buffer, num_samples);
            }
            const float compensation = compensation_gain_.load(std::memory_order_acquire);
            if (compensation != 1.0f) {
                for (size_t i = 0; i < num_samples; ++i) buffer[i] *= compensation;
            }
        } catch (...) { /* buffer left unchanged */ }
    }

    // Interleaved stereo path used by DspChain. Independent filter state is
    // kept per channel; extra channels are not modified.
    void process_stereo(float* buffer, size_t frames, int channels = 2) noexcept {
        try {
            if (!buffer || frames == 0 || channels < 2) return;
            for (size_t frame = 0; frame < frames; ++frame) {
                const size_t base = frame * static_cast<size_t>(channels);
                for (int ch = 0; ch < 2; ++ch) {
                    float value = buffer[base + static_cast<size_t>(ch)];
                    for (int band = 0; band < BAND_COUNT; ++band) {
                        if (effective_gains_[band].load(std::memory_order_relaxed) == 0.0f) continue;
                        value = bands_[ch][band].process_sample(value);
                    }
                    buffer[base + static_cast<size_t>(ch)] =
                        value * compensation_gain_.load(std::memory_order_relaxed);
                }
            }
        } catch (...) { /* buffer left valid */ }
    }

    // Reset all filter histories. Control thread; not for use mid-process.
    void reset() noexcept {
        try {
            for (int ch = 0; ch < 2; ++ch)
                for (int i = 0; i < BAND_COUNT; ++i) bands_[ch][i].reset();
        } catch (...) { /* noexcept */ }
    }

private:
    static float clamp(float value, float low, float high) noexcept {
        return value < low ? low : (value > high ? high : value);
    }

    void recompute_bands() noexcept {
        try {
            float low = 0.0f, high = 0.0f, weighted = 0.0f, weights = 0.0f;
            static constexpr float kWeights[BAND_COUNT] = {
                0.45f, 0.6f, 0.78f, 0.92f, 1.0f,
                1.0f, 0.96f, 0.86f, 0.68f, 0.5f
            };
            for (int i = 0; i < BAND_COUNT; ++i) {
                const float gain = gains_[i].load(std::memory_order_relaxed);
                if (i < BAND_COUNT / 2) low += gain;
                else high += gain;
                weighted += gain * kWeights[i];
                weights += kWeights[i];
            }
            const float tilt = clamp((high - low) * 0.1f, -3.0f, 3.0f);
            const float coupling = tilt_coupling_.load(std::memory_order_relaxed);
            for (int i = 0; i < BAND_COUNT; ++i) {
                const float position = (static_cast<float>(i) - 4.5f) / 4.5f;
                const float effective = clamp(
                    gains_[i].load(std::memory_order_relaxed) -
                    tilt * position * coupling, MIN_GAIN_DB, MAX_GAIN_DB);
                effective_gains_[i].store(effective, std::memory_order_release);
                configure_band(i);
            }
            const float mean_gain = weights > 0.0f ? weighted / weights : 0.0f;
            const float compensation_db = clamp(
                -mean_gain * 0.3f * auto_compensation_.load(std::memory_order_relaxed),
                -4.0f, 4.0f);
            compensation_gain_.store(std::pow(10.0f, compensation_db * 0.05f),
                                     std::memory_order_release);
        } catch (...) { /* retain the last valid coefficients */ }
    }

    // Control thread. Bands at or above the usable limit are held at 0 dB
    // (identity) instead of reset, so an old filter cannot keep running
    // after a sample-rate change.
    void configure_band(int index) noexcept {
        try {
            if (sample_rate_ <= 0.0f) return;
            const float nyquist = sample_rate_ * 0.5f;
            const float freq = center_frequencies()[index];
            const float gain = effective_gains_[index].load(std::memory_order_relaxed);
            const bool above_limit = freq >= nyquist * 0.98f;
            const float effective_gain = above_limit ? 0.0f : gain;
            for (int ch = 0; ch < 2; ++ch) {
                bands_[ch][index].set_peak_or_shelf(
                    Biquad::Type::Peaking, freq, Q, effective_gain, sample_rate_);
            }
        } catch (...) { /* noexcept */ }
    }

    float sample_rate_ = 0.0f;
    std::atomic<float> gains_[BAND_COUNT]{};
    std::atomic<float> effective_gains_[BAND_COUNT]{};
    std::atomic<float> tilt_coupling_{0.18f};
    std::atomic<float> auto_compensation_{0.35f};
    std::atomic<float> compensation_gain_{1.0f};
    Biquad bands_[2][BAND_COUNT];
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_GRAPHIC_EQ_10_BAND_H