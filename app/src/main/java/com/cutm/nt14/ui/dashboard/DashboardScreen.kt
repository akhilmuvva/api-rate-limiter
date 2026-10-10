package com.cutm.nt14.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cutm.nt14.data.remote.GatewayConnectionState
import com.cutm.nt14.data.remote.model.PolyLanceAttestation
import com.cutm.nt14.data.remote.model.PolyLanceEscrow
import com.cutm.nt14.data.remote.model.PolyLanceTalent
import com.cutm.nt14.data.remote.model.RequestLogDto
import com.cutm.nt14.domain.detector.OptimizationResult
import com.cutm.nt14.domain.model.UserRole
import com.cutm.nt14.ui.components.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onLogout: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val polyLanceState by viewModel.polyLanceState.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val userName by viewModel.userName.collectAsState()
    var showHostDialog by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return@remember c
            c = c.baseContext
        }
        null
    }

    GlassBackground {
        if (uiState.userRole == UserRole.ADMIN) {
            AdminDashboardContent(
                viewModel = viewModel,
                uiState = uiState,
                polyLanceState = polyLanceState,
                userEmail = userEmail,
                activity = activity,
                onLogout = onLogout,
                onShowHostDialog = { showHostDialog = true },
                onShowPairDialog = { showPairDialog = true }
            )
        } else {
            ViewerDashboardContent(
                viewModel = viewModel,
                uiState = uiState,
                polyLanceState = polyLanceState,
                userEmail = userEmail,
                userName = userName,
                activity = activity,
                onLogout = onLogout,
                onShowPairDialog = { showPairDialog = true }
            )
        }
    }

    // Gateway Pairing QR / Token Dialog
    if (showPairDialog) {
        GatewayPairDialog(
            currentHost = uiState.connectedHost,
            onDismiss = { showPairDialog = false },
            onPair = { pairInput ->
                viewModel.pairWithGateway(pairInput)
            },
            onFetchFromServer = { host ->
                viewModel.fetchPairingFromServer(host)
            }
        )
    }

    // Host Configuration Dialog (Admin only)
    if (showHostDialog && uiState.userRole == UserRole.ADMIN) {
        WhiteHostConfigDialog(
            currentHost = uiState.connectedHost,
            onDismiss = { showHostDialog = false },
            onSave = { newHost ->
                viewModel.updateGatewayHost(newHost)
                showHostDialog = false
            }
        )
    }

    // Raw JSON Inspector Dialog
    if (polyLanceState.showJsonModal) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleJsonModal(false) },
            containerColor = Color.White,
            title = {
                Text(
                    text = "Raw PolyLance Gateway JSON",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = PolyTextPrimary
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = polyLanceState.rawJson ?: "No response body recorded",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = PolyTextPrimary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.toggleJsonModal(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = PolyPrimary)
                ) {
                    Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

/**
 * ========================================================
 * 1. ADMIN DASHBOARD: Full Gateway Control & Operations
 * ========================================================
 */
@Composable
fun AdminDashboardContent(
    viewModel: DashboardViewModel,
    uiState: DashboardUiState,
    polyLanceState: PolyLanceInspectorUiState,
    userEmail: String?,
    activity: android.app.Activity?,
    onLogout: () -> Unit,
    onShowHostDialog: () -> Unit,
    onShowPairDialog: () -> Unit
) {
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
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "ADMIN CONTROL CENTER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PolyPrimary,
                                fontFamily = FontFamily.SansSerif,
                                letterSpacing = 1.2.sp
                            )
                            GlassBadge(
                                text = "ADMIN",
                                color = PolyPrimary
                            )
                            if (uiState.demoMode) {
                                GlassBadge(
                                    text = "DEMO TRAFFIC",
                                    color = PolyWarning
                                )
                            }
                        }
                        Text(
                            text = "Gateway Dashboard",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            color = PolyTextPrimary
                        )
                        if (!userEmail.isNullOrBlank()) {
                            Text(
                                text = "Google: $userEmail",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.SansSerif,
                                color = PolyPrimary
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Scan Gateway QR Pill Button
                        PillActionButton(
                            text = "Scan QR",
                            icon = Icons.Default.Share,
                            onClick = onShowPairDialog
                        )

                        // Gateway Host Switcher Button
                        PillActionButton(
                            text = "Host",
                            icon = Icons.Default.Settings,
                            onClick = onShowHostDialog
                        )

                        // Logout Button
                        IconButton(
                            onClick = {
                                viewModel.logout(activity)
                                onLogout()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.85f))
                                .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Logout",
                                tint = PolyDanger,
                                modifier = Modifier.size(20.dp)
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 140.dp
                )
            ) {
                // 1. Live Gateway Connection Banner
                item {
                    val connState = uiState.connectionState
                    val isConnected = connState is GatewayConnectionState.Connected
                    val isWaking = connState is GatewayConnectionState.WakingServer
                    val isConnecting = connState is GatewayConnectionState.Connecting
                    val isFailed = connState is GatewayConnectionState.Failed

                    val statusColor = when {
                        isConnected -> PolySuccess
                        isWaking -> PolyCyan
                        isConnecting -> PolyWarning
                        else -> PolyDanger
                    }

                    val statusTitle = when {
                        isConnected -> "Connected to Gateway"
                        isWaking -> "Waking server..."
                        isConnecting -> "Connecting to Gateway..."
                        isFailed -> "Gateway Disconnected"
                        else -> "Gateway Offline"
                    }

                    val cleanHost = uiState.connectedHost.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://").trim().trimEnd('/')
                    val wsProtocol = if (cleanHost.contains("onrender.com") || cleanHost.contains("cloud") || uiState.connectedHost.startsWith("https://") || uiState.connectedHost.startsWith("wss://")) "wss://" else "ws://"
                    val wsDisplayUrl = "$wsProtocol$cleanHost/ws/events"

                    GlassCard(
                        backgroundColor = if (isConnected) Color.White.copy(alpha = 0.92f) else if (isWaking) Color(0xFFF0FDF4) else Color(0xFFFFFBEB),
                        borderBrush = if (isConnected) GlassBorderSubtle else BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)).brush
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Column {
                                    Text(
                                        text = statusTitle,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = PolyTextPrimary,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                    Text(
                                        text = wsDisplayUrl,
                                        fontSize = 11.sp,
                                        color = PolyTextSecondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            if (!isConnected) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PillActionButton(
                                        text = "Scan QR",
                                        icon = Icons.Default.Share,
                                        onClick = onShowPairDialog
                                    )
                                    PillActionButton(
                                        text = "Reconnect",
                                        icon = Icons.Default.Refresh,
                                        onClick = { viewModel.reconnect() }
                                    )
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (uiState.demoMode) {
                                        GlassBadge(
                                            text = "DEMO TRAFFIC",
                                            color = PolyWarning
                                        )
                                    }
                                    GlassBadge(
                                        text = "LIVE FEED",
                                        color = PolySuccess
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Action Message Banner
                if (!uiState.actionMessage.isNullOrBlank()) {
                    item {
                        GlassCard(
                            backgroundColor = PolyPrimaryLight,
                            borderBrush = GlassBorderSubtle
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uiState.actionMessage!!,
                                    color = PolyPrimaryDark,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "DISMISS",
                                    color = PolyPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif,
                                    modifier = Modifier
                                        .clickable { viewModel.clearActionMessage() }
                                        .padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }

                // 2. Prominent Real-Time Burst Simulator & Action Deck (Above the fold)
                item {
                    GlassCard(
                        backgroundColor = Color.White.copy(alpha = 0.95f),
                        borderBrush = BorderStroke(1.5.dp, PolyWarning.copy(alpha = 0.6f)).brush
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFEF3C7)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Simulator",
                                            tint = PolyWarning,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "TRAFFIC BURST SIMULATOR",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PolyTextPrimary,
                                            fontFamily = FontFamily.SansSerif,
                                            letterSpacing = 1.sp
                                        )
                                        Text(
                                            text = "Inject real-time load against rate limiter engine",
                                            fontSize = 10.sp,
                                            color = PolyTextSecondary
                                        )
                                    }
                                }

                                GlassBadge(
                                    text = if (polyLanceState.isBursting) "BLASTING..." else "READY",
                                    color = if (polyLanceState.isBursting) PolyDanger else PolySuccess
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RingActionButton(
                                    label = "Burst 25",
                                    icon = Icons.Default.PlayArrow,
                                    ringProgress = if (polyLanceState.isBursting) 0.8f else 0.4f,
                                    ringBrush = androidx.compose.ui.graphics.SolidColor(Nt14Ui.Warn),
                                    onClick = { viewModel.simulateTraffic("/api/polylance/escrows", 25, 20.0, "burst") }
                                )
                                RingActionButton(
                                    label = "Attack 50",
                                    icon = Icons.Default.Warning,
                                    ringProgress = if (polyLanceState.isBursting) 1.0f else 0.7f,
                                    ringBrush = androidx.compose.ui.graphics.SolidColor(Nt14Ui.Blocked),
                                    onClick = { viewModel.simulateTraffic("/api/polylance/escrows", 50, 40.0, "attack") }
                                )
                                RingActionButton(
                                    label = "Test GET",
                                    icon = Icons.Default.Check,
                                    ringProgress = 0.25f,
                                    ringBrush = androidx.compose.ui.graphics.SolidColor(Nt14Ui.Accent),
                                    onClick = { viewModel.fireTestRequest("/api/polylance/escrows") }
                                )
                                RingActionButton(
                                    label = "Clear",
                                    icon = Icons.Default.Delete,
                                    ringProgress = null,
                                    onClick = { viewModel.clearLogs() }
                                )
                            }
                        }
                    }
                }

                // 3. Security & Anti-Tamper Card
                item {
                    val sec = uiState.securityReport
                    val isConnected = uiState.connectionState is GatewayConnectionState.Connected
                    val isThreat = sec != null && sec.isCompromised
                    val isClean = isConnected && !isThreat

                    val cardBg = when {
                        !isConnected -> Color.White.copy(alpha = 0.90f)
                        isThreat -> Color(0xFFFFF1F2)
                        else -> Color.White.copy(alpha = 0.94f)
                    }
                    val cardBorder = when {
                        !isConnected -> GlassBorderSubtle
                        isThreat -> BorderStroke(1.5.dp, PolyDanger).brush
                        else -> GlassBorderCobalt
                    }
                    val iconTint = when {
                        !isConnected -> PolyTextSecondary
                        isThreat -> PolyDanger
                        else -> PolyPrimary
                    }
                    val statusText = when {
                        !isConnected -> "Gateway Offline - Integrity Unknown"
                        isThreat -> "Security Threat Detected!"
                        else -> "Device & Network Integrity Verified"
                    }

                    GlassCard(
                        backgroundColor = cardBg,
                        borderBrush = cardBorder
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(if (!isConnected) Color(0xFFF1F5F9) else if (isClean) PolyPrimaryLight else Color(0xFFFFE4E6)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Security Integrity",
                                            tint = iconTint,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "ANTI-TAMPER & ZERO-TRUST SECURITY",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = iconTint,
                                            fontFamily = FontFamily.SansSerif,
                                            letterSpacing = 1.sp
                                        )
                                        Text(
                                            text = statusText,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.SansSerif,
                                            color = PolyTextPrimary
                                        )
                                    }
                                }

                                PillActionButton(
                                    text = "Rescan",
                                    icon = Icons.Default.Lock,
                                    onClick = { viewModel.rescanSecurityIntegrity() }
                                )
                            }

                            HorizontalDivider(color = Color(0xFFE2E8F0))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecurityStatusRow(
                                    label = "Anti-MitM Proxy Defense",
                                    status = if (!isConnected) "UNKNOWN (Gateway Offline)" else if (sec?.isProxyDetected == true) "FAIL (Proxy Active)" else "PASS (Clean)",
                                    isOk = isConnected && sec?.isProxyDetected != true,
                                    detail = if (!isConnected) "Requires active gateway connection" else if (sec?.isProxyDetected == true) sec.proxyIndicators.firstOrNull() else "System CAs enforced, User CAs rejected"
                                )
                                SecurityStatusRow(
                                    label = "Root / Jailbreak Guard",
                                    status = if (sec?.isRooted == true) "FAIL (Rooted)" else "PASS (Clean)",
                                    isOk = sec?.isRooted != true,
                                    detail = if (sec?.isRooted == true) sec.rootIndicators.firstOrNull() else "SU binaries absent, Verified OS build"
                                )
                                SecurityStatusRow(
                                    label = "Reverse Engineering & Frida",
                                    status = if (sec?.isFridaDetected == true || sec?.isDebuggerAttached == true) "ALERT (Hooked)" else "PASS (Clean)",
                                    isOk = sec?.isFridaDetected != true && sec?.isDebuggerAttached != true,
                                    detail = if (sec?.isFridaDetected == true) "Hooking library mapped" else "Port 27042 closed, Memory maps unhooked"
                                )
                                SecurityStatusRow(
                                    label = "Bytecode & ADB Extraction",
                                    status = "HARDENED",
                                    isOk = true,
                                    detail = "R8 Minified, ProGuard Active, ADB Backup Disabled"
                                )
                            }
                        }
                    }
                }

                // 4. Transparent White Metric Tiles (2x2 Grid)
                item {
                    Text(
                        text = "REAL-TIME METRICS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PolyTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WhiteMetricTile(
                                title = "Endpoints",
                                value = uiState.endpointCount.toString(),
                                subtitle = "Active routes",
                                accentColor = PolyPurple,
                                icon = Icons.Default.Place,
                                modifier = Modifier.weight(1f)
                            )
                            WhiteMetricTile(
                                title = "Total Requests",
                                value = uiState.totalRequests.toString(),
                                subtitle = "Processed live",
                                accentColor = PolyCyan,
                                icon = Icons.Default.CheckCircle,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WhiteMetricTile(
                                title = "Error / Throttle",
                                value = "%.1f%%".format(uiState.errorRate * 100),
                                subtitle = "429 rate throttled",
                                accentColor = if (uiState.errorRate > 0) PolyWarning else PolySuccess,
                                icon = Icons.Default.Warning,
                                modifier = Modifier.weight(1f)
                            )
                            WhiteMetricTile(
                                title = "Active Incidents",
                                value = uiState.activeIncidents.toString(),
                                subtitle = if (uiState.activeIncidents > 0) "Immediate attention" else "Zero threats",
                                accentColor = if (uiState.activeIncidents > 0) PolyDanger else PolySuccess,
                                icon = Icons.Default.Notifications,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 5. PolyLance Sovereign Protocol Live Data & Rate Limit Optimizer
                item {
                    PolyLanceLiveProtocolSection(
                        state = polyLanceState,
                        onTabSelect = { viewModel.selectPolyLanceTab(it) },
                        onFetch = { viewModel.fetchActivePolyLanceData() },
                        onCreateEscrow = { viewModel.createTestEscrow(500.0) },
                        onBurst = { viewModel.simulateAttackBurst() },
                        onOptimize = { viewModel.runRateLimitOptimizerOnPolyLance() },
                        onApplyOptimization = { viewModel.applyOptimizedLimit() },
                        onDismissOptimization = { viewModel.dismissOptimization() },
                        onViewJson = { viewModel.toggleJsonModal(true) }
                    )
                }

                // 6. Live Traffic Stream Feed
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LIVE TRAFFIC STREAM",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextSecondary,
                            letterSpacing = 1.sp
                        )
                        GlassBadge(
                            text = if (uiState.recentLogs.isEmpty()) "IDLE" else "REALTIME STREAM",
                            color = if (uiState.recentLogs.isEmpty()) PolyTextMuted else PolySuccess
                        )
                    }
                }

                if (uiState.recentLogs.isEmpty()) {
                    item {
                        GlassCard(backgroundColor = Color.White.copy(alpha = 0.85f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = PolyTextMuted,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No traffic yet. Tap 'Test GET' or 'Simulate Burst' above to generate live gateway events!",
                                        color = PolyTextSecondary,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(uiState.recentLogs, key = { it.id }) { log ->
                        WhiteLogItemCard(log)
                    }
                }
            }
        }
    }

/**
 * ========================================================
 * 2. VIEWER DASHBOARD: Dedicated Passive Observability
 * ========================================================
 */
@Composable
fun ViewerDashboardContent(
    viewModel: DashboardViewModel,
    uiState: DashboardUiState,
    polyLanceState: PolyLanceInspectorUiState,
    userEmail: String?,
    userName: String?,
    activity: android.app.Activity?,
    onLogout: () -> Unit,
    onShowPairDialog: () -> Unit
) {
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
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "TELEMETRY OBSERVER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyPrimary,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 1.2.sp
                        )
                        GlassBadge(
                            text = "VIEWER",
                            color = PolyTextSecondary
                        )
                        if (uiState.demoMode) {
                            GlassBadge(
                                text = "DEMO TRAFFIC",
                                color = PolyWarning
                            )
                        }
                    }
                    Text(
                        text = "Viewer Dashboard",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        color = PolyTextPrimary
                    )
                    if (!userEmail.isNullOrBlank()) {
                        Text(
                            text = "Google: $userEmail",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.SansSerif,
                            color = PolyTextSecondary
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pair Gateway Pill Button
                    PillActionButton(
                        text = "Scan QR",
                        icon = Icons.Default.Share,
                        onClick = onShowPairDialog
                    )

                    // Logout Button
                    IconButton(
                        onClick = {
                            viewModel.logout(activity)
                            onLogout()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.88f))
                            .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Logout",
                            tint = PolyDanger,
                            modifier = Modifier.size(20.dp)
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 140.dp
            )
        ) {
            // 1. Viewer Welcome & Access Level Card
            item {
                GlassCard(
                    backgroundColor = Color.White.copy(alpha = 0.94f),
                    borderBrush = GlassBorderCobalt
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(PolyPrimaryLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = PolyPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Welcome, ${userName ?: "Viewer"}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                color = PolyTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Signed in with Google ($userEmail). You have real-time read-only access to monitor gateway health, availability SLAs, and live Polygon smart contract escrows.",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.SansSerif,
                                color = PolyTextSecondary,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = PolySuccess,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Zero-Trust Active: System CAs Enforced, Anti-Proxy Protected",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.SansSerif,
                                    color = PolySuccess
                                )
                            }
                        }
                    }
                }
            }

            // 2. Gateway SLA & Operational Availability Banner
            item {
                val connState = uiState.connectionState
                val isConnected = connState is GatewayConnectionState.Connected
                val isWaking = connState is GatewayConnectionState.WakingServer
                val isConnecting = connState is GatewayConnectionState.Connecting
                val isFailed = connState is GatewayConnectionState.Failed

                val statusColor = when {
                    isConnected -> PolySuccess
                    isWaking -> PolyCyan
                    isConnecting -> PolyWarning
                    else -> PolyDanger
                }

                val statusTitle = when {
                    isConnected -> "Gateway Operational"
                    isWaking -> "Waking server..."
                    isConnecting -> "Connecting to Gateway..."
                    isFailed -> "Gateway Disconnected"
                    else -> "Gateway Offline"
                }

                GlassCard(
                    backgroundColor = if (isConnected) Color.White.copy(alpha = 0.90f) else if (isWaking) Color(0xFFF0FDF4) else Color(0xFFFFFBEB),
                    borderBrush = if (isConnected) GlassBorderSubtle else BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)).brush
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Column {
                                Text(
                                    text = statusTitle,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    color = PolyTextPrimary
                                )
                                val availabilityRatio = if (uiState.totalRequests > 0) {
                                    ((uiState.totalRequests - uiState.throttledCount).toDouble() / uiState.totalRequests.toDouble()) * 100.0
                                } else 100.0
                                val availabilityText = if (isConnected) {
                                    "Realtime QoS: %.2f%% Success • %.1f RPS".format(availabilityRatio, uiState.rps)
                                } else if (isWaking) {
                                    "Render cold start wake probe in flight..."
                                } else {
                                    "Gateway Offline • Reconnect to resume live feed"
                                }

                                Text(
                                    text = availabilityText,
                                    fontSize = 11.sp,
                                    color = PolyTextSecondary,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }

                        if (!isConnected) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PillActionButton(
                                    text = "Scan QR",
                                    icon = Icons.Default.Share,
                                    onClick = onShowPairDialog
                                )
                                PillActionButton(
                                    text = "Reconnect",
                                    icon = Icons.Default.Refresh,
                                    onClick = { viewModel.reconnect() }
                                )
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (uiState.demoMode) {
                                    GlassBadge(
                                        text = "DEMO TRAFFIC",
                                        color = PolyWarning
                                    )
                                }
                                GlassBadge(
                                    text = "LIVE FEED",
                                    color = PolySuccess
                                )
                            }
                        }
                    }
                }
            }

            // 3. High-Level Service Telemetry Tiles
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WhiteMetricTile(
                        title = "TOTAL REQUESTS",
                        value = "${uiState.totalRequests}",
                        subtitle = "monitored requests",
                        icon = Icons.Default.Info,
                        accentColor = PolyPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    WhiteMetricTile(
                        title = "ACTIVE ROUTES",
                        value = "${uiState.endpointCount}",
                        subtitle = "rate-limited routes",
                        icon = Icons.Default.Menu,
                        accentColor = PolyPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WhiteMetricTile(
                        title = "QOS SCORE",
                        value = "${(100 - uiState.errorRate * 100).toInt()}%",
                        subtitle = if (uiState.errorRate > 0) "${(uiState.errorRate * 100).toInt()}% throttled" else "zero drops",
                        icon = Icons.Default.Check,
                        accentColor = if (uiState.errorRate > 0.1f) PolyWarning else PolySuccess,
                        modifier = Modifier.weight(1f)
                    )
                    WhiteMetricTile(
                        title = "SECURITY THREATS",
                        value = "${uiState.activeIncidents}",
                        subtitle = if (uiState.activeIncidents == 0) "all clear" else "threats caught",
                        icon = Icons.Default.Warning,
                        accentColor = if (uiState.activeIncidents == 0) PolySuccess else PolyDanger,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4. Real-Time PolyLance Protocol Live Inspector (Viewer Read-Only Mode)
            item {
                PolyLanceLiveProtocolSection(
                    state = polyLanceState,
                    isAdmin = false,
                    onTabSelect = { viewModel.selectPolyLanceTab(it) },
                    onFetch = { viewModel.fetchActivePolyLanceData() }
                )
            }

            // 5. Live Request Telemetry Feed
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE REQUEST TELEMETRY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PolyTextSecondary,
                        letterSpacing = 1.sp
                    )
                    GlassBadge(
                        text = "${uiState.recentLogs.size} IN STREAM",
                        color = PolyPrimary
                    )
                }
            }

            if (uiState.recentLogs.isEmpty()) {
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = Color.White.copy(alpha = 0.85f),
                        elevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = PolyTextMuted,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Awaiting Live Requests",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = PolyTextPrimary
                            )
                            Text(
                                text = "Traffic hitting protected endpoints will stream here in real-time.",
                                color = PolyTextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(uiState.recentLogs, key = { it.id }) { log ->
                    WhiteLogItemCard(log)
                }
            }

            // 6. Security Governance Information Banner
            item {
                GlassCard(
                    backgroundColor = Color.White.copy(alpha = 0.90f),
                    borderBrush = GlassBorderSubtle
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Security",
                            tint = PolyPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Gateway Policy Enforcement Active",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = PolyTextPrimary
                            )
                            Text(
                                text = "Token Bucket burst control and Sliding Window rate limiting are enforced. Administered by authorized administrators.",
                                fontSize = 11.sp,
                                color = PolyTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PolyLanceLiveProtocolSection(
    state: PolyLanceInspectorUiState,
    isAdmin: Boolean = true,
    onTabSelect: (PolyLanceTab) -> Unit,
    onFetch: () -> Unit,
    onCreateEscrow: () -> Unit = {},
    onBurst: () -> Unit = {},
    onOptimize: () -> Unit = {},
    onApplyOptimization: () -> Unit = {},
    onDismissOptimization: () -> Unit = {},
    onViewJson: () -> Unit = {}
) {
    val activeEndpointPath = when (state.selectedTab) {
        PolyLanceTab.ESCROWS -> "/api/polylance/escrows"
        PolyLanceTab.ATTESTATIONS -> "/api/polylance/attestations"
        PolyLanceTab.TALENTS -> "/api/polylance/talents"
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "POLYLANCE PROTOCOL LIVE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = PolyTextSecondary,
                letterSpacing = 1.sp
            )
            GlassBadge(
                text = activeEndpointPath,
                color = PolyPurple
            )
        }

        GlassCard(
            backgroundColor = Color.White.copy(alpha = 0.92f),
            borderBrush = GlassBorderSubtle
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header with Live Telemetry Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Real-Time Protocol Telemetry",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = PolyTextPrimary
                        )
                        Text(
                            text = "Connected to Polygon Escrow & Attestation Gateway",
                            fontSize = 11.sp,
                            color = PolyTextSecondary
                        )
                    }

                    if (state.lastStatusCode != null) {
                        val is200 = state.lastStatusCode == 200 || state.lastStatusCode == 201
                        GlassBadge(
                            text = "HTTP ${state.lastStatusCode}",
                            color = if (is200) PolySuccess else PolyDanger
                        )
                    }
                }

                // Live Header Metrics Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Latency: ${state.lastLatencyMs ?: 0}ms",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PolyTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Quota: ${state.rateLimitRemaining ?: 20}/${state.rateLimitLimit ?: 20}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if ((state.rateLimitRemaining ?: 20) <= 2) PolyDanger else PolyPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Tab Selector with FilterTiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterTile(
                        label = "Escrows",
                        icon = Icons.Default.Star,
                        selected = state.selectedTab == PolyLanceTab.ESCROWS,
                        badge = state.escrows.size.toString(),
                        onClick = { onTabSelect(PolyLanceTab.ESCROWS) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTile(
                        label = "Attestations",
                        icon = Icons.Default.CheckCircle,
                        selected = state.selectedTab == PolyLanceTab.ATTESTATIONS,
                        badge = state.attestations.size.toString(),
                        onClick = { onTabSelect(PolyLanceTab.ATTESTATIONS) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterTile(
                        label = "Talents",
                        icon = Icons.Default.Person,
                        selected = state.selectedTab == PolyLanceTab.TALENTS,
                        badge = state.talents.size.toString(),
                        onClick = { onTabSelect(PolyLanceTab.TALENTS) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Actions Deck
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillActionButton(
                        text = if (state.isLoading) "Fetching..." else if (isAdmin) "Fetch Live" else "Refresh",
                        icon = Icons.Default.Refresh,
                        onClick = onFetch,
                        modifier = if (isAdmin) Modifier.weight(1.2f) else Modifier.weight(1f)
                    )

                    if (isAdmin) {
                        if (state.selectedTab == PolyLanceTab.ESCROWS) {
                            PillActionButton(
                                text = "+500 POL",
                                icon = Icons.Default.Add,
                                onClick = onCreateEscrow,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        PillActionButton(
                            text = "Optimize",
                            icon = Icons.Default.Star,
                            onClick = onOptimize,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        GlassBadge(
                            text = "READ-ONLY",
                            color = PolyPrimary
                        )
                    }
                }

                // Rate Limit Optimizer Recommendation Card (Admin Only)
                if (isAdmin && state.optimizationResult != null) {
                    OptimizationResultBanner(
                        result = state.optimizationResult,
                        onApply = onApplyOptimization,
                        onDismiss = onDismissOptimization
                    )
                }

                // Live Data Presentation
                if (state.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = PolyPrimary,
                            strokeWidth = 2.5.dp
                        )
                    }
                } else {
                    when (state.selectedTab) {
                        PolyLanceTab.ESCROWS -> {
                            if (state.escrows.isEmpty()) {
                                Text(
                                    text = "No live escrows loaded yet. Tap 'Fetch Live Data' to query the gateway.",
                                    fontSize = 12.sp,
                                    color = PolyTextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.escrows.forEach { escrow ->
                                        EscrowLiveCard(escrow)
                                    }
                                }
                            }
                        }
                        PolyLanceTab.ATTESTATIONS -> {
                            if (state.attestations.isEmpty()) {
                                Text(
                                    text = "No skill attestations loaded. Tap 'Fetch Live Data' to retrieve on-chain attestations.",
                                    fontSize = 12.sp,
                                    color = PolyTextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.attestations.forEach { att ->
                                        AttestationLiveCard(att)
                                    }
                                }
                            }
                        }
                        PolyLanceTab.TALENTS -> {
                            if (state.talents.isEmpty()) {
                                Text(
                                    text = "No talent records loaded. Tap 'Fetch Live Data' to load verified talent directory.",
                                    fontSize = 12.sp,
                                    color = PolyTextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.talents.forEach { talent ->
                                        TalentLiveCard(talent)
                                    }
                                }
                            }
                        }
                    }
                }

                // Footer with Raw JSON inspection (Admin only)
                if (isAdmin && state.rawJson != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onViewJson)
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "View Raw Gateway JSON Response",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PolyLanceTabChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PolyPrimary else Color(0xFFF1F5F9))
            .border(
                BorderStroke(
                    1.dp,
                    if (selected) PolyPrimary else Color(0xFFE2E8F0)
                ),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) Color.White else PolyTextSecondary
        )
    }
}

@Composable
fun EscrowLiveCard(escrow: PolyLanceEscrow) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = escrow.escrowId,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = PolyTextPrimary
                )
                GlassBadge(
                    text = escrow.status,
                    color = when (escrow.status.lowercase()) {
                        "completed", "funded_in_escrow", "funded" -> PolySuccess
                        "submitted", "milestone_pending" -> PolyWarning
                        else -> PolyPurple
                    }
                )
            }

            if (escrow.title.isNotBlank()) {
                Text(
                    text = escrow.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PolyTextPrimary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${escrow.amountPol} ${escrow.token}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = PolyPurple
                )
                Text(
                    text = "${escrow.client.take(6)}...${escrow.client.takeLast(4)} -> ${escrow.freelancer.take(6)}...${escrow.freelancer.takeLast(4)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = PolyTextSecondary
                )
            }

            if (!escrow.contractAddress.isNullOrBlank()) {
                Text(
                    text = "Contract: ${escrow.contractAddress}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = PolyTextMuted
                )
            }
        }
    }
}

@Composable
fun AttestationLiveCard(att: PolyLanceAttestation) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = att.attestationId,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = PolyTextPrimary
                )
                GlassBadge(text = att.soulboundTokenId, color = PolyPrimary)
            }

            Text(
                text = att.skillAttestation,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = PolyTextPrimary
            )

            Text(
                text = "Verified for github.com/${att.developerGithub}",
                fontSize = 11.sp,
                color = PolySuccess
            )
        }
    }
}

@Composable
fun TalentLiveCard(talent: PolyLanceTalent) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = talent.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = PolyTextPrimary
                )
                Text(
                    text = talent.specialization,
                    fontSize = 12.sp,
                    color = PolyTextSecondary
                )
            }

            GlassBadge(
                text = "Score ${talent.rating}",
                color = PolyPurple
            )
        }
    }
}

@Composable
fun OptimizationResultBanner(
    result: OptimizationResult,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFEF3C7))
            .border(BorderStroke(1.dp, Color(0xFFFCD34D)), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rate Limit Optimizer Recommendation",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF92400E)
                )
                Text(
                    text = "${result.totalAnalyzed} logs analyzed",
                    fontSize = 10.sp,
                    color = Color(0xFFB45309)
                )
            }

            Text(
                text = result.rationale,
                fontSize = 12.sp,
                color = Color(0xFF78350F)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recommended: ${result.recommendedLimitPerMin} req/min (Burst: ${result.recommendedBurstLimit})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PolyDanger
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = "Dismiss",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E)
                        )
                    }

                    PillActionButton(
                        text = "Apply Policy",
                        icon = Icons.Default.Check,
                        onClick = onApply
                    )
                }
            }
        }
    }
}

@Composable
fun WhiteMetricTile(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    GlassCard(
        backgroundColor = Color.White.copy(alpha = 0.88f),
        borderBrush = GlassBorderSubtle,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PolyTextSecondary,
                    letterSpacing = 0.5.sp
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PolyTextPrimary
            )

            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = PolyTextMuted
            )
        }
    }
}

@Composable
fun WhiteLogItemCard(log: RequestLogDto) {
    val isBlocked = log.status == 429
    val isError = log.status >= 400 && !isBlocked
    val isSuccess = log.status in 200..299

    val statusColor = when {
        isSuccess -> PolySuccess
        isBlocked -> PolyDanger
        isError -> PolyWarning
        else -> PolyCyan
    }

    val timeFormatted = remember(log.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
    }

    GlassCard(
        backgroundColor = Color.White.copy(alpha = 0.88f),
        borderBrush = GlassBorderSubtle
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Column {
                    Text(
                        text = "${log.method} ${log.path}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = PolyTextPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "${log.clientId} • ${log.latencyMs}ms • $timeFormatted",
                        fontSize = 11.sp,
                        color = PolyTextSecondary
                    )
                }
            }

            GlassBadge(
                text = "HTTP ${log.status}",
                color = statusColor
            )
        }
    }
}

@Composable
fun WhiteLogItem(log: RequestLogDto) = WhiteLogItemCard(log)

@Composable
fun WhiteHostConfigDialog(
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
                text = "Gateway Server Configuration",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = PolyTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Configure the IP:Port or URL of the live Kotlin/Ktor Rate Limiter Gateway.",
                    fontSize = 12.sp,
                    color = PolyTextSecondary
                )

                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it },
                    label = { Text("Host (e.g. 127.0.0.1:8000)") },
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
                    text = "Quick Presets:",
                    color = PolyTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WhitePresetChip("Emulator (10.0.2.2)") {
                            hostInput = "10.0.2.2:8000"
                        }
                        WhitePresetChip("Local USB (127.0.0.1)") {
                            hostInput = "127.0.0.1:8000"
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WhitePresetChip("LAN Wi-Fi (Auto)") {
                            hostInput = "192.168.1.100:8000"
                        }
                        WhitePresetChip("Cloud Gateway (Render)") {
                            hostInput = "https://api-rate-limiter-gateway-n0ab.onrender.com"
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
fun WhitePresetChip(label: String, onClick: () -> Unit) {
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

@Composable
fun SecurityStatusRow(
    label: String,
    status: String,
    isOk: Boolean,
    detail: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = PolyTextPrimary
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    fontSize = 10.sp,
                    color = PolyTextSecondary
                )
            }
        }
        GlassBadge(
            text = status,
            color = if (isOk) PolySuccess else PolyDanger
        )
    }
}
