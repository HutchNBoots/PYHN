cmake_minimum_required(VERSION 3.22)
project(groovebox_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(GROOVE_BUILD_TESTS "Build host unit tests (GoogleTest)" OFF)
set(GROOVE_SANITIZE "" CACHE STRING "Sanitizers for host builds, e.g. address,undefined or thread")

if(GROOVE_SANITIZE)
    add_compile_options(-fsanitize=${GROOVE_SANITIZE} -fno-omit-frame-pointer -fno-sanitize-recover=all)
    add_link_options(-fsanitize=${GROOVE_SANITIZE})
endif()

set(GROOVE_WARNINGS -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Werror)

# Platform-independent DSP core. No Android, Oboe or JNI here: it must build and test on the host.
add_library(groovecore STATIC
    core/src/Oscillator.cpp
    core/src/Envelope.cpp
    core/src/SynthCore.cpp
)
target_include_directories(groovecore PUBLIC core/include)
target_compile_options(groovecore PRIVATE ${GROOVE_WARNINGS})

if(ANDROID)
    find_package(oboe REQUIRED CONFIG)
    add_library(groovebox SHARED
        android/AudioEngine.cpp
        android/JniBridge.cpp
    )
    target_compile_options(groovebox PRIVATE ${GROOVE_WARNINGS})
    target_link_libraries(groovebox PRIVATE groovecore oboe::oboe android log)
    # 16 KB page size support (required for new apps on recent Android releases).
    target_link_options(groovebox PRIVATE "-Wl,-z,max-page-size=16384")
endif()

if(GROOVE_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
