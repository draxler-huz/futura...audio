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
// Stage implementations are header-only. Configuration setters run on the
// control thread; process() uses atomic settings and non-blocking filter
// handoffs so it does not wait for the control thread.

#ifndef FUTURA_ENGINE_DSP_DSP_CHAIN_H
#define FUTURA_ENGINE_DSP_DSP_CHAIN_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <exception>
#include <cmath>
#include <mutex>
#include <string>

#include "dsp/AutoGainControlEngine.h"
#include "dsp/BassBoostSynthesizer.h"
#include "dsp/CrossfeedProcessor.h"
#include "dsp/DitherGenerator.h"
#include "dsp/DynamicRangeCompressor.h"
#include "dsp/FreeverbEngine.h"
#include "dsp/GraphicEq10Band.h"
#include "dsp/HighPassDcBlockingFilter.h"
#include "dsp/LoudnessMaximizer.h"
#include "dsp/PeakLevelDetector.h"
#include "dsp/PhaseInverter.h"
#include "dsp/PreampGainController.h"
#include "dsp/StereoToBinauralConverter.h"
#include "dsp/StereoWidthExpander.h"

namespace futura::engine::dsp {

// Interchange layout constants — must match DspBridge.kt's 30-float
// layout and DspJni.cpp's encode/decode.
constexpr int CHAIN_CONFIG_FIELD_COUNT = 30;
constexpr int CHAIN_EQ_BAND_COUNT      = 10;

class DspChain {
public:
    DspChain() noexcept { initialize_stages(); }
    ~DspChain() = default;

    DspChain(const DspChain&) = delete;
    DspChain& operator=(const DspChain&) = delete;
    DspChain(DspChain&&) = delete;
    DspChain& operator=(DspChain&&) = delete;

    // ── Master toggle ─────────────────────────────────────────────
    void set_enabled(bool enabled) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.enabled = enabled;
        } catch (...) { /* noexcept */ }
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
            for (size_t i = 0; i < len; ++i) {
                if (!std::isfinite(config[i])) return false;
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
            if (!std::isfinite(db)) return;
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.preamp_db = clamp(db, -20.0f, 20.0f);
            preamp_.set_gain_db(config_.preamp_db);
        } catch (...) { /* noexcept */ }
    }

    void set_preamp_warmth(float amount) noexcept {
        preamp_.set_warmth(amount);
    }

    void set_eq_band_gain(int index, float gain_db) noexcept {
        try {
            if (index < 0 || index >= CHAIN_EQ_BAND_COUNT) return;
            if (!std::isfinite(gain_db)) return;
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.eq_bands[index] = clamp(gain_db, -12.0f, 12.0f);
            eq_.set_band_gain(index, config_.eq_bands[index]);
        } catch (...) { /* noexcept */ }
    }

    void set_bass_boost(float v) noexcept {
        try {
            if (!std::isfinite(v)) return;
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.bass_boost = clamp(v, 0.0f, 1.0f);
            bass_.set_amount(config_.bass_boost);
        } catch (...) { /* noexcept */ }
    }

    void set_stereo_width(float v) noexcept {
        try {
            if (!std::isfinite(v)) return;
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.stereo_width = clamp(v, 0.0f, 2.0f);
            width_.set_width(config_.stereo_width);
        } catch (...) { /* noexcept */ }
    }

    void set_crossfeed_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.crossfeed_enabled = e;
            crossfeed_.set_enabled(e);
        } catch (...) { /* noexcept */ }
    }

    void set_binaural_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.binaural_enabled = e;
            binaural_.set_enabled(e);
        } catch (...) { /* noexcept */ }
    }

    void set_reverb_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.reverb_enabled = e;
            reverb_.set_enabled(e);
        } catch (...) { /* noexcept */ }
    }

    void set_reverb_params(float room, float damping, float wet) noexcept {
        try {
            if (!std::isfinite(room) || !std::isfinite(damping) || !std::isfinite(wet)) return;
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.reverb_room_size = clamp(room, 0.0f, 1.0f);
            config_.reverb_damping   = clamp(damping, 0.0f, 1.0f);
            config_.reverb_wet_level = clamp(wet, 0.0f, 1.0f);
            reverb_.set_parameters(config_.reverb_room_size, config_.reverb_damping,
                                   config_.reverb_wet_level);
        } catch (...) { /* noexcept */ }
    }

    void set_compressor_enabled(bool e) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.compressor_enabled = e;
            compressor_.set_enabled(e);
        } catch (...) { /* noexcept */ }
    }

    void set_compressor_params(float threshold_db, float ratio,
                               float attack_ms, float release_ms) noexcept {
        try {
            if (!std::isfinite(threshold_db) || !std::isfinite(ratio) ||
                !std::isfinite(attack_ms) || !std::isfinite(release_ms)) return;
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.compressor_threshold = clamp(threshold_db, -60.0f, 0.0f);
            config_.compressor_ratio     = clamp(ratio, 1.0f, 20.0f);
            config_.compressor_attack_ms = clamp(attack_ms, 0.1f, 200.0f);
            config_.compressor_release_ms = clamp(release_ms, 10.0f, 1000.0f);
            compressor_.set_parameters(config_.compressor_threshold, config_.compressor_ratio,
                                       config_.compressor_attack_ms,
                                       config_.compressor_release_ms);
        } catch (...) { /* noexcept */ }
    }

    void set_auto_gain_control_enabled(bool enabled) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.auto_gain_control_enabled = enabled;
            agc_.set_enabled(enabled);
        } catch (...) { /* noexcept */ }
    }

    void set_crossfeed_amount(float amount) noexcept {
        if (!std::isfinite(amount)) return;
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.crossfeed_amount = clamp(amount, 0.0f, 1.0f);
            crossfeed_.set_amount(config_.crossfeed_amount);
        } catch (...) { /* noexcept */ }
    }

    void set_phase_inverted(bool left, bool right) noexcept {
        try {
            std::lock_guard<std::mutex> lock(config_mutex_);
            config_.phase_inverted_left = left;
            config_.phase_inverted_right = right;
            phase_inverter_.set_inverted(left, right);
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

            // Volume transitions start at a zero crossing (or a short
            // timeout) and follow a smooth logarithmic S-curve.
            for (size_t frame = 0; frame < num_frames; ++frame) {
                const size_t base = frame * static_cast<size_t>(channels);
                const float left = std::isfinite(buffer[base]) ? buffer[base] : 0.0f;
                const float right = channels > 1 && std::isfinite(buffer[base + 1])
                                  ? buffer[base + 1] : left;
                const float volume = next_volume(target_volume, left, right);
                for (int ch = 0; ch < channels; ++ch) {
                    float& sample = buffer[base + static_cast<size_t>(ch)];
                    sample = std::isfinite(sample) ? sample * volume : 0.0f;
                }
                last_volume_input_[0] = left;
                last_volume_input_[1] = right;
            }

            // Preamp saturation is before EQ so the EQ can shape its harmonics.
            preamp_.process(buffer, total_samples);
            if (channels == 1) eq_.process(buffer, num_frames);
            else eq_.process_stereo(buffer, num_frames, channels);
            bass_.process(buffer, num_frames, channels);
            width_.process(buffer, num_frames, channels);

            // Crossfeed and binaural rendering are mutually exclusive.
            if (snapshot_.binaural_enabled) {
                binaural_.process(buffer, num_frames, channels);
            } else if (snapshot_.crossfeed_enabled) {
                crossfeed_.process(buffer, num_frames, channels);
            }
            reverb_.process(buffer, num_frames, channels);
            compressor_.process(buffer, num_frames, channels);
            agc_.process(buffer, num_frames, channels);
            maximizer_.process(buffer, num_frames, channels);

            // This chain emits float32; the dither stage remains a no-op here.
            // Integer-output callers can use DitherGenerator with 16 or 24 bits.
            dither_.process(buffer, num_frames, channels, 32);
            update_peaks(buffer, num_frames, channels);
            phase_inverter_.process(buffer, num_frames, channels);
            dc_blocker_.process(buffer, num_frames, channels);
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

    void initialize_stages() noexcept {
        constexpr float sample_rate = 48000.0f;
        eq_.set_sample_rate(sample_rate);
        bass_.set_sample_rate(sample_rate);
        width_.set_sample_rate(sample_rate);
        crossfeed_.set_sample_rate(sample_rate);
        binaural_.set_sample_rate(sample_rate);
        reverb_.set_sample_rate(sample_rate);
        compressor_.set_sample_rate(sample_rate);
        agc_.set_sample_rate(sample_rate);
        maximizer_.set_sample_rate(sample_rate);
        peak_detector_.set_sample_rate(sample_rate);
        dc_blocker_.set_sample_rate(sample_rate);
        sync_components_locked();
    }

    // Caller holds config_mutex_.
    void sync_components_locked() noexcept {
        preamp_.set_gain_db(config_.preamp_db);
        for (int i = 0; i < CHAIN_EQ_BAND_COUNT; ++i) {
            eq_.set_band_gain(i, config_.eq_bands[i]);
        }
        bass_.set_amount(config_.bass_boost);
        width_.set_width(config_.stereo_width);
        crossfeed_.set_enabled(config_.crossfeed_enabled);
        crossfeed_.set_amount(config_.crossfeed_amount);
        binaural_.set_enabled(config_.binaural_enabled);
        reverb_.set_enabled(config_.reverb_enabled);
        reverb_.set_parameters(config_.reverb_room_size, config_.reverb_damping,
                               config_.reverb_wet_level);
        compressor_.set_enabled(config_.compressor_enabled);
        compressor_.set_parameters(config_.compressor_threshold,
                                   config_.compressor_ratio,
                                   config_.compressor_attack_ms,
                                   config_.compressor_release_ms);
        agc_.set_enabled(config_.auto_gain_control_enabled);
        phase_inverter_.set_inverted(config_.phase_inverted_left,
                                     config_.phase_inverted_right);
    }

    bool crossing_started(float left, float right) const noexcept {
        const bool left_cross = (last_volume_input_[0] <= 0.0f && left > 0.0f) ||
                                (last_volume_input_[0] >= 0.0f && left < 0.0f);
        const bool right_cross = (last_volume_input_[1] <= 0.0f && right > 0.0f) ||
                                 (last_volume_input_[1] >= 0.0f && right < 0.0f);
        return left_cross || right_cross;
    }

    static float gain_to_db(float gain) noexcept {
        return 20.0f * std::log10(gain > 0.0f ? gain : 1.0e-4f);
    }

    float interpolate_volume(float progress) const noexcept {
        if (progress <= 0.0f) return volume_ramp_start_;
        if (progress >= 1.0f) return volume_ramp_target_;
        if (volume_ramp_start_ <= 0.0f && volume_ramp_target_ <= 0.0f) return 0.0f;
        const float smooth = progress * progress * (3.0f - 2.0f * progress);
        const float db = volume_ramp_start_db_ +
                         (volume_ramp_target_db_ - volume_ramp_start_db_) * smooth;
        return std::pow(10.0f, db * 0.05f);
    }

    float next_volume(float requested, float left, float right) noexcept {
        if (!std::isfinite(requested)) requested = smoothed_volume_;
        requested = clamp(requested, 0.0f, 4.0f);
        if (requested != observed_volume_target_) {
            observed_volume_target_ = requested;
            pending_volume_target_ = requested;
            volume_wait_frames_ = 0;
            volume_ramp_active_ = false;
        }
        if (!volume_ramp_active_ && pending_volume_target_ != smoothed_volume_) {
            if (crossing_started(left, right) ||
                ++volume_wait_frames_ >= VOLUME_ZERO_CROSS_TIMEOUT) {
                volume_ramp_start_ = smoothed_volume_;
                volume_ramp_target_ = pending_volume_target_;
                volume_ramp_start_db_ = gain_to_db(volume_ramp_start_);
                volume_ramp_target_db_ = gain_to_db(volume_ramp_target_);
                volume_ramp_frames_ = 0;
                volume_ramp_active_ = true;
            }
        }
        if (volume_ramp_active_) {
            ++volume_ramp_frames_;
            const float progress = static_cast<float>(volume_ramp_frames_) /
                                   static_cast<float>(VOLUME_RAMP_SAMPLES);
            smoothed_volume_ = interpolate_volume(progress < 1.0f ? progress : 1.0f);
            if (volume_ramp_frames_ >= VOLUME_RAMP_SAMPLES) {
                smoothed_volume_ = volume_ramp_target_;
                volume_ramp_active_ = false;
            }
        }
        return smoothed_volume_;
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
        sync_components_locked();
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
            if (!buf || channels < 1) return;
            peak_detector_.process(buf, frames, channels);
            peak_left_.store(peak_detector_.peak_left(), std::memory_order_relaxed);
            peak_right_.store(peak_detector_.peak_right(), std::memory_order_relaxed);
        } catch (...) { /* noexcept */ }
    }

    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    // ── Constants ─────────────────────────────────────────────────
    static constexpr size_t VOLUME_RAMP_SAMPLES = 576;  // 12 ms at 48 kHz
    static constexpr size_t VOLUME_ZERO_CROSS_TIMEOUT = 240; // 5 ms fallback

    // ── State ─────────────────────────────────────────────────────
    // Rule 7: config_ guarded by config_mutex_. snapshot_ is audio-
    // thread-local (only touched inside process()); no lock needed.
    // enabled_ is atomic for lock-free reads from both threads.
    mutable std::mutex config_mutex_;
    ChainConfig        config_;
    ChainConfig        snapshot_;
    float              smoothed_volume_ = 1.0f;
    float observed_volume_target_ = 1.0f;
    float pending_volume_target_ = 1.0f;
    float volume_ramp_start_ = 1.0f, volume_ramp_target_ = 1.0f;
    float volume_ramp_start_db_ = 0.0f, volume_ramp_target_db_ = 0.0f;
    size_t volume_wait_frames_ = 0, volume_ramp_frames_ = 0;
    bool volume_ramp_active_ = false;
    float last_volume_input_[2] = {0.0f, 0.0f};

    PreampGainController preamp_;
    GraphicEq10Band eq_;
    BassBoostSynthesizer bass_;
    StereoWidthExpander width_;
    CrossfeedProcessor crossfeed_;
    StereoToBinauralConverter binaural_;
    FreeverbEngine reverb_;
    DynamicRangeCompressor compressor_;
    AutoGainControlEngine agc_;
    LoudnessMaximizer maximizer_;
    DitherGenerator dither_;
    PeakLevelDetector peak_detector_;
    PhaseInverter phase_inverter_;
    HighPassDcBlockingFilter dc_blocker_;

    std::atomic<bool>  enabled_{true};
    std::atomic<float> peak_left_{0.0f};
    std::atomic<float> peak_right_{0.0f};

    mutable std::mutex error_mutex_;
    std::string        last_error_;
};

}  // namespace futura::engine::dsp

#endif  // FUTURA_ENGINE_DSP_DSP_CHAIN_H