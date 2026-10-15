#pragma once

#include <cstddef>

namespace groove::test {

// Counts global operator new calls made on the current thread while a scope is active.
// Used to prove render paths never allocate (audio thread rule).
class AllocationScope {
public:
    AllocationScope();
    ~AllocationScope();
    std::size_t count() const;
};

}  // namespace groove::test
