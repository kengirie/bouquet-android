package io.github.kengirie.bouquet.viewer

import fi.iki.elonen.NanoHTTPD
import io.github.kengirie.bouquet.config.Defaults
import io.github.kengirie.bouquet.core.GatewayDeps
import io.github.kengirie.bouquet.core.GatewayError
import io.github.kengirie.bouquet.core.ResolvedSiteResource
import io.github.kengirie.bouquet.core.resolveSiteResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Identity of a single viewer session: a NanoHTTPD listener bound to
 * `127.0.0.1:<port>` that resolves every request through the gateway for
 * the supplied [addressSegment].
 */
data class ViewerSession(
    val id: String,
    val addressSegment: String,
    val port: Int,
)

/**
 * Per-session loopback HTTP gateway. Direct port of desktop
 * `electron/session-registry.ts` — every viewer window/Activity gets its
 * own listener on an OS-assigned ephemeral port so concurrent sessions
 * don't share state and a closed Activity can fully release its port.
 *
 * Thread-safe: `ConcurrentHashMap` for the session map, and `NanoHTTPD`
 * itself spawns a request thread per connection so [resolveSiteResource]
 * is invoked off-main.
 *
 * Browser sessions (no owning Activity) opt into an idle-timeout sweep by
 * passing `idleTimeout = true` to [createSession]; WebView sessions keep
 * the default and rely on Activity lifecycle to call [closeSession].
 */
class SessionRegistry(
    private val deps: GatewayDeps,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMs: Long = Defaults.BROWSER_SESSION_IDLE_TIMEOUT_MS,
    sweepIntervalMs: Long = Defaults.BROWSER_SESSION_SWEEP_INTERVAL_MS,
) {
    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    // Daemon scheduler so it never blocks JVM shutdown. Disabled (null) when
    // sweepIntervalMs <= 0 so unit tests can drive sweepIdleSessions()
    // deterministically without a background ticker.
    private val scheduler: ScheduledExecutorService? = if (sweepIntervalMs > 0) {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bouquet-session-sweeper").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(
                { runCatching { sweepIdleSessions() } },
                sweepIntervalMs,
                sweepIntervalMs,
                TimeUnit.MILLISECONDS,
            )
        }
    } else {
        null
    }

    fun createSession(addressSegment: String, idleTimeout: Boolean = false): ViewerSession {
        val id = UUID.randomUUID().toString()
        val server = SessionServer(addressSegment, deps, clock)
        // Port 0 → OS picks; `listeningPort` reads back the assigned value
        // after start() returns successfully.
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        val session = ViewerSession(id, addressSegment, server.listeningPort)
        sessions[id] = SessionEntry(server, idleTimeout)
        return session
    }

    fun closeSession(id: String) {
        sessions.remove(id)?.server?.stop()
    }

    fun closeAllSessions() {
        val snapshot = sessions.toMap()
        sessions.clear()
        snapshot.values.forEach { runCatching { it.server.stop() } }
        scheduler?.shutdownNow()
    }

    /** Test hook: number of sessions currently tracked. */
    internal val activeSessionCount: Int
        get() = sessions.size

    /**
     * Closes any idle-timeout-eligible session whose last request is older
     * than [idleTimeoutMs]. Exposed for tests; the background scheduler
     * calls this on a fixed cadence.
     */
    fun sweepIdleSessions(nowMs: Long = clock()) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (!entry.idleEligible) continue
            if (nowMs - entry.server.lastRequestAtMs <= idleTimeoutMs) continue
            iterator.remove()
            runCatching { entry.server.stop() }
        }
    }

    private class SessionEntry(val server: SessionServer, val idleEligible: Boolean)
}

/**
 * NanoHTTPD listener for a single [addressSegment]. Bridges the synchronous
 * `serve()` interface to the suspending [resolveSiteResource] via
 * `runBlocking(Dispatchers.IO) { ... }` (NanoHTTPD's default async runner
 * already spawns a thread per request, so blocking here is fine).
 */
private class SessionServer(
    private val addressSegment: String,
    private val deps: GatewayDeps,
    private val clock: () -> Long,
) : NanoHTTPD("127.0.0.1", 0) {

    @Volatile
    var lastRequestAtMs: Long = clock()
        private set

    override fun serve(session: IHTTPSession): Response {
        lastRequestAtMs = clock()
        val method = session.method
        val path = session.uri.ifEmpty { "/" }

        if (method != Method.GET && method != Method.HEAD) {
            val html = renderErrorHtml(
                status = 405,
                statusText = "Method Not Allowed",
                detail = "Method ${method?.name ?: "?"} is not supported.",
                hint = hintForStatus(405),
                path = path,
            )
            return errorResponse(Response.Status.METHOD_NOT_ALLOWED, html).apply {
                addHeader("Allow", "GET, HEAD")
            }
        }

        val resource: ResolvedSiteResource = try {
            runBlocking(Dispatchers.IO) {
                resolveSiteResource(addressSegment, path, deps)
            }
        } catch (e: GatewayError) {
            return gatewayErrorResponse(e, path)
        } catch (e: Exception) {
            val html = renderErrorHtml(
                status = 500,
                statusText = "Internal Server Error",
                detail = e.message ?: e.javaClass.simpleName,
                hint = hintForStatus(500),
                path = path,
            )
            return errorResponse(Response.Status.INTERNAL_ERROR, html)
        }

        return successResponse(resource, isHead = method == Method.HEAD)
    }

    private fun successResponse(resource: ResolvedSiteResource, isHead: Boolean): Response {
        val status = Response.Status.lookup(resource.status) ?: Response.Status.OK
        // For HEAD we still send the correct Content-Length but an empty
        // body — NanoHTTPD doesn't strip the body automatically across all
        // versions so we do it explicitly.
        val bodyStream = if (isHead) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            ByteArrayInputStream(resource.body)
        }
        val response = newFixedLengthResponse(
            status,
            resource.contentType,
            bodyStream,
            resource.body.size.toLong(),
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun gatewayErrorResponse(e: GatewayError, path: String): Response {
        val nanoStatus = Response.Status.lookup(e.status) ?: Response.Status.INTERNAL_ERROR
        val html = renderErrorHtml(
            status = e.status,
            statusText = e.statusText,
            detail = e.message ?: e.statusText,
            hint = hintForStatus(e.status),
            path = path,
        )
        return errorResponse(nanoStatus, html)
    }

    private fun errorResponse(status: Response.IStatus, html: String): Response {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val response = newFixedLengthResponse(
            status,
            "text/html; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }
}
