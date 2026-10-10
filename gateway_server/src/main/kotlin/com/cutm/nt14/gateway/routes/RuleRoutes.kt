package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.core.RateLimiter
import com.cutm.nt14.gateway.core.TrafficHistoryManager
import com.cutm.nt14.gateway.core.WebSocketManager
import com.cutm.nt14.gateway.models.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

import com.cutm.nt14.gateway.core.requireAdmin
import com.cutm.nt14.gateway.core.requireAuth

fun Route.ruleRoutes(
    rateLimiter: RateLimiter,
    webSocketManager: WebSocketManager,
    historyManager: TrafficHistoryManager,
    jwtService: JwtService,
    startTimeMs: Long = System.currentTimeMillis()
) {
    // GET /api/stats -> live telemetry & server health (requires valid JWT)
    get("/api/stats") {
        if (call.requireAuth(jwtService) == null) return@get
        val metrics = historyManager.calculateCurrentMetrics()
        call.respond(
            StatsResponse(
                status = "UP",
                uptimeMs = System.currentTimeMillis() - startTimeMs,
                subscribers = webSocketManager.activeSubscriberCount(),
                activeBansCount = rateLimiter.anomalyDetector.getActiveBans().size,
                rulesCount = rateLimiter.getAllRules().size,
                metrics = metrics,
                demoMode = metrics.demoMode
            )
        )
    }

    // GET /api/logs -> recent logs with limit and cursor (requires valid JWT)
    get("/api/logs") {
        if (call.requireAuth(jwtService) == null) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        val cursor = call.request.queryParameters["cursor"]
        val logs = historyManager.getRecentLogs(limit.coerceIn(1, 200), cursor)
        call.respond(logs)
    }

    // GET /api/reports?range=1h|24h|7d -> aggregated history (requires valid JWT)
    get("/api/reports") {
        if (call.requireAuth(jwtService) == null) return@get
        val range = call.request.queryParameters["range"] ?: "1h"
        val report = historyManager.generateReport(range)
        call.respond(report)
    }

    route("/api/rules") {
        // GET /api/rules -> list all active rate limit rules (requires valid JWT)
        get {
            if (call.requireAuth(jwtService) == null) return@get
            call.respond(rateLimiter.getAllRules())
        }

        // POST /api/rules -> create or update rule (ADMIN only)
        post {
            if (!call.requireAdmin(jwtService)) return@post
            val rule = call.receive<RateLimitRule>()
            rateLimiter.setRule(rule)
            webSocketManager.broadcast(
                GatewayEvent(
                    type = "rule_changed",
                    rule = rule,
                    endpoint = rule.endpointId
                )
            )
            call.respond(HttpStatusCode.Created, rule)
        }

        // PUT /api/rules -> update rule (ADMIN only)
        put {
            if (!call.requireAdmin(jwtService)) return@put
            val rule = call.receive<RateLimitRule>()
            rateLimiter.setRule(rule)
            webSocketManager.broadcast(
                GatewayEvent(
                    type = "rule_changed",
                    rule = rule,
                    endpoint = rule.endpointId
                )
            )
            call.respond(HttpStatusCode.OK, rule)
        }

        // DELETE /api/rules/{endpoint...} -> remove rate limit rule (ADMIN only)
        delete("{endpoint...}") {
            if (!call.requireAdmin(jwtService)) return@delete
            val endpointPath = "/" + (call.parameters.getAll("endpoint")?.joinToString("/") ?: "")
            val removed = rateLimiter.removeRule(endpointPath)
            if (removed) {
                webSocketManager.broadcast(
                    GatewayEvent(
                        type = "rule_changed",
                        rule = RateLimitRule(endpointPath, 0, 0, "REMOVED"),
                        endpoint = endpointPath
                    )
                )
                call.respond(ApiMessage("Rule for $endpointPath removed successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiMessage("No rule found for $endpointPath"))
            }
        }
    }

    route("/api/bans") {
        // GET /api/bans -> list active anomaly bans (requires valid JWT)
        get {
            if (call.requireAuth(jwtService) == null) return@get
            val activeBans = rateLimiter.anomalyDetector.getActiveBans().map { (ip, record) ->
                ActiveBanDto(clientId = ip, reason = record.reason, expiresAt = record.bannedUntil)
            }
            call.respond(activeBans)
        }

        // DELETE /api/bans/{clientId...} -> lift active ban (ADMIN only)
        delete("{clientId...}") {
            if (!call.requireAdmin(jwtService)) return@delete
            val clientId = call.parameters.getAll("clientId")?.joinToString("/") ?: ""
            val unbanned = rateLimiter.anomalyDetector.unbanClient(clientId)
            if (unbanned) {
                webSocketManager.broadcast(
                    GatewayEvent(
                        type = "unban",
                        ip = clientId,
                        decision = "unbanned"
                    )
                )
                call.respond(ApiMessage("Ban lifted for $clientId"))
            } else {
                call.respond(HttpStatusCode.NotFound, ApiMessage("No active ban found for $clientId"))
            }
        }
    }
}
