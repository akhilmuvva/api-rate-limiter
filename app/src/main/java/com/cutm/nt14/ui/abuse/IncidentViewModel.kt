package com.cutm.nt14.ui.abuse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutm.nt14.data.remote.model.ActiveBanDto
import com.cutm.nt14.data.remote.model.ClientInfoDto
import com.cutm.nt14.data.remote.model.IncidentDto
import com.cutm.nt14.data.repository.GatewayRepository
import com.cutm.nt14.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IncidentViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    val userRole: StateFlow<UserRole> = repository.userRole
    val isOffline: StateFlow<Boolean> = repository.isOffline
    val activeBans: StateFlow<List<ActiveBanDto>> = repository.bans
    val incidents: StateFlow<List<IncidentDto>> = repository.incidents
    val clients: StateFlow<List<ClientInfoDto>> = repository.clients

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    fun banClient(clientId: String, durationMinutes: Long = 60, reason: String = "Manual ban") {
        viewModelScope.launch {
            val ok = repository.banClient(clientId, durationMinutes, reason)
            _actionMessage.value = if (ok) "Client $clientId banned for ${durationMinutes}m" else "Failed to ban $clientId"
        }
    }

    fun unbanClient(clientId: String) {
        viewModelScope.launch {
            val ok = repository.unbanClient(clientId)
            _actionMessage.value = if (ok) "Ban lifted for $clientId" else "Failed to lift ban"
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }
}
