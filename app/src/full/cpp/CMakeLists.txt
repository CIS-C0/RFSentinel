# Whisper speech-to-text for P25 call transcripts (full flavor only, see app/build.gradle.kts).
# The FOSS flavor configures this with RFS_WHISPER=OFF and builds nothing, so its APK has no native code of ours.
cmake_minimum_required(VERSION 3.22.1)
project(rfsentinel_whisper C CXX)

option(RFS_WHISPER "Build Whisper (whisper.cpp) for call transcripts" OFF)

if (RFS_WHISPER)
    # whisper.cpp and ggml linked statically into one library; no tools, tests, examples or OpenMP runtime to ship.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "armeabi-v7a")
        add_compile_options(-mfpu=neon-vfpv4)
    endif ()
    add_subdirectory(whisper.cpp)

    add_library(rfswhisper SHARED whisper_jni.c)
    target_link_libraries(rfswhisper PRIVATE whisper log)
    target_compile_options(rfswhisper PRIVATE -O2 -fvisibility=hidden)
    target_link_options(rfswhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif ()
