package com.cutm.nt14.ui.abuse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cutm.nt14.data.remote.model.ActiveBanDto
import com.cutm.nt14.data.remote.model.ClientInfoDto
import com.cutm.nt14.domain.model.UserRole
import com.cutm.nt14.ui.components.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

@Composable
fun IncidentScreen(
    viewModel: IncidentViewModel = hiltViewModel()
) {
    val activeBans by viewModel.activeBans.collectAsState()
    val incidents by viewModel.incidents.collectAsState()
    val clients by viewModel.clients.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val userRole by viewModel.userRole.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    var selectedTab by remember { mutableStateOf("clients") } // "clients", "bans", "incidents"
    var clientToBan by remember { mutableStateOf<ClientInfoDto?>(null) }
    var clientToUnban by remember { mutableStateOf<String?>(null) }
    var banDurationMinutes by remember { mutableStateOf(60L) }
    val isAdmin = userRole == UserRole.ADMIN

    val sortedClients = remember(clients) {
        clients.sortedByDescending { it.requestsPerMin }
    }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SECURITY CENTER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyDanger,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Threat Incidents",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextPrimary
                        )
                    }

                    GlassBadge(
                        text = if (isOffline) "OFFLINE (UNKNOWN)" else if (activeBans.isNotEmpty()) "${activeBans.size} ACTIVE BANS" else "ALL CLEAR",
                        color = if (isOffline) PolyWarning else if (activeBans.isNotEmpty()) PolyDanger else PolySuccess
                    )
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp
                )
            ) {
                if (!actionMessage.isNullOrBlank()) {
                    item {
                        GlassCard(backgroundColor = PolyPrimaryLight) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = actionMessage!!,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PolyPrimaryDark
                                )
                                TextButton(onClick = { viewModel.clearActionMessage() }) {
                                    Text("Dismiss", color = PolyPrimary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // Status Header Card
                item {
                    val statusText = if (isOffline) {
                        "Gateway Disconnected • Security integrity cannot be confirmed"
                    } else if (activeBans.isNotEmpty()) {
                        "${activeBans.size} Malicious clients currently blacklisted by Anomaly Engine"
                    } else {
                        "No anomalous brute force or DDoS floods currently detected"
                    }

                    GlassCard(
                        backgroundColor = if (isOffline) Color(0xFFFFFBEB) else if (activeBans.isNotEmpty()) PolyDangerBg else PolySuccessBg,
                        borderBrush = GlassBorderSubtle
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isOffline) PolyWarning else if (activeBans.isNotEmpty()) PolyDanger else PolySuccess),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (activeBans.isNotEmpty()) Icons.Default.Warning else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (isOffline) "STATUS: UNKNOWN (OFFLINE)" else if (activeBans.isNotEmpty()) "THREAT ENFORCEMENT ACTIVE" else "ALL PROTOCOLS SECURE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOffline) PolyWarning else if (activeBans.isNotEmpty()) PolyDanger else PolySuccess,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = statusText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PolyTextPrimary
                                )
                            }
                        }
                    }
                }

                // Segmented Tabs using FilterTile
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilterTile(
                            label = "Clients",
                            icon = Icons.Default.Person,
                            selected = selectedTab == "clients",
                            badge = "${clients.size}",
                            onClick = { selectedTab = "clients" }
                        )
                        FilterTile(
                            label = "Bans",
                            icon = Icons.Default.Lock,
                            selected = selectedTab == "bans",
                            badge = "${activeBans.size}",
                            onClick = { selectedTab = "bans" }
                        )
                        FilterTile(
                            label = "Incidents",
                            icon = Icons.Default.Warning,
                            selected = selectedTab == "incidents",
                            badge = "${incidents.size}",
                            onClick = { selectedTab = "incidents" }
                        )
                    }
                }

                // Content based on selected tab
                when (selectedTab) {
                    "clients" -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CLIENT MONITORING (${sortedClients.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PolyTextSecondary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = if (isAdmin) "Sorted by req/min" else "Masked IPs (Viewer)",
                                    fontSize = 10.sp,
                                    color = PolyTextMuted
                                )
                            }
                        }

                        if (sortedClients.isEmpty()) {
                            item {
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = Color.White.copy(alpha = 0.90f),
                                    elevation = 1.dp
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "No Active Clients",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = PolyTextPrimary
                                        )
                                        Text(
                                            text = "No network traffic observed in the last hour.",
                                            fontSize = 11.sp,
                                            color = PolyTextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        items(sortedClients, key = { it.id }) { client ->
                            val displayId = if (isAdmin) client.id else client.maskedId
                            val isBanned = client.status == "banned" || activeBans.any { it.clientId == client.id }
                            val isThrottled = client.status == "throttled"
                            val statusText = when {
                                isBanned -> "BANNED"
                                isThrottled -> "THROTTLED"
                                else -> "ACTIVE"
                            }
                            val statusColor = when {
                                isBanned -> PolyDanger
                                isThrottled -> PolyWarning
                                else -> PolySuccess
                            }
                            val statusBg = when {
                                isBanned -> PolyDangerBg
                                isThrottled -> PolyWarningBg
                                else -> PolySuccessBg
                            }

                            val lastSeenSec = if (client.lastSeen > 0) {
                                ((System.currentTimeMillis() - client.lastSeen) / 1000).coerceAtLeast(0)
                            } else null
                            val lastSeenText = when {
                                lastSeenSec == null -> "Never"
                                lastSeenSec < 60 -> "${lastSeenSec}s ago"
                                lastSeenSec < 3600 -> "${lastSeenSec / 60}m ago"
                                else -> "${lastSeenSec / 3600}h ago"
                            }

                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = isAdmin) {
                                        if (isBanned) {
                                            clientToUnban = client.id
                                        } else {
                                            clientToBan = client
                                        }
                                    },
                                backgroundColor = Color.White.copy(alpha = 0.94f),
                                elevation = 1.dp
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f, fill = false)
                                        ) {
                                            Text(
                                                text = displayId,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = PolyTextPrimary,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (client.isDemo) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color(0xFFEDE9FE))
                                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "DEMO",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF7C3AED)
                                                    )
                                                }
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(statusBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = statusText,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = statusColor
                                                )
                                            }

                                            if (isAdmin) {
                                                if (isBanned) {
                                                    PillActionButton(
                                                        text = "Unban",
                                                        icon = Icons.Default.Check,
                                                        onClick = { clientToUnban = client.id }
                                                    )
                                                } else {
                                                    PillActionButton(
                                                        text = "Ban",
                                                        icon = Icons.Default.Close,
                                                        onClick = { clientToBan = client }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${String.format(Locale.US, "%.1f", client.requestsPerMin)} req/min",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Nt14Ui.Accent
                                        )
                                        Text(
                                            text = "${client.totalRequests} reqs (${client.throttledCount} blocked)",
                                            fontSize = 11.sp,
                                            color = PolyTextSecondary
                                        )
                                        Text(
                                            text = "Seen: $lastSeenText",
                                            fontSize = 11.sp,
                                            color = PolyTextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "bans" -> {
                        item {
                            Text(
                                text = "ACTIVE CLIENT BANS (${activeBans.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PolyTextSecondary,
                                letterSpacing = 1.sp
                            )
                        }

                        if (activeBans.isEmpty()) {
                            item {
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = Color.White.copy(alpha = 0.90f),
                                    elevation = 1.dp
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "No Active Bans",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = PolyTextPrimary
                                        )
                                        Text(
                                            text = "No clients are banned by the gateway anomaly detector.",
                                            fontSize = 11.sp,
                                            color = PolyTextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        items(activeBans, key = { it.clientId }) { ban ->
                            val now = System.currentTimeMillis()
                            val remainingSec = ceil((ban.expiresAt - now) / 1000.0).toLong().coerceAtLeast(0L)

                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Color.White.copy(alpha = 0.94f),
                                elevation = 2.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = ban.clientId,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = PolyDanger,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(PolyDangerBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${remainingSec}s left",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PolyDanger
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = ban.reason,
                                            fontSize = 12.sp,
                                            color = PolyTextSecondary
                                        )
                                    }

                                    if (isAdmin) {
                                        PillActionButton(
                                            text = "Unban",
                                            icon = Icons.Default.Check,
                                            onClick = { clientToUnban = ban.clientId }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "incidents" -> {
                        item {
                            Text(
                                text = "SECURITY INCIDENT FEED (${incidents.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PolyTextSecondary,
                                letterSpacing = 1.sp
                            )
                        }

                        if (incidents.isEmpty()) {
                            item {
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = Color.White.copy(alpha = 0.90f),
                                    elevation = 1.dp
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Incident Feed Clean",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = PolyTextPrimary
                                        )
                                        Text(
                                            text = "No severe anomalies or DDoS spikes logged.",
                                            fontSize = 11.sp,
                                            color = PolyTextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        items(incidents, key = { it.id }) { inc ->
                            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                            val formatted = timeFormat.format(Date(inc.timestamp))

                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Color.White.copy(alpha = 0.94f),
                                elevation = 1.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (inc.severity == "CRITICAL") PolyDangerBg else PolyWarningBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = inc.severity,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (inc.severity == "CRITICAL") PolyDanger else PolyWarning
                                                )
                                            }
                                            Text(
                                                text = inc.type,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = PolyTextPrimary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = inc.detail,
                                            fontSize = 11.sp,
                                            color = PolyTextSecondary
                                        )
                                    }

                                    Text(
                                        text = formatted,
                                        fontSize = 10.sp,
                                        color = PolyTextMuted,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dialog for Banning Client
        clientToBan?.let { client ->
            AlertDialog(
                onDismissRequest = { clientToBan = null },
                containerColor = Color.White,
                title = { Text("Ban Client", fontWeight = FontWeight.Bold, color = PolyTextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Manually blacklist client ${client.id} from accessing the gateway.",
                            color = PolyTextSecondary,
                            fontSize = 13.sp
                        )
                        Text("Duration:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = PolyTextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15L, 60L, 1440L).forEach { mins ->
                                FilterChip(
                                    selected = banDurationMinutes == mins,
                                    onClick = { banDurationMinutes = mins },
                                    label = { Text(if (mins == 1440L) "24 hours" else "${mins}m") }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.banClient(client.id, banDurationMinutes, "Manual admin ban")
                            clientToBan = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PolyDanger)
                    ) {
                        Text("Ban Client", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { clientToBan = null }) {
                        Text("Cancel", color = PolyTextSecondary)
                    }
                }
            )
        }

        // Dialog for Unbanning Client
        clientToUnban?.let { clientId ->
            AlertDialog(
                onDismissRequest = { clientToUnban = null },
                containerColor = Color.White,
                title = { Text("Lift Ban", fontWeight = FontWeight.Bold, color = PolyTextPrimary) },
                text = { Text("Are you sure you want to lift the active ban for client $clientId?", color = PolyTextSecondary) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.unbanClient(clientId)
                            clientToUnban = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary)
                    ) {
                        Text("Unban", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { clientToUnban = null }) {
                        Text("Cancel", color = PolyTextSecondary)
                    }
                }
            )
        }
    }
}
