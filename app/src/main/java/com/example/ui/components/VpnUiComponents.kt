package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ConnectionStep
import com.example.model.ServerHealth
import com.example.model.VpnProtocol
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmethystPurple
import com.example.ui.theme.CardBorderNavy
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.DeepPurple
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricBlueGlow
import com.example.ui.theme.ElevatedNavy
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytesPerSec >= mb -> String.format("%.2f MB/s", bytesPerSec / mb)
        bytesPerSec >= kb -> String.format("%.1f KB/s", bytesPerSec / kb)
        else -> "$bytesPerSec B/s"
    }
}

fun formatDuration(totalSeconds: Long): String {
    val hrs = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hrs, mins, secs)
}

fun formatBandwidthMbps(bps: Long): String {
    if (bps <= 0L) return "N/A"
    val mbps = bps / 1_000_000.0
    return if (mbps >= 1.0) String.format("%.1f Mbps", mbps) else String.format("%.0f Kbps", bps / 1000.0)
}

@Composable
fun AnimatedConnectButton(
    step: ConnectionStep,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = step == ConnectionStep.CONNECTED
    val isBusy = step.isTransitioning

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pressScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "vpnPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isBusy) 1400 else 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress"
    )

    val ringColor by animateColorAsState(
        targetValue = when {
            isConnected -> EmeraldOnline
            isBusy -> AmethystPurple
            step == ConnectionStep.ERROR -> CoralError
            else -> ElectricBlue
        },
        animationSpec = tween(350),
        label = "ringColor"
    )

    val coreGradient = when {
        isConnected -> Brush.linearGradient(listOf(EmeraldOnline, Color(0xFF059669), CyanAccent))
        isBusy -> Brush.linearGradient(listOf(ElectricBlue, AmethystPurple))
        step == ConnectionStep.ERROR -> Brush.linearGradient(listOf(CoralError, Color(0xFF991B1B)))
        else -> Brush.linearGradient(listOf(ElectricBlue, DeepPurple))
    }

    val buttonLabel = when {
        isConnected -> "DISCONNECT"
        isBusy -> "CANCEL"
        step == ConnectionStep.ERROR -> "RETRY"
        else -> "CONNECT"
    }

    val buttonIcon = when {
        isConnected -> Icons.Filled.GppGood
        isBusy -> Icons.Filled.Sync
        else -> Icons.Filled.PowerSettingsNew
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(214.dp)
            .scale(pressScale)
            .drawBehind {
                val center = this.center
                val maxRadius = size.minDimension / 2f

                // Outer animated pulse ring
                val ringRadius = maxRadius * (0.76f + 0.24f * pulseProgress)
                val ringAlpha = (1f - pulseProgress) * if (isBusy || isConnected) 0.55f else 0.28f
                drawCircle(
                    color = ringColor.copy(alpha = ringAlpha),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )

                // Ambient glow halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ringColor.copy(alpha = if (isConnected) 0.34f else 0.24f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxRadius
                    ),
                    radius = maxRadius,
                    center = center
                )
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(156.dp)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(ElevatedNavy, ObsidianBlack)
                    )
                )
                .border(
                    width = 2.dp,
                    brush = coreGradient,
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.material3.ripple(bounded = true, color = ringColor),
                    onClick = onClick
                )
                .testTag("connect_button")
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(134.dp)
                    .clip(CircleShape)
                    .background(brush = coreGradient)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = buttonIcon,
                        contentDescription = buttonLabel,
                        tint = TextPrimary,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = buttonLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionPipelineBanner(
    step: ConnectionStep,
    detail: String?,
    modifier: Modifier = Modifier
) {
    val steps = listOf(
        ConnectionStep.DISCOVERING_SERVERS to "Discover",
        ConnectionStep.PROBING_LATENCY to "Ping",
        ConnectionStep.PROVISIONING_KEYS to "Keys",
        ConnectionStep.ESTABLISHING_TUNNEL to "Tunnel",
        ConnectionStep.VERIFYING_CONNECTIVITY to "Verify"
    )
    val currentIndex = steps.indexOfFirst { it.first == step }

    AnimatedVisibility(
        visible = step.isTransitioning && step != ConnectionStep.DISCONNECTING,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            color = ElevatedNavy.copy(alpha = 0.9f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AmethystPurple.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                if (!detail.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (currentIndex >= 0) (currentIndex + 1) / steps.size.toFloat() else 0.5f
                    },
                    color = CyanAccent,
                    trackColor = CardBorderNavy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    steps.forEachIndexed { idx, (_, label) ->
                        val active = idx <= currentIndex
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) CyanAccent else TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProtocolChip(protocol: VpnProtocol, inAppSupported: Boolean) {
    val bgColor = when {
        protocol == VpnProtocol.WIREGUARD && inAppSupported -> ElectricBlue.copy(alpha = 0.18f)
        protocol == VpnProtocol.WIREGUARD -> AmethystPurple.copy(alpha = 0.18f)
        else -> AmethystPurple.copy(alpha = 0.18f)
    }
    val textColor = when {
        protocol == VpnProtocol.WIREGUARD && inAppSupported -> ElectricBlueGlow
        else -> Color(0xFFC4B5FD)
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.35f))
    ) {
        Text(
            text = if (protocol == VpnProtocol.OPENVPN) "OpenVPN" else "WireGuard",
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun HealthStatusBadge(health: ServerHealth, latencyMs: Int?) {
    val (bg, fg, text) = when (health) {
        ServerHealth.ONLINE -> Triple(
            EmeraldContainer.copy(alpha = 0.6f),
            EmeraldOnline,
            if (latencyMs != null) "${latencyMs} ms" else "Online"
        )
        ServerHealth.DEGRADED -> Triple(
            AmberContainer.copy(alpha = 0.6f),
            AmberWarning,
            if (latencyMs != null) "${latencyMs} ms" else "High Load"
        )
        ServerHealth.UNREACHABLE -> Triple(
            CoralContainer.copy(alpha = 0.6f),
            CoralError,
            "Unreachable"
        )
        ServerHealth.UNTESTED -> Triple(
            DeepNavySurface,
            TextSecondary,
            if (latencyMs != null) "~${latencyMs} ms" else "Untested"
        )
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(fg)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}

@Composable
fun TrustAndSecurityDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeepNavySurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        icon = {
            Icon(
                imageVector = Icons.Filled.VerifiedUser,
                contentDescription = null,
                tint = CyanAccent,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Security, Trust & Public Servers",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                TrustBullet(
                    title = "1. Authorized Public Directories Only",
                    body = "Servers are discovered strictly from documented public networks (Cloudflare WARP public registration, VPNGate University of Tsukuba academic relays, RiseupVPN LEAP EIP) or profiles you explicitly import."
                )
                TrustBullet(
                    title = "2. Real Tunnel Encryption & DNS Protection",
                    body = "WireGuard tunnels use Curve25519 + ChaCha20-Poly1305 inside Android VpnService and force DNS resolution through your selected protected resolver to prevent DNS leaks."
                )
                TrustBullet(
                    title = "3. Encrypted Keystore Storage",
                    body = "Private keys and imported profiles are encrypted with AES-256-GCM backed by AndroidKeyStore and never displayed in plain text."
                )
                TrustBullet(
                    title = "4. Public Server Trust Limitations",
                    body = "Free public relays are operated by third-party volunteers and providers. Inspect each server's Operator and Log Policy before sending sensitive traffic. Unlimited app usage means no app-imposed cap or timer; actual speed depends on each server's bandwidth."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Understood", color = ElectricBlueGlow)
            }
        }
    )
}

@Composable
private fun TrustBullet(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}
