include(FetchContent)
FetchContent_Declare(
    googletest
    GIT_REPOSITORY https://github.com/google/googletest.git
    GIT_TAG v1.15.2
    GIT_SHALLOW TRUE
)
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_executable(engine_tests
    AllocationCounter.cpp
    SpscQueueTest.cpp
    OscillatorTest.cpp
    EnvelopeTest.cpp
    MonoNoteStackTest.cpp
    SynthCoreTest.cpp
)
target_link_libraries(engine_tests PRIVATE groovecore GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(engine_tests)
