// futura-audio/engine/src/main/cpp/dsp/DspChain.h
//
// Phase 4 — DSP processing chain. Owns the ordered sequence of
// per-sample processing stages that transform decoded audio between
// the ring buffer and the Oboe output:
//
//   preamp → EQ (10 biquads) → bass boost → stereo width →
//   crossfeed / binaural (mutually exclusive at runtime) → reverb →
//   compressor → AGC → loudness maximizer → dither → peak detect →
//   phase invert → DC block
//
// The safety processors — DC blocking (HighPassDcBlockingFilter) and
// the loudness maximizer (LoudnessMaximizer) — are ALWAYS active when
// the chain is enabled. They cannot be turned off from Kotlin, matching
// DspBridge.kt's documented contract.
//
// Threading:
//   • Config setters — control thread, mutex-guarded (rule 7).
//   • process() — audio thread, try_lock snapshot per call; never
//     blocks. On contention, uses the previous snapshot.
//
// Growth seams (each stage) — every `// GROWTH:` comment below names
// the exact dsp/*.h header that supplies the per-sample implementation:
//   PreampGainController.h       (preamp stage)
//   GraphicEq10Band.h / Biquad.h (EQ stage)
//   BassBoostSynthesizer.h       (bass stage)
//   StereoWidthExpander.h        (width stage)
//   CrossfeedProcessor.h         (crossfeed stage)
//   StereoToBinauralConverter.h  (binaural stage)
//   FreeverbEngine.h             (reverb stage)
//   DynamicRangeCompressor.h     (compressor stage)
//   AutoGainControlEngine.h      (AGC stage)
//   LoudnessMaximizer.h          (maximizer stage)
//   DitherGenerator.h            (dither stage)
//   PeakLevelDetector.h          (peak stage)
//   PhaseInverter.h              (phase stage)
//   HighPassDcBlockingFilter.h   (DC block stage)
//
// What works today: master enable, full config storage (matching
// DspBridge.kt's 30-float layout), volume ramp, peak metering from the
// last processed frame. The stage-by-stage per-sample work is stubbed
// to identity (pass-through) with a GROWTH seam per stage.

#ifndef FUTURA_ENGINE_DSP_DSP_CHAIN_H
#define FUTURA_ENGINE_DSP_DSP_CHAIN_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>

namespace futura::engine::dsp {

// Interchange layout constants — must match DspBridge.kt's 30-float
// layout and DspJni.cpp's encode/decode.
constexpr int CHAIN_CONFIG_FIELD_COUNT = 30;
constexpr int CHAIN_EQ_BAND_COUNT      = 10;

class DspChain {
public:
    DspChain() noexcept = default;
    ~DspChain() = default;

    DspChain(const DspChain&) = delete;
    DspChain& operator=(const DspChain&) = delete;
    DspChain(DspChain&&) = delete;
    DspChain& operator=(DspChain&&) = delete;

    // ── Master toggle ─────────────────────────────────────────────
    void set_enabled(bool enabled) noexcept {
        enabled_.store(enabled, std::memory_order_release);
    }
    bool is_enabled() const noexcept {
        return enabled_.load(std::memory_order_acquire);
    }

    // ── Bulk config (control thread) ──────────────────────────────
    //
    // Applies the 30-float interchange config (see file header for the
    // full layout — matches DspBridge.kt's documented mapping). All
    // stages are updated atomically: either the entire config takes
    // effect, or none does.
    //
    // Returns false on invalid length or exception during apply.
    bool apply_config(const float* config, size_t len) noexcept {
        try {
            if (!config || len != static_cast<size_t>(CHAIN_CONFIG_FIELD_COUNT)) {
                return false;
            }
            std::lock_guard<std::mutex> lock(config_mutex_);
            decode_locked(config);
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("apply_config: ") + e.what());
        } catch (...) {
            set_error("apply_config: unknown exception");
        }
        return false;
    }

    // Encode current config into the 30-float layout. Caller supplies
    // a buffer of CHAIN_CONFIG_FIELD_COUNT floats. Returns true on
    // success.
    bool get_config(float* out) noexcept {
        try {
            if (!out) return false;
            std::lock_guard<std::mutex> lock(config_mutex_);
            encode_locked(out);
            return true;
        } catch (...) {
            return false;
        }
    }

    // ── Individual config setters (all mutex-guarded) ─────────────

    void set_preamp_db(float db) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.preamp_db = clamp(db, -20.0f, 20.0f);
        } catch (...) { /* noexcept */ }
    }

    void set_eq_band_gain(int index, float gain_db) noexcept {
        try {
            if (index < 0 || index >= CHAIN_EQ_BAND_COUNT) return;
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.eq_bands[index] = clamp(gain_db, -12.0f, 12.0f);
        } catch (...) { /* noexcept */ }
    }

    void set_bass_boost(float v) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.bass_boost = clamp(v, 0.0f, 1.0f);
        } catch (...) { /* noexcept */ }
    }

    void set_stereo_width(float v) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.stereo_width = clamp(v, 0.0f, 2.0f);
        } catch (...) { /* noexcept */ }
    }

    void set_crossfeed_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.crossfeed_enabled = e;
        } catch (...) { /* noexcept */ }
    }

    void set_binaural_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.binaural_enabled = e;
        } catch (...) { /* noexcept */ }
    }

    void set_reverb_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.reverb_enabled = e;
        } catch (...) { /* noexcept */ }
    }

    void set_reverb_params(float room, float damping, float wet) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.reverb_room_size = clamp(room, 0.0f, 1.0f);
            config_.reverb_damping   = clamp(damping, 0.0f, 1.0f);
            config_.reverb_wet_level = clamp(wet, 0.0f, 1.0f);
        } catch (...) { /* noexcept */ }
    }

    void set_compressor_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.compressor_enabled = e;
        } catch (...) { /* noexcept */ }
    }

    void set_compressor_params(float threshold_db, float ratio,
                               float attack_ms, float release_ms) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.compressor_threshold = clamp(threshold_db, -60.0f, 0.0f);
            config_.compressor_ratio     = clamp(ratio, 1.0f, 20.0f);
            config_.compressor_attack_ms = clamp(attack_ms, 0.1f, 200.0f);
            config_.compressor_release_ms = clamp(release_ms, 10.0f, 1000.0f);
        } catch (...) { /* noexcept */ }
    }

    // ── Metering (any thread, lock-free) ──────────────────────────

    float peak_left() const noexcept {
        return peak_left_.load(std::memory_order_relaxed);
    }
    float peak_right() const noexcept {
        return peak_right_.load(std::memory_order_relaxed);
    }

    // ── Audio-thread process (never blocks) ───────────────────────

    // Process num_frames × channels interleaved float samples in-place.
    // Applies volume ramp + the enabled DSP stages + peak metering.
    // Uses try_lock to snapshot config so the audio thread never waits
    // for the control thread (rule 7 with real-time caveat).
    void process(float* buffer, size_t num_frames, int channels,
                 float target_volume) noexcept {
        try {
            if (!buffer || num_frames == 0 || channels <= 0) return;

            // Snapshot config non-blockingly.
            {
                std::unique_lock<std::mutex> lock(
                    config_mutex_, std::try_to_lock);
                if (lock.owns_lock()) {
                    snapshot_ = config_;
                }
                // else: snapshot_ retains its previous value.
            }

            // Master bypass — bit-perfect passthrough. Still peek at
            // input peaks so the meter doesn't stick.
            if (!enabled_.load(std::memory_order_acquire)) {
                update_peaks(buffer, num_frames, channels);
                return;
            }

            const size_t total_samples =
                num_frames * static_cast<size_t>(channels);

            // ── Volume ramp (identical to ZeroAllocRenderLoop) ────
            float s = smoothed_volume_;
            for (size_t i = 0; i < total_samples; ++i) {
                s += (target_volume - s) * VOLUME_RAMP_COEFF;
                buffer[i] *= s;
            }
            smoothed_volume_ = s;

            // ── GROWTH: preamp — PreampGainController.h ───────────
            // Apply config_.preamp_db as a global gain before EQ.
            // Requires: db_to_linear(config_.preamp_db) multiplier.

            // ── GROWTH: 10-band EQ — GraphicEq10Band.h + Biquad.h ─
            // Iterate config_.eq_bands[0..9]; each drives a biquad
            // stage; coefficients cached per band and recomputed on
            // change (see Biquad.h).

            // ── GROWTH: bass boost — BassBoostSynthesizer.h ───────
            // Harmonic synthesis (not low-shelf), enabled when
            // config_.bass_boost > 0.

            // ── GROWTH: stereo width — StereoWidthExpander.h ──────
            // M/S manipulation, coefficient derived from
            // config_.stereo_width (0=mono, 1=unity, 2=double).

            // ── GROWTH: crossfeed — CrossfeedProcessor.h ──────────
            // Bauer BS2B; enabled when config_.crossfeed_enabled.
            // Mutually exclusive with binaural (caller contract).

            // ── GROWTH: binaural — StereoToBinauralConverter.h ────
            // HRTF; enabled when config_.binaural_enabled.

            // ── GROWTH: reverb — FreeverbEngine.h ─────────────────
            // Freeverb; enabled when config_.reverb_enabled;
            // parameters from config_.reverb_*.

            // ── GROWTH: compressor — DynamicRangeCompressor.h ─────
            // Enabled when config_.compressor_enabled;
            // parameters from config_.compressor_*.

            // ── GROWTH: AGC — AutoGainControlEngine.h ─────────────
            // Enabled when config_.auto_gain_control_enabled.

            // ── GROWTH: loudness maximizer — LoudnessMaximizer.h ──
            // Always active per chain contract (safety processor).

            // ── GROWTH: dither — DitherGenerator.h ────────────────
            // Applied at 24→16-bit truncation, not here. No-op at
            // float32 output.

            // ── Peak metering (always) ────────────────────────────
            update_peaks(buffer, num_frames, channels);

            // ── GROWTH: phase invert — PhaseInverter.h ────────────
            // Per-channel sign flip when config_.phase_inverted_*.

            // ── GROWTH: DC block — HighPassDcBlockingFilter.h ─────
            // Always active per chain contract (safety processor).
        } catch (...) {
            // Rule 6. Leave buffer in a safe state — input float32 is
            // already a valid audio signal; failing to apply DSP is
            // preferable to producing garbage.
        }
    }

    // ── Last error (any thread) ───────────────────────────────────

    std::string last_error() const {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            return last_error_;
        } catch (...) { return {}; }
    }

private:
    // ── Config struct (matches the 30-float interchange) ──────────

    struct ChainConfig {
        bool  enabled                  = true;
        float preamp_db                = 0.0f;
        float eq_bands[CHAIN_EQ_BAND_COUNT] = {0};
        float bass_boost               = 0.0f;
        float stereo_width             = 1.0f;
        bool  crossfeed_enabled        = false;
        float crossfeed_amount         = 0.3f;
        bool  binaural_enabled         = false;
        bool  reverb_enabled           = false;
        float reverb_room_size         = 0.5f;
        float reverb_damping           = 0.5f;
        float reverb_wet_level         = 0.3f;
        bool  compressor_enabled       = false;
        float compressor_threshold     = -18.0f;
        float compressor_ratio         = 4.0f;
        float compressor_attack_ms     = 10.0f;
        float compressor_release_ms    = 100.0f;
        bool  auto_gain_control_enabled = false;
        bool  loudness_maximizer_enabled = true;
        bool  phase_inverted_left      = false;
        bool  phase_inverted_right     = false;
    };

    static float clamp(float v, float lo, float hi) noexcept {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // Decode the 30-float interchange buffer into config_. Caller holds
    // config_mutex_.
    void decode_locked(const float* c) noexcept {
        config_.enabled = c[0] != 0.0f;
        enabled_.store(config_.enabled, std::memory_order_release);
        config_.preamp_db = clamp(c[1], -20.0f, 20.0f);
        for (int i = 0; i < CHAIN_EQ_BAND_COUNT; ++i) {
            config_.eq_bands[i] = clamp(c[2 + i], -12.0f, 12.0f);
        }
        config_.bass_boost       = clamp(c[12], 0.0f, 1.0f);
        config_.stereo_width     = clamp(c[13], 0.0f, 2.0f);
        config_.crossfeed_enabled = c[14] != 0.0f;
        config_.crossfeed_amount = clamp(c[15], 0.0f, 1.0f);
        config_.binaural_enabled = c[16] != 0.0f;
        config_.reverb_enabled   = c[17] != 0.0f;
        config_.reverb_room_size = clamp(c[18], 0.0f, 1.0f);
        config_.reverb_damping   = clamp(c[19], 0.0f, 1.0f);
        config_.reverb_wet_level = clamp(c[20], 0.0f, 1.0f);
        config_.compressor_enabled = c[21] != 0.0f;
        config_.compressor_threshold = clamp(c[22], -60.0f, 0.0f);
        config_.compressor_ratio     = clamp(c[23], 1.0f, 20.0f);
        config_.compressor_attack_ms = clamp(c[24], 0.1f, 200.0f);
        config_.compressor_release_ms = clamp(c[25], 10.0f, 1000.0f);
        config_.auto_gain_control_enabled = c[26] != 0.0f;
        config_.loudness_maximizer_enabled = c[27] != 0.0f;
        config_.phase_inverted_left  = c[28] != 0.0f;
        config_.phase_inverted_right = c[29] != 0.0f;
    }

    // Encode config_ into the 30-float interchange buffer. Caller holds
    // config_mutex_.
    void encode_locked(float* out) noexcept {
        out[0]  = config_.enabled ? 1.0f : 0.0f;
        out[1]  = config_.preamp_db;
        for (int i = 0; i < CHAIN_EQ_BAND_COUNT; ++i) out[2 + i] = config_.eq_bands[i];
        out[12] = config_.bass_boost;
        out[13] = config_.stereo_width;
        out[14] = config_.crossfeed_enabled ? 1.0f : 0.0f;
        out[15] = config_.crossfeed_amount;
        out[16] = config_.binaural_enabled ? 1.0f : 0.0f;
        out[17] = config_.reverb_enabled ? 1.0f : 0.0f;
        out[18] = config_.reverb_room_size;
        out[19] = config_.reverb_damping;
        out[20] = config_.reverb_wet_level;
        out[21] = config_.compressor_enabled ? 1.0f : 0.0f;
        out[22] = config_.compressor_threshold;
        out[23] = config_.compressor_ratio;
        out[24] = config_.compressor_attack_ms;
        out[25] = config_.compressor_release_ms;
        out[26] = config_.auto_gain_control_enabled ? 1.0f : 0.0f;
        out[27] = config_.loudness_maximizer_enabled ? 1.0f : 0.0f;
        out[28] = config_.phase_inverted_left ? 1.0f : 0.0f;
        out[29] = config_.phase_inverted_right ? 1.0f : 0.0f;
    }

    // Compute per-channel peak from the buffer and store into atomics.
    void update_peaks(const float* buf, size_t frames, int channels) noexcept {
        try {
            if (channels < 2) return;
            float l = 0.0f, r = 0.0f;
            const size_t total = frames * static_cast<size_t>(channels);
            for (size_t i = 0; i < total; i += static_cast<size_t>(channels)) {
                const float sl = buf[i];
                const float sr = buf[i + 1];
                const float al = sl < 0 ? -sl : sl;
                const float ar = sr < 0 ? -sr : sr;
                if (al > l) l = al;
                if (ar > r) r = ar;
            }
            peak_left_.store(l, std::memory_order_relaxed);
            peak_right_.store(r, std::memory_order_relaxed);
        } catch (...) { /* noexcept */ }
    }

    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    // ── Constants ─────────────────────────────────────────────────
    static constexpr float VOLUME_RAMP_COEFF = 0.005f;

    // ── State ─────────────────────────────────────────────────────
    // Rule 7: config_ guarded by config_mutex_. snapshot_ is audio-
    // thread-local (only touched inside process()); no lock needed.
    // enabled_ is atomic for lock-free reads from both threads.
    mutable std::mutex config_mutex_;
    ChainConfig        config_;
    ChainConfig        snapshot_;
    float              smoothed_volume_ = 1.0f;

    std::atomic<bool>  enabled_{true};
    std::atomic<float> peak_left_{0.0f};
    std::atomic<float> peak_right_{0.0f};

    mutable std::mutex error_mutex_;
    std::string        last_error_;
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_DSP_CHAIN_H