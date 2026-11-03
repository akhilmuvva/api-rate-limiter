package com.cutm.nt14.data.repository

import com.cutm.nt14.data.local.SessionManager
import com.cutm.nt14.data.remote.GatewayConnectionState
import com.cutm.nt14.data.remote.GatewayWebSocketClient
import com.cutm.nt14.data.remote.model.*
import com.cutm.nt14.domain.model.UserRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayRepository @Inject constructor(
    private val wsClient: GatewayWebSocketClient,
    private val sessionManager: SessionManager,
    val resolver: com.cutm.nt14.data.remote.GatewayEndpointResolver
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val connectionState: StateFlow<GatewayConnectionState> = wsClient.connectionState
    val connectedHost: StateFlow<String> = wsClient.connectedHost
    val resolverState: StateFlow<com.cutm.nt14.data.remote.ResolverState> = resolver.state
    val endpointType: StateFlow<com.cutm.nt14.data.remote.EndpointType> = resolver.endpointType

    val isOffline: StateFlow<Boolean> = wsClient.connectionState.map {
        it !is GatewayConnectionState.Connected
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val metrics: StateFlow<GatewayMetrics> = wsClient.liveMetrics
    val rules: StateFlow<List<RateLimitRuleDto>> = wsClient.liveRules
    val bans: StateFlow<List<ActiveBanDto>> = wsClient.liveBans
    val incidents: StateFlow<List<IncidentDto>> = wsClient.liveIncidents
    val logs: StateFlow<List<RequestLogDto>> = wsClient.liveLogs
    val clients: StateFlow<List<ClientInfoDto>> = wsClient.liveClients
    val targets: StateFlow<List<TargetStatusDto>> = wsClient.liveTargets
    val lastDataSyncMs: StateFlow<Long> = wsClient.lastDataSyncMs
    val persistenceMode: StateFlow<String> = wsClient.persistenceMode
    val nextLogCursor: StateFlow<String?> = wsClient.nextLogCursor

    val userRole: StateFlow<UserRole> = sessionManager.userRole.stateIn(
        scope, SharingStarted.Eagerly, UserRole.VIEWER
    )

    val selectionMode: Flow<String> = sessionManager.selectionMode
    val lanHost: Flow<String> = sessionManager.lanHost
    val manualHost: Flow<String> = sessionManager.manualHost

    init {
        scope.launch {
            wsClient.connect()
        }
    }

    suspend fun setAutoMode() {
        sessionManager.saveSelectionMode("AUTO")
        resolver.resolve(force = true)
    }

    suspend fun setManualHost(host: String) {
        sessionManager.saveSelectionMode("MANUAL")
        sessionManager.saveManualHost(host)
        resolver.resolve(force = true)
    }

    suspend fun setLanHost(host: String) {
        sessionManager.saveLanHost(host)
        resolver.resolve(force = true)
    }

    fun reResolve() {
        resolver.resolve(force = true)
    }

    suspend fun refresh() {
        wsClient.refreshRestData()
    }

    suspend fun addRule(rule: RateLimitRuleDto): Boolean {
        return wsClient.addRule(rule)
    }

    suspend fun deleteRule(endpoint: String): Boolean {
        return wsClient.deleteRule(endpoint)
    }

    suspend fun banClient(clientId: String, durationMinutes: Long = 60, reason: String = "Manual ban"): Boolean {
        return wsClient.banClient(clientId, durationMinutes, reason)
    }

    suspend fun unbanClient(clientId: String): Boolean {
        return wsClient.unbanClient(clientId)
    }

    suspend fun resolveIncident(id: String): Boolean {
        return wsClient.resolveIncident(id)
    }

    suspend fun simulate(endpoint: String, count: Int, rps: Double, profile: String): Boolean {
        val req = SimulateRequestDto(
            endpoint = endpoint,
            requestCount = count,
            rps = rps,
            profile = profile
        )
        return wsClient.simulateTraffic(req)
    }

    suspend fun getReport(range: String, source: String? = null): TrafficReportDto? {
        return wsClient.fetchReports(range, source)
    }

    suspend fun getMetricsSeries(range: String, source: String? = null): MetricsSeriesDto? {
        return wsClient.fetchMetricsSeries(range, source)
    }

    suspend fun fetchLogs(limit: Int = 50, cursor: String? = null, source: String? = null, status: String? = null, clientQuery: String? = null): List<RequestLogDto> {
        return wsClient.fetchLogs(limit, cursor, source, status, clientQuery)
    }

    suspend fun fetchTargetsStatus(): List<TargetStatusDto> {
        return wsClient.fetchTargetsStatus()
    }

    suspend fun createTarget(req: TargetRequestDto): Boolean {
        return wsClient.createTarget(req)
    }

    suspend fun deleteTarget(id: Long): Boolean {
        return wsClient.deleteTarget(id)
    }

    suspend fun probeTargetNow(id: Long): TargetStatusDto? {
        return wsClient.probeTargetNow(id)
    }

    suspend fun downloadCsvExport(source: String? = null): String? {
        return wsClient.downloadCsvExport(source)
    }

    suspend fun fetchWhoAmI(): WhoAmIDto? {
        return wsClient.fetchWhoAmI()
    }

    suspend fun getSessionToken(): String? {
        return wsClient.getSessionToken()
    }

    fun reconnect() {
        wsClient.reconnect()
    }

    fun updateHost(newHost: String) {
        wsClient.reconnectWithHost(newHost)
    }

    fun clearLogs() {
        wsClient.clearLogs()
    }

    suspend fun switchRole(role: UserRole) {
        val email = if (role == UserRole.ADMIN) "akpolylance@gmail.com" else "guest@cutm.nt14.com"
        val name = if (role == UserRole.ADMIN) "Admin" else "Guest Viewer"
        sessionManager.saveSession(
            email = email,
            name = name,
            role = role,
            provider = "direct",
            jwtToken = if (role == UserRole.ADMIN) "dev-admin-token" else "dev-guest-token"
        )
        reconnect()
    }
}
