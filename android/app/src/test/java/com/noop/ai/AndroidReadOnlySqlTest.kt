package com.noop.ai

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The second, read-only connection behind `/v1/sql`, on Android's real SQLite (Robolectric). The
 * point of these is the layer UNDER the guard: a write handed straight to the runner still fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidReadOnlySqlTest {
    private lateinit var file: File
    private lateinit var writer: SQLiteDatabase
    private lateinit var runner: AndroidReadOnlySql

    @Before fun setUp() {
        file = File.createTempFile("noop-lan", ".db")
        file.delete()
        writer = SQLiteDatabase.openOrCreateDatabase(file, null)
        writer.enableWriteAheadLogging()
        writer.execSQL("CREATE TABLE dailyMetric (day TEXT PRIMARY KEY, recovery REAL, raw BLOB)")
        for (i in 1..30) writer.execSQL("INSERT INTO dailyMetric VALUES ('2026-10-${"%02d".format(i)}', $i.5, x'0102')")
        runner = AndroidReadOnlySql(file.path)
    }

    @After fun tearDown() {
        runner.close()
        writer.close()
        file.delete()
    }

    @Test fun readsRowsWithTypesAndTruncation() {
        val r = runner.query("SELECT day, recovery, raw, NULL AS n FROM dailyMetric ORDER BY day", wrappable = true, limit = 10)
        assertEquals(listOf("day", "recovery", "raw", "n"), (0 until r.getJSONArray("columns").length()).map { r.getJSONArray("columns").getString(it) })
        assertEquals(10, r.getInt("row_count"))
        assertTrue(r.getBoolean("truncated"))
        val first = r.getJSONArray("rows").getJSONArray(0)
        assertEquals("2026-10-01", first.getString(0))
        assertEquals(1.5, first.getDouble(1), 1e-9)
        assertEquals(2, first.getJSONObject(2).getInt("bytes"))
        assertTrue(first.isNull(3))

        val all = runner.query("SELECT count(*) FROM dailyMetric", wrappable = true, limit = 10)
        assertEquals(30L, all.getJSONArray("rows").getJSONArray(0).getLong(0))
        assertEquals(false, all.getBoolean("truncated"))
    }

    @Test fun writesFailEvenPastTheGuard() {
        for (stmt in listOf("DELETE FROM dailyMetric", "INSERT INTO dailyMetric(day) VALUES ('x')", "DROP TABLE dailyMetric")) {
            try {
                runner.query(stmt, wrappable = false, limit = 10)
                // rawQuery may only prepare without stepping; make sure nothing changed either way.
            } catch (_: Exception) {
                // expected: read-only connection
            }
        }
        writer.rawQuery("SELECT count(*) FROM dailyMetric", null).use {
            it.moveToFirst()
            assertEquals(30, it.getInt(0))
        }
    }

    @Test fun seesTheWritersLatestRows() {
        runner.query("SELECT 1", wrappable = true, limit = 1)
        writer.execSQL("INSERT INTO dailyMetric VALUES ('2026-11-01', 99, NULL)")
        val r = runner.query("SELECT recovery FROM dailyMetric WHERE day = '2026-11-01'", wrappable = true, limit = 5)
        assertEquals(99.0, r.getJSONArray("rows").getJSONArray(0).getDouble(0), 1e-9)
    }

    @Test fun schemaListsTablesAndColumns() {
        val tables = runner.schema().getJSONArray("tables")
        assertEquals(1, tables.length())
        val t = tables.getJSONObject(0)
        assertEquals("dailyMetric", t.getString("name"))
        assertEquals(3, t.getJSONArray("columns").length())
        assertTrue(t.getJSONArray("columns").getJSONObject(0).getBoolean("primary_key"))
        try {
            runner.query("SELECT * FROM missing", wrappable = true, limit = 1)
            fail("expected an error for a missing table")
        } catch (_: Exception) {
        }
    }
}
