cmake_minimum_required(VERSION 3.10)
project(whisper_jni)
set(CMAKE_CXX_STANDARD 17)

set(WHISPER_LIB_DIR /mnt/dev/toolchain/whisper.cpp)

# GLOBAL flags so ALL targets (whisper, ggml, ggml-cpu, ggml-base) get dotprod +
# fp16 — the quantized CPU kernels live in ggml-cpu, not ggml.
add_compile_options(-O3 -march=armv8.2-a+fp16+dotprod
    -fvisibility=hidden -ffunction-sections -fdata-sections)

set(SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/whisper_jni.c)

find_library(LOG_LIB log)
include(FetchContent)

add_library(whisper SHARED ${SOURCE_FILES})
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="1.7")
target_link_options(whisper PRIVATE -Wl,--gc-sections)

FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

target_link_libraries(whisper ${LOG_LIB} android ggml)

include_directories(
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
