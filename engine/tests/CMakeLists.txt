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
    AdsrTest.cpp
    DspMathTest.cpp
    FiltersTest.cpp
    LfoTest.cpp
    MasterBusTest.cpp
    MonoNoteStackTest.cpp
    OscillatorTest.cpp
    PresetsTest.cpp
    RealtimeSafetyTest.cpp
    SpscQueueTest.cpp
    SynthEngineTest.cpp
    SynthParamsTest.cpp
)
target_link_libraries(engine_tests PRIVATE groovecore GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(engine_tests DISCOVERY_TIMEOUT 60)

# The benchmark compiles the core again with optimisation, so the number it prints
# means something even in Debug (CI) builds.
add_executable(engine_benchmark EngineBenchmark.cpp ${GROOVE_CORE_SOURCES})
target_include_directories(engine_benchmark PRIVATE ${PROJECT_SOURCE_DIR}/core/include)
target_compile_options(engine_benchmark PRIVATE -O2 -DNDEBUG)
target_link_libraries(engine_benchmark PRIVATE GTest::gtest_main)
add_test(NAME EngineBenchmark COMMAND engine_benchmark)
