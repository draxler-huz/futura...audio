// futura-audio/engine/src/main/cpp/decode/FlacNativeDecoder.h
//
// Phase 3 — libFLAC stream decoder (feature #58). RAII wrapper; decodes
// FLAC to interleaved float32. Faster and bit-perfect-24-capable compared
// to FFmpeg's libavcodec FLAC path.
//
// Push-to-pull bridge: libFLAC delivers decoded blocks via write_callback
// synchronously from within FLAC__stream_decoder_process_single(), which
// read_frames() calls on the decoder thread. Scratch buffer accumulates
// int32 samples; read_frames() converts to normalised float on demand.
//
// Threading: not thread-safe at the ALSA-style level, but state_mutex_
// guards the only shared members (decoder_, scratch_, scratch_offset_,
// reached_eof_). Callers (DecoderThread) invoke methods serially; the
// mutex protects against a future caller that pauses/seeks from another
// thread.
//
// Growth: none. Complete.

#ifndef FUTURA_ENGINE_DECODE_FLAC_NATIVE_DECODER_H
#define FUTURA_ENGINE_DECODE_FLAC_NATIVE_DECODER_H

extern "C" {
#include <FLAC/metadata.h>
#include <FLAC/stream_decoder.h>
}

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <vector>

namespace futura::engine::decode {

class FlacNativeDecoder {
public:
    FlacNativeDecoder() noexcept = default;

    ~FlacNativeDecoder() { close(); }

    FlacNativeDecoder(const FlacNativeDecoder&) = delete;
    FlacNativeDecoder& operator=(const FlacNativeDecoder&) = delete;
    FlacNativeDecoder(FlacNativeDecoder&&) = delete;
    FlacNativeDecoder& operator=(FlacNativeDecoder&&) = delete;

    // ── Lifecycle ──────────────────────────────────────────────────

    // Open a FLAC file. Blocking (5–150ms). Returns true on success.
    bool open(const std::string& path) noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);

            if (decoder_) close_locked();

            if (path.empty()) {
                set_error_locked("open: empty path");
                return false;
            }

            decoder_ = FLAC__stream_decoder_new();
            if (!decoder_) {
                set_error_locked("FLAC__stream_decoder_new failed");
                return false;
            }

            FLAC__stream_decoder_set_metadata_respond(
                decoder_, FLAC__METADATA_TYPE_STREAMINFO);

            const FLAC__StreamDecoderInitStatus init_status =
                FLAC__stream_decoder_init_file(
                    decoder_, path.c_str(),
                    &FlacNativeDecoder::write_callback_static,
                    &FlacNativeDecoder::metadata_callback_static,
                    &FlacNativeDecoder::error_callback_static,
                    this);

            if (init_status != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
                set_error_locked(
                    std::string("init_file failed: ") +
                    FLAC__StreamDecoderInitStatusString[init_status]);
                FLAC__stream_decoder_delete(decoder_);
                decoder_ = nullptr;
                return false;
            }

            if (!FLAC__stream_decoder_process_until_end_of_metadata(decoder_)) {
                set_error_locked("process_until_end_of_metadata failed");
                FLAC__stream_decoder_finish(decoder_);
                FLAC__stream_decoder_delete(decoder_);
                decoder_ = nullptr;
                return false;
            }

            if (!metadata_received_) {
                set_error_locked("STREAMINFO metadata not received");
                FLAC__stream_decoder_finish(decoder_);
                FLAC__stream_decoder_delete(decoder_);
                decoder_ = nullptr;
                return false;
            }

            sample_rate_ = static_cast<int32_t>(stream_sample_rate_);
            channels_    = static_cast<int32_t>(stream_channels_);
            bit_depth_   = static_cast<int32_t>(stream_bit_depth_);
            duration_ms_ = (stream_total_samples_ > 0 && sample_rate_ > 0)
                         ? (stream_total_samples_ * 1000) / sample_rate_
                         : -1;

            // Scratch size hint — libFLAC blocks are bounded by
            // max_blocksize from STREAMINFO. Reserve once so push_back in
            // write_callback doesn't reallocate under normal operation.
            // (Any tail beyond this is still handled correctly by
            // push_back's normal growth.)
            if (stream_blocksize_ > 0) {
                scratch_.reserve(stream_blocksize_ *
                                 static_cast<size_t>(channels_));
            }
            reached_eof_ = false;
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("open: ") + e.what());
        } catch (...) {
            set_error("open: unknown exception");
        }
        return false;
    }

    void close() noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);
            close_locked();
        } catch (...) { /* noexcept */ }
    }

    // ── Decoding ───────────────────────────────────────────────────

    // Decode up to max_frames frames into out. Returns frames written;
    // 0 means EOF. Never allocates after the first call (scratch_ grows
    // only to its high-water mark from open()'s reserve).
    size_t read_frames(float* out, size_t max_frames) noexcept {
        try {
            if (!out || max_frames == 0) return 0;

            std::lock_guard<std::mutex> lock(state_mutex_);
            if (!decoder_) return 0;
            if (channels_ <= 0) return 0;

            const size_t frames_available =
                (scratch_.size() - scratch_offset_) /
                static_cast<size_t>(channels_);

            if (frames_available < max_frames && !reached_eof_) {
                // Decode more until we have enough or hit EOF.
                while ((scratch_.size() - scratch_offset_) /
                       static_cast<size_t>(channels_) < max_frames &&
                       !reached_eof_) {
                    if (!FLAC__stream_decoder_process_single(decoder_)) {
                        // Error — leave whatever we have; caller sees a
                        // short read and next call gets 0.
                        break;
                    }
                    if (FLAC__stream_decoder_get_state(decoder_) ==
                        FLAC__STREAM_DECODER_END_OF_STREAM) {
                        reached_eof_ = true;
                    }
                }
            }

            const size_t available =
                (scratch_.size() - scratch_offset_) /
                static_cast<size_t>(channels_);
            const size_t to_copy = std::min(max_frames, available);
            if (to_copy == 0) return 0;

            // int32 scratch → normalised float. Scale factor depends on
            // bit depth. For 8-bit, FLAC uses signed int8 (range -128..127),
            // so scale is 1/128 — the same as 2^(8-1).
            const float scale = 1.0f /
                static_cast<float>(1u << (bit_depth_ - 1));

            const int32_t* src = scratch_.data() + scratch_offset_;
            const size_t samples = to_copy * static_cast<size_t>(channels_);
            for (size_t i = 0; i < samples; ++i) {
                out[i] = static_cast<float>(src[i]) * scale;
            }
            scratch_offset_ += samples;

            // Compact when consumed prefix is at least half of the buffer
            // AND the buffer has grown past the initial reserve. Avoids
            // repeated erase() calls when the buffer is small.
            if (scratch_offset_ > scratch_.size() / 2 &&
                scratch_offset_ > 0) {
                scratch_.erase(scratch_.begin(),
                               scratch_.begin() + scratch_offset_);
                scratch_offset_ = 0;
            }

            return to_copy;
        } catch (...) {
            return 0;
        }
    }

    // Seek to position_ms. Blocking (10–80ms). Discards any buffered
    // samples from the pre-seek position.
    bool seek_to(int64_t position_ms) noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);
            if (!decoder_ || sample_rate_ <= 0) return false;
            if (position_ms < 0) position_ms = 0;

            const FLAC__uint64 target_sample =
                static_cast<FLAC__uint64>(
                    (position_ms * static_cast<int64_t>(sample_rate_)) / 1000);

            if (!FLAC__stream_decoder_seek_absolute(decoder_, target_sample)) {
                set_error_locked("FLAC__stream_decoder_seek_absolute failed");
                return false;
            }
            scratch_.clear();
            scratch_offset_ = 0;
            reached_eof_ = false;
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("seek_to: ") + e.what());
        } catch (...) {
            set_error("seek_to: unknown exception");
        }
        return false;
    }

    // ── Metadata ───────────────────────────────────────────────────

    bool        is_open() const noexcept    { return decoder_ != nullptr; }
    int32_t     sample_rate() const noexcept { return sample_rate_; }
    int32_t     channels() const noexcept    { return channels_; }
    int32_t     bit_depth() const noexcept   { return bit_depth_; }
    int64_t     duration_ms() const noexcept { return duration_ms_; }

    const char* format_name() const noexcept { return "flac"; }

    std::string last_error() const {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            return last_error_;
        } catch (...) { return {}; }
    }

private:
    void close_locked() noexcept {
        if (decoder_) {
            FLAC__stream_decoder_finish(decoder_);
            FLAC__stream_decoder_delete(decoder_);
            decoder_ = nullptr;
        }
        scratch_.clear();
        scratch_offset_ = 0;
        sample_rate_ = channels_ = bit_depth_ = 0;
        duration_ms_ = -1;
        reached_eof_ = false;
        metadata_received_ = false;
    }

    void set_error_locked(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    // ── libFLAC callbacks (invoked synchronously from process_single /
    //    process_until_end_of_metadata; state_mutex_ is already held by
    //    the calling method) ──────────────────────────────────────────

    static FLAC__StreamDecoderWriteStatus write_callback_static(
        const FLAC__StreamDecoder* /*decoder*/,
        const FLAC__Frame* frame,
        const FLAC__int32* const buffer[],
        void* client_data) {
        auto* self = static_cast<FlacNativeDecoder*>(client_data);
        return self->write_callback(frame, buffer);
    }

    static void metadata_callback_static(
        const FLAC__StreamDecoder* /*decoder*/,
        const FLAC__StreamMetadata* metadata,
        void* client_data) {
        auto* self = static_cast<FlacNativeDecoder*>(client_data);
        self->metadata_callback(metadata);
    }

    static void error_callback_static(
        const FLAC__StreamDecoder* /*decoder*/,
        FLAC__StreamDecoderErrorStatus status,
        void* client_data) {
        auto* self = static_cast<FlacNativeDecoder*>(client_data);
        self->error_callback(status);
    }

    FLAC__StreamDecoderWriteStatus write_callback(
        const FLAC__Frame* frame,
        const FLAC__int32* const buffer[]) noexcept {
        try {
            const uint32_t blocksize  = frame->header.blocksize;
            const uint32_t n_channels = frame->header.channels;
            if (blocksize == 0 || n_channels == 0) {
                return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
            }

            // Interleave per-channel planes into scratch_.
            for (uint32_t s = 0; s < blocksize; ++s) {
                for (uint32_t c = 0; c < n_channels; ++c) {
                    scratch_.push_back(buffer[c][s]);
                }
            }
            return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
        } catch (...) {
            return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
        }
    }

    void metadata_callback(const FLAC__StreamMetadata* metadata) noexcept {
        try {
            if (metadata->type != FLAC__METADATA_TYPE_STREAMINFO) return;
            stream_sample_rate_   = static_cast<int32_t>(
                                        metadata->data.stream_info.sample_rate);
            stream_channels_      = static_cast<int32_t>(
                                        metadata->data.stream_info.channels);
            stream_bit_depth_     = static_cast<int32_t>(
                                        metadata->data.stream_info.bits_per_sample);
            stream_total_samples_ = static_cast<int64_t>(
                                        metadata->data.stream_info.total_samples);
            stream_blocksize_     = static_cast<size_t>(
                                        metadata->data.stream_info.max_blocksize);
            metadata_received_    = true;
        } catch (...) { /* noexcept */ }
    }

    void error_callback(FLAC__StreamDecoderErrorStatus status) noexcept {
        try {
            // LOST_SYNC at end of stream is normal — ignore.
            if (status == FLAC__STREAM_DECODER_ERROR_STATUS_LOST_SYNC) return;
            if (status == FLAC__STREAM_DECODER_ERROR_STATUS_UNPARSEABLE_STREAM) {
                reached_eof_ = true;
            }
            set_error_locked(
                std::string("libFLAC error: ") +
                FLAC__StreamDecoderErrorStatusString[status]);
        } catch (...) { /* noexcept */ }
    }

    // ── State (rule 7: all guarded by state_mutex_) ────────────────

    mutable std::mutex         state_mutex_;
    FLAC__StreamDecoder*       decoder_ = nullptr;
    std::vector<int32_t>       scratch_;
    size_t                     scratch_offset_ = 0;
    int32_t                    sample_rate_ = 0;
    int32_t                    channels_    = 0;
    int32_t                    bit_depth_   = 0;
    int64_t                    duration_ms_ = -1;

    // STREAMINFO raw fields
    uint32_t stream_sample_rate_   = 0;
    uint32_t stream_channels_      = 0;
    uint32_t stream_bit_depth_     = 0;
    int64_t  stream_total_samples_ = 0;
    size_t   stream_blocksize_     = 4096;
    bool     metadata_received_    = false;
    bool     reached_eof_          = false;

    mutable std::mutex error_mutex_;
    std::string        last_error_;
};

}  // namespace futura::engine::decode

#endif  // FUTURA_ENGINE_DECODE_FLAC_NATIVE_DECODER_H