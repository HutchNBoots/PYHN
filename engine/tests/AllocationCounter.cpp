#include "AllocationCounter.h"

#include <cstdlib>
#include <new>

namespace {
thread_local bool tTracking = false;
thread_local std::size_t tCount = 0;

void* allocate(std::size_t size) {
    if (tTracking) ++tCount;
    if (size == 0) size = 1;
    if (void* p = std::malloc(size)) return p;
    throw std::bad_alloc();
}
}  // namespace

void* operator new(std::size_t size) { return allocate(size); }
void* operator new[](std::size_t size) { return allocate(size); }
void* operator new(std::size_t size, const std::nothrow_t&) noexcept {
    try {
        return allocate(size);
    } catch (...) {
        return nullptr;
    }
}
void* operator new[](std::size_t size, const std::nothrow_t&) noexcept {
    try {
        return allocate(size);
    } catch (...) {
        return nullptr;
    }
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }

namespace groove::test {

AllocationScope::AllocationScope() {
    tCount = 0;
    tTracking = true;
}

AllocationScope::~AllocationScope() { tTracking = false; }

std::size_t AllocationScope::count() const { return tCount; }

}  // namespace groove::test
