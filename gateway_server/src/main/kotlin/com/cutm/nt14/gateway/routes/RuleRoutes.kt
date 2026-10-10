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
import com.cutm.nt14.gateway.core.ClientTracker
import com.cutm.nt14.gateway.core.TrustedProxyResolver
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.origin

fun Route.ruleRoutes(
    rateLimiter: RateLimiter,
    webSocketManager: WebSocketManager,
    historyManager: TrafficHistoryManager,
    jwtService: JwtService,
    startTimeMs: Long = System.currentTimeMillis(),
    clientTracker: ClientTracker = ClientTracker()
) {
    // GET /api/whoami -> returns resolved IP & raw forwarding headers (ADMIN only)
    get("/api/whoami") {
        if (!call.requireAdmin(jwtService)) return@get
        val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
            ?: call.request.queryParameters["token"]
        val claims = bearer?.let { jwtService.verify(it) }

        val remoteHost = call.request.origin.remoteHost
        val trustedSet = TrustedProxyResolver.getTrustedSet()
        val isTrusted = TrustedProxyResolver.isTrustedProxy(remoteHost, trustedSet)
        val resolvedIp = TrustedProxyResolver.resolveClientIp(call)

        call.respond(
            WhoAmIResponse(
                resolvedIp = resolvedIp,
                immediatePeer = remoteHost,
                rawXForwardedFor = call.request.headers["X-Forwarded-For"],
                cfConnectingIp = call.request.headers["CF-Connecting-IP"],
                isPeerTrusted = isTrusted,
                authenticatedUser = claims?.email,
                role = claims?.role
            )
        )
    }

    // GET /api/clients -> per-client real-time traffic & status (requires valid JWT, masked for VIEWER)
    get("/api/clients") {
        val claims = call.requireAuth(jwtService) ?: return@get
        val isAdmin = claims.role == "ADMIN"
        val clients = clientTracker.getAllClients(isAdmin, rateLimiter.anomalyDetector)
        call.respond(clients)
    }

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

        // POST /api/bans -> create manual ban (ADMIN only)
        post {
            if (!call.requireAdmin(jwtService)) return@post
            val req = call.receive<CreateBanRequest>()
            val durationSeconds = (req.durationMinutes.coerceIn(1, 1440)) * 60L
            val record = rateLimiter.anomalyDetector.banClient(req.clientId, durationSeconds, req.reason)
            val banDto = ActiveBanDto(clientId = req.clientId, reason = req.reason, expiresAt = record.bannedUntil)
            webSocketManager.broadcast(
                GatewayEvent(
                    type = "ban",
                    clientId = req.clientId,
                    ip = req.clientId,
                    ban = banDto
                )
            )
            call.respond(HttpStatusCode.Created, banDto)
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
