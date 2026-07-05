cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Compiler flags for optimization
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")

# 16 KB page size compatibility for Android 15+
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ARM NEON optimization for ARM architectures
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+fp+simd")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
endif()

# ============================================================================
# llama.cpp Submodule
# ============================================================================
set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

# Check if llama.cpp submodule exists
if(EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(STATUS "Found llama.cpp at ${LLAMA_CPP_DIR}")

    # llama.cpp build options
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
    set(GGML_STATIC ON CACHE BOOL "" FORCE)
    set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

    # Add llama.cpp as subdirectory
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp)

    # Include directories from llama.cpp
    set(LLAMA_INCLUDE_DIRS
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/common
    )

    set(LLAMA_AVAILABLE TRUE)
else()
    message(WARNING "llama.cpp not found at ${LLAMA_CPP_DIR}")
    message(WARNING "Run: git submodule update --init --recursive")
    set(LLAMA_AVAILABLE FALSE)
endif()

# ============================================================================
# JNI Library
# ============================================================================

# JNI source files
set(JNI_SOURCES
    ai_chat.cpp
)

# Create the shared library
add_library(ai-chat SHARED ${JNI_SOURCES})

# Include directories
target_include_directories(ai-chat PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LLAMA_INCLUDE_DIRS}
)

# Find required Android libraries
find_library(log-lib log)
find_library(android-lib android)

# Link libraries
if(LLAMA_AVAILABLE)
    target_link_libraries(ai-chat
        llama
        ggml
        ${log-lib}
        ${android-lib}
    )
else()
    target_link_libraries(ai-chat
        ${log-lib}
        ${android-lib}
    )
endif()
