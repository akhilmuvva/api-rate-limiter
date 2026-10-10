package com.cutm.nt14.ui.endpoints

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cutm.nt14.ui.endpoints.EndpointUiModel
import com.cutm.nt14.domain.model.UserRole
import com.cutm.nt14.ui.components.*

@Composable
fun EndpointScreen(
    viewModel: EndpointViewModel = hiltViewModel()
) {
    val endpoints by viewModel.endpoints.collectAsState()
    val userRole by viewModel.userRole.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var endpointToDelete by remember { mutableStateOf<EndpointUiModel?>(null) }

    val isAdmin = userRole == UserRole.ADMIN

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
                            text = "ROUTING GATEWAY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyPrimary,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "API Endpoints",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextPrimary
                        )
                    }

                    if (isAdmin) {
                        PillActionButton(
                            text = "Add Route",
                            icon = Icons.Default.Add,
                            onClick = { showAddDialog = true }
                        )
                    }
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
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${endpoints.size} REGISTERED GATEWAY ROUTES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextSecondary,
                            letterSpacing = 1.sp
                        )
                        GlassBadge(
                            text = if (isAdmin) "ADMIN ACCESS" else "VIEWER MODE",
                            color = if (isAdmin) PolyPurple else PolyPrimary
                        )
                    }
                }

                if (endpoints.isEmpty()) {
                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White.copy(alpha = 0.90f),
                            elevation = 1.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = PolyTextMuted,
                                    modifier = Modifier.size(42.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No Endpoints Configured",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = PolyTextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "No routing rules registered yet. Tap below to create your first protected gateway route.",
                                    fontSize = 12.sp,
                                    color = PolyTextSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                if (isAdmin) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    GlassButton(
                                        text = "+ Add Endpoint",
                                        accentColor = PolyPrimary,
                                        onClick = { showAddDialog = true }
                                    )
                                }
                            }
                        }
                    }
                }

                items(endpoints) { endpoint ->
                    val (methodColor, methodBg) = when (endpoint.method.uppercase()) {
                        "GET" -> PolySuccess to PolySuccessBg
                        "POST" -> PolyPrimary to PolyPrimaryLight
                        "PUT" -> PolyWarning to PolyWarningBg
                        "DELETE" -> PolyDanger to PolyDangerBg
                        else -> PolyCyan to Color(0xFFF0F9FF)
                    }

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = Color.White.copy(alpha = 0.90f),
                        elevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(methodBg)
                                            .border(BorderStroke(1.dp, methodColor.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = endpoint.method,
                                            color = methodColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = endpoint.name,
                                        color = PolyTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = endpoint.baseUrl,
                                    color = PolyTextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Policy: ${endpoint.limitPerMin} req/min • Burst: ${endpoint.burstLimit} • ${endpoint.action}",
                                    color = PolyTextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            if (isAdmin) {
                                IconButton(
                                    onClick = { endpointToDelete = endpoint },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(PolyDangerBg)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = PolyDanger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(84.dp))
                }
            }
        }

        if (showAddDialog) {
            AddEndpointWhiteDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { name, url, method ->
                    viewModel.addEndpoint(name, url, method)
                    showAddDialog = false
                }
            )
        }

        endpointToDelete?.let { endpoint ->
            AlertDialog(
                onDismissRequest = { endpointToDelete = null },
                containerColor = Color.White,
                title = { Text("Delete Endpoint", color = PolyTextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Are you sure you want to remove ${endpoint.name} (${endpoint.baseUrl}) from gateway routing?",
                        color = PolyTextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteEndpoint(endpoint)
                            endpointToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PolyDanger)
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { endpointToDelete = null }) {
                        Text("Cancel", color = PolyTextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
fun AddEndpointWhiteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("/api/") }
    var method by remember { mutableStateOf("GET") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Add Gateway Route", color = PolyTextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Route Name (e.g. Invoices API)") },
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

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Path (e.g. /api/invoices)") },
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
                    text = "HTTP Method:",
                    color = PolyTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("GET", "POST", "PUT", "DELETE").forEach { m ->
                        val isSel = method == m
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) PolyPrimary else PolyPrimaryLight)
                                .clickable { method = m }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = m,
                                color = if (isSel) Color.White else PolyPrimaryDark,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onConfirm(name.trim(), url.trim(), method)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary)
            ) {
                Text("Add Route", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PolyTextSecondary)
            }
        }
    )
}
