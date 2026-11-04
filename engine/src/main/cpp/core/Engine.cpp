// futura-audio/engine/src/main/cpp/core/Engine.cpp
//
// Phase 3 — Native engine orchestrator implementation.
//
// ── Scope note — READ BEFORE EXPECTING PRODUCTION AUDIO ─────────────────
// The tree annotates this file as "620 lines." That length assumes the
// complete pipeline: decoder thread, ring buffer, DSP chain, gapless
// transition, xrun tracking, bit-perfect switching, device recovery.
// NONE of those subsystems exist yet — their headers (LockFreeRingBuffer.h,
// StreamStateMachine.h, ZeroAllocRenderLoop.h, FFmpegDecoderContext.h,
// FlacNativeDecoder.h, ResamplerEngine.h, DspChain.h, GaplessPlaybackQueue.h)
// are all in the tree but not yet delivered.
//
// This file therefore implements what can be honestly built against
// DELIVERED headers today:
//
//   • Full pimpl (struct Engine::Impl) holding ALL state.
//   • All 51 method bodies (49 public + 2 RenderCallback overrides).
//   • A real, working "silence engine":
//       - initialize() opens a real Oboe stream via AudioOutput.
//       - play() starts the stream, audio output is silence (zeros).
//       - onAudioReady() advances position in real time.
//       - ENDED transition fires when position reaches duration.
//       - All telemetry (sample rate, latency, thread id, etc.) reports
//         real values from the live stream.
//       - Full error state management with getLastError/clearLastError.
//   • Documented growth seams: every method that needs a future
//     subsystem names the file to wire in, marked `// GROWTH:`.
//
// When each subsystem is delivered, its presence is added to Impl and the
// corresponding method body expands — with NO header change to Engine.h
// (the pimpl makes this possible).
//
// ── Compile prerequisites ───────────────────────────────────────────────
// `AudioOutput.h` and `OboeStreamBuilder.h` use explicit `::oboe::`
// qualification for the third-party Oboe namespace. The Oboe dependency
// itself must be populated before this file can compile.
//
// ── Design notes carried over from Engine.h ────────────────────────────
// • All state lives in `Impl`; `Engine` holds only `unique_ptr<Impl> impl_`.
// • Threading: control thread calls play/pause/seek/setX; audio thread
//   calls onAudioReady/onErrorAfterClose. Communication via atomics only.
// • Error handling: fallible methods return false + populate lastError().
// • No mutex is ever taken from the audio thread (see the exception note
//   in AudioOutput.cpp — the same reasoning applies here for the atomic
//   error code path).
//
// ── Forward-reference audit ────────────────────────────────────────────
// ✔ Delivered and used:
//     core/Engine.h            (this file implements its 51 declarations)
//     audio/AudioOutput.h      (via Engine.h — the real stream owner)
//     <oboe/Oboe.h>            (declared dependency, .gitmodules)
//     <atomic>, <cmath>, <cstdint>, <cstring>, <memory>, <mutex>, <string>
//                              (stdlib)
// ✘ NOT included, NOT referenced (all future):
//     Everything listed in Engine.h's forward-reference audit — 30+ headers.

#include "core/Engine.h"

#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <chrono>
#include <fstream>
#include <thread>
#include <vector>
#include "audio/pcm_parser.h"
#include "core/LockFreeRingBuffer.h"

namespace futura::engine {

// ═════════════════════════════════════════════════════════════════════════
// File-local constants and state enums
// ═════════════════════════════════════════════════════════════════════════

namespace {

// Stub metadata — populated by prepareSource() while no real decoder is
// available. Once FFmpegDecoderContext / FlacNativeDecoder land, these
// constants become unused and prepareSource will populate real values
// from the file header.
constexpr int64_t STUB_DURATION_MS = 180'000;    // 3 minutes of silence
constexpr const char* STUB_FORMAT = "Stub";
constexpr int32_t STUB_SAMPLE_RATE = 44100;
constexpr int32_t STUB_CHANNELS = 2;
constexpr int32_t STUB_BIT_DEPTH = 16;

// Playback state — values match NativePlaybackState.nativeValue on the
// Kotlin side (NativePlayerBridge.kt, delivered).
namespace PlaybackState {
    constexpr int IDLE    = 1;
    constexpr int READY   = 2;
    constexpr int PLAYING = 3;
    constexpr int PAUSED  = 4;
    constexpr int ENDED   = 5;
    constexpr int ERROR   = 6;
}

// Stream state — values match NativeStreamState.nativeValue.
namespace StreamState {
    constexpr int UNINITIALIZED = 0;
    constexpr int OPENING       = 1;
    constexpr int STARTED       = 2;
    constexpr int PAUSED        = 3;
    constexpr int FLUSHING      = 4;
    constexpr int STOPPED       = 5;
    constexpr int CLOSING       = 6;
    constexpr int DISCONNECTED  = 7;
    constexpr int ERROR         = 8;
}

}  // namespace

// ═════════════════════════════════════════════════════════════════════════
// Engine::Impl — all state lives here
// ═════════════════════════════════════════════════════════════════════════
//
// The struct exists in this .cpp (not in Engine.h) so it can reference
// future subsystem classes without forcing the header to include their
// definitions. When each subsystem lands, its member is added here and
// the corresponding method body in this file is expanded — Engine.h is
// untouched (pimpl contract).
//
// All atomics use std::memory_order_relaxed except where a genuine
// happens-before ordering matters (e.g., gapless_queued is
// release/acquire so the control thread sees a fully-written URI).

namespace {

// Reads a whole file into `out`. Control thread only; never called from the audio thread.
bool readWholeFile(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) return false;
    const std::streamsize size = in.tellg();
    if (size <= 0) return false;
    out.resize(static_cast<size_t>(size));
    in.seekg(0, std::ios::beg);
    return static_cast<bool>(in.read(reinterpret_cast<char*>(out.data()), size));
}

}  // namespace

struct Engine::Impl {

    // ── Configuration (copied at initialize; read by future features) ──
    EngineConfig config;

    // ── The Oboe stream owner ──────────────────────────────────────────
    // GROWTH: additional subsystems (LockFreeRingBuffer, StreamStateMachine,
    // ZeroAllocRenderLoop, decoder, DspChain, GaplessPlaybackQueue) become
    // members of this struct when delivered. Their presence does not
    // affect any method signature in Engine.h.
    std::unique_ptr<audio::AudioOutput> audio_output;

    // ── Transport / playback state ────────────────────────────────────
    std::atomic<bool>    is_playing{false};
    std::atomic<int>     playback_state{PlaybackState::IDLE};
    std::atomic<int>     stream_state{StreamState::UNINITIALIZED};

    // ── Position / duration (audio thread writes position; control reads) ─
    std::atomic<int64_t> position_frames{0};
    std::atomic<int64_t> duration_ms{0};

    // ── Volume / speed / pitch (control writes; audio thread will read) ─
    std::atomic<float>   volume_linear{1.0f};
    std::atomic<float>   speed{1.0f};
    std::atomic<float>   pitch_semitones{0.0f};

    // ── A/B loop ───────────────────────────────────────────────────────
    std::atomic<bool>    loop_enabled{false};
    std::atomic<int64_t> loop_start_ms{0};
    std::atomic<int64_t> loop_end_ms{0};

    // ── Source state ───────────────────────────────────────────────────
    std::atomic<bool>    source_prepared{false};
    std::atomic<int32_t> source_sample_rate{0};
    std::atomic<int32_t> source_channels{0};
    std::atomic<int32_t> source_bit_depth{0};
    std::string          source_uri;          // control thread only
    int                  source_type = 0;     // control thread only

    // ── Bit-perfect / resampler / silence trim flags ───────────────────
    std::atomic<bool>    bit_perfect_enabled{false};
    std::atomic<int>     resampler_quality{1};    // 1 = BALANCED
    std::atomic<bool>    silence_trim_enabled{false};

    // ── Gapless queue (control thread writes; any thread reads flag) ────
    std::atomic<bool>    gapless_queued{false};
    std::string          gapless_uri;
    int                  gapless_source_type = 0;
    mutable std::mutex   gapless_mutex;

    // ── Error state ────────────────────────────────────────────────────
    // last_result_code is atomic so onErrorAfterClose (audio thread) can
    // record it without a lock. error_message is mutex-guarded because
    // it's a std::string — only written from control-thread failure paths,
    // never from the audio thread.
    std::atomic<::oboe::Result> last_result_code{::oboe::Result::OK};
    mutable std::mutex          error_message_mutex;
    std::string                 error_message;

    // ── Source format string (mutex-guarded; const getter) ─────────────
    mutable std::mutex source_mutex;
    std::string        source_format;

    // ── Stage 1 playback: WAV source → feeder thread → output ring ──────
    // The feeder runs only while playing. Pause and stop join it, so the
    // ring and the streamer have one owner whenever either is changed.
    static constexpr size_t kRingFrames     = 16384;
    static constexpr size_t kOutputChannels = 2;

    std::vector<uint8_t>                                             source_bytes;   // control thread only
    futura::audio::WavPcmStreamer                                    streamer;       // feeder while playing
    std::unique_ptr<futura::engine::core::LockFreeRingBuffer<float>> ring;           // created in initialize()
    std::thread                                                      feeder;
    std::atomic<bool>                                                feeder_running{false};
    std::atomic<bool>                                                looping{false};
    std::atomic<int64_t>                                             loop_length_frames{0};

    void startFeeder() {
        if (feeder_running.exchange(true, std::memory_order_acq_rel)) return;
        feeder = std::thread([this] { feederLoop(); });
    }

    void stopFeeder() {
        feeder_running.store(false, std::memory_order_release);
        if (feeder.joinable()) feeder.join();
    }

    ~Impl() { stopFeeder(); }

    // Moves frames from the streamer into the ring, keeping it topped up.
    // The file is 16-bit PCM; mono is duplicated to stereo to match the ring.
    void feederLoop() {
        constexpr size_t kChunk = 512;
        float src[kChunk * 2];
        float stereo[kChunk * 2];
        while (feeder_running.load(std::memory_order_acquire)) {
            if (!ring || ring->getFramesFree() < kChunk) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
            const size_t got = streamer.readFloat(src, kChunk);
            if (got == 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
            if (streamer.format().channels == 1) {
                for (size_t i = 0; i < got; ++i) {
                    stereo[2 * i]     = src[i];
                    stereo[2 * i + 1] = src[i];
                }
            } else {
                std::memcpy(stereo, src, got * 2 * sizeof(float));
            }
            ring->write(stereo, got);
        }
    }
};

// ═════════════════════════════════════════════════════════════════════════
// Construction / destruction
// ═════════════════════════════════════════════════════════════════════════

Engine::Engine() : impl_(std::make_unique<Impl>()) {}

Engine::~Engine() {
    // Ensure the stream is closed before Impl is destroyed. shutdown()
    // is idempotent and safe even if called twice.
    shutdown();
}

// ═════════════════════════════════════════════════════════════════════════
// 1. Lifecycle
// ═════════════════════════════════════════════════════════════════════════

bool Engine::initialize(const EngineConfig& config) {
    // Idempotent re-initialization: if already initialized, tear down
    // the previous state.
    if (impl_ && impl_->audio_output) {
        shutdown();
    }
    if (!impl_) {
        impl_ = std::make_unique<Impl>();
    }

    impl_->config = config;
    impl_->ring = std::make_unique<futura::engine::core::LockFreeRingBuffer<float>>(
        Impl::kRingFrames, Impl::kOutputChannels);
    impl_->stream_state.store(StreamState::OPENING, std::memory_order_relaxed);

    // Translate EngineConfig → AudioOutputConfig.
    audio::AudioOutputConfig output_config;
    output_config.sample_rate          = config.preferred_sample_rate;
    output_config.channel_count        = 2;
    output_config.frames_per_callback  = config.preferred_frames_per_burst;
    output_config.format               = ::oboe::AudioFormat::Float;
    output_config.performance_mode     =
        config.performance_mode == 2 ? ::oboe::PerformanceMode::PowerSaving :
        config.performance_mode == 0 ? ::oboe::PerformanceMode::None :
                                       ::oboe::PerformanceMode::LowLatency;
    output_config.sharing_mode         =
        config.enable_bit_perfect ? ::oboe::SharingMode::Exclusive
                                  : ::oboe::SharingMode::Shared;
    output_config.fall_back_to_shared  = true;
    output_config.usage                = ::oboe::Usage::Media;
    output_config.content_type         = ::oboe::ContentType::Music;

    impl_->audio_output = std::make_unique<audio::AudioOutput>();
    if (!impl_->audio_output->open(output_config, this)) {
        {
            std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
            impl_->error_message =
                "AudioOutput::open failed: " + impl_->audio_output->lastError();
        }
        impl_->last_result_code.store(
            impl_->audio_output->lastResult(), std::memory_order_relaxed);
        impl_->audio_output.reset();
        impl_->playback_state.store(PlaybackState::ERROR, std::memory_order_relaxed);
        impl_->stream_state.store(StreamState::ERROR, std::memory_order_relaxed);
        return false;
    }

    impl_->bit_perfect_enabled.store(config.enable_bit_perfect,
                                     std::memory_order_relaxed);
    impl_->playback_state.store(PlaybackState::IDLE, std::memory_order_relaxed);
    impl_->stream_state.store(StreamState::STOPPED, std::memory_order_relaxed);
    return true;
}

void Engine::shutdown() {
    if (!impl_) return;
    impl_->stream_state.store(StreamState::CLOSING, std::memory_order_relaxed);
    if (impl_->audio_output) {
        // AudioOutput::close() blocks until the audio thread has fully
        // unwound — after this returns, no further callbacks can fire.
        impl_->audio_output->close();
    }
    // impl_.reset() destroys Impl, which destroys audio_output (whose
    // destructor auto-closes as a safety net — already closed here),
    // releasing all resources.
    impl_.reset();
}

bool Engine::isInitialized() const noexcept {
    // Returns true only if a live stream exists. This intentionally
    // returns false after onErrorAfterClose() fires (the AudioOutput
    // transitions to Closed internally), even though shutdown() was
    // never explicitly called — the caller interprets false as "engine
    // is not usable; call initialize() to recover."
    return impl_ && impl_->audio_output && impl_->audio_output->isOpen();
}

// ═════════════════════════════════════════════════════════════════════════
// 2. Source preparation
// ═════════════════════════════════════════════════════════════════════════

bool Engine::prepareSource(const std::string& uri, int source_type) {
    if (!impl_ || !impl_->audio_output || !impl_->ring) return false;

    // The feeder and the stream must be idle before the ring and the streamer
    // change. Both are control-thread operations, so this order is safe.
    impl_->stopFeeder();
    if (impl_->audio_output->isOpen()) {
        impl_->audio_output->stop();
    }
    impl_->ring->clear();

    {
        std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
        impl_->error_message.clear();
    }
    impl_->last_result_code.store(::oboe::Result::OK, std::memory_order_relaxed);

    // The whole file is read here, on the control thread. The audio thread
    // never touches the file or the parser.
    std::vector<uint8_t> bytes;
    if (!readWholeFile(uri, bytes)) {
        {
            std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
            impl_->error_message = "cannot read source: " + uri;
        }
        impl_->playback_state.store(PlaybackState::ERROR, std::memory_order_relaxed);
        impl_->source_prepared.store(false, std::memory_order_relaxed);
        return false;
    }

    impl_->source_bytes = std::move(bytes);
    const auto parse = impl_->streamer.reset(impl_->source_bytes.data(),
                                             impl_->source_bytes.size());
    if (parse != futura::audio::WavParseResult::kOk) {
        {
            std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
            impl_->error_message = "unsupported WAV (parse result " +
                std::to_string(static_cast<int>(parse)) + ")";
        }
        impl_->playback_state.store(PlaybackState::ERROR, std::memory_order_relaxed);
        impl_->source_prepared.store(false, std::memory_order_relaxed);
        return false;
    }

    // Stage 1: the alarm tone loops until the session stops it.
    impl_->streamer.setLooping(true);
    impl_->looping.store(true, std::memory_order_relaxed);
    const auto& fmt = impl_->streamer.format();
    const int64_t frames = static_cast<int64_t>(impl_->streamer.totalFrames());
    impl_->loop_length_frames.store(frames, std::memory_order_relaxed);

    impl_->source_uri  = uri;
    impl_->source_type = source_type;
    impl_->source_sample_rate.store(static_cast<int32_t>(fmt.sample_rate), std::memory_order_relaxed);
    impl_->source_channels.store(static_cast<int32_t>(fmt.channels), std::memory_order_relaxed);
    impl_->source_bit_depth.store(static_cast<int32_t>(fmt.bits_per_sample), std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(impl_->source_mutex);
        impl_->source_format = "WAV PCM";
    }
    impl_->duration_ms.store(
        fmt.sample_rate > 0 ? frames * 1000 / static_cast<int64_t>(fmt.sample_rate) : 0,
        std::memory_order_relaxed);
    impl_->position_frames.store(0, std::memory_order_relaxed);
    impl_->source_prepared.store(true, std::memory_order_relaxed);
    impl_->playback_state.store(PlaybackState::READY, std::memory_order_relaxed);
    return true;
}

void Engine::reset() {
    if (!impl_) return;

    // Stop the feeder and drop buffered audio; the streamer returns to the start.
    impl_->stopFeeder();
    if (impl_->ring) impl_->ring->clear();
    impl_->streamer.rewind();

    impl_->source_prepared.store(false, std::memory_order_relaxed);
    impl_->position_frames.store(0,     std::memory_order_relaxed);
    impl_->duration_ms.store(0,         std::memory_order_relaxed);
    impl_->source_sample_rate.store(0,  std::memory_order_relaxed);
    impl_->source_channels.store(0,     std::memory_order_relaxed);
    impl_->source_bit_depth.store(0,    std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(impl_->source_mutex);
        impl_->source_format.clear();
    }
    {
        std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
        impl_->error_message.clear();
    }
    impl_->last_result_code.store(::oboe::Result::OK, std::memory_order_relaxed);

    if (impl_->audio_output && impl_->audio_output->isOpen()) {
        impl_->audio_output->stop();
    }
    impl_->is_playing.store(false, std::memory_order_relaxed);
    impl_->playback_state.store(PlaybackState::IDLE, std::memory_order_relaxed);
    impl_->stream_state.store(StreamState::STOPPED, std::memory_order_relaxed);
}

bool Engine::isPrepared() const noexcept {
    return impl_ && impl_->source_prepared.load(std::memory_order_relaxed);
}

// ═════════════════════════════════════════════════════════════════════════
// 3. Transport
// ═════════════════════════════════════════════════════════════════════════

bool Engine::play() {
    if (!impl_ || !impl_->audio_output) return false;
    if (!impl_->source_prepared.load(std::memory_order_relaxed)) return false;
    if (!impl_->audio_output->isOpen()) return false;

    // Fill the ring before the stream starts, so the first callback has data.
    impl_->startFeeder();

    if (!impl_->audio_output->start()) {
        std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
        impl_->error_message =
            "AudioOutput::start failed: " + impl_->audio_output->lastError();
        impl_->last_result_code.store(
            impl_->audio_output->lastResult(), std::memory_order_relaxed);
        return false;
    }

    impl_->is_playing.store(true, std::memory_order_relaxed);
    impl_->playback_state.store(PlaybackState::PLAYING, std::memory_order_relaxed);
    impl_->stream_state.store(StreamState::STARTED, std::memory_order_relaxed);
    return true;
}

bool Engine::pause() {
    if (!impl_ || !impl_->audio_output) return false;
    if (!impl_->audio_output->isOpen()) return false;

    if (!impl_->audio_output->pause()) {
        std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
        impl_->error_message =
            "AudioOutput::pause failed: " + impl_->audio_output->lastError();
        impl_->last_result_code.store(
            impl_->audio_output->lastResult(), std::memory_order_relaxed);
        return false;
    }

    // Pause keeps the buffered audio, so play() resumes without a gap.
    impl_->stopFeeder();
    impl_->is_playing.store(false, std::memory_order_relaxed);
    impl_->playback_state.store(PlaybackState::PAUSED, std::memory_order_relaxed);
    impl_->stream_state.store(StreamState::PAUSED, std::memory_order_relaxed);
    return true;
}

bool Engine::stop() {
    if (!impl_ || !impl_->audio_output) return false;
    if (!impl_->audio_output->isOpen()) return false;

    if (!impl_->audio_output->stop()) {
        std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
        impl_->error_message =
            "AudioOutput::stop failed: " + impl_->audio_output->lastError();
        impl_->last_result_code.store(
            impl_->audio_output->lastResult(), std::memory_order_relaxed);
        return false;
    }

    impl_->is_playing.store(false, std::memory_order_relaxed);
    // Stop drops buffered audio and rewinds to the start.
    impl_->stopFeeder();
    if (impl_->ring) impl_->ring->clear();
    impl_->streamer.rewind();
    impl_->position_frames.store(0, std::memory_order_relaxed);
    impl_->playback_state.store(PlaybackState::READY, std::memory_order_relaxed);
    impl_->stream_state.store(StreamState::STOPPED, std::memory_order_relaxed);
    return true;
}

bool Engine::seekTo(int64_t position_ms) {
    if (!impl_ || !impl_->audio_output) return false;
    if (!impl_->source_prepared.load(std::memory_order_relaxed)) return false;

    int32_t sample_rate = impl_->audio_output->getSampleRate();
    if (sample_rate <= 0) return false;

    int64_t duration_ms = impl_->duration_ms.load(std::memory_order_relaxed);
    if (position_ms < 0) position_ms = 0;
    if (duration_ms > 0 && position_ms > duration_ms) position_ms = duration_ms;

    int64_t new_frames = (position_ms * sample_rate) / 1000;
    impl_->position_frames.store(new_frames, std::memory_order_relaxed);

    // Flush the stream so buffered audio from the pre-seek position does
    // not play. GROWTH: also flush LockFreeRingBuffer here.
    return impl_->audio_output->flush();
}

bool Engine::isPlaying() const noexcept {
    return impl_ && impl_->is_playing.load(std::memory_order_relaxed);
}

// ═════════════════════════════════════════════════════════════════════════
// 4. Position / state
// ═════════════════════════════════════════════════════════════════════════

int64_t Engine::getPositionMs() const noexcept {
    if (!impl_ || !impl_->audio_output) return 0;
    int32_t sample_rate = impl_->audio_output->getSampleRate();
    if (sample_rate <= 0) return 0;
    int64_t frames = impl_->position_frames.load(std::memory_order_relaxed);
    return (frames * 1000) / sample_rate;
}

int64_t Engine::getDurationMs() const noexcept {
    if (!impl_) return -1;
    int64_t duration = impl_->duration_ms.load(std::memory_order_relaxed);
    return duration > 0 ? duration : -1;
}

int Engine::getPlaybackState() const noexcept {
    return impl_
        ? impl_->playback_state.load(std::memory_order_relaxed)
        : PlaybackState::IDLE;
}

int Engine::getStreamState() const noexcept {
    return impl_
        ? impl_->stream_state.load(std::memory_order_relaxed)
        : StreamState::UNINITIALIZED;
}

// ═════════════════════════════════════════════════════════════════════════
// 5. Volume
// ═════════════════════════════════════════════════════════════════════════

void Engine::setVolumeLinear(float volume) {
    if (!impl_) return;
    if (volume < 0.0f) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    impl_->volume_linear.store(volume, std::memory_order_relaxed);
    // GROWTH: when core/NativeVolumeScaling.h is delivered, the render
    // loop reads this value and applies it per sample.
}

void Engine::setVolumeDb(float db) {
    if (!impl_) return;
    if (db < -96.0f) db = -96.0f;
    if (db > 0.0f)   db = 0.0f;
    // -96 dB is treated as silence (0.0 linear) rather than the literal
    // 10^(-96/20) ≈ 1.58e-5, because that's the convention every audio
    // player uses for "volume off."
    float linear = (db <= -96.0f) ? 0.0f : std::pow(10.0f, db / 20.0f);
    impl_->volume_linear.store(linear, std::memory_order_relaxed);
}

float Engine::getVolumeLinear() const noexcept {
    return impl_ ? impl_->volume_linear.load(std::memory_order_relaxed) : 0.0f;
}

// ═════════════════════════════════════════════════════════════════════════
// 6. Speed / pitch
// ═════════════════════════════════════════════════════════════════════════

bool Engine::setPlaybackSpeed(float speed) {
    if (!impl_) return false;
    if (!impl_->source_prepared.load(std::memory_order_relaxed)) return false;
    if (speed < 0.5f) speed = 0.5f;
    if (speed > 3.0f) speed = 3.0f;
    impl_->speed.store(speed, std::memory_order_relaxed);
    // GROWTH: SpeedScalingEngine.h / PitchSpeedPreservingEngine.h will
    // read this value on the next render tick.
    return true;
}

float Engine::getPlaybackSpeed() const noexcept {
    return impl_ ? impl_->speed.load(std::memory_order_relaxed) : 1.0f;
}

bool Engine::setPitchSemitones(float semitones) {
    if (!impl_) return false;
    if (!impl_->source_prepared.load(std::memory_order_relaxed)) return false;
    if (semitones < -24.0f) semitones = -24.0f;
    if (semitones >  24.0f) semitones =  24.0f;
    impl_->pitch_semitones.store(semitones, std::memory_order_relaxed);
    // GROWTH: PitchShiftEngine.h (SoundTouch) will read this on the next
    // render tick.
    return true;
}

float Engine::getPitchSemitones() const noexcept {
    return impl_ ? impl_->pitch_semitones.load(std::memory_order_relaxed) : 0.0f;
}

// ═════════════════════════════════════════════════════════════════════════
// 7. A/B loop
// ═════════════════════════════════════════════════════════════════════════

bool Engine::setLoopAB(int64_t start_ms, int64_t end_ms) {
    if (!impl_) return false;
    if (end_ms <= start_ms) return false;
    int64_t duration = impl_->duration_ms.load(std::memory_order_relaxed);
    if (duration > 0 && end_ms > duration) return false;
    impl_->loop_start_ms.store(start_ms, std::memory_order_relaxed);
    impl_->loop_end_ms.store(end_ms, std::memory_order_relaxed);
    return true;
}

void Engine::clearLoopAB() {
    if (!impl_) return;
    impl_->loop_start_ms.store(0, std::memory_order_relaxed);
    impl_->loop_end_ms.store(0, std::memory_order_relaxed);
    impl_->loop_enabled.store(false, std::memory_order_relaxed);
}

void Engine::setLoopEnabled(bool enabled) {
    if (!impl_) return;
    if (enabled) {
        int64_t start = impl_->loop_start_ms.load(std::memory_order_relaxed);
        int64_t end   = impl_->loop_end_ms.load(std::memory_order_relaxed);
        // Enabling requires valid loop points — silent no-op otherwise,
        // matching NativePlayerBridge.setLoopEnabled's documented behavior.
        if (end <= start) return;
    }
    impl_->loop_enabled.store(enabled, std::memory_order_relaxed);
    // GROWTH: analysis/ABLoopEngine.h will observe this flag and wrap
    // playback from loop_end back to loop_start.
}

bool Engine::isLoopEnabled() const noexcept {
    return impl_ && impl_->loop_enabled.load(std::memory_order_relaxed);
}

// ═════════════════════════════════════════════════════════════════════════
// 8. Gapless queue
// ═════════════════════════════════════════════════════════════════════════

bool Engine::enqueueNext(const std::string& uri, int source_type) {
    if (!impl_) return false;
    {
        std::lock_guard<std::mutex> lock(impl_->gapless_mutex);
        impl_->gapless_uri         = uri;
        impl_->gapless_source_type = source_type;
    }
    impl_->gapless_queued.store(true, std::memory_order_release);
    // GROWTH: when decoders are delivered, this will also pre-decode the
    // next track into GaplessPlaybackQueue.h so the transition is seamless.
    return true;
}

void Engine::clearQueue() {
    if (!impl_) return;
    {
        std::lock_guard<std::mutex> lock(impl_->gapless_mutex);
        impl_->gapless_uri.clear();
        impl_->gapless_source_type = 0;
    }
    impl_->gapless_queued.store(false, std::memory_order_release);
}

int Engine::getQueueSize() const noexcept {
    return (impl_ && impl_->gapless_queued.load(std::memory_order_acquire))
        ? 1 : 0;
}

bool Engine::skipToNext() {
    if (!impl_) return false;
    if (!impl_->gapless_queued.load(std::memory_order_acquire)) return false;

    std::string uri;
    int source_type = 0;
    {
        std::lock_guard<std::mutex> lock(impl_->gapless_mutex);
        uri               = impl_->gapless_uri;
        source_type       = impl_->gapless_source_type;
        impl_->gapless_uri.clear();
        impl_->gapless_source_type = 0;
    }
    impl_->gapless_queued.store(false, std::memory_order_release);

    return prepareSource(uri, source_type);
}

bool Engine::isNextPrepared() const noexcept {
    return impl_ && impl_->gapless_queued.load(std::memory_order_acquire);
}

// ═════════════════════════════════════════════════════════════════════════
// 9. Source info
// ═════════════════════════════════════════════════════════════════════════

std::string Engine::getSourceFormat() const {
    if (!impl_) return "";
    std::lock_guard<std::mutex> lock(impl_->source_mutex);
    return impl_->source_format;
}

int32_t Engine::getSourceSampleRate() const noexcept {
    return impl_ ? impl_->source_sample_rate.load(std::memory_order_relaxed) : 0;
}

int32_t Engine::getSourceChannels() const noexcept {
    return impl_ ? impl_->source_channels.load(std::memory_order_relaxed) : 0;
}

int32_t Engine::getSourceBitDepth() const noexcept {
    return impl_ ? impl_->source_bit_depth.load(std::memory_order_relaxed) : 0;
}

// ═════════════════════════════════════════════════════════════════════════
// 10. Bit-perfect / resampler
// ═════════════════════════════════════════════════════════════════════════

bool Engine::setBitPerfectEnabled(bool enabled) {
    if (!impl_) return false;
    // GROWTH: bitperfect/BitPerfectSwitcher.h will re-open the stream with
    // the appropriate sharing mode. On most modern Android devices,
    // Exclusive mode is refused by the audio HAL and falls back to Shared,
    // so the caller's UI should display the EFFECTIVE state (via the
    // future StreamInspector) rather than just the requested one.
    impl_->bit_perfect_enabled.store(enabled, std::memory_order_relaxed);
    return true;
}

bool Engine::isBitPerfectEnabled() const noexcept {
    return impl_ && impl_->bit_perfect_enabled.load(std::memory_order_relaxed);
}

void Engine::setResamplerQuality(int quality) {
    if (!impl_) return;
    if (quality < 0) quality = 0;
    if (quality > 3) quality = 3;
    impl_->resampler_quality.store(quality, std::memory_order_relaxed);
    // GROWTH: decode/ResamplerEngine.h will read this value on the next
    // stream configuration.
}

int Engine::getResamplerQuality() const noexcept {
    return impl_ ? impl_->resampler_quality.load(std::memory_order_relaxed) : 1;
}

// ═════════════════════════════════════════════════════════════════════════
// 11. Silence trimming
// ═════════════════════════════════════════════════════════════════════════

void Engine::setSilenceTrimEnabled(bool enabled) {
    if (!impl_) return;
    impl_->silence_trim_enabled.store(enabled, std::memory_order_relaxed);
    // GROWTH: the decoder (once delivered) will skip silence at the start
    // and end of each track based on this flag.
}

bool Engine::isSilenceTrimEnabled() const noexcept {
    return impl_ && impl_->silence_trim_enabled.load(std::memory_order_relaxed);
}

// ═════════════════════════════════════════════════════════════════════════
// 12. Error state
// ═════════════════════════════════════════════════════════════════════════

std::string Engine::getLastError() const {
    if (!impl_) return "";

    ::oboe::Result code = impl_->last_result_code.load(std::memory_order_relaxed);
    if (code == ::oboe::Result::OK) return "";

    {
        std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
        if (!impl_->error_message.empty()) return impl_->error_message;
    }
    // No detailed message was recorded (typical after onErrorAfterClose
    // fired — that path stores only the error code). Synthesize from
    // the code.
    return std::string("Error: ") + ::oboe::convertToText(code);
}

void Engine::clearLastError() {
    if (!impl_) return;
    impl_->last_result_code.store(::oboe::Result::OK, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(impl_->error_message_mutex);
    impl_->error_message.clear();
}

// ═════════════════════════════════════════════════════════════════════════
// 13. Diagnostics
// ═════════════════════════════════════════════════════════════════════════

int64_t Engine::getBufferedPositionMs() const noexcept {
    // GROWTH: with a real decoder, this returns the decoder's buffer head
    // position. In the skeleton there is no buffer ahead of the playhead
    // (we output silence in real time), so buffered == current.
    return getPositionMs();
}

int64_t Engine::getLoadedMs() const noexcept {
    if (!impl_) return 0;
    int64_t duration = impl_->duration_ms.load(std::memory_order_relaxed);
    return duration > 0 ? duration : 0;
}

int32_t Engine::getOutputSampleRate() const noexcept {
    if (!impl_ || !impl_->audio_output) return 0;
    return impl_->audio_output->getSampleRate();
}

int32_t Engine::getOutputChannels() const noexcept {
    if (!impl_ || !impl_->audio_output) return 0;
    return impl_->audio_output->getChannelCount();
}

int32_t Engine::getRenderThreadId() const noexcept {
    if (!impl_ || !impl_->audio_output) return -1;
    return impl_->audio_output->getRenderThreadId();
}

// ═════════════════════════════════════════════════════════════════════════
// 14. Device info
// ═════════════════════════════════════════════════════════════════════════

int32_t Engine::getDeviceSampleRate() const noexcept {
    return getOutputSampleRate();
}

int32_t Engine::getDeviceFramesPerBurst() const noexcept {
    if (!impl_ || !impl_->audio_output) return 0;
    return impl_->audio_output->getFramesPerBurst();
}

double Engine::getDeviceHardwareLatencyMs() const noexcept {
    if (!impl_ || !impl_->audio_output) return 0.0;
    return impl_->audio_output->getLatencyMs();
}

// ═════════════════════════════════════════════════════════════════════════
// 15. RenderCallback overrides
// ═════════════════════════════════════════════════════════════════════════

::oboe::DataCallbackResult Engine::onAudioReady(
    ::oboe::AudioStream* /*stream*/,
    void* audio_data,
    int32_t num_frames) {

    // Real-time path — no allocation, no locks, no blocking.
    if (!impl_ || !audio_data) {
        return ::oboe::DataCallbackResult::Stop;
    }

    int32_t channel_count = impl_->audio_output
        ? impl_->audio_output->getChannelCount()
        : 0;
    if (channel_count <= 0) channel_count = 2;  // defensive fallback

    float* out = static_cast<float*>(audio_data);
    const size_t total_samples = static_cast<size_t>(num_frames)
                               * static_cast<size_t>(channel_count);

    // Fill from the ring. The ring holds stereo frames, so it is read only
    // when the stream is stereo. On an underrun, the rest is zero-filled.
    size_t frames_read = 0;
    if (impl_->ring && channel_count == static_cast<int32_t>(Impl::kOutputChannels)) {
        frames_read = impl_->ring->read(out, static_cast<size_t>(num_frames));
    }
    const size_t filled = frames_read * static_cast<size_t>(channel_count);
    if (filled < total_samples) {
        std::memset(out + filled, 0, (total_samples - filled) * sizeof(float));
    }

    // Advance position when playing. A looping source wraps instead of ending.
    if (impl_->is_playing.load(std::memory_order_relaxed)) {
        const bool looping = impl_->looping.load(std::memory_order_relaxed);
        int64_t prev = impl_->position_frames.load(std::memory_order_relaxed);
        int64_t new_frames = prev + num_frames;
        if (looping) {
            const int64_t loop_frames =
                impl_->loop_length_frames.load(std::memory_order_relaxed);
            if (loop_frames > 0) new_frames %= loop_frames;
        }
        impl_->position_frames.store(new_frames, std::memory_order_relaxed);

        // End-of-track detection (non-looping sources only).
        int64_t duration_ms = impl_->duration_ms.load(std::memory_order_relaxed);
        int32_t sample_rate = impl_->audio_output
            ? impl_->audio_output->getSampleRate() : 0;
        if (!looping && duration_ms > 0 && sample_rate > 0) {
            int64_t duration_frames = (duration_ms * sample_rate) / 1000;
            if (new_frames >= duration_frames) {
                impl_->is_playing.store(false, std::memory_order_relaxed);
                impl_->playback_state.store(PlaybackState::ENDED,
                                            std::memory_order_relaxed);
                impl_->position_frames.store(duration_frames,
                                             std::memory_order_relaxed);
            }
        }
    }

    return ::oboe::DataCallbackResult::Continue;
}

void Engine::onErrorAfterClose(
    ::oboe::AudioStream* /*stream*/,
    ::oboe::Result error) {

    // Real-time path — no mutex, no allocation. Record only the error
    // code atomically; getLastError() synthesizes a message from it.
    if (!impl_) return;

    impl_->last_result_code.store(error, std::memory_order_relaxed);
    impl_->is_playing.store(false, std::memory_order_relaxed);
    impl_->playback_state.store(PlaybackState::ERROR, std::memory_order_relaxed);
    impl_->stream_state.store(StreamState::DISCONNECTED, std::memory_order_relaxed);

    // The AudioOutput will transition its own state to Closed internally
    // (see AudioOutput.cpp's onErrorAfterClose). Engine::isInitialized()
    // observes that and returns false, signalling to the control thread
    // that recovery is needed.
}

}  // namespace futura::engine