package com.cutm.nt14.ui.dashboard

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutm.nt14.data.local.SessionManager
import com.cutm.nt14.data.local.entities.RateLimit
import com.cutm.nt14.data.local.entities.RequestLog
import com.cutm.nt14.data.remote.GatewayConnectionState
import com.cutm.nt14.data.remote.GatewayWebSocketClient
import com.cutm.nt14.data.remote.GoogleAuthManager
import com.cutm.nt14.data.remote.model.*
import com.cutm.nt14.data.repository.GatewayRepository
import com.cutm.nt14.domain.detector.OptimizationResult
import com.cutm.nt14.domain.detector.RateLimitOptimizer
import com.cutm.nt14.domain.model.UserRole
import com.cutm.nt14.security.SecurityIntegrityChecker
import com.cutm.nt14.security.SecurityIntegrityReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PolyLanceTab {
    ESCROWS, ATTESTATIONS, TALENTS
}

data class PolyLanceInspectorUiState(
    val selectedTab: PolyLanceTab = PolyLanceTab.ESCROWS,
    val isLoading: Boolean = false,
    val escrows: List<PolyLanceEscrow> = emptyList(),
    val attestations: List<PolyLanceAttestation> = emptyList(),
    val talents: List<PolyLanceTalent> = emptyList(),
    val lastStatusCode: Int? = null,
    val lastLatencyMs: Long? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitLimit: Int? = null,
    val rateLimitReset: Long? = null,
    val optimizationResult: OptimizationResult? = null,
    val isBursting: Boolean = false,
    val rawJson: String? = null,
    val showJsonModal: Boolean = false
)

data class DashboardUiState(
    val endpointCount: Int = 0,
    val totalRequests: Long = 0,
    val rps: Double = 0.0,
    val throttledCount: Long = 0,
    val errorRate: Float = 0f,
    val p50LatencyMs: Long = 0,
    val p95LatencyMs: Long = 0,
    val activeBansCount: Int = 0,
    val activeIncidents: Int = 0,
    val isLoading: Boolean = false,
    val isOffline: Boolean = true,
    val connectionState: GatewayConnectionState = GatewayConnectionState.Idle,
    val connectedHost: String = "127.0.0.1:8000",
    val recentLogs: List<RequestLogDto> = emptyList(),
    val actionMessage: String? = null,
    val userEmail: String? = null,
    val userName: String? = null,
    val userRole: UserRole = UserRole.VIEWER,
    val securityReport: SecurityIntegrityReport? = null,
    val topEndpoints: Map<String, Long> = emptyMap()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val sessionManager: SessionManager,
    private val authManager: GoogleAuthManager,
    private val wsClient: GatewayWebSocketClient,
    private val securityChecker: SecurityIntegrityChecker
) : ViewModel() {

    private val _actionMessage = MutableStateFlow<String?>(null)
    private val _polyLanceState = MutableStateFlow(PolyLanceInspectorUiState())
    val polyLanceState: StateFlow<PolyLanceInspectorUiState> = _polyLanceState.asStateFlow()

    val userEmail: StateFlow<String?> = sessionManager.userEmail.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )
    val userName: StateFlow<String?> = sessionManager.userName.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )
    val userPhotoUrl: StateFlow<String?> = sessionManager.userPhotoUrl.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )
    val userRole: StateFlow<UserRole> = repository.userRole

    private val optimizer = RateLimitOptimizer()

    private val bundleA = combine(repository.metrics, repository.rules, repository.bans) { m, r, b ->
        Triple(m, r, b)
    }

    private val bundleB = combine(repository.incidents, repository.logs, repository.connectionState) { i, l, c ->
        Triple(i, l, c)
    }

    private val bundleC = combine(repository.connectedHost, repository.userRole, _actionMessage) { h, role, msg ->
        Triple(h, role, msg)
    }

    val uiState: StateFlow<DashboardUiState> = combine(bundleA, bundleB, bundleC) { a, b, c ->
        val (metrics, rules, bans) = a
        val (incidents, logs, connState) = b
        val (host, role, msg) = c

        val isOff = connState !is GatewayConnectionState.Connected
        val totalReq = metrics.allowed + metrics.throttled
        val errRate = metrics.errorRate.toFloat()

        DashboardUiState(
            endpointCount = rules.size,
            totalRequests = totalReq,
            rps = metrics.rps,
            throttledCount = metrics.throttled,
            errorRate = errRate,
            p50LatencyMs = metrics.p50LatencyMs,
            p95LatencyMs = metrics.p95LatencyMs,
            activeBansCount = bans.size,
            activeIncidents = incidents.size,
            isLoading = false,
            isOffline = isOff,
            connectionState = connState,
            connectedHost = host,
            recentLogs = logs.take(6),
            actionMessage = msg,
            userRole = role,
            securityReport = securityChecker.checkIntegrity(),
            topEndpoints = metrics.endpointCounts
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    fun rescanSecurityIntegrity() {
        val report = securityChecker.checkIntegrity()
        val isConn = repository.connectionState.value is GatewayConnectionState.Connected
        _actionMessage.value = if (!isConn) {
            "Zero-Trust Status: Gateway Offline (Integrity Unverified)"
        } else if (report.isCompromised) {
            "Security Alert: Root, Proxy or Frida detected!"
        } else {
            "Zero-Trust Security Scan: Verified Clean"
        }
    }

    init {
        fetchActivePolyLanceData()
    }

    fun reconnect() {
        repository.reconnect()
    }

    fun updateGatewayHost(newHost: String) {
        repository.updateHost(newHost)
    }

    fun selectPolyLanceTab(tab: PolyLanceTab) {
        _polyLanceState.update { it.copy(selectedTab = tab) }
        fetchActivePolyLanceData()
    }

    fun toggleJsonModal(show: Boolean) {
        _polyLanceState.update { it.copy(showJsonModal = show) }
    }

    fun fetchActivePolyLanceData() {
        viewModelScope.launch {
            _polyLanceState.update { it.copy(isLoading = true) }
            when (_polyLanceState.value.selectedTab) {
                PolyLanceTab.ESCROWS -> {
                    val resp = wsClient.fetchPolyLanceEscrows()
                    _polyLanceState.update {
                        it.copy(
                            isLoading = false,
                            escrows = resp.data ?: emptyList(),
                            lastStatusCode = resp.statusCode,
                            lastLatencyMs = resp.latencyMs,
                            rateLimitRemaining = resp.rateLimitRemaining,
                            rateLimitLimit = resp.rateLimitLimit,
                            rateLimitReset = resp.rateLimitReset,
                            rawJson = resp.rawJson
                        )
                    }
                }
                PolyLanceTab.ATTESTATIONS -> {
                    val resp = wsClient.fetchPolyLanceAttestations()
                    _polyLanceState.update {
                        it.copy(
                            isLoading = false,
                            attestations = resp.data ?: emptyList(),
                            lastStatusCode = resp.statusCode,
                            lastLatencyMs = resp.latencyMs,
                            rateLimitRemaining = resp.rateLimitRemaining,
                            rateLimitLimit = resp.rateLimitLimit,
                            rateLimitReset = resp.rateLimitReset,
                            rawJson = resp.rawJson
                        )
                    }
                }
                PolyLanceTab.TALENTS -> {
                    val resp = wsClient.fetchPolyLanceTalents()
                    _polyLanceState.update {
                        it.copy(
                            isLoading = false,
                            talents = resp.data ?: emptyList(),
                            lastStatusCode = resp.statusCode,
                            lastLatencyMs = resp.latencyMs,
                            rateLimitRemaining = resp.rateLimitRemaining,
                            rateLimitLimit = resp.rateLimitLimit,
                            rateLimitReset = resp.rateLimitReset,
                            rawJson = resp.rawJson
                        )
                    }
                }
            }
        }
    }

    fun simulateTraffic(endpoint: String = "/api/polylance/escrows", count: Int = 20, rps: Double = 15.0, profile: String = "burst") {
        viewModelScope.launch {
            _actionMessage.value = "Triggering $profile simulation on $endpoint..."
            val ok = repository.simulate(endpoint, count, rps, profile)
            _actionMessage.value = if (ok) "Simulation started ($profile profile)" else "Simulation request failed"
        }
    }

    fun fireTestRequest(endpoint: String = "/api/polylance/escrows") {
        viewModelScope.launch {
            val code = wsClient.sendTestRequest(endpoint)
            _actionMessage.value = "Test GET $endpoint -> HTTP $code"
            fetchActivePolyLanceData()
        }
    }

    fun fireBurstTraffic(count: Int = 18, endpoint: String = "/api/polylance/escrows") {
        viewModelScope.launch {
            _polyLanceState.update { it.copy(isBursting = true) }
            wsClient.sendBurstSimulation(count, endpoint)
            _polyLanceState.update { it.copy(isBursting = false) }
            _actionMessage.value = "Burst Traffic Dispatched ($count reqs on $endpoint)"
            fetchActivePolyLanceData()
        }
    }

    fun createTestEscrow(amountPol: Double = 500.0) {
        viewModelScope.launch {
            _polyLanceState.update { it.copy(isLoading = true) }
            _actionMessage.value = "Deploying live test escrow for $amountPol POL..."
            val resp = wsClient.createPolyLanceEscrow(amountPol = amountPol)
            _polyLanceState.update {
                it.copy(
                    isLoading = false,
                    lastStatusCode = resp.statusCode,
                    lastLatencyMs = resp.latencyMs,
                    rateLimitRemaining = resp.rateLimitRemaining,
                    rateLimitLimit = resp.rateLimitLimit,
                    rateLimitReset = resp.rateLimitReset,
                    rawJson = resp.rawJson
                )
            }
            if (resp.data != null) {
                _polyLanceState.update { it.copy(escrows = listOf(resp.data) + it.escrows) }
                _actionMessage.value = "Escrow ${resp.data.escrowId} created live ($amountPol POL)!"
            } else {
                _actionMessage.value = "Escrow creation response: HTTP ${resp.statusCode}"
            }
        }
    }

    fun runRateLimitOptimizerOnPolyLance() {
        viewModelScope.launch {
            val targetEndpoint = when (_polyLanceState.value.selectedTab) {
                PolyLanceTab.ESCROWS -> "/api/polylance/escrows"
                PolyLanceTab.ATTESTATIONS -> "/api/polylance/attestations"
                PolyLanceTab.TALENTS -> "/api/polylance/talents"
            }
            val logs = repository.logs.value.filter { it.path == targetEndpoint }.map { dto ->
                RequestLog(
                    logId = dto.id,
                    endpointId = dto.path,
                    timestamp = dto.timestamp,
                    sourceIp = dto.clientId,
                    userId = dto.clientId,
                    statusCode = dto.status,
                    latencyMs = dto.latencyMs
                )
            }
            val currentRule = repository.rules.value.find { it.endpointId == targetEndpoint }?.let { r ->
                RateLimit(
                    ruleId = "rl_" + r.endpointId.replace("/", "_").trimStart('_'),
                    endpointId = r.endpointId,
                    limitPerMin = r.limitPerMin,
                    burstLimit = r.burstLimit,
                    action = r.action,
                    updatedAt = System.currentTimeMillis()
                )
            }
            val optResult = optimizer.optimize(targetEndpoint, logs, currentRule)
            _polyLanceState.update { it.copy(optimizationResult = optResult) }
            _actionMessage.value = "Optimizer evaluated ${optResult.totalAnalyzed} logs on $targetEndpoint"
        }
    }

    fun applyOptimizedLimit() {
        viewModelScope.launch {
            val recRule = _polyLanceState.value.optimizationResult?.recommendedRule ?: return@launch
            repository.addRule(
                RateLimitRuleDto(
                    endpointId = recRule.endpointId,
                    limitPerMin = recRule.limitPerMin,
                    burstLimit = recRule.burstLimit,
                    action = recRule.action
                )
            )
            _actionMessage.value = "Applied optimized limit: ${recRule.limitPerMin} req/min (Burst: ${recRule.burstLimit})"
            _polyLanceState.update { it.copy(optimizationResult = null) }
        }
    }

    fun dismissOptimization() {
        _polyLanceState.update { it.copy(optimizationResult = null) }
    }

    fun simulateAttackBurst() {
        simulateTraffic(endpoint = "/api/polylance/escrows", count = 30, rps = 25.0, profile = "attack")
    }

    fun clearLogs() {
        repository.clearLogs()
        _actionMessage.value = "Local log stream cleared"
    }

    fun logout(activity: Activity? = null) {
        viewModelScope.launch {
            authManager.signOut(activity)
        }
    }

    fun pairWithGateway(input: String) {
        viewModelScope.launch {
            try {
                val trimmed = input.trim()
                var targetHost = trimmed
                var token: String? = null

                if (trimmed.startsWith("nt14-pair://") || trimmed.contains("pair?")) {
                    val uri = android.net.Uri.parse(trimmed)
                    val hostParam = uri.getQueryParameter("host")
                    val ticketParam = uri.getQueryParameter("ticket")
                    if (!hostParam.isNullOrBlank()) targetHost = hostParam
                    if (!ticketParam.isNullOrBlank()) token = ticketParam
                } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    val hostMatch = Regex("\"host\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
                    val ticketMatch = Regex("\"ticket\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
                    if (hostMatch != null) targetHost = hostMatch.groupValues[1]
                    if (ticketMatch != null) token = ticketMatch.groupValues[1]
                }

                val cleanHost = targetHost.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://").trimEnd('/')
                sessionManager.saveGatewayHost(cleanHost)

                if (!token.isNullOrBlank()) {
                    sessionManager.saveSession(
                        email = "admin@cutm.nt14.com",
                        name = "Admin (Paired)",
                        role = UserRole.ADMIN,
                        provider = "gateway-pair",
                        jwtToken = token
                    )
                }

                _actionMessage.value = "Paired with $cleanHost successfully!"
                repository.updateHost(cleanHost)
            } catch (e: Exception) {
                _actionMessage.value = "Pairing failed: ${e.message}"
            }
        }
    }

    fun fetchPairingFromServer(host: String) {
        viewModelScope.launch {
            _actionMessage.value = "Fetching pairing token from $host..."
            try {
                val cleanHost = host.removePrefix("http://").removePrefix("https://").trimEnd('/')
                val scheme = if (cleanHost.contains("onrender.com") || host.startsWith("https://")) "https" else "http"
                val url = "$scheme://$cleanHost/api/auth/pair"

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val req = okhttp3.Request.Builder().url(url).get().build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    pairWithGateway(body)
                } else {
                    _actionMessage.value = "Failed to fetch pairing from $host (HTTP ${resp.code})"
                }
            } catch (e: Exception) {
                _actionMessage.value = "Pairing probe failed: ${e.message}"
            }
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }
}
