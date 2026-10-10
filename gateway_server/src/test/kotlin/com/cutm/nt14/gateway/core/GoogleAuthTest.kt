package com.cutm.nt14.gateway.core

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class GoogleAuthTest {

    private val jwtService = JwtService("test-secret-key-nt14-gateway-production-test")

    @Test
    fun testForgedGoogleTokenRejected() = runBlocking {
        // Completely forged/garbage token string
        val forgedToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NSIsImVtYWlsIjoiYWRtaW5AZXhhbXBsZS5jb20ifQ.fake-signature-here"
        val payload = GoogleAuth.verify(forgedToken)
        assertNull("Forged Google ID token must be rejected and return null payload", payload)
    }

    @Test
    fun testWrongAudienceRejected() = runBlocking {
        // Build verifier expecting specific audience "correct-web-client-id"
        val strictVerifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(listOf("correct-web-client-id"))
            .build()

        GoogleAuth.customVerifier = strictVerifier

        // Forged or wrong audience token
        val invalidAudienceToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMiLCJhdWQiOiJ3cm9uZy1hdWRpZW5jZSIsImVtYWlsIjoiYWtwb2x5bGFuY2VAZ21haWwuY29tIn0.sig"
        val payload = GoogleAuth.verify(invalidAudienceToken)
        assertNull("Token with wrong audience must fail verification", payload)

        GoogleAuth.customVerifier = null
    }

    @Test
    fun testNonAdminAccountYieldsViewerRole() {
        val payload = GoogleIdToken.Payload().apply {
            email = "regular.user@example.com"
            emailVerified = true
            subject = "google-sub-12345"
        }

        val adminWhitelist = setOf("akpolylance@gmail.com")
        val role = GoogleAuth.roleFor(payload, adminWhitelist)

        assertEquals("VIEWER", role)
    }

    @Test
    fun testUnverifiedEmailYieldsViewerRoleEvenIfOnAdminWhitelist() {
        val payload = GoogleIdToken.Payload().apply {
            email = "akpolylance@gmail.com"
            emailVerified = false // Google says email is NOT verified!
            subject = "google-sub-forged-email"
        }

        val adminWhitelist = setOf("akpolylance@gmail.com")
        val role = GoogleAuth.roleFor(payload, adminWhitelist)

        assertEquals("Unverified email must NEVER receive ADMIN role", "VIEWER", role)
    }

    @Test
    fun testAdminAccountYieldsAdminRole() {
        val payload = GoogleIdToken.Payload().apply {
            email = "akpolylance@gmail.com"
            emailVerified = true
            subject = "google-sub-admin-1"
        }

        val adminWhitelist = setOf("akpolylance@gmail.com")
        val role = GoogleAuth.roleFor(payload, adminWhitelist)

        assertEquals("Verified admin email must receive ADMIN role", "ADMIN", role)
    }

    @Test
    fun testForgedGatewayJwtRejected() {
        // Legitimate token issued for regular user
        val legitimateToken = jwtService.sign(
            subject = "user1",
            email = "regular.user@example.com",
            role = "VIEWER",
            ttlSeconds = 3600
        )

        val parts = legitimateToken.split(".")
        // Tamper with payload: promote role to ADMIN
        val tamperedPayloadJson = """{"sub":"user1","email":"regular.user@example.com","name":"user","role":"ADMIN","iat":1000,"exp":9999999999,"iss":"nt14-gateway","provider":"google"}"""
        val tamperedPayloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedPayloadJson.toByteArray(StandardCharsets.UTF_8))
        val forgedJwt = "${parts[0]}.$tamperedPayloadB64.${parts[2]}"

        val verifiedClaims = jwtService.verify(forgedJwt)
        assertNull("Forged / tampered gateway JWT must fail verification", verifiedClaims)
    }

    @Test
    fun testExpiredGatewayJwtRejected() {
        val expiredJwt = jwtService.sign(
            subject = "user1",
            email = "akpolylance@gmail.com",
            role = "ADMIN",
            ttlSeconds = -30 // Expired 30 seconds ago
        )

        val verifiedClaims = jwtService.verify(expiredJwt)
        assertNull("Expired gateway JWT must fail verification", verifiedClaims)
    }

    @Test
    fun testValidAdminGatewayJwtVerified() {
        val validJwt = jwtService.sign(
            subject = "admin-sub",
            email = "akpolylance@gmail.com",
            role = "ADMIN",
            ttlSeconds = 3600
        )

        val claims = jwtService.verify(validJwt)
        assertNotNull("Valid gateway JWT must verify successfully", claims)
        assertEquals("ADMIN", claims!!.role)
        assertEquals("akpolylance@gmail.com", claims.email)
    }
}
