package com.cutm.nt14.ui.endpoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutm.nt14.data.remote.model.RateLimitRuleDto
import com.cutm.nt14.data.repository.GatewayRepository
import com.cutm.nt14.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EndpointUiModel(
    val endpointId: String,
    val name: String,
    val baseUrl: String,
    val method: String,
    val status: String,
    val limitPerMin: Int,
    val burstLimit: Int,
    val action: String
)

@HiltViewModel
class EndpointViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    val userRole: StateFlow<UserRole> = repository.userRole

    val endpoints: StateFlow<List<EndpointUiModel>> = repository.rules.map { rules ->
        rules.map { r ->
            val simpleName = r.endpointId.substringAfterLast("/").ifBlank { "Root" }
                .replaceFirstChar { it.uppercase() } + " API"
            EndpointUiModel(
                endpointId = r.endpointId,
                name = simpleName,
                baseUrl = r.endpointId,
                method = "GET",
                status = "PROTECTED",
                limitPerMin = r.limitPerMin,
                burstLimit = r.burstLimit,
                action = r.action
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    fun addEndpoint(name: String, path: String, method: String) {
        viewModelScope.launch {
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            val rule = RateLimitRuleDto(
                endpointId = cleanPath,
                limitPerMin = 60,
                burstLimit = 15,
                action = "BLOCK"
            )
            val ok = repository.addRule(rule)
            _actionMessage.value = if (ok) "Route registered: $cleanPath" else "Failed to register route"
        }
    }

    fun deleteEndpoint(endpoint: EndpointUiModel) {
        viewModelScope.launch {
            val ok = repository.deleteRule(endpoint.baseUrl)
            _actionMessage.value = if (ok) "Route deleted: ${endpoint.baseUrl}" else "Failed to delete route"
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
