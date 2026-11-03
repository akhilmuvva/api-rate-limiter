package com.cutm.nt14.ui.reports

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cutm.nt14.ui.components.*
import com.cutm.nt14.ui.monitoring.DataFreshnessBanner
import com.cutm.nt14.ui.monitoring.LatencySparkline
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReportScreen(
    viewModel: ReportViewModel = hiltViewModel()
) {
    val report by viewModel.report.collectAsState()
    val metricsSeries by viewModel.metricsSeries.collectAsState()
    val selectedRange by viewModel.selectedRange.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val lastSyncMs by viewModel.lastDataSyncMs.collectAsState()
    val persistenceMode by viewModel.persistenceMode.collectAsState()
    val context = LocalContext.current

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
                            text = "ANALYTICS & AUDIT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyPrimary,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Traffic Reports",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextPrimary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillActionButton(
                            text = "Export CSV",
                            icon = Icons.Default.Share,
                            onClick = {
                                viewModel.exportCsv { csv ->
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, csv)
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "Export Traffic Audit CSV")
                                    context.startActivity(shareIntent)
                                }
                            }
                        )

                        IconButton(
                            onClick = { viewModel.loadReport() },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(PolyPrimaryLight)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = PolyPrimary,
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp
                )
            ) {
                // Range Selector Bar using FilterTile
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DataFreshnessBanner(lastSyncMs = lastSyncMs, persistenceMode = persistenceMode)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("1h" to "1 Hour", "24h" to "24 Hours", "7d" to "7 Days").forEach { (rng, label) ->
                                val isSel = selectedRange == rng
                                FilterTile(
                                    label = label,
                                    icon = Icons.Default.Info,
                                    selected = isSel,
                                    badge = rng.uppercase(),
                                    onClick = { viewModel.selectRange(rng) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("all" to "All Sources", "real" to "Real Only", "demo" to "Demo Only").forEach { (src, lbl) ->
                                FilterChip(
                                    selected = selectedSource == src,
                                    onClick = { viewModel.selectSource(src) },
                                    label = { Text(lbl, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                )
                            }
                        }
                    }
                }

                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PolyPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                } else if (report == null) {
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
                                Icon(Icons.Default.Info, contentDescription = null, tint = PolyTextMuted, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("No Report Generated", fontWeight = FontWeight.Bold, color = PolyTextPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Awaiting server aggregation. Tap below to fetch.", fontSize = 12.sp, color = PolyTextSecondary)
                                Spacer(modifier = Modifier.height(12.dp))
                                CardPillButton(
                                    text = "Fetch Report",
                                    icon = Icons.Default.Refresh,
                                    containerColor = Nt14Ui.Accent,
                                    contentColor = Color.White,
                                    borderColor = null,
                                    onClick = { viewModel.loadReport() }
                                )
                            }
                        }
                    }
                } else {
                    val rep = report!!
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US)

                    // 1. Executive Summary Card
                    item {
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
                                        text = "AGGREGATE SUMMARY (${rep.range.uppercase()} • ${rep.source.uppercase()} • ${rep.persistence.uppercase()})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PolyPrimary,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        text = "Generated ${dateFormat.format(Date(rep.generatedAt))}",
                                        fontSize = 10.sp,
                                        color = PolyTextMuted,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "TOTAL REQUESTS",
                                        value = rep.totalRequests.toString(),
                                        color = PolyTextPrimary
                                    )
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "ALLOWED",
                                        value = rep.allowedRequests.toString(),
                                        color = PolySuccess
                                    )
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "BLOCKED",
                                        value = rep.blockedRequests.toString(),
                                        color = if (rep.blockedRequests > 0) PolyDanger else PolyTextSecondary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "THROTTLE RATE",
                                        value = "%.1f%%".format(rep.throttleRate * 100),
                                        color = if (rep.throttleRate > 0) PolyWarning else PolySuccess
                                    )
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "5XX ERROR RATE",
                                        value = "%.1f%% (${rep.serverErrors})".format(rep.errorRate * 100),
                                        color = if (rep.serverErrors > 0) PolyDanger else PolySuccess
                                    )
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "UPSTREAM P50/P95",
                                        value = "${rep.p50UpstreamMs}/${rep.p95UpstreamMs}ms",
                                        color = PolyPrimary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "LIMITER P50/P95",
                                        value = "${rep.p50LimiterMs}/${rep.p95LimiterMs}ms",
                                        color = PolyPrimary
                                    )
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "TOTAL P50/P95",
                                        value = "${rep.p50TotalMs}/${rep.p95LatencyMs}ms",
                                        color = PolyPurple
                                    )
                                    ReportStatTile(
                                        modifier = Modifier.weight(1f),
                                        title = "PEAK RPS",
                                        value = "${rep.peakRps} rps",
                                        color = PolyCyan
                                    )
                                }
                            }
                        }
                    }

                    // 2. Top Endpoints Breakdown
                    item {
                        Text(
                            text = "TOP TARGET ENDPOINTS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextSecondary,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White.copy(alpha = 0.94f)
                        ) {
                            if (rep.topEndpoints.isEmpty()) {
                                Text("No endpoint records in this window", fontSize = 12.sp, color = PolyTextMuted)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rep.topEndpoints.entries.sortedByDescending { it.value }.take(5).forEach { (ep, count) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = ep,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = PolyTextPrimary
                                            )
                                            GlassBadge(text = "$count reqs", color = PolyPrimary)
                                        }
                                    }
                                    if (metricsSeries != null && metricsSeries!!.points.isNotEmpty()) {
                                        val pts = metricsSeries!!.points
                                        Text(
                                            text = "P95 LATENCY ROLLUP SPARKLINE (metrics_1m: ${pts.size} buckets)",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PolyTextMuted
                                        )
                                        LatencySparkline(
                                            latencies = pts.map { it.p95.toLong() },
                                            upFlags = pts.map { it.errors == 0L },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(42.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFF8FAFC))
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Top Client IPs Breakdown
                    item {
                        Text(
                            text = "TOP CLIENT IDENTIFIERS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PolyTextSecondary,
                            letterSpacing = 1.sp
                        )
                    }

                    item {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color.White.copy(alpha = 0.94f)
                        ) {
                            if (rep.topClients.isEmpty()) {
                                Text("No client records in this window", fontSize = 12.sp, color = PolyTextMuted)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rep.topClients.entries.sortedByDescending { it.value }.take(5).forEach { (client, count) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = client,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = PolyTextPrimary
                                            )
                                            GlassBadge(text = "$count reqs", color = PolyPurple)
                                        }
                                    }
                                    if (metricsSeries != null && metricsSeries!!.points.isNotEmpty()) {
                                        val pts = metricsSeries!!.points
                                        Text(
                                            text = "P95 LATENCY ROLLUP SPARKLINE (metrics_1m: ${pts.size} buckets)",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PolyTextMuted
                                        )
                                        LatencySparkline(
                                            latencies = pts.map { it.p95.toLong() },
                                            upFlags = pts.map { it.errors == 0L },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(42.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFF8FAFC))
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportStatTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    color: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF8FAFC))
            .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        Text(text = title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PolyTextSecondary, letterSpacing = 0.5.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
