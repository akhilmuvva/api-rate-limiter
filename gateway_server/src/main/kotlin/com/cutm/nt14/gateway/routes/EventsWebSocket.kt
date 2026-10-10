package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.core.WebSocketManager
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.channels.consumeEach
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("EventsWebSocket")

/**
 * Real-time telemetry feed route matching Android's GatewayWebSocketClient.
 * Authenticates via Authorization header (Bearer JWT) or short-lived single-use ticket.
 * Automatically supports resume via lastEventId parameter.
 */
fun Route.eventsWebSocket(webSocketManager: WebSocketManager, jwtService: JwtService) {
    webSocket("/ws/events") {
        val authHeader = call.request.headers["Authorization"]
        val bearerToken = authHeader?.removePrefix("Bearer ")?.trim()
            ?: call.request.queryParameters["token"]
        val ticketParam = call.request.queryParameters["ticket"]

        val authenticatedInfo: Pair<String, String>? = when {
            !ticketParam.isNullOrBlank() -> {
                jwtService.consumeWsTicket(ticketParam)?.let { it.role to it.email }
            }
            !bearerToken.isNullOrBlank() -> {
                jwtService.verifyToken(bearerToken)?.let { it.role to it.email }
            }
            else -> null
        }

        if (authenticatedInfo == null) {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Authentication required: provide ?ticket= or Authorization header"))
            return@webSocket
        }

        val (clientRole, clientEmail) = authenticatedInfo

        val lastEventId = call.request.queryParameters["lastEventId"]
            ?: call.request.headers["Last-Event-ID"]

        logger.info("Accepted WebSocket subscriber for $clientEmail (role=$clientRole, lastEventId=$lastEventId)")

        webSocketManager.register(this, lastEventId, isAdmin = clientRole == "ADMIN")
        try {
            incoming.consumeEach { frame ->
                logger.trace("Received frame from client ($clientEmail): ${frame.frameType}")
            }
        } catch (e: Exception) {
            logger.debug("WebSocket session ended: ${e.message}")
        } finally {
            webSocketManager.unregister(this)
        }
    }
}
