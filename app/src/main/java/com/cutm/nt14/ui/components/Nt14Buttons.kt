package com.cutm.nt14.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens: one accent, soft neutral fills, status colours. Replace values with your theme. */
object Nt14Ui {
    val Accent = Color(0xFF0B4DB8)
    val TileFill = Color(0xFFEEF2F8)
    val Outline = Color(0xFFC9D3E3)
    val TextPrimary = Color(0xFF16202E)
    val TextMuted = Color(0xFF5B677A)
    val Ok = Color(0xFF1B9E5A)
    val Warn = Color(0xFFE08A00)
    val Blocked = Color(0xFFD62F2F)
}

/** 1. Pill button (like "Scan QR"): full-round, tinted fill, no border, bold label + trailing icon. */
@Composable
fun PillActionButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        color = Nt14Ui.TileFill,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Nt14Ui.TextPrimary)
            Icon(icon, contentDescription = null, tint = Nt14Ui.TextPrimary)
        }
    }
}

/**
 * 2. Round service button (like "Recharge", "Pay Bills"): white circle, soft glow, outline icon,
 * label underneath. Optional status ring: progress arc (e.g. bans / threshold) or a gradient ring (featured/new).
 */
@Composable
fun RingActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ringProgress: Float? = null,                       // 0f..1f draws a partial arc; null = no ring
    ringBrush: Brush = SolidColor(Nt14Ui.Accent),
    enabled: Boolean = true,
) {
    Column(
        modifier
            .width(84.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .shadow(6.dp, CircleShape, ambientColor = Nt14Ui.Accent.copy(alpha = 0.15f), spotColor = Nt14Ui.Accent.copy(alpha = 0.15f))
                .background(Color.White, CircleShape)
                .drawBehind {
                    if (ringProgress != null) {
                        val w = 3.dp.toPx()
                        drawArc(
                            brush = ringBrush,
                            startAngle = -90f,
                            sweepAngle = 360f * ringProgress.coerceIn(0f, 1f),
                            useCenter = false,
                            topLeft = Offset(w / 2, w / 2),
                            size = Size(size.width - w, size.height - w),
                            style = Stroke(width = w, cap = StrokeCap.Round),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) Nt14Ui.TextPrimary else Nt14Ui.TextMuted, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, color = Nt14Ui.TextPrimary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * 3. Filter tile (like the All / Wi-Fi / Postpaid row): big rounded square, label OUTSIDE the tile.
 * Selected = white fill + thin outline + bold label + short accent underline.
 */
@Composable
fun FilterTile(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    Column(modifier.width(88.dp).clickable(role = Role.Tab, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (selected) Color.White else Nt14Ui.TileFill,
            border = if (selected) BorderStroke(1.5.dp, Nt14Ui.Outline) else null,
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Nt14Ui.TextPrimary, modifier = Modifier.size(30.dp))
                if (badge != null) {
                    Text(badge, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Nt14Ui.Accent, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = Nt14Ui.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(28.dp).height(3.dp).background(if (selected) Nt14Ui.Accent else Color.Transparent, RoundedCornerShape(2.dp)))
    }
}

/** 4. Gradient card button (like "Fast Lane Postpaid"): whole card is the button, title bottom-left, arrow bottom-right. */
@Composable
fun GradientCardButton(title: String, colors: List<Color>, onClick: () -> Unit, modifier: Modifier = Modifier, heroIcon: ImageVector? = null) {
    Box(
        modifier
            .height(150.dp)
            .background(Brush.linearGradient(colors), RoundedCornerShape(24.dp))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.12f), radius = size.width * 0.45f, center = Offset(size.width * 0.72f, size.height * 0.2f))
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
    ) {
        if (heroIcon != null) {
            Icon(heroIcon, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).size(40.dp))
        }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.7f))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** Example: NT14 home quick actions. Ring values should come from live gateway state, not constants. */
@Composable
fun QuickActionsRow(bansRatio: Float, onSimulate: () -> Unit, onAddRule: () -> Unit, onBans: () -> Unit, onReconnect: () -> Unit, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        RingActionButton("Simulate traffic", Icons.Filled.PlayArrow, onSimulate)
        RingActionButton("Add rule", Icons.Filled.Add, onAddRule)
        RingActionButton("Bans", Icons.Filled.Lock, onBans, ringProgress = bansRatio, ringBrush = SolidColor(Nt14Ui.Blocked))
        RingActionButton("Reconnect", Icons.Filled.Refresh, onReconnect)
        RingActionButton("Export", Icons.Filled.Share, onExport)
    }
}
