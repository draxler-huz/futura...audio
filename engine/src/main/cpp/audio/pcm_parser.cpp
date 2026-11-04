// engine/src/main/cpp/audio/pcm_parser.cpp

#include "audio/pcm_parser.h"

#include <cstring>

namespace futura::audio {

namespace {

constexpr uint16_t kFormatPcm        = 0x0001;
constexpr uint16_t kFormatExtensible = 0xFFFE;
constexpr float    kInt16Scale       = 1.0f / 32768.0f;

// Little-endian readers. Byte-wise, so unaligned input is safe.
inline uint16_t readLe16(const uint8_t* p) noexcept {
    return static_cast<uint16_t>(p[0] | (static_cast<uint16_t>(p[1]) << 8));
}

inline uint32_t readLe32(const uint8_t* p) noexcept {
    return static_cast<uint32_t>(p[0])
         | (static_cast<uint32_t>(p[1]) << 8)
         | (static_cast<uint32_t>(p[2]) << 16)
         | (static_cast<uint32_t>(p[3]) << 24);
}

inline bool fourccEquals(const uint8_t* p, const char (&id)[5]) noexcept {
    return std::memcmp(p, id, 4) == 0;
}

}  // namespace

WavParseResult parseWav16(const uint8_t* bytes, size_t size, WavFormat* out) noexcept {
    if (out == nullptr || bytes == nullptr) return WavParseResult::kTooShort;
    if (size < 12) return WavParseResult::kTooShort;
    if (!fourccEquals(bytes, "RIFF") || !fourccEquals(bytes + 8, "WAVE")) {
        return WavParseResult::kNotRiffWave;
    }

    WavFormat fmt{};
    bool have_fmt  = false;
    bool have_data = false;
    bool fmt_ok    = false;

    // Chunks are 8-byte headers (id + size) followed by the body. Bodies are
    // padded to an even length. Sizes are 64-bit so a hostile size can't wrap.
    uint64_t pos = 12;
    while (pos + 8 <= size) {
        const uint8_t* header = bytes + pos;
        const uint64_t chunk_size = readLe32(header + 4);
        const uint64_t body = pos + 8;
        const uint64_t avail = body <= size ? size - body : 0;

        if (fourccEquals(header, "fmt ")) {
            // Need the 16-byte base header at minimum.
            if (chunk_size < 16 || avail < 16) return WavParseResult::kUnsupportedFormat;
            const uint8_t* f = bytes + body;
            const uint16_t audio_format = readLe16(f);
            const uint16_t channels     = readLe16(f + 2);
            const uint32_t sample_rate  = readLe32(f + 4);
            const uint16_t bits         = readLe16(f + 14);

            bool pcm = audio_format == kFormatPcm;
            if (audio_format == kFormatExtensible) {
                // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes
                // of the SubFormat GUID, at offset 24 of the fmt body.
                pcm = chunk_size >= 40 && avail >= 40 && readLe16(f + 24) == kFormatPcm;
            }

            fmt.channels         = channels;
            fmt.sample_rate      = sample_rate;
            fmt.bits_per_sample  = bits;
            fmt_ok               = pcm && bits == 16 && (channels == 1 || channels == 2)
                                   && sample_rate > 0;
            have_fmt             = true;
        } else if (fourccEquals(header, "data")) {
            const uint64_t clamped = chunk_size < avail ? chunk_size : avail;
            fmt.data_offset = static_cast<size_t>(body);
            fmt.data_size   = static_cast<size_t>(clamped);
            have_data       = true;
        }

        if (have_fmt && have_data) break;

        const uint64_t padded = chunk_size + (chunk_size & 1u);
        // Stop if the next chunk would start past the end of the buffer.
        if (body + padded > size) break;
        pos = body + padded;
    }

    if (!have_fmt) return WavParseResult::kNoFmtChunk;
    if (!have_data) return WavParseResult::kNoDataChunk;
    if (!fmt_ok) return WavParseResult::kUnsupportedFormat;

    // Keep only whole sample frames, so the streamer never reads half a frame.
    const size_t frame_bytes = static_cast<size_t>(fmt.channels) * 2u;
    fmt.data_size -= fmt.data_size % frame_bytes;

    *out = fmt;
    return WavParseResult::kOk;
}

WavParseResult WavPcmStreamer::reset(const uint8_t* bytes, size_t size) noexcept {
    valid_           = false;
    bytes_           = nullptr;
    total_frames_    = 0;
    position_frames_ = 0;

    WavFormat fmt{};
    const WavParseResult result = parseWav16(bytes, size, &fmt);
    if (result != WavParseResult::kOk) return result;

    format_       = fmt;
    bytes_        = bytes;
    total_frames_ = fmt.data_size / (static_cast<size_t>(fmt.channels) * 2u);
    valid_        = true;
    return result;
}

size_t WavPcmStreamer::readFloat(float* out, size_t max_frames) noexcept {
    if (!valid_ || out == nullptr || max_frames == 0) return 0;

    const size_t channels = format_.channels;
    size_t written = 0;

    while (written < max_frames) {
        if (position_frames_ >= total_frames_) {
            if (!loop_ || total_frames_ == 0) break;
            position_frames_ = 0;
        }

        const uint64_t left_in_source = total_frames_ - position_frames_;
        const uint64_t want           = max_frames - written;
        const size_t take = static_cast<size_t>(left_in_source < want ? left_in_source : want);

        const uint8_t* src = bytes_ + format_.data_offset
                           + static_cast<size_t>(position_frames_) * channels * 2u;
        float* dst         = out + written * channels;
        const size_t samples = take * channels;

        for (size_t i = 0; i < samples; ++i) {
            const uint16_t raw = readLe16(src + 2 * i);
            const int16_t  s   = static_cast<int16_t>(raw);
            dst[i] = static_cast<float>(s) * kInt16Scale;
        }

        position_frames_ += take;
        written          += take;
    }

    return written;
}

}  // namespace futura::audio
