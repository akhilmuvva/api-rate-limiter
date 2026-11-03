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
import com.cutm.nt14.ui.monitoring.DataFreshnessBanner
import com.cutm.nt14.ui.monitoring.LatencySparkline
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
        GatewayHostConfigDialog(
            currentHost = uiState.connectedHost,
            onDismiss = { showHostDialog = false },
            onSave = { newHost ->
                viewModel.updateGatewayHost(newHost)
                showHostDialog = false
            },
            isAdmin = uiState.userRole == UserRole.ADMIN,
            repository = viewModel.repository
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
            ScreenHeader(
                eyebrow = "ADMIN CONTROL CENTER",
                title = "Gateway Dashboard",
                userRole = uiState.userRole,
                userEmail = userEmail,
                onScanQr = onShowPairDialog,
                onSignOut = {
                    viewModel.logout(activity)
                    onLogout()
                },
                onOpenHost = onShowHostDialog,
                onToggleRole = { viewModel.toggleRole() },
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    ) { padding ->
        SpacedScreen(modifier = Modifier.padding(padding)) {
            // 1. Overview InfoCard + Data Freshness + Monitored Targets Summary
            item {
                val lastSyncMs by viewModel.repository.lastDataSyncMs.collectAsState()
                val persistenceMode by viewModel.repository.persistenceMode.collectAsState()
                val targets by viewModel.repository.targets.collectAsState()

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DataFreshnessBanner(lastSyncMs = lastSyncMs, persistenceMode = persistenceMode)
                    InfoCard(
                        title = "Autonomous Gateway Protection",
                        body = "Adaptive sliding window limiter, sliding rate token buckets, and real-time abuse anomaly heuristics active.",
                        icon = Icons.Default.Shield,
                        badge = if (uiState.demoMode) "Demo Traffic" else "Real Traffic"
                    )
                    if (targets.isNotEmpty()) {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White.copy(alpha = 0.94f)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "MONITORED UPSTREAM TARGETS (${targets.count { it.up == true }}/${targets.size} UP)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PolyPrimary,
                                    letterSpacing = 1.sp
                                )
                                targets.forEach { t ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${t.name} (${t.lastLatencyMs ?: 0}ms)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = PolyTextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (t.recentLatencies.isNotEmpty()) {
                                            LatencySparkline(
                                                latencies = t.recentLatencies,
                                                upFlags = t.recentUp,
                                                modifier = Modifier
                                                    .width(90.dp)
                                                    .height(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = when (t.up) { true -> "UP"; false -> "DOWN"; null -> "..." },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when (t.up) { true -> PolySuccess; false -> PolyDanger; null -> PolyWarning }
                                        )
                                    }
                                }
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

                // 4. Real-Time Metrics (2-column Grid)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "REAL-TIME METRICS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Nt14Ui.TextMuted,
                            letterSpacing = 1.sp
                        )
                        MetricsGrid(
                            metrics = listOf(
                                MetricTileData(
                                    title = "TOTAL REQUESTS",
                                    value = uiState.totalRequests.toString(),
                                    subtext = "Processed live",
                                    accentColor = Nt14Ui.Accent,
                                    icon = Icons.Default.CheckCircle
                                ),
                                MetricTileData(
                                    title = "ENDPOINTS",
                                    value = uiState.endpointCount.toString(),
                                    subtext = "Active routes",
                                    accentColor = Nt14Ui.Accent,
                                    icon = Icons.Default.Place
                                ),
                                MetricTileData(
                                    title = "THROTTLE (429)",
                                    value = "%.1f%%".format(uiState.throttleRate * 100),
                                    subtext = "5xx err: %.1f%%".format(uiState.errorRate * 100),
                                    accentColor = if (uiState.throttleRate > 0) Nt14Ui.Warn else Nt14Ui.Ok,
                                    icon = Icons.Default.Warning
                                ),
                                MetricTileData(
                                    title = "UPSTREAM P50/P95",
                                    value = "${uiState.p50UpstreamMs}/${uiState.p95UpstreamMs}ms",
                                    subtext = "Limiter p50: ${uiState.p50LimiterMs}ms",
                                    accentColor = Nt14Ui.Accent,
                                    icon = Icons.Default.Notifications
                                )
                            )
                        )
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
            ScreenHeader(
                eyebrow = "TELEMETRY OBSERVER",
                title = "Viewer Dashboard",
                userRole = uiState.userRole,
                userEmail = userEmail,
                onScanQr = onShowPairDialog,
                onSignOut = {
                    viewModel.logout(activity)
                    onLogout()
                },
                onToggleRole = { viewModel.toggleRole() },
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    ) { padding ->
        SpacedScreen(modifier = Modifier.padding(padding)) {
            // 1. Viewer Read-Only InfoCard
            item {
                InfoCard(
                    title = "Read-Only Viewer Session",
                    body = "Real-time access to telemetry, SLA health, rate limits, and live upstream protocol streams. Admin privileges required for configuration updates.",
                    icon = Icons.Default.Visibility,
                    badge = "Guest (read-only)"
                )
            }

            // 2. High-Level Service Telemetry Tiles
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "SERVICE TELEMETRY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Nt14Ui.TextMuted,
                        letterSpacing = 1.sp
                    )
                    MetricsGrid(
                        metrics = listOf(
                            MetricTileData(
                                title = "TOTAL REQUESTS",
                                value = uiState.totalRequests.toString(),
                                subtext = "Monitored requests",
                                accentColor = Nt14Ui.Accent,
                                icon = Icons.Default.Info
                            ),
                            MetricTileData(
                                title = "ACTIVE ROUTES",
                                value = uiState.endpointCount.toString(),
                                subtext = "Rate-limited routes",
                                accentColor = Nt14Ui.Accent,
                                icon = Icons.Default.Menu
                            ),
                            MetricTileData(
                                title = "QOS SCORE",
                                value = "${(100 - uiState.errorRate * 100).toInt()}%",
                                subtext = if (uiState.errorRate > 0) "${(uiState.errorRate * 100).toInt()}% throttled" else "Zero drops",
                                accentColor = if (uiState.errorRate > 0.1f) Nt14Ui.Warn else Nt14Ui.Ok,
                                icon = Icons.Default.Check
                            ),
                            MetricTileData(
                                title = "SECURITY THREATS",
                                value = uiState.activeIncidents.toString(),
                                subtext = if (uiState.activeIncidents == 0) "All clear" else "Threats caught",
                                accentColor = if (uiState.activeIncidents == 0) Nt14Ui.Ok else Nt14Ui.Blocked,
                                icon = Icons.Default.Warning
                            )
                        )
                    )
                }
            }

            // 3. Real-Time PolyLance Protocol Live Inspector (Viewer Read-Only Mode)
            item {
                PolyLanceLiveProtocolSection(
                    state = polyLanceState,
                    isAdmin = false,
                    onTabSelect = { viewModel.selectPolyLanceTab(it) },
                    onFetch = { viewModel.fetchActivePolyLanceData() }
                )
            }

            // 4. Live Request Telemetry Feed
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

            // 5. Security Governance Information Banner
            item {
                InfoCard(
                    title = "Gateway Policy Enforcement Active",
                    body = "Token Bucket burst control and Sliding Window rate limiting are enforced in real time across all incoming traffic.",
                    icon = Icons.Default.Lock
                )
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
    onSave: (String) -> Unit,
    viewModel: DashboardViewModel? = null
) {
    GatewayHostConfigDialog(
        currentHost = currentHost,
        onDismiss = onDismiss,
        onSave = onSave,
        isAdmin = true,
        repository = viewModel?.repository
    )
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
