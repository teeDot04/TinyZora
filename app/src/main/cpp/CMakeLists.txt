cmake_minimum_required(VERSION 3.10.2)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# CORRECT PATH: From app/src/main/cpp/ go up 4 levels to repo root
add_subdirectory(../../../../llama.cpp build-llama)

add_library(ai-chat SHARED ai_chat.cpp)

target_compile_definitions(ai-chat PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
    GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(ai-chat PRIVATE
    ../../../../llama.cpp
    ../../../../llama.cpp/include
    ../../../../llama.cpp/ggml/include
    ../../../../llama.cpp/ggml/src
    ../../../../llama.cpp/vendor/nlohmann/json/single_include
)

target_link_libraries(ai-chat
    PRIVATE
    llama
    ggml
    android
    log
)
