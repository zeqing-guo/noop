package com.noop.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Routes one computer-query request. Pure over its collaborators, so the whole HTTP surface is
 * unit-tested without a socket or a database.
 *
 * Unauthenticated: `GET /` (usage) and pairing (`POST /v1/pair`, `GET /v1/pair/<id>`). Everything
 * else needs `Authorization: Bearer <token>` from a pairing the wearer allowed on the phone.
 *
 * `Host` must name the address the server is bound to (or a `.local` mDNS name on its port). A web
 * page the computer happens to have open could otherwise rebind its own domain to the phone's address
 * and read replies as same-origin.
 */
internal class CoachLanRouter(
    private val session: CoachLanSession,
    private val allowedHosts: Set<String>,
    private val coachContext: () -> CoachContext,
    private val sql: LanSqlRunner,
    private val onChange: () -> Unit = {},
) {
    data class CoachContext(val consent: Boolean, val includeSignals: Boolean, val text: String)

    fun handle(req: LanHttpRequest, remoteAddress: String): LanHttpResponse {
        if (session.isExpired()) return error(410, "this access window has ended; start a new one on the phone")
        val host = req.headers["host"]?.lowercase()
        if (host == null || !hostAllowed(host)) return error(421, "unexpected Host header")

        val path = req.path.trimEnd('/').ifEmpty { "/" }
        return when {
            path == "/" -> if (req.method == "GET") ok(usage()) else methodNotAllowed()
            path == "/v1/pair" -> if (req.method == "POST") pair(req, remoteAddress) else methodNotAllowed()
            path.startsWith("/v1/pair/") -> if (req.method == "GET") poll(path.removePrefix("/v1/pair/"), remoteAddress) else methodNotAllowed()
            path == "/v1/coach/context" || path == "/v1/schema" || path == "/v1/sql" -> {
                val token = req.headers["authorization"]?.let {
                    if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null
                }
                if (!session.authorize(token, remoteAddress)) {
                    return error(401, "pair first: POST /v1/pair, approve on the phone, then send the token as 'Authorization: Bearer <token>'")
                }
                when (path) {
                    "/v1/coach/context" -> if (req.method == "GET") counted { context() } else methodNotAllowed()
                    "/v1/schema" -> if (req.method == "GET") counted { ok(sql.schema()) } else methodNotAllowed()
                    else -> when (req.method) {
                        "POST" -> counted { runSql(req) }
                        "GET" -> counted { runSql(req) }
                        else -> methodNotAllowed()
                    }
                }
            }
            else -> error(404, "no such endpoint; GET / lists them")
        }
    }

    /** The bound address, or an mDNS `.local` name on the bound port (what `dns-sd` resolution yields). */
    private fun hostAllowed(host: String): Boolean {
        if (host in allowedHosts) return true
        val name = host.substringBeforeLast(':', "")
        val port = host.substringAfterLast(':', "")
        return name.endsWith(".local") && name.length > ".local".length &&
            allowedHosts.any { it.substringAfterLast(':') == port }
    }

    private fun usage(): JSONObject = JSONObject()
        .put("service", "noop-coach-lan")
        .put("version", 1)
        .put("expires_at_ms", session.expiresAtMillis)
        .put("expires_in_s", session.remainingMillis() / 1000)
        .put(
            "pairing",
            "POST /v1/pair with {\"client\":\"<your name>\"}; the wearer allows it on the phone; poll " +
                "GET /v1/pair/<request_id> until status is approved, then send 'Authorization: Bearer <token>'.",
        )
        .put(
            "endpoints",
            JSONArray()
                .put("GET /v1/coach/context: the data the AI coach is currently allowed to see, as the text it is sent")
                .put("GET /v1/schema: tables and columns of the app database")
                .put("POST /v1/sql {\"sql\":\"SELECT ...\",\"limit\":200}: one read-only SELECT/WITH/VALUES/EXPLAIN; also GET /v1/sql?q=...&limit=...")
        )
        .put("limits", JSONObject().put("default_rows", DEFAULT_LIMIT).put("max_rows", MAX_LIMIT))
        .put("notes", "Timestamps in the database are mostly Unix seconds; days are YYYY-MM-DD local dates.")

    private fun pair(req: LanHttpRequest, remoteAddress: String): LanHttpResponse {
        val client = runCatching { JSONObject(req.body.ifBlank { "{}" }).optString("client", "") }.getOrDefault("")
        val request = session.requestAccess(client, remoteAddress)
            ?: return error(429, "too many requests are waiting for an answer on the phone")
        onChange()
        return LanHttpResponse(
            202,
            JSONObject()
                .put("request_id", request.id)
                .put("status", "pending")
                .put("poll", "/v1/pair/${request.id}")
                .put("message", "Waiting for the wearer to allow '${request.client}' on the phone.")
                .toString(),
        )
    }

    private fun poll(id: String, remoteAddress: String): LanHttpResponse {
        val (status, token) = session.poll(id, remoteAddress)
        return when (status) {
            CoachLanSession.Status.PENDING -> ok(JSONObject().put("status", "pending"))
            CoachLanSession.Status.APPROVED -> ok(
                JSONObject().put("status", "approved").put("token", token).put("expires_at_ms", session.expiresAtMillis),
            )
            CoachLanSession.Status.DENIED -> error(403, "the wearer denied this request", JSONObject().put("status", "denied"))
            CoachLanSession.Status.UNKNOWN -> error(404, "unknown pairing request")
        }
    }

    private fun context(): LanHttpResponse {
        val c = coachContext()
        return ok(
            JSONObject()
                .put("consent", c.consent)
                .put("include_signals", c.includeSignals)
                .put("context", c.text),
        )
    }

    private fun runSql(req: LanHttpRequest): LanHttpResponse {
        val (text, limitRaw) = if (req.method == "POST") {
            val body = runCatching { JSONObject(req.body) }.getOrNull()
                ?: return error(400, "body must be JSON: {\"sql\": \"SELECT ...\"}")
            body.optString("sql", "") to (if (body.has("limit")) body.optInt("limit", DEFAULT_LIMIT) else DEFAULT_LIMIT)
        } else {
            req.query["q"].orEmpty() to (req.query["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
        }
        val limit = limitRaw.coerceIn(1, MAX_LIMIT)
        return when (val verdict = ReadOnlySqlGuard.check(text)) {
            is ReadOnlySqlGuard.Verdict.Rejected -> error(400, verdict.reason)
            is ReadOnlySqlGuard.Verdict.Ok -> try {
                ok(sql.query(verdict.sql, verdict.wrappable, limit))
            } catch (e: Exception) {
                error(400, "query failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private inline fun counted(block: () -> LanHttpResponse): LanHttpResponse {
        val response = block()
        if (response.status == 200) {
            session.recordQuery()
            onChange()
        }
        return response
    }

    private fun ok(json: JSONObject) = LanHttpResponse(200, json.toString())
    private fun methodNotAllowed() = error(405, "method not allowed")
    private fun error(status: Int, message: String, extra: JSONObject = JSONObject()) =
        LanHttpResponse(status, extra.put("error", message).toString())

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 5_000
    }
}
