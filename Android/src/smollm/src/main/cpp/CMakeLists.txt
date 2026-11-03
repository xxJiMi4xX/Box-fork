cmake_minimum_required(VERSION 3.22.1)
project(box_omni_native LANGUAGES C CXX)

if(NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Box Omni JNI currently supports arm64-v8a only")
endif()

# Build upstream sources as static dependencies and expose only the Box JNI .so.
# LiteRT-LM's NPU native libraries are packaged independently by the app module.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

# First stage: safe ARM64 feature baseline. Upgrade to ARMv9/I8MM/SVE2
# only after we can inspect per-core features and compare actual throughput.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

set(LLAMA_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp")
add_subdirectory("${LLAMA_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(smollm SHARED omni_native.cpp)
target_compile_features(smollm PRIVATE cxx_std_17)
target_include_directories(smollm PRIVATE "${LLAMA_ROOT}/include")
target_compile_options(smollm PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(smollm PRIVATE llama mtmd log android)
set_target_properties(smollm PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
