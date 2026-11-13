# Apple Music native playback feasibility spike (docs/applemusic.md). NOT part of the app or
# its build: nothing under ohos/ references this directory.
#
# Configure with the OHOS NDK's toolchain, then build (compile only; nothing here runs on the Mac):
#   N=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native
#   $N/build-tools/cmake/bin/cmake -S . -B <scratch>/build -G Ninja \
#     -DCMAKE_MAKE_PROGRAM=$N/build-tools/cmake/bin/ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$N/build/cmake/ohos.toolchain.cmake -DOHOS_ARCH=arm64-v8a
#   $N/build-tools/cmake/bin/cmake --build <scratch>/build
cmake_minimum_required(VERSION 3.16)
project(yoin_musickit_spike CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# The toolchain file passes --gcc-toolchain, which clang reports as unused.
add_compile_options(-Wall -Wextra -Werror -Wno-unused-parameter -Wno-unused-command-line-argument)

# libam.so: every Android-only import of the two Apple libraries (log, OpenSL ES, bionic libc bits).
# The name stays 8 characters so patch_musickit_so.py can write it over "libdl.so" in place.
# It must not pull in a C++ runtime of its own: the process then holds OHOS libc++ (std::__n1,
# Flutter's) and Apple's NDK libc++ (std::__ndk1), and the shim should add neither a third nor
# re-export the unwinder (-nostdlib++, no exceptions/RTTI, static libs' symbols kept local).
add_library(am SHARED shim/libam_shim.cpp)
target_compile_options(am PRIVATE -fvisibility=hidden -fno-exceptions -fno-rtti)
target_link_options(am PRIVATE -nostdlib++ -Wl,--exclude-libs,ALL -Wl,--no-undefined)

# The probe: dlopens the patched libraries from a directory and resolves the native entry points.
add_executable(musickit_probe probe/musickit_probe.cpp)
target_link_libraries(musickit_probe PRIVATE dl pthread)
