// engine/src/main/cpp/audio/pcm_parser.h
//
// Minimal RIFF/WAVE reader for 16-bit PCM. Stage 1 of the audio path: it lets
// bundled, losslessly encoded tones play through the Oboe ring buffer without
// any external decoder.
//
// Rules:
//   • Parsing and the streamer's constructor/reset() may allocate nothing;
//     the caller owns the byte buffer and it must outlive the streamer.
//   • readFloat() is allocation-free and safe to call from the feeder thread.
//     It is NOT thread-safe: one feeder thread per streamer.
//   • Output is interleaved float32 in [-1, 1), the format the ring buffer
//     and Oboe stream use.

#pragma once

#include <cstddef>
#include <cstdint>

namespace futura::audio {

struct WavFormat {
    uint32_t sample_rate    = 0;
    uint16_t channels       = 0;   // 1 or 2
    uint16_t bits_per_sample = 0;  // always 16 in stage 1
    size_t   data_offset    = 0;   // byte offset of the first sample frame
    size_t   data_size      = 0;   // bytes of whole sample frames, clamped to the buffer
};

enum class WavParseResult : uint8_t {
    kOk,
    kTooShort,
    kNotRiffWave,
    kNoFmtChunk,
    kNoDataChunk,
    kUnsupportedFormat,  // not PCM, not 16-bit, or channels outside {1, 2}
};

// Parse the RIFF/WAVE headers in [bytes, bytes + size). Walks chunks in
// either order (fmt before or after data) and skips unknown chunks.
// Allocation-free and does not throw.
WavParseResult parseWav16(const uint8_t* bytes, size_t size, WavFormat* out) noexcept;

// Streams a parsed 16-bit WAV as float frames, optionally looping.
class WavPcmStreamer {
public:
    WavPcmStreamer() noexcept = default;

    // Parse [bytes, bytes + size) and rewind to the first frame. The buffer
    // must stay valid while the streamer is in use. Returns the parse result;
    // the streamer is valid only when it returns kOk.
    WavParseResult reset(const uint8_t* bytes, size_t size) noexcept;

    // Rewind to the first frame without re-parsing.
    void rewind() noexcept { position_frames_ = 0; }

    void setLooping(bool loop) noexcept { loop_ = loop; }

    // Write up to max_frames interleaved float frames into out (which must
    // hold max_frames * channels floats). Returns frames written. Without
    // looping this drops below max_frames at the end of the data and returns
    // 0 once finished. With looping it wraps and returns max_frames.
    size_t readFloat(float* out, size_t max_frames) noexcept;

    bool valid() const noexcept { return valid_; }
    bool finished() const noexcept { return valid_ && !loop_ && position_frames_ >= total_frames_; }
    uint64_t totalFrames() const noexcept { return total_frames_; }
    uint64_t positionFrames() const noexcept { return position_frames_; }
    const WavFormat& format() const noexcept { return format_; }

private:
    const uint8_t* bytes_           = nullptr;
    WavFormat      format_{};
    uint64_t       total_frames_    = 0;
    uint64_t       position_frames_ = 0;
    bool           loop_            = false;
    bool           valid_           = false;
};

}  // namespace futura::audio
