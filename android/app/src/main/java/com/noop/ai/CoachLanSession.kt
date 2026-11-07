package com.noop.ai

import java.security.SecureRandom

/**
 * One computer-query window: who has asked, who has been let in, and when it all ends.
 *
 * Pure state (no sockets, no Android), driven by [CoachLanServer] and unit-tested. The window has ONE
 * deadline, [expiresAtMillis]; the server, the foreground notification and the coach card all count
 * down from it rather than each keeping their own.
 *
 * Access is per computer and approved on the phone: a computer posts a pairing request, the wearer
 * sees its address and name on the coach page and allows or denies it, and an allowed computer
 * collects a bearer token by polling. Tokens die with the window.
 */
internal class CoachLanSession(
    val expiresAtMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Request(
        val id: String,
        val client: String,
        val remoteAddress: String,
        val requestedAtMillis: Long,
    )

    data class Client(val client: String, val remoteAddress: String, val approvedAtMillis: Long)

    enum class Status { PENDING, APPROVED, DENIED, UNKNOWN }

    data class Snapshot(
        val pending: List<Request>,
        val approved: List<Client>,
        val queriesServed: Int,
    )

    private val lock = Any()
    private val pending = LinkedHashMap<String, Request>()
    private val denied = HashMap<String, String>() // request id -> remote address
    private val grants = HashMap<String, Pair<String, String>>() // request id -> (token, remote address)
    private val tokens = HashMap<String, Client>() // token -> client
    private var queries = 0

    fun isExpired(): Boolean = now() >= expiresAtMillis

    /** Time left on the window, on the session's own clock; never negative. */
    fun remainingMillis(): Long = (expiresAtMillis - now()).coerceAtLeast(0)

    /**
     * Records a pairing request. Returns null when too many are waiting (the wearer has not answered
     * them yet), so a noisy device on the network cannot stack up dialogs. One waiting request per
     * address: asking again returns the one already waiting.
     */
    fun requestAccess(clientName: String, remoteAddress: String): Request? = synchronized(lock) {
        pending.values.firstOrNull { it.remoteAddress == remoteAddress }?.let { return it }
        if (pending.size >= MAX_PENDING) return null
        val request = Request(newId(16), sanitizeClient(clientName), remoteAddress, now())
        pending[request.id] = request
        request
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        val request = pending.remove(id) ?: return false
        val token = newId(32)
        grants[id] = token to request.remoteAddress
        tokens[token] = Client(request.client, request.remoteAddress, now())
        true
    }

    fun deny(id: String): Boolean = synchronized(lock) {
        val request = pending.remove(id) ?: return false
        denied[id] = request.remoteAddress
        true
    }

    /**
     * The state of pairing request [id] as seen from [remoteAddress]. The token is handed only to the
     * address that asked, so a request id overheard on the network is worth nothing elsewhere.
     */
    fun poll(id: String, remoteAddress: String): Pair<Status, String?> = synchronized(lock) {
        pending[id]?.let { return (if (it.remoteAddress == remoteAddress) Status.PENDING else Status.UNKNOWN) to null }
        grants[id]?.let { (token, addr) ->
            return if (addr == remoteAddress) Status.APPROVED to token else Status.UNKNOWN to null
        }
        denied[id]?.let { addr -> return (if (addr == remoteAddress) Status.DENIED else Status.UNKNOWN) to null }
        Status.UNKNOWN to null
    }

    /** True when [token] was issued in this window to [remoteAddress]. */
    fun authorize(token: String?, remoteAddress: String): Boolean = synchronized(lock) {
        if (token.isNullOrEmpty()) return false
        val client = tokens[token] ?: return false
        client.remoteAddress == remoteAddress
    }

    fun recordQuery() = synchronized(lock) { queries++ }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(pending.values.toList(), tokens.values.sortedBy { it.approvedAtMillis }, queries)
    }

    private fun newId(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_PENDING = 4

        /** What the dialog shows as the computer's name: printable, single-line, bounded. */
        fun sanitizeClient(raw: String): String {
            val cleaned = raw.filter { !it.isISOControl() }.trim().take(40)
            return cleaned.ifEmpty { "unnamed agent" }
        }
    }
}
