package com.noop.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The pure parts of the computer-query server: HTTP parsing, the SQL guard, pairing and routing. */
class CoachLanTest {

    // MARK: - HTTP

    private fun parse(raw: String) = LanHttp.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test fun parsesGetWithQuery() {
        val r = parse("GET /v1/sql?q=SELECT%201&limit=5 HTTP/1.1\r\nHost: 10.0.0.2:8765\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/v1/sql", r.path)
        assertEquals("SELECT 1", r.query["q"])
        assertEquals("5", r.query["limit"])
        assertEquals("10.0.0.2:8765", r.headers["host"])
    }

    @Test fun parsesPostBody() {
        val body = "{\"sql\":\"SELECT 1\"}"
        val r = parse("POST /v1/sql HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals(body, r.body)
    }

    @Test fun rejectsOversizedBodyAndMalformedLines() {
        assertStatus(413) { parse("POST / HTTP/1.1\r\nContent-Length: ${LanHttp.MAX_BODY_BYTES + 1}\r\n\r\n") }
        assertStatus(400) { parse("NONSENSE\r\n\r\n") }
        assertStatus(400) { parse("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n") }
        assertStatus(400) { parse("GET / HTTP/1.1\r\nHost: x") }
        assertStatus(431) { parse("GET / HTTP/1.1\r\nX: " + "a".repeat(LanHttp.MAX_HEADER_BYTES) + "\r\n\r\n") }
    }

    @Test fun writesContentLengthInBytes() {
        val out = ByteArrayOutputStream()
        LanHttp.write(out, LanHttpResponse(200, "{\"t\":\"教练\"}"))
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: ${"{\"t\":\"教练\"}".toByteArray().size}\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
    }

    private fun assertStatus(status: Int, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected LanHttpException($status)")
        } catch (e: LanHttpException) {
            assertEquals(status, e.status)
        }
    }

    // MARK: - SQL guard

    private fun ok(sql: String) = ReadOnlySqlGuard.check(sql) as? ReadOnlySqlGuard.Verdict.Ok
        ?: throw AssertionError("expected $sql to pass, got ${ReadOnlySqlGuard.check(sql)}")

    private fun rejected(sql: String) =
        assertTrue("expected rejection: $sql", ReadOnlySqlGuard.check(sql) is ReadOnlySqlGuard.Verdict.Rejected)

    @Test fun guardAcceptsQueries() {
        assertEquals("SELECT * FROM dailyMetric", ok("  SELECT * FROM dailyMetric ;; ").sql)
        assertTrue(ok("with x as (select 1) select * from x").wrappable)
        assertTrue(ok("VALUES (1)").wrappable)
        assertFalse(ok("EXPLAIN QUERY PLAN SELECT 1").wrappable)
        // Keywords inside literals, quoted identifiers and comments are not keywords.
        ok("SELECT 'attach; drop table x' AS note")
        ok("SELECT \"delete\" FROM [update] -- pragma\n")
        ok("SELECT 1 /* insert; */")
        assertEquals("SELECT 1", ok("SELECT 1 -- trailing").sql)
    }

    @Test fun guardRejectsEverythingElse() {
        rejected("")
        rejected("   ;  ")
        rejected("DELETE FROM dailyMetric")
        rejected("PRAGMA table_info(dailyMetric)")
        rejected("ATTACH DATABASE '/data/x.db' AS x")
        rejected("SELECT 1; DELETE FROM dailyMetric")
        rejected("SELECT 1; SELECT 2")
        rejected("WITH x AS (SELECT 1) DELETE FROM dailyMetric")
        rejected("SELECT * FROM a WHERE b IN (SELECT 1) ; vacuum")
        rejected("SELECT 'unterminated")
        rejected("SELECT 1 /* open")
        rejected("SELECT " + "1,".repeat(ReadOnlySqlGuard.MAX_SQL_CHARS))
    }

    // MARK: - Session

    @Test fun pairingGrantsATokenOnlyToTheAskingAddress() {
        val s = CoachLanSession(expiresAtMillis = Long.MAX_VALUE)
        val req = s.requestAccess("claude-code", "192.168.1.20")!!
        assertEquals(CoachLanSession.Status.PENDING, s.poll(req.id, "192.168.1.20").first)
        assertEquals(CoachLanSession.Status.UNKNOWN, s.poll(req.id, "192.168.1.21").first)
        assertTrue(s.approve(req.id))
        val (status, token) = s.poll(req.id, "192.168.1.20")
        assertEquals(CoachLanSession.Status.APPROVED, status)
        assertNotNull(token)
        assertNull(s.poll(req.id, "192.168.1.21").second)
        assertTrue(s.authorize(token, "192.168.1.20"))
        assertFalse(s.authorize(token, "192.168.1.21"))
        assertFalse(s.authorize("nope", "192.168.1.20"))
        assertFalse(s.authorize(null, "192.168.1.20"))
    }

    @Test fun deniedAndBoundedRequests() {
        val s = CoachLanSession(expiresAtMillis = Long.MAX_VALUE)
        val a = s.requestAccess("a", "10.0.0.1")!!
        assertEquals(a.id, s.requestAccess("again", "10.0.0.1")!!.id) // one waiting request per address
        assertTrue(s.deny(a.id))
        assertFalse(s.approve(a.id))
        assertEquals(CoachLanSession.Status.DENIED, s.poll(a.id, "10.0.0.1").first)
        repeat(CoachLanSession.MAX_PENDING) { assertNotNull(s.requestAccess("c$it", "10.0.1.$it")) }
        assertNull(s.requestAccess("one too many", "10.0.2.1"))
    }

    @Test fun sanitizesClientNames() {
        assertEquals("unnamed agent", CoachLanSession.sanitizeClient("  \n "))
        assertEquals("ab", CoachLanSession.sanitizeClient("a\u0000b\n"))
        assertEquals(40, CoachLanSession.sanitizeClient("x".repeat(100)).length)
    }

    // MARK: - Router

    private class FakeSql : LanSqlRunner {
        val seen = mutableListOf<Triple<String, Boolean, Int>>()
        override fun query(sql: String, wrappable: Boolean, limit: Int): JSONObject {
            seen += Triple(sql, wrappable, limit)
            return JSONObject().put("columns", JSONArray().put("x")).put("rows", JSONArray().put(JSONArray().put(1)))
        }
        override fun schema(): JSONObject = JSONObject().put("tables", JSONArray())
        override fun close() {}
    }

    private val host = "192.168.1.5:8765"
    private val peer = "192.168.1.20"

    private fun req(method: String, path: String, body: String = "", token: String? = null, hostHeader: String? = host) =
        LanHttpRequest(
            method, path.substringBefore('?'),
            if ('?' in path) LanHttp.parseQuery(path.substringAfter('?')) else emptyMap(),
            buildMap {
                hostHeader?.let { put("host", it) }
                token?.let { put("authorization", "Bearer $it") }
            },
            body,
        )

    private fun router(session: CoachLanSession, sql: LanSqlRunner = FakeSql(), consent: Boolean = true) = CoachLanRouter(
        session, setOf(host),
        coachContext = { CoachLanRouter.CoachContext(consent, false, if (consent) "USER DATA ..." else AiCoach.NO_CONSENT_NOTE) },
        sql = sql,
    )

    private fun pairedToken(session: CoachLanSession, r: CoachLanRouter): String {
        val pair = r.handle(req("POST", "/v1/pair", "{\"client\":\"test\"}"), peer)
        assertEquals(202, pair.status)
        val id = JSONObject(pair.body).getString("request_id")
        assertEquals("pending", JSONObject(r.handle(req("GET", "/v1/pair/$id"), peer).body).getString("status"))
        session.approve(id)
        val polled = JSONObject(r.handle(req("GET", "/v1/pair/$id"), peer).body)
        assertEquals("approved", polled.getString("status"))
        return polled.getString("token")
    }

    @Test fun usageIsOpenButDataNeedsAnApprovedToken() {
        val session = CoachLanSession(Long.MAX_VALUE)
        val r = router(session)
        assertEquals(200, r.handle(req("GET", "/"), peer).status)
        assertEquals(401, r.handle(req("GET", "/v1/coach/context"), peer).status)
        assertEquals(401, r.handle(req("POST", "/v1/sql", "{\"sql\":\"SELECT 1\"}"), peer).status)
        val token = pairedToken(session, r)
        val ctx = r.handle(req("GET", "/v1/coach/context", token = token), peer)
        assertEquals(200, ctx.status)
        assertEquals("USER DATA ...", JSONObject(ctx.body).getString("context"))
        // The same token from another address is refused.
        assertEquals(401, r.handle(req("GET", "/v1/coach/context", token = token), "192.168.1.99").status)
        assertEquals(1, session.snapshot().queriesServed)
    }

    @Test fun coachContextFollowsConsent() {
        val session = CoachLanSession(Long.MAX_VALUE)
        val r = router(session, consent = false)
        val token = pairedToken(session, r)
        val body = JSONObject(r.handle(req("GET", "/v1/coach/context", token = token), peer).body)
        assertFalse(body.getBoolean("consent"))
        assertEquals(AiCoach.NO_CONSENT_NOTE, body.getString("context"))
    }

    @Test fun sqlGoesThroughTheGuardAndTheLimitClamp() {
        val session = CoachLanSession(Long.MAX_VALUE)
        val sql = FakeSql()
        val r = router(session, sql)
        val token = pairedToken(session, r)
        assertEquals(200, r.handle(req("POST", "/v1/sql", "{\"sql\":\"SELECT 1;\",\"limit\":999999}", token), peer).status)
        assertEquals(Triple("SELECT 1", true, CoachLanRouter.MAX_LIMIT), sql.seen.last())
        assertEquals(200, r.handle(req("GET", "/v1/sql?q=EXPLAIN%20SELECT%201", token = token), peer).status)
        assertEquals(Triple("EXPLAIN SELECT 1", false, CoachLanRouter.DEFAULT_LIMIT), sql.seen.last())
        assertEquals(400, r.handle(req("POST", "/v1/sql", "{\"sql\":\"DELETE FROM x\"}", token), peer).status)
        assertEquals(400, r.handle(req("POST", "/v1/sql", "not json", token), peer).status)
        assertEquals(2, sql.seen.size)
    }

    @Test fun refusesForeignHostsAndExpiredWindows() {
        val session = CoachLanSession(Long.MAX_VALUE)
        val r = router(session)
        assertEquals(421, r.handle(req("GET", "/", hostHeader = "evil.example:8765"), peer).status)
        assertEquals(421, r.handle(req("GET", "/", hostHeader = null), peer).status)
        assertEquals(200, r.handle(req("GET", "/", hostHeader = "pixel-9.local:8765"), peer).status)
        assertEquals(421, r.handle(req("GET", "/", hostHeader = "pixel-9.local:9999"), peer).status)
        assertEquals(421, r.handle(req("GET", "/", hostHeader = ".local:8765"), peer).status)
        var now = 0L
        val ending = CoachLanSession(expiresAtMillis = 1_000L, now = { now })
        val r2 = router(ending)
        val usage = JSONObject(r2.handle(req("GET", "/"), peer).body)
        assertEquals(1L, usage.getLong("expires_in_s"))
        now = 1_000L
        assertEquals(410, r2.handle(req("GET", "/"), peer).status)
    }

    // MARK: - Address choice

    @Test fun picksWifiNeverCellular() {
        assertEquals(
            "192.168.1.5",
            CoachLanServer.pickLanAddress(listOf("rmnet_data0" to "10.12.0.4", "wlan0" to "192.168.1.5")),
        )
        assertNull(CoachLanServer.pickLanAddress(listOf("rmnet_data0" to "10.12.0.4", "tun0" to "100.77.1.1")))
        assertNull(CoachLanServer.pickLanAddress(listOf("wlan0" to "8.8.8.8")))
        assertEquals("10.0.0.9", CoachLanServer.pickLanAddress(listOf("eth0" to "10.0.0.9")))
    }

    @Test fun countdownFormat() {
        assertEquals("10:00", com.noop.ui.formatRemaining(600_000))
        assertEquals("0:01", com.noop.ui.formatRemaining(1))
        assertEquals("0:00", com.noop.ui.formatRemaining(-5))
    }
}
