package com.cutm.nt14.ui.ratelimits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutm.nt14.data.remote.model.RateLimitRuleDto
import com.cutm.nt14.data.repository.GatewayRepository
import com.cutm.nt14.domain.model.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RateLimitViewModel @Inject constructor(
    private val repository: GatewayRepository
) : ViewModel() {

    val userRole: StateFlow<UserRole> = repository.userRole
    val rules: StateFlow<List<RateLimitRuleDto>> = repository.rules

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    fun addOrUpdateRule(rule: RateLimitRuleDto) {
        viewModelScope.launch {
            val ok = repository.addRule(rule)
            _actionMessage.value = if (ok) "Rule updated for ${rule.endpointId}" else "Failed to update rule"
        }
    }

    fun deleteRule(endpointId: String) {
        viewModelScope.launch {
            val ok = repository.deleteRule(endpointId)
            _actionMessage.value = if (ok) "Rule removed for $endpointId" else "Failed to remove rule"
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
