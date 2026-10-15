#include "groove/MonoNoteStack.h"

#include <gtest/gtest.h>

using groove::MonoNoteStack;

TEST(MonoNoteStack, EmptyHasNoTop) {
    MonoNoteStack s;
    EXPECT_TRUE(s.empty());
    EXPECT_EQ(s.top(), MonoNoteStack::kNone);
}

TEST(MonoNoteStack, LastNoteWins) {
    MonoNoteStack s;
    s.push(60);
    s.push(64);
    EXPECT_EQ(s.top(), 64);
}

TEST(MonoNoteStack, ReleasingTopFallsBackToPreviousHeldNote) {
    MonoNoteStack s;
    s.push(60);
    s.push(64);
    s.remove(64);
    EXPECT_EQ(s.top(), 60);
}

TEST(MonoNoteStack, ReleasingNonTopKeepsTop) {
    MonoNoteStack s;
    s.push(60);
    s.push(64);
    s.remove(60);
    EXPECT_EQ(s.top(), 64);
    EXPECT_EQ(s.size(), 1u);
}

TEST(MonoNoteStack, RepushMovesNoteToTopWithoutDuplicating) {
    MonoNoteStack s;
    s.push(60);
    s.push(64);
    s.push(60);
    EXPECT_EQ(s.top(), 60);
    EXPECT_EQ(s.size(), 2u);
    s.remove(60);
    EXPECT_EQ(s.top(), 64);
}

TEST(MonoNoteStack, OverflowDropsOldest) {
    MonoNoteStack s;
    for (int i = 0; i < static_cast<int>(MonoNoteStack::kCapacity) + 1; ++i) s.push(i);
    EXPECT_EQ(s.size(), MonoNoteStack::kCapacity);
    EXPECT_EQ(s.top(), static_cast<int>(MonoNoteStack::kCapacity));
    s.remove(0);  // already dropped; no effect
    EXPECT_EQ(s.size(), MonoNoteStack::kCapacity);
}

TEST(MonoNoteStack, RemovingUnknownNoteIsHarmless) {
    MonoNoteStack s;
    s.remove(42);
    EXPECT_TRUE(s.empty());
}
