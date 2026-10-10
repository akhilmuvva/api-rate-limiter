package com.cutm.nt14.gateway.routes

import com.cutm.nt14.gateway.core.JwtClaims
import com.cutm.nt14.gateway.core.JwtService
import com.cutm.nt14.gateway.models.ApiMessage
import com.cutm.nt14.gateway.models.AuthResponse
import com.cutm.nt14.gateway.models.AuthUserInfo
import com.cutm.nt14.gateway.models.GoogleAuthRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AuthRoutes")

fun Route.authRoutes(jwtService: JwtService) {
    route("/api/auth") {
        /**
         * Authenticates a Google user using their Google ID Token (JWT) or verified Google email.
         * Generates and returns a signed Gateway Session JWT.
         */
        post("/google") {
            try {
                val req = call.receive<GoogleAuthRequest>()
                
                // 1. Authenticate Google user using cryptographically verified ID token
                val verifiedPayload = if (!req.idToken.isNullOrBlank()) {
                    jwtService.verifyGoogleIdToken(req.idToken)
                } else null

                val finalEmail: String
                val finalName: String
                val finalSub: String
                val role: String

                val adminEmailsEnv = System.getenv("ADMIN_EMAILS") ?: "akpolylance@gmail.com"
                val adminEmails = adminEmailsEnv.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

                if (verifiedPayload != null) {
                    finalEmail = verifiedPayload.email.trim().lowercase()
                    finalName = verifiedPayload.name ?: req.displayName ?: "Google User"
                    finalSub = verifiedPayload.sub
                    // Role derived ONLY from verified Google email
                    role = if (adminEmails.contains(finalEmail)) "ADMIN" else "VIEWER"
                    logger.info("Validated Google ID token for $finalEmail -> role=$role")
                } else {
                    // No valid Google ID token: NEVER trust client-supplied email for ADMIN role.
                    // Fall back to unauthenticated guest VIEWER
                    finalEmail = (req.email?.takeIf { it.isNotBlank() } ?: "guest@cutm.nt14").trim().lowercase()
                    finalName = req.displayName ?: "Guest User"
                    finalSub = "guest_" + java.util.UUID.randomUUID().toString().take(8)
                    role = "VIEWER" // Strictest security: unverified guests are always VIEWER
                    logger.info("Issued unverified guest session for $finalEmail with role=VIEWER")
                }

                val token = jwtService.generateToken(
                    sub = finalSub,
                    email = finalEmail,
                    name = finalName,
                    role = role,
                    provider = if (verifiedPayload != null) "google" else "guest",
                    expirationSeconds = 86400L // 24 hours
                )

                logger.info("Issued Gateway JWT for $finalEmail with role $role")

                call.respond(
                    HttpStatusCode.OK,
                    AuthResponse(
                        token = token,
                        tokenType = "Bearer",
                        expiresIn = 86400L,
                        user = AuthUserInfo(
                            email = finalEmail,
                            name = finalName,
                            role = role,
                            provider = if (verifiedPayload != null) "google" else "guest"
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Error during Google JWT auth: ${e.message}", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiMessage("Google authentication failed: ${e.message}")
                )
            }
        }

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
                mapOf(
                    "ticket" to ticket,
                    "role" to role,
                    "email" to email,
                    "expiresIn" to 60
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
                mapOf(
                    "valid" to true,
                    "sub" to claims.sub,
                    "email" to claims.email,
                    "name" to claims.name,
                    "role" to claims.role,
                    "issuer" to claims.iss,
                    "issuedAt" to claims.iat,
                    "expiresAt" to claims.exp
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
                mapOf(
                    "host" to baseUrl,
                    "wss" to wsUrl,
                    "ticket" to ticket,
                    "role" to "ADMIN",
                    "email" to adminEmail,
                    "pairingUri" to pairingUri,
                    "expiresIn" to 600
                )
            )
        }
    }
}
