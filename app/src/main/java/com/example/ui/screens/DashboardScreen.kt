package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ConnectionStep
import com.example.model.VpnServer
import com.example.ui.VpnUiState
import com.example.ui.components.AnimatedConnectButton
import com.example.ui.components.ConnectionPipelineBanner
import com.example.ui.components.HealthStatusBadge
import com.example.ui.components.ProtocolChip
import com.example.ui.components.TrustAndSecurityDialog
import com.example.ui.components.formatBytes
import com.example.ui.components.formatDuration
import com.example.ui.components.formatSpeed
import com.example.ui.theme.AmethystPurple
import com.example.ui.theme.CardBorderNavy
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricBlueGlow
import com.example.ui.theme.ElevatedNavy
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun DashboardScreen(
    uiState: VpnUiState,
    onConnectClick: () -> Unit,
    onAutoOptimalClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onDismissError: () -> Unit,
    onOpenVpnHandoff: (VpnServer) -> Unit,
    onDismissOpenVpnHandoff: () -> Unit,
    isOpenVpnInstalled: Boolean,
    modifier: Modifier = Modifier
) {
    var showTrustDialog by remember { mutableStateOf(false) }
    val conn = uiState.connectionState
    val displayServer = conn.activeServer ?: uiState.selectedServer

    if (showTrustDialog) {
        TrustAndSecurityDialog(onDismiss = { showTrustDialog = false })
    }

    conn.openVpnHandoffServer?.let { ovpnServer ->
        AlertDialog(
            onDismissRequest = onDismissOpenVpnHandoff,
            containerColor = DeepNavySurface,
            title = { Text("OpenVPN Profile Selected", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${ovpnServer.name} uses OpenVPN (${ovpnServer.transport}:${ovpnServer.port}).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isOpenVpnInstalled) {
                            "An OpenVPN client app is installed on your device. You can open this validated .ovpn profile in your OpenVPN client, or switch to a native in-app WireGuard server."
                        } else {
                            "In-app tunneling natively embeds WireGuard. No OpenVPN client app was detected on this device; install OpenVPN Connect / OpenVPN for Android to use .ovpn profiles, or tap Auto WireGuard for instant encrypted connection."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                if (isOpenVpnInstalled && ovpnServer.encryptedConfigPayload != null) {
                    Button(
                        onClick = {
                            onOpenVpnHandoff(ovpnServer)
                            onDismissOpenVpnHandoff()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Text("Open in OpenVPN")
                    }
                } else {
                    Button(
                        onClick = {
                            onDismissOpenVpnHandoff()
                            onAutoOptimalClick()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Text("Use Auto WireGuard")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissOpenVpnHandoff) {
                    Text("Close", color = TextSecondary)
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(ObsidianBlack, MidnightNavy, ObsidianBlack)
                )
            )
    ) {
        // Atmospheric network globe backdrop
        Image(
            painter = painterResource(id = R.drawable.img_network_globe_1794977199330),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
                .alpha(0.32f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ObsidianBlack.copy(alpha = 0.45f),
                            MidnightNavy.copy(alpha = 0.72f),
                            ObsidianBlack
                        )
                    )
                )
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 700.dp

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = if (isWide) 920.dp else 580.dp)
                ) {
                    // Header Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = ElectricBlue.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.4f))
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Shield,
                                    contentDescription = "Free Public VPN Shield",
                                    tint = CyanAccent,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Free Public VPN",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "WireGuard • DNS Protected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanAccent
                                )
                            }
                        }

                        IconButton(
                            onClick = { showTrustDialog = true },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("trust_info_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Security and public server trust info",
                                tint = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Connection Status Pill
                    ConnectionStatusBadge(
                        step = conn.step,
                        exitIp = conn.verifiedExitIp,
                        dnsIp = conn.dnsServerDisplay
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Multi-stage pipeline progress banner
                    ConnectionPipelineBanner(
                        step = conn.step,
                        detail = conn.statusDetail
                    )

                    // Error Callout
                    if (conn.step == ConnectionStep.ERROR && !conn.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = CoralContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, CoralError.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Connection error",
                                    tint = CoralError
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = conn.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissError) {
                                    Text("Dismiss", color = TextPrimary)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    if (isWide) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                AnimatedConnectButton(
                                    step = conn.step,
                                    onClick = onConnectClick
                                )
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1.3f)
                            ) {
                                ActiveServerCard(
                                    server = displayServer,
                                    isConnected = conn.isConnected,
                                    isBusy = conn.isBusy,
                                    onAutoBest = onAutoOptimalClick,
                                    onSwitchServer = onSwitchServerClick
                                )
                                TelemetryGrid(uiState = uiState)
                            }
                        }
                    } else {
                        AnimatedConnectButton(
                            step = conn.step,
                            onClick = onConnectClick
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        ActiveServerCard(
                            server = displayServer,
                            isConnected = conn.isConnected,
                            isBusy = conn.isBusy,
                            onAutoBest = onAutoOptimalClick,
                            onSwitchServer = onSwitchServerClick
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        TelemetryGrid(uiState = uiState)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Honest Unlimited & Privacy Footnote
                    Surface(
                        color = DeepNavySurface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CardBorderNavy),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = AmethystPurple,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "No artificial data cap or session timer. Real throughput & uptime depend on the selected public server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusBadge(
    step: ConnectionStep,
    exitIp: String?,
    dnsIp: String
) {
    val (bg, fg, label) = when (step) {
        ConnectionStep.CONNECTED -> Triple(
            EmeraldContainer.copy(alpha = 0.65f),
            EmeraldOnline,
            if (!exitIp.isNullOrBlank()) "CONNECTED • EXIT $exitIp • DNS $dnsIp" else "CONNECTED • ENCRYPTED • DNS $dnsIp"
        )
        ConnectionStep.ERROR -> Triple(
            CoralContainer.copy(alpha = 0.65f),
            CoralError,
            "DISCONNECTED • ERROR"
        )
        ConnectionStep.IDLE -> Triple(
            ElevatedNavy,
            TextSecondary,
            "DISCONNECTED • READY"
        )
        else -> Triple(
            ElectricBlue.copy(alpha = 0.18f),
            CyanAccent,
            step.title.uppercase()
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
        modifier = Modifier.testTag("connection_status_badge")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActiveServerCard(
    server: VpnServer?,
    isConnected: Boolean,
    isBusy: Boolean,
    onAutoBest: () -> Unit,
    onSwitchServer: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DeepNavySurface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    if (isConnected) EmeraldOnline.copy(alpha = 0.65f) else ElectricBlue.copy(alpha = 0.4f),
                    AmethystPurple.copy(alpha = 0.4f)
                )
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_server_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isConnected) "ACTIVE TUNNEL NODE" else "SELECTED PUBLIC SERVER",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                if (server != null) {
                    HealthStatusBadge(health = server.health, latencyMs = server.latencyMs)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (server != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.countryFlagEmoji,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${server.directorySource.displayName} • ${server.ipAddress}:${server.port}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    ProtocolChip(protocol = server.protocol, inAppSupported = server.inAppTunnelSupported)
                }
            } else {
                Text(
                    text = "Automatic Optimal Server Discovery",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap Connect to discover authorized public servers, probe live latency, and establish a WireGuard tunnel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = onAutoBest,
                    enabled = !isBusy && !isConnected,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = ElevatedNavy,
                        contentColor = CyanAccent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("auto_connect_best_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Auto Best", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onSwitchServer,
                    border = BorderStroke(1.dp, CardBorderNavy),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("switch_server_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SwapHoriz,
                        contentDescription = null,
                        tint = ElectricBlueGlow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Servers", color = TextPrimary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TelemetryGrid(uiState: VpnUiState) {
    val conn = uiState.connectionState
    val pingDisplay = conn.activeServer?.latencyMs?.let { "$it ms" }
        ?: uiState.selectedServer?.latencyMs?.let { "$it ms" }
        ?: "-- ms"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TelemetryStatCard(
                label = "DOWNLOAD",
                value = formatSpeed(conn.downloadBytesPerSec),
                subValue = "Rx ${formatBytes(conn.totalRxBytes)}",
                icon = Icons.Filled.ArrowDownward,
                accent = CyanAccent,
                modifier = Modifier
                    .weight(1f)
                    .testTag("telemetry_download")
            )
            TelemetryStatCard(
                label = "UPLOAD",
                value = formatSpeed(conn.uploadBytesPerSec),
                subValue = "Tx ${formatBytes(conn.totalTxBytes)}",
                icon = Icons.Filled.ArrowUpward,
                accent = AmethystPurple,
                modifier = Modifier
                    .weight(1f)
                    .testTag("telemetry_upload")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TelemetryStatCard(
                label = "TRANSFERRED",
                value = formatBytes(conn.totalRxBytes + conn.totalTxBytes),
                subValue = "Ping $pingDisplay",
                icon = Icons.Filled.SwapVert,
                accent = ElectricBlueGlow,
                modifier = Modifier
                    .weight(1f)
                    .testTag("telemetry_transferred")
            )
            TelemetryStatCard(
                label = "DURATION",
                value = formatDuration(conn.elapsedSeconds),
                subValue = if (conn.ipv6Active) "IPv4 + IPv6" else "IPv4 Tunnel",
                icon = Icons.Filled.Schedule,
                accent = EmeraldOnline,
                modifier = Modifier
                    .weight(1f)
                    .testTag("telemetry_duration")
            )
        }
    }
}

@Composable
private fun TelemetryStatCard(
    label: String,
    value: String,
    subValue: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DeepNavySurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorderNavy),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
