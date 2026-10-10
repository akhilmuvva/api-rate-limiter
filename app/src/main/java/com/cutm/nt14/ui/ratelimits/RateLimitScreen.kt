package com.cutm.nt14.ui.ratelimits

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.cutm.nt14.data.remote.model.RateLimitRuleDto
import com.cutm.nt14.domain.model.UserRole
import com.cutm.nt14.ui.components.*

@Composable
fun RateLimitScreen(
    viewModel: RateLimitViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    val userRole by viewModel.userRole.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var ruleToEdit by remember { mutableStateOf<RateLimitRuleDto?>(null) }
    var ruleToDelete by remember { mutableStateOf<RateLimitRuleDto?>(null) }

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
                            text = "TRAFFIC POLICIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyPrimary,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Rate Limits",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextPrimary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassBadge(
                            text = if (isAdmin) "ADMIN" else "VIEWER",
                            color = if (isAdmin) PolyPurple else PolyPrimary
                        )

                        if (isAdmin) {
                            PillActionButton(
                                text = "Add Rule",
                                icon = Icons.Default.Add,
                                onClick = { showAddDialog = true }
                            )
                        }
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

                item {
                    Text(
                        text = "${rules.size} ACTIVE GATEWAY ENFORCEMENT RULES (TOKEN BUCKET + SLIDING WINDOW)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = PolyTextSecondary,
                        letterSpacing = 0.8.sp
                    )
                }

                if (rules.isEmpty()) {
                    item {
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
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = PolyTextMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No Rules Active",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = PolyTextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Connect to gateway or configure your first rate limit rule.",
                                    fontSize = 12.sp,
                                    color = PolyTextSecondary
                                )
                                if (isAdmin) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    GlassButton(
                                        text = "+ Create Rule",
                                        accentColor = PolyPrimary,
                                        onClick = { showAddDialog = true }
                                    )
                                }
                            }
                        }
                    }
                }

                items(rules, key = { it.endpointId }) { rule ->
                    val isBlock = rule.action.uppercase() == "BLOCK"
                    val (actionCol, actionBg) = if (isBlock) PolyDanger to PolyDangerBg else PolyWarning to PolyWarningBg

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = Color.White.copy(alpha = 0.94f),
                        elevation = 2.dp
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = rule.endpointId,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = PolyTextPrimary,
                                    fontFamily = FontFamily.Monospace
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(actionBg)
                                            .border(BorderStroke(1.dp, actionCol.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = rule.action,
                                            color = actionCol,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (isAdmin) {
                                        IconButton(
                                            onClick = { ruleToEdit = rule },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit",
                                                tint = PolyPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { ruleToDelete = rule },
                                            modifier = Modifier.size(48.dp)
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

                            // Policy Gauges
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFF8FAFC))
                                        .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "SLIDING WINDOW",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PolyTextSecondary,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${rule.limitPerMin} req/min",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = PolyPrimaryDark
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFF8FAFC))
                                        .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "TOKEN BUCKET BURST",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PolyTextSecondary,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${rule.burstLimit} tokens",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = PolyPurple
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            RuleConfigDialog(
                initialRule = null,
                onDismiss = { showAddDialog = false },
                onSave = { rule ->
                    viewModel.addOrUpdateRule(rule)
                    showAddDialog = false
                }
            )
        }

        ruleToEdit?.let { rule ->
            RuleConfigDialog(
                initialRule = rule,
                onDismiss = { ruleToEdit = null },
                onSave = { updated ->
                    viewModel.addOrUpdateRule(updated)
                    ruleToEdit = null
                }
            )
        }

        ruleToDelete?.let { rule ->
            AlertDialog(
                onDismissRequest = { ruleToDelete = null },
                containerColor = Color.White,
                title = { Text("Remove Rate Limit Rule", fontWeight = FontWeight.Bold, color = PolyTextPrimary) },
                text = { Text("Are you sure you want to delete policy enforcement on ${rule.endpointId}?", color = PolyTextSecondary) },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteRule(rule.endpointId)
                            ruleToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PolyDanger)
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { ruleToDelete = null }) {
                        Text("Cancel", color = PolyTextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
fun RuleConfigDialog(
    initialRule: RateLimitRuleDto?,
    onDismiss: () -> Unit,
    onSave: (RateLimitRuleDto) -> Unit
) {
    var endpoint by remember { mutableStateOf(initialRule?.endpointId ?: "/api/") }
    var limitStr by remember { mutableStateOf((initialRule?.limitPerMin ?: 60).toString()) }
    var burstStr by remember { mutableStateOf((initialRule?.burstLimit ?: 15).toString()) }
    var action by remember { mutableStateOf(initialRule?.action ?: "BLOCK") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text(
                text = if (initialRule == null) "Create Policy Rule" else "Edit Policy Rule",
                fontWeight = FontWeight.Bold,
                color = PolyTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint Path (e.g. /api/users)") },
                    enabled = initialRule == null,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PolyTextPrimary,
                        unfocusedTextColor = PolyTextPrimary,
                        focusedBorderColor = PolyPrimary,
                        unfocusedBorderColor = Color(0xFFCBD5E1)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = limitStr,
                    onValueChange = { limitStr = it },
                    label = { Text("Limit per Minute (Sliding Window)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PolyTextPrimary,
                        unfocusedTextColor = PolyTextPrimary,
                        focusedBorderColor = PolyPrimary,
                        unfocusedBorderColor = Color(0xFFCBD5E1)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = burstStr,
                    onValueChange = { burstStr = it },
                    label = { Text("Burst Capacity (Token Bucket)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PolyTextPrimary,
                        unfocusedTextColor = PolyTextPrimary,
                        focusedBorderColor = PolyPrimary,
                        unfocusedBorderColor = Color(0xFFCBD5E1)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Action:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PolyTextSecondary)
                    listOf("BLOCK", "ALERT").forEach { a ->
                        val isSel = action == a
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) PolyPrimary else PolyPrimaryLight)
                                .clickable { action = a }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = a,
                                color = if (isSel) Color.White else PolyPrimaryDark,
                                fontSize = 11.sp,
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
                    val limit = limitStr.toIntOrNull() ?: 60
                    val burst = burstStr.toIntOrNull() ?: 15
                    if (endpoint.isNotBlank()) {
                        onSave(
                            RateLimitRuleDto(
                                endpointId = endpoint.trim(),
                                limitPerMin = limit,
                                burstLimit = burst,
                                action = action
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary)
            ) {
                Text("Save Policy", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = PolyTextSecondary)
            }
        }
    )
}
