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

    fun recordRequest(
        id: String = "req_" + UUID.randomUUID().toString().take(8),
        timestamp: Long = System.currentTimeMillis(),
        clientId: String,
        method: String,
        path: String,
        status: Int,
        latencyMs: Long,
        decision: String
    ): RequestLogDto {
        val log = RequestLogDto(
            id = id,
            timestamp = timestamp,
            clientId = clientId,
            method = method,
            path = path,
            status = status,
            latencyMs = latencyMs,
            decision = decision
        )

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
        incidentsList.addLast(incident)
        while (incidentsList.size > 50) {
            incidentsList.pollFirst()
        }
        return incident
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

    fun getRecentLogs(limit: Int = 200, cursor: String? = null): List<RequestLogDto> {
        val all = logsRingBuffer.toList().reversed()
        if (cursor.isNullOrBlank()) {
            return all.take(limit)
        }
        val index = all.indexOfFirst { it.id == cursor }
        if (index < 0) return all.take(limit)
        return all.drop(index + 1).take(limit)
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

        val latencies = (if (recentLogs60s.isNotEmpty()) recentLogs60s else logsRingBuffer.toList())
            .map { it.latencyMs }.sorted()

        val p50 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.50).toInt().coerceAtMost(latencies.lastIndex)] else 0L
        val p95 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.lastIndex)] else 0L

        val activeClients = recentLogs60s.map { it.clientId }.distinct().size.coerceAtLeast(if (logsRingBuffer.isNotEmpty()) 1 else 0)

        val totalWindow = (allowedCount + throttledCount).toDouble()
        val errorRate = if (totalWindow > 0) (throttledCount.toDouble() / totalWindow) else 0.0

        val endpointCounts = mutableMapOf<String, Long>()
        for (log in (if (recentLogs60s.isNotEmpty()) recentLogs60s else logsRingBuffer.toList())) {
            endpointCounts[log.path] = (endpointCounts[log.path] ?: 0L) + 1L
        }

        val isDemo = System.getenv("DEMO_TRAFFIC")?.toBooleanStrictOrNull() ?: true

        return GatewayMetrics(
            rps = (rps * 10).roundToInt() / 10.0,
            allowed = allowedCount,
            throttled = throttledCount,
            errorRate = (errorRate * 1000).roundToInt() / 1000.0,
            p50LatencyMs = p50,
            p95LatencyMs = p95,
            activeClients = activeClients,
            endpointCounts = endpointCounts,
            demoMode = isDemo
        )
    }

    fun generateReport(range: String = "1h"): TrafficReportDto {
        val now = System.currentTimeMillis()
        val durationMs = when (range.lowercase()) {
            "24h" -> 86_400_000L
            "7d" -> 7 * 86_400_000L
            else -> 3_600_000L // 1h default
        }
        val cutoff = now - durationMs
        val filtered = logsRingBuffer.filter { it.timestamp >= cutoff }

        val total = filtered.size.toLong()
        val blocked = filtered.count { it.status == 429 || it.decision != "allowed" }.toLong()
        val allowed = total - blocked
        val errorRate = if (total > 0) blocked.toDouble() / total.toDouble() else 0.0

        val latencies = filtered.map { it.latencyMs }.sorted()
        val avgLatency = if (latencies.isNotEmpty()) latencies.average().toLong() else 0L
        val p95 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.lastIndex)] else 0L

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
            p95LatencyMs = p95,
            peakRps = peakRps,
            topEndpoints = topEndpoints,
            topClients = topClients,
            generatedAt = now
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
