package com.cutm.nt14.ui.login

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutm.nt14.data.local.SessionManager
import com.cutm.nt14.data.remote.GoogleAuthManager
import com.cutm.nt14.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authManager: GoogleAuthManager,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState

    fun signInWithCredentialManager(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            authManager.signIn(activity).fold(
                onSuccess = {
                    val email = sessionManager.userEmail.first() ?: ""
                    _uiState.value = LoginUiState.Success(email, email.substringBefore("@"))
                },
                onFailure = { e ->
                    if (e is androidx.credentials.exceptions.GetCredentialCancellationException) {
                        _uiState.value = LoginUiState.Idle
                    } else {
                        _uiState.value = LoginUiState.Error(e.message ?: "Google authentication failed")
                    }
                }
            )
        }
    }

    fun onSignInCancelled() {
        _uiState.value = LoginUiState.Idle
    }

    fun signInAsGuest() {
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading
            val guestEmail = "guest@cutm.nt14.com"
            val guestName = "Guest Viewer"
            sessionManager.saveSession(
                email = guestEmail,
                name = guestName,
                role = UserRole.VIEWER,
                provider = "guest",
                jwtToken = null
            )
            _uiState.value = LoginUiState.Success(guestEmail, guestName)
        }
    }

    fun signOut(activity: Activity?) {
        viewModelScope.launch {
            authManager.signOut(activity)
            _uiState.value = LoginUiState.Idle
        }
    }
}
