// futura-audio/engine/src/main/cpp/decode/MemoryMappedFile.h
//
// Phase 3 — mmap-backed file access (feature #63). Direct memory-mapped
// read access to a local file. Used by FFmpegDecoderContext via a custom
// AVIOContext (caller-side integration; this file is only the mmap primitive).
//
// close() contract: must not be called concurrently with an in-flight
// read of the mapped region. `data()` returns the atomic pointer; a
// reader that already obtained it before close() began must finish before
// close() unmaps. Same pattern as LockFreeRingBuffer::clear().

#ifndef FUTURA_ENGINE_DECODE_MEMORY_MAPPED_FILE_H
#define FUTURA_ENGINE_DECODE_MEMORY_MAPPED_FILE_H

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <mutex>
#include <string>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace futura::engine::decode {

class MemoryMappedFile {
public:
    MemoryMappedFile() noexcept = default;

    ~MemoryMappedFile() { close(); }

    MemoryMappedFile(const MemoryMappedFile&) = delete;
    MemoryMappedFile& operator=(const MemoryMappedFile&) = delete;
    MemoryMappedFile(MemoryMappedFile&&) = delete;
    MemoryMappedFile& operator=(MemoryMappedFile&&) = delete;

    // Map `path` fully into memory. Empty files succeed (size() == 0,
    // data() == nullptr). Returns false on any OS error, with
    // last_error() carrying a human-readable message.
    bool open(const std::string& path) noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);

            if (mapped_base_ || fd_ >= 0) close_locked();

            if (path.empty()) {
                set_error_locked("open: empty path");
                return false;
            }

            fd_ = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
            if (fd_ < 0) {
                set_errno_locked("open");
                return false;
            }

            struct stat st;
            if (::fstat(fd_, &st) != 0) {
                set_errno_locked("fstat");
                ::close(fd_);
                fd_ = -1;
                return false;
            }
            if (!S_ISREG(st.st_mode)) {
                set_error_locked("open: not a regular file");
                ::close(fd_);
                fd_ = -1;
                return false;
            }

            const size_t len = static_cast<size_t>(st.st_size);
            if (len == 0) {
                size_atomic_.store(0, std::memory_order_release);
                data_atomic_.store(nullptr, std::memory_order_release);
                open_atomic_.store(true, std::memory_order_release);
                return true;
            }

            void* base = ::mmap(nullptr, len, PROT_READ, MAP_PRIVATE, fd_, 0);
            if (base == MAP_FAILED) {
                set_errno_locked("mmap");
                ::close(fd_);
                fd_ = -1;
                return false;
            }

            ::madvise(base, len, MADV_SEQUENTIAL);
            ::posix_fadvise(fd_, 0, static_cast<off_t>(len),
                            POSIX_FADV_SEQUENTIAL);

            mapped_base_ = base;
            mapped_len_  = len;
            data_atomic_.store(static_cast<const uint8_t*>(base),
                               std::memory_order_release);
            size_atomic_.store(len, std::memory_order_release);
            open_atomic_.store(true, std::memory_order_release);
            return true;
        } catch (const std::exception& e) {
            close();  // release any fd or mapping opened before the throw
            set_error(std::string("open: ") + e.what());
        } catch (...) {
            close();
            set_error("open: unknown exception");
        }
        return false;
    }

    // Unmap and close the fd. Idempotent. See "close() contract" above.
    void close() noexcept {
        try {
            std::lock_guard<std::mutex> lock(state_mutex_);
            close_locked();
        } catch (...) { /* noexcept */ }
    }

    // Lock-free. Returns nullptr before open() or after close().
    // A caller holding this pointer across a close() runs the risk
    // documented in the file header.
    const uint8_t* data() const noexcept {
        return data_atomic_.load(std::memory_order_acquire);
    }

    // Lock-free. Returns 0 before open() and after close().
    size_t size() const noexcept {
        return size_atomic_.load(std::memory_order_acquire);
    }

    bool is_open() const noexcept {
        return open_atomic_.load(std::memory_order_acquire);
    }

    std::string last_error() const {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            return last_error_;
        } catch (...) { return {}; }
    }

private:
    // Caller must hold state_mutex_.
    void close_locked() noexcept {
        if (mapped_base_) {
            ::munmap(mapped_base_, mapped_len_);
            mapped_base_ = nullptr;
            mapped_len_  = 0;
        }
        if (fd_ >= 0) {
            ::close(fd_);
            fd_ = -1;
        }
        data_atomic_.store(nullptr, std::memory_order_release);
        size_atomic_.store(0, std::memory_order_release);
        open_atomic_.store(false, std::memory_order_release);
    }

    // Caller must hold state_mutex_. Used from open()'s catch blocks
    // BEFORE the lock is released — but the outer catch releases it
    // first, so this takes error_mutex_ directly.
    void set_error_locked(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    // Caller must hold state_mutex_. Captures errno immediately.
    void set_errno_locked(const char* op) noexcept {
        const int saved = errno;
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = std::string(op) + ": " +
                          (saved == ENOENT  ? "ENOENT (file not found)" :
                           saved == EACCES  ? "EACCES (permission denied)" :
                           saved == EMFILE  ? "EMFILE (too many open files)" :
                           saved == ENOMEM  ? "ENOMEM (out of memory)" :
                           saved == EISDIR  ? "EISDIR (is a directory)" :
                                              "errno " + std::to_string(saved));
        } catch (...) { /* noexcept */ }
    }

    // Used from open()'s outer catch block — state_mutex_ is released by
    // then, so this acquires error_mutex_ directly.
    void set_error(const std::string& msg) noexcept {
        try {
            std::lock_guard<std::mutex> lock(error_mutex_);
            last_error_ = msg;
        } catch (...) { /* noexcept */ }
    }

    mutable std::mutex state_mutex_;
    int                fd_          = -1;
    void*              mapped_base_ = nullptr;
    size_t             mapped_len_  = 0;

    std::atomic<const uint8_t*> data_atomic_{nullptr};
    std::atomic<size_t>         size_atomic_{0};
    std::atomic<bool>           open_atomic_{false};

    mutable std::mutex error_mutex_;
    std::string        last_error_;
};

}  // namespace futura::engine::decode

#endif  // FUTURA_ENGINE_DECODE_MEMORY_MAPPED_FILE_H