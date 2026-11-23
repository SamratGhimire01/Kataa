package app.kataa.core.db

/** SQLite failure from the native driver (message includes SQLite's error text and code). */
class SqliteException(message: String) : RuntimeException(message)

/**
 * JNI bindings to libkataa_sqlite (native/kataa_jni.c). Android loads it from the APK; JVM tests load
 * the host build. Handles are raw sqlite3* / sqlite3_stmt* pointers.
 */
object KataaNative {
    @Volatile private var loaded = false

    /** Loads the library once. [path] is for the host build in tests; Android passes null. */
    @Synchronized
    fun load(path: String? = null) {
        if (loaded) return
        if (path != null) System.load(path) else System.loadLibrary("kataa_sqlite")
        loaded = true
    }

    @JvmStatic external fun open(pathUtf8: ByteArray, key: ByteArray?): Long
    @JvmStatic external fun close(db: Long)
    @JvmStatic external fun exec(db: Long, sqlUtf8: ByteArray)
    @JvmStatic external fun prepare(db: Long, sqlUtf8: ByteArray): Long
    @JvmStatic external fun finalize(stmt: Long)
    @JvmStatic external fun reset(stmt: Long)
    @JvmStatic external fun bindNull(stmt: Long, index: Int)
    @JvmStatic external fun bindLong(stmt: Long, index: Int, value: Long)
    @JvmStatic external fun bindDouble(stmt: Long, index: Int, value: Double)
    @JvmStatic external fun bindText(stmt: Long, index: Int, utf8: ByteArray)
    @JvmStatic external fun bindBlob(stmt: Long, index: Int, value: ByteArray)
    @JvmStatic external fun step(stmt: Long): Int
    @JvmStatic external fun columnCount(stmt: Long): Int
    @JvmStatic external fun columnType(stmt: Long, index: Int): Int
    @JvmStatic external fun columnLong(stmt: Long, index: Int): Long
    @JvmStatic external fun columnDouble(stmt: Long, index: Int): Double
    @JvmStatic external fun columnText(stmt: Long, index: Int): ByteArray?
    @JvmStatic external fun columnBlob(stmt: Long, index: Int): ByteArray?
    @JvmStatic external fun changes(db: Long): Int
    @JvmStatic external fun lastInsertRowId(db: Long): Long

    const val SQLITE_NULL = 5
}

/**
 * The production driver: Kataa's own SQLCipher build (AES-256 encrypted file, FTS5, R*Tree, sqlite-vec
 * built in). Used on Android, and on the JVM in tests so the shipped C code is what gets tested.
 *
 * @param path database file, or ":memory:".
 * @param key raw 32-byte database key (from the Android Keystore); null opens an unencrypted database.
 *   The array is not kept; callers should wipe it after opening.
 */
class NativeSqlDriver(path: String, key: ByteArray?, statementCacheSize: Int = 64) : SqlDriver {
    private val db: Long
    private var depth = 0
    private var closed = false

    /** Prepared statements reused by SQL text; a statement in use (nested query) is never shared. */
    private val cache = object : LinkedHashMap<String, Stmt>(statementCacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stmt>): Boolean {
            if (size <= statementCacheSize) return false
            if (!eldest.value.inUse) KataaNative.finalize(eldest.value.handle) else eldest.value.evicted = true
            return true
        }
    }

    private class Stmt(val handle: Long) { var inUse = false; var evicted = false }

    init {
        KataaNative.load()
        db = KataaNative.open(path.encodeToByteArray(), key)
        try {
            // Reading anything proves the key is right; a wrong key fails here with "file is not a database".
            KataaNative.exec(db, "SELECT count(*) FROM sqlite_master".encodeToByteArray())
            if (path != ":memory:") KataaNative.exec(db, "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL".encodeToByteArray())
            KataaNative.exec(db, "PRAGMA foreign_keys=ON".encodeToByteArray())
        } catch (t: Throwable) {
            KataaNative.close(db); throw t
        }
    }

    override fun execute(sql: String, vararg args: Any?): Int = withStatement(sql, args) { s ->
        while (KataaNative.step(s) == 1) Unit // PRAGMAs may return a row; drain it.
        KataaNative.changes(db)
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = withStatement(sql, args) { s ->
        val row = NativeRow(s)
        val out = ArrayList<T>()
        while (KataaNative.step(s) == 1) out += map(row)
        out
    }

    override fun lastInsertRowId(): Long = KataaNative.lastInsertRowId(db)

    override fun <T> transaction(block: () -> T): T {
        val name = "sp$depth"
        exec(if (depth == 0) "BEGIN IMMEDIATE" else "SAVEPOINT $name")
        depth++
        try {
            val result = block()
            depth--
            exec(if (depth == 0) "COMMIT" else "RELEASE $name")
            return result
        } catch (t: Throwable) {
            depth--
            runCatching { if (depth == 0) exec("ROLLBACK") else { exec("ROLLBACK TO $name"); exec("RELEASE $name") } }
            throw t
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cache.values.forEach { KataaNative.finalize(it.handle) }
        cache.clear()
        KataaNative.close(db)
    }

    private fun exec(sql: String) = KataaNative.exec(db, sql.encodeToByteArray())

    private inline fun <R> withStatement(sql: String, args: Array<out Any?>, use: (Long) -> R): R {
        check(!closed) { "database is closed" }
        val cached = cache[sql]
        val stmt: Stmt
        val temporary: Boolean
        if (cached != null && !cached.inUse) { stmt = cached; temporary = false }
        else {
            stmt = Stmt(KataaNative.prepare(db, sql.encodeToByteArray()))
            temporary = cached != null // a nested use of a busy statement gets its own copy
            if (!temporary) cache[sql] = stmt
        }
        stmt.inUse = true
        try {
            bind(stmt.handle, args)
            return use(stmt.handle)
        } finally {
            stmt.inUse = false
            if (temporary || stmt.evicted) KataaNative.finalize(stmt.handle) else KataaNative.reset(stmt.handle)
        }
    }

    private fun bind(s: Long, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> KataaNative.bindNull(s, idx)
                is Long -> KataaNative.bindLong(s, idx, a)
                is Int -> KataaNative.bindLong(s, idx, a.toLong())
                is Double -> KataaNative.bindDouble(s, idx, a)
                is Float -> KataaNative.bindDouble(s, idx, a.toDouble())
                is String -> KataaNative.bindText(s, idx, a.encodeToByteArray())
                is ByteArray -> KataaNative.bindBlob(s, idx, a)
                is Boolean -> KataaNative.bindLong(s, idx, if (a) 1 else 0)
                else -> throw IllegalArgumentException("unsupported SQL argument type ${a::class}")
            }
        }
    }

    private class NativeRow(private val s: Long) : Row {
        override fun isNull(index: Int) = KataaNative.columnType(s, index) == KataaNative.SQLITE_NULL
        override fun long(index: Int) = KataaNative.columnLong(s, index)
        override fun double(index: Int) = KataaNative.columnDouble(s, index)
        override fun string(index: Int) = KataaNative.columnText(s, index)?.decodeToString() ?: ""
        override fun bytes(index: Int) = KataaNative.columnBlob(s, index) ?: ByteArray(0)
    }
}
