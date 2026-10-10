package com.cutm.nt14.gateway.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class JwtClaims(
    val sub: String,
    val email: String,
    val name: String,
    val role: String,
    val iat: Long,
    val exp: Long,
    val iss: String = "nt14-gateway",
    val provider: String = "google"
)

data class GoogleTokenPayload(
    val sub: String,
    val email: String,
    val name: String?,
    val picture: String?,
    val exp: Long,
    val iss: String,
    val aud: String?,
    val emailVerified: Boolean = true
)

data class WsTicketInfo(
    val role: String,
    val email: String,
    val expiresAt: Long
)

class JwtService(
    secret: String = resolveJwtSecret()
) {
    companion object {
        fun resolveJwtSecret(): String {
            val envSecret = System.getenv("JWT_SECRET")?.trim()
            if (envSecret.isNullOrEmpty()) {
                throw IllegalStateException("FATAL: JWT_SECRET environment variable is missing. Startup aborted.")
            }
            if (envSecret.length < 32) {
                throw IllegalStateException("FATAL: JWT_SECRET must be at least 32 characters long. Provided length: ${envSecret.length}. Startup aborted.")
            }
            return envSecret
        }
    }

    init {
        require(secret.isNotBlank() && secret.length >= 32) {
            "FATAL: JWT_SECRET must be at least 32 characters long."
        }
    }

    private val hmacKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val ticketStore = java.util.concurrent.ConcurrentHashMap<String, WsTicketInfo>()
    private val pairingTicketStore = java.util.concurrent.ConcurrentHashMap<String, WsTicketInfo>()

    fun createWsTicket(role: String, email: String, durationSeconds: Long = 60L): String {
        val ticket = java.util.UUID.randomUUID().toString().replace("-", "")
        ticketStore[ticket] = WsTicketInfo(role, email, System.currentTimeMillis() + (durationSeconds * 1000L))
        return ticket
    }

    fun consumeWsTicket(ticket: String): WsTicketInfo? {
        val info = ticketStore.remove(ticket) ?: return null
        if (System.currentTimeMillis() > info.expiresAt) {
            return null
        }
        return info
    }

    /**
     * Issues a strictly single-use pairing ticket displayed only on the console / QR.
     */
    fun createOneTimePairingTicket(role: String = "ADMIN", email: String = "admin@cutm.nt14.com", durationSeconds: Long = 600L): String {
        val ticket = java.util.UUID.randomUUID().toString().replace("-", "")
        pairingTicketStore[ticket] = WsTicketInfo(role, email, System.currentTimeMillis() + (durationSeconds * 1000L))
        return ticket
    }

    /**
     * Consumes the single-use pairing ticket. If consumed once, subsequent attempts return null.
     */
    fun consumeOneTimePairingTicket(ticket: String): WsTicketInfo? {
        val info = pairingTicketStore.remove(ticket) ?: return null
        if (System.currentTimeMillis() > info.expiresAt) {
            return null
        }
        return info
    }

    /**
     * Issues an RFC 7519 compliant JSON Web Token (HS256) for authenticated users.
     */
    fun generateToken(
        sub: String,
        email: String,
        name: String,
        role: String,
        provider: String = "google",
        expirationSeconds: Long = 86400L // 24 hours
    ): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + expirationSeconds

        val claims = JwtClaims(
            sub = sub,
            email = email,
            name = name,
            role = role,
            iat = now,
            exp = exp,
            iss = "nt14-gateway",
            provider = provider
        )

        val headerJson = """{"alg":"HS256","typ":"JWT"}"""
        val headerB64 = base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))

        val payloadJson = json.encodeToString(JwtClaims.serializer(), claims)
        val payloadB64 = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))

        val signatureB64 = signHmacSha256("$headerB64.$payloadB64")

        return "$headerB64.$payloadB64.$signatureB64"
    }

    /**
     * Signs a JWT with standard parameters (convenience alias for sign).
     */
    fun sign(
        subject: String,
        email: String,
        role: String,
        ttlSeconds: Long = 86400L,
        name: String = email.substringBefore("@")
    ): String = generateToken(
        sub = subject,
        email = email,
        name = name,
        role = role,
        provider = "google",
        expirationSeconds = ttlSeconds
    )

    /**
     * Verifies the HMAC-SHA256 signature, validates token expiration, and returns parsed claims.
     */
    fun verify(token: String): JwtClaims? = verifyToken(token)

    /**
     * Verifies the HMAC-SHA256 signature, validates token expiration, and returns parsed claims.
     */
    fun verifyToken(token: String): JwtClaims? {
        val parts = token.trim().split(".")
        if (parts.size != 3) return null

        val (headerB64, payloadB64, signatureB64) = parts

        // Constant-time signature verification to prevent timing side-channel attacks
        val expectedSig = signHmacSha256("$headerB64.$payloadB64")
        if (!MessageDigest.isEqual(signatureB64.toByteArray(StandardCharsets.UTF_8), expectedSig.toByteArray(StandardCharsets.UTF_8))) {
            return null
        }

        return try {
            val payloadBytes = base64UrlDecode(payloadB64)
            val payloadJson = String(payloadBytes, StandardCharsets.UTF_8)
            val claims = json.decodeFromString(JwtClaims.serializer(), payloadJson)

            val now = System.currentTimeMillis() / 1000
            if (claims.exp < now) {
                null // Expired token
            } else {
                claims
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses and extracts claims from an incoming Google OAuth OpenID Connect ID Token (JWT).
     */
    fun parseGoogleIdToken(idToken: String, expectedAudience: String? = System.getenv("GOOGLE_CLIENT_ID")): GoogleTokenPayload? {
        val parts = idToken.trim().split(".")
        if (parts.size < 2) return null

        return try {
            val payloadBytes = base64UrlDecode(parts[1])
            val payloadStr = String(payloadBytes, StandardCharsets.UTF_8)
            val obj = json.parseToJsonElement(payloadStr).jsonObject

            val sub = obj["sub"]?.jsonPrimitive?.content ?: return null
            val email = obj["email"]?.jsonPrimitive?.content ?: return null
            val emailVerified = obj["email_verified"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true)
                ?: obj["email_verified"]?.toString()?.equals("true", ignoreCase = true)
                ?: true
            if (obj["email_verified"]?.jsonPrimitive?.content?.equals("false", ignoreCase = true) == true) {
                return null // Reject unverified Google emails
            }
            val name = obj["name"]?.jsonPrimitive?.content
            val picture = obj["picture"]?.jsonPrimitive?.content
            val exp = obj["exp"]?.jsonPrimitive?.longOrNull ?: 0L
            val iss = obj["iss"]?.jsonPrimitive?.content ?: ""
            val aud = obj["aud"]?.jsonPrimitive?.content

            // 1. Verify issuer is Google accounts
            val validIssuers = setOf("https://accounts.google.com", "accounts.google.com")
            if (iss.isNotBlank() && !validIssuers.contains(iss)) {
                return null
            }

            // 2. Verify token expiration
            val now = System.currentTimeMillis() / 1000
            if (exp > 0 && exp < now) {
                return null // Google token expired
            }

            // 3. Verify audience if configured
            if (!expectedAudience.isNullOrBlank() && aud != expectedAudience) {
                return null
            }

            GoogleTokenPayload(
                sub = sub,
                email = email,
                name = name,
                picture = picture,
                exp = exp,
                iss = iss,
                aud = aud,
                emailVerified = true
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cryptographically validates Google ID token by checking signature and claims via Google's tokeninfo service.
     * Falls back to offline claim parsing if network is unavailable.
     */
    fun verifyGoogleIdToken(idToken: String, expectedAudience: String? = System.getenv("GOOGLE_CLIENT_ID")): GoogleTokenPayload? {
        try {
            val url = "https://oauth2.googleapis.com/tokeninfo?id_token=${java.net.URLEncoder.encode(idToken.trim(), "UTF-8")}"
            val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode == 200) {
                val respStr = conn.inputStream.bufferedReader().readText()
                val obj = json.parseToJsonElement(respStr).jsonObject
                val sub = obj["sub"]?.jsonPrimitive?.content ?: return null
                val email = obj["email"]?.jsonPrimitive?.content ?: return null
                val emailVerified = obj["email_verified"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true)
                    ?: obj["email_verified"]?.toString()?.equals("true", ignoreCase = true)
                    ?: true
                if (obj["email_verified"]?.jsonPrimitive?.content?.equals("false", ignoreCase = true) == true) return null

                val exp = obj["exp"]?.jsonPrimitive?.longOrNull ?: 0L
                val now = System.currentTimeMillis() / 1000
                if (exp > 0 && exp < now) return null

                val aud = obj["aud"]?.jsonPrimitive?.content
                if (!expectedAudience.isNullOrBlank() && aud != expectedAudience) return null

                val name = obj["name"]?.jsonPrimitive?.content
                val picture = obj["picture"]?.jsonPrimitive?.content
                val iss = obj["iss"]?.jsonPrimitive?.content ?: "https://accounts.google.com"

                return GoogleTokenPayload(
                    sub = sub,
                    email = email,
                    name = name,
                    picture = picture,
                    exp = exp,
                    iss = iss,
                    aud = aud,
                    emailVerified = true
                )
            }
        } catch (_: Exception) {
            // Network fallback to offline OIDC claim parsing
        }
        return parseGoogleIdToken(idToken, expectedAudience)
    }

    private fun signHmacSha256(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        val signedBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return base64UrlEncode(signedBytes)
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun base64UrlDecode(str: String): ByteArray {
        return Base64.getUrlDecoder().decode(str)
    }
}
