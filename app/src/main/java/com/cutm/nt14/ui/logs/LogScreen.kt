package com.cutm.nt14.ui.logs

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
import com.cutm.nt14.data.remote.model.RequestLogDto
import com.cutm.nt14.ui.components.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogScreen(
    viewModel: LogViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val isPaused by viewModel.isStreamingPaused.collectAsState()

    var selectedLog by remember { mutableStateOf<RequestLogDto?>(null) }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "REALTIME TELEMETRY FEED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PolyPrimary,
                                letterSpacing = 1.5.sp
                            )
                            Text(
                                text = "Request Stream",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = PolyTextPrimary
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pause/Resume Stream Button
                            PillActionButton(
                                text = if (isPaused) "Resume" else "Pause",
                                icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Close,
                                onClick = { viewModel.toggleStreamingPause() }
                            )

                            if (logs.isNotEmpty()) {
                                IconButton(
                                    onClick = { viewModel.clearLogs() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(PolyDangerBg)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear Logs",
                                        tint = PolyDanger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Search Field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search by path, client IP, or request ID...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = PolyTextMuted, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = PolyTextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PolyTextPrimary,
                            unfocusedTextColor = PolyTextPrimary,
                            focusedBorderColor = PolyPrimary,
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Filter Tiles Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LogStatusFilter.values().forEach { filter ->
                            val isSel = statusFilter == filter
                            val (label, icon) = when (filter) {
                                LogStatusFilter.ALL -> "All" to Icons.Default.Check
                                LogStatusFilter.OK_200 -> "2xx OK" to Icons.Default.CheckCircle
                                LogStatusFilter.BLOCKED_429 -> "429 Block" to Icons.Default.Warning
                                LogStatusFilter.ERROR_5XX -> "5xx Err" to Icons.Default.Warning
                            }
                            val count = when (filter) {
                                LogStatusFilter.ALL -> logs.size
                                LogStatusFilter.OK_200 -> logs.count { it.status in 200..299 }
                                LogStatusFilter.BLOCKED_429 -> logs.count { it.status == 429 }
                                LogStatusFilter.ERROR_5XX -> logs.count { it.status >= 500 }
                            }
                            FilterTile(
                                label = label,
                                icon = icon,
                                selected = isSel,
                                badge = count.toString(),
                                onClick = { viewModel.setStatusFilter(filter) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        ) { padding ->
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = Color.White.copy(alpha = 0.90f),
                        elevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = PolyTextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Matching Traffic Logs",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = PolyTextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Awaiting inbound requests. Trigger simulation or wait for background traffic to stream.",
                                fontSize = 12.sp,
                                color = PolyTextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            GlassButton(
                                text = "Refresh Telemetry",
                                accentColor = PolyPrimary,
                                onClick = { viewModel.refresh() }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
                    )
                ) {
                    items(logs, key = { it.id }) { log ->
                        val is200 = log.status in 200..299
                        val is429 = log.status == 429
                        val (statusCol, bgCol) = when {
                            is200 -> PolySuccess to PolySuccessBg
                            is429 -> PolyWarning to PolyWarningBg
                            else -> PolyDanger to PolyDangerBg
                        }

                        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                        val formattedTime = timeFormat.format(Date(log.timestamp))

                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White.copy(alpha = 0.94f),
                            elevation = 1.dp,
                            onClick = { selectedLog = log }
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
                                                .background(bgCol)
                                                .border(BorderStroke(1.dp, statusCol.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "${log.status} ${log.method}",
                                                color = statusCol,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Text(
                                            text = log.path,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = PolyTextPrimary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "IP: ${log.clientId}",
                                            fontSize = 11.sp,
                                            color = PolyTextSecondary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "•",
                                            fontSize = 10.sp,
                                            color = PolyTextMuted
                                        )
                                        Text(
                                            text = "${log.latencyMs}ms",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = PolyTextSecondary,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "•",
                                            fontSize = 10.sp,
                                            color = PolyTextMuted
                                        )
                                        Text(
                                            text = log.decision,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (log.decision == "allowed") PolySuccess else PolyDanger
                                        )
                                    }
                                }

                                Text(
                                    text = formattedTime,
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

        selectedLog?.let { log ->
            AlertDialog(
                onDismissRequest = { selectedLog = null },
                containerColor = Color.White,
                title = {
                    Text(
                        text = "Request Inspection",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = PolyTextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailItem("Request ID", log.id)
                        DetailItem("Endpoint", log.path)
                        DetailItem("Method", log.method)
                        DetailItem("Client IP", log.clientId)
                        DetailItem("HTTP Status", log.status.toString())
                        DetailItem("Latency", "${log.latencyMs} ms")
                        DetailItem("Policy Decision", log.decision)
                        DetailItem("Timestamp", Date(log.timestamp).toString())
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { selectedLog = null },
                        colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary)
                    ) {
                        Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "$label:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PolyTextSecondary)
        Text(text = value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = PolyTextPrimary)
    }
}
