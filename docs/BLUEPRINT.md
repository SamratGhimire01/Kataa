# Second Brain ("Kai"): Revised Blueprint v2

Reviewed 23 Nov 2026, updated the same day with Samrat's answers: **Android only, English only**, a hardware-key shortcut, a big tap-to-talk orb, and auto categories (see §10–§12). This replaces the v1 spec's architecture, schema and phase plan. The visual design language in v1 (AMOLED black, molten orange orb, emerald success glass, Kai) is kept as the target for the frontend phase, with the corrections in §8.

Companion files in this folder:
- `schema_v1.sql`: corrected schema (tested)
- `hybrid_search.sql`: hybrid retrieval query with rank fusion (tested)
- `hybrid_search_in_category.sql`: the same query limited to one category (tested)
- `nearest_place_targets.sql`: picks which stores/places to watch right now (tested with 150 Big Mart branches)
- `test_schema.py`: test that runs both against SQLite 3.53 + sqlite-vec 0.1.9 and reproduces the v1 bugs (`pip install sqlite-vec && python3 test_schema.py`)

---

## 1. Verdict in one paragraph

The idea is good and buildable on today's Android phones. The storage and search core is the safest part. The three things that would have hurt most are: (1) the v1 SQL silently breaks search the first time a memory is edited or deleted, (2) the always-on "Hey Remember" wake word is not viable as specced (Picovoice's free tier ended on 30 June 2026, and Android 14+ will not let an app start background mic capture on its own), and (3) the spec promises answers like "IN THE BLUE DRAWER", but nothing in the stack produces a short answer, it only finds the memory. All three have good fixes below. The 300 ms target is realistic for search, but not for the full voice round trip, so the budget is restated in §5.

---

## 2. Biggest flaws found in v1 (ranked)

| # | Flaw | Impact | Fix |
|---|------|--------|-----|
| 1 | **No FTS5 UPDATE trigger.** Verified: after editing "keys on counter" to "keys in drawer", searching "drawer" finds nothing, "counter" still finds it, and FTS5 `integrity-check` reports a checksum mismatch (index corrupt). | Wrong search results after any edit; "actually it's in the drawer" corrections, the most common voice action, break search. | Update trigger that deletes the old indexed row and inserts the new one (§4). |
| 2 | **Soft delete never reaches FTS or vectors.** `is_deleted = 1` is an UPDATE, so the deleted text stays searchable (verified) and the vector stays in `memories_vec`. Every query would need a filter, and a FTS `rebuild` would re-index trash. | Deleted memories show up in answers. Privacy bug. | Index only live rows: FTS content is a view of live rows, triggers remove/add on trash/restore, vector deleted on trash and re-embedded on restore. |
| 3 | **FTS keyed on an implicit rowid.** `memories.id` is `TEXT PRIMARY KEY`, so FTS uses the hidden rowid, which SQLite may renumber on `VACUUM`. | FTS points at the wrong memories after a VACUUM. | `id INTEGER PRIMARY KEY` (stable rowid alias) + separate `uuid TEXT UNIQUE` for export. The same integer joins FTS, vectors, reminders and attachments. |
| 4 | **Raw speech used as an FTS query.** `where's my keys?` throws `fts5: syntax error` (verified). Words like AND/OR/NOT/NEAR and quotes also break it. | Search crashes on ordinary questions. | Tokenize in app code, drop stop words, quote each token, join with OR. |
| 5 | **BM25 and cosine can't be added together.** They live on different scales (BM25 is unbounded and negative in FTS5). | Hybrid ranking would be arbitrary. | Reciprocal Rank Fusion (`hybrid_search.sql`), then recency and supersession boosts in app code. |
| 6 | **No answer extraction.** Retrieval returns a memory, not "IN THE BLUE DRAWER". | The headline success screen can't be built as specced. | Phase-1: show the matched memory with the location phrase highlighted (rule-based). Optional on-device LLM for phrasing on capable phones (§6). |
| 7 | **No notion of "latest wins".** "Keys on counter" (Mon) and "keys in drawer" (Wed) both match "where are my keys". | Confidently wrong answers. | `subject` + `supersedes_id` columns and a recency boost; OBJECT_LOCATION memories answer with the newest one and show "previously: counter". |
| 8 | **Wake word.** Porcupine free-tier keys were disabled on 30 Jun 2026 and the SDK "does not run without a valid key"; keys are validated at engine init. Android 14+ forbids starting a microphone foreground service from the background or from BOOT_COMPLETED. Always-on mic also shows a permanent green privacy dot and drains battery. | Feature as specced can't ship, and it contradicts "zero network". | Default trigger = set Kai as the phone's **default digital assistant** (long-press power / corner swipe launches it with mic allowed), plus Quick Settings tile, home widget, and lock-screen shortcut. Wake word becomes an opt-in, "while Kai is open or charging" mode using open-source keyword spotting (sherpa-onnx KWS). |
| 9 | **Embedding dimension mismatch.** EmbeddingGemma outputs 768 (truncatable to 512/256/128), not 384; "EmbeddingGemma 2" could not be confirmed to exist. Nothing records which model made a vector. | Swapping models silently mixes incompatible vectors. | Store `embed_model` per row and `embed_dim` in `meta`; the app creates `memories_vec` from the active model; model change triggers a background re-embed. |
| 10 | **"WHATSAPP" as a source type.** Apps can't read WhatsApp; content only arrives through the share sheet, and the sending app isn't reliably identified. Shared image URIs are temporary grants. | Misleading data model; lost images. | `source = 'SHARE'` + best-effort `source_app`; copy shared files into app storage immediately. |
| 11 | **Time and place reminders have no parser.** "Remind me when I get to the pharmacy" needs a place, which can't be geocoded offline. | Reminders feature undefined. | Rule-based date/time grammar + user-saved places ("home", "office", "here"). Unknown place → Kai asks "where is that? Drop a pin or say 'here' when you're there." |
| 12 | **Exact-time reminders on WorkManager.** WorkManager is for deferrable work; it can be hours late in Doze. | Late reminders. | AlarmManager exact alarms (`SCHEDULE_EXACT_ALARM`, denied by default on Android 14+ so ask in context; fall back to inexact windows), reschedule on boot / time change / time-zone change. |
| 13 | **Cloud backup leak + no export.** Android Auto Backup uploads app data to Google Drive by default, which breaks "100% on-device"; and with no sync, a lost phone loses every memory. | Privacy promise broken, or total data loss. | Opt out of Auto Backup (`allowBackup=false` / `dataExtractionRules`), ship encrypted export/import to a user-chosen location from day one. |
| 14 | **SF Pro font.** Apple licenses SF Pro only for Apple platforms. | Can't ship in an Android app. | Inter (OFL) or similar. |
| 15 | **GeofenceProvider** isn't an Android API; geofences come from Play services `GeofencingClient`. | Naming only, but limits matter. | 100 geofences per app, 100–150 m minimum radius, 2–3 min typical latency (up to 6 when stationary), needs `ACCESS_BACKGROUND_LOCATION` and a Play Console declaration. |

Not a flaw after testing: sqlite-vec 0.1.9 does accept `TEXT PRIMARY KEY` on vec0. The v1 delete trigger was correct for hard deletes; the problems are the missing update path and soft delete.

---

## 3. Feasibility verdict per component

Legend: ✅ feasible now · 🟡 feasible with changes · ⛔ drop or defer

| Component | Verdict | Notes |
|---|---|---|
| SQLite + FTS5 | ✅ | Must bundle our own SQLite (Android's system SQLite can't load extensions and its FTS5 support varies by OEM). Flutter: `sqlite3` + `sqlite3_flutter_libs`/SQLCipher build. Kotlin: requery `sqlite-android` or a custom NDK build. |
| sqlite-vec | ✅ | Brute-force KNN, which is fine: 10k memories × 384 floats ≈ 15 MB scanned in a few ms on NEON. Still v0.1.x (pre-1.0), so pin the version. Compile it statically into the bundled SQLite. At >100k memories consider int8/bit quantization. SQLite's own `vec1` (v0.8, IVF ANN, needs training) is worth watching but is less suited to a small, constantly changing table. |
| Hybrid search | ✅ | RRF query provided and tested. |
| Encryption at rest | ✅ | SQLCipher (works with FTS5 and a statically linked sqlite-vec). Random 256-bit DB key wrapped by an Android Keystore key. Attachments encrypted with Tink (AES-GCM streaming). Note `androidx.security-crypto` is deprecated; use Tink directly. Add biometric app lock as an option. Decide this in Phase 1: migrating a plain DB to SQLCipher later is painful. |
| Embeddings (ONNX Runtime Mobile) | ✅ | **Decided (English only): bge-small-en-v1.5** (MIT, 384-d, ~33M params, ≈35 MB int8) as default, **all-MiniLM-L6-v2** (Apache-2.0, 384-d, ≈23 MB int8) as the low-end fallback. Both are 384-d, so the schema is unchanged. EmbeddingGemma (308M, 768-d) stays a later upgrade option if the eval shows a real gain. Run a 50–100 query personal eval before locking it in. |
| Speech-to-text | 🟡 | whisper.cpp works but is batch: base/small models take ~1–3 s for a 5 s clip on a mid-range phone, and small is ~460 MB. Better fit: **sherpa-onnx** (Apache-2.0) with Silero VAD + streaming Zipformer (live partial text while speaking) or Moonshine/Whisper-tiny for short clips. One library also gives keyword spotting and offline TTS. Android's built-in on-device recognizer (API 31+) is a free fallback where present. English only means the smaller English-specific models (Moonshine, Whisper base.en, English streaming Zipformer) are usable, which helps speed and size. |
| Wake word | ⛔ as specced / 🟡 redesigned | See flaw #8. Default-assistant role + tile + widget covers the zero-friction goal without background mic. |
| Intent routing (log vs search vs remind) | ✅ | Layered: (1) rules on cue words ("remember", "remind me", "where", "what", "when did I", "?" intonation via ASR punctuation); (2) a tiny classifier (logistic regression) on the sentence embedding we already compute, so ~0 ms extra; (3) if confidence is low, Kai asks "Save or search?" with two big buttons. Log every correction to retrain locally. |
| Time/place parsing | 🟡 | Rule-based grammar for English (today/tomorrow/tonight/next Monday/in 2 hours/at 5/every day). ML Kit Entity Extraction is an on-device option for date-times, but downloads its model once over the network, so treat it as optional. Places = saved labels only. |
| Time reminders | ✅ | AlarmManager exact alarms + `POST_NOTIFICATIONS` (Android 13+) + reschedule on boot. |
| Geofence reminders | 🟡 → ✅ | Works within the limits in flaw #15, including "any Big Mart" chain reminders via the rolling-geofence design in §13. Requires Google Play services and "Allow all the time" location, which needs a clear in-app explanation and a Play declaration. |
| Share-sheet ingestion | ✅ | `ACTION_SEND`/`SEND_MULTIPLE` for `text/plain` and `image/*`; copy bytes immediately; dedupe by SHA-256. |
| Screenshot OCR | ✅ | ML Kit Text Recognition v2, bundled model (fully offline; Latin and Devanagari scripts available). Ingest via share sheet or the system photo picker. Auto-watching the screenshots folder needs broad photo permission, which Play's photo/video policy restricts: ⛔ for v1. |
| Answer phrasing ("IN THE BLUE DRAWER") | 🟡 | Rule-based extraction for OBJECT_LOCATION memories ("in/on/under/behind/at the …") covers the core use case. Optional on-device LLM tier described in §10. |
| Categories | ✅ | Auto-assigned with the embedding we already compute, editable, filterable. Design in §12, schema tested. |
| Hardware-key shortcut | 🟡 | Power-key long-press via the default-assistant role is fully supported. Volume-key combos need an Accessibility Service, which Play restricts for assistants. Details in §11. |
| Backup/export without sync | ✅ | Encrypted `.kai` archive (SQLite snapshot via backup API + attachments + manifest with model id). Import merges by `uuid`. Scheduled export to a user-picked folder (which may itself be a cloud folder, user's choice). |
| 300 ms target | 🟡 | Realistic for text-in → result (see §5). Not for speech → result. |

---

## 4. Corrected schema (summary)

Full DDL: `schema_v1.sql`. Key decisions:

- `memories.id INTEGER PRIMARY KEY` is the single join key for FTS, vectors, reminders and attachments. `uuid` is the portable id.
- FTS5 external-content table over a **view of live rows** (`memories_live`), so `rebuild` and `integrity-check` match the soft-delete rule. Triggers keep it in sync on insert, update (text or trash/restore) and hard delete. Tokenizer `unicode61 remove_diacritics 2` (handles accents and Devanagari word boundaries reasonably).
- `memories_vec` is `vec0(memory_id INTEGER PRIMARY KEY, embedding float[N] distance_metric=cosine)`. N comes from `meta.embed_dim` and is created by app code. Vectors are written by the app in the same transaction as the memory (or by the background embed worker); triggers delete stale vectors on edit/trash/hard delete and set `embed_model = NULL` so the worker re-embeds.
- New columns: `kind` (NOTE / OBJECT_LOCATION / REMINDER / TASK / FACT), `subject` + `supersedes_id` (latest-wins answers), `raw_transcript` vs cleaned `body`, `extracted_text` (OCR/shared text, also full-text indexed), `source_app`, `lang`, capture location, `pinned`, `deleted_at` (timestamp instead of a flag, so the trash can auto-purge after 30 days).
- `attachments` table instead of one `media_path` (a memory can have a photo and audio).
- `places` (user-labeled, radius ≥ 100 m) and `reminders` (TIME / PLACE_ENTER / PLACE_EXIT, IANA time zone, optional RRULE, status lifecycle, OS request id). Trashing a memory cancels its reminders.
- `meta` table for schema version and active embedding model. Migrations run with `PRAGMA user_version` in a transaction.

Test coverage in `test_schema.py`: insert, edit, trash, restore, hard delete of live and trashed rows, FTS `integrity-check` after each step, vector cleanup, reminder cancellation, hybrid query ranking, VACUUM.

---

## 5. Latency budget (replaces "sub-300 ms")

Measured on target hardware in Phase 2–3; these are planning numbers for a 2023-era mid-range phone (Snapdragon 7-class), models already loaded.

| Step | Budget |
|---|---|
| End-of-speech detection (VAD hangover) | 300–500 ms |
| Final ASR pass on a short utterance (streaming model) | 100–400 ms |
| Intent rules + classifier | < 5 ms |
| Query embedding (384-d, int8) | 15–40 ms |
| FTS5 + vec0 KNN + fusion (10k memories) | 5–20 ms |
| Answer extraction (rules) | < 5 ms |
| **Text → result** | **≈ 30–70 ms ✅ (well under 300 ms)** |
| **Speech end → result on screen** | **≈ 0.5–1 s** |

Design rules that make it feel instant: show live partial transcript while the user speaks, warm-load models when the orb opens (cold ONNX session load is 100–500 ms), save first and embed in the background (the memory is searchable by keyword immediately and semantically a moment later), and start the success animation the moment intent is known.

---

## 6. Features: what would make it best-in-class

### Tier 1: build into the core (feasible, high value)
1. **Latest-wins answers with history.** "Where are my keys?" → "Blue drawer (Wed 6 pm). Before that: kitchen counter."
2. **Voice correction and undo.** "Actually, it's in the car" updates the last memory; "undo" / "forget that" trashes it. One-tap undo on the success card.
3. **Honest "I don't know".** Similarity threshold below which Kai says it has nothing and offers to save instead. Prevents wrong confident answers.
4. **Read-back confirmation** with offline Android TextToSpeech (optional, for hands-busy moments) plus haptics.
5. **System-level entry points**: default assistant role, Quick Settings tile, home widget, app shortcut, share sheet.
6. **Time reminders from speech** ("remind me tomorrow at 9 to call mom") with natural-language snooze.
7. **Photo + location memories**: "remember where I parked" auto-attaches GPS and offers a photo; recall shows a map pin offline (no tiles needed, just distance and direction).
8. **Screenshot / shared-text OCR** so a shared WhatsApp image of a Wi-Fi password is searchable by its text.
9. **Time-scoped search**: "what did I save last week about the bank?" (time filter parsed from the query).
10. **Trash with 30-day restore, encrypted export/import, biometric lock.**

### Tier 2: next (feasible, more work)
11. **Place reminders** using saved places and "here".
12. **People and things**: lightweight entity extraction (capitalized names, saved contacts if the user allows) so "what do I know about Ramesh?" groups memories.
13. **Resurfacing**: a quiet daily digest of pinned or soon-relevant memories, and "on this day".
14. **Recurring reminders and checklists** ("remember to buy milk, eggs, bread" becomes a ticking list).
15. **Personal intent model**: corrections retrain the local classifier so routing gets better for this user.

### Tier 3: optional / device-dependent
16. **LLM-phrased answers and summaries** (Gemini Nano on supported phones, Gemma on 8 GB+ phones). Must degrade gracefully to Tier 1 answers.
17. **Opt-in wake word** while the app is open or charging (sherpa-onnx KWS).
18. **Image semantic search** ("the photo of the receipt") with a mobile CLIP/SigLIP model: feasible but adds ~100–300 MB.
19. **Wear OS tile** for capture from the wrist.

### Not feasible or not worth it now
- Reading WhatsApp or other apps' chats automatically (no API; notification-listener scraping is a privacy and Play-policy risk).
- Always-on ambient listening/recording.
- Offline geocoding of arbitrary places ("the pharmacy on 5th street").
- Auto-importing every screenshot (photo-permission policy).
- Identical feature set on iOS (no default-assistant replacement, no background mic, different geofence and widget rules) if iOS is ever a goal.

---

## 7. Phased backend plan (frontend waits until Phase 6 passes)

Each phase ends with automated tests and a short device benchmark. A plain debug screen (text box, buttons, raw results) is used to exercise the backend; no design work.

**Phase 0: Decisions & skeleton (1–2 days)**
Resolve §9. Create the repo, CI, module layout (`core-db`, `core-ml`, `core-speech`, `core-intent`, `core-triggers`, `core-ingest`, `app`). Pick 2 test devices (one mid-range, one low-end).

**Phase 1: Storage & hybrid search (≈1 week)**
Includes the categories tables and category-filtered search. Bundled SQLCipher + statically linked sqlite-vec + FTS5. Schema v1, migrations, Keystore-wrapped key. Repository API: `save`, `update`, `trash`, `restore`, `purge`, `search(text, filters)`, `latestFor(subject)`. FTS query builder (token quoting, stop words). RRF + recency + supersession ranking. Encrypted export/import. Auto Backup disabled. Tests: everything in `test_schema.py`, ported, plus export round-trip and 10k-row benchmark.

**Phase 2: Embeddings (≈1 week)**
ONNX Runtime Mobile, tokenizer, chosen model, warm-up, background embed worker (picks rows with `embed_model IS NULL`), re-embed on model change. Build a 50–100 query personal eval set; report recall@3 for FTS-only, vector-only, hybrid.

**Phase 3: Speech (≈1–2 weeks)**
Audio capture, Silero VAD, streaming ASR via sherpa-onnx (or chosen engine), partial results stream, amplitude stream for the future orb. Measure the §5 budget on both devices.

**Phase 4: Understanding (≈1–2 weeks)**
Intent layers (rules → embedding classifier → ask), category auto-assignment (rules + embedding prototypes, learning from corrections), date/time grammar, object-location extraction ("in/on/under the …"), subject normalization for latest-wins, correction/undo commands, "I don't know" threshold tuned on the eval set.

**Phase 4b: Optional LLM brain (≈1 week, after Phase 4 works without it)**
`Brain` interface with `RuleBrain` (always) and `LlmBrain` (Gemini Nano where AICore has it, else downloadable Gemma 3 1B int4). Used for answer phrasing, hard intents and category second opinions, with a strict JSON output schema and a timeout that falls back to rules. Measure on both test phones.

**Phase 5: Triggers & ingestion (≈2–3 weeks)**
Exact alarms + notifications + reboot rescheduling; saved places + offline store pack + rolling geofences with dwell (§13); Bluetooth moments; document date extraction (§14); share-sheet receiver; ML Kit OCR; default-assistant role (`VoiceInteractionService`/`ROLE_ASSISTANT`, launchable over the lock screen in capture-only mode), Quick Settings tile, widget, in-app hold-volume-to-talk, and the optional volume-combo Accessibility Service in a sideload build flavor (§11).

**Phase 6: Hardening (≈1 week)**
Battery check (no background wakeups except alarms/geofences), crash-safe writes, trash auto-purge, large-DB tests, privacy review (no network permission at all in the release manifest if feasible; model files bundled or sideloaded).

**Phase 7+: Frontend** (orb shader, Kai state machine, glass cards, motion) on top of a stable state stream: `Idle → Listening(partial, amplitude) → Processing(intent) → Saved(memory) | Answer(answer, sources) | NotFound | Error`.

---

## 8. Notes for the frontend phase (kept short, not started)

- The state stream above is the contract between backend and UI, so the UI can be iterated without touching the backend.
- Kai as a **Rive** state machine (inputs map 1:1 to the states) works on both Flutter and native Android; Lottie lacks state machines.
- Orb: Flutter `FragmentProgram` shaders (all Android versions) or Compose `RuntimeShader`/AGSL (Android 13+ only; needs a fallback for older phones).
- Pure black + #00FF88 works on OLED, but keep body text off pure white to reduce halation, and respect "reduce motion" and TalkBack from the start.
- Font: Inter (OFL), not SF Pro.

---

## 9. Decisions

Made (23 Nov 2026): **English only. Android first, Play Store-ready, easy to port to iOS later, must run smoothly on low-RAM phones (3 GB, Android 10+).** Stack: **Kotlin Multiplatform + Compose Multiplatform**: shared core (database, search, categories, intent, reminder logic, UI) with platform modules for assistant role, alarms, geofences, share sheet and Keystore. Repo: `kai-memory`. (Supersedes the Kotlin-vs-Flutter question below.) **Default-assistant power-key launch + big in-app orb** as the main triggers, no always-on wake word in v1. **Categories** in v1.

Still open:
1. **Kotlin or Flutter?** Recommendation: Kotlin + Compose (Android-only, and the hard parts are native Android anyway).
2. **Minimum Android version.** Recommendation: Android 10 (API 29), testing on 14–16.
3. **Optional LLM tier.** Recommendation: build v1 so it fully works without an LLM, then add Gemini Nano (no download, supported phones) and an optional Gemma 3 1B download (~530 MB) for other phones with 8 GB+ RAM.
4. **Distribution.** Google Play or personal sideload first? This decides whether the volume-key combo ships (§11).

---

## 10. The "brain": is an LLM needed?

Short answer: **no LLM is needed for the app to work well, but a small optional one makes it nicer.** Kai's "brain" is really four jobs, and only one of them clearly benefits from an LLM:

| Job | Without LLM (v1, always on) | What an LLM adds |
|---|---|---|
| Understand what you want (save / search / remind / correct / delete) | Cue-word rules + a tiny classifier on the sentence embedding, ~1 ms. Asks "Save or search?" when unsure. Handles 95%+ of normal phrasing. | Better at odd phrasing ("ugh I always lose the remote, it's behind the TV now"). |
| Find the right memory | Hybrid search (keywords + meaning) + "latest wins". This is the real memory, and an LLM does not replace it. | Nothing essential. |
| Answer in short words ("IN THE BLUE DRAWER") | Pattern extraction ("in/on/under/behind/next to the …") for where-is questions; otherwise shows the matched memory with the key words highlighted. | Natural one-line answers for any question, and summaries ("what did I save about the bank this month?"). |
| Pick categories | Embedding similarity to each category + keyword rules, learning from your corrections. | Second opinion on unclear items. |

Why not make the LLM required:
- **Size and speed.** Gemma 3 1B int4 is ~530 MB and uses ~1 GB RAM; Google's published numbers on a Galaxy S24 Ultra are ~55 tokens/s output on CPU. Mid-range phones are much slower, so a one-line answer takes about 1–2 s on top of everything else. Rules take milliseconds.
- **Gemini Nano** (ML Kit GenAI Prompt API) costs no download and is fast, but only runs on supported phones (Pixel 9/10/11, Galaxy S25/S26, recent OnePlus, Xiaomi, vivo and others), is still Beta, has per-app quotas, and **only runs while the app is in the foreground**. That's fine for answering inside Kai, not for background work.
- **Wrong-answer risk.** A small model can invent a location. Kai must always show the source memory under any LLM answer, and fall back to rules on timeout or invalid output.

Design: a `Brain` interface with two implementations. `RuleBrain` always runs first and returns instantly. `LlmBrain`, if available, can refine the answer text a moment later (the UI shows the rule answer, then smoothly swaps in the nicer sentence). The LLM only ever sees the user's question and the top 3–5 retrieved memories, and must reply in a fixed JSON shape.

---

## 11. Shortcuts: keys, gestures, orb

What Android actually allows a third-party app, from best to worst:

| Trigger | Works? | Notes |
|---|---|---|
| **Press and hold power (side) key** | ✅ Best option | Kai registers as the phone's **default digital assistant** (`VoiceInteractionService` + assistant role). User picks Kai once in Settings → Default apps → Digital assistant, and sets the power key to "Digital assistant" (Pixel, Samsung and most 2022+ phones). Opens instantly with the mic, even from the lock screen. This replaces Google Assistant/Gemini on that gesture. |
| Corner swipe / home long-press | ✅ | Same assistant role, no extra work. |
| **Big orb in the app** | ✅ | Tap to talk, or hold to talk and release to send. |
| **Volume keys while Kai is open** | ✅ | e.g. hold volume-up to talk, like a walkie-talkie. Simple and allowed. |
| Samsung side key double-press, Pixel Quick Tap (double-tap the back) | ✅ | OS settings that can open any app; Kai's onboarding links to them. |
| Quick Settings tile, home-screen widget, app-icon long-press shortcut | ✅ | Always available. |
| **Volume combo from anywhere** (e.g. volume-up + volume-down) | 🟡 | Only possible through an **Accessibility Service** that watches key presses, or the system "hold both volume keys for 3 s" accessibility shortcut, which also needs one. Works for a personal/sideloaded build, but Google Play's policy says voice assistants are not accessibility tools, so it would need a separate disclosure, a Play Console declaration with a video, and could still be rejected. Doesn't work with the screen off. Android 13+ also blocks enabling it for sideloaded apps until the user allows "restricted settings". |
| Power-key combos (power + volume, double-press power) | ⛔ | Reserved by the system (screenshot, camera, emergency). Apps can't capture them. |

Recommendation: make **power-key long-press (assistant role)** and the **orb** the primary paths. Build the volume combo as an optional extra in a "sideload" build flavor, and only try it on Play after launch.

Lock screen privacy: when opened over the lock screen, Kai can **save** but must not **reveal** memories until the phone is unlocked (or show only non-private categories, user's choice).

---

## 12. Categories

Goal: you never have to file anything, but you can browse "Education", "Work", etc. and fix anything Kai got wrong.

**How it works**
1. **Starter set** (renamable, recolorable, can be archived): Where things are, Education, Work, Health, Money, Shopping, People, Ideas, Travel, Home, and **Private** (passwords, PINs, codes: hidden behind fingerprint, never shown on the lock screen).
2. **Auto-assign on save, in the background, ~1 ms.** Each category has a short description that is embedded once to make a "prototype" vector. A new memory's embedding (already computed for search) is compared with every prototype; it gets the best category if the match is strong enough and clearly ahead of the runner-up, and sometimes two (e.g. Work + Money). Keyword rules cover obvious cases ("exam", "assignment" → Education; "password", "PIN" → Private). If nothing is confident, the memory stays uncategorized rather than guessing badly.
3. **Learns from you.** When you move a memory to another category, the prototype shifts toward your examples, so Kai's sorting gets more personal over time. A category you remove from a memory is never re-added to it.
4. **Your own categories.** Say or type "make a category called Guitar for music practice"; the description is embedded and it works immediately, no training.
5. **By voice.** "Save this under education", "show my work stuff", "what did I save about exams?" (search inside one category).
6. **Optional LLM second opinion** (§10) only for memories the embeddings weren't sure about.

**Schema** (in `schema_v1.sql`, tested): `categories` (name, description, icon, color, private flag, prototype vector + which model built it, archived) and `memory_categories` (many-to-many, with `state` = AUTO / CONFIRMED / REJECTED, confidence, and who assigned it). Editing a memory's text clears its AUTO labels so it's re-sorted, but keeps your own decisions. Trash and delete behave correctly. Category-filtered search uses `hybrid_search_in_category.sql`, which filters **before** picking the nearest vectors (tested: filtering after can return nothing).

**What the backend gives the future UI:** per-category counts, recent items, a filter for search, a "needs a category" inbox, and a move/merge/rename API.

---

## 13. Store-arrival reminders ("buy milk when I'm at Big Mart")

**Verdict: yes, feasible, and it should be a headline feature.** Example: you say "Remember to buy milk at Big Mart". The next time you are inside *any* Big Mart for a couple of minutes, your phone shows "You're at Big Mart: buy milk (+2 more)", with Done and Snooze buttons.

**The hard parts and the fixes**

| Problem | Fix |
|---|---|
| Kai must know where Big Marts are, offline. | **Offline store pack**: a small file of shops (name, brand, type, coordinates) for Nepal, built from OpenStreetMap data (free, ODbL licence, needs an attribution line in the app). Probably a few MB. Updated with app updates. |
| OpenStreetMap may be missing some branches. Big Mart claims 150+ stores across Kathmandu Valley, Pokhara and Chitwan; coverage in the map data is unverified (the lookup was blocked from my environment, so this is the first thing to check in Phase 5). | **Kai learns branches.** When you tap Done on a Big Mart reminder, or say "I'm at Big Mart", that location is saved as a Big Mart branch. You can also drop a pin. Over time Kai knows every branch you actually use. |
| Android allows only **100 geofences per app**, and there are 150+ Big Marts. | **Rolling geofences**: watch only the ~99 nearest matching places plus one big "refresh" circle around you. When you leave that circle, Kai recalculates the nearest set. Tested in `nearest_place_targets.sql` with 150 branches. |
| Walking or driving past a store would trigger it. Shops in Kathmandu are close together. | Fire only after you've **stayed inside ~2 minutes** (Android's "dwell" trigger). Minimum circle is 100–150 m, so a reminder may occasionally fire next door; Snooze → "not this one" teaches Kai. |
| Delay | Typically under 2 minutes, 2–3 minutes in battery-saving conditions (Google's numbers), which is fine for a shopping trip. |
| Battery | Geofencing uses Android's low-power location system, not continuous GPS. Expected impact is small; measured in Phase 6. |
| Permission | Needs location "Allow all the time". Kai asks only when you create your first place reminder, explaining why. Without it, Kai still works but place reminders don't. |

**What you can say**
- A chain: "…at Big Mart", "…at Bhat-Bhateni".
- A kind of place: "…when I'm at a pharmacy / any supermarket / petrol pump".
- Your places: "…when I get home", "…when I reach the office".
- Leaving: "**when I leave home**, remind me to take the charger".
- "Here": "remind me here next time" (saves the current spot).

Several errands for the same place are grouped into one notification, which becomes a ticking shopping list.

**Schema** (in `schema_v1.sql`, tested): `places` gains brand, type, origin (yours / learned / store pack) and an R*Tree spatial index; `reminders` targets exactly one of a specific place, a brand or a place type, with a dwell time; `active_geofences` tracks what is registered with Android.

---

## 14. Two features that make Kai part of daily life

Many apps try to make themselves impossible to remove. Kai should earn its place by being useful every day. These two do that and are buildable offline:

### A. "Moments": reminders that fire at the right moment in real life, not just at a time
Store-arrival (§13) is one moment. Others:
- **Leaving home**: "take the charger / lunch / umbrella". This covers the most common way people forget things, and it happens daily.
- **Getting in the car or putting in earbuds**: "when I get in the car, remind me to fill petrol". Detected when the phone connects to that Bluetooth device. Android lets apps receive this event even in the background, and it needs no GPS. ✅ feasible.
- **Next morning / tonight**: "remind me tomorrow morning" without picking an exact time.
Why it's sticky: people make these reminders several times a week, and no default app on Android does store chains + leaving home + car in one voice sentence.

### B. "Life admin autopilot": never miss an expiry or due date
Share or screenshot a bill, ticket, insurance card, warranty, passport/licence page, or prescription. Kai reads it on the device (ML Kit text recognition, offline) and finds the important dates and numbers. It then offers reminders: electricity bill 3 days before it's due, bike insurance 2 weeks before it expires, passport 6 months before. Later you can ask "when does my bike insurance expire?" or "what's my policy number?", and sensitive items go into the fingerprint-locked Private category. ✅ feasible (date patterns + OCR; accuracy is improved by confirming each suggested reminder with one tap).
Why it's sticky: once your important documents and deadlines live in Kai, it becomes the place you check, and it saves real money and stress.

Both reuse the backend already planned (reminders, places, OCR, categories), so they add about a week, not a new system.

---

## 15. Changes from user research (23 Nov 2026)

Source: `/mnt/project-files/research/forgetting_pain_points_2026-11.md` (562 posts read). The top problems are (1) forgetting to do something at the right moment, (2) saving things but never getting them back, (3) losing objects. Kai's core already targets all three. Changes:

1. **Nag until done** (new, Tier 1). Important reminders don't vanish when swiped: they come back (5 min, 15 min, at next unlock, or "when I get home"), stay pinned in Kai until marked Done, and Snooze offers moments as well as times ("later", "when I'm home", "tonight"). Normal reminders keep one gentle notification. Reason: "if I dismissed a notification, it would just disappear".
2. **"Did I…?" log** (new, Tier 1). "I locked the door", "took my meds" is saved with a timestamp in one tap or one sentence; "did I take my meds today?" gets a yes/no with the time. Optional home-screen buttons for daily items. Stored as `kind = 'FACT'` memories with `subject` (no schema change).
3. **Moments and store reminders move to the top of the launch pitch.** Google removed location reminders from Assistant, Keep and Gemini, so Android users who relied on them have nowhere obvious to go.
4. **Zero-maintenance promise.** No folders, tags or reviews required; categories, resurfacing and the morning brief do the upkeep, because abandoned systems are the second-biggest complaint.
5. **People notes stay Tier 2** (21 posts). Medication stays as recurring reminders + the "Did I…?" log rather than a separate medication tracker.
6. **No always-recording wearable.** Users like the idea but distrust recording everyone and clouds that shut down. A Wear OS tile for one-tap capture stays on the later list.
