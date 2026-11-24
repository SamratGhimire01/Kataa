# Kataa

Offline, voice-first memory app ("Kataa" = "where" in Nepali). Android first (Play Store), iOS later.
Plan, decisions and phases: `docs/BLUEPRINT.md`. User research: `docs/RESEARCH_forgetting_pain_points.md`.

## Layout
- `core/`: Kotlin Multiplatform shared core (database, search, categories, reminders, backup, and the rule-based
  understanding layer: `nlu/` parses what the user says, `assistant/Assistant.handle()` acts on it). `commonMain` must stay
  free of JVM/Android APIs so the iOS port is a build change. `jvmMain` has the JDBC driver used by tests.
- `native/`: Kataa's own SQLite build (SQLCipher 4.20 / SQLite 3.54 with LibTomCrypt AES-256, FTS5, R*Tree,
  sqlite-vec built in, `load_extension` compiled out) + `kataa_jni.c`. Kotlin side: `core/jvmMain/.../NativeSqlDriver.kt`.
  The same C builds for Android (NDK) and for the host, so JVM tests run the shipped code.
- `app/`: Android app (minSdk 26, targetSdk 36). `DatabaseKey` (Keystore-wrapped DB key), `CoreProvider` (opens the
  core off the main thread, memory-maps the model from the APK). `DevConsoleActivity` is a temporary test screen
  until the frontend phase.

## Commands
- `./scripts/fetch-sqlite-vec.sh` and `./scripts/fetch-model.sh` once (sqlite-vec extension; bge-small-en-v1.5 int8
  ONNX model, 34 MB). Without the model, real-model tests print SKIP and pass.
- `./scripts/build-native-host.sh` (needs cmake + ninja) builds the native database for tests. When it is built,
  tests use it; `-Pkataa.driver=jdbc` forces sqlite-jdbc instead.
- Android: SDK at `local.properties` `sdk.dir`, NDK 30.0.16248370, platform 37. `./gradlew :app:assembleRelease`.
- On-device AI ("mind", optional): `./scripts/fetch-llama.sh` (pinned llama.cpp) and `./scripts/fetch-llm-model.sh`
  (Qwen2.5-1.5B Q4_0, ~1 GB, stored as `mind/jni/arm64-v8a/libkataa_mind.so` so it is packaged uncompressed and
  page-aligned and memory-mapped from the APK). Host lib for tests:
  `cmake -S native/llm -B native/build/host-llm -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build native/build/host-llm`.
  Without them the app and tests still build; `MindModelTest` prints SKIP.
- `./gradlew :core:jvmTest` runs all tests and the 10k-memory benchmark (prints a `BENCH` line).

## Rules
- Text crosses JNI as UTF-8 byte arrays, never JNI strings (modified UTF-8 breaks emoji).
- Native libraries must stay 16 KB page aligned (Play requirement); CI checks with `zipalign -c -P 16`.
- Never pass raw user text to FTS `MATCH`; use `FtsQuery.build`.
- Schema changes go through `Migrations` with a new `PRAGMA user_version`; never edit v1 statements in place after release.
- Saving must stay instant: no ML on the save path; embedding/classification run in `KataaCore.processPending()`.
- Search thresholds are per model (`Embedder.answerThreshold`, `categoryThreshold`), tuned on `SearchEval`. Changing the
  model or search ranking: rerun `SearchQualityTest` and update the numbers. Add failing real queries to `SearchEval`.
- Rules first, AI second: `Assistant.mind` is only asked when rules are unsure (`needsMind`), and a time the rules
  read from the user's own words beats the model's (`keepUsersTime`). Never on the save path for clear sentences.
- Keep the speed budget: median hybrid search over 10k memories < 60 ms in `BenchmarkTest`.
