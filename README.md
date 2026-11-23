# Kataa

**Kataa** (Nepali for "where") is an offline, voice-first memory companion: say "my keys are in the blue drawer",
later ask "where are my keys?", and get reminded to buy milk when you walk into any Big Mart.
Everything stays on the phone.

Status: **Phase 1 — shared storage core** (this repo). The Android app, voice, and UI come in later phases;
see [docs/BLUEPRINT.md](docs/BLUEPRINT.md).

## What the core does today
- Save, edit, trash/restore, permanently delete memories; "latest wins" history per subject (keys moved → newest answer).
- Hybrid search: keywords (SQLite FTS5, BM25, porter stemming) + meaning (sqlite-vec cosine), fused with
  Reciprocal Rank Fusion, recency and pin boosts, and an honest "I don't know".
- Categories: 11 starters (incl. a fingerprint-locked Private), automatic sorting from embeddings + keyword rules,
  user moves/rejections are respected and teach the classifier, user-made categories work immediately.
- Places and reminders: time, arrive/leave a place, any branch of a chain ("Big Mart"), any place of a type,
  Bluetooth (car/earbuds), "nag until done"; geofence planner that fits 150+ branches into Android's 100-fence limit.
- Portable JSON backup/import (merge by id, idempotent).

## Run the tests
```bash
./scripts/fetch-sqlite-vec.sh   # once
./gradlew :core:jvmTest
```
The benchmark test inserts 10,000 memories with 384-d vectors and prints search latency.
