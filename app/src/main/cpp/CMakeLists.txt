cmake_minimum_required(VERSION 3.22.1)
project("llama_android")

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL               OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_METAL               OFF CACHE BOOL "" FORCE)
set(GGML_CUDA                OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL              OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN              OFF CACHE BOOL "" FORCE)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -march=armv8-a+fp+simd -ffast-math -fno-finite-math-only")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8-a+fp+simd -ffast-math -fno-finite-math-only")
set(GGML_NEON ON CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_CPP_DIR} llama_build)

add_library(llama_android SHARED llama-android.cpp)

target_include_directories(llama_android PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/common
    ${LLAMA_CPP_DIR}/ggml/include
)

target_compile_options(llama_android PRIVATE
    -std=c++17
      -O3
        -march=armv8-a+fp+simd
              -ffast-math -fno-finite-math-only
                -DGGML_USE_CPU
                  -DGGML_ARM_NEON
                )
target_link_libraries(llama_android PRIVATE llama android log)
