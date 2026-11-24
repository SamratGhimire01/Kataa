# Kataa native database: SQLCipher (SQLite 3.54 + AES-256 encryption via LibTomCrypt) with FTS5, R*Tree
# and sqlite-vec compiled in, plus a thin JNI driver. One library for Android (NDK) and for JVM tests on
# the host, so tests exercise exactly the C code that ships.
# Sources: ./scripts/fetch-native-sources.sh
cmake_minimum_required(VERSION 3.22)
project(kataa_sqlite C CXX)

set(TP ${CMAKE_CURRENT_LIST_DIR}/third_party)
if(NOT EXISTS ${TP}/sqlcipher/sqlite3.c)
  message(FATAL_ERROR "Run ./scripts/fetch-native-sources.sh first")
endif()

find_package(JNI QUIET)
if(ANDROID)
  set(JNI_INCLUDES "")
elseif(JNI_FOUND)
  set(JNI_INCLUDES ${JNI_INCLUDE_DIRS})
else()
  message(FATAL_ERROR "JNI headers not found (set JAVA_HOME)")
endif()

file(GLOB_RECURSE LTC_SOURCES ${TP}/libtomcrypt/src/*.c)

add_library(kataa_sqlite SHARED
  ${TP}/sqlcipher/sqlite3.c
  ${TP}/sqlite-vec/sqlite-vec.c
  ${LTC_SOURCES}
  kataa_jni.c
)

target_include_directories(kataa_sqlite PRIVATE
  ${TP}/sqlcipher ${TP}/sqlite-vec ${TP}/libtomcrypt/src/headers ${JNI_INCLUDES})

target_compile_definitions(kataa_sqlite PRIVATE
  # SQLCipher
  SQLITE_HAS_CODEC SQLCIPHER_CRYPTO_LIBTOMCRYPT SQLITE_TEMP_STORE=3
  SQLITE_EXTRA_INIT=sqlcipher_extra_init SQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown
  # Features Kataa uses
  SQLITE_ENABLE_FTS5 SQLITE_ENABLE_RTREE SQLITE_CORE SQLITE_VEC_STATIC
  # Smaller, faster, safer
  SQLITE_THREADSAFE=2 SQLITE_DEFAULT_WAL_SYNCHRONOUS=1 SQLITE_DEFAULT_FOREIGN_KEYS=1
  SQLITE_DQS=0 SQLITE_DEFAULT_MEMSTATUS=0 SQLITE_LIKE_DOESNT_MATCH_BLOBS SQLITE_MAX_EXPR_DEPTH=0
  SQLITE_OMIT_DEPRECATED SQLITE_OMIT_SHARED_CACHE SQLITE_OMIT_LOAD_EXTENSION SQLITE_USE_ALLOCA
  SQLITE_OMIT_PROGRESS_CALLBACK SQLITE_DEFAULT_CACHE_SIZE=-2000
  # LibTomCrypt: only what SQLCipher needs (AES, SHA1/256/512, HMAC, PBKDF2, Fortuna)
  LTC_NO_TEST LTC_NO_FILE ARGTYPE=4
)
target_compile_options(kataa_sqlite PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections
  -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function -Wno-deprecated-declarations)
target_link_options(kataa_sqlite PRIVATE -Wl,--gc-sections)

if(ANDROID)
  target_link_libraries(kataa_sqlite PRIVATE log)
  # Google Play requires 16 KB page alignment for native libraries.
  target_link_options(kataa_sqlite PRIVATE -Wl,-z,max-page-size=16384)
else()
  target_link_libraries(kataa_sqlite PRIVATE m pthread)
endif()

# The on-device language model (Kataa's "mind"): 64-bit only; 32-bit phones lack the memory and speed for it.
if(ANDROID AND (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64") AND EXISTS ${CMAKE_CURRENT_LIST_DIR}/llm/llama.cpp/CMakeLists.txt)
  enable_language(CXX)
  add_subdirectory(llm)
endif()
