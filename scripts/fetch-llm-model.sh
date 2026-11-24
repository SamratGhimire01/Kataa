#!/usr/bin/env bash
# Downloads Kataa's on-device language model (Qwen2.5-1.5B-Instruct, Q4_0 GGUF, Apache-2.0, ~1 GB).
# Q4_0 because llama.cpp repacks it for fast ARM dot-product kernels at load.
# It is stored as mind/jni/arm64-v8a/libkataa_mind.so: native-library entries are kept uncompressed and
# 16 KB aligned in the APK, so the app memory-maps the model straight out of the APK (no copy), and only the
# arm64 APK carries it (32-bit phones can't run it).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=mind/jni/arm64-v8a
FILE=qwen2.5-1.5b-instruct-q4_0.gguf
mkdir -p "$OUT"
if [ -f "$OUT/libkataa_mind.so" ]; then echo "model ready"; exit 0; fi
if [ -f models-cache/$FILE ]; then cp models-cache/$FILE "$OUT/libkataa_mind.so"; exit 0; fi
curl -fL --retry 4 -o "$OUT/libkataa_mind.so.part" "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/$FILE"
mv "$OUT/libkataa_mind.so.part" "$OUT/libkataa_mind.so"
