#!/usr/bin/env bash
# Downloads the sqlite-vec loadable extension used by JVM tests (Linux x86_64 / macOS).
# The Android build compiles sqlite-vec from source instead (Phase 1b).
set -euo pipefail
VERSION="${SQLITE_VEC_VERSION:-0.1.9}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/native"
mkdir -p "$OUT"
TMP="$(mktemp -d)"
python3 -m pip download "sqlite-vec==$VERSION" --no-deps -d "$TMP" -q
python3 - "$TMP" "$OUT" <<'PY'
import sys, zipfile, glob, os
tmp, out = sys.argv[1], sys.argv[2]
whl = glob.glob(os.path.join(tmp, "*.whl"))[0]
with zipfile.ZipFile(whl) as z:
    for n in z.namelist():
        base = os.path.basename(n)
        if base.startswith("vec0."):
            with open(os.path.join(out, base), "wb") as f:
                f.write(z.read(n))
            print("extracted", base)
PY
rm -rf "$TMP"
