package com.cutm.nt14.gateway

import com.cutm.nt14.gateway.core.*
import com.cutm.nt14.gateway.models.ClientInfo
import com.cutm.nt14.gateway.models.CreateBanRequest
import com.cutm.nt14.gateway.models.RateLimitRule
import com.cutm.nt14.gateway.models.WhoAmIResponse
import com.cutm.nt14.gateway.routes.authRoutes
import com.cutm.nt14.gateway.routes.ruleRoutes
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ClientMonitoringTest {

    private val testSecret = "test-secret-key-32-chars-long-minimum-length-ok"
    private val jwtService = JwtService(testSecret)

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

    @Test
    fun testTrustedProxyResolvesRealIpWhenPeerIsTrusted() = testApplication {
        val clientTracker = ClientTracker()
        val rateLimiter = RateLimiter()
        val wsManager = WebSocketManager()
        val historyManager = TrafficHistoryManager()

        application {
            install(ContentNegotiation) { json() }
            routing {
                ruleRoutes(rateLimiter, wsManager, historyManager, jwtService, System.currentTimeMillis(), clientTracker)
            }
        }

        val adminToken = createAdminToken()

        // 1. Peer in testApplication is local/trusted. CF-Connecting-IP takes precedence.
        val respCf = client.get("/api/whoami") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            header("CF-Connecting-IP", "203.0.113.88")
            header("X-Forwarded-For", "198.51.100.1, 203.0.113.88")
        }
        assertEquals(HttpStatusCode.OK, respCf.status)
        val bodyCf = Json.decodeFromString<WhoAmIResponse>(respCf.bodyAsText())
        assertEquals("203.0.113.88", bodyCf.resolvedIp)

        // 2. Traverses X-Forwarded-For from right to left, picking the last untrusted IP
        val respXff = client.get("/api/whoami") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            header("X-Forwarded-For", "198.51.100.1, 203.0.113.99")
        }
        assertEquals(HttpStatusCode.OK, respXff.status)
        val bodyXff = Json.decodeFromString<WhoAmIResponse>(respXff.bodyAsText())
        assertEquals("203.0.113.99", bodyXff.resolvedIp)
    }

    @Test
    fun testUntrustedPeerSpoofedXForwardedForIsIgnored() {
        val trustedSet = setOf("127.0.0.1", "10.0.0.1")

        // Direct untrusted peer
        val untrustedPeer = "198.51.100.42"
        assertFalse(TrustedProxyResolver.isTrustedProxy(untrustedPeer, trustedSet))

        // Trusted peers
        assertTrue(TrustedProxyResolver.isTrustedProxy("127.0.0.1", trustedSet))
        assertTrue(TrustedProxyResolver.isTrustedProxy("10.0.0.5", trustedSet))
        assertTrue(TrustedProxyResolver.isTrustedProxy("192.168.1.50", trustedSet))
    }

    @Test
    fun testTwoDistinctIpsTrackedSeparatelyAndOnlyAbuserBanned() = runBlocking {
        val rateLimiter = RateLimiter()
        val clientTracker = ClientTracker()

        val endpoint = "/api/test"
        rateLimiter.setRule(RateLimitRule(endpoint, limitPerMin = 100, burstLimit = 20, action = "BLOCK"))

        val normalClient = "192.0.2.1"
        val abusiveClient = "192.0.2.2"

        // Normal client makes 2 requests
        val res1 = rateLimiter.evaluate(endpoint, normalClient)
        val res2 = rateLimiter.evaluate(endpoint, normalClient)
        rateLimiter.anomalyDetector.recordAndInspect(normalClient, endpoint, 200)
        clientTracker.recordRequest(normalClient, isThrottled = !res1.allowed)
        clientTracker.recordRequest(normalClient, isThrottled = !res2.allowed)
        assertTrue("Normal client requests should be allowed", res1.allowed && res2.allowed)

        // Abusive client floods with 85 rapid requests to trip DDoS detector
        for (i in 1..85) {
            val res = rateLimiter.evaluate(endpoint, abusiveClient)
            rateLimiter.anomalyDetector.recordAndInspect(abusiveClient, endpoint, if (res.allowed) 200 else 429)
            clientTracker.recordRequest(abusiveClient, isThrottled = !res.allowed)
        }

        // Verify abusive client tripped anomaly detector and got banned
        val banRecord = rateLimiter.anomalyDetector.checkBan(abusiveClient)
        assertNotNull("Abusive client must be banned", banRecord)

        // Verify normal client was NOT banned
        val normalBan = rateLimiter.anomalyDetector.checkBan(normalClient)
        assertNull("Normal client must NOT be banned", normalBan)

        // Verify clientTracker tracking
        val clients = clientTracker.getAllClients(isAdmin = true, rateLimiter.anomalyDetector)
        val trackedAbuser = clients.find { it.id == abusiveClient }
        val trackedNormal = clients.find { it.id == normalClient }

        assertNotNull(trackedAbuser)
        assertNotNull(trackedNormal)
        assertEquals("banned", trackedAbuser!!.status)
        assertEquals("active", trackedNormal!!.status)
        assertEquals("192.0.***.***", trackedAbuser.maskedId)
    }

    @Test
    fun testWhoAmIRouteSecurity() = testApplication {
        val clientTracker = ClientTracker()
        val rateLimiter = RateLimiter()
        val wsManager = WebSocketManager()
        val historyManager = TrafficHistoryManager()

        application {
            install(ContentNegotiation) { json() }
            routing {
                ruleRoutes(rateLimiter, wsManager, historyManager, jwtService, System.currentTimeMillis(), clientTracker)
            }
        }

        // 1. Without auth -> 401
        val noAuth = client.get("/api/whoami")
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)

        // 2. With VIEWER token -> 403 Forbidden
        val viewerToken = createViewerToken()
        val viewerResp = client.get("/api/whoami") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
        }
        assertEquals(HttpStatusCode.Forbidden, viewerResp.status)

        // 3. With ADMIN token -> 200 OK
        val adminToken = createAdminToken()
        val adminResp = client.get("/api/whoami") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, adminResp.status)
        val whoAmI = Json.decodeFromString<WhoAmIResponse>(adminResp.bodyAsText())
        assertNotNull(whoAmI.resolvedIp)
        assertEquals("akpolylance@gmail.com", whoAmI.authenticatedUser)
        assertEquals("ADMIN", whoAmI.role)
    }

    @Test
    fun testClientsRouteMaskingAndManualBanLifecycle() = testApplication {
        val clientTracker = ClientTracker()
        val rateLimiter = RateLimiter()
        val wsManager = WebSocketManager()
        val historyManager = TrafficHistoryManager()

        clientTracker.recordRequest("198.51.100.77", isThrottled = false)
        clientTracker.recordRequest("user:operator@cutm.nt14.com", isThrottled = false)

        application {
            install(ContentNegotiation) { json() }
            routing {
                ruleRoutes(rateLimiter, wsManager, historyManager, jwtService, System.currentTimeMillis(), clientTracker)
            }
        }

        val viewerToken = createViewerToken()
        val adminToken = createAdminToken()

        // 1. VIEWER receives masked IDs
        val viewerResp = client.get("/api/clients") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
        }
        assertEquals(HttpStatusCode.OK, viewerResp.status)
        val viewerClients = Json.decodeFromString<List<ClientInfo>>(viewerResp.bodyAsText())
        assertTrue("IP must be masked for viewer", viewerClients.any { it.id == "198.51.***.***" })
        assertTrue("Email must be masked for viewer", viewerClients.any { it.id.contains("***@cutm.nt14.com") })

        // 2. ADMIN receives real unmasked IDs
        val adminResp = client.get("/api/clients") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, adminResp.status)
        val adminClients = Json.decodeFromString<List<ClientInfo>>(adminResp.bodyAsText())
        assertTrue("IP must be unmasked for admin", adminClients.any { it.id == "198.51.100.77" })
        assertTrue("Email must be unmasked for admin", adminClients.any { it.id == "user:operator@cutm.nt14.com" })

        // 3. ADMIN manually bans a client
        val banReq = CreateBanRequest(clientId = "198.51.100.77", durationMinutes = 30, reason = "Manual ban test")
        val banResp = client.post("/api/bans") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(banReq))
        }
        assertEquals(HttpStatusCode.Created, banResp.status)
        assertNotNull(rateLimiter.anomalyDetector.checkBan("198.51.100.77"))

        // 4. ADMIN lifts the ban
        val unbanResp = client.delete("/api/bans/198.51.100.77") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, unbanResp.status)
        assertNull(rateLimiter.anomalyDetector.checkBan("198.51.100.77"))
    }
}
