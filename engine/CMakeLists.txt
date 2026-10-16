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
# Every .cpp in core/src is part of the core, so new modules need no edit here.
file(GLOB GROOVE_CORE_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/core/src/*.cpp")
add_library(groovecore STATIC ${GROOVE_CORE_SOURCES})
target_include_directories(groovecore PUBLIC core/include)
target_compile_options(groovecore PRIVATE ${GROOVE_WARNINGS})
# The tape's writer, loader and exporter threads and the drum kit's render thread (M4).
find_package(Threads REQUIRED)
target_link_libraries(groovecore PUBLIC Threads::Threads)

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
