package com.cutm.nt14.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cutm.nt14.data.local.daos.RequestLogDao
import com.cutm.nt14.data.remote.model.RequestLogDto
import com.cutm.nt14.data.repository.GatewayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LogStatusFilter {
    ALL, OK_200, BLOCKED_429, ERROR_5XX
}

@HiltViewModel
class LogViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val logDao: RequestLogDao
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusFilter = MutableStateFlow(LogStatusFilter.ALL)
    val statusFilter: StateFlow<LogStatusFilter> = _statusFilter.asStateFlow()

    private val _isStreamingPaused = MutableStateFlow(false)
    val isStreamingPaused: StateFlow<Boolean> = _isStreamingPaused.asStateFlow()

    private val _pausedSnapshot = MutableStateFlow<List<RequestLogDto>>(emptyList())

    val logs: StateFlow<List<RequestLogDto>> = combine(
        repository.logs,
        _searchQuery,
        _statusFilter,
        _isStreamingPaused
    ) { liveLogs, query, filter, paused ->
        val source = if (paused && _pausedSnapshot.value.isNotEmpty()) _pausedSnapshot.value else liveLogs
        source.filter { log ->
            val matchesQuery = query.isBlank() ||
                    log.path.contains(query, ignoreCase = true) ||
                    log.clientId.contains(query, ignoreCase = true) ||
                    log.id.contains(query, ignoreCase = true)

            val matchesFilter = when (filter) {
                LogStatusFilter.ALL -> true
                LogStatusFilter.OK_200 -> log.status in 200..299
                LogStatusFilter.BLOCKED_429 -> log.status == 429
                LogStatusFilter.ERROR_5XX -> log.status >= 500
            }

            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setStatusFilter(filter: LogStatusFilter) {
        _statusFilter.value = filter
    }

    fun toggleStreamingPause() {
        val next = !_isStreamingPaused.value
        _isStreamingPaused.value = next
        if (next) {
            _pausedSnapshot.value = repository.logs.value
        } else {
            _pausedSnapshot.value = emptyList()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logDao.clearAllLogs()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }
}
