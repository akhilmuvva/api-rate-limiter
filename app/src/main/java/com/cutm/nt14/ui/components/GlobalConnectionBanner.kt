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
import com.cutm.nt14.data.remote.GatewayConnectionState
import com.cutm.nt14.data.repository.GatewayRepository

@Composable
fun GlobalConnectionBanner(
    repository: GatewayRepository,
    modifier: Modifier = Modifier
) {
    val connectionState by repository.connectionState.collectAsState()
    val host by repository.connectedHost.collectAsState()
    var showHostDialog by remember { mutableStateOf(false) }

    val isConnected = connectionState is GatewayConnectionState.Connected

    AnimatedVisibility(
        visible = !isConnected,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val (bannerBg, borderCol, iconTint, statusText, subText) = when (val state = connectionState) {
            is GatewayConnectionState.WakingServer -> {
                Quint(
                    Color(0xFFEFF6FF),
                    Color(0xFFBFDBFE),
                    PolyPrimary,
                    "Waking Gateway Instance...",
                    "Cold-start health probe in flight to $host"
                )
            }
            is GatewayConnectionState.Connecting -> {
                Quint(
                    Color(0xFFFFFBEB),
                    Color(0xFFFDE68A),
                    PolyWarning,
                    "Connecting to Gateway...",
                    "Establishing WebSocket stream at $host"
                )
            }
            is GatewayConnectionState.Failed -> {
                Quint(
                    Color(0xFFFEF2F2),
                    Color(0xFFFECACA),
                    PolyDanger,
                    "Gateway Offline • ${state.message}",
                    "Target: $host • Data may be stale or unverified"
                )
            }
            is GatewayConnectionState.Idle -> {
                Quint(
                    Color(0xFFF8FAFC),
                    Color(0xFFE2E8F0),
                    PolyTextSecondary,
                    "Gateway Disconnected",
                    "Target: $host"
                )
            }
            else -> Quint(Color.White, Color.Transparent, Color.Transparent, "", "")
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
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (connectionState is GatewayConnectionState.Failed) Icons.Default.Warning else Icons.Default.Refresh,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(14.dp)
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
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillActionButton(
                        text = "Retry",
                        icon = Icons.Default.Refresh,
                        onClick = { repository.reconnect() }
                    )

                    PillActionButton(
                        text = "Host",
                        icon = Icons.Default.Settings,
                        onClick = { showHostDialog = true }
                    )
                }
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
            }
        )
    }
}

private data class Quint<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

@Composable
fun GatewayHostConfigDialog(
    currentHost: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var hostInput by remember { mutableStateOf(currentHost) }

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
                    text = "Configure the Gateway Server endpoint. The mobile client communicates exclusively with the Rate Limiting Gateway, which proxies upstream protocols.",
                    fontSize = 12.sp,
                    color = PolyTextSecondary
                )

                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("Gateway Base URL / Host:Port") },
                    placeholder = { Text("127.0.0.1:8000") },
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

                Text(
                    text = "Select Preset Target:",
                    color = PolyTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PresetChip("Local USB (127.0.0.1:8000)") {
                            hostInput = "127.0.0.1:8000"
                        }
                        PresetChip("Emulator (10.0.2.2:8000)") {
                            hostInput = "10.0.2.2:8000"
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PresetChip("Wi-Fi / LAN") {
                            hostInput = "192.168.1.100:8000"
                        }
                        PresetChip("Cloud Gateway") {
                            hostInput = "https://nt14-gateway.onrender.com"
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(hostInput.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary)
            ) {
                Text("Connect", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PolyTextSecondary)
            }
        }
    )
}

@Composable
fun PresetChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PolyPrimaryLight)
            .border(BorderStroke(1.dp, PolyPrimary.copy(alpha = 0.25f)), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = PolyPrimaryDark,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
