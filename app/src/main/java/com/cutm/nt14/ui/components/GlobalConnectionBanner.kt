package com.cutm.nt14.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutm.nt14.data.remote.EndpointType
import com.cutm.nt14.data.remote.GatewayConnectionState
import com.cutm.nt14.data.remote.ResolverState
import com.cutm.nt14.data.repository.GatewayRepository
import com.cutm.nt14.domain.model.UserRole
import kotlinx.coroutines.launch

@Composable
fun GlobalConnectionBanner(
    repository: GatewayRepository,
    modifier: Modifier = Modifier
) {
    val connectionState by repository.connectionState.collectAsState()
    val resolverState by repository.resolverState.collectAsState()
    val endpointType by repository.endpointType.collectAsState()
    val host by repository.connectedHost.collectAsState()
    val userRole by repository.userRole.collectAsState()
    var showHostDialog by remember { mutableStateOf(false) }

    val (bannerBg, borderCol, iconTint, statusText, subText, showRetry) = when (val resState = resolverState) {
        is ResolverState.WakingCloud -> {
            Hexa(
                Color(0xFFEFF6FF),
                Color(0xFFBFDBFE),
                PolyPrimary,
                "Waking Cloud Gateway...",
                "Cold-start health probe in flight (~30-60s) • ${resState.url}",
                true
            )
        }
        is ResolverState.Probing -> {
            Hexa(
                Color(0xFFFFFBEB),
                Color(0xFFFDE68A),
                PolyWarning,
                "Probing Endpoints...",
                "Testing candidate target • ${resState.candidate}",
                true
            )
        }
        is ResolverState.Unreachable -> {
            Hexa(
                Color(0xFFFEF2F2),
                Color(0xFFFECACA),
                PolyDanger,
                "Gateways Unreachable",
                resState.lastError,
                true
            )
        }
        is ResolverState.Connected -> {
            when (val state = connectionState) {
                is GatewayConnectionState.WakingServer -> Hexa(
                    Color(0xFFEFF6FF),
                    Color(0xFFBFDBFE),
                    PolyPrimary,
                    "Waking Gateway Instance...",
                    "Cold-start probe in flight to $host",
                    true
                )
                is GatewayConnectionState.Connecting -> Hexa(
                    Color(0xFFFFFBEB),
                    Color(0xFFFDE68A),
                    PolyWarning,
                    "Connecting to Gateway...",
                    "Establishing WebSocket stream at $host",
                    true
                )
                is GatewayConnectionState.Failed -> Hexa(
                    Color(0xFFFEF2F2),
                    Color(0xFFFECACA),
                    PolyDanger,
                    "Gateway Offline • ${state.message}",
                    "Target: $host • Data may be stale or unverified",
                    true
                )
                is GatewayConnectionState.Connected -> Hexa(
                    Color(0xFFF0FDF4),
                    Color(0xFFBBF7D0),
                    PolySuccess,
                    "Connected to Gateway",
                    host,
                    false
                )
                is GatewayConnectionState.Idle -> Hexa(
                    Color(0xFFF8FAFC),
                    Color(0xFFE2E8F0),
                    PolyTextSecondary,
                    "Gateway Disconnected",
                    host,
                    true
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bannerBg)
            .border(BorderStroke(1.dp, borderCol), RoundedCornerShape(12.dp))
            .clickable { showHostDialog = true }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // LOCAL / CLOUD Chip
                Surface(
                    color = if (endpointType == EndpointType.LOCAL) Color(0xFFDCFCE7) else Color(0xFFE0E7FF),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (endpointType == EndpointType.LOCAL) Color(0xFF86EFAC) else Color(0xFFA5B4FC)
                    )
                ) {
                    Text(
                        text = if (endpointType == EndpointType.LOCAL) "LOCAL" else "CLOUD",
                        color = if (endpointType == EndpointType.LOCAL) Color(0xFF166534) else Color(0xFF3730A3),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Column {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PolyTextPrimary
                    )
                    Text(
                        text = subText,
                        fontSize = 10.sp,
                        color = PolyTextSecondary,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showRetry) {
                    PillActionButton(
                        text = "Retry",
                        icon = Icons.Default.Refresh,
                        onClick = { repository.reResolve() }
                    )
                }

                PillActionButton(
                    text = "Host",
                    icon = Icons.Default.Settings,
                    onClick = { showHostDialog = true }
                )
            }
        }
    }

    if (showHostDialog) {
        GatewayHostConfigDialog(
            currentHost = host,
            onDismiss = { showHostDialog = false },
            onSave = { newHost ->
                repository.updateHost(newHost)
                showHostDialog = false
            },
            isAdmin = userRole == UserRole.ADMIN,
            repository = repository
        )
    }
}

private data class Hexa<A, B, C, D, E, F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F
)

@Composable
fun GatewayHostConfigDialog(
    currentHost: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    isAdmin: Boolean = false,
    repository: GatewayRepository? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedMode by remember { mutableStateOf("AUTO") }
    var lanInput by remember { mutableStateOf("") }
    var manualInput by remember { mutableStateOf(currentHost) }
    var showDiagnostics by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        repository?.selectionMode?.collect { mode ->
            selectedMode = mode
        }
    }
    LaunchedEffect(repository) {
        repository?.lanHost?.collect { lan ->
            lanInput = lan
        }
    }
    LaunchedEffect(repository) {
        repository?.manualHost?.collect { manual ->
            if (manual.isNotBlank()) manualInput = manual
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text(
                text = "Gateway Connectivity Setup",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = PolyTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "The mobile client connects to the NT14 Gateway to stream live rate limits and telemetry.",
                    fontSize = 12.sp,
                    color = PolyTextSecondary
                )

                // Selection Mode: Auto vs Manual
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterTile(
                        label = "Auto (Recommended)",
                        icon = Icons.Default.CheckCircle,
                        selected = selectedMode == "AUTO",
                        badge = "Auto-probe",
                        onClick = { selectedMode = "AUTO" },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTile(
                        label = "Manual Override",
                        icon = Icons.Default.Settings,
                        selected = selectedMode == "MANUAL",
                        badge = "Custom",
                        onClick = { selectedMode = "MANUAL" },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (selectedMode == "AUTO") {
                    Surface(
                        color = Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Probe Priority:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PolyTextPrimary
                            )
                            Text(
                                text = "1. Local USB (127.0.0.1:8000 via adb reverse)\n2. Emulator (10.0.2.2:8000 on emulators only)\n3. Saved LAN Host (if specified below)\n4. Cloud Gateway (https://api-rate-limiter-gateway-qgfy.onrender.com)",
                                fontSize = 10.sp,
                                color = PolyTextSecondary,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    OutlinedTextField(
                        value = lanInput,
                        onValueChange = { lanInput = it },
                        label = { Text("Saved LAN Host (optional)") },
                        placeholder = { Text("e.g. 192.168.1.100:8000") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PolyTextPrimary,
                            unfocusedTextColor = PolyTextPrimary,
                            focusedBorderColor = PolyPrimary,
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedLabelColor = PolyPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        label = { Text("Gateway Base URL / Host:Port") },
                        placeholder = { Text("https://api-rate-limiter-gateway-qgfy.onrender.com") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PolyTextPrimary,
                            unfocusedTextColor = PolyTextPrimary,
                            focusedBorderColor = PolyPrimary,
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedLabelColor = PolyPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isAdmin && repository != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showDiagnostics = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PolyPrimary),
                        border = BorderStroke(1.dp, PolyPrimary)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Gateway Diagnostics (Admin)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        if (repository != null) {
                            if (selectedMode == "AUTO") {
                                repository.setLanHost(lanInput.trim())
                                repository.setAutoMode()
                            } else {
                                repository.setManualHost(manualInput.trim())
                            }
                        } else {
                            onSave(if (selectedMode == "AUTO") lanInput.trim() else manualInput.trim())
                        }
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary)
            ) {
                Text(if (selectedMode == "AUTO") "Apply Auto" else "Connect", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PolyTextSecondary)
            }
        }
    )

    if (showDiagnostics && repository != null) {
        GatewayDiagnosticsDialog(
            currentHost = currentHost,
            onDismiss = { showDiagnostics = false },
            fetchWhoAmI = { repository.fetchWhoAmI() },
            fetchSessionToken = { repository.getSessionToken() }
        )
    }
}
