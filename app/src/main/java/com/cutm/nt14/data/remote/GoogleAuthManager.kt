package com.cutm.nt14.data.remote

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.cutm.nt14.BuildConfig
import com.cutm.nt14.data.local.SessionManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GatewayAuthApi,          // POST /api/auth/google { idToken } -> { token, role, email, expiresIn }
    private val session: SessionManager,      // encrypted store
) {
    suspend fun signIn(activityContext: Context): Result<Unit> = runCatching {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setNonce(nonce)
            .build()

        val result = CredentialManager.create(context)
            .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())

        val cred = result.credential
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unsupported credential"
        }
        val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken

        // Send ONLY the ID token. The gateway derives email and role from it; the app never decides the role.
        val login = api.loginWithGoogle(GoogleLoginRequest(idToken))
        session.save(token = login.token, role = login.role, email = login.email)   // role is for UI display only
    }

    suspend fun signOut(activity: Activity? = null) {
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
        session.clearSession()
    }
}
