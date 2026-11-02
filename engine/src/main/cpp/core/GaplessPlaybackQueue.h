// futura-audio/engine/src/main/cpp/core/GaplessPlaybackQueue.h
//
// Phase 3 — Gapless playback queue (feature #67). Holds at most ONE
// pre-decoded next track (the "pending" slot). Multiple pre-decoded
// tracks would require multiple decoder contexts alive simultaneously
// (hundreds of MB for hi-res FLAC), which is not worth the complexity
// for a music player.
//
// Lifecycle:
//   1. Caller pre-decodes next track, then calls push(info) + set_ready().
//   2. At track end, DecoderThread/Engine calls consume() to atomically
//      take the pending track and reset the queue.
//   3. Caller replaces DecoderThread's source with a new one built from
//      the consumed info.
//
// has_ready() is lock-free (atomic). consume() takes mutex_ once at the
// track boundary — never on the audio path.
//
// Growth:
//   • decode/MemoryMappedFile.h — no integration needed here; that's
//     the decoder's concern, not the queue's.
//   • Unrelated to bit-perfect or DSP; those are downstream.

#ifndef FUTURA_ENGINE_CORE_GAPLESS_PLAYBACK_QUEUE_H
#define FUTURA_ENGINE_CORE_GAPLESS_PLAYBACK_QUEUE_H

#include <atomic>
#include <cstdint>
#include <exception>
#include <mutex>
#include <string>
#include <utility>

namespace futura::engine::core {

// Metadata needed to open the next track's decoder. Populated by the
// caller during pre-decode; consumed at track transition.
struct GaplessTrackInfo {
    std::string uri;           // filesystem path (content:// already resolved)
    int         source_type = 0; // SourceType.nativeValue (0=FILE,1=CONTENT,2=ASSET)
    int32_t     sample_rate = 0;
    int32_t     channels    = 0;
    int32_t     bit_depth   = 0;
    int64_t     duration_ms = -1;
};

class GaplessPlaybackQueue {
public:
    GaplessPlaybackQueue() noexcept = default;

    ~GaplessPlaybackQueue() = default;

    GaplessPlaybackQueue(const GaplessPlaybackQueue&) = delete;
    GaplessPlaybackQueue& operator=(const GaplessPlaybackQueue&) = delete;
    GaplessPlaybackQueue(GaplessPlaybackQueue&&) = delete;
    GaplessPlaybackQueue& operator=(GaplessPlaybackQueue&&) = delete;

    // ── Producer side (control thread) ─────────────────────────────
    //
    // push overwrites any existing pending track. Callers that want
    // "reject if already queued" should check has_pending() first.
    // Returns true on success, false on any failure (empty uri,
    // allocation failure on info.uri).
    bool push(const GaplessTrackInfo& info) noexcept {
        try {
            if (info.uri.empty()) {
                set_error("push: empty uri");
                return false;
            }

            std::lock_guard<std::mutex> lock(mutex_);
            pending_ = info;                       // may throw bad_alloc
            // Clear ready before raising has_pending, so a lock-free
            // has_ready() can never see the new track marked as ready.
            ready_.store(false, std::memory_order_release);
            has_pending_.store(true, std::memory_order_release);
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("push: ") + e.what());
        } catch (...) {
            set_error("push: unknown exception");
        }
        return false;
    }

    // Mark the pending track as fully pre-decoded and safe to transition
    // to. Caller calls this AFTER the decoder context is open and warm.
    // No-op if there is no pending track.
    void set_ready(bool ready = true) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!has_pending_.load(std::memory_order_acquire)) {
                ready_.store(false, std::memory_order_release);
                return;
            }
            ready_.store(ready, std::memory_order_release);
        } catch (...) { /* noexcept */ }
    }

    // ── Consumer side (control thread or decoder thread at EOF) ────

    // Lock-free fast check: is there a ready track waiting?
    // Safe to call from the audio thread (never blocks).
    bool has_ready() const noexcept {
        return ready_.load(std::memory_order_acquire) &&
               has_pending_.load(std::memory_order_acquire);
    }

    // Lock-free check: is there a pending track (ready or not)?
    bool has_pending() const noexcept {
        return has_pending_.load(std::memory_order_acquire);
    }

    // Take the pending track and reset the queue. Returns false if no
    // ready track exists. Blocks briefly on mutex_ at track boundaries.
    bool consume(GaplessTrackInfo& out) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!has_pending_.load(std::memory_order_acquire) ||
                !ready_.load(std::memory_order_acquire)) {
                return false;
            }
            out = std::move(pending_);             // may throw bad_alloc
            pending_ = GaplessTrackInfo{};
            has_pending_.store(false, std::memory_order_release);
            ready_.store(false, std::memory_order_release);
            return true;
        } catch (const std::exception& e) {
            set_error(std::string("consume: ") + e.what());
        } catch (...) {
            set_error("consume: unknown exception");
        }
        return false;
    }

    // ── Control (control thread) ───────────────────────────────────

    // Discard any pending track. Does not affect currently-playing.
    void clear() noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            pending_ = GaplessTrackInfo{};
            has_pending_.store(false, std::memory_order_release);
            ready_.store(false, std::memory_order_release);
        } catch (...) { /* noexcept */ }
    }

    // Snapshot the pending track (does not consume). Returns false if
    // no pending track exists.
    bool peek(GaplessTrackInfo& out) const noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!has_pending_.load(std::memory_order_acquire)) return false;
            out = pending_;                        // may throw bad_alloc
            return true;
        } catch (...) {
            return false;
        }
    }

    // 0 or 1 — the queue holds at most one track.
    int size() const noexcept {
        return has_pending_.load(std::memory_order_acquire) ? 1 : 0;
    }

    std::string last_error() const {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            return last_error_;
        } catch (...) { return {}; }
    }

private:
    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    // Rule 7: pending_ is guarded by mutex_. The two atomics are used
    // for lock-free reads on the audio/track-boundary path.
    mutable std::mutex          mutex_;
    GaplessTrackInfo            pending_;
    std::atomic<bool>           has_pending_{false};
    std::atomic<bool>           ready_{false};

    mutable std::mutex          error_mutex_;
    std::string                 last_error_;
};

}  // namespace futura::engine::core

#endif  // FUTURA_ENGINE_CORE_GAPLESS_PLAYBACK_QUEUE_H