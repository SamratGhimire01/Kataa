# Kataa's on-device language model (the "mind"): llama.cpp + a small JNI wrapper, one shared library.
# Built for the host (JVM tests run the real model) and for arm64-v8a / x86_64 on Android.
# Sources: ./scripts/fetch-llama.sh (pinned llama.cpp into native/llm/llama.cpp, gitignored).
cmake_minimum_required(VERSION 3.22)
project(kataa_llm C CXX)

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
  message(FATAL_ERROR "Run ./scripts/fetch-llama.sh first")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
if(ANDROID)
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot-product instructions (every 64-bit phone core since ~2018: Cortex-A55/A75 and newer).
    # The app checks the CPU for them before loading this library, so older phones just skip the mind.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
  endif()
else()
  set(GGML_NATIVE ON CACHE BOOL "" FORCE)
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${LLAMA_DIR} llama EXCLUDE_FROM_ALL)

find_package(JNI QUIET)
add_library(kataa_llm SHARED kataa_llm.cpp)
target_link_libraries(kataa_llm PRIVATE llama)
# Kataa's offline speech recognizer (whisper.cpp, ./scripts/fetch-whisper.sh) lives in the same library and
# uses the same ggml as the mind: one copy, no clashing symbols.
set(WHISPER_DIR ${CMAKE_CURRENT_LIST_DIR}/../whisper/whisper.cpp)
if(EXISTS ${WHISPER_DIR}/src/whisper.cpp)
  target_sources(kataa_llm PRIVATE ${WHISPER_DIR}/src/whisper.cpp ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_CURRENT_LIST_DIR}/../whisper/kataa_whisper.cpp)
  target_include_directories(kataa_llm PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/src)
  target_compile_definitions(kataa_llm PRIVATE WHISPER_VERSION="1.9.6")
endif()
target_compile_features(kataa_llm PRIVATE cxx_std_17)
target_compile_options(kataa_llm PRIVATE -O2 -fvisibility=hidden)
if(ANDROID)
  target_link_libraries(kataa_llm PRIVATE log)
  # Google Play requires 16 KB page alignment for native libraries.
  target_link_options(kataa_llm PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
  if(NOT JNI_FOUND)
    message(FATAL_ERROR "JNI headers not found (set JAVA_HOME)")
  endif()
  target_include_directories(kataa_llm PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_libraries(kataa_llm PRIVATE pthread)
endif()
