package app.kataa.core.db

import org.sqlite.SQLiteConfig
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * JVM driver (tests and desktop tooling): xerial sqlite-jdbc, which ships SQLite with FTS5 and R*Tree,
 * plus the sqlite-vec loadable extension.
 *
 * @param path database file, or ":memory:".
 * @param sqliteVecPath path to the sqlite-vec extension (vec0.so / vec0.dylib), with or without suffix.
 */
class JdbcSqlDriver(path: String, sqliteVecPath: String) : SqlDriver {
    private val conn: Connection
    private var depth = 0

    init {
        val config = SQLiteConfig().apply {
            enableLoadExtension(true)
            enforceForeignKeys(true)
            setBusyTimeout(3000)
            if (path != ":memory:") {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            }
        }
        conn = config.createConnection("jdbc:sqlite:$path")
        conn.createStatement().use { it.execute("SELECT load_extension('${sqliteVecPath.replace("'", "''")}')") }
    }

    override fun execute(sql: String, vararg args: Any?): Int =
        prepare(sql, args).use { st ->
            // PRAGMA and some statements return a result set; executeUpdate would reject those.
            if (st.execute()) 0 else st.updateCount.coerceAtLeast(0)
        }

    override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> =
        prepare(sql, args).use { st ->
            st.executeQuery().use { rs ->
                val row = JdbcRow(rs)
                val out = ArrayList<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

    override fun lastInsertRowId(): Long =
        conn.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }

    override fun <T> transaction(block: () -> T): T {
        val name = "sp$depth"
        if (depth == 0) raw("BEGIN IMMEDIATE") else raw("SAVEPOINT $name")
        depth++
        try {
            val result = block()
            depth--
            if (depth == 0) raw("COMMIT") else raw("RELEASE $name")
            return result
        } catch (t: Throwable) {
            depth--
            runCatching { if (depth == 0) raw("ROLLBACK") else { raw("ROLLBACK TO $name"); raw("RELEASE $name") } }
            throw t
        }
    }

    override fun close() = conn.close()

    private fun raw(sql: String) { conn.createStatement().use { it.execute(sql) } }

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement {
        val st = conn.prepareStatement(sql)
        try {
            args.forEachIndexed { i, a ->
                val idx = i + 1
                when (a) {
                    null -> st.setNull(idx, Types.NULL)
                    is Long -> st.setLong(idx, a)
                    is Int -> st.setLong(idx, a.toLong())
                    is Double -> st.setDouble(idx, a)
                    is Float -> st.setDouble(idx, a.toDouble())
                    is String -> st.setString(idx, a)
                    is ByteArray -> st.setBytes(idx, a)
                    is Boolean -> st.setLong(idx, if (a) 1 else 0)
                    else -> throw IllegalArgumentException("unsupported SQL argument type ${a::class}")
                }
            }
        } catch (t: Throwable) {
            st.close(); throw t
        }
        return st
    }

    private class JdbcRow(private val rs: ResultSet) : Row {
        override fun isNull(index: Int): Boolean { rs.getObject(index + 1); return rs.wasNull() }
        override fun long(index: Int): Long = rs.getLong(index + 1)
        override fun double(index: Int): Double = rs.getDouble(index + 1)
        override fun string(index: Int): String = rs.getString(index + 1)
        override fun bytes(index: Int): ByteArray = rs.getBytes(index + 1)
    }
}
