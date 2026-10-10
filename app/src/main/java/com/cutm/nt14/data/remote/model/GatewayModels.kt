package com.cutm.nt14.data.remote.model

data class GatewayMetrics(
    val rps: Double = 0.0,
    val allowed: Long = 0,
    val throttled: Long = 0,
    val errorRate: Double = 0.0,
    val p50LatencyMs: Long = 0,
    val p95LatencyMs: Long = 0,
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
    val decision: String
)

data class IncidentDto(
    val id: String,
    val type: String,
    val severity: String = "HIGH",
    val detail: String,
    val timestamp: Long
)

data class TrafficReportDto(
    val range: String,
    val totalRequests: Long,
    val allowedRequests: Long,
    val blockedRequests: Long,
    val errorRate: Double,
    val avgLatencyMs: Long,
    val p95LatencyMs: Long,
    val peakRps: Double,
    val topEndpoints: Map<String, Long>,
    val topClients: Map<String, Long>,
    val generatedAt: Long = System.currentTimeMillis()
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
    val isDemo: Boolean = false
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
