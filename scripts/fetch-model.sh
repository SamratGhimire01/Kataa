#!/usr/bin/env bash
# Downloads bge-small-en-v1.5 (int8 ONNX, 34 MB) + vocab into models/ and verifies checksums.
# The Android app bundles the same two files (Phase 1b).
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=models/bge-small-en-v1.5
mkdir -p "$DIR"
fetch() { # url file sha256
  if [ -f "$DIR/$2" ] && echo "$3  $DIR/$2" | sha256sum -c --status; then return; fi
  curl -fsSL --retry 4 -o "$DIR/$2.part" "$1"
  echo "$3  $DIR/$2.part" | sha256sum -c --status || { echo "checksum mismatch for $2" >&2; exit 1; }
  mv "$DIR/$2.part" "$DIR/$2"
}
# Pinned to exact repo revisions so the checksums stay valid.
fetch "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/ea104dacec62c0de699686887e3f920caeb4f3e3/onnx/model_quantized.onnx" model_quantized.onnx 6c9c6101a956d62dfb5e7190c538226c0c5bb9cb27b651234b6df063ee7dbfe4
fetch "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/5c38ec7c405ec4b44b94cc5a9bb96e735b38267a/vocab.txt" vocab.txt 07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3
echo "model ready in $DIR"
