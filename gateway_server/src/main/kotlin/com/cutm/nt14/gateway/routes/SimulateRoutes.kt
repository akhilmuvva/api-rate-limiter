package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.core.RateLimiter
import com.cutm.nt14.gateway.core.TrafficHistoryManager
import com.cutm.nt14.gateway.core.WebSocketManager
import com.cutm.nt14.gateway.models.ApiMessage
import com.cutm.nt14.gateway.models.GatewayEvent
import com.cutm.nt14.gateway.models.SimulateRequest
import com.cutm.nt14.gateway.models.SimulateResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * Control-plane traffic simulation routes.
 * Enforces ADMIN role verification.
 * Supports profiles: "normal", "burst", "attack".
 */
fun Route.simulateRoutes(
    rateLimiter: RateLimiter,
    webSocketManager: WebSocketManager,
    historyManager: TrafficHistoryManager,
    jwtService: JwtService,
    simulationScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    post("/api/simulate") {
        if (!call.isAdminUser(jwtService)) {
            call.respond(HttpStatusCode.Forbidden, ApiMessage("Forbidden: Administrator privileges required to simulate traffic."))
            return@post
        }

        val req = call.receive<SimulateRequest>()

        simulationScope.launch {
            val profile = req.profile.lowercase()
            val totalRequests = req.requestCount.coerceIn(5, 200)

            val delayMs = when (profile) {
                "attack" -> 15L // Fast flood
                "burst" -> 35L
                else -> if (req.rps > 0) (1000.0 / req.rps).toLong().coerceIn(20L, 500L) else 100L
            }

            val sourceIps = when (profile) {
                "attack" -> listOf("198.51.100.42", "203.0.113.19", "185.220.101.5")
                "burst" -> req.sourceIps.ifEmpty { listOf("192.168.1.105") }
                else -> req.sourceIps.ifEmpty { listOf("192.168.1.10", "192.168.1.25", "10.0.0.5") }
            }

            val targetEndpoints = when (profile) {
                "attack" -> listOf("/api/polylance/escrows", "/api/users", "/api/orders")
                else -> listOf(req.endpoint)
            }

            repeat(totalRequests) { i ->
                val ip = sourceIps[Random.nextInt(sourceIps.size)]
                val endpoint = targetEndpoints[Random.nextInt(targetEndpoints.size)]
                val start = System.currentTimeMillis()

                val eval = rateLimiter.evaluate(endpoint, ip)
                val latency = Random.nextLong(15, 65)
                val status = if (eval.allowed) 200 else 429
                val decision = if (eval.allowed) "allowed" else if (eval.action == "BLOCK") "sliding_window" else "token_bucket"

                val log = historyManager.recordRequest(
                    clientId = ip,
                    method = "GET",
                    path = endpoint,
                    status = status,
                    latencyMs = latency,
                    decision = decision
                )

                val eventType = when {
                    !eval.allowed && eval.action == "BLOCK" -> "ip_blocked"
                    !eval.allowed -> "blocked_request"
                    else -> "request"
                }

                // If anomaly detector triggers ban
                if (status == 429 && profile == "attack" && i >= 12) {
                    val ban = rateLimiter.anomalyDetector.recordAndInspect(ip, endpoint, status, webSocketManager)
                    if (ban != null) {
                        historyManager.recordIncident(
                            type = "DDoS Flood Spike",
                            severity = "HIGH",
                            detail = "Simulated flood spike blocked on $endpoint from $ip"
                        )
                    }
                }

                webSocketManager.broadcast(
                    GatewayEvent(
                        type = eventType,
                        id = log.id,
                        ip = ip,
                        endpoint = endpoint,
                        method = "GET",
                        status = status,
                        latencyMs = latency,
                        decision = decision,
                        timestamp = start / 1000.0
                    )
                )

                if (delayMs > 0) delay(delayMs)
            }
        }

        call.respond(
            SimulateResponse(
                message = "Simulation started with profile '${req.profile}' (${req.requestCount} requests on ${req.endpoint})",
                totalTriggered = req.requestCount,
                endpoint = req.endpoint,
                profile = req.profile
            )
        )
    }
}
