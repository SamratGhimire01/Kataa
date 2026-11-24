#!/usr/bin/env bash
# Builds Kataa's on-device language model: Qwen2.5-1.5B-Instruct (Apache-2.0) quantized from the official Q8_0
# GGUF to Q4_0, with the output layer also Q4_0 and token embeddings Q3_K (975 MB, so the arm64 APK stays under
# 1 GiB; answers on MindModelTest match the stock Q4_0 file). Q4_0 because llama.cpp repacks it for fast ARM
# dot-product kernels at load. Needs llama-quantize (scripts/fetch-llama.sh, then this script builds it).
# It is stored as mind/jni/arm64-v8a/libkataa_mind.so: native-library entries are kept uncompressed and
# 16 KB aligned in the APK, so the app memory-maps the model straight out of the APK (no copy), and only the
# arm64 APK carries it (32-bit phones can't run it).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=mind/jni/arm64-v8a
CACHE=models-cache/kataa-mind-qwen2.5-1.5b.gguf
SHA=e15d020b0cfd1f9d59e679cb9f3fa221ffad79f7b359a13146deaa8d7ddb9a13
mkdir -p "$OUT" models-cache
if [ -f "$OUT/libkataa_mind.so" ] && [ "$(sha256sum "$OUT/libkataa_mind.so" | cut -d' ' -f1)" = "$SHA" ]; then echo "model ready"; exit 0; fi
if [ ! -f "$CACHE" ]; then
  SRC=models-cache/qwen2.5-1.5b-instruct-q8_0.gguf
  [ -f "$SRC" ] || curl -fL --retry 4 -o "$SRC" "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q8_0.gguf"
  L=native/llm/llama.cpp
  [ -x $L/build-host/bin/llama-quantize ] || { cmake -S $L -B $L/build-host -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF >/dev/null; cmake --build $L/build-host --target llama-quantize; }
  $L/build-host/bin/llama-quantize --allow-requantize --output-tensor-type q4_0 --token-embedding-type q3_k "$SRC" "$CACHE.part" Q4_0 4
  mv "$CACHE.part" "$CACHE"
fi
cp "$CACHE" "$OUT/libkataa_mind.so"
echo "model: $(sha256sum "$OUT/libkataa_mind.so" | cut -d' ' -f1) (expected $SHA)"
