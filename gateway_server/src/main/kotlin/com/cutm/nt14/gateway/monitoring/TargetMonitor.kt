package com.cutm.nt14.gateway.monitoring

import com.cutm.nt14.gateway.core.TrafficHistoryManager
import com.cutm.nt14.gateway.core.WebSocketManager
import com.cutm.nt14.gateway.db.IncidentStore
import com.cutm.nt14.gateway.db.ProbeStore
import com.cutm.nt14.gateway.db.TargetStore
import com.cutm.nt14.gateway.models.GatewayEvent
import com.cutm.nt14.gateway.models.IncidentDto
import com.cutm.nt14.gateway.models.MonitoredTarget
import com.cutm.nt14.gateway.models.TargetStatusDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Probes every enabled monitored target on its own interval and stores the outcome in probe_results.
 *
 * - A probe is "up" when the response status equals the target's expected status.
 * - After a failed probe the next probe runs after min(interval, [failRetrySec]) so that
 *   3 consecutive failures (=> "Target Down" incident) are detected well within 90 s.
 * - Up/down transitions are stored on the probe row ("up->down" / "down->up") and the open
 *   incident is resolved when the target recovers.
 * - Every probe emits a `target_status` WebSocket event.
 */
class TargetMonitor(
    private val targets: TargetStore,
    private val probes: ProbeStore,
    private val incidents: IncidentStore,
    private val ws: WebSocketManager?,
    private val history: TrafficHistoryManager?,
    val failThreshold: Int = 3,
    private val failRetrySec: Int = 10,
    private val refreshTargetsMs: Long = 10_000L
) {
    private val logger = LoggerFactory.getLogger(TargetMonitor::class.java)

    private class State(
        var lastUp: Boolean?,
        var consecutiveFailures: Int,
        var openIncidentId: String?,
        var nextDueAt: Long = 0L,
        @Volatile var inFlight: Boolean = false
    )

    private val states = ConcurrentHashMap<Long, State>()
    @Volatile private var cachedTargets: List<MonitoredTarget> = emptyList()
    @Volatile private var lastRefresh = 0L

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Call after CRUD changes so the scheduler picks them up immediately. */
    fun invalidate(targetId: Long? = null) {
        lastRefresh = 0L
        if (targetId != null) states.remove(targetId)
    }

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastRefresh > refreshTargetsMs) {
                        cachedTargets = targets.all()
                        lastRefresh = now
                        states.keys.retainAll(cachedTargets.map { it.id }.toSet())
                    }
                    for (t in cachedTargets) {
                        if (!t.enabled) continue
                        val st = stateFor(t.id)
                        if (st.inFlight || now < st.nextDueAt) continue
                        st.inFlight = true
                        launch {
                            try { probeOnce(t) } catch (e: Exception) {
                                logger.warn("Probe crashed for ${t.name}: ${e.message}")
                            } finally { st.inFlight = false }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Monitor loop error: ${e.message}")
                }
                delay(1_000L)
            }
        }
    }

    private fun stateFor(targetId: Long): State = states.computeIfAbsent(targetId) {
        // Restore from storage so transitions and incidents survive restarts.
        val recent = runCatching { probes.recent(targetId, 10) }.getOrDefault(emptyList())
        State(
            lastUp = recent.firstOrNull()?.up,
            consecutiveFailures = recent.takeWhile { !it.up }.size,
            openIncidentId = runCatching { incidents.openForTarget(targetId) }.getOrNull()
        )
    }

    /** Executes one probe, persists it and applies incident logic. Returns the new status. */
    suspend fun probeOnce(t: MonitoredTarget): TargetStatusDto {
        val st = stateFor(t.id)
        val started = System.currentTimeMillis()
        var code: Int? = null
        var error: String? = null
        try {
            val req = HttpRequest.newBuilder(URI(t.url))
                .timeout(Duration.ofMillis(t.timeoutMs.toLong()))
                .header("User-Agent", "NT14-Gateway-Monitor/1.0")
                .method(t.method.uppercase(), HttpRequest.BodyPublishers.noBody())
                .build()
            code = withContext(Dispatchers.IO) { http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() }
            if (code != t.expectedStatus) error = "expected ${t.expectedStatus}, got $code"
        } catch (e: Exception) {
            error = (e::class.simpleName ?: "error") + (e.message?.let { ": $it" } ?: "")
        }
        val latency = System.currentTimeMillis() - started
        val up = code == t.expectedStatus
        val now = System.currentTimeMillis()

        val transition = when {
            st.lastUp == true && !up -> "up->down"
            st.lastUp == false && up -> "down->up"
            else -> null
        }
        st.lastUp = up
        st.consecutiveFailures = if (up) 0 else st.consecutiveFailures + 1
        st.nextDueAt = now + 1000L * (if (up) t.intervalSec else minOf(t.intervalSec, failRetrySec))

        probes.insert(t.id, ProbeStore.ProbeRow(now, up, code, latency, error, transition))

        if (!up && st.consecutiveFailures >= failThreshold && st.openIncidentId == null) {
            val incident = IncidentDto(
                id = "inc_" + UUID.randomUUID().toString().take(8),
                type = "Target Down",
                severity = "CRITICAL",
                detail = "${t.name} (${t.method} ${t.url}) failed ${st.consecutiveFailures} consecutive probes: ${error ?: "unexpected status"}",
                timestamp = now,
                targetId = t.id
            )
            incidents.insert(incident, t.id)
            st.openIncidentId = incident.id
            history?.pushIncident(incident)
            ws?.broadcast(GatewayEvent(type = "incident", incident = incident))
            logger.warn("INCIDENT ${incident.id}: ${incident.detail}")
        } else if (up && st.openIncidentId != null) {
            val id = st.openIncidentId!!
            incidents.resolve(id, now)
            st.openIncidentId = null
            val recovered = IncidentDto(
                id = id, type = "Target Down", severity = "CRITICAL",
                detail = "${t.name} recovered after downtime", timestamp = now, targetId = t.id, resolvedAt = now
            )
            ws?.broadcast(GatewayEvent(type = "incident_resolved", incident = recovered))
            logger.info("Incident $id resolved: ${t.name} is back up")
        }

        val status = statusFor(t, transition)
        ws?.broadcast(GatewayEvent(type = "target_status", target = status))
        return status
    }

    fun statusFor(t: MonitoredTarget, transition: String? = null): TargetStatusDto {
        val st = stateFor(t.id)
        val recent = probes.recent(t.id, 30)
        val last = recent.firstOrNull()
        return TargetStatusDto(
            id = t.id, name = t.name, url = t.url, method = t.method,
            expectedStatus = t.expectedStatus, intervalSec = t.intervalSec, enabled = t.enabled,
            source = t.source, synthetic = t.synthetic,
            up = last?.up,
            lastStatusCode = last?.statusCode,
            lastLatencyMs = last?.latencyMs,
            lastError = last?.error,
            lastCheckedAt = last?.ts,
            consecutiveFailures = st.consecutiveFailures,
            uptimePct24h = probes.uptimePct(t.id, System.currentTimeMillis() - 86_400_000L),
            recentLatencies = recent.reversed().map { it.latencyMs },
            recentUp = recent.reversed().map { it.up },
            openIncidentId = st.openIncidentId,
            transition = transition
        )
    }

    fun statusAll(includeSynthetic: Boolean = false, source: String? = null): List<TargetStatusDto> =
        targets.all()
            .filter { t ->
                val synOk = includeSynthetic || !t.synthetic
                val srcOk = source.isNullOrBlank() || t.source.equals(source.trim(), ignoreCase = true)
                synOk && srcOk
            }
            .map { statusFor(it) }
}
