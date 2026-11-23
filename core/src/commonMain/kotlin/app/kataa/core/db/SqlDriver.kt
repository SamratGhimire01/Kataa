package app.kataa.core.db

/**
 * The minimal SQL surface the core needs. Each platform supplies one implementation:
 * - JVM (tests, desktop tools): sqlite-jdbc + sqlite-vec loadable extension.
 * - Android: bundled SQLCipher build with FTS5, R*Tree and sqlite-vec compiled in (Phase 1b).
 * - iOS: same native build, linked statically (later).
 *
 * Arguments may be: null, Long, Int, Double, Float, String, ByteArray, Boolean.
 * Implementations are not thread-safe; the core uses one connection on one worker thread.
 */
interface SqlDriver : AutoCloseable {
    /** Runs a statement that returns no rows. Returns the number of changed rows. */
    fun execute(sql: String, vararg args: Any?): Int

    /** Runs a query and maps every row. */
    fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T>

    /** Rowid of the last successful INSERT on this connection. */
    fun lastInsertRowId(): Long

    /**
     * Runs [block] atomically. Nested calls become savepoints, so a failing inner block
     * rolls back only its own work when the caller catches the exception.
     */
    fun <T> transaction(block: () -> T): T
}

/** Column accessors by 0-based index. */
interface Row {
    fun isNull(index: Int): Boolean
    fun long(index: Int): Long
    fun double(index: Int): Double
    fun string(index: Int): String
    fun bytes(index: Int): ByteArray

    fun longOrNull(index: Int): Long? = if (isNull(index)) null else long(index)
    fun doubleOrNull(index: Int): Double? = if (isNull(index)) null else double(index)
    fun stringOrNull(index: Int): String? = if (isNull(index)) null else string(index)
    fun bytesOrNull(index: Int): ByteArray? = if (isNull(index)) null else bytes(index)
}

fun <T> SqlDriver.queryOne(sql: String, vararg args: Any?, map: (Row) -> T): T? =
    query(sql, *args, map = map).firstOrNull()

fun SqlDriver.queryLong(sql: String, vararg args: Any?): Long? =
    queryOne(sql, *args) { it.longOrNull(0) }

fun SqlDriver.queryString(sql: String, vararg args: Any?): String? =
    queryOne(sql, *args) { it.stringOrNull(0) }
