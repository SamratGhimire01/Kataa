# Kataa

Offline, voice-first memory app ("Kataa" = "where" in Nepali). Android first (Play Store), iOS later.
Plan, decisions and phases: `docs/BLUEPRINT.md`. User research: `docs/RESEARCH_forgetting_pain_points.md`.

## Layout
- `core/`: Kotlin Multiplatform shared core (database, search, categories, reminders, backup, and the rule-based
  understanding layer: `nlu/` parses what the user says, `assistant/Assistant.handle()` acts on it). `commonMain` must stay
  free of JVM/Android APIs so the iOS port is a build change. `jvmMain` has the JDBC driver used by tests.
- Android app and native SQLite build (SQLCipher + FTS5 + R*Tree + sqlite-vec): Phase 1b, not yet present.

## Commands
- `./scripts/fetch-sqlite-vec.sh` once (downloads the sqlite-vec extension for JVM tests).
- `./gradlew :core:jvmTest` runs all tests and the 10k-memory benchmark (prints a `BENCH` line).

## Rules
- Never pass raw user text to FTS `MATCH`; use `FtsQuery.build`.
- Schema changes go through `Migrations` with a new `PRAGMA user_version`; never edit v1 statements in place after release.
- Saving must stay instant: no ML on the save path; embedding/classification run in `KataaCore.processPending()`.
- Keep the speed budget: median hybrid search over 10k memories < 60 ms in `BenchmarkTest`.
