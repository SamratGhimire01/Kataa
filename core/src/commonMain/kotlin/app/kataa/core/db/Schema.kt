package app.kataa.core.db

/**
 * Schema v1. One statement per list entry (drivers execute them one by one).
 *
 * Invariants (covered by tests):
 * - `memories.id` is an INTEGER PRIMARY KEY (rowid alias), so FTS/vector/foreign keys survive VACUUM.
 * - `memories_fts` indexes exactly the live rows (deleted_at IS NULL) with their current text.
 *   Its content source is the `memories_live` view, so 'rebuild' and 'integrity-check' agree.
 * - A vector exists only for live rows whose text has not changed since it was embedded;
 *   `embed_model IS NULL` marks rows the embedding worker must (re)process.
 * - `classified_at IS NULL` marks rows the category classifier must (re)process.
 * - Trashing a memory cancels its pending reminders.
 */
object Schema {
    const val VERSION = 3

    /** Created separately because its dimension depends on the active embedding model. */
    fun createVectorTable(dimension: Int): String {
        require(dimension in 8..4096) { "unsupported embedding dimension $dimension" }
        return """
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_vec USING vec0(
                memory_id INTEGER PRIMARY KEY,
                embedding float[$dimension] distance_metric=cosine
            )
        """.trimIndent()
    }

    val v1: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS meta (
            key   TEXT PRIMARY KEY,
            value TEXT NOT NULL
        ) WITHOUT ROWID
        """,
        """
        CREATE TABLE IF NOT EXISTS memories (
            id             INTEGER PRIMARY KEY,
            uuid           TEXT NOT NULL UNIQUE,
            kind           TEXT NOT NULL DEFAULT 'NOTE'
                           CHECK (kind IN ('NOTE','OBJECT_LOCATION','REMINDER','TASK','FACT')),
            body           TEXT NOT NULL,
            raw_transcript TEXT,
            extracted_text TEXT,
            source         TEXT NOT NULL
                           CHECK (source IN ('VOICE','TEXT','SHARE','SCREENSHOT','IMPORT')),
            source_app     TEXT,
            lang           TEXT,
            subject        TEXT,
            captured_lat   REAL,
            captured_lng   REAL,
            captured_acc_m REAL,
            created_at     INTEGER NOT NULL,
            updated_at     INTEGER NOT NULL,
            supersedes_id  INTEGER REFERENCES memories(id) ON DELETE SET NULL,
            pinned         INTEGER NOT NULL DEFAULT 0 CHECK (pinned IN (0,1)),
            embed_model    TEXT,
            classified_at  INTEGER,
            deleted_at     INTEGER
        )
        """,
        "CREATE INDEX IF NOT EXISTS memories_live_created ON memories(created_at DESC) WHERE deleted_at IS NULL",
        "CREATE INDEX IF NOT EXISTS memories_subject ON memories(subject, created_at DESC) WHERE deleted_at IS NULL",
        "CREATE INDEX IF NOT EXISTS memories_needs_embed ON memories(id) WHERE embed_model IS NULL AND deleted_at IS NULL",
        "CREATE INDEX IF NOT EXISTS memories_needs_class ON memories(id) WHERE classified_at IS NULL AND deleted_at IS NULL",
        "CREATE INDEX IF NOT EXISTS memories_supersedes ON memories(supersedes_id) WHERE supersedes_id IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS memories_trash ON memories(deleted_at) WHERE deleted_at IS NOT NULL",
        """
        CREATE TABLE IF NOT EXISTS attachments (
            id         INTEGER PRIMARY KEY,
            memory_id  INTEGER NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
            kind       TEXT NOT NULL CHECK (kind IN ('IMAGE','AUDIO','FILE')),
            rel_path   TEXT NOT NULL,
            mime       TEXT,
            bytes      INTEGER,
            sha256     TEXT,
            created_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS attachments_memory ON attachments(memory_id)",

        // ---------- Full-text search over live rows ----------
        """
        CREATE VIEW IF NOT EXISTS memories_live AS
            SELECT id, body, extracted_text FROM memories WHERE deleted_at IS NULL
        """,
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
            body,
            extracted_text,
            content = 'memories_live',
            content_rowid = 'id',
            tokenize = "porter unicode61 remove_diacritics 2"
        )
        """,
        """
        CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories
        WHEN new.deleted_at IS NULL BEGIN
            INSERT INTO memories_fts(rowid, body, extracted_text)
            VALUES (new.id, new.body, new.extracted_text);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
            INSERT INTO memories_fts(memories_fts, rowid, body, extracted_text)
            SELECT 'delete', old.id, old.body, old.extracted_text WHERE old.deleted_at IS NULL;
            DELETE FROM memories_vec WHERE memory_id = old.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE OF body, extracted_text, deleted_at ON memories BEGIN
            INSERT INTO memories_fts(memories_fts, rowid, body, extracted_text)
            SELECT 'delete', old.id, old.body, old.extracted_text WHERE old.deleted_at IS NULL;
            INSERT INTO memories_fts(rowid, body, extracted_text)
            SELECT new.id, new.body, new.extracted_text WHERE new.deleted_at IS NULL;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS memories_invalidate_derived
        AFTER UPDATE OF body, extracted_text, deleted_at ON memories
        WHEN old.body IS NOT new.body
          OR old.extracted_text IS NOT new.extracted_text
          OR (new.deleted_at IS NOT NULL AND old.deleted_at IS NULL) BEGIN
            DELETE FROM memories_vec WHERE memory_id = new.id;
            UPDATE memories SET embed_model = NULL, classified_at = NULL WHERE id = new.id;
        END
        """,

        // ---------- Categories ----------
        """
        CREATE TABLE IF NOT EXISTS categories (
            id             INTEGER PRIMARY KEY,
            slug           TEXT NOT NULL UNIQUE,
            name           TEXT NOT NULL,
            description    TEXT,
            icon           TEXT,
            color          TEXT,
            is_system      INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0,1)),
            is_private     INTEGER NOT NULL DEFAULT 0 CHECK (is_private IN (0,1)),
            sort_order     INTEGER NOT NULL DEFAULT 0,
            centroid       BLOB,
            centroid_model TEXT,
            example_count  INTEGER NOT NULL DEFAULT 0,
            created_at     INTEGER NOT NULL,
            archived_at    INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS memory_categories (
            memory_id   INTEGER NOT NULL REFERENCES memories(id)   ON DELETE CASCADE,
            category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
            state       TEXT NOT NULL CHECK (state IN ('AUTO','CONFIRMED','REJECTED')),
            confidence  REAL,
            assigned_by TEXT NOT NULL DEFAULT 'RULES' CHECK (assigned_by IN ('RULES','EMBEDDING','LLM','USER')),
            updated_at  INTEGER NOT NULL,
            PRIMARY KEY (memory_id, category_id)
        ) WITHOUT ROWID
        """,
        "CREATE INDEX IF NOT EXISTS memory_categories_by_cat ON memory_categories(category_id, memory_id) WHERE state <> 'REJECTED'",
        """
        CREATE TRIGGER IF NOT EXISTS memories_recategorize
        AFTER UPDATE OF body, extracted_text ON memories
        WHEN old.body IS NOT new.body OR old.extracted_text IS NOT new.extracted_text BEGIN
            DELETE FROM memory_categories WHERE memory_id = new.id AND state = 'AUTO';
        END
        """,
        """
        CREATE VIEW IF NOT EXISTS memory_categories_active AS
            SELECT mc.memory_id, mc.category_id, mc.state, mc.confidence
            FROM memory_categories mc
            JOIN categories c ON c.id = mc.category_id AND c.archived_at IS NULL
            JOIN memories   m ON m.id = mc.memory_id   AND m.deleted_at IS NULL
            WHERE mc.state <> 'REJECTED'
        """,

        // ---------- Places & reminders ----------
        """
        CREATE TABLE IF NOT EXISTS places (
            id         INTEGER PRIMARY KEY,
            label      TEXT NOT NULL COLLATE NOCASE,
            brand      TEXT COLLATE NOCASE,
            place_type TEXT,
            lat        REAL NOT NULL,
            lng        REAL NOT NULL,
            radius_m   REAL NOT NULL DEFAULT 150 CHECK (radius_m >= 100),
            origin     TEXT NOT NULL DEFAULT 'USER' CHECK (origin IN ('USER','LEARNED','POI_PACK')),
            poi_ref    TEXT,
            created_at INTEGER NOT NULL,
            UNIQUE (label, lat, lng)
        )
        """,
        "CREATE INDEX IF NOT EXISTS places_brand ON places(brand) WHERE brand IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS places_type ON places(place_type) WHERE place_type IS NOT NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS places_poi_ref ON places(poi_ref) WHERE poi_ref IS NOT NULL",
        "CREATE VIRTUAL TABLE IF NOT EXISTS places_rtree USING rtree(id, min_lat, max_lat, min_lng, max_lng)",
        """
        CREATE TRIGGER IF NOT EXISTS places_rtree_ai AFTER INSERT ON places BEGIN
            INSERT INTO places_rtree VALUES (new.id, new.lat, new.lat, new.lng, new.lng);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS places_rtree_au AFTER UPDATE OF lat, lng ON places BEGIN
            UPDATE places_rtree SET min_lat = new.lat, max_lat = new.lat, min_lng = new.lng, max_lng = new.lng
            WHERE id = new.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS places_rtree_ad AFTER DELETE ON places BEGIN
            DELETE FROM places_rtree WHERE id = old.id;
        END
        """,
        """
        CREATE TABLE IF NOT EXISTS reminders (
            id             INTEGER PRIMARY KEY,
            memory_id      INTEGER NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
            type           TEXT NOT NULL CHECK (type IN ('TIME','PLACE_ENTER','PLACE_EXIT','BLUETOOTH_CONNECT','BLUETOOTH_DISCONNECT')),
            fire_at        INTEGER,
            tz             TEXT,
            rrule          TEXT,
            place_id       INTEGER REFERENCES places(id) ON DELETE CASCADE,
            place_brand    TEXT COLLATE NOCASE,
            place_type     TEXT,
            dwell_ms       INTEGER NOT NULL DEFAULT 120000,
            bt_device      TEXT,
            persistent     INTEGER NOT NULL DEFAULT 0 CHECK (persistent IN (0,1)),
            status         TEXT NOT NULL DEFAULT 'SCHEDULED'
                           CHECK (status IN ('SCHEDULED','FIRED','SNOOZED','DONE','CANCELLED')),
            snooze_until   INTEGER,
            os_request_id  TEXT,
            last_fired_at  INTEGER,
            created_at     INTEGER NOT NULL,
            CHECK (
                (type = 'TIME' AND fire_at IS NOT NULL) OR
                (type IN ('PLACE_ENTER','PLACE_EXIT') AND
                    (place_id IS NOT NULL) + (place_brand IS NOT NULL) + (place_type IS NOT NULL) = 1) OR
                (type IN ('BLUETOOTH_CONNECT','BLUETOOTH_DISCONNECT') AND bt_device IS NOT NULL)
            )
        )
        """,
        "CREATE INDEX IF NOT EXISTS reminders_due ON reminders(fire_at) WHERE type = 'TIME' AND status IN ('SCHEDULED','SNOOZED')",
        "CREATE INDEX IF NOT EXISTS reminders_place ON reminders(place_id) WHERE status IN ('SCHEDULED','SNOOZED')",
        "CREATE INDEX IF NOT EXISTS reminders_brand ON reminders(place_brand) WHERE status IN ('SCHEDULED','SNOOZED')",
        "CREATE INDEX IF NOT EXISTS reminders_ptype ON reminders(place_type) WHERE status IN ('SCHEDULED','SNOOZED')",
        "CREATE INDEX IF NOT EXISTS reminders_memory ON reminders(memory_id)",
        """
        CREATE TRIGGER IF NOT EXISTS memories_trash_cancels_reminders
        AFTER UPDATE OF deleted_at ON memories
        WHEN new.deleted_at IS NOT NULL AND old.deleted_at IS NULL BEGIN
            UPDATE reminders SET status = 'CANCELLED'
            WHERE memory_id = new.id AND status IN ('SCHEDULED','SNOOZED','FIRED');
        END
        """,
        """
        CREATE TABLE IF NOT EXISTS active_geofences (
            request_id    TEXT PRIMARY KEY,
            place_id      INTEGER REFERENCES places(id) ON DELETE CASCADE,
            lat           REAL NOT NULL,
            lng           REAL NOT NULL,
            radius_m      REAL NOT NULL,
            registered_at INTEGER NOT NULL
        ) WITHOUT ROWID
        """,
    ).map { it.trimIndent().trim() }

    /**
     * v2: to-do lists and the journal.
     * - `list_items.list_key` is "day:2026-11-24", "month:2026-11" or "inbox". Items are plain rows, not memories:
     *   ticking one must not touch search or embeddings.
     * - A journal entry is a normal memory (searchable), linked to the day it was said in `journal_entries`;
     *   `journal_days.summary` is that day's short summary, rebuilt when `changed_at > summarized_at`.
     */
    val v2: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS list_items (
            id          INTEGER PRIMARY KEY,
            uuid        TEXT NOT NULL UNIQUE,
            list_key    TEXT NOT NULL,
            body        TEXT NOT NULL,
            position    INTEGER NOT NULL,
            cost        REAL,
            minutes     INTEGER,
            done_at     INTEGER,
            created_at  INTEGER NOT NULL,
            deleted_at  INTEGER
        )
        """,
        "CREATE INDEX IF NOT EXISTS list_items_by_key ON list_items(list_key, position) WHERE deleted_at IS NULL",
        """
        CREATE TABLE IF NOT EXISTS journal_entries (
            memory_id   INTEGER PRIMARY KEY REFERENCES memories(id) ON DELETE CASCADE,
            day         TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS journal_entries_by_day ON journal_entries(day)",
        """
        CREATE TABLE IF NOT EXISTS journal_days (
            day            TEXT PRIMARY KEY,
            summary        TEXT,
            summary_by_ai  INTEGER NOT NULL DEFAULT 0,
            summarized_at  INTEGER,
            changed_at     INTEGER NOT NULL
        ) WITHOUT ROWID
        """,
    ).map { it.trimIndent().trim() }

    /** v3: how many entries a journal day's summary was made from, so a deleted entry rebuilds it. */
    val v3: List<String> = listOf(
        "ALTER TABLE journal_days ADD COLUMN entry_count INTEGER NOT NULL DEFAULT 0",
        // Existing days: the summary is checked again once (the count starts at 0 and differs from the live count).
    )
}
