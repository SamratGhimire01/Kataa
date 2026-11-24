#!/usr/bin/env bash
# Kataa's own offline speech recognizer: whisper.cpp (MIT) pinned, plus the base.en model (MIT, q5_1, ~57 MB)
# stored as ear/jni/arm64-v8a/libkataa_ear.so so only the arm64 APK carries it, uncompressed and page aligned, and
# the app memory-maps it from the APK. Without them the app uses Android's recognizer only.
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=v1.9.6
if [ ! -d native/whisper/whisper.cpp ]; then
  git clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/whisper.cpp native/whisper/whisper.cpp
fi
mkdir -p ear/jni/arm64-v8a
M=ear/jni/arm64-v8a/libkataa_ear.so
[ -f "$M" ] || { curl -fL --retry 4 -o "$M.part" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin; mv "$M.part" "$M"; }
echo "whisper ready"
