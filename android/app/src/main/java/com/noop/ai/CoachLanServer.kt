package com.noop.ai

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat
import com.noop.NoopApplication
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * "Let a computer query my data": a ten-minute, Wi-Fi-only HTTP server the wearer starts from the
 * coach page, so an agent on a computer on the same network can read what the AI coach is allowed to
 * see and run read-only SQL over the app database.
 *
 * FORK-ONLY. Upstream's scope (AGENTS.md) has no listener of any kind; this is a deliberate
 * departure, kept as narrow as it can be:
 *  - nothing listens until the wearer taps Start, and it stops by itself after [WINDOW_MILLIS];
 *  - it binds the Wi-Fi address only (never cellular), and drops peers outside private ranges;
 *  - each computer must be allowed on the phone before it gets a token, and tokens die with the window;
 *  - SQL runs on a separate `OPEN_READONLY`, `query_only` connection behind [ReadOnlySqlGuard].
 *
 * Process lifetime while the phone is locked comes from [CoachLanService], a foreground service the
 * coach card starts alongside this.
 */
object CoachLanServer {
    const val WINDOW_MILLIS = 10 * 60 * 1000L
    const val PREFERRED_PORT = 8765
    private const val TAG = "CoachLan"
    private const val NSD_TYPE = "_noop-coach._tcp"

    enum class StartError { NO_WIFI, BIND_FAILED }

    /** What the coach card and the notification render. [expiresAtMillis] is the one deadline. */
    data class State(
        val running: Boolean = false,
        val url: String? = null,
        val expiresAtMillis: Long = 0L,
        val pending: List<PendingRequest> = emptyList(),
        val approved: List<ApprovedClient> = emptyList(),
        val queriesServed: Int = 0,
        val lastError: StartError? = null,
    )

    data class PendingRequest(val id: String, val client: String, val remoteAddress: String)
    data class ApprovedClient(val client: String, val remoteAddress: String)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var running: Running? = null

    private class Running(
        val socket: ServerSocket,
        val session: CoachLanSession,
        val sql: LanSqlRunner,
        val workers: ExecutorService,
        val timer: ScheduledExecutorService,
        val url: String,
        var nsd: NsdManager.RegistrationListener? = null,
    )

    /**
     * Starts a window (or keeps the current one) and the foreground service that holds it. Does
     * network setup on the caller's thread; call off the main thread. Returns null on success.
     */
    fun start(context: Context): StartError? {
        val app = context.applicationContext
        synchronized(lock) {
            if (running != null) return null
            val address = pickLanAddress(lanCandidates())
            if (address == null) {
                _state.value = State(lastError = StartError.NO_WIFI)
                return StartError.NO_WIFI
            }
            val socket = runCatching { bind(address, PREFERRED_PORT) }
                .recoverCatching { bind(address, 0) }
                .getOrElse {
                    Log.w(TAG, "could not bind a port on the Wi-Fi address", it)
                    _state.value = State(lastError = StartError.BIND_FAILED)
                    return StartError.BIND_FAILED
                }
            val port = socket.localPort
            val session = CoachLanSession(System.currentTimeMillis() + WINDOW_MILLIS)
            val sql = AndroidReadOnlySql(app.getDatabasePath(WhoopDatabase.DB_NAME).path)
            val r = Running(
                socket = socket,
                session = session,
                sql = sql,
                workers = Executors.newFixedThreadPool(4) { Thread(it, "noop-lan-conn").apply { isDaemon = true } },
                timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "noop-lan-timer").apply { isDaemon = true } },
                url = "http://$address:$port",
            )
            val router = CoachLanRouter(
                session = session,
                allowedHosts = setOf("$address:$port"),
                coachContext = { coachContext(app) },
                sql = sql,
                onChange = { publish() },
            )
            running = r
            Thread({ acceptLoop(r, router) }, "noop-lan-accept").apply { isDaemon = true }.start()
            r.timer.schedule({ stop(app) }, WINDOW_MILLIS, TimeUnit.MILLISECONDS)
            r.nsd = registerNsd(app, port)
            // Rare-event evidence, always on: that a listener opened and where. Never the token.
            Log.i(TAG, "computer-query window opened on ${r.url} for ${WINDOW_MILLIS / 60_000} min")
        }
        publish()
        runCatching { ContextCompat.startForegroundService(app, CoachLanService.startIntent(app)) }
            .onFailure { Log.w(TAG, "foreground service did not start; the window may close if the app is backgrounded", it) }
        return null
    }

    fun stop(context: Context) {
        val r = synchronized(lock) { running.also { running = null } } ?: return
        runCatching { r.socket.close() }
        r.workers.shutdownNow()
        r.timer.shutdownNow()
        runCatching { r.sql.close() }
        r.nsd?.let { listener ->
            runCatching {
                (context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(listener)
            }
        }
        Log.i(TAG, "computer-query window closed (${r.session.snapshot().queriesServed} queries served)")
        _state.value = State()
    }

    fun approve(id: String) {
        val r = synchronized(lock) { running } ?: return
        if (r.session.approve(id)) publish()
    }

    fun deny(id: String) {
        val r = synchronized(lock) { running } ?: return
        if (r.session.deny(id)) publish()
    }

    private fun publish() {
        val r = synchronized(lock) { running } ?: return
        val snap = r.session.snapshot()
        _state.value = State(
            running = true,
            url = r.url,
            expiresAtMillis = r.session.expiresAtMillis,
            pending = snap.pending.map { PendingRequest(it.id, it.client, it.remoteAddress) },
            approved = snap.approved.map { ApprovedClient(it.client, it.remoteAddress) },
            queriesServed = snap.queriesServed,
        )
    }

    private fun bind(address: String, port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(address), port), 16)
    }

    private fun acceptLoop(r: Running, router: CoachLanRouter) {
        while (!r.socket.isClosed) {
            val client = try { r.socket.accept() } catch (_: Exception) { break }
            val peer = client.inetAddress?.hostAddress.orEmpty().substringBefore('%')
            if (!AiCoach.isPrivateLanOrLoopback(peer)) {
                runCatching { client.close() }
                continue
            }
            runCatching { r.workers.execute { serve(client, peer, router) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(socket: Socket, peer: String, router: CoachLanRouter) {
        socket.use { s ->
            s.soTimeout = 10_000
            val response = try {
                router.handle(LanHttp.parse(s.getInputStream()), peer)
            } catch (e: LanHttpException) {
                LanHttpResponse(e.status, org.json.JSONObject().put("error", e.message).toString())
            } catch (_: SocketTimeoutException) {
                LanHttpResponse(408, "{\"error\":\"request timed out\"}")
            } catch (e: Exception) {
                Log.w(TAG, "request from $peer failed", e)
                LanHttpResponse(500, "{\"error\":\"internal error\"}")
            }
            runCatching { LanHttp.write(s.getOutputStream(), response) }
        }
    }

    /**
     * What the coach card copies for the wearer to paste into their agent. Addressed to the agent, so
     * it is deliberately not localized: it names endpoints, not UI.
     */
    fun agentInstructions(url: String): String =
        "My phone's NOOP app is serving my health data read-only on the local network at $url for the " +
            "next few minutes. Start with `curl $url/`: it lists the endpoints and how to pair. Pair with " +
            "`POST $url/v1/pair` and a JSON body {\"client\":\"<your name>\"}, wait while I approve it on " +
            "the phone (poll the returned URL), then send the token as `Authorization: Bearer <token>`. " +
            "GET /v1/coach/context is what my AI coach can see; GET /v1/schema and POST /v1/sql " +
            "{\"sql\":\"SELECT ...\"} query the app database read-only."

    /** The coach's own funnel ([AiCoach.coachDataContext]) under the coach's own consent settings. */
    private fun coachContext(app: Context): CoachLanRouter.CoachContext {
        val consent = AiKeyStore.readConsent(app)
        val includeSignals = consent && NoopPrefs.coachSignals(app)
        val coach = AiCoach(
            WhoopRepository(WhoopDatabase.get(app)),
            activeStrapId = { (app as? NoopApplication)?.activeDeviceId ?: WhoopRepository.WHOOP_SOURCE },
        )
        val text = runBlocking { coach.coachDataContext(app, consent, includeSignals) }
        return CoachLanRouter.CoachContext(consent, includeSignals, text)
    }

    /** Advertises the window over mDNS so an agent can find the phone with `dns-sd -B _noop-coach._tcp`. */
    private fun registerNsd(app: Context, port: Int): NsdManager.RegistrationListener? = runCatching {
        val manager = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed ($errorCode); the address on the coach card still works")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        val info = NsdServiceInfo().apply {
            serviceName = "NOOP coach"
            serviceType = NSD_TYPE
            setPort(port)
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        listener
    }.getOrNull()

    private fun lanCandidates(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress.orEmpty() }
            }
    }.getOrDefault(emptyList())

    /**
     * Picks the Wi-Fi (or wired) IPv4 address to bind, from (interface name, address) pairs. Cellular
     * (`rmnet*`, `ccmni*`), VPN (`tun*`, `ppp*`) and anything not in a private range are never chosen,
     * so the server cannot end up reachable from the carrier side. Pure; unit-tested.
     */
    internal fun pickLanAddress(candidates: List<Pair<String, String>>): String? {
        val usable = candidates.filter { (name, addr) ->
            val n = name.lowercase()
            (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("swlan")) &&
                AiCoach.isPrivateLanOrLoopback(addr) && !addr.startsWith("127.") && !addr.startsWith("169.254.")
        }
        return (usable.firstOrNull { it.first.lowercase().startsWith("wlan") } ?: usable.firstOrNull())?.second
    }
}
