package com.cutm.nt14.gateway.core

import com.cutm.nt14.gateway.models.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class TrafficHistoryManager(private val maxLogs: Int = 1000) {

    private val logsRingBuffer = ConcurrentLinkedDeque<RequestLogDto>()
    private val incidentsList = ConcurrentLinkedDeque<IncidentDto>()
    private val totalAllowed = AtomicLong(0)
    private val totalThrottled = AtomicLong(0)

    /** Durable sinks, set when PostgreSQL persistence is enabled. Must be non-blocking. */
    @Volatile var logSink: ((com.cutm.nt14.gateway.db.LogEntry) -> Unit)? = null
    @Volatile var incidentSink: ((IncidentDto) -> Unit)? = null

    /**
     * Records one gateway decision. `clientId` is the raw limiter key (IP or "user:<email>");
     * only a masked form is kept in memory/WS and only masked + HMAC-hashed forms reach the database.
     */
    fun recordRequest(
        id: String = com.cutm.nt14.gateway.db.LogIdGenerator.next().toString(),
        timestamp: Long = System.currentTimeMillis(),
        clientId: String,
        method: String,
        path: String,
        status: Int,
        latencyMs: Long,
        decision: String,
        source: String = "real",
        upstream: String? = null,
        clientIp: String? = null,
        limiterMs: Long? = null,
        upstreamMs: Long? = null,
        totalMs: Long? = latencyMs
    ): RequestLogDto {
        val privacy = com.cutm.nt14.gateway.db.IpPrivacy
        val ipForHash = clientIp ?: clientId.takeIf { privacy.looksLikeIp(it) }
        val ipHash = ipForHash?.let { privacy.hash(it) }
        val storedClient = privacy.storedClientId(clientId)
        val log = RequestLogDto(
            id = id,
            timestamp = timestamp,
            clientId = storedClient,
            method = method,
            path = path,
            status = status,
            latencyMs = latencyMs,
            decision = decision,
            source = source,
            upstream = upstream,
            clientIpHash = ipHash,
            limiterMs = limiterMs,
            upstreamMs = upstreamMs,
            totalMs = totalMs
        )

        logSink?.let { sink ->
            id.toLongOrNull()?.let { numericId ->
                sink(com.cutm.nt14.gateway.db.LogEntry(numericId, timestamp, storedClient, ipHash, method, path, status, latencyMs, decision, source, upstream, limiterMs, upstreamMs, totalMs))
            }
        }

        logsRingBuffer.addLast(log)
        while (logsRingBuffer.size > maxLogs) {
            logsRingBuffer.pollFirst()
        }

        if (status == 429 || decision != "allowed") {
            totalThrottled.incrementAndGet()
        } else {
            totalAllowed.incrementAndGet()
        }

        return log
    }

    fun recordIncident(
        type: String,
        severity: String = "HIGH",
        detail: String
    ): IncidentDto {
        val incident = IncidentDto(
            id = "inc_" + UUID.randomUUID().toString().take(8),
            type = type,
            severity = severity,
            detail = detail,
            timestamp = System.currentTimeMillis()
        )
        pushIncident(incident)
        runCatching { incidentSink?.invoke(incident) }
        return incident
    }

    /** Adds an incident to the in-memory list only (used when it was already persisted elsewhere). */
    fun pushIncident(incident: IncidentDto) {
        incidentsList.addLast(incident)
        while (incidentsList.size > 50) {
            incidentsList.pollFirst()
        }
    }

    /** Marks an incident as resolved in the in-memory ring buffer. */
    fun resolveIncident(id: String, resolvedAt: Long = System.currentTimeMillis()): IncidentDto? {
        val current = incidentsList.toList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = current[idx].copy(resolvedAt = current[idx].resolvedAt ?: resolvedAt)
        val newList = current.toMutableList().also { it[idx] = updated }
        incidentsList.clear()
        incidentsList.addAll(newList)
        return updated
    }

    fun resolveIncidentsForTarget(targetId: Long, resolvedAt: Long = System.currentTimeMillis()) {
        val updated = incidentsList.map { inc ->
            if (inc.targetId == targetId && inc.resolvedAt == null) inc.copy(resolvedAt = resolvedAt) else inc
        }
        incidentsList.clear()
        incidentsList.addAll(updated)
    }

    @Volatile private var baselineRps: Double = 5.0
    @Volatile private var lastAnomalyCheckTime: Long = 0L

    fun checkAggregateAnomaly(currentRps: Double): IncidentDto? {
        val now = System.currentTimeMillis()
        val window60s = now - 60_000L
        val recentLogs60s = logsRingBuffer.filter { it.timestamp >= window60s }
        val avgRps60s = (recentLogs60s.size / 60.0).coerceAtLeast(1.0)

        // Exponential moving average update
        baselineRps = (0.95 * baselineRps) + (0.05 * avgRps60s)

        // Trigger if current RPS is significantly higher than baseline (at least 2.5x and >= 15 RPS)
        if (currentRps >= (baselineRps * 2.5) && currentRps >= 15.0 && now - lastAnomalyCheckTime > 30_000L) {
            lastAnomalyCheckTime = now
            val surgePct = ((currentRps - baselineRps) / baselineRps * 100.0).roundToInt()
            return recordIncident(
                type = "Aggregate Traffic Anomaly",
                severity = "CRITICAL",
                detail = "Aggregate traffic surge detected: Current RPS %.1f exceeds baseline %.1f by %d%%".format(currentRps, baselineRps, surgePct)
            )
        }
        return null
    }

    fun getRecentLogs(limit: Int = 200, cursor: String? = null, source: String? = null): List<RequestLogDto> {
        val src = com.cutm.nt14.gateway.db.normalizeSource(source)
        val all = logsRingBuffer.toList().reversed().filter { src == null || it.source == src }
        if (cursor.isNullOrBlank()) {
            return all.take(limit)
        }
        val cursorLong = cursor.toLongOrNull()
        val after = if (cursorLong != null) {
            all.filter { (it.id.toLongOrNull() ?: Long.MAX_VALUE) < cursorLong }
        } else {
            val index = all.indexOfFirst { it.id == cursor }
            if (index < 0) all else all.drop(index + 1)
        }
        return after.take(limit)
    }

    fun queryLogs(q: com.cutm.nt14.gateway.db.LogQuery): LogsPage {
        val limit = q.limit.coerceIn(1, 500)
        val src = com.cutm.nt14.gateway.db.normalizeSource(q.source)
        val statusClass = q.status?.trim()?.lowercase()?.let { Regex("^([1-5])xx$").find(it)?.groupValues?.get(1)?.toInt() }
        val statusExact = q.status?.trim()?.toIntOrNull()
        val clientFilter = q.client?.trim()?.takeIf { it.isNotEmpty() }
        val clientIpHash = clientFilter?.let {
            when {
                it.startsWith("iphash:") -> it.removePrefix("iphash:")
                com.cutm.nt14.gateway.db.IpPrivacy.looksLikeIp(it) -> com.cutm.nt14.gateway.db.IpPrivacy.hash(it)
                else -> null
            }
        }
        val filtered = logsRingBuffer.toList().reversed().filter { log ->
            val idLong = log.id.toLongOrNull() ?: Long.MAX_VALUE
            (q.cursor == null || idLong < q.cursor) &&
                (q.fromMs == null || log.timestamp >= q.fromMs) &&
                (q.toMs == null || log.timestamp < q.toMs) &&
                (src == null || log.source == src) &&
                (statusClass == null || (log.status in (statusClass * 100)..<(statusClass * 100 + 100))) &&
                (statusExact == null || log.status == statusExact) &&
                (clientFilter == null || (if (clientIpHash != null) log.clientIpHash == clientIpHash else log.clientId == clientFilter))
        }
        val hasMore = filtered.size > limit
        val items = filtered.take(limit)
        return LogsPage(
            items = items,
            nextCursor = if (hasMore) items.last().id else null,
            limit = limit,
            source = src ?: "all",
            persistence = "memory",
            generatedAt = System.currentTimeMillis()
        )
    }

    fun getLogsSince(lastEventId: String): List<RequestLogDto>? {
        val all = logsRingBuffer.toList()
        val index = all.indexOfFirst { it.id == lastEventId }
        if (index < 0) return null
        return all.subList(index + 1, all.size)
    }

    fun getIncidents(): List<IncidentDto> = incidentsList.toList().reversed()

    fun calculateCurrentMetrics(): GatewayMetrics {
        val now = System.currentTimeMillis()
        val window1s = now - 1000L
        val window60s = now - 60_000L

        val recentLogs1s = logsRingBuffer.filter { it.timestamp >= window1s }
        val recentLogs60s = logsRingBuffer.filter { it.timestamp >= window60s }

        val rps = recentLogs1s.size.toDouble()
        val allowedCount = totalAllowed.get()
        val throttledCount = totalThrottled.get()

        val sampleLogs = if (recentLogs60s.isNotEmpty()) recentLogs60s else logsRingBuffer.toList()
        val latencies = sampleLogs.map { it.latencyMs }.sorted()
        val limiterLatencies = sampleLogs.mapNotNull { it.limiterMs }.sorted()
        val upstreamLatencies = sampleLogs.mapNotNull { it.upstreamMs }.sorted()
        val totalLatencies = sampleLogs.map { it.totalMs ?: it.latencyMs }.sorted()

        fun pct(sorted: List<Long>, q: Double): Long =
            if (sorted.isNotEmpty()) sorted[(sorted.size * q).toInt().coerceAtMost(sorted.lastIndex)] else 0L

        val p50 = pct(latencies, 0.50)
        val p95 = pct(latencies, 0.95)
        val limP50 = pct(limiterLatencies, 0.50)
        val limP95 = pct(limiterLatencies, 0.95)
        val upP50 = pct(upstreamLatencies, 0.50)
        val upP95 = pct(upstreamLatencies, 0.95)
        val totP50 = pct(totalLatencies, 0.50)
        val totP95 = pct(totalLatencies, 0.95)

        val activeClients = recentLogs60s.map { it.clientId }.distinct().size.coerceAtLeast(if (logsRingBuffer.isNotEmpty()) 1 else 0)

        val totalWindow = (allowedCount + throttledCount).toDouble()
        val errorRate = if (totalWindow > 0) (throttledCount.toDouble() / totalWindow) else 0.0

        val endpointCounts = mutableMapOf<String, Long>()
        for (log in sampleLogs) {
            endpointCounts[log.path] = (endpointCounts[log.path] ?: 0L) + 1L
        }

        val isDemo = System.getenv("DEMO_TRAFFIC")?.toBooleanStrictOrNull() ?: false

        return GatewayMetrics(
            rps = (rps * 10).roundToInt() / 10.0,
            allowed = allowedCount,
            throttled = throttledCount,
            errorRate = (errorRate * 1000).roundToInt() / 1000.0,
            p50LatencyMs = p50,
            p95LatencyMs = p95,
            p50LimiterMs = limP50,
            p95LimiterMs = limP95,
            p50UpstreamMs = upP50,
            p95UpstreamMs = upP95,
            p50TotalMs = totP50,
            p95TotalMs = totP95,
            activeClients = activeClients,
            endpointCounts = endpointCounts,
            demoMode = isDemo
        )
    }

    fun generateReport(range: String = "1h", source: String? = null): TrafficReportDto {
        val now = System.currentTimeMillis()
        val durationMs = when (range.lowercase()) {
            "24h" -> 86_400_000L
            "7d" -> 7 * 86_400_000L
            else -> 3_600_000L // 1h default
        }
        val cutoff = now - durationMs
        val src = com.cutm.nt14.gateway.db.normalizeSource(source)
        val filtered = logsRingBuffer.filter { it.timestamp >= cutoff && (src == null || it.source == src) }

        val total = filtered.size.toLong()
        val blocked = filtered.count { it.status == 429 || it.decision != "allowed" }.toLong()
        val serverErrors = filtered.count { it.status >= 500 }.toLong()
        val allowed = total - blocked
        val errorRate = if (total > 0) blocked.toDouble() / total.toDouble() else 0.0

        val latencies = filtered.map { it.latencyMs }.sorted()
        val limLat = filtered.mapNotNull { it.limiterMs }.sorted()
        val upLat = filtered.mapNotNull { it.upstreamMs }.sorted()
        val totLat = filtered.map { it.totalMs ?: it.latencyMs }.sorted()
        fun pct(sorted: List<Long>, q: Double): Long =
            if (sorted.isNotEmpty()) sorted[(sorted.size * q).toInt().coerceAtMost(sorted.lastIndex)] else 0L
        val avgLatency = if (latencies.isNotEmpty()) latencies.average().toLong() else 0L
        val p50 = pct(latencies, 0.50)
        val p95 = pct(latencies, 0.95)

        // Peak RPS over 1s intervals
        val timeBuckets = filtered.groupBy { it.timestamp / 1000L }
        val peakRps = timeBuckets.values.maxOfOrNull { it.size.toDouble() } ?: 0.0

        val topEndpoints = filtered.groupingBy { it.path }.eachCount().mapValues { it.value.toLong() }
        val topClients = filtered.groupingBy { it.clientId }.eachCount().mapValues { it.value.toLong() }

        return TrafficReportDto(
            range = range,
            totalRequests = total,
            allowedRequests = allowed,
            blockedRequests = blocked,
            errorRate = (errorRate * 1000).roundToInt() / 1000.0,
            avgLatencyMs = avgLatency,
            p50LatencyMs = p50,
            p95LatencyMs = p95,
            p50LimiterMs = pct(limLat, 0.50),
            p95LimiterMs = pct(limLat, 0.95),
            p50UpstreamMs = pct(upLat, 0.50),
            p95UpstreamMs = pct(upLat, 0.95),
            p50TotalMs = pct(totLat, 0.50),
            p95TotalMs = pct(totLat, 0.95),
            peakRps = peakRps,
            topEndpoints = topEndpoints,
            topClients = topClients,
            generatedAt = now,
            serverErrors = serverErrors,
            source = src ?: "all",
            persistence = "memory"
        )
    }

    fun buildSnapshot(rules: List<RateLimitRule>, bans: List<ActiveBanDto>, clients: List<ClientInfo> = emptyList()): GatewayEvent {
        return GatewayEvent(
            type = "snapshot",
            metrics = calculateCurrentMetrics(),
            rules = rules,
            activeBans = bans,
            logs = getRecentLogs(200),
            incidents = getIncidents(),
            clients = clients
        )
    }
}
