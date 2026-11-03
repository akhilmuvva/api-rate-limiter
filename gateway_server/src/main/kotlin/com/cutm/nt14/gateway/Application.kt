package com.cutm.nt14.gateway

import com.cutm.nt14.gateway.core.ClientTracker
import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.core.PolyLanceUpstream
import com.cutm.nt14.gateway.core.RateLimitEvaluation
import com.cutm.nt14.gateway.core.RateLimiter
import com.cutm.nt14.gateway.core.TrafficHistoryManager
import com.cutm.nt14.gateway.core.WebSocketManager
import com.cutm.nt14.gateway.db.GatewayDatabase
import com.cutm.nt14.gateway.db.Persistence
import com.cutm.nt14.gateway.models.ApiMessage
import com.cutm.nt14.gateway.models.GatewayEvent
import com.cutm.nt14.gateway.models.HealthResponse
import com.cutm.nt14.gateway.models.PersistenceStatus
import com.cutm.nt14.gateway.routes.authRoutes
import com.cutm.nt14.gateway.routes.demoRoutes
import com.cutm.nt14.gateway.routes.eventsWebSocket
import com.cutm.nt14.gateway.routes.ruleRoutes
import com.cutm.nt14.gateway.routes.simulateRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.Principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.random.Random

data class ApiKeyPrincipal(val key: String) : Principal

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8000
    val logger = LoggerFactory.getLogger("GatewayServer")
    logger.info("Starting NT14 Rate Limiter Gateway on port $port (bind 0.0.0.0)...")

    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() = module(customDatabase = null, startBackgroundMonitor = true)

fun Application.module(
    customDatabase: GatewayDatabase? = null,
    startBackgroundMonitor: Boolean = true,
    customUpstream: PolyLanceUpstream? = null,
    customJwtService: JwtService? = null
) {
    // Fail fast on startup if IP_HASH_SALT is not configured in environment
    com.cutm.nt14.gateway.db.IpPrivacy.ensureConfigured()

    val rateLimiter = RateLimiter()
    val webSocketManager = WebSocketManager()
    val historyManager = TrafficHistoryManager()
    val clientTracker = ClientTracker()
    val jwtService = customJwtService ?: JwtService()
    val polyLanceUpstream = customUpstream ?: PolyLanceUpstream()
    val serverStartTime = System.currentTimeMillis()

    webSocketManager.historyManager = historyManager
    webSocketManager.rateLimiter = rateLimiter
    webSocketManager.clientTracker = clientTracker

    // Initialize PostgreSQL + Flyway migrations when DATABASE_URL is configured (or passed by tests)
    val db = customDatabase ?: runCatching { GatewayDatabase.fromEnv() }
        .onFailure { LoggerFactory.getLogger("GatewayServer").error("Failed to parse DATABASE_URL: ${it.message}", it) }
        .getOrNull()
    val persistence: Persistence? = db?.let { database ->
        database.migrate()
        Persistence(database).also { p ->
            p.attach(rateLimiter, historyManager, webSocketManager)
            p.start(this, startMonitor = startBackgroundMonitor)
            environment.monitor.subscribe(ApplicationStopped) {
                p.shutdown(closePool = customDatabase == null)
            }
        }
    }

    val initAdminTicket = jwtService.createWsTicket(role = "ADMIN", email = "admin@cutm.nt14.com", durationSeconds = 86400L)
    LoggerFactory.getLogger("GatewayServer").info(
        """
        ================================================================================
        [GATEWAY READY - SCAN / PAIR WITH ANDROID APP]
        Pairing URI: nt14-pair://pair?host=http://127.0.0.1:8000&ticket=$initAdminTicket
        Quick Pair Token: $initAdminTicket
        Endpoints: GET /api/auth/pair | WS /ws/events?ticket=...
        ================================================================================
        """.trimIndent()
    )

    // 1. Content Negotiation (JSON)
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    // 2. WebSockets (hardened frame size to 64KB)
    install(WebSockets) {
        maxFrameSize = 65536L
        masking = false
    }

    // 3. Call Logging
    install(CallLogging)

    // 4. Control-plane Authentication Plugin
    install(Authentication) {
        provider("api-key") {
            authenticate { context ->
                val expectedApiKey = System.getenv("GATEWAY_API_KEY") ?: "dev-local-key"
                val apiKeyHeader = context.call.request.headers["X-API-Key"]
                val apiKeyQuery = context.call.request.queryParameters["api_key"]
                val key = apiKeyHeader ?: apiKeyQuery

                if (key == expectedApiKey) {
                    context.principal(ApiKeyPrincipal(expectedApiKey))
                } else {
                    context.challenge("api-key", AuthenticationFailedCause.InvalidCredentials) { challenge, call ->
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ApiMessage("Unauthorized: Invalid or missing API Key. Provide via X-API-Key header.")
                        )
                        challenge.complete()
                    }
                }
            }
        }
    }

    // 5. Rate Limiting Gateway Interceptor (intercepting /api/* demo and proxied routes)
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        call.response.header("X-Frame-Options", "DENY")

        val path = call.request.path()
        val isControlPlane = path.startsWith("/api/rules") ||
                path.startsWith("/api/simulate") ||
                path.startsWith("/api/auth") ||
                path.startsWith("/api/stats") ||
                path.startsWith("/api/logs") ||
                path.startsWith("/api/bans") ||
                path.startsWith("/api/reports") ||
                path.startsWith("/api/metrics") ||
                path.startsWith("/api/targets") ||
                path.startsWith("/api/export") ||
                path.startsWith("/api/incidents") ||
                path.startsWith("/api/clients") ||
                path.startsWith("/api/whoami")

        if (path.startsWith("/api/") && !isControlPlane) {
            val handlerStartNs = System.nanoTime()

            // Resolve identity from JWT Bearer token if present
            val authHeader = call.request.headers["Authorization"]
            val bearerToken = authHeader?.removePrefix("Bearer ")?.trim()
                ?: call.request.queryParameters["token"]

            val jwtClaims = bearerToken?.let { jwtService.verifyToken(it) }
            val rawClientIp = rateLimiter.resolveClientId(call)
            val clientId = if (jwtClaims != null) "user:${jwtClaims.email}" else rawClientIp

            // Evaluate rate limit: ADMIN role with valid JWT gets privileged headroom
            val limiterStartNs = System.nanoTime()
            val evaluation = if (jwtClaims?.role == "ADMIN") {
                RateLimitEvaluation(
                    allowed = true,
                    limit = 1000,
                    remaining = 999,
                    resetSeconds = 60,
                    retryAfterSeconds = 0,
                    action = "ALLOW"
                )
            } else {
                rateLimiter.evaluate(path, clientId)
            }
            val limiterMs = ((System.nanoTime() - limiterStartNs) / 1_000_000L).coerceAtLeast(0L)

            rateLimiter.appendHeaders(call, evaluation)
            call.response.header("X-Limiter-Ms", limiterMs.toString())

            if (jwtClaims != null) {
                call.response.header("X-Authenticated-User", jwtClaims.email)
                call.response.header("X-Authenticated-Role", jwtClaims.role)
            }

            val isDemo = call.request.headers["X-Traffic-Source"]?.equals("demo", ignoreCase = true) == true ||
                path.contains("simulate") ||
                clientId.startsWith("demo:") ||
                clientId in listOf("192.168.1.10", "192.168.1.25", "10.0.0.5", "198.51.100.42", "203.0.113.19")
            val sourceTag = if (isDemo) "demo" else "real"
            val upstreamUrl = polyLanceUpstream.upstreamUrlFor(path)
            clientTracker.recordRequest(clientId, isThrottled = !evaluation.allowed, isDemo = isDemo)

            if (!evaluation.allowed) {
                val totalMs = ((System.nanoTime() - handlerStartNs) / 1_000_000L).coerceAtLeast(0L)
                call.response.header("X-Total-Ms", totalMs.toString())
                val eventType = if (evaluation.action == "BLOCK") "ip_blocked" else "blocked_request"
                val decision = if (evaluation.action == "BLOCK") "sliding_window" else "token_bucket"

                val log = historyManager.recordRequest(
                    clientId = clientId,
                    method = call.request.httpMethod.value,
                    path = path,
                    status = 429,
                    latencyMs = totalMs,
                    decision = decision,
                    source = sourceTag,
                    upstream = upstreamUrl,
                    clientIp = rawClientIp,
                    limiterMs = limiterMs,
                    upstreamMs = null,
                    totalMs = totalMs
                )

                webSocketManager.broadcast(
                    GatewayEvent(
                        type = eventType,
                        id = log.id,
                        ip = log.clientId,
                        endpoint = path,
                        method = call.request.httpMethod.value,
                        status = 429,
                        latencyMs = totalMs,
                        decision = decision,
                        source = sourceTag,
                        upstream = upstreamUrl,
                        timestamp = System.currentTimeMillis() / 1000.0
                    )
                )

                val wasAlreadyBanned = rateLimiter.anomalyDetector.isBanned(clientId)
                val ban = rateLimiter.anomalyDetector.recordAndInspect(clientId, path, 429, webSocketManager)
                if (!wasAlreadyBanned && ban != null) {
                    historyManager.recordIncident(
                        type = "Anomaly Ban Triggered",
                        severity = "HIGH",
                        detail = "Client ${log.clientId} exceeded threshold on $path: ${ban.reason}"
                    )
                }

                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiMessage("Rate limit exceeded for ${log.clientId}. Action: ${evaluation.action}. Retry after ${evaluation.retryAfterSeconds}s")
                )
                finish()
                return@intercept
            }

            // Proceed to the endpoint handler
            proceed()

            // After execution: emit telemetry for successful call
            val totalMs = ((System.nanoTime() - handlerStartNs) / 1_000_000L).coerceAtLeast(0L)
            val upstreamMs = call.attributes.getOrNull(com.cutm.nt14.gateway.core.UpstreamMsKey)
            val upstreamProto = call.attributes.getOrNull(com.cutm.nt14.gateway.core.UpstreamProtocolKey)
            if (!call.response.isCommitted) {
                runCatching {
                    if (upstreamMs != null) call.response.header("X-Upstream-Ms", upstreamMs.toString())
                    if (upstreamProto != null) call.response.header("X-Upstream-Protocol", upstreamProto)
                    call.response.header("X-Total-Ms", totalMs.toString())
                }
            }
            val status = call.response.status()?.value ?: 200

            val log = historyManager.recordRequest(
                clientId = clientId,
                method = call.request.httpMethod.value,
                path = path,
                status = status,
                latencyMs = totalMs,
                decision = "allowed",
                source = sourceTag,
                upstream = upstreamUrl,
                clientIp = rawClientIp,
                limiterMs = limiterMs,
                upstreamMs = upstreamMs,
                totalMs = totalMs
            )

            rateLimiter.anomalyDetector.recordAndInspect(clientId, path, status, webSocketManager)

            webSocketManager.broadcast(
                GatewayEvent(
                    type = "request",
                    id = log.id,
                    ip = log.clientId,
                    endpoint = path,
                    method = call.request.httpMethod.value,
                    status = status,
                    latencyMs = totalMs,
                    decision = "allowed",
                    source = sourceTag,
                    upstream = upstreamUrl,
                    timestamp = System.currentTimeMillis() / 1000.0
                )
            )
            return@intercept
        }

        proceed()
    }

    val isDemoTrafficEnabled = System.getenv("DEMO_TRAFFIC")?.toBooleanStrictOrNull() ?: false

    // 6. Routing Assembly
    routing {
        get("/") {
            call.respond(ApiMessage("NT14 Rate Limiter Gateway (Kotlin/Ktor) running on port ${System.getenv("PORT") ?: "8000"}"))
        }

        get("/health") {
            call.respond(
                HealthResponse(
                    status = "UP",
                    subscribers = webSocketManager.activeSubscriberCount(),
                    uptimeMs = System.currentTimeMillis() - serverStartTime,
                    metrics = historyManager.calculateCurrentMetrics(),
                    persistence = persistence?.status() ?: PersistenceStatus(mode = "memory"),
                    demoTraffic = isDemoTrafficEnabled
                )
            )
        }

        get("/health-light") {
            call.respond(
                mapOf(
                    "status" to "UP",
                    "uptimeMs" to (System.currentTimeMillis() - serverStartTime).toString()
                )
            )
        }

        authRoutes(jwtService)
        demoRoutes(polyLanceUpstream)
        ruleRoutes(rateLimiter, webSocketManager, historyManager, jwtService, serverStartTime, clientTracker, persistence)
        simulateRoutes(rateLimiter, webSocketManager, historyManager, jwtService)
        eventsWebSocket(webSocketManager, jwtService)
    }

    // 7. Background Autonomous Maintenance Loop
    launch(Dispatchers.Default) {
        var cycle = 0
        while (isActive) {
            delay(30_000L) // Runs every 30 seconds
            cycle++
            try {
                rateLimiter.cleanupIdleLimiters()
                clientTracker.cleanExpired()
                if (cycle % 4 == 0 && startBackgroundMonitor) {
                    polyLanceUpstream.keepAlive()
                }
            } catch (_: Exception) {
            }
        }
    }

    // 8. 1-Second Metrics Broadcast Ticker
    launch(Dispatchers.Default) {
        while (isActive) {
            delay(1000L)
            try {
                if (webSocketManager.activeSubscriberCount() > 0) {
                    val metrics = historyManager.calculateCurrentMetrics()
                    val anomaly = historyManager.checkAggregateAnomaly(metrics.rps)
                    if (anomaly != null) {
                        webSocketManager.broadcast(GatewayEvent(type = "incident", incident = anomaly))
                    }
                    webSocketManager.broadcast(
                        GatewayEvent(
                            type = "metrics",
                            timestamp = System.currentTimeMillis() / 1000.0,
                            metrics = metrics
                        )
                    )
                    val clientList = clientTracker.getAllClients(isAdmin = false, rateLimiter.anomalyDetector)
                    if (clientList.isNotEmpty()) {
                        webSocketManager.broadcast(
                            GatewayEvent(
                                type = "client_update",
                                timestamp = System.currentTimeMillis() / 1000.0,
                                clients = clientList
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // 9. Optional Demo Traffic Generator (OFF by default: DEMO_TRAFFIC=false)
    if (isDemoTrafficEnabled) {
        launch(Dispatchers.Default) {
            delay(2000L) // Initial warm up delay
            val demoEndpoints = listOf(
                "/api/polylance/escrows",
                "/api/polylance/talents",
                "/api/users",
                "/api/orders",
                "/api/products"
            )
            val demoIps = listOf(
                "192.168.1.10",
                "192.168.1.25",
                "10.0.0.5",
                "172.16.52.20"
            )

            while (isActive) {
                delay(Random.nextLong(1500L, 3000L))
                try {
                    val endpoint = demoEndpoints[Random.nextInt(demoEndpoints.size)]
                    val ip = demoIps[Random.nextInt(demoIps.size)]
                    val start = System.currentTimeMillis()

                    val eval = rateLimiter.evaluate(endpoint, ip)
                    val status = if (eval.allowed) 200 else 429
                    val latency = Random.nextLong(18, 55)
                    val decision = if (eval.allowed) "allowed" else if (eval.action == "BLOCK") "sliding_window" else "token_bucket"

                    val log = historyManager.recordRequest(
                        clientId = "demo:$ip",
                        method = "GET",
                        path = endpoint,
                        status = status,
                        latencyMs = latency,
                        decision = decision,
                        source = "demo",
                        clientIp = ip
                    )

                    webSocketManager.broadcast(
                        GatewayEvent(
                            type = if (eval.allowed) "request" else "blocked_request",
                            id = log.id,
                            ip = log.clientId,
                            endpoint = endpoint,
                            method = "GET",
                            status = status,
                            latencyMs = latency,
                            decision = decision,
                            source = "demo",
                            timestamp = start / 1000.0
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
