package com.cutm.nt14.data.remote.model

data class GatewayMetrics(
    val rps: Double = 0.0,
    val allowed: Long = 0,
    val throttled: Long = 0,
    val serverErrors: Long = 0,
    val errorRate: Double = 0.0,
    val throttleRate: Double = 0.0,
    val p50LatencyMs: Long = 0,
    val p95LatencyMs: Long = 0,
    val p50LimiterMs: Long = 0,
    val p95LimiterMs: Long = 0,
    val p50UpstreamMs: Long = 0,
    val p95UpstreamMs: Long = 0,
    val p50TotalMs: Long = 0,
    val p95TotalMs: Long = 0,
    val activeClients: Int = 0,
    val endpointCounts: Map<String, Long> = emptyMap(),
    val demoMode: Boolean = false
)

data class ActiveBanDto(
    val clientId: String,
    val reason: String,
    val expiresAt: Long
)

data class RequestLogDto(
    val id: String,
    val timestamp: Long,
    val clientId: String,
    val method: String,
    val path: String,
    val status: Int,
    val latencyMs: Long,
    val decision: String,
    val source: String = "real",
    val upstream: String? = null,
    val clientIpHash: String? = null,
    val limiterMs: Long? = null,
    val upstreamMs: Long? = null,
    val totalMs: Long? = null
)

data class LogsPageDto(
    val items: List<RequestLogDto>,
    val nextCursor: String? = null,
    val limit: Int = 50,
    val source: String = "all",
    val persistence: String = "postgres",
    val generatedAt: Long = System.currentTimeMillis()
)

data class IncidentDto(
    val id: String,
    val type: String,
    val severity: String = "HIGH",
    val detail: String,
    val timestamp: Long,
    val targetId: Long? = null,
    val resolvedAt: Long? = null
)

data class TrafficReportDto(
    val range: String,
    val totalRequests: Long,
    val allowedRequests: Long,
    val blockedRequests: Long,
    val errorRate: Double,
    val throttleRate: Double = 0.0,
    val avgLatencyMs: Long,
    val p50LatencyMs: Long = 0,
    val p95LatencyMs: Long,
    val p50LimiterMs: Long = 0,
    val p95LimiterMs: Long = 0,
    val p50UpstreamMs: Long = 0,
    val p95UpstreamMs: Long = 0,
    val p50TotalMs: Long = 0,
    val p95TotalMs: Long = 0,
    val peakRps: Double,
    val topEndpoints: Map<String, Long>,
    val topClients: Map<String, Long>,
    val generatedAt: Long = System.currentTimeMillis(),
    val serverErrors: Long = 0,
    val source: String = "all",
    val persistence: String = "memory",
    val dataFrom: Long = 0,
    val dataTo: Long = 0,
    val lastRollupAt: Long = 0
)

data class MetricsPointDto(
    val ts: Long,
    val requests: Long,
    val allowed: Long,
    val throttled: Long,
    val errors: Long,
    val errorRate: Double = 0.0,
    val throttleRate: Double = 0.0,
    val p50: Double,
    val p95: Double,
    val limiterP50: Double = 0.0,
    val limiterP95: Double = 0.0,
    val upstreamP50: Double? = null,
    val upstreamP95: Double? = null,
    val totalP50: Double = 0.0,
    val totalP95: Double = 0.0
)

data class MetricsSeriesDto(
    val range: String,
    val bucketSeconds: Int,
    val source: String,
    val points: List<MetricsPointDto>,
    val errorRate: Double = 0.0,
    val throttleRate: Double = 0.0,
    val lastRollupAt: Long,
    val generatedAt: Long
)

data class TargetStatusDto(
    val id: Long,
    val name: String,
    val url: String,
    val method: String = "GET",
    val expectedStatus: Int = 200,
    val intervalSec: Int = 30,
    val enabled: Boolean = true,
    val source: String = "real",
    val synthetic: Boolean = false,
    val up: Boolean? = null,
    val lastStatusCode: Int? = null,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null,
    val lastCheckedAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val uptimePct24h: Double? = null,
    val recentLatencies: List<Long> = emptyList(),
    val recentUp: List<Boolean> = emptyList(),
    val openIncidentId: String? = null,
    val transition: String? = null
)

data class TargetRequestDto(
    val name: String,
    val url: String,
    val method: String = "GET",
    val expectedStatus: Int = 200,
    val intervalSec: Int = 30,
    val timeoutMs: Int = 10_000,
    val enabled: Boolean = true,
    val source: String = "real",
    val synthetic: Boolean = false
)

data class RateLimitRuleDto(
    val endpointId: String,
    val limitPerMin: Int,
    val burstLimit: Int,
    val action: String = "ALERT"
)

data class SimulateRequestDto(
    val endpoint: String,
    val requestCount: Int,
    val rps: Double,
    val profile: String = "burst",
    val sourceIps: List<String> = emptyList()
)

data class ClientInfoDto(
    val id: String,
    val maskedId: String,
    val requestsPerMin: Double = 0.0,
    val totalRequests: Long = 0L,
    val throttledCount: Long = 0L,
    val lastSeen: Long = 0L,
    val status: String = "active", // "active", "throttled", "banned"
    val isDemo: Boolean = false,
    val clientIpHash: String? = null
)

data class WhoAmIDto(
    val resolvedIp: String,
    val immediatePeer: String,
    val rawXForwardedFor: String? = null,
    val cfConnectingIp: String? = null,
    val isTrustedProxy: Boolean = false,
    val authenticatedUser: String? = null,
    val role: String = "VIEWER"
)
