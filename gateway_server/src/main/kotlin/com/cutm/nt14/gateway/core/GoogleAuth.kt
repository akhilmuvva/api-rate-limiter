package com.cutm.nt14.gateway.core

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data class GoogleLoginRequest(val idToken: String)          // NOTHING else is trusted from the client
@Serializable data class ErrorBody(val error: String)
@Serializable data class LoginResponse(val token: String, val role: String, val email: String, val expiresIn: Long)

object GoogleAuth {
    val clientId: String
        get() = System.getenv("GOOGLE_CLIENT_ID") ?: ""

    val adminEmails: Set<String>
        get() = (System.getenv("ADMIN_EMAILS") ?: "akpolylance@gmail.com")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    // Verifies signature (Google public keys), issuer, audience and expiry.
    @Volatile
    var customVerifier: GoogleIdTokenVerifier? = null

    val verifier: GoogleIdTokenVerifier
        get() {
            customVerifier?.let { return it }
            val audienceList = if (clientId.isNotBlank()) listOf(clientId) else emptyList()
            val builder = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
            if (audienceList.isNotEmpty()) {
                builder.setAudience(audienceList)
            }
            return builder.build()
        }

    /** Returns the verified payload, or null if the token is forged, expired, or for another app. */
    suspend fun verify(rawIdToken: String): GoogleIdToken.Payload? = withContext(Dispatchers.IO) {
        runCatching { verifier.verify(rawIdToken)?.payload }.getOrNull()
    }

    /** ADMIN only when Google says the email is verified AND it is on the whitelist. */
    fun roleFor(payload: GoogleIdToken.Payload, adminWhitelist: Set<String> = adminEmails): String {
        val email = payload.email?.lowercase() ?: return "VIEWER"
        return if (payload.emailVerified == true && email in adminWhitelist) "ADMIN" else "VIEWER"
    }
}

// ---- Route: POST /api/auth/google --------------------------------------------------------------
// `jwtService.sign(...)` stands for your existing HS256 JwtService; adapt the call to its real API.
fun Route.googleAuthRoute(jwtService: JwtService) {
    post("/api/auth/google") {
        val body = runCatching { call.receive<GoogleLoginRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request"))

        val payload = GoogleAuth.verify(body.idToken)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_google_token"))

        val email = payload.email?.lowercase() ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorBody("missing_email"))
        val role = GoogleAuth.roleFor(payload)                       // from the VERIFIED token, never from the request
        val ttl = 24 * 3600L
        val token = jwtService.sign(subject = payload.subject, email = email, role = role, ttlSeconds = ttl)

        call.respond(LoginResponse(token = token, role = role, email = email, expiresIn = ttl))
    }
}

// ---- Guard for admin-only routes (rules, bans, simulate) ---------------------------------------
// Usage: post("/api/simulate") { if (!call.requireAdmin(jwtService)) return@post; ... }
suspend fun ApplicationCall.requireAdmin(jwtService: JwtService): Boolean {
    val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        ?: request.queryParameters["token"]
    val claims = bearer?.let { jwtService.verify(it) }               // checks HMAC signature + expiry
    return when {
        claims == null -> { respond(HttpStatusCode.Unauthorized, ErrorBody("unauthenticated")); false }
        claims.role != "ADMIN" -> { respond(HttpStatusCode.Forbidden, ErrorBody("admin_only")); false }
        else -> true
    }
}

// ---- Guard for read routes (rules, bans, stats, logs, reports) --------------------------------
// Requires valid, unexpired Gateway JWT (role = ADMIN or VIEWER)
suspend fun ApplicationCall.requireAuth(jwtService: JwtService): JwtClaims? {
    val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        ?: request.queryParameters["token"]
    val claims = bearer?.let { jwtService.verify(it) }
    if (claims == null) {
        respond(HttpStatusCode.Unauthorized, ErrorBody("unauthenticated"))
        return null
    }
    return claims
}
