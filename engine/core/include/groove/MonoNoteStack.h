#pragma once

#include <array>
#include <cstddef>

namespace groove {

// Held-note stack for a monophonic voice with last-note priority.
// Fixed capacity; when full, the oldest held note is dropped. Real-time safe.
class MonoNoteStack {
public:
    static constexpr std::size_t kCapacity = 16;
    static constexpr int kNone = -1;

    void push(int note) {
        remove(note);
        if (size_ == kCapacity) {
            for (std::size_t i = 1; i < size_; ++i) notes_[i - 1] = notes_[i];
            --size_;
        }
        notes_[size_++] = note;
    }

    void remove(int note) {
        std::size_t out = 0;
        for (std::size_t i = 0; i < size_; ++i) {
            if (notes_[i] != note) notes_[out++] = notes_[i];
        }
        size_ = out;
    }

    void clear() { size_ = 0; }

    int top() const { return size_ == 0 ? kNone : notes_[size_ - 1]; }
    // Held notes oldest first: at(size() - 1) == top(). i < size().
    int at(std::size_t i) const { return notes_[i]; }
    bool empty() const { return size_ == 0; }
    std::size_t size() const { return size_; }

private:
    std::array<int, kCapacity> notes_{};
    std::size_t size_ = 0;
};

}  // namespace groove
