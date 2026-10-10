package com.cutm.nt14.data.remote

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cutm.nt14.BuildConfig
import com.cutm.nt14.MainActivity
import com.cutm.nt14.data.local.SessionManager
import com.cutm.nt14.data.local.SyncStatus
import com.cutm.nt14.data.local.daos.AbuseEventDao
import com.cutm.nt14.data.local.daos.DDoSIncidentDao
import com.cutm.nt14.data.local.daos.EndpointDao
import com.cutm.nt14.data.local.daos.RateLimitDao
import com.cutm.nt14.data.local.daos.RequestLogDao
import com.cutm.nt14.data.local.entities.AbuseEvent
import com.cutm.nt14.data.local.entities.DDoSIncident
import com.cutm.nt14.data.local.entities.Endpoint
import com.cutm.nt14.data.local.entities.RateLimit
import com.cutm.nt14.data.local.entities.RequestLog
import com.cutm.nt14.data.remote.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class GatewayConnectionState {
    object Idle : GatewayConnectionState()
    object WakingServer : GatewayConnectionState()
    object Connecting : GatewayConnectionState()
    object Connected : GatewayConnectionState()
    data class Failed(val code: Int? = null, val message: String = "Connection failed") : GatewayConnectionState()
}

@Singleton
class GatewayWebSocketClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logDao: RequestLogDao,
    private val abuseDao: AbuseEventDao,
    private val ddosDao: DDoSIncidentDao,
    private val rateLimitDao: RateLimitDao,
    private val endpointDao: EndpointDao,
    private val sessionManager: SessionManager
) {
    private val tag = "GatewayWS"

    // OkHttpClient with 60s timeout for Render cold start and 20s ping interval for proxy keep-alive
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 1000L
    private var lastEventId: String? = null

    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _connectedHost = MutableStateFlow("127.0.0.1:8000")
    val connectedHost: StateFlow<String> = _connectedHost.asStateFlow()

    // Realtime reactive streams
    private val _liveMetrics = MutableStateFlow(GatewayMetrics())
    val liveMetrics: StateFlow<GatewayMetrics> = _liveMetrics.asStateFlow()

    private val _liveRules = MutableStateFlow<List<RateLimitRuleDto>>(emptyList())
    val liveRules: StateFlow<List<RateLimitRuleDto>> = _liveRules.asStateFlow()

    private val _liveBans = MutableStateFlow<List<ActiveBanDto>>(emptyList())
    val liveBans: StateFlow<List<ActiveBanDto>> = _liveBans.asStateFlow()

    private val _liveIncidents = MutableStateFlow<List<IncidentDto>>(emptyList())
    val liveIncidents: StateFlow<List<IncidentDto>> = _liveIncidents.asStateFlow()

    private val _liveLogs = MutableStateFlow<List<RequestLogDto>>(emptyList())
    val liveLogs: StateFlow<List<RequestLogDto>> = _liveLogs.asStateFlow()

    private val _liveClients = MutableStateFlow<List<ClientInfoDto>>(emptyList())
    val liveClients: StateFlow<List<ClientInfoDto>> = _liveClients.asStateFlow()

    init {
        scope.launch {
            sessionManager.gatewayHost.collect { host ->
                _connectedHost.value = host
            }
        }
    }

    fun buildHttpUrl(host: String, path: String): String {
        val cleanHost = host.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://").trim().trimEnd('/')
        val isSecure = cleanHost.contains("onrender.com") || cleanHost.contains("cloud") || host.startsWith("https://") || host.startsWith("wss://")
        val scheme = if (isSecure) "https" else "http"
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$scheme://$cleanHost$cleanPath"
    }

    fun buildWsUrl(host: String, path: String): String {
        val cleanHost = host.removePrefix("ws://").removePrefix("wss://").removePrefix("http://").removePrefix("https://").trim().trimEnd('/')
        val isSecure = cleanHost.contains("onrender.com") || cleanHost.contains("cloud") || host.startsWith("wss://") || host.startsWith("https://")
        val scheme = if (isSecure) "wss" else "ws"
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val resumeQuery = if (!lastEventId.isNullOrBlank()) "?lastEventId=$lastEventId" else ""
        return "$scheme://$cleanHost$cleanPath$resumeQuery"
    }

    private suspend fun attachAuthHeaders(builder: Request.Builder): Request.Builder {
        val jwt = sessionManager.userJwtToken.first()
        if (!jwt.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $jwt")
        }
        return builder
    }

    fun connect() {
        if (_connectionState.value is GatewayConnectionState.Connected ||
            _connectionState.value is GatewayConnectionState.Connecting ||
            _connectionState.value is GatewayConnectionState.WakingServer) {
            return
        }

        scope.launch {
            val configuredHost = sessionManager.gatewayHost.first()
            attemptConnect(configuredHost)
        }
    }

    fun reconnect() {
        scope.launch {
            reconnectJob?.cancel()
            reconnectDelayMs = 1000L
            disconnect()
            val configuredHost = sessionManager.gatewayHost.first()
            attemptConnect(configuredHost)
        }
    }

    fun reconnectWithHost(newHost: String) {
        scope.launch {
            sessionManager.saveGatewayHost(newHost)
            reconnect()
        }
    }

    private suspend fun wakeServer(host: String): Boolean {
        _connectionState.value = GatewayConnectionState.WakingServer
        val healthUrl = buildHttpUrl(host, "/health")
        Log.i(tag, "Waking gateway server via $healthUrl...")
        return try {
            val req = Request.Builder().url(healthUrl).get().build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(tag, "Wake server probe completed with note: ${e.message}")
            false
        }
    }

    private suspend fun attemptConnect(host: String) {
        // Step 1: Wake sleeping server instance (e.g. Render free tier cold start)
        wakeServer(host)

        // Step 2: Establish WebSocket stream
        _connectionState.value = GatewayConnectionState.Connecting
        _connectedHost.value = host

        val wsUrl = buildWsUrl(host, "/ws/events")
        Log.i(tag, "Opening WebSocket event stream at $wsUrl")

        val reqBuilder = Request.Builder().url(wsUrl)
        attachAuthHeaders(reqBuilder)
        val request = reqBuilder.build()

        webSocket?.cancel()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "Successfully connected to live gateway at $host")
                _connectionState.value = GatewayConnectionState.Connected
                reconnectDelayMs = 1000L
                reconnectJob?.cancel()

                // Trigger fresh REST data fetch in parallel
                scope.launch {
                    refreshRestData()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleLiveEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val userMsg = when {
                    code == 404 -> "Endpoint not found"
                    code == 401 || code == 403 -> "Session expired or unauthorized"
                    code != null && code >= 500 -> "Server error, retrying"
                    t is SocketTimeoutException -> "Connection timed out"
                    else -> "Connection failed: ${t.message ?: "Server unreachable"}"
                }
                Log.w(tag, "WebSocket failure on $host [HTTP ${code ?: 0}]: ${t.message}")
                this@GatewayWebSocketClient.webSocket = null
                _connectionState.value = GatewayConnectionState.Failed(code, userMsg)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WebSocket closed ($code): $reason")
                this@GatewayWebSocketClient.webSocket = null
                _connectionState.value = GatewayConnectionState.Idle
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30000L) // Exponential backoff: 1s, 2s, 4s... max 30s
            val current = _connectedHost.value
            attemptConnect(current)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.cancel()
        webSocket = null
        _connectionState.value = GatewayConnectionState.Idle
    }

    private fun mapHttpError(code: Int): String {
        return when (code) {
            404 -> "Endpoint not found"
            401 -> "Session expired"
            403 -> "Access unauthorized"
            429 -> "Rate limit reached, throttling"
            in 500..599 -> "Server error, retrying"
            else -> "Gateway error (HTTP $code)"
        }
    }

    private fun parseMetricsJson(obj: JSONObject): GatewayMetrics {
        val endpointCountsMap = mutableMapOf<String, Long>()
        val epCountsObj = obj.optJSONObject("endpointCounts")
        if (epCountsObj != null) {
            for (key in epCountsObj.keys()) {
                endpointCountsMap[key] = epCountsObj.optLong(key, 0L)
            }
        }
        return GatewayMetrics(
            rps = obj.optDouble("rps", 0.0),
            allowed = obj.optLong("allowed", 0L),
            throttled = obj.optLong("throttled", 0L),
            errorRate = obj.optDouble("errorRate", 0.0),
            p50LatencyMs = obj.optLong("p50LatencyMs", 0L),
            p95LatencyMs = obj.optLong("p95LatencyMs", 0L),
            activeClients = obj.optInt("activeClients", 0),
            endpointCounts = endpointCountsMap,
            demoMode = obj.optBoolean("demoMode", false)
        )
    }

    private fun parseClientsJson(cArr: JSONArray): List<ClientInfoDto> {
        val clients = mutableListOf<ClientInfoDto>()
        for (i in 0 until cArr.length()) {
            val cObj = cArr.getJSONObject(i)
            clients.add(
                ClientInfoDto(
                    id = cObj.optString("id"),
                    maskedId = cObj.optString("maskedId", cObj.optString("id")),
                    requestsPerMin = cObj.optDouble("requestsPerMin", 0.0),
                    totalRequests = cObj.optLong("totalRequests", 0L),
                    throttledCount = cObj.optLong("throttledCount", 0L),
                    lastSeen = cObj.optLong("lastSeen", 0L),
                    status = cObj.optString("status", "active"),
                    isDemo = cObj.optBoolean("isDemo", false)
                )
            )
        }
        return clients
    }

    private fun handleLiveEvent(jsonStr: String) {
        scope.launch {
            try {
                val json = JSONObject(jsonStr)
                val type = json.optString("type", "request")

                val id = json.optString("id").takeIf { it.isNotBlank() }
                if (id != null) lastEventId = id

                when (type) {
                    "snapshot" -> {
                        // 1. Snapshot metrics
                        val mObj = json.optJSONObject("metrics")
                        if (mObj != null) {
                            _liveMetrics.value = parseMetricsJson(mObj)
                        }

                        // 2. Snapshot rules
                        val rArr = json.optJSONArray("rules")
                        if (rArr != null) {
                            val rules = mutableListOf<RateLimitRuleDto>()
                            for (i in 0 until rArr.length()) {
                                val rObj = rArr.getJSONObject(i)
                                rules.add(
                                    RateLimitRuleDto(
                                        endpointId = rObj.optString("endpointId"),
                                        limitPerMin = rObj.optInt("limitPerMin", 60),
                                        burstLimit = rObj.optInt("burstLimit", 10),
                                        action = rObj.optString("action", "ALERT")
                                    )
                                )
                            }
                            _liveRules.value = rules
                            cacheRulesToRoom(rules)
                        }

                        // 3. Snapshot bans
                        val bArr = json.optJSONArray("activeBans")
                        if (bArr != null) {
                            val bans = mutableListOf<ActiveBanDto>()
                            for (i in 0 until bArr.length()) {
                                val bObj = bArr.getJSONObject(i)
                                bans.add(
                                    ActiveBanDto(
                                        clientId = bObj.optString("clientId"),
                                        reason = bObj.optString("reason"),
                                        expiresAt = bObj.optLong("expiresAt")
                                    )
                                )
                            }
                            _liveBans.value = bans
                        }

                        // 4. Snapshot logs
                        val lArr = json.optJSONArray("logs")
                        if (lArr != null) {
                            val logs = mutableListOf<RequestLogDto>()
                            for (i in 0 until lArr.length()) {
                                val lObj = lArr.getJSONObject(i)
                                logs.add(
                                    RequestLogDto(
                                        id = lObj.optString("id"),
                                        timestamp = lObj.optLong("timestamp"),
                                        clientId = lObj.optString("clientId"),
                                        method = lObj.optString("method", "GET"),
                                        path = lObj.optString("path"),
                                        status = lObj.optInt("status"),
                                        latencyMs = lObj.optLong("latencyMs"),
                                        decision = lObj.optString("decision", "allowed")
                                    )
                                )
                            }
                            _liveLogs.value = logs
                            cacheLogsToRoom(logs)
                        }

                        // 5. Snapshot incidents
                        val iArr = json.optJSONArray("incidents")
                        if (iArr != null) {
                            val incidents = mutableListOf<IncidentDto>()
                            for (i in 0 until iArr.length()) {
                                val iObj = iArr.getJSONObject(i)
                                incidents.add(
                                    IncidentDto(
                                        id = iObj.optString("id"),
                                        type = iObj.optString("type"),
                                        severity = iObj.optString("severity", "HIGH"),
                                        detail = iObj.optString("detail"),
                                        timestamp = iObj.optLong("timestamp")
                                    )
                                )
                            }
                            _liveIncidents.value = incidents
                        }

                        // 6. Snapshot clients
                        val cArr = json.optJSONArray("clients")
                        if (cArr != null) {
                            _liveClients.value = parseClientsJson(cArr)
                        }
                    }

                    "client_update" -> {
                        val cArr = json.optJSONArray("clients")
                        if (cArr != null) {
                            _liveClients.value = parseClientsJson(cArr)
                        } else {
                            val cObj = json.optJSONObject("client")
                            if (cObj != null) {
                                val updatedClient = ClientInfoDto(
                                    id = cObj.optString("id"),
                                    maskedId = cObj.optString("maskedId", cObj.optString("id")),
                                    requestsPerMin = cObj.optDouble("requestsPerMin", 0.0),
                                    totalRequests = cObj.optLong("totalRequests", 0L),
                                    throttledCount = cObj.optLong("throttledCount", 0L),
                                    lastSeen = cObj.optLong("lastSeen", 0L),
                                    status = cObj.optString("status", "active"),
                                    isDemo = cObj.optBoolean("isDemo", false)
                                )
                                val current = _liveClients.value.filter { it.id != updatedClient.id }.toMutableList()
                                current.add(updatedClient)
                                _liveClients.value = current.sortedByDescending { it.requestsPerMin }
                            }
                        }
                    }

                    "metrics" -> {
                        val mObj = json.optJSONObject("metrics") ?: json
                        _liveMetrics.value = parseMetricsJson(mObj)
                    }

                    "rule_changed" -> {
                        val rObj = json.optJSONObject("rule") ?: json
                        val ruleDto = RateLimitRuleDto(
                            endpointId = rObj.optString("endpointId"),
                            limitPerMin = rObj.optInt("limitPerMin", 60),
                            burstLimit = rObj.optInt("burstLimit", 10),
                            action = rObj.optString("action", "ALERT")
                        )
                        val updated = _liveRules.value.filter { it.endpointId != ruleDto.endpointId }.toMutableList()
                        if (ruleDto.action != "REMOVED" && ruleDto.limitPerMin > 0) {
                            updated.add(ruleDto)
                        }
                        _liveRules.value = updated
                        cacheRulesToRoom(updated)
                    }

                    "ban" -> {
                        val clientId = json.optString("clientId", json.optString("ip"))
                        val reason = json.optString("reason", "Anomaly Detected")
                        val expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + 300_000L)
                        val ban = ActiveBanDto(clientId, reason, expiresAt)
                        _liveBans.value = listOf(ban) + _liveBans.value.filter { it.clientId != clientId }
                        showNotification(
                            title = "Security Alert: IP Banned",
                            body = "Client $clientId banned: $reason"
                        )
                    }

                    "unban" -> {
                        val clientId = json.optString("clientId", json.optString("ip"))
                        _liveBans.value = _liveBans.value.filter { it.clientId != clientId }
                    }

                    "incident" -> {
                        val inc = IncidentDto(
                            id = json.optString("id", "inc_" + UUID.randomUUID().toString().take(6)),
                            type = json.optString("type", "Security Incident"),
                            severity = json.optString("severity", "HIGH"),
                            detail = json.optString("detail"),
                            timestamp = json.optLong("timestamp", System.currentTimeMillis())
                        )
                        _liveIncidents.value = listOf(inc) + _liveIncidents.value
                    }

                    "request", "blocked_request", "ip_blocked" -> {
                        val ip = json.optString("ip", "unknown")
                        val endpoint = json.optString("endpoint", "/api/unknown")
                        val status = json.optInt("status", 200)
                        val latency = json.optDouble("latency_ms", 50.0).toLong()
                        val timestamp = (json.optDouble("timestamp", System.currentTimeMillis() / 1000.0) * 1000).toLong()
                        val method = json.optString("method", "GET")
                        val decision = json.optString("decision", if (status == 429) "token_bucket" else "allowed")
                        val logId = id ?: ("log_" + UUID.randomUUID().toString().take(8))

                        val logDto = RequestLogDto(
                            id = logId,
                            timestamp = timestamp,
                            clientId = ip,
                            method = method,
                            path = endpoint,
                            status = status,
                            latencyMs = latency,
                            decision = decision
                        )

                        _liveLogs.value = (listOf(logDto) + _liveLogs.value).take(200)

                        // Cache in Room
                        val log = RequestLog(
                            logId = logId,
                            endpointId = endpoint,
                            timestamp = timestamp,
                            sourceIp = ip,
                            userId = "ip_" + ip.replace(".", "_"),
                            statusCode = status,
                            latencyMs = latency,
                            syncStatus = SyncStatus.SYNCED
                        )
                        logDao.insertLog(log)

                        if (type == "ip_blocked" || type == "blocked_request" || status == 429) {
                            val eventId = "ae_" + UUID.randomUUID().toString().take(8)
                            val abuseEvent = AbuseEvent(
                                eventId = eventId,
                                logId = logId,
                                eventType = "Rate Limit Breach / DDoS Surge",
                                riskScore = 95,
                                action = if (type == "ip_blocked") "BLOCK_IP" else "THROTTLE",
                                createdAt = timestamp,
                                syncStatus = SyncStatus.SYNCED
                            )
                            abuseDao.insertEvent(abuseEvent)

                            val incidentId = "ddos_" + UUID.randomUUID().toString().take(8)
                            val incident = DDoSIncident(
                                incidentId = incidentId,
                                endpointId = endpoint,
                                startTime = timestamp,
                                requestSpike = 500,
                                severity = if (type == "ip_blocked") "CRITICAL" else "HIGH",
                                status = "ACTIVE",
                                syncStatus = SyncStatus.SYNCED
                            )
                            ddosDao.insertIncident(incident)

                            val incDto = IncidentDto(
                                id = incidentId,
                                type = if (type == "ip_blocked") "IP Block Enforcement" else "Rate Limit Spike",
                                severity = if (type == "ip_blocked") "CRITICAL" else "HIGH",
                                detail = "Client $ip throttled on $endpoint (action: ${if (type == "ip_blocked") "BLOCK_IP" else "THROTTLE"})",
                                timestamp = timestamp
                            )
                            _liveIncidents.value = (listOf(incDto) + _liveIncidents.value).take(100)

                            if (type == "ip_blocked") {
                                showNotification(
                                    title = "Security Alert: IP Blocked",
                                    body = "Client $ip was blocked on $endpoint"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error parsing live event: ${e.message}")
            }
        }
    }

    private suspend fun cacheRulesToRoom(rules: List<RateLimitRuleDto>) {
        val now = System.currentTimeMillis()
        for (r in rules) {
            val epId = "ep_" + r.endpointId.replace("/", "_").trimStart('_')
            val endpoint = Endpoint(
                endpointId = epId,
                name = r.endpointId.substringAfterLast("/").replaceFirstChar { it.uppercase() } + " API",
                baseUrl = r.endpointId,
                method = "GET",
                status = "ACTIVE",
                ownerEmail = "gateway@nt14.cutm"
            )
            endpointDao.insertEndpoint(endpoint)

            val rateLimit = RateLimit(
                ruleId = "rl_" + r.endpointId.replace("/", "_").trimStart('_'),
                endpointId = epId,
                limitPerMin = r.limitPerMin,
                burstLimit = r.burstLimit,
                action = r.action,
                updatedAt = now,
                syncStatus = SyncStatus.SYNCED
            )
            rateLimitDao.insertRule(rateLimit)
        }
    }

    private suspend fun cacheLogsToRoom(logs: List<RequestLogDto>) {
        for (l in logs) {
            val log = RequestLog(
                logId = l.id,
                endpointId = l.path,
                timestamp = l.timestamp,
                sourceIp = l.clientId,
                userId = "ip_" + l.clientId.replace(".", "_"),
                statusCode = l.status,
                latencyMs = l.latencyMs,
                syncStatus = SyncStatus.SYNCED
            )
            logDao.insertLog(log)
        }
    }

    suspend fun refreshRestData() = withContext(Dispatchers.IO) {
        fetchStats()
        fetchRules()
        fetchBans()
        fetchClients()
        fetchLogs(50)
    }

    suspend fun fetchStats(): GatewayMetrics? = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/stats")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val obj = JSONObject(body)
                    val mObj = obj.optJSONObject("metrics") ?: obj
                    val metrics = parseMetricsJson(mObj)
                    _liveMetrics.value = metrics
                    metrics
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchRules(): List<RateLimitRuleDto> = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/rules")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = JSONArray(body)
                    val list = mutableListOf<RateLimitRuleDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            RateLimitRuleDto(
                                endpointId = obj.optString("endpointId"),
                                limitPerMin = obj.optInt("limitPerMin", 60),
                                burstLimit = obj.optInt("burstLimit", 10),
                                action = obj.optString("action", "ALERT")
                            )
                        )
                    }
                    _liveRules.value = list
                    cacheRulesToRoom(list)
                    list
                } else emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun addRule(rule: RateLimitRuleDto): Boolean = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/rules")
        try {
            val json = JSONObject().apply {
                put("endpointId", rule.endpointId)
                put("limitPerMin", rule.limitPerMin)
                put("burstLimit", rule.burstLimit)
                put("action", rule.action)
            }.toString()
            val req = attachAuthHeaders(
                Request.Builder()
                    .url(url)
                    .post(json.toRequestBody("application/json".toMediaType()))
            ).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    fetchRules()
                    true
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteRule(endpointPath: String): Boolean = withContext(Dispatchers.IO) {
        val clean = if (endpointPath.startsWith("/")) endpointPath.removePrefix("/") else endpointPath
        val url = buildHttpUrl(_connectedHost.value, "/api/rules/$clean")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).delete()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    fetchRules()
                    true
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchBans(): List<ActiveBanDto> = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/bans")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = JSONArray(body)
                    val list = mutableListOf<ActiveBanDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            ActiveBanDto(
                                clientId = obj.optString("clientId"),
                                reason = obj.optString("reason"),
                                expiresAt = obj.optLong("expiresAt")
                            )
                        )
                    }
                    _liveBans.value = list
                    list
                } else emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fetchClients(): List<ClientInfoDto> = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/clients")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = JSONArray(body)
                    val list = parseClientsJson(arr)
                    _liveClients.value = list
                    list
                } else emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun banClient(clientId: String, durationMinutes: Long = 60, reason: String = "Manual ban"): Boolean = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/bans")
        try {
            val json = JSONObject().apply {
                put("clientId", clientId)
                put("durationMinutes", durationMinutes)
                put("reason", reason)
            }.toString()
            val req = attachAuthHeaders(
                Request.Builder()
                    .url(url)
                    .post(json.toRequestBody("application/json".toMediaType()))
            ).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    fetchBans()
                    fetchClients()
                    true
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun unbanClient(clientId: String): Boolean = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/bans/$clientId")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).delete()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    fetchBans()
                    fetchClients()
                    true
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun simulateTraffic(reqDto: SimulateRequestDto): Boolean = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/simulate")
        try {
            val json = JSONObject().apply {
                put("endpoint", reqDto.endpoint)
                put("requestCount", reqDto.requestCount)
                put("rps", reqDto.rps)
                put("profile", reqDto.profile)
                if (reqDto.sourceIps.isNotEmpty()) {
                    val ipsArr = JSONArray()
                    reqDto.sourceIps.forEach { ipsArr.put(it) }
                    put("sourceIps", ipsArr)
                }
            }.toString()
            val req = attachAuthHeaders(
                Request.Builder()
                    .url(url)
                    .post(json.toRequestBody("application/json".toMediaType()))
            ).build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchReports(range: String = "1h"): TrafficReportDto? = withContext(Dispatchers.IO) {
        val url = buildHttpUrl(_connectedHost.value, "/api/reports?range=$range")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val obj = JSONObject(body)
                    val topEndpointsMap = mutableMapOf<String, Long>()
                    val topEpObj = obj.optJSONObject("topEndpoints")
                    topEpObj?.keys()?.forEach { k -> topEndpointsMap[k] = topEpObj.optLong(k) }

                    val topClientsMap = mutableMapOf<String, Long>()
                    val topClObj = obj.optJSONObject("topClients")
                    topClObj?.keys()?.forEach { k -> topClientsMap[k] = topClObj.optLong(k) }

                    TrafficReportDto(
                        range = obj.optString("range", range),
                        totalRequests = obj.optLong("totalRequests"),
                        allowedRequests = obj.optLong("allowedRequests"),
                        blockedRequests = obj.optLong("blockedRequests"),
                        errorRate = obj.optDouble("errorRate"),
                        avgLatencyMs = obj.optLong("avgLatencyMs"),
                        p95LatencyMs = obj.optLong("p95LatencyMs"),
                        peakRps = obj.optDouble("peakRps"),
                        topEndpoints = topEndpointsMap,
                        topClients = topClientsMap,
                        generatedAt = obj.optLong("generatedAt", System.currentTimeMillis())
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchLogs(limit: Int = 50, cursor: String? = null): List<RequestLogDto> = withContext(Dispatchers.IO) {
        val cursorQuery = if (!cursor.isNullOrBlank()) "&cursor=$cursor" else ""
        val url = buildHttpUrl(_connectedHost.value, "/api/logs?limit=$limit$cursorQuery")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = JSONArray(body)
                    val list = mutableListOf<RequestLogDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            RequestLogDto(
                                id = obj.optString("id"),
                                timestamp = obj.optLong("timestamp"),
                                clientId = obj.optString("clientId"),
                                method = obj.optString("method", "GET"),
                                path = obj.optString("path"),
                                status = obj.optInt("status"),
                                latencyMs = obj.optLong("latencyMs"),
                                decision = obj.optString("decision", "allowed")
                            )
                        )
                    }
                    if (list.isNotEmpty()) {
                        _liveLogs.value = list
                        cacheLogsToRoom(list)
                    }
                    list
                } else emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearLogs() {
        _liveLogs.value = emptyList()
    }

    private fun showNotification(title: String, body: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "high_risk_alerts"

        val channel = NotificationChannel(
            channelId,
            "High-Risk Security Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for live DDoS attacks and IP blocks"
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    // PolyLance Web3 APIs (Proxied through Gateway)
    suspend fun fetchPolyLanceEscrows(): GatewayResponse<List<PolyLanceEscrow>> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val host = _connectedHost.value
        val primaryUrl = buildHttpUrl(host, "/api/polylance/escrows")
        val fallbackUrl = buildHttpUrl(host, "/api/jobs")

        try {
            var req = attachAuthHeaders(Request.Builder().url(primaryUrl).get()).build()
            var res = executeEscrowsCall(req)

            if (res.code == 404) {
                req = attachAuthHeaders(Request.Builder().url(fallbackUrl).get()).build()
                val fallbackRes = executeEscrowsCall(req)
                if (fallbackRes.code in 200..299) {
                    res = fallbackRes
                }
            }

            val latency = System.currentTimeMillis() - start
            if (res.code in 200..299 && res.body.isNotBlank()) {
                val list = parseEscrowsJson(res.body)
                GatewayResponse(
                    statusCode = res.code,
                    data = list,
                    rawJson = res.body,
                    rateLimitRemaining = res.remaining,
                    rateLimitLimit = res.limit,
                    rateLimitReset = res.reset,
                    latencyMs = latency
                )
            } else {
                GatewayResponse(
                    statusCode = res.code,
                    data = null,
                    rawJson = res.body,
                    rateLimitRemaining = res.remaining,
                    rateLimitLimit = res.limit,
                    rateLimitReset = res.reset,
                    latencyMs = latency,
                    errorMessage = mapHttpError(res.code)
                )
            }
        } catch (e: Exception) {
            val userMsg = if (e is SocketTimeoutException) "Connection timed out" else "Network unreachable"
            GatewayResponse(
                statusCode = -1,
                data = null,
                rawJson = null,
                rateLimitRemaining = null,
                rateLimitLimit = null,
                rateLimitReset = null,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = userMsg
            )
        }
    }

    private data class EscrowCallResult(
        val code: Int,
        val body: String,
        val limit: Int?,
        val remaining: Int?,
        val reset: Long?
    )

    private fun executeEscrowsCall(req: Request): EscrowCallResult {
        return try {
            client.newCall(req).execute().use { resp ->
                EscrowCallResult(
                    code = resp.code,
                    body = resp.body?.string().orEmpty(),
                    limit = resp.header("X-RateLimit-Limit")?.toIntOrNull(),
                    remaining = resp.header("X-RateLimit-Remaining")?.toIntOrNull(),
                    reset = resp.header("X-RateLimit-Reset")?.toLongOrNull()
                )
            }
        } catch (e: Exception) {
            EscrowCallResult(code = -1, body = "", limit = null, remaining = null, reset = null)
        }
    }

    private fun parseEscrowsJson(bodyStr: String): List<PolyLanceEscrow> {
        val list = mutableListOf<PolyLanceEscrow>()
        val arr = try {
            val trimmed = bodyStr.trim()
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                root.optJSONArray("jobs") ?: root.optJSONArray("escrows") ?: JSONArray()
            } else {
                JSONArray()
            }
        } catch (e: Exception) {
            JSONArray()
        }

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("escrowId").ifBlank { obj.optString("id") }
            val amount = if (obj.has("amountPol")) {
                obj.optDouble("amountPol", 0.0)
            } else if (obj.has("amountUsdc") && obj.optDouble("amountUsdc", 0.0) > 0.0) {
                obj.optDouble("amountUsdc", 0.0)
            } else {
                obj.optDouble("amountEth", 0.0)
            }
            val token = obj.optString("token").ifBlank {
                obj.optString("paymentTokenSymbol", "POL")
            }

            list.add(
                PolyLanceEscrow(
                    escrowId = id,
                    client = obj.optString("client"),
                    freelancer = obj.optString("freelancer"),
                    amountPol = amount,
                    status = obj.optString("status"),
                    title = obj.optString("title"),
                    token = token,
                    contractAddress = obj.optString("contractAddress")
                )
            )
        }
        return list
    }

    suspend fun fetchPolyLanceAttestations(): GatewayResponse<List<PolyLanceAttestation>> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val host = _connectedHost.value
        val url = buildHttpUrl(host, "/api/polylance/attestations")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                val limit = resp.header("X-RateLimit-Limit")?.toIntOrNull()
                val remaining = resp.header("X-RateLimit-Remaining")?.toIntOrNull()
                val reset = resp.header("X-RateLimit-Reset")?.toLongOrNull()
                val bodyStr = resp.body?.string().orEmpty()

                if (resp.isSuccessful) {
                    val list = mutableListOf<PolyLanceAttestation>()
                    val arr = JSONArray(bodyStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            PolyLanceAttestation(
                                attestationId = obj.optString("attestationId"),
                                developerGithub = obj.optString("developerGithub"),
                                skillAttestation = obj.optString("skillAttestation"),
                                soulboundTokenId = obj.optString("soulboundTokenId")
                            )
                        )
                    }
                    GatewayResponse(
                        statusCode = resp.code,
                        data = list,
                        rawJson = bodyStr,
                        rateLimitRemaining = remaining,
                        rateLimitLimit = limit,
                        rateLimitReset = reset,
                        latencyMs = latency
                    )
                } else {
                    GatewayResponse(
                        statusCode = resp.code,
                        data = null,
                        rawJson = bodyStr,
                        rateLimitRemaining = remaining,
                        rateLimitLimit = limit,
                        rateLimitReset = reset,
                        latencyMs = latency,
                        errorMessage = mapHttpError(resp.code)
                    )
                }
            }
        } catch (e: Exception) {
            val userMsg = if (e is SocketTimeoutException) "Connection timed out" else "Network unreachable"
            GatewayResponse(
                statusCode = -1,
                data = null,
                rawJson = null,
                rateLimitRemaining = null,
                rateLimitLimit = null,
                rateLimitReset = null,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = userMsg
            )
        }
    }

    suspend fun fetchPolyLanceTalents(): GatewayResponse<List<PolyLanceTalent>> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val host = _connectedHost.value
        val url = buildHttpUrl(host, "/api/polylance/talents")
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                val limit = resp.header("X-RateLimit-Limit")?.toIntOrNull()
                val remaining = resp.header("X-RateLimit-Remaining")?.toIntOrNull()
                val reset = resp.header("X-RateLimit-Reset")?.toLongOrNull()
                val bodyStr = resp.body?.string().orEmpty()

                if (resp.isSuccessful) {
                    val list = mutableListOf<PolyLanceTalent>()
                    val arr = JSONArray(bodyStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            PolyLanceTalent(
                                talentId = obj.optString("talentId"),
                                name = obj.optString("name"),
                                specialization = obj.optString("specialization"),
                                rating = obj.optDouble("rating", 5.0)
                            )
                        )
                    }
                    GatewayResponse(
                        statusCode = resp.code,
                        data = list,
                        rawJson = bodyStr,
                        rateLimitRemaining = remaining,
                        rateLimitLimit = limit,
                        rateLimitReset = reset,
                        latencyMs = latency
                    )
                } else {
                    GatewayResponse(
                        statusCode = resp.code,
                        data = null,
                        rawJson = bodyStr,
                        rateLimitRemaining = remaining,
                        rateLimitLimit = limit,
                        rateLimitReset = reset,
                        latencyMs = latency,
                        errorMessage = mapHttpError(resp.code)
                    )
                }
            }
        } catch (e: Exception) {
            val userMsg = if (e is SocketTimeoutException) "Connection timed out" else "Network unreachable"
            GatewayResponse(
                statusCode = -1,
                data = null,
                rawJson = null,
                rateLimitRemaining = null,
                rateLimitLimit = null,
                rateLimitReset = null,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = userMsg
            )
        }
    }

    suspend fun createPolyLanceEscrow(
        clientAddr: String = "0x3F9a...b210",
        freelancerAddr: String = "0x78Ce...4a91",
        amountPol: Double = 500.0
    ): GatewayResponse<PolyLanceEscrow> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val host = _connectedHost.value
        val url = buildHttpUrl(host, "/api/polylance/escrows")
        try {
            val jsonBody = JSONObject().apply {
                put("client", clientAddr)
                put("freelancer", freelancerAddr)
                put("amountPol", amountPol)
            }.toString()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonBody.toRequestBody(mediaType)
            val req = attachAuthHeaders(Request.Builder().url(url).post(body)).build()
            client.newCall(req).execute().use { resp ->
                val latency = System.currentTimeMillis() - start
                val limit = resp.header("X-RateLimit-Limit")?.toIntOrNull()
                val remaining = resp.header("X-RateLimit-Remaining")?.toIntOrNull()
                val reset = resp.header("X-RateLimit-Reset")?.toLongOrNull()
                val bodyStr = resp.body?.string().orEmpty()

                if (resp.isSuccessful) {
                    val obj = JSONObject(bodyStr)
                    val escrow = PolyLanceEscrow(
                        escrowId = obj.optString("escrowId"),
                        client = obj.optString("client"),
                        freelancer = obj.optString("freelancer"),
                        amountPol = obj.optDouble("amountPol", amountPol),
                        status = obj.optString("status"),
                        title = obj.optString("title", "Test Escrow"),
                        token = obj.optString("token", "POL"),
                        contractAddress = obj.optString("contractAddress")
                    )
                    GatewayResponse(
                        statusCode = resp.code,
                        data = escrow,
                        rawJson = bodyStr,
                        rateLimitRemaining = remaining,
                        rateLimitLimit = limit,
                        rateLimitReset = reset,
                        latencyMs = latency
                    )
                } else {
                    GatewayResponse(
                        statusCode = resp.code,
                        data = null,
                        rawJson = bodyStr,
                        rateLimitRemaining = remaining,
                        rateLimitLimit = limit,
                        rateLimitReset = reset,
                        latencyMs = latency,
                        errorMessage = mapHttpError(resp.code)
                    )
                }
            }
        } catch (e: Exception) {
            val userMsg = if (e is SocketTimeoutException) "Connection timed out" else "Network unreachable"
            GatewayResponse(
                statusCode = -1,
                data = null,
                rawJson = null,
                rateLimitRemaining = null,
                rateLimitLimit = null,
                rateLimitReset = null,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = userMsg
            )
        }
    }

    suspend fun sendTestRequest(endpoint: String = "/api/polylance/escrows"): Int = withContext(Dispatchers.IO) {
        val host = _connectedHost.value
        val url = buildHttpUrl(host, endpoint)
        try {
            val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
            client.newCall(req).execute().use { resp ->
                resp.code
            }
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun sendBurstSimulation(count: Int = 18, endpoint: String = "/api/polylance/escrows") = withContext(Dispatchers.IO) {
        val host = _connectedHost.value
        val url = buildHttpUrl(host, endpoint)
        val jobs = (1..count).map {
            async {
                try {
                    val req = attachAuthHeaders(Request.Builder().url(url).get()).build()
                    client.newCall(req).execute().use { it.code }
                } catch (e: Exception) {
                    -1
                }
            }
        }
        jobs.awaitAll()
    }
}
