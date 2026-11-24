#!/usr/bin/env bash
# Fetches pinned llama.cpp into native/llm/llama.cpp (gitignored) for Kataa's on-device language model.
set -euo pipefail
cd "$(dirname "$0")/.."
TAG=b12500
DIR=native/llm/llama.cpp
if [ -f "$DIR/.kataa-$TAG" ]; then echo "llama.cpp $TAG ready"; exit 0; fi
rm -rf "$DIR"
git -c advice.detachedHead=false clone -q --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$DIR"
touch "$DIR/.kataa-$TAG"
