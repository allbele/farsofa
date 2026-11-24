# SPDX-License-Identifier: AGPL-3.0-only

cmake_minimum_required(VERSION 3.22)
project(farsofa C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CHIAKI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third-party/chiaki-ng")
set(CHIAKI_ANDROID_CPP "${CHIAKI_DIR}/android/app/src/main/cpp")

# Oboe comes from the Gradle prefab package; chiaki-ng's own Android target also looks for it.
find_package(oboe REQUIRED CONFIG)
find_package(OpenXR REQUIRED CONFIG)

# Builds chiaki-lib and its dependencies. Upstream's own JNI target is configured but never built.
add_subdirectory("${CHIAKI_DIR}" chiaki-ng EXCLUDE_FROM_ALL)

# core/ is the only code that talks to libchiaki. The audio decoder and the log helper are compiled
# straight from the chiaki-ng tree; the video decoder and the audio output are our own, behind the
# same headers.
add_library(farsofa SHARED
        src/main/cpp/core/bridge.cpp
        src/main/cpp/xr/xr_screen.cpp
        src/main/cpp/xr/smooth_video.cpp
        src/main/cpp/video/video_decoder.cpp
        "${CHIAKI_ANDROID_CPP}/audio-decoder.c"
        src/main/cpp/audio/audio_output.cpp
        "${CHIAKI_ANDROID_CPP}/log.c")

target_include_directories(farsofa PRIVATE "${CHIAKI_ANDROID_CPP}")
target_link_libraries(farsofa chiaki-lib oboe::oboe OpenXR::openxr_loader EGL GLESv3 log mediandk android)
