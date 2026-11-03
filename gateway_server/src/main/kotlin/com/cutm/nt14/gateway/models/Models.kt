package com.cutm.nt14.gateway.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Real-time event payload matching Android's GatewayWebSocketClient byte-for-byte.
 * Supports versioned events:
 * - "snapshot"
 * - "request"
 * - "blocked_request"
 * - "ip_blocked"
 * - "metrics"
 * - "ban"
 * - "unban"
 * - "rule_changed"
 * - "incident"
 */
@Serializable
data class GatewayEvent(
    val type: String, // "snapshot" | "request" | "blocked_request" | "ip_blocked" | "metrics" | "ban" | "unban" | "rule_changed" | "incident"
    val id: String? = null,
    val ip: String? = null,
    val clientId: String? = null,
    val endpoint: String? = null,
    val method: String? = null,
    val status: Int? = null,
    @SerialName("latency_ms")
    val latencyMs: Long? = null,
    val decision: String? = null,
    val timestamp: Double = System.currentTimeMillis() / 1000.0,
    val metrics: GatewayMetrics? = null,
    val rules: List<RateLimitRule>? = null,
    val activeBans: List<ActiveBanDto>? = null,
    val logs: List<RequestLogDto>? = null,
    val incidents: List<IncidentDto>? = null,
    val ban: ActiveBanDto? = null,
    val rule: RateLimitRule? = null,
    val incident: IncidentDto? = null,
    val clients: List<ClientInfo>? = null,
    val client: ClientInfo? = null,
    val source: String? = null,            // "real" | "demo" on request events
    val upstream: String? = null,
    val target: TargetStatusDto? = null,   // target_status event
    val targets: List<TargetStatusDto>? = null,
    val persistence: String? = null,       // "postgres" | "memory" on snapshot
    val snapshotFrom: String? = null       // "db" when the snapshot was read from PostgreSQL
)

@Serializable
data class ClientInfo(
    val id: String,
    val maskedId: String,
    val requestsPerMin: Double,
    val totalRequests: Long,
    val throttledCount: Long,
    val lastSeen: Long,
    val status: String, // "active", "throttled", "banned"
    val isDemo: Boolean = false
)

@Serializable
data class WhoAmIResponse(
    val resolvedIp: String,
    val immediatePeer: String,
    val rawXForwardedFor: String? = null,
    val cfConnectingIp: String? = null,
    val isPeerTrusted: Boolean,
    val authenticatedUser: String? = null,
    val role: String? = null
)

@Serializable
data class CreateBanRequest(
    val clientId: String,
    val durationMinutes: Long = 60L,
    val reason: String = "Manual ban by Administrator"
)

@Serializable
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

@Serializable
data class ActiveBanDto(
    val clientId: String,
    val reason: String,
    val expiresAt: Long
)

@Serializable
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

@Serializable
data class IncidentDto(
    val id: String,
    val type: String,
    val severity: String = "HIGH", // "CRITICAL", "HIGH", "MEDIUM"
    val detail: String,
    val timestamp: Long,
    val targetId: Long? = null,
    val resolvedAt: Long? = null
)

@Serializable
data class TrafficReportDto(
    val range: String, // "1h", "24h", "7d"
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

/**
 * Dynamic rate limit rule definition.
 */
@Serializable
data class RateLimitRule(
    val endpointId: String,
    val limitPerMin: Int,
    val burstLimit: Int,
    val action: String = "ALERT" // "ALERT" | "BLOCK"
)

/**
 * Request payload for POST /api/simulate control-plane route.
 */
@Serializable
data class SimulateRequest(
    val endpoint: String = "/api/polylance/escrows",
    val requestCount: Int = 30,
    val rps: Double = 20.0,
    val sourceIps: List<String> = listOf("192.168.1.10", "192.168.1.25", "10.0.0.5"),
    val profile: String = "burst" // "normal" | "burst" | "attack"
)

/**
 * Response payload for POST /api/simulate.
 */
@Serializable
data class SimulateResponse(
    val message: String,
    val totalTriggered: Int,
    val endpoint: String,
    val profile: String = "burst"
)

/**
 * General status response payload.
 */
@Serializable
data class ApiMessage(
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

// Protected Demo Resources
@Serializable
data class DemoUser(val id: String, val name: String, val role: String)

@Serializable
data class DemoOrder(val orderId: String, val amount: Double, val status: String)

@Serializable
data class DemoProduct(val sku: String, val name: String, val price: Double)

@Serializable
data class HealthResponse(
    val status: String,
    val subscribers: Int,
    val uptimeMs: Long = 0L,
    val metrics: GatewayMetrics? = null,
    val persistence: PersistenceStatus? = null,
    val demoTraffic: Boolean = false
)

// PolyLance Web3 Protocol Models
@Serializable
data class PolyLanceEscrow(
    val escrowId: String,
    val client: String,
    val freelancer: String,
    val amountPol: Double = 0.0,
    val status: String,
    val title: String = "",
    val token: String = "POL",
    val contractAddress: String? = null,
    val createdAt: Long? = null
)

@Serializable
data class CreateEscrowRequest(
    val client: String = "0x3F9a...b210",
    val freelancer: String = "0x78Ce...4a91",
    val amountPol: Double = 500.0
)

@Serializable
data class PolyLanceAttestation(val attestationId: String, val developerGithub: String, val skillAttestation: String, val soulboundTokenId: String)

@Serializable
data class PolyLanceTalent(val talentId: String, val name: String, val specialization: String, val rating: Double)

@Serializable
data class GoogleAuthRequest(
    val idToken: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null
)

@Serializable
data class AuthResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: AuthUserInfo
)

@Serializable
data class AuthUserInfo(
    val email: String,
    val name: String,
    val role: String,
    val provider: String = "google"
)

@Serializable
data class PairResponse(
    val host: String,
    val wss: String,
    val ticket: String,
    val role: String,
    val email: String,
    val pairingUri: String,
    val expiresIn: Long
)

@Serializable
data class TicketResponse(
    val ticket: String,
    val role: String,
    val email: String,
    val expiresIn: Long
)

@Serializable
data class MeResponse(
    val valid: Boolean,
    val sub: String,
    val email: String,
    val name: String,
    val role: String,
    val issuer: String,
    val issuedAt: Long,
    val expiresAt: Long
)

@Serializable
data class StatsResponse(
    val status: String,
    val uptimeMs: Long,
    val subscribers: Int,
    val activeBansCount: Int,
    val rulesCount: Int,
    val metrics: GatewayMetrics,
    val demoMode: Boolean = false
)


// ---- Persistence / monitoring DTOs -------------------------------------------------------------

@Serializable
data class LogsPage(
    val items: List<RequestLogDto>,
    val nextCursor: String? = null,
    val limit: Int,
    val source: String = "all",
    val persistence: String = "postgres",
    val generatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class MetricsPointDto(
    val ts: Long,
    val requests: Long,
    val allowed: Long,
    val throttled: Long,
    val errors: Long,
    val errorRate: Double = 0.0,
    val throttleRate: Double = 0.0,
    val p50: Double,   // request-weighted mean of per-minute p50
    val p95: Double,   // max of per-minute p95 within the bucket (upper bound)
    val limiterP50: Double = 0.0,
    val limiterP95: Double = 0.0,
    val upstreamP50: Double? = null,
    val upstreamP95: Double? = null,
    val totalP50: Double = 0.0,
    val totalP95: Double = 0.0
)

@Serializable
data class MetricsSeriesDto(
    val range: String,
    val bucketSeconds: Int,
    val source: String,
    val points: List<MetricsPointDto>,
    val totalRequests: Long = 0,
    val totalAllowed: Long = 0,
    val totalThrottled: Long = 0,
    val totalErrors: Long = 0,
    val errorRate: Double = 0.0,
    val throttleRate: Double = 0.0,
    val lastRollupAt: Long,
    val generatedAt: Long
)

@Serializable
data class MonitoredTarget(
    val id: Long,
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

@Serializable
data class TargetRequest(
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

@Serializable
data class TargetStatusDto(
    val id: Long,
    val name: String,
    val url: String,
    val method: String,
    val expectedStatus: Int,
    val intervalSec: Int,
    val enabled: Boolean,
    val source: String = "real",
    val synthetic: Boolean = false,
    val up: Boolean? = null,               // null = not probed yet
    val lastStatusCode: Int? = null,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null,
    val lastCheckedAt: Long? = null,
    val consecutiveFailures: Int = 0,
    val uptimePct24h: Double? = null,
    val recentLatencies: List<Long> = emptyList(),  // oldest -> newest, for sparklines
    val recentUp: List<Boolean> = emptyList(),
    val openIncidentId: String? = null,
    val transition: String? = null
)

@Serializable
data class PersistenceStatus(
    val mode: String,                // "postgres" | "memory"
    val queued: Int = 0,
    val written: Long = 0,
    val dropped: Long = 0,
    val batches: Long = 0,
    val failures: Long = 0,
    val lastFlushAt: Long = 0,
    val lastRollupAt: Long = 0
)