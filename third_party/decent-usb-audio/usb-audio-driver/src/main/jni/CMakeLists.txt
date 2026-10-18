cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# ── libFLAC ── LaHo: xiph/flac comes from the third_party/flac git submodule
# (tag 1.5.0); the JNI FLAC parser lives in ../flac-jni.
set(FLAC_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../flac")
set(FLAC_MODULE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../flac-jni")
if(NOT EXISTS "${FLAC_SRC_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "libFLAC sources missing: run 'git submodule update --init third_party/flac'")
endif()
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
add_subdirectory("${FLAC_SRC_DIR}"
                 "${CMAKE_CURRENT_BINARY_DIR}/libflac"
                 EXCLUDE_FROM_ALL)

# LaHo: on 32-bit Android (armv7/x86) libFLAC's CMake forces HAVE_FSEEKO=0 (it sees a
# system version < 24 inside its own project), so compat.h #defines fseeko -> fseek, which
# clashes with bionic's fseeko declaration under _FILE_OFFSET_BITS=64. fseeko/ftello exist
# since API 24 and this module's minSdk is 29, so declare them available for the target.
if(ANDROID)
    target_compile_definitions(FLAC PRIVATE HAVE_FSEEKO=1)
endif()

# ── Main library: USB audio driver + native audio engine ──
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
    native-audio-engine.cpp
    "${FLAC_MODULE_DIR}/flac_parser.cc"
)

target_include_directories(decent_usb_audio PRIVATE
    "${FLAC_MODULE_DIR}/include"
    "${FLAC_SRC_DIR}/include"
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio
    ${log-lib}
    FLAC
)
