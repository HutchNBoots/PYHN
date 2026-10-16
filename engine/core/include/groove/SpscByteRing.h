#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>

namespace groove {

// Lock-free single-producer single-consumer byte ring for variable-size records
// (docs/plans/2026-10-16-m4-keep-it.md: the tape's changed blocks on their way to the writer).
//
// The producer writes a whole record (one or two spans) or nothing; the consumer reads
// records back with read() in the order they were written. Head and tail are monotonic
// byte counters; the capacity is a power of two. Allocates only in the constructor, so the
// producer side is safe on the audio thread.
class SpscByteRing {
public:
    // capacity is rounded up to a power of two (at least 64 bytes).
    explicit SpscByteRing(std::size_t capacity) {
        std::size_t c = 64;
        while (c < capacity) c *= 2;
        capacity_ = c;
        mask_ = c - 1;
        data_.reset(new uint8_t[c]);
    }
    SpscByteRing(const SpscByteRing&) = delete;
    SpscByteRing& operator=(const SpscByteRing&) = delete;

    std::size_t capacity() const { return capacity_; }

    // --- producer ---
    std::size_t freeBytes() const {
        const std::size_t tail = tail_.load(std::memory_order_relaxed);
        const std::size_t head = head_.load(std::memory_order_acquire);
        return capacity_ - (tail - head);
    }
    // Writes a then b as one record; false (nothing written) if they don't fit.
    bool write(const void* a, std::size_t na, const void* b = nullptr, std::size_t nb = 0) {
        const std::size_t tail = tail_.load(std::memory_order_relaxed);
        const std::size_t head = head_.load(std::memory_order_acquire);
        if (capacity_ - (tail - head) < na + nb) return false;
        copyIn(tail, a, na);
        if (nb > 0) copyIn(tail + na, b, nb);
        tail_.store(tail + na + nb, std::memory_order_release);
        return true;
    }

    // --- consumer ---
    std::size_t readable() const {
        return tail_.load(std::memory_order_acquire) - head_.load(std::memory_order_relaxed);
    }
    // Copies n bytes out and consumes them; false (nothing consumed) if fewer are readable.
    bool read(void* out, std::size_t n) {
        const std::size_t head = head_.load(std::memory_order_relaxed);
        const std::size_t tail = tail_.load(std::memory_order_acquire);
        if (tail - head < n) return false;
        const std::size_t at = head & mask_;
        const std::size_t first = n < capacity_ - at ? n : capacity_ - at;
        std::memcpy(out, data_.get() + at, first);
        if (n > first) std::memcpy(static_cast<uint8_t*>(out) + first, data_.get(), n - first);
        head_.store(head + n, std::memory_order_release);
        return true;
    }

private:
    void copyIn(std::size_t pos, const void* src, std::size_t n) {
        const std::size_t at = pos & mask_;
        const std::size_t first = n < capacity_ - at ? n : capacity_ - at;
        std::memcpy(data_.get() + at, src, first);
        if (n > first) std::memcpy(data_.get(), static_cast<const uint8_t*>(src) + first, n - first);
    }

    std::size_t capacity_ = 0;
    std::size_t mask_ = 0;
    std::unique_ptr<uint8_t[]> data_;
    alignas(64) std::atomic<std::size_t> head_{0};
    alignas(64) std::atomic<std::size_t> tail_{0};
};

}  // namespace groove
