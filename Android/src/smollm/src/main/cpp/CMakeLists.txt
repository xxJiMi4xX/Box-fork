cmake_minimum_required(VERSION 3.22.1)
project(box_gguf LANGUAGES C CXX)

# Single arm64 native adapter; retain original LiteRT-LM backend separately.
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# Android's cross-compilation toolchain searches the NDK sysroot for CMake
# packages. SPIRV-Headers is a header-only HOST package supplied by the Vulkan
# shader toolchain; allow its explicit config directory without altering how
# the rest of the NDK resolves target libraries.
if(ANDROID AND NOT DEFINED "SPIRV-Headers_DIR")
    if(DEFINED ENV{SPIRV_HEADERS_CMAKE_DIR} AND
       EXISTS "$ENV{SPIRV_HEADERS_CMAKE_DIR}/SPIRV-HeadersConfig.cmake")
        set("SPIRV-Headers_DIR" "$ENV{SPIRV_HEADERS_CMAKE_DIR}" CACHE PATH
            "Host SPIRV-Headers CMake package for Vulkan shader generation")
    elseif(EXISTS "/usr/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
        set("SPIRV-Headers_DIR" "/usr/share/cmake/SPIRV-Headers" CACHE PATH
            "Host SPIRV-Headers CMake package for Vulkan shader generation")
    endif()
endif()

get_filename_component(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp" ABSOLUTE)
add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build")

# Android NDK ships the Vulkan C API, but llama.cpp's Vulkan backend also
# requires Vulkan-Hpp (vulkan/vulkan.hpp). Never add /usr/include directly to
# Android compilation: that would leak host glibc headers into the NDK build.
if(ANDROID AND GGML_VULKAN)
    if(NOT TARGET ggml-vulkan)
        message(FATAL_ERROR "Vulkan backend was requested but ggml-vulkan is unavailable")
    endif()
    if(NOT DEFINED ENV{BOX_VULKAN_HEADERS_DIR} OR
       NOT EXISTS "$ENV{BOX_VULKAN_HEADERS_DIR}/vulkan/vulkan.hpp")
        message(FATAL_ERROR
            "Set BOX_VULKAN_HEADERS_DIR to an isolated Vulkan SDK headers root "
            "containing vulkan/vulkan.hpp (host Vulkan-Hpp, target-safe headers)")
    endif()
    target_include_directories(ggml-vulkan SYSTEM PRIVATE "$ENV{BOX_VULKAN_HEADERS_DIR}")
endif()


add_library(smollm SHARED LLMInference.cpp smollm.cpp)
target_compile_features(smollm PRIVATE cxx_std_17)
target_include_directories(smollm PRIVATE "${LLAMA_DIR}/include" "${LLAMA_DIR}/ggml/include")
target_link_libraries(smollm PRIVATE llama mtmd log android)
target_compile_options(smollm PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(smollm PRIVATE -Wl,--no-undefined -Wl,--gc-sections)

add_library(ggufreader SHARED GGUFReader.cpp)
target_compile_features(ggufreader PRIVATE cxx_std_17)
target_include_directories(ggufreader PRIVATE "${LLAMA_DIR}/ggml/include")
target_link_libraries(ggufreader PRIVATE ggml)
target_compile_options(ggufreader PRIVATE -O2 -fvisibility=hidden -fvisibility-inlines-hidden)
