package app.kataa.core.db

import app.kataa.core.NATIVE_LIB
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shipped native database: encryption, built-in extensions, and driver edge cases. */
class NativeSqlDriverTest {
    private fun lib() = NATIVE_LIB?.also { KataaNative.load(it) }

    private fun tempDb(): File = Files.createTempDirectory("kataa").toFile().resolve("kataa.db")

    @Test fun encryptedAtRestAndKeyRequired() {
        lib() ?: return println("SKIP native library not built")
        val file = tempDb()
        val key = Random.nextBytes(32)
        NativeSqlDriver(file.path, key.copyOf()).use { d ->
            d.execute("CREATE TABLE t(x TEXT)")
            d.execute("INSERT INTO t VALUES (?)", "my bank PIN is 4321")
        }
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.startsWith("SQLite format 3"), "file header must be encrypted")
        assertFalse(raw.contains("4321"), "plaintext leaked to disk")
        file.parentFile.listFiles()!!.filter { it != file }.forEach {
            assertFalse(String(it.readBytes(), Charsets.ISO_8859_1).contains("4321"), "plaintext in ${it.name}")
        }

        assertFailsWith<SqliteException> { NativeSqlDriver(file.path, Random.nextBytes(32)) }
        assertFailsWith<SqliteException> { NativeSqlDriver(file.path, null) }
        NativeSqlDriver(file.path, key.copyOf()).use { d ->
            assertEquals("my bank PIN is 4321", d.queryString("SELECT x FROM t"))
        }
    }

    @Test fun extensionsAreBuiltIn() {
        lib() ?: return
        NativeSqlDriver(":memory:", null).use { d ->
            assertTrue(d.queryString("PRAGMA cipher_version")!!.isNotBlank())
            assertTrue(d.queryString("SELECT sqlite_version()")!!.startsWith("3."))
            assertTrue(d.queryString("SELECT vec_version()")!!.startsWith("v0.1"))
            d.execute("CREATE VIRTUAL TABLE f USING fts5(body, tokenize='porter unicode61')")
            d.execute("CREATE VIRTUAL TABLE r USING rtree(id, minX, maxX, minY, maxY)")
            d.execute("CREATE VIRTUAL TABLE v USING vec0(embedding float[2] distance_metric=cosine)")
            // load_extension is compiled out: nothing can load code into the app's database.
            assertFailsWith<SqliteException> { d.query("SELECT load_extension('x')") { it.long(0) } }
        }
    }

    @Test fun unicodeRoundTrips() {
        lib() ?: return
        NativeSqlDriver(":memory:", null).use { d ->
            d.execute("CREATE TABLE t(x TEXT, b BLOB)")
            val text = "चाबी 🔑 in the drawer — café, 日本, 𝄞"
            val blob = byteArrayOf(0, 1, -1, 127, -128)
            d.execute("INSERT INTO t VALUES (?, ?)", text, blob)
            assertEquals(text, d.queryString("SELECT x FROM t"))
            assertTrue(blob.contentEquals(d.queryOne("SELECT b FROM t") { it.bytes(0) }!!))
            assertEquals(text.encodeToByteArray().size.toLong(), d.queryLong("SELECT length(CAST(x AS BLOB)) FROM t"))
        }
    }

    @Test fun nestedQueriesAndSavepoints() {
        lib() ?: return
        NativeSqlDriver(":memory:", null).use { d ->
            d.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, n INT)")
            for (i in 1..3) d.execute("INSERT INTO t(n) VALUES (?)", i)
            // The same SQL used inside its own row callback must not clobber the outer cursor.
            val pairs = d.query("SELECT n FROM t ORDER BY n") { r ->
                r.long(0) to d.query("SELECT n FROM t ORDER BY n") { it.long(0) }.sum()
            }
            assertEquals(listOf(1L to 6L, 2L to 6L, 3L to 6L), pairs)

            d.transaction {
                d.execute("INSERT INTO t(n) VALUES (10)")
                runCatching { d.transaction { d.execute("INSERT INTO t(n) VALUES (20)"); error("inner fails") } }
            }
            assertEquals(listOf(1L, 2L, 3L, 10L), d.query("SELECT n FROM t ORDER BY n") { it.long(0) })
            assertFailsWith<SqliteException> { d.execute("INSERT INTO nope VALUES (1)") }
            assertEquals(1, d.execute("UPDATE t SET n = 11 WHERE n = 10"))
        }
    }

    @Test fun statementCacheEvictionIsSafe() {
        lib() ?: return
        NativeSqlDriver(":memory:", null, statementCacheSize = 4).use { d ->
            repeat(50) { i -> assertEquals(i.toLong(), d.queryLong("SELECT $i")) }
        }
    }
}
