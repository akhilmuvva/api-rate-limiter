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
import com.cutm.nt14.gateway.db.LogQuery
import com.cutm.nt14.gateway.db.Persistence
import com.cutm.nt14.gateway.db.TimeParam
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondTextWriter

fun Route.ruleRoutes(
    rateLimiter: RateLimiter,
    webSocketManager: WebSocketManager,
    historyManager: TrafficHistoryManager,
    jwtService: JwtService,
    startTimeMs: Long = System.currentTimeMillis(),
    clientTracker: ClientTracker = ClientTracker(),
    persistence: Persistence? = null
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

    // GET /api/admin/db-inspect -> flyway checksums & privacy counts (ADMIN only)
    get("/api/admin/db-inspect") {
        if (!call.requireAdmin(jwtService)) return@get
        val db = persistence?.db ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ApiMessage("Database persistence not configured"))
        val result = db.withConnection { conn ->
            val counts = mutableMapOf<String, Long>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM request_logs WHERE client_id LIKE '%@%'").use { rs -> rs.next(); counts["request_logs_with_at"] = rs.getLong(1) }
                st.executeQuery("SELECT count(*) FROM bans WHERE client_key LIKE '%@%'").use { rs -> rs.next(); counts["bans_with_at"] = rs.getLong(1) }
                st.executeQuery("SELECT count(*) FROM incidents WHERE detail LIKE '%@%'").use { rs -> rs.next(); counts["incidents_with_at"] = rs.getLong(1) }
                st.executeQuery("SELECT count(*) FROM clients WHERE id LIKE '%@%' OR label LIKE '%@%'").use { rs -> rs.next(); counts["clients_with_at"] = rs.getLong(1) }
            }
            val flywayRows = mutableListOf<FlywayRowDto>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT installed_rank, version, description, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank").use { rs ->
                    while (rs.next()) {
                        flywayRows += FlywayRowDto(
                            rank = rs.getInt("installed_rank"),
                            version = rs.getString("version"),
                            description = rs.getString("description"),
                            script = rs.getString("script"),
                            checksum = rs.getInt("checksum").takeIf { !rs.wasNull() },
                            success = rs.getBoolean("success")
                        )
                    }
                }
            }
            val defaultTimeout = conn.createStatement().use { st ->
                st.executeQuery("SELECT column_default FROM information_schema.columns WHERE table_name = 'monitored_targets' AND column_name = 'timeout_ms'").use { rs ->
                    if (rs.next()) rs.getString(1)?.toIntOrNull() ?: 10000 else 10000
                }
            }
            DbInspectResponse(
                counts = counts,
                flywaySchemaHistory = flywayRows,
                defaultProbeTimeoutMs = defaultTimeout
            )
        }
        call.respond(result)
    }

    // GET /api/clients -> per-client real-time traffic & status (requires valid JWT, masked for VIEWER)
    get("/api/clients") {
        val claims = call.requireAuth(jwtService) ?: return@get
        val isAdmin = claims.role == "ADMIN"
        val liveClients = clientTracker.getAllClients(isAdmin, rateLimiter.anomalyDetector)
        if (persistence != null) {
            if (persistence.writer.pending() > 0) runCatching { persistence.writer.flushNow() }
            val dbClients = runCatching { persistence.logs.listClients(isAdmin, rateLimiter.anomalyDetector) }.getOrDefault(emptyList())
            val liveKeys = liveClients.flatMap { c ->
                val raw = c.id.removePrefix("ip:")
                val hashKey = if (com.cutm.nt14.gateway.db.IpPrivacy.looksLikeIp(raw)) {
                    "iphash:${com.cutm.nt14.gateway.db.IpPrivacy.hash(raw)}"
                } else null
                listOfNotNull(
                    c.id,
                    c.maskedId,
                    com.cutm.nt14.gateway.db.IpPrivacy.storedClientId(raw),
                    hashKey
                )
            }.toSet()
            val merged = liveClients + dbClients.filter { it.id !in liveKeys && it.maskedId !in liveKeys }
            call.respond(merged)
        } else {
            call.respond(liveClients)
        }
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

    // GET /api/logs?cursor&limit&status&client&from&to&source -> keyset pagination (requires valid JWT)
    get("/api/logs") {
        if (call.requireAuth(jwtService) == null) return@get
        val qp = call.request.queryParameters
        val limit = (qp["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        val cursorRaw = qp["cursor"]?.trim()?.takeIf { it.isNotEmpty() }
        val q = LogQuery(
            cursor = cursorRaw?.toLongOrNull(),
            limit = limit,
            status = qp["status"],
            client = qp["client"],
            fromMs = TimeParam.parse(qp["from"]),
            toMs = TimeParam.parse(qp["to"]),
            source = qp["source"]
        )
        val page = if (persistence != null) {
            if (persistence.writer.pending() > 0) runCatching { persistence.writer.flushNow() }
            persistence.logs.page(q)
        } else {
            historyManager.queryLogs(q)
        }
        page.nextCursor?.let { call.response.header("X-Next-Cursor", it) }
        call.respond(page)
    }

    // GET /api/metrics?range=1h|24h|7d&source=real|demo&endpoint=... -> time series from metrics_1m (requires valid JWT)
    get("/api/metrics") {
        if (call.requireAuth(jwtService) == null) return@get
        val qp = call.request.queryParameters
        val range = qp["range"] ?: "1h"
        val source = qp["source"]
        val endpoint = qp["endpoint"]
        if (persistence != null) {
            if (persistence.writer.pending() > 0) runCatching { persistence.writer.flushNow() }
            val now = System.currentTimeMillis()
            runCatching { persistence.metrics.rollup(now - 5 * 60_000L, now + 60_000L) }
            call.respond(persistence.metrics.series(range, source, endpoint, now))
        } else {
            call.respond(
                MetricsSeriesDto(
                    range = range,
                    bucketSeconds = 60,
                    source = source ?: "all",
                    points = emptyList(),
                    lastRollupAt = 0L,
                    generatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // GET /api/reports?range=1h|24h|7d&source=real|demo -> aggregated from DB (requires valid JWT)
    get("/api/reports") {
        if (call.requireAuth(jwtService) == null) return@get
        val range = call.request.queryParameters["range"] ?: "1h"
        val source = call.request.queryParameters["source"]
        val report = if (persistence != null) {
            if (persistence.writer.pending() > 0) runCatching { persistence.writer.flushNow() }
            val now = System.currentTimeMillis()
            runCatching { persistence.metrics.rollup(now - 5 * 60_000L, now + 60_000L) }
            persistence.metrics.report(range, source, now)
        } else {
            historyManager.generateReport(range, source)
        }
        call.respond(report)
    }

    // GET /api/incidents -> recent security & target-down incidents (requires valid JWT)
    get("/api/incidents") {
        if (call.requireAuth(jwtService) == null) return@get
        val openOnly = call.request.queryParameters["open"]?.toBooleanStrictOrNull() ?: false
        val incidents = if (persistence != null) {
            persistence.incidents.recent(50)
        } else {
            historyManager.getIncidents()
        }
        call.respond(if (openOnly) incidents.filter { it.resolvedAt == null } else incidents)
    }

    // PATCH /api/incidents/{id}/resolve -> mark an incident resolved (ADMIN only)
    patch("/api/incidents/{id}/resolve") {
        if (!call.requireAdmin(jwtService)) return@patch
        val id = call.parameters["id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiMessage("Missing incident id"))
        val now = System.currentTimeMillis()
        val resolved = if (persistence != null) {
            persistence.incidents.resolve(id, now)?.also { historyManager.resolveIncident(id, it.resolvedAt ?: now) }
        } else {
            historyManager.resolveIncident(id, now)
        }
        if (resolved == null) {
            call.respond(HttpStatusCode.NotFound, ApiMessage("Incident $id not found"))
        } else {
            webSocketManager.broadcast(GatewayEvent(type = "incident", incident = resolved))
            call.respond(HttpStatusCode.OK, resolved)
        }
    }

    // GET /api/export?from&to&format=csv&source&status&client -> CSV export (ADMIN only)
    get("/api/export") {
        if (!call.requireAdmin(jwtService)) return@get
        val qp = call.request.queryParameters
        val format = (qp["format"] ?: "csv").lowercase()
        if (format != "csv") {
            call.respond(HttpStatusCode.BadRequest, ApiMessage("Unsupported export format '$format'. Use format=csv."))
            return@get
        }
        val q = LogQuery(
            status = qp["status"],
            client = qp["client"],
            fromMs = TimeParam.parse(qp["from"]),
            toMs = TimeParam.parse(qp["to"]),
            source = qp["source"]
        )
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"gateway-logs.csv\"")
        call.respondTextWriter(contentType = ContentType.Text.CSV) {
            if (persistence != null) {
                if (persistence.writer.pending() > 0) runCatching { persistence.writer.flushNow() }
                persistence.logs.exportCsv(q, this)
            } else {
                write("id,ts,client_id,client_ip_hash,method,path,status,latency_ms,decision,source,upstream\n")
                for (row in historyManager.queryLogs(q.copy(limit = 500)).items.reversed()) {
                    write("${row.id},${java.time.Instant.ofEpochMilli(row.timestamp)},${row.clientId},${row.clientIpHash ?: ""},${row.method},${row.path},${row.status},${row.latencyMs},${row.decision},${row.source},${row.upstream ?: ""}\n")
                }
            }
        }
    }

    // GET /api/targets/status -> live status, latency sparklines & 24h uptime for all targets (requires valid JWT)
    // Excludes synthetic/test targets by default unless ?includeSynthetic=true
    get("/api/targets/status") {
        if (call.requireAuth(jwtService) == null) return@get
        val includeSynthetic = call.request.queryParameters["includeSynthetic"]?.toBooleanStrictOrNull() ?: false
        val source = call.request.queryParameters["source"]
        val statuses = persistence?.monitor?.statusAll(includeSynthetic = includeSynthetic, source = source) ?: emptyList()
        call.respond(statuses)
    }

    // /api/targets CRUD -> ADMIN only
    route("/api/targets") {
        get {
            if (!call.requireAdmin(jwtService)) return@get
            val list = persistence?.targets?.all() ?: emptyList()
            call.respond(list)
        }

        post {
            if (!call.requireAdmin(jwtService)) return@post
            val db = persistence ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiMessage("Database persistence not configured"))
            val req = runCatching { call.receive<TargetRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiMessage("Invalid target payload"))
            if (req.name.isBlank() || !(req.url.startsWith("http://") || req.url.startsWith("https://"))) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiMessage("Target requires a non-blank name and http(s):// url"))
            }
            val normalized = req.copy(
                intervalSec = req.intervalSec.coerceIn(5, 3600),
                timeoutMs = req.timeoutMs.coerceIn(500, 60_000),
                method = req.method.uppercase()
            )
            val created = try {
                db.targets.create(normalized)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.Conflict, ApiMessage("Failed to create target: ${e.message}"))
            }
            db.monitor.invalidate(created.id)
            call.respond(HttpStatusCode.Created, created)
        }

        put("{id}") {
            if (!call.requireAdmin(jwtService)) return@put
            val db = persistence ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, ApiMessage("Database persistence not configured"))
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ApiMessage("Invalid target id"))
            val req = runCatching { call.receive<TargetRequest>() }.getOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, ApiMessage("Invalid target payload"))
            val normalized = req.copy(
                intervalSec = req.intervalSec.coerceIn(5, 3600),
                timeoutMs = req.timeoutMs.coerceIn(500, 60_000),
                method = req.method.uppercase()
            )
            val updated = db.targets.update(id, normalized)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiMessage("Target $id not found"))
            db.monitor.invalidate(id)
            call.respond(HttpStatusCode.OK, updated)
        }

        delete("{id}") {
            if (!call.requireAdmin(jwtService)) return@delete
            val db = persistence ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable, ApiMessage("Database persistence not configured"))
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiMessage("Invalid target id"))
            val resolvedIds = db.incidents.resolveForTarget(id)
            val deleted = db.targets.delete(id)
            if (!deleted) return@delete call.respond(HttpStatusCode.NotFound, ApiMessage("Target $id not found"))
            historyManager.resolveIncidentsForTarget(id)
            db.monitor.invalidate(id)
            call.respond(ApiMessage("Target $id deleted (auto-resolved ${resolvedIds.size} open incident(s))"))
        }

        post("{id}/probe") {
            if (!call.requireAdmin(jwtService)) return@post
            val db = persistence ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiMessage("Database persistence not configured"))
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ApiMessage("Invalid target id"))
            val target = db.targets.get(id)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiMessage("Target $id not found"))
            val status = db.monitor.probeOnce(target)
            call.respond(status)
        }
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
