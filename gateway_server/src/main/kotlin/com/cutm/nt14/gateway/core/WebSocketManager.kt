package com.cutm.nt14.gateway.core

import com.cutm.nt14.gateway.models.ActiveBanDto
import com.cutm.nt14.gateway.models.GatewayEvent
import com.cutm.nt14.gateway.models.GatewayMetrics
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active WebSocket subscriber sessions and broadcasts real-time telemetry events.
 * Directly matches the JSON schema expected by Android's GatewayWebSocketClient.
 */
class WebSocketManager {
    private val logger = LoggerFactory.getLogger(WebSocketManager::class.java)
    private val sessions = Collections.newSetFromMap(ConcurrentHashMap<DefaultWebSocketServerSession, Boolean>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    var historyManager: TrafficHistoryManager? = null
    var rateLimiter: RateLimiter? = null
    var clientTracker: ClientTracker? = null

    fun register(session: DefaultWebSocketServerSession, lastEventId: String? = null, isAdmin: Boolean = false) {
        sessions.add(session)
        logger.info("New WebSocket client connected (lastEventId=$lastEventId, isAdmin=$isAdmin). Active subscribers: ${sessions.size}")

        // Immediately send initial snapshot or replay events from lastEventId
        scope.launch {
            try {
                val rules = rateLimiter?.getAllRules() ?: emptyList()
                val bans = rateLimiter?.anomalyDetector?.getActiveBans()?.map { (ip, record) ->
                    ActiveBanDto(clientId = ip, reason = record.reason, expiresAt = record.bannedUntil)
                } ?: emptyList()
                val clients = clientTracker?.getAllClients(isAdmin, rateLimiter?.anomalyDetector) ?: emptyList()

                val missedLogs = if (!lastEventId.isNullOrBlank()) {
                    historyManager?.getLogsSince(lastEventId)
                } else null

                if (missedLogs != null) {
                    for (log in missedLogs) {
                        val replayEvent = GatewayEvent(
                            type = "request",
                            id = log.id,
                            ip = log.clientId,
                            endpoint = log.path,
                            method = log.method,
                            status = log.status,
                            latencyMs = log.latencyMs,
                            decision = log.decision,
                            timestamp = log.timestamp / 1000.0
                        )
                        session.send(Frame.Text(json.encodeToString(replayEvent)))
                    }
                    val metricsEvent = GatewayEvent(
                        type = "metrics",
                        metrics = historyManager?.calculateCurrentMetrics() ?: GatewayMetrics(),
                        rules = rules,
                        activeBans = bans,
                        clients = clients
                    )
                    session.send(Frame.Text(json.encodeToString(metricsEvent)))
                    logger.debug("Replayed ${missedLogs.size} events to resumed client since $lastEventId")
                } else {
                    val snapshot = historyManager?.buildSnapshot(rules, bans, clients) ?: GatewayEvent(
                        type = "snapshot",
                        metrics = GatewayMetrics(),
                        rules = rules,
                        activeBans = bans,
                        logs = emptyList(),
                        incidents = emptyList(),
                        clients = clients
                    )
                    session.send(Frame.Text(json.encodeToString(snapshot)))
                    logger.debug("Sent initial state snapshot to new subscriber")
                }
            } catch (e: Exception) {
                logger.warn("Failed to initialize subscriber session: ${e.message}")
            }
        }
    }

    fun unregister(session: DefaultWebSocketServerSession) {
        sessions.remove(session)
        logger.info("WebSocket client disconnected. Active subscribers: ${sessions.size}")
    }

    fun activeSubscriberCount(): Int = sessions.size

    fun broadcast(event: GatewayEvent) {
        val jsonPayload = json.encodeToString(event)
        val frame = Frame.Text(jsonPayload)

        scope.launch {
            val deadSessions = mutableListOf<DefaultWebSocketServerSession>()

            for (session in sessions) {
                try {
                    session.send(frame)
                } catch (e: ClosedSendChannelException) {
                    deadSessions.add(session)
                } catch (e: Exception) {
                    logger.warn("Failed to send WebSocket event to session: ${e.message}")
                    deadSessions.add(session)
                }
            }

            if (deadSessions.isNotEmpty()) {
                sessions.removeAll(deadSessions.toSet())
                logger.debug("Cleaned up ${deadSessions.size} closed WebSocket sessions.")
            }
        }
    }
}
