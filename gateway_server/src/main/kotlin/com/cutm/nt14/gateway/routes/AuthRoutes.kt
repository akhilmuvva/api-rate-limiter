package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.JwtClaims
import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.models.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

import com.cutm.nt14.gateway.core.googleAuthRoute
import com.cutm.nt14.gateway.core.requireAuth
import com.cutm.nt14.gateway.core.ErrorBody
import io.ktor.server.plugins.origin
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("AuthRoutes")
private val pairAttemptsByIp = ConcurrentHashMap<String, MutableList<Long>>()

private fun checkPairRateLimit(ip: String, maxPerMinute: Int = 5): Boolean {
    val now = System.currentTimeMillis()
    val window = now - 60_000L
    val list = pairAttemptsByIp.compute(ip) { _, current ->
        val updated = current?.filter { it > window }?.toMutableList() ?: mutableListOf()
        if (updated.size < maxPerMinute) {
            updated.add(now)
        }
        updated
    }
    return (list?.size ?: 0) <= maxPerMinute
}

fun Route.authRoutes(jwtService: JwtService) {
    googleAuthRoute(jwtService)

    route("/api/auth") {

        /**
         * Issues a short-lived, single-use ticket for WebSocket authentication.
         * Requires a valid Gateway JWT.
         */
        post("/ticket") {
            val claims = call.requireAuth(jwtService) ?: return@post

            val ticket = jwtService.createWsTicket(claims.role, claims.email, durationSeconds = 60L)
            call.respond(
                HttpStatusCode.OK,
                TicketResponse(
                    ticket = ticket,
                    role = claims.role,
                    email = claims.email,
                    expiresIn = 60L
                )
            )
        }

        /**
         * Verifies the caller's JWT token from Authorization header and returns identity claims.
         * Requires a valid Gateway JWT.
         */
        get("/me") {
            val claims = call.requireAuth(jwtService) ?: return@get

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
         * Pairs an Android device with the Gateway.
         * STRICT SECURITY:
         * - Rate-limited to 5 requests per minute per IP.
         * - NOT publicly usable: Requires either:
         *   1) Valid ADMIN JWT, OR
         *   2) A one-time pairing ticket displayed ONLY on the gateway console / QR (strictly single-use).
         */
        get("/pair") {
            val clientIp = call.request.origin.remoteHost
            if (!checkPairRateLimit(clientIp)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorBody("rate_limit_exceeded"))
                return@get
            }

            // Check A: Valid ADMIN JWT
            val bearer = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
                ?: call.request.queryParameters["token"]
            val adminClaims = bearer?.let { jwtService.verify(it) }?.takeIf { it.role == "ADMIN" }

            // Check B: Single-use console pairing ticket
            val ticketQuery = call.request.queryParameters["ticket"] ?: call.request.headers["X-Pairing-Ticket"]
            val consumedTicket = ticketQuery?.let { jwtService.consumeOneTimePairingTicket(it) }

            if (adminClaims == null && consumedTicket == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorBody("pairing_requires_admin_auth_or_one_time_console_ticket")
                )
                return@get
            }

            val adminEmailsEnv = System.getenv("ADMIN_EMAILS") ?: "akpolylance@gmail.com"
            val adminEmail = adminClaims?.email ?: consumedTicket?.email
                ?: adminEmailsEnv.split(",").firstOrNull { it.isNotBlank() }?.trim() ?: "admin@cutm.nt14.com"

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
