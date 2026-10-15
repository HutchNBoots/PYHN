#include "groove/SpscQueue.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <thread>

using groove::SpscQueue;

TEST(SpscQueue, PopOnEmptyReturnsFalse) {
    SpscQueue<int, 4> q;
    int v = -1;
    EXPECT_FALSE(q.pop(v));
    EXPECT_EQ(v, -1);
}

TEST(SpscQueue, PreservesFifoOrder) {
    SpscQueue<int, 8> q;
    for (int i = 0; i < 5; ++i) ASSERT_TRUE(q.push(i));
    for (int i = 0; i < 5; ++i) {
        int v = -1;
        ASSERT_TRUE(q.pop(v));
        EXPECT_EQ(v, i);
    }
}

TEST(SpscQueue, RejectsPushWhenFullAndKeepsContents) {
    SpscQueue<int, 4> q;
    for (int i = 0; i < 4; ++i) ASSERT_TRUE(q.push(i));
    EXPECT_FALSE(q.push(99));
    int v = -1;
    ASSERT_TRUE(q.pop(v));
    EXPECT_EQ(v, 0);
    EXPECT_TRUE(q.push(4));
}

TEST(SpscQueue, WrapsAroundManyTimes) {
    SpscQueue<int, 4> q;
    for (int i = 0; i < 1000; ++i) {
        ASSERT_TRUE(q.push(i));
        ASSERT_TRUE(q.push(i + 1));
        int a = -1, b = -1;
        ASSERT_TRUE(q.pop(a));
        ASSERT_TRUE(q.pop(b));
        EXPECT_EQ(a, i);
        EXPECT_EQ(b, i + 1);
    }
}

TEST(SpscQueue, TwoThreadsDeliverEveryItemInOrder) {
    constexpr int64_t kItems = 200000;
    SpscQueue<int64_t, 64> q;
    std::thread producer([&] {
        for (int64_t i = 0; i < kItems; ++i) {
            while (!q.push(i)) std::this_thread::yield();
        }
    });
    int64_t expected = 0;
    while (expected < kItems) {
        int64_t v;
        if (q.pop(v)) {
            ASSERT_EQ(v, expected);
            ++expected;
        } else {
            std::this_thread::yield();
        }
    }
    producer.join();
}
