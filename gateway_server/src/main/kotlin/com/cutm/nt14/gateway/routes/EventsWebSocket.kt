package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.core.WebSocketManager
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
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

        val clientEmail: String
        val clientRole: String

        if (!ticketParam.isNullOrBlank()) {
            val ticketInfo = jwtService.consumeWsTicket(ticketParam)
            if (ticketInfo != null) {
                clientRole = ticketInfo.role
                clientEmail = ticketInfo.email
            } else {
                clientRole = "VIEWER"
                clientEmail = "guest@cutm.nt14"
            }
        } else if (!bearerToken.isNullOrBlank()) {
            val claims = jwtService.verifyToken(bearerToken)
            if (claims != null) {
                clientRole = claims.role
                clientEmail = claims.email
            } else {
                clientRole = "VIEWER"
                clientEmail = "guest@cutm.nt14"
            }
        } else {
            clientRole = "VIEWER"
            clientEmail = "guest@cutm.nt14"
        }

        val lastEventId = call.request.queryParameters["lastEventId"]
            ?: call.request.headers["Last-Event-ID"]

        logger.info("Accepted WebSocket subscriber for $clientEmail (role=$clientRole, lastEventId=$lastEventId)")

        webSocketManager.register(this, lastEventId)
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
