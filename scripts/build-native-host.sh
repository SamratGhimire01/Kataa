#!/usr/bin/env bash
# Builds native/ (SQLCipher + FTS5 + R*Tree + sqlite-vec + JNI driver) for this machine, so JVM tests
# run the same C code the Android app ships. Output: native/build/host/libkataa_sqlite.{so,dylib}
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/fetch-native-sources.sh
cmake -S native -B native/build/host -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build native/build/host
