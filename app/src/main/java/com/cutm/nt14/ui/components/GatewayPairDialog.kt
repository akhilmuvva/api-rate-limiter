package com.cutm.nt14.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GatewayPairDialog(
    currentHost: String,
    onDismiss: () -> Unit,
    onPair: (String) -> Unit,
    onFetchFromServer: (String) -> Unit
) {
    var pairInput by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = Nt14Ui.Accent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Pair Gateway via QR / Token",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Nt14Ui.TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Paste the pairing URI / token printed by your gateway console on startup, or auto-fetch a ticket from the active gateway.",
                    fontSize = 12.sp,
                    color = Nt14Ui.TextMuted,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = pairInput,
                    onValueChange = { pairInput = it },
                    label = { Text("Pairing Token, JSON, or URI") },
                    placeholder = { Text("nt14-pair://pair?host=...&ticket=...") },
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Nt14Ui.TextPrimary,
                        unfocusedTextColor = Nt14Ui.TextPrimary,
                        focusedBorderColor = Nt14Ui.Accent,
                        unfocusedBorderColor = Nt14Ui.Outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Quick Presets:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Nt14Ui.TextPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterTile(
                        label = "Local USB",
                        icon = Icons.Default.Home,
                        selected = selectedPreset == "usb",
                        badge = "127.0.0.1",
                        onClick = {
                            selectedPreset = "usb"
                            pairInput = "127.0.0.1:8000"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTile(
                        label = "Emulator",
                        icon = Icons.Default.Info,
                        selected = selectedPreset == "emu",
                        badge = "10.0.2.2",
                        onClick = {
                            selectedPreset = "emu"
                            pairInput = "10.0.2.2:8000"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterTile(
                        label = "LAN Wi-Fi",
                        icon = Icons.Default.Settings,
                        selected = selectedPreset == "lan",
                        badge = "LAN IP",
                        onClick = {
                            selectedPreset = "lan"
                            pairInput = "192.168.1.100:8000"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTile(
                        label = "Cloud",
                        icon = Icons.Default.Star,
                        selected = selectedPreset == "cloud",
                        badge = "Render",
                        onClick = {
                            selectedPreset = "cloud"
                            pairInput = "https://nt14-gateway.onrender.com"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Auto-fetch token button
                PillActionButton(
                    text = "Auto-Fetch Token (/api/auth/pair)",
                    icon = Icons.Default.Refresh,
                    onClick = {
                        val host = if (pairInput.isNotBlank()) pairInput.trim() else currentHost
                        onFetchFromServer(host)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            PillActionButton(
                text = "Pair & Connect",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = {
                    if (pairInput.isNotBlank()) {
                        onPair(pairInput.trim())
                    }
                    onDismiss()
                }
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = "Cancel",
                    color = Nt14Ui.TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}
