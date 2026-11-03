package com.cutm.nt14.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cutm.nt14.domain.model.UserRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "session_prefs")

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val USER_EMAIL = stringPreferencesKey("user_email")
    private val USER_NAME = stringPreferencesKey("user_name")
    private val USER_ROLE = stringPreferencesKey("user_role")
    private val USER_PHOTO_URL = stringPreferencesKey("user_photo_url")
    private val AUTH_PROVIDER = stringPreferencesKey("auth_provider")
    private val USER_JWT_TOKEN = stringPreferencesKey("user_jwt_token")

    val userEmail: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_EMAIL]
    }

    val userName: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_NAME]
    }

    val userPhotoUrl: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_PHOTO_URL]
    }

    val authProvider: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[AUTH_PROVIDER]
    }

    val userJwtToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USER_JWT_TOKEN]?.let { TokenEncryptor.decrypt(it) }
    }

    val userRole: Flow<UserRole> = context.dataStore.data.map { prefs ->
        val roleStr = prefs[USER_ROLE] ?: UserRole.VIEWER.name
        UserRole.valueOf(roleStr)
    }

    private val GATEWAY_HOST = stringPreferencesKey("gateway_host")
    private val GATEWAY_SELECTION_MODE = stringPreferencesKey("gateway_selection_mode")
    private val GATEWAY_MANUAL_HOST = stringPreferencesKey("gateway_manual_host")
    private val GATEWAY_LAN_HOST = stringPreferencesKey("gateway_lan_host")
    private val GATEWAY_RESOLVED_HOST = stringPreferencesKey("gateway_resolved_host")

    val selectionMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GATEWAY_SELECTION_MODE] ?: "AUTO"
    }

    val manualHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GATEWAY_MANUAL_HOST] ?: "https://api-rate-limiter-gateway-qgfy.onrender.com"
    }

    val lanHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GATEWAY_LAN_HOST] ?: ""
    }

    val resolvedHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GATEWAY_RESOLVED_HOST] ?: "https://api-rate-limiter-gateway-qgfy.onrender.com"
    }

    val gatewayHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[GATEWAY_RESOLVED_HOST] ?: prefs[GATEWAY_HOST] ?: "https://api-rate-limiter-gateway-qgfy.onrender.com"
    }

    suspend fun saveGatewayHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[GATEWAY_HOST] = host
            prefs[GATEWAY_RESOLVED_HOST] = host
        }
    }

    suspend fun saveSelectionMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[GATEWAY_SELECTION_MODE] = mode
        }
    }

    suspend fun saveManualHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[GATEWAY_MANUAL_HOST] = host
        }
    }

    suspend fun saveLanHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[GATEWAY_LAN_HOST] = host
        }
    }

    suspend fun saveResolvedHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[GATEWAY_RESOLVED_HOST] = host
            prefs[GATEWAY_HOST] = host
        }
    }

    private fun hostTokenKey(host: String): androidx.datastore.preferences.core.Preferences.Key<String> {
        val clean = host.removePrefix("https://").removePrefix("http://").removePrefix("wss://").removePrefix("ws://")
            .trim().trimEnd('/').replace(":", "_").replace(".", "_").replace("/", "_").lowercase()
        return stringPreferencesKey("jwt_token_$clean")
    }

    suspend fun saveTokenForHost(host: String, token: String, role: String? = null, email: String? = null) {
        val key = hostTokenKey(host)
        context.dataStore.edit { prefs ->
            prefs[key] = TokenEncryptor.encrypt(token)
            prefs[USER_JWT_TOKEN] = TokenEncryptor.encrypt(token)
            if (role != null) {
                val userRole = if (role.equals("ADMIN", ignoreCase = true)) UserRole.ADMIN else UserRole.VIEWER
                prefs[USER_ROLE] = userRole.name
            }
            if (email != null) {
                prefs[USER_EMAIL] = email
                prefs[USER_NAME] = email.substringBefore("@")
            }
        }
    }

    suspend fun getTokenForHost(host: String): String? {
        val key = hostTokenKey(host)
        val prefs = context.dataStore.data.first()
        return prefs[key]?.let { TokenEncryptor.decrypt(it) }
    }

    suspend fun saveSession(
        email: String,
        name: String,
        role: UserRole = UserRole.VIEWER,
        photoUrl: String? = null,
        provider: String = "google",
        jwtToken: String? = null
    ) {
        val currentHost = gatewayHost.first()
        val key = hostTokenKey(currentHost)
        context.dataStore.edit { prefs ->
            prefs[USER_EMAIL] = email
            prefs[USER_NAME] = name
            prefs[USER_ROLE] = role.name
            if (photoUrl != null) {
                prefs[USER_PHOTO_URL] = photoUrl
            } else {
                prefs.remove(USER_PHOTO_URL)
            }
            prefs[AUTH_PROVIDER] = provider
            if (jwtToken != null) {
                val encrypted = TokenEncryptor.encrypt(jwtToken)
                prefs[USER_JWT_TOKEN] = encrypted
                prefs[key] = encrypted
            } else {
                prefs.remove(USER_JWT_TOKEN)
                prefs.remove(key)
            }
        }
    }

    suspend fun save(token: String, role: String, email: String) {
        val userRole = if (role.equals("ADMIN", ignoreCase = true)) UserRole.ADMIN else UserRole.VIEWER
        saveSession(
            email = email,
            name = email.substringBefore("@"),
            role = userRole,
            provider = "google",
            jwtToken = token
        )
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

internal object TokenEncryptor {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "nt14_gateway_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private fun getOrCreateSecretKey(): javax.crypto.SecretKey {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return keyGenerator.generateKey()
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as java.security.KeyStore.SecretKeyEntry).secretKey
    }

    fun encrypt(plainText: String): String {
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
            android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }
    }

    fun decrypt(encryptedString: String): String? {
        return try {
            val combined = android.util.Base64.decode(encryptedString, android.util.Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val secretKey = (keyStore.getEntry(KEY_ALIAS, null) as java.security.KeyStore.SecretKeyEntry).secretKey
                val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
                val spec = javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, combined, 0, GCM_IV_LENGTH)
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
                val plainTextBytes = cipher.doFinal(combined, GCM_IV_LENGTH, combined.size - GCM_IV_LENGTH)
                String(plainTextBytes, Charsets.UTF_8)
            } else {
                String(combined, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            try {
                val decoded = android.util.Base64.decode(encryptedString, android.util.Base64.NO_WRAP)
                String(decoded, Charsets.UTF_8)
            } catch (_: Exception) {
                encryptedString
            }
        }
    }
}

