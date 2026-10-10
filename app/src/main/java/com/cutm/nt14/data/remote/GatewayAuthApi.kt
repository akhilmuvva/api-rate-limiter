package com.cutm.nt14.data.remote

import com.cutm.nt14.data.local.SessionManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class GoogleLoginRequest(
    @SerializedName("idToken") val idToken: String
)

data class LoginResponse(
    @SerializedName("token") val token: String,
    @SerializedName("role") val role: String,
    @SerializedName("email") val email: String,
    @SerializedName("expiresIn") val expiresIn: Long
)

interface GatewayAuthApi {
    suspend fun loginWithGoogle(request: GoogleLoginRequest): LoginResponse
}

@Singleton
class GatewayAuthApiImpl @Inject constructor(
    private val sessionManager: SessionManager
) : GatewayAuthApi {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun loginWithGoogle(request: GoogleLoginRequest): LoginResponse = withContext(Dispatchers.IO) {
        val host = sessionManager.gatewayHost.first().trim()
        val baseUrl = when {
            host.startsWith("http://") || host.startsWith("https://") -> host.trimEnd('/')
            host.contains("onrender.com") -> "https://$host".trimEnd('/')
            else -> "http://$host".trimEnd('/')
        }

        val url = "$baseUrl/api/auth/google"
        val requestBody = gson.toJson(request).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Gateway authentication failed with code ${response.code}: $bodyString")
            }
            gson.fromJson(bodyString, LoginResponse::class.java)
                ?: throw IOException("Empty or invalid login response from gateway")
        }
    }
}
