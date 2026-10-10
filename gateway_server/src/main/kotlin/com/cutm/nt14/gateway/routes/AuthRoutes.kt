package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.JwtClaims
import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.models.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

import com.cutm.nt14.gateway.core.googleAuthRoute

private val logger = LoggerFactory.getLogger("AuthRoutes")

fun Route.authRoutes(jwtService: JwtService) {
    googleAuthRoute(jwtService)

    route("/api/auth") {

        /**
         * Issues a short-lived, single-use ticket for WebSocket authentication.
         * Allows connecting to /ws/events without exposing JWT in URL query strings.
         */
        post("/ticket") {
            val authHeader = call.request.headers["Authorization"]
            val token = authHeader?.removePrefix("Bearer ")?.trim()
                ?: call.request.queryParameters["token"]

            val claims = token?.let { jwtService.verifyToken(it) }
            val role = claims?.role ?: "VIEWER"
            val email = claims?.email ?: "guest@cutm.nt14"

            val ticket = jwtService.createWsTicket(role, email, durationSeconds = 60L)
            call.respond(
                HttpStatusCode.OK,
                TicketResponse(
                    ticket = ticket,
                    role = role,
                    email = email,
                    expiresIn = 60L
                )
            )
        }

        /**
         * Verifies the caller's JWT token from Authorization header and returns identity claims.
         */
        get("/me") {
            val authHeader = call.request.headers["Authorization"]
            val token = authHeader?.removePrefix("Bearer ")?.trim()

            if (token.isNullOrBlank()) {
                call.respond(HttpStatusCode.Unauthorized, ApiMessage("Missing Authorization Bearer token"))
                return@get
            }

            val claims = jwtService.verifyToken(token)
            if (claims == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiMessage("Invalid or expired JWT token"))
                return@get
            }

            call.respond(
                HttpStatusCode.OK,
                MeResponse(
                    valid = true,
                    sub = claims.sub,
                    email = claims.email,
                    name = claims.name,
                    role = claims.role,
                    issuer = claims.iss,
                    issuedAt = claims.iat,
                    expiresAt = claims.exp
                )
            )
        }

        /**
         * Generates a one-time pairing ticket and payload for instant Android device pairing.
         */
        get("/pair") {
            val adminEmailsEnv = System.getenv("ADMIN_EMAILS") ?: "akpolylance@gmail.com"
            val adminEmail = adminEmailsEnv.split(",").firstOrNull { it.isNotBlank() }?.trim() ?: "admin@cutm.nt14.com"
            val ticket = jwtService.createWsTicket(role = "ADMIN", email = adminEmail, durationSeconds = 600L)
            val host = call.request.headers["Host"] ?: "127.0.0.1:8000"
            val isTls = call.request.headers["X-Forwarded-Proto"] == "https"
            val scheme = if (isTls) "https" else "http"
            val wsScheme = if (isTls) "wss" else "ws"
            val baseUrl = "$scheme://$host"
            val wsUrl = "$wsScheme://$host/ws/events"
            val pairingUri = "nt14-pair://pair?host=${java.net.URLEncoder.encode(baseUrl, "UTF-8")}&ticket=$ticket"

            call.respond(
                HttpStatusCode.OK,
                PairResponse(
                    host = baseUrl,
                    wss = wsUrl,
                    ticket = ticket,
                    role = "ADMIN",
                    email = adminEmail,
                    pairingUri = pairingUri,
                    expiresIn = 600L
                )
            )
        }
    }
}
