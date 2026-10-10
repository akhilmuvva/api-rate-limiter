package com.cutm.nt14.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cutm.nt14.BuildConfig
import com.cutm.nt14.data.remote.model.WhoAmIDto
import kotlinx.coroutines.launch

@Composable
fun GatewayDiagnosticsDialog(
    currentHost: String,
    onDismiss: () -> Unit,
    fetchWhoAmI: suspend () -> WhoAmIDto?,
    fetchSessionToken: suspend () -> String?
) {
    var loading by remember { mutableStateOf(true) }
    var whoAmIData by remember { mutableStateOf<WhoAmIDto?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var copyStatus by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    fun loadDiagnostics() {
        loading = true
        errorMessage = null
        scope.launch {
            try {
                val result = fetchWhoAmI()
                if (result != null) {
                    whoAmIData = result
                } else {
                    errorMessage = "Failed to load /api/whoami. Confirm the gateway is online and your session has ADMIN privileges."
                }
            } catch (e: Exception) {
                errorMessage = "Network error: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDiagnostics()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = PolyPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Gateway Diagnostics",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = PolyTextPrimary
                    )
                }

                IconButton(
                    onClick = { loadDiagnostics() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = PolyPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Live diagnostic reflection from GET /api/whoami. Verifies that Render reverse proxies pass the real client IP and untrusted header spoofing is rejected.",
                    fontSize = 12.sp,
                    color = PolyTextSecondary
                )

                if (copyStatus != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PolyPrimaryLight)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = copyStatus!!,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PolyPrimaryDark
                        )
                    }
                }

                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PolyPrimary, modifier = Modifier.size(32.dp))
                    }
                } else if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PolyDangerBg)
                            .border(BorderStroke(1.dp, PolyDanger.copy(alpha = 0.3f)), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "Inspection Failed",
                                fontWeight = FontWeight.Bold,
                                color = PolyDanger,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = errorMessage!!,
                                color = PolyTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else if (whoAmIData != null) {
                    val data = whoAmIData!!
                    val isPrivateOrInternal = data.resolvedIp.startsWith("10.") ||
                            data.resolvedIp.startsWith("192.168.") ||
                            data.resolvedIp.startsWith("172.") ||
                            data.resolvedIp == "127.0.0.1"

                    // Resolved IP Section
                    DiagnosticField(
                        label = "RESOLVED CLIENT IP",
                        value = data.resolvedIp,
                        highlight = true,
                        badge = if (isPrivateOrInternal) "INTERNAL / LOCAL" else "REAL PUBLIC IP",
                        badgeColor = if (isPrivateOrInternal) PolyWarning else PolySuccess
                    )

                    // Immediate Peer Section
                    DiagnosticField(
                        label = "IMMEDIATE PEER",
                        value = data.immediatePeer,
                        badge = if (data.immediatePeer.startsWith("10.")) "RENDER INGRESS" else null
                    )

                    // CF-Connecting-IP
                    DiagnosticField(
                        label = "CF-CONNECTING-IP",
                        value = data.cfConnectingIp ?: "(Not present / Direct)",
                        isMono = true
                    )

                    // Raw X-Forwarded-For
                    DiagnosticField(
                        label = "RAW X-FORWARDED-FOR CHAIN",
                        value = data.rawXForwardedFor ?: "(None)",
                        isMono = true
                    )

                    // Trusted Proxy Status
                    DiagnosticField(
                        label = "TRUSTED PROXY FLAG",
                        value = if (data.isTrustedProxy) "YES (Trusted ingress)" else "NO (Direct untrusted peer)",
                        badge = if (data.isTrustedProxy) "TRUSTED" else "UNTRUSTED",
                        badgeColor = if (data.isTrustedProxy) PolySuccess else PolyWarning
                    )

                    // Role & Authenticated User
                    DiagnosticField(
                        label = "AUTHENTICATED IDENTITY",
                        value = "${data.authenticatedUser ?: "None"} [${data.role}]",
                        badge = data.role,
                        badgeColor = if (data.role == "ADMIN") PolyPrimary else PolyTextMuted
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Action: Copy Diagnostics
                if (whoAmIData != null) {
                    Button(
                        onClick = {
                            val data = whoAmIData!!
                            val report = """
                                === GATEWAY DIAGNOSTICS ===
                                Target Host: $currentHost
                                Resolved IP: ${data.resolvedIp}
                                Immediate Peer: ${data.immediatePeer}
                                CF-Connecting-IP: ${data.cfConnectingIp ?: "none"}
                                Raw X-Forwarded-For: ${data.rawXForwardedFor ?: "none"}
                                Trusted Proxy: ${data.isTrustedProxy}
                                Authenticated User: ${data.authenticatedUser ?: "none"}
                                Role: ${data.role}
                                Timestamp: ${System.currentTimeMillis()}
                            """.trimIndent()
                            clipboardManager.setText(AnnotatedString(report))
                            copyStatus = "Diagnostics report copied to clipboard!"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Diagnostics", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                // Debug-only Copy Session Token (NEVER in release builds, NEVER logged)
                if (BuildConfig.DEBUG) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val token = fetchSessionToken()
                                if (!token.isNullOrBlank()) {
                                    clipboardManager.setText(AnnotatedString(token))
                                    copyStatus = "Session token copied! (DEBUG ONLY - Never logged)"
                                } else {
                                    copyStatus = "No active session token found."
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PolyWarning),
                        border = BorderStroke(1.dp, PolyWarning),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Session Token (Debug Only)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = PolyTextSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
private fun DiagnosticField(
    label: String,
    value: String,
    highlight: Boolean = false,
    badge: String? = null,
    badgeColor: Color = PolySuccess,
    isMono: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) PolyPrimaryLight.copy(alpha = 0.5f) else Color(0xFFF8FAFC))
            .border(
                BorderStroke(1.dp, if (highlight) PolyPrimary.copy(alpha = 0.25f) else Color(0xFFE2E8F0)),
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (highlight) PolyPrimaryDark else PolyTextSecondary,
                letterSpacing = 0.8.sp
            )
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = if (highlight) 14.sp else 12.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) PolyPrimaryDark else PolyTextPrimary,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default
        )
    }
}
