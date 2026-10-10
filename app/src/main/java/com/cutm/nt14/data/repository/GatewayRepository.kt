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
    private val sessionManager: SessionManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val connectionState: StateFlow<GatewayConnectionState> = wsClient.connectionState
    val connectedHost: StateFlow<String> = wsClient.connectedHost

    val isOffline: StateFlow<Boolean> = wsClient.connectionState.map {
        it !is GatewayConnectionState.Connected
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val metrics: StateFlow<GatewayMetrics> = wsClient.liveMetrics
    val rules: StateFlow<List<RateLimitRuleDto>> = wsClient.liveRules
    val bans: StateFlow<List<ActiveBanDto>> = wsClient.liveBans
    val incidents: StateFlow<List<IncidentDto>> = wsClient.liveIncidents
    val logs: StateFlow<List<RequestLogDto>> = wsClient.liveLogs
    val clients: StateFlow<List<ClientInfoDto>> = wsClient.liveClients

    val userRole: StateFlow<UserRole> = sessionManager.userRole.stateIn(
        scope, SharingStarted.Eagerly, UserRole.VIEWER
    )

    init {
        scope.launch {
            wsClient.connect()
        }
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

    suspend fun simulate(endpoint: String, count: Int, rps: Double, profile: String): Boolean {
        val req = SimulateRequestDto(
            endpoint = endpoint,
            requestCount = count,
            rps = rps,
            profile = profile
        )
        return wsClient.simulateTraffic(req)
    }

    suspend fun getReport(range: String): TrafficReportDto? {
        return wsClient.fetchReports(range)
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
}
