package com.cutm.nt14.gateway

import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.core.PolyLanceUpstream
import com.cutm.nt14.gateway.db.*
import com.cutm.nt14.gateway.models.*
import com.cutm.nt14.gateway.monitoring.TargetMonitor
import com.sun.net.httpserver.HttpServer
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class PersistenceAndMonitoringTest {

    companion object {
        private lateinit var pg: EmbeddedPostgres
        private lateinit var db: GatewayDatabase
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val jwtService = JwtService("test-secret-key-at-least-32-bytes-long-for-persistence")

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            pg = EmbeddedPostgres.builder().start()
            val jdbcUrl = pg.getJdbcUrl("postgres", "postgres")
            db = GatewayDatabase.create(JdbcSettings(jdbcUrl, "postgres", "postgres"))
            db.migrate()
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            if (::db.isInitialized) db.close()
            if (::pg.isInitialized) pg.close()
        }
    }

    @Before
    fun cleanTables() {
        db.withConnection { c ->
            c.createStatement().use { st ->
                st.execute("TRUNCATE request_logs, metrics_1m, clients, bans, incidents, probe_results RESTART IDENTITY CASCADE")
                st.execute("DELETE FROM monitored_targets")
                st.execute(
                    """INSERT INTO monitored_targets (name, url, method, expected_status, interval_sec, timeout_ms) VALUES
                       ('PolyLance health',  'https://polylance-fv-1-45wy.onrender.com/health',   'GET', 200, 30, 10000),
                       ('PolyLance escrows', 'https://polylance-fv-1-45wy.onrender.com/api/jobs', 'GET', 200, 30, 10000)"""
                )
            }
        }
    }

    @Test
    fun testFreshDatabaseMigratesWithV1RevertedAndV4SetsTimeout10000AndScrubsEmails() {
        db.withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT column_default FROM information_schema.columns WHERE table_name = 'monitored_targets' AND column_name = 'timeout_ms'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("10000", rs.getString(1))
                }
                st.executeQuery("SELECT count(*) FROM monitored_targets WHERE timeout_ms <> 10000").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(0L, rs.getLong(1))
                }
            }
        }
    }

    @Test
    fun testBatchingAndOverflowDropAndCount() {
        val writer = RequestLogWriter(db, capacity = 50, batchSize = 20, flushIntervalMs = 10_000L)
        val now = System.currentTimeMillis()
        val rawIp = "203.0.113.42"
        val masked = IpPrivacy.storedClientId(rawIp)
        val ipHash = IpPrivacy.hash(rawIp)

        var accepted = 0
        var rejected = 0
        repeat(80) { i ->
            val ok = writer.offer(
                LogEntry(
                    id = LogIdGenerator.next(now + i),
                    tsMs = now + i,
                    clientId = masked,
                    clientIpHash = ipHash,
                    method = "GET",
                    path = "/api/polylance/escrows",
                    status = if (i % 5 == 0) 429 else 200,
                    latencyMs = 25L + (i % 10),
                    decision = if (i % 5 == 0) "token_bucket" else "allowed",
                    source = "real",
                    upstream = "https://polylance-fv-1-45wy.onrender.com/api/jobs"
                )
            )
            if (ok) accepted++ else rejected++
        }

        assertEquals(50, accepted)
        assertEquals(30, rejected)
        assertEquals(50, writer.pending())
        assertEquals(30L, writer.dropped.get())

        val flushed = writer.flushNow()
        assertEquals(50, flushed)
        assertEquals(0, writer.pending())
        assertEquals(50L, writer.written.get())
        assertEquals(3L, writer.batches.get()) // 20 + 20 + 10

        db.withConnection { c ->
            assertEquals(50L, c.countRows("request_logs"))
            assertEquals(1L, c.countRows("clients"))
            // Verify privacy: raw IP is never stored in clear text
            c.prepareStatement("SELECT count(*) FROM request_logs WHERE client_id LIKE '%203.0.113.42%'").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); assertEquals(0L, rs.getLong(1)) }
            }
        }
    }

    @Test
    fun testRetention7DaysLogsAnd90DaysMetrics() {
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val writer = RequestLogWriter(db, capacity = 100, batchSize = 100)

        // Insert request_logs at 10d, 8d (expired > 7d) and 6d, 1h (retained <= 7d)
        listOf(10 * dayMs, 8 * dayMs, 6 * dayMs, 3_600_000L).forEachIndexed { idx, age ->
            val ts = now - age
            writer.offer(
                LogEntry(
                    id = LogIdGenerator.next(ts + idx),
                    tsMs = ts,
                    clientId = "user:test@cutm.nt14.com",
                    clientIpHash = null,
                    method = "GET",
                    path = "/api/polylance/escrows",
                    status = 200,
                    latencyMs = 30L,
                    decision = "allowed",
                    source = "real",
                    upstream = "https://polylance-fv-1-45wy.onrender.com/api/jobs"
                )
            )
        }
        writer.flushNow()

        // Insert metrics_1m at 100d, 91d (expired > 90d) and 89d, 1h (retained <= 90d)
        db.withConnection { c ->
            listOf(100 * dayMs, 91 * dayMs, 89 * dayMs, 3_600_000L).forEach { age ->
                c.prepareStatement(
                    """INSERT INTO metrics_1m (minute, endpoint, source, requests, allowed, throttled, errors, p50, p95, latency_sum_ms)
                       VALUES (date_trunc('minute', ?::timestamptz), '/api/polylance/escrows', 'real', 10, 9, 1, 0, 20, 45, 250)"""
                ).use { ps ->
                    ps.setTimestamp(1, java.sql.Timestamp(now - age))
                    ps.executeUpdate()
                }
            }
        }

        val res = RetentionJob(db).run(now)
        assertEquals(2, res.logsDeleted)
        assertEquals(2, res.metricsDeleted)

        db.withConnection { c ->
            assertEquals(2L, c.countRows("request_logs"))
            assertEquals(2L, c.countRows("metrics_1m"))
        }
    }

    @Test
    fun testKeysetPaginationFilteringAndCsvExport() = testApplication {
        val adminToken = jwtService.sign("admin_1", "akpolylance@gmail.com", "ADMIN")
        val now = System.currentTimeMillis()
        val writer = RequestLogWriter(db, capacity = 200, batchSize = 200)

        // 25 real logs + 10 demo logs
        val realIds = mutableListOf<Long>()
        repeat(25) { i ->
            val ts = now - (30 - i) * 1000L
            val id = LogIdGenerator.next(ts)
            realIds += id
            val ip = if (i % 2 == 0) "198.51.100.77" else "198.51.100.88"
            writer.offer(
                LogEntry(
                    id = id,
                    tsMs = ts,
                    clientId = IpPrivacy.storedClientId(ip),
                    clientIpHash = IpPrivacy.hash(ip),
                    method = "GET",
                    path = "/api/polylance/escrows",
                    status = if (i % 5 == 0) 429 else 200,
                    latencyMs = 20L + i,
                    decision = if (i % 5 == 0) "token_bucket" else "allowed",
                    source = "real",
                    upstream = "https://polylance-fv-1-45wy.onrender.com/api/jobs"
                )
            )
        }
        repeat(10) { i ->
            val ts = now - (10 - i) * 500L
            writer.offer(
                LogEntry(
                    id = LogIdGenerator.next(ts),
                    tsMs = ts,
                    clientId = "demo:192.168.1.10",
                    clientIpHash = IpPrivacy.hash("192.168.1.10"),
                    method = "GET",
                    path = "/api/users",
                    status = 200,
                    latencyMs = 15L,
                    decision = "allowed",
                    source = "demo",
                    upstream = null
                )
            )
        }
        writer.flushNow()

        application { module(customDatabase = db, startBackgroundMonitor = false, customJwtService = jwtService) }

        // Keyset paginate through all 25 real logs (10 + 10 + 5)
        val collected = mutableListOf<RequestLogDto>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val url = "/api/logs?limit=10&source=real" + (cursor?.let { "&cursor=$it" } ?: "")
            val resp = client.get(url) { header(HttpHeaders.Authorization, "Bearer $adminToken") }
            assertEquals(HttpStatusCode.OK, resp.status)
            val page = json.decodeFromString<LogsPage>(resp.bodyAsText())
            assertEquals("real", page.source)
            assertEquals("postgres", page.persistence)
            collected += page.items
            pages++
            cursor = page.nextCursor
            if (cursor == null) break
        }
        assertEquals(3, pages)
        assertEquals(25, collected.size)
        assertEquals(realIds.reversed().map { it.toString() }, collected.map { it.id })

        // Filter by source=demo -> 10 logs
        val demoPage = json.decodeFromString<LogsPage>(
            client.get("/api/logs?limit=50&source=demo") { header(HttpHeaders.Authorization, "Bearer $adminToken") }.bodyAsText()
        )
        assertEquals(10, demoPage.items.size)
        assertTrue(demoPage.items.all { it.source == "demo" })

        // Filter by status=429 -> 5 logs
        val throttledPage = json.decodeFromString<LogsPage>(
            client.get("/api/logs?limit=50&status=429&source=real") { header(HttpHeaders.Authorization, "Bearer $adminToken") }.bodyAsText()
        )
        assertEquals(5, throttledPage.items.size)
        assertTrue(throttledPage.items.all { it.status == 429 })

        // Filter by raw IP (resolved via HMAC hash lookup without storing raw IP) -> 13 logs
        val clientPage = json.decodeFromString<LogsPage>(
            client.get("/api/logs?limit=50&client=198.51.100.77") { header(HttpHeaders.Authorization, "Bearer $adminToken") }.bodyAsText()
        )
        assertEquals(13, clientPage.items.size)

        // CSV Export (Admin)
        val csvResp = client.get("/api/export?format=csv&source=real") { header(HttpHeaders.Authorization, "Bearer $adminToken") }
        assertEquals(HttpStatusCode.OK, csvResp.status)
        val csvLines = csvResp.bodyAsText().trim().lines()
        assertEquals("id,ts,client_id,client_ip_hash,method,path,status,latency_ms,decision,source,upstream", csvLines.first())
        assertEquals(26, csvLines.size) // 1 header + 25 real rows
    }

    @Test
    fun testRbacOnTargetsExportMetricsAndReports() = testApplication {
        val viewerToken = jwtService.sign("viewer_1", "viewer@cutm.nt14.com", "VIEWER")
        val adminToken = jwtService.sign("admin_1", "akpolylance@gmail.com", "ADMIN")

        application { module(customDatabase = db, startBackgroundMonitor = false, customJwtService = jwtService) }

        // 1. Unauthenticated -> 401
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/logs").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/metrics").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/reports").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/targets/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/targets").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/export").status)

        // 2. Viewer -> can read /api/logs, /api/metrics, /api/reports, /api/targets/status, but 403 on /api/targets CRUD and /api/export
        assertEquals(HttpStatusCode.OK, client.get("/api/logs") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/metrics?range=1h") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/reports?range=1h") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }.status)
        val statusResp = client.get("/api/targets/status") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }
        assertEquals(HttpStatusCode.OK, statusResp.status)
        val initialTargets = json.decodeFromString<List<TargetStatusDto>>(statusResp.bodyAsText())
        assertEquals(2, initialTargets.size) // PolyLance health + PolyLance escrows

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/targets") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/targets") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TargetRequest(name = "Forbidden", url = "https://example.com")))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/export") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }.status)

        // 3. Admin -> full CRUD on /api/targets
        val createResp = client.post("/api/targets") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TargetRequest(name = "Custom Upstream", url = "https://polylance-fv-1-45wy.onrender.com/api/sync", intervalSec = 30)))
        }
        assertEquals(HttpStatusCode.Created, createResp.status)
        val created = json.decodeFromString<MonitoredTarget>(createResp.bodyAsText())
        assertEquals("Custom Upstream", created.name)

        val updateResp = client.put("/api/targets/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(TargetRequest(name = "Custom Upstream v2", url = created.url, intervalSec = 15)))
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)

        val deleteResp = client.delete("/api/targets/${created.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, deleteResp.status)
    }

    @Test
    fun testTargetDownIncidentCreatedWithin3ProbesAndResolvedOnRecovery() = runBlocking {
        val statusCode = AtomicInteger(200)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val code = statusCode.get()
            val bytes = """{"status":"$code"}""".toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val targets = TargetStore(db)
            val probes = ProbeStore(db)
            val incidents = IncidentStore(db)
            val monitor = TargetMonitor(targets, probes, incidents, ws = null, history = null, failThreshold = 3, failRetrySec = 5)

            val target = targets.create(
                TargetRequest(
                    name = "Local Stoppable API",
                    url = "http://127.0.0.1:$port/health",
                    method = "GET",
                    expectedStatus = 200,
                    intervalSec = 10,
                    timeoutMs = 2000
                )
            )

            // 1. Target is UP initially
            val up1 = monitor.probeOnce(target)
            assertEquals(true, up1.up)
            assertEquals(0, up1.consecutiveFailures)
            assertNull(up1.openIncidentId)

            // 2. Stop target (returns 503) -> 3 consecutive failures create a CRITICAL incident
            statusCode.set(503)
            val down1 = monitor.probeOnce(target)
            assertEquals(false, down1.up)
            assertEquals("up->down", down1.transition)
            assertEquals(1, down1.consecutiveFailures)
            assertNull(down1.openIncidentId)

            val down2 = monitor.probeOnce(target)
            assertEquals(false, down2.up)
            assertEquals(2, down2.consecutiveFailures)
            assertNull(down2.openIncidentId)

            val down3 = monitor.probeOnce(target)
            assertEquals(false, down3.up)
            assertEquals(3, down3.consecutiveFailures)
            assertNotNull("Expected incident after 3 consecutive failed probes", down3.openIncidentId)

            val storedIncidents = incidents.recent(10)
            assertEquals(1, storedIncidents.size)
            assertEquals("Target Down", storedIncidents.first().type)
            assertEquals("CRITICAL", storedIncidents.first().severity)
            assertEquals(target.id, storedIncidents.first().targetId)
            assertNull(storedIncidents.first().resolvedAt)
            println("EVIDENCE_DOWN_INCIDENT_JSON=" + json.encodeToString(storedIncidents.first()))

            // 3. Target recovers -> incident is resolved and transition is down->up
            statusCode.set(200)
            val recovered = monitor.probeOnce(target)
            assertEquals(true, recovered.up)
            assertEquals("down->up", recovered.transition)
            assertEquals(0, recovered.consecutiveFailures)
            assertNull(recovered.openIncidentId)
            assertEquals(5, recovered.recentLatencies.size)

            val afterRecovery = incidents.recent(10).first()
            assertNotNull("Incident should be marked resolved after recovery", afterRecovery.resolvedAt)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testLogsAndWebSocketSnapshotSurviveGatewayRestart() {
        val adminToken = jwtService.sign("admin_1", "akpolylance@gmail.com", "ADMIN")

        // Local mock PolyLance upstream so proxy routes return real 200 responses deterministically
        val upstreamServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstreamServer.createContext("/api/jobs") { ex ->
            val body = """{"jobs":[{"id":"escrow_1","title":"Smart Contract Audit","client":"0xabc","freelancer":"0xdef","amountEth":"250.0","paymentTokenSymbol":"POL","status":"Funded","createdAt":"1700000000000"}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        upstreamServer.createContext("/health") { ex ->
            val body = """{"status":"UP"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        upstreamServer.start()

        try {
            val testUpstream = PolyLanceUpstream("http://127.0.0.1:${upstreamServer.address.port}")

            // --- INSTANCE 1: send real proxied traffic, then shut down ---
            val firstInstanceIds = mutableListOf<String>()
            testApplication {
                application {
                    module(customDatabase = db, startBackgroundMonitor = false, customUpstream = testUpstream, customJwtService = jwtService)
                }
                repeat(6) { i ->
                    val r = client.get("/api/polylance/escrows") {
                        header("X-Forwarded-For", "198.51.100.200")
                    }
                    // Default rule on /api/polylance/escrows has burstLimit=5: first 5 -> 200 OK, 6th -> 429 Too Many Requests
                    if (i < 5) assertEquals(HttpStatusCode.OK, r.status) else assertEquals(HttpStatusCode.TooManyRequests, r.status)
                }
                val page1 = json.decodeFromString<LogsPage>(
                    client.get("/api/logs?limit=20&source=real") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText()
                )
                assertEquals(6, page1.items.size)
                firstInstanceIds.addAll(page1.items.map { it.id })
                println("EVIDENCE_INSTANCE_1_LOGS_COUNT=${page1.items.size} FIRST_ID=${page1.items.first().id} SOURCE=${page1.items.first().source} UPSTREAM=${page1.items.first().upstream}")

                // Register and probe the local upstream health endpoint via Admin API so probe_results are recorded before restart
                val createTargetResp = client.post("/api/targets") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(TargetRequest(name = "Local PolyLance Health", url = "http://127.0.0.1:${upstreamServer.address.port}/health", intervalSec = 30)))
                }
                assertEquals(HttpStatusCode.Created, createTargetResp.status)
                val createdTarget = json.decodeFromString<MonitoredTarget>(createTargetResp.bodyAsText())
                val probeResp = client.post("/api/targets/${createdTarget.id}/probe") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }
                assertEquals(HttpStatusCode.OK, probeResp.status)
            }

            // --- INSTANCE 2: fresh Ktor application on the same PostgreSQL DB ---
            testApplication {
                application {
                    module(customDatabase = db, startBackgroundMonitor = false, customUpstream = testUpstream, customJwtService = jwtService)
                }

                // 1. /api/logs still returns all 6 logs from Instance 1
                val pageAfterRestart = json.decodeFromString<LogsPage>(
                    client.get("/api/logs?limit=20&source=real") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText()
                )
                assertEquals("postgres", pageAfterRestart.persistence)
                assertEquals(firstInstanceIds, pageAfterRestart.items.map { it.id })
                assertTrue(pageAfterRestart.items.all { it.source == "real" && it.upstream?.endsWith("/api/jobs") == true })
                assertEquals(5, pageAfterRestart.items.count { it.status == 200 })
                assertEquals(1, pageAfterRestart.items.count { it.status == 429 })
                println("EVIDENCE_INSTANCE_2_AFTER_RESTART_LOGS_COUNT=${pageAfterRestart.items.size} IDS_MATCH=${firstInstanceIds == pageAfterRestart.items.map { it.id }} PERSISTENCE=${pageAfterRestart.persistence}")

                // 2. /api/metrics & /api/reports return aggregated data from metrics_1m
                val metricsSeries = json.decodeFromString<MetricsSeriesDto>(
                    client.get("/api/metrics?range=1h&source=real") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText()
                )
                assertTrue(metricsSeries.points.isNotEmpty())
                assertEquals(6L, metricsSeries.points.sumOf { it.requests })

                val report = json.decodeFromString<TrafficReportDto>(
                    client.get("/api/reports?range=1h&source=real") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText()
                )
                assertEquals("postgres", report.persistence)
                assertEquals(6L, report.totalRequests)

                // 3. WebSocket /ws/events snapshot comes from PostgreSQL after restart
                val wsClient = createClient { install(WebSockets) }
                val session = wsClient.webSocketSession("/ws/events?token=$adminToken")
                val frame = session.incoming.receive() as Frame.Text
                val snapshot = json.decodeFromString<GatewayEvent>(frame.readText())
                assertEquals("snapshot", snapshot.type)
                assertEquals("postgres", snapshot.persistence)
                assertEquals("db", snapshot.snapshotFrom)
                assertEquals(6, snapshot.logs?.size)
                assertEquals(3, snapshot.targets?.size)
                session.close()

                // 4. Verify admin can ban a client from GET /api/clients even when client IDs are masked/hashed in storage
                val clientsAfterRestart = json.decodeFromString<List<ClientInfo>>(
                    client.get("/api/clients") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText()
                )
                assertTrue(clientsAfterRestart.isNotEmpty())
                val storedClient = clientsAfterRestart.first()
                assertTrue(storedClient.id.startsWith("iphash:"))
                assertEquals("ip:198.51.100.x", storedClient.maskedId)

                val banResp = client.post("/api/bans") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(CreateBanRequest(clientId = storedClient.id, reason = "Admin ban from Clients tab", durationMinutes = 15)))
                }
                assertEquals(HttpStatusCode.Created, banResp.status)

                // Subsequent request from 198.51.100.200 on an unexhausted route (/api/polylance/talents) is blocked by the ban
                val blockedByBan = client.get("/api/polylance/talents") {
                    header("X-Forwarded-For", "198.51.100.200")
                }
                assertEquals(HttpStatusCode.TooManyRequests, blockedByBan.status)

                // 5. Print live PostgreSQL SELECT count(*) evidence across all 8 schema tables
                db.withConnection { c ->
                    println("=== POSTGRESQL DURABLE STORAGE EVIDENCE (SELECT count(*)) ===")
                    for (tbl in listOf("request_logs", "metrics_1m", "clients", "bans", "rules", "incidents", "monitored_targets", "probe_results")) {
                        println("SELECT count(*) FROM $tbl => ${c.countRows(tbl)}")
                    }
                    println("=============================================================")
                }
            }
        } finally {
            upstreamServer.stop(0)
        }
    }

    @Test
    fun testThrottleVsErrorMetricSeparationAndLatencyBreakdown() {
        val adminToken = jwtService.sign("admin_1", "akpolylance@gmail.com", "ADMIN")
        val upstreamStatus = AtomicInteger(200)
        val upstreamServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstreamServer.createContext("/api/jobs") { ex ->
            val code = upstreamStatus.get()
            val body = """{"jobs":[]}""".toByteArray()
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        upstreamServer.start()

        try {
            val testUpstream = PolyLanceUpstream("http://127.0.0.1:${upstreamServer.address.port}")
            testApplication {
                application {
                    module(customDatabase = db, startBackgroundMonitor = false, customUpstream = testUpstream, customJwtService = jwtService)
                }

                // Send 5 allowed (200) + 5 throttled (429) requests from client 198.51.100.50 (burstLimit=5 on /api/polylance/escrows)
                repeat(10) { i ->
                    val r = client.get("/api/polylance/escrows") {
                        header("X-Forwarded-For", "198.51.100.50")
                    }
                    if (i < 5) {
                        assertEquals(HttpStatusCode.OK, r.status)
                        assertNotNull(r.headers["X-Limiter-Ms"])
                        assertNotNull(r.headers["X-Upstream-Ms"])
                        assertNotNull(r.headers["X-Total-Ms"])
                    } else {
                        assertEquals(HttpStatusCode.TooManyRequests, r.status)
                        assertNotNull(r.headers["X-Limiter-Ms"])
                        assertNull(r.headers["X-Upstream-Ms"])
                        assertNotNull(r.headers["X-Total-Ms"])
                    }
                }

                // Verify /health separates errorRate (0.0, since zero 5xx occurred) from throttleRate (0.5 = 50%)
                val healthAfter429 = json.decodeFromString<HealthResponse>(client.get("/health").bodyAsText())
                val m1 = healthAfter429.metrics!!
                assertEquals(5L, m1.allowed)
                assertEquals(5L, m1.throttled)
                assertEquals(0L, m1.serverErrors)
                assertEquals(0.0, m1.errorRate, 0.001)
                assertEquals(0.5, m1.throttleRate, 0.001)

                // Now trigger 2 upstream 502 Bad Gateway responses from a second client
                upstreamStatus.set(502)
                repeat(2) {
                    val r = client.get("/api/polylance/escrows") {
                        header("X-Forwarded-For", "198.51.100.51")
                    }
                    assertEquals(HttpStatusCode.BadGateway, r.status)
                }

                val healthAfter502 = json.decodeFromString<HealthResponse>(client.get("/health").bodyAsText())
                val m2 = healthAfter502.metrics!!
                assertEquals(7L, m2.allowed)
                assertEquals(5L, m2.throttled)
                assertEquals(2L, m2.serverErrors)
                assertTrue("errorRate must reflect 5xx server failures (> 0)", m2.errorRate > 0.0)
                assertTrue("throttleRate must remain separate from errorRate", m2.throttleRate > m2.errorRate)

                // Verify /api/logs exposes limiterMs, upstreamMs (null on 429, non-null on 200), and totalMs
                val logsPage = json.decodeFromString<LogsPage>(
                    client.get("/api/logs?limit=20&source=real") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText()
                )
                assertEquals(12, logsPage.items.size)
                val allowedLogs = logsPage.items.filter { it.status == 200 }
                val throttledLogs = logsPage.items.filter { it.status == 429 }
                assertEquals(5, allowedLogs.size)
                assertEquals(5, throttledLogs.size)
                assertTrue(allowedLogs.all { it.limiterMs != null && it.upstreamMs != null && it.totalMs != null })
                assertTrue(throttledLogs.all { it.limiterMs != null && it.upstreamMs == null && it.totalMs != null })

                // Verify /api/reports exposes throttleRate vs errorRate and latency percentiles
                val report = json.decodeFromString<TrafficReportDto>(
                    client.get("/api/reports?range=1h&source=real") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }.bodyAsText()
                )
                assertEquals(12L, report.totalRequests)
                assertEquals(5L, report.blockedRequests)
                assertEquals(2L, report.serverErrors)
                assertTrue(report.throttleRate > report.errorRate)
            }
        } finally {
            upstreamServer.stop(0)
        }
    }

    @Test
    fun testIncidentResolveEndpointSyntheticTargetFilterAndAutoResolveOnDelete() = testApplication {
        val adminToken = jwtService.sign("admin_1", "akpolylance@gmail.com", "ADMIN")
        val viewerToken = jwtService.sign("viewer_1", "viewer@cutm.nt14.com", "VIEWER")

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fail") { ex ->
            val bytes = """{"error":"down"}""".toByteArray()
            ex.sendResponseHeaders(503, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            application {
                module(customDatabase = db, startBackgroundMonitor = false, customJwtService = jwtService)
            }

            // 1. Create a synthetic failing target
            val createResp = client.post("/api/targets") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        TargetRequest(
                            name = "Synthetic Probe Test",
                            url = "http://127.0.0.1:${server.address.port}/fail",
                            intervalSec = 30,
                            source = "test",
                            synthetic = true
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.Created, createResp.status)
            val created = json.decodeFromString<MonitoredTarget>(createResp.bodyAsText())
            assertTrue(created.synthetic)
            assertEquals("test", created.source)

            // 2. Verify synthetic target is excluded from GET /api/targets/status by default, and included when includeSynthetic=true
            val defaultTargets = json.decodeFromString<List<TargetStatusDto>>(
                client.get("/api/targets/status") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }.bodyAsText()
            )
            assertTrue("Synthetic target must be excluded by default", defaultTargets.none { it.id == created.id })

            val allTargets = json.decodeFromString<List<TargetStatusDto>>(
                client.get("/api/targets/status?includeSynthetic=true") { header(HttpHeaders.Authorization, "Bearer $viewerToken") }.bodyAsText()
            )
            assertTrue("Synthetic target must be included when includeSynthetic=true", allTargets.any { it.id == created.id })

            // 3. Probe 3 times to trigger a Target Down incident
            repeat(3) {
                client.post("/api/targets/${created.id}/probe") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }
            }
            val openIncidents = json.decodeFromString<List<IncidentDto>>(
                client.get("/api/incidents") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }.bodyAsText()
            ).filter { it.targetId == created.id && it.resolvedAt == null }
            assertEquals(1, openIncidents.size)
            val incidentId = openIncidents.first().id

            // 4. Viewer cannot resolve (403), Admin can resolve via PATCH /api/incidents/{id}/resolve
            val viewerResolve = client.patch("/api/incidents/$incidentId/resolve") {
                header(HttpHeaders.Authorization, "Bearer $viewerToken")
            }
            assertEquals(HttpStatusCode.Forbidden, viewerResolve.status)

            val adminResolve = client.patch("/api/incidents/$incidentId/resolve") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
            }
            assertEquals(HttpStatusCode.OK, adminResolve.status)
            val resolvedIncident = json.decodeFromString<IncidentDto>(adminResolve.bodyAsText())
            assertNotNull(resolvedIncident.resolvedAt)

            // 5. Trigger another open incident on a second target, then DELETE /api/targets/{id} and verify auto-resolution
            val secondTargetResp = client.post("/api/targets") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        TargetRequest(
                            name = "Auto-Resolve Delete Test",
                            url = "http://127.0.0.1:${server.address.port}/fail",
                            intervalSec = 30,
                            synthetic = true
                        )
                    )
                )
            }
            val secondTarget = json.decodeFromString<MonitoredTarget>(secondTargetResp.bodyAsText())
            repeat(3) {
                client.post("/api/targets/${secondTarget.id}/probe") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }
            }
            val openBeforeDelete = json.decodeFromString<List<IncidentDto>>(
                client.get("/api/incidents") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }.bodyAsText()
            ).filter { it.targetId == secondTarget.id && it.resolvedAt == null }
            assertEquals(1, openBeforeDelete.size)

            val delResp = client.delete("/api/targets/${secondTarget.id}") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
            }
            assertEquals(HttpStatusCode.OK, delResp.status)

            val openAfterDelete = json.decodeFromString<List<IncidentDto>>(
                client.get("/api/incidents") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }.bodyAsText()
            ).filter { it.resolvedAt == null }
            assertEquals("Deleting a target must auto-resolve all its open incidents", 0, openAfterDelete.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testIpHashSaltFailsFastWhenMissingAndHealthLightWorks() = testApplication {
        // 1. Verify IpPrivacy.ensureConfigured fails fast when IP_HASH_SALT is missing or blank
        val missingError = runCatching { IpPrivacy.ensureConfigured { null } }.exceptionOrNull()
        assertTrue(missingError is IllegalStateException)
        val blankError = runCatching { IpPrivacy.ensureConfigured { "   " } }.exceptionOrNull()
        assertTrue(blankError is IllegalStateException)

        // 2. Verify /health-light returns 200 OK with no DB dependency
        application {
            module(customDatabase = null, startBackgroundMonitor = false, customJwtService = jwtService)
        }
        val lightResp = client.get("/health-light")
        assertEquals(HttpStatusCode.OK, lightResp.status)
        assertTrue(lightResp.bodyAsText().contains("\"status\""))
        assertTrue(lightResp.bodyAsText().contains("UP"))
    }

    @Test
    fun testBanByClientIpHashBlocksClientInRateLimiter() = testApplication {
        val adminToken = jwtService.sign("admin_1", "akpolylance@gmail.com", "ADMIN")
        val upstreamServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstreamServer.createContext("/api/jobs") { ex ->
            val body = """{"jobs":[]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        upstreamServer.start()

        try {
            val testUpstream = PolyLanceUpstream("http://127.0.0.1:${upstreamServer.address.port}")
            application {
                module(customDatabase = db, startBackgroundMonitor = false, customUpstream = testUpstream, customJwtService = jwtService)
            }

            val rawIp = "203.0.113.195"
            val rawHash = IpPrivacy.hash(rawIp)

            // 1. Before ban, client request is allowed (200 OK)
            val initialResp = client.get("/api/polylance/escrows") {
                header("X-Forwarded-For", rawIp)
            }
            assertEquals(HttpStatusCode.OK, initialResp.status)

            // 2. Fetch /api/clients as ADMIN and confirm raw IP is masked while clientIpHash is exposed
            val clients = json.decodeFromString<List<ClientInfo>>(
                client.get("/api/clients") {
                    header(HttpHeaders.Authorization, "Bearer $adminToken")
                }.bodyAsText()
            )
            val targetClient = clients.first { it.clientIpHash == rawHash }
            assertEquals("iphash:$rawHash", targetClient.id)
            assertEquals("ip:203.0.113.x", targetClient.maskedId)

            // 3. Ban client using its clientIpHash (24-char HMAC hex) via POST /api/bans
            val banResp = client.post("/api/bans") {
                header(HttpHeaders.Authorization, "Bearer $adminToken")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(CreateBanRequest(clientId = targetClient.clientIpHash!!, durationMinutes = 15, reason = "Ban by clientIpHash test")))
            }
            assertEquals(HttpStatusCode.Created, banResp.status)

            // 4. Subsequent request from rawIp is immediately blocked (429 Too Many Requests) by the rate limiter
            val blockedResp = client.get("/api/polylance/escrows") {
                header("X-Forwarded-For", rawIp)
            }
            assertEquals(HttpStatusCode.TooManyRequests, blockedResp.status)
            assertTrue(blockedResp.bodyAsText().contains("Rate limit exceeded"))
            println("EVIDENCE_BAN_BY_CLIENT_IP_HASH: clientIpHash=$rawHash maskedId=${targetClient.maskedId} statusBefore=${initialResp.status.value} statusAfterBan=${blockedResp.status.value}")
        } finally {
            upstreamServer.stop(0)
        }
    }

    @Test
    fun testThirtyRequestBurstCoalescesWithBatchesLessThanWritten() = runBlocking {
        val writer = RequestLogWriter(db, capacity = 10_000, batchSize = 500, flushIntervalMs = 200L)
        val writerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        writer.start(writerScope)
        try {
            val now = System.currentTimeMillis()
            // Enqueue a 30-request burst synchronously (non-blocking offer)
            for (i in 1..30) {
                val accepted = writer.offer(
                    LogEntry(
                        id = LogIdGenerator.next(now + i),
                        tsMs = now + i,
                        clientId = "ip:198.51.100.x",
                        clientIpHash = IpPrivacy.hash("198.51.100.$i"),
                        method = "GET",
                        path = "/api/polylance/escrows",
                        status = 200,
                        latencyMs = 45L,
                        decision = "allowed",
                        source = "real",
                        upstream = "https://polylance-fv-1-45wy.onrender.com/api/jobs",
                        limiterMs = 0L,
                        upstreamMs = 40L,
                        totalMs = 45L
                    )
                )
                assertTrue(accepted)
            }

            // Wait for background flush cycle to drain the 30 queued items
            kotlinx.coroutines.delay(450L)
            val written = writer.written.get()
            val batches = writer.batches.get()
            val pending = writer.pending()
            val dropped = writer.dropped.get()
            println("EVIDENCE_30_BURST_BATCHER: written=$written batches=$batches queued=$pending dropped=$dropped")
            assertEquals(30L, written)
            assertEquals(0, pending)
            assertTrue("Expected batches ($batches) < written ($written) for 30-request burst", batches in 1..<written)
        } finally {
            writerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
