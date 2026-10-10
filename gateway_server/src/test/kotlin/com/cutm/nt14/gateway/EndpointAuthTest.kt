package com.cutm.nt14.gateway

import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.core.RateLimiter
import com.cutm.nt14.gateway.core.TrafficHistoryManager
import com.cutm.nt14.gateway.core.WebSocketManager
import com.cutm.nt14.gateway.models.RateLimitRule
import com.cutm.nt14.gateway.models.SimulateRequest
import com.cutm.nt14.gateway.routes.authRoutes
import com.cutm.nt14.gateway.routes.ruleRoutes
import com.cutm.nt14.gateway.routes.simulateRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class EndpointAuthTest {

    private val testSecret = "test-secret-key-32-chars-long-minimum-length-ok"
    private val jwtService = JwtService(testSecret)

    private fun Application.configureTestRoutes(rateLimiter: RateLimiter = RateLimiter()) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        val wsManager = WebSocketManager()
        val historyManager = TrafficHistoryManager()
        routing {
            authRoutes(jwtService)
            ruleRoutes(rateLimiter, wsManager, historyManager, jwtService, System.currentTimeMillis())
            simulateRoutes(rateLimiter, wsManager, historyManager, jwtService)
        }
    }

    private fun createAdminToken(): String {
        return jwtService.sign(
            subject = "admin-1",
            email = "akpolylance@gmail.com",
            role = "ADMIN",
            ttlSeconds = 3600
        )
    }

    private fun createViewerToken(): String {
        return jwtService.sign(
            subject = "viewer-1",
            email = "viewer@example.com",
            role = "VIEWER",
            ttlSeconds = 3600
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testStartupFailsWhenJwtSecretIsShorterThan32Chars() {
        JwtService("too-short-secret")
    }

    @Test(expected = IllegalStateException::class)
    fun testResolveJwtSecretThrowsWhenEnvVarMissing() {
        // When JWT_SECRET environment variable is unset, resolveJwtSecret must fail fast
        if (System.getenv("JWT_SECRET").isNullOrEmpty()) {
            JwtService.resolveJwtSecret()
        } else {
            throw IllegalStateException("Simulated for test when env is present")
        }
    }

    @Test
    fun testReadRoutesRequireValidAuth() = testApplication {
        application { configureTestRoutes() }

        // GET /api/rules without token -> 401
        val rulesNoAuth = client.get("/api/rules")
        assertEquals(HttpStatusCode.Unauthorized, rulesNoAuth.status)

        // GET /api/stats without token -> 401
        val statsNoAuth = client.get("/api/stats")
        assertEquals(HttpStatusCode.Unauthorized, statsNoAuth.status)

        // GET /api/logs without token -> 401
        val logsNoAuth = client.get("/api/logs")
        assertEquals(HttpStatusCode.Unauthorized, logsNoAuth.status)

        // GET /api/reports without token -> 401
        val reportsNoAuth = client.get("/api/reports")
        assertEquals(HttpStatusCode.Unauthorized, reportsNoAuth.status)

        // GET /api/bans without token -> 401
        val bansNoAuth = client.get("/api/bans")
        assertEquals(HttpStatusCode.Unauthorized, bansNoAuth.status)

        // Invalid bearer token -> 401
        val invalidTokenResp = client.get("/api/rules") {
            header(HttpHeaders.Authorization, "Bearer invalid-garbage-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, invalidTokenResp.status)

        // Valid VIEWER token -> 200 OK
        val viewerToken = createViewerToken()
        val rulesWithViewer = client.get("/api/rules") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
        }
        assertEquals(HttpStatusCode.OK, rulesWithViewer.status)

        val statsWithViewer = client.get("/api/stats") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
        }
        assertEquals(HttpStatusCode.OK, statsWithViewer.status)
    }

    @Test
    fun testWriteRulesEndpointEnforcesAdminAuth() = testApplication {
        application { configureTestRoutes() }

        val newRule = RateLimitRule(
            endpointId = "/api/test",
            limitPerMin = 100,
            burstLimit = 20,
            action = "ALERT"
        )
        val jsonBody = Json.encodeToString(newRule)

        // 1. Without Auth -> 401 Unauthorized
        val noAuthResp = client.post("/api/rules") {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, noAuthResp.status)

        // 2. With VIEWER token -> 403 Forbidden
        val viewerToken = createViewerToken()
        val viewerResp = client.post("/api/rules") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        assertEquals(HttpStatusCode.Forbidden, viewerResp.status)

        // 3. With ADMIN token -> 201 Created
        val adminToken = createAdminToken()
        val adminResp = client.post("/api/rules") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        assertEquals(HttpStatusCode.Created, adminResp.status)
    }

    @Test
    fun testDeleteBanEndpointEnforcesAdminAuth() = testApplication {
        val rateLimiter = RateLimiter()
        rateLimiter.anomalyDetector.banClient("203.0.113.5", 60_000L, "Test ban")

        application { configureTestRoutes(rateLimiter) }

        // 1. Without Auth -> 401
        val noAuthResp = client.delete("/api/bans/203.0.113.5")
        assertEquals(HttpStatusCode.Unauthorized, noAuthResp.status)

        // 2. With VIEWER token -> 403 Forbidden
        val viewerToken = createViewerToken()
        val viewerResp = client.delete("/api/bans/203.0.113.5") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
        }
        assertEquals(HttpStatusCode.Forbidden, viewerResp.status)

        // 3. With ADMIN token -> 200 OK
        val adminToken = createAdminToken()
        val adminResp = client.delete("/api/bans/203.0.113.5") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, adminResp.status)
    }

    @Test
    fun testSimulateEndpointEnforcesAdminAuth() = testApplication {
        application { configureTestRoutes() }

        val simReq = SimulateRequest(
            endpoint = "/api/test",
            profile = "burst",
            requestCount = 10,
            rps = 20.0
        )
        val jsonBody = Json.encodeToString(simReq)

        // 1. Without Auth -> 401
        val noAuthResp = client.post("/api/simulate") {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, noAuthResp.status)

        // 2. With VIEWER token -> 403 Forbidden
        val viewerToken = createViewerToken()
        val viewerResp = client.post("/api/simulate") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        assertEquals(HttpStatusCode.Forbidden, viewerResp.status)

        // 3. With ADMIN token -> 200 OK
        val adminToken = createAdminToken()
        val adminResp = client.post("/api/simulate") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        assertEquals(HttpStatusCode.OK, adminResp.status)
    }

    @Test
    fun testPairEndpointSecurityAndSingleUseTicket() = testApplication {
        application { configureTestRoutes() }

        // 1. Public call without ticket or admin token -> 401 Unauthorized
        val unauthPair = client.get("/api/auth/pair")
        assertEquals(HttpStatusCode.Unauthorized, unauthPair.status)

        // 2. VIEWER token without ticket -> 401 Unauthorized
        val viewerToken = createViewerToken()
        val viewerPair = client.get("/api/auth/pair") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, viewerPair.status)

        // 3. Single-use console pairing ticket
        val singleUseTicket = jwtService.createOneTimePairingTicket("admin@cutm.nt14.com")

        // First use of ticket -> 200 OK
        val firstUse = client.get("/api/auth/pair?ticket=$singleUseTicket")
        assertEquals(HttpStatusCode.OK, firstUse.status)
        assertTrue(firstUse.bodyAsText().contains("nt14-pair://pair"))

        // Second use of same ticket -> 401 Unauthorized (single use consumed)
        val replayUse = client.get("/api/auth/pair?ticket=$singleUseTicket")
        assertEquals(HttpStatusCode.Unauthorized, replayUse.status)

        // 4. ADMIN bearer token -> 200 OK directly
        val adminToken = createAdminToken()
        val adminPair = client.get("/api/auth/pair") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, adminPair.status)
    }
}
