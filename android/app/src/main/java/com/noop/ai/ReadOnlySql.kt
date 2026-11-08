package com.noop.ai

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The statement check in front of the computer-query server's `/v1/sql`.
 *
 * It is the outer of three layers, not the only one: the statement then runs on a connection opened
 * `OPEN_READONLY` with `PRAGMA query_only = 1` ([AndroidReadOnlySql]). Those two stop every write.
 * What they do not stop is `ATTACH`, which would let a read reach other database files in the app's
 * sandbox, and `PRAGMA`, some of which change connection behaviour; so this rejects those by keyword,
 * plus anything that is not a single query statement.
 *
 * Keywords are read from bare words only: string literals, quoted identifiers and comments are
 * skipped, so `WHERE note = 'attach'` passes and a `;` inside a literal is not a second statement.
 */
internal object ReadOnlySqlGuard {
    sealed class Verdict {
        /** [sql] has trailing semicolons and surrounding space removed; [wrappable] says it may go
         *  inside `SELECT * FROM (...) LIMIT n` (EXPLAIN may not). */
        data class Ok(val sql: String, val wrappable: Boolean) : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    private val LEADING = setOf("SELECT", "WITH", "VALUES", "EXPLAIN")
    private val FORBIDDEN = setOf(
        "ATTACH", "DETACH", "PRAGMA", "VACUUM", "INSERT", "UPDATE", "DELETE", "REPLACE", "UPSERT",
        "CREATE", "DROP", "ALTER", "REINDEX", "ANALYZE", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT",
        "RELEASE", "LOAD_EXTENSION",
    )
    const val MAX_SQL_CHARS = 20_000

    fun check(raw: String): Verdict {
        if (raw.length > MAX_SQL_CHARS) return Verdict.Rejected("statement is longer than $MAX_SQL_CHARS characters")
        val scan = scan(raw) ?: return Verdict.Rejected("unterminated string, identifier or comment")
        // Everything after the last statement character must be whitespace, comments or semicolons;
        // a semicolon followed by more statement text is a second statement.
        val stmtEnd = scan.lastSignificant + 1
        if (stmtEnd == 0) return Verdict.Rejected("empty statement")
        if (scan.semicolons.any { it < stmtEnd }) return Verdict.Rejected("only one statement is allowed")
        val first = scan.words.firstOrNull()?.uppercase()
            ?: return Verdict.Rejected("statement must start with SELECT, WITH, VALUES or EXPLAIN")
        if (first !in LEADING) return Verdict.Rejected("statement must start with SELECT, WITH, VALUES or EXPLAIN")
        scan.words.firstOrNull { it.uppercase() in FORBIDDEN }?.let {
            return Verdict.Rejected("${it.uppercase()} is not allowed")
        }
        return Verdict.Ok(raw.substring(0, stmtEnd).trim(), wrappable = first != "EXPLAIN")
    }

    private class Scan(val words: List<String>, val semicolons: List<Int>, val lastSignificant: Int)

    /** Returns null when a literal or comment is left open. */
    private fun scan(s: String): Scan? {
        val words = ArrayList<String>()
        val semis = ArrayList<Int>()
        var last = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '-' && i + 1 < s.length && s[i + 1] == '-' -> {
                    val nl = s.indexOf('\n', i)
                    i = if (nl < 0) s.length else nl + 1
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) return null
                    i = end + 2
                }
                c == '\'' || c == '"' || c == '`' -> {
                    var j = i + 1
                    while (true) {
                        if (j >= s.length) return null
                        if (s[j] == c) {
                            if (j + 1 < s.length && s[j + 1] == c) { j += 2; continue }
                            break
                        }
                        j++
                    }
                    last = j
                    i = j + 1
                }
                c == '[' -> {
                    val end = s.indexOf(']', i + 1)
                    if (end < 0) return null
                    last = end
                    i = end + 1
                }
                c == ';' -> { semis.add(i); i++ }
                c.isWhitespace() -> i++
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '$')) j++
                    words.add(s.substring(i, j))
                    last = j - 1
                    i = j
                }
                else -> { last = i; i++ }
            }
        }
        return Scan(words, semis, last)
    }
}

/** What the router needs from the database, so it can be tested without Android's SQLite. */
internal interface LanSqlRunner {
    /** Runs a statement [ReadOnlySqlGuard] accepted; at most [limit] rows. */
    fun query(sql: String, wrappable: Boolean, limit: Int): JSONObject
    fun schema(): JSONObject
    fun close()
}

/**
 * Runs `/v1/sql` on its own connection to the Room file, opened `OPEN_READONLY` and set
 * `PRAGMA query_only = 1`, so it can never write even if the guard missed something, and never
 * touches the app's own (writable) connection pool. One statement at a time; each is cancelled after
 * [timeoutMs]. A WAL database allows this second reader alongside Room's writer.
 */
internal class AndroidReadOnlySql(
    private val dbPath: String,
    private val timeoutMs: Long = 15_000,
) : LanSqlRunner {
    private val lock = Any()
    private var db: SQLiteDatabase? = null
    // Timeouts run as coroutines, not on a scheduled executor: the parity ledger matches calls by name,
    // and an executor's scheduling call would read as a production caller of the breath-protocol
    // player's method of the same name.
    private val timeouts = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun open(): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        val opened = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        opened.rawQuery("PRAGMA query_only = 1", null).use { it.moveToFirst() }
        db = opened
        return opened
    }

    override fun query(sql: String, wrappable: Boolean, limit: Int): JSONObject = synchronized(lock) {
        val started = System.nanoTime()
        val statement = if (wrappable) "SELECT * FROM (\n$sql\n) LIMIT ${limit + 1}" else sql
        val signal = CancellationSignal()
        val timer = timeouts.launch {
            delay(timeoutMs)
            signal.cancel()
        }
        try {
            open().rawQuery(statement, null, signal).use { c ->
                val result = encode(c, limit)
                result.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                result
            }
        } finally {
            timer.cancel()
        }
    }

    override fun schema(): JSONObject = synchronized(lock) {
        val database = open()
        val tables = JSONArray()
        database.rawQuery(
            "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table') ORDER BY name",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                val columns = JSONArray()
                database.rawQuery("PRAGMA table_info(\"${name.replace("\"", "\"\"")}\")", null).use { col ->
                    while (col.moveToNext()) {
                        columns.put(
                            JSONObject()
                                .put("name", col.getString(1))
                                .put("type", col.getString(2) ?: "")
                                .put("not_null", col.getInt(3) != 0)
                                .put("primary_key", col.getInt(5) != 0),
                        )
                    }
                }
                tables.put(JSONObject().put("name", name).put("type", c.getString(1)).put("columns", columns))
            }
        }
        JSONObject().put("tables", tables)
    }

    override fun close() {
        synchronized(lock) {
            runCatching { db?.close() }
            db = null
        }
        timeouts.cancel()
    }

    private fun encode(c: Cursor, limit: Int): JSONObject {
        val columns = JSONArray()
        c.columnNames.forEach { columns.put(it) }
        val rows = JSONArray()
        var bytes = 0L
        var truncated = false
        while (c.moveToNext()) {
            if (rows.length() >= limit || bytes > MAX_RESULT_BYTES) { truncated = true; break }
            val row = JSONArray()
            for (i in 0 until c.columnCount) {
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> row.put(JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> row.put(c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> row.put(c.getDouble(i))
                    Cursor.FIELD_TYPE_STRING -> c.getString(i).also { bytes += it.length; row.put(it) }
                    Cursor.FIELD_TYPE_BLOB -> {
                        val blob = c.getBlob(i)
                        bytes += blob.size
                        row.put(
                            if (blob.size <= MAX_BLOB_BYTES) {
                                JSONObject().put("blob_base64", Base64.encodeToString(blob, Base64.NO_WRAP)).put("bytes", blob.size)
                            } else {
                                JSONObject().put("bytes", blob.size).put("omitted", true)
                            },
                        )
                    }
                }
            }
            rows.put(row)
        }
        return JSONObject()
            .put("columns", columns)
            .put("rows", rows)
            .put("row_count", rows.length())
            .put("truncated", truncated)
    }

    companion object {
        const val MAX_RESULT_BYTES = 4L * 1024 * 1024
        const val MAX_BLOB_BYTES = 64 * 1024
    }
}
