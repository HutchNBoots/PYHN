#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <type_traits>

namespace groove {

// Lock-free single-producer single-consumer ring buffer with fixed capacity.
// Exactly one thread may call push() and exactly one (other) thread may call pop().
// Never allocates after construction, so it is safe to use on the audio thread.
template <typename T, std::size_t Capacity>
class SpscQueue {
    static_assert(Capacity >= 2 && (Capacity & (Capacity - 1)) == 0,
                  "Capacity must be a power of two");
    static_assert(std::is_trivially_copyable_v<T>, "T must be trivially copyable");

public:
    // Producer side. Returns false (and drops the item) when the queue is full.
    bool push(const T& item) {
        const std::size_t tail = tail_.load(std::memory_order_relaxed);
        const std::size_t head = head_.load(std::memory_order_acquire);
        if (tail - head == Capacity) return false;
        slots_[tail & kMask] = item;
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    // Consumer side. Returns false when the queue is empty.
    bool pop(T& out) {
        const std::size_t head = head_.load(std::memory_order_relaxed);
        const std::size_t tail = tail_.load(std::memory_order_acquire);
        if (head == tail) return false;
        out = slots_[head & kMask];
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    static constexpr std::size_t capacity() { return Capacity; }

private:
    static constexpr std::size_t kMask = Capacity - 1;
    // Separate cache lines so producer and consumer do not false-share.
    alignas(64) std::atomic<std::size_t> head_{0};
    alignas(64) std::atomic<std::size_t> tail_{0};
    alignas(64) std::array<T, Capacity> slots_{};
};

}  // namespace groove
