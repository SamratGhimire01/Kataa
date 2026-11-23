#!/usr/bin/env bash
# Fetches pinned C sources for Kataa's native database into native/third_party/ (gitignored):
#   SQLCipher (SQLite 3.54.1 + encryption), LibTomCrypt (SQLCipher's crypto, pure C), sqlite-vec.
# Then generates the SQLCipher amalgamation (sqlite3.c). Used by the host test build and the Android build.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=native/third_party
SQLCIPHER_TAG=v4.20.0
LIBTOMCRYPT_COMMIT=8cb39333d95766b47643d6827ec7510a5c9e3c31
SQLITE_VEC_TAG=v0.1.9
mkdir -p "$OUT"
if [ -f "$OUT/.done-$SQLCIPHER_TAG-$LIBTOMCRYPT_COMMIT-$SQLITE_VEC_TAG" ]; then echo "native sources ready"; exit 0; fi
rm -rf "$OUT"/* && mkdir -p "$OUT/sqlcipher" "$OUT/libtomcrypt" "$OUT/sqlite-vec"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

git -c advice.detachedHead=false clone -q --depth 1 --branch "$SQLCIPHER_TAG" https://github.com/sqlcipher/sqlcipher.git "$TMP/sqlcipher"
(cd "$TMP/sqlcipher" && ./configure --disable-tcl >/dev/null && make sqlite3.c >/dev/null)
cp "$TMP/sqlcipher/sqlite3.c" "$TMP/sqlcipher/sqlite3.h" "$TMP/sqlcipher/sqlite3ext.h" "$TMP/sqlcipher/LICENSE.md" "$OUT/sqlcipher/"

git init -q "$TMP/libtomcrypt" && git -C "$TMP/libtomcrypt" fetch -q --depth 1 https://github.com/libtom/libtomcrypt.git "$LIBTOMCRYPT_COMMIT"
git -C "$TMP/libtomcrypt" -c advice.detachedHead=false checkout -q FETCH_HEAD
cp -r "$TMP/libtomcrypt/src" "$TMP/libtomcrypt/LICENSE" "$OUT/libtomcrypt/"

git -c advice.detachedHead=false clone -q --depth 1 --branch "$SQLITE_VEC_TAG" https://github.com/asg017/sqlite-vec.git "$TMP/sqlite-vec"
V=$(cat "$TMP/sqlite-vec/VERSION")
sed -e "s/\${VERSION}/$V/g" -e "s/\${DATE}/pinned/g" -e "s/\${SOURCE}/$SQLITE_VEC_TAG/g" \
    -e "s/\${VERSION_MAJOR}/$(echo "$V" | cut -d. -f1)/g" -e "s/\${VERSION_MINOR}/$(echo "$V" | cut -d. -f2)/g" \
    -e "s/\${VERSION_PATCH}/$(echo "$V" | cut -d. -f3 | cut -d- -f1)/g" \
    "$TMP/sqlite-vec/sqlite-vec.h.tmpl" > "$OUT/sqlite-vec/sqlite-vec.h"
cp "$TMP/sqlite-vec/sqlite-vec.c" "$TMP/sqlite-vec/LICENSE-MIT" "$OUT/sqlite-vec/"

touch "$OUT/.done-$SQLCIPHER_TAG-$LIBTOMCRYPT_COMMIT-$SQLITE_VEC_TAG"
echo "native sources ready in $OUT"
