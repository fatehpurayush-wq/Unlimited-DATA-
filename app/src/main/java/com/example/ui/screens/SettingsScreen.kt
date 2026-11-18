package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.DnsMode
import com.example.model.ProtocolPreference
import com.example.model.SplitTunnelMode
import com.example.ui.VpnUiState
import com.example.ui.theme.AmethystPurple
import com.example.ui.theme.CardBorderNavy
import com.example.ui.theme.ColorTokens
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricBlueGlow
import com.example.ui.theme.ElevatedNavy
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    uiState: VpnUiState,
    onAutoConnectChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onProtocolChange: (ProtocolPreference) -> Unit,
    onDnsModeChange: (DnsMode) -> Unit,
    onCustomDnsSave: (String) -> Unit,
    onIpv6Change: (Boolean) -> Unit,
    onSplitTunnelModeChange: (SplitTunnelMode) -> Unit,
    onLoadInstalledApps: () -> Unit,
    onToggleSplitTunnelApp: (String) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onResetKeys: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = uiState.preferences

    var customDnsDraft by remember(prefs.customDnsIp) { mutableStateOf(prefs.customDnsIp) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            containerColor = DeepNavySurface,
            title = {
                Text("Split Tunneling App Selection", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            },
            text = {
                Column {
                    Text(
                        text = when (prefs.splitTunnelMode) {
                            SplitTunnelMode.EXCLUDE_SELECTED -> "Checked apps will bypass the encrypted VPN tunnel."
                            SplitTunnelMode.INCLUDE_ONLY_SELECTED -> "Only checked apps will route through the VPN tunnel."
                            SplitTunnelMode.DISABLED -> "Enable Bypass or Include mode to apply per-app rules."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (uiState.installedApps.isEmpty()) {
                        Text(
                            text = "Loading launchable apps…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(uiState.installedApps, key = { it.packageName }) { app ->
                                val checked = app.packageName in prefs.splitTunnelPackages
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleSplitTunnelApp(app.packageName) }
                                        .minimumInteractiveComponentSize()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { onToggleSplitTunnelApp(app.packageName) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = ElectricBlue,
                                            uncheckedColor = TextMuted
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(app.appName, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("Done", color = ElectricBlueGlow)
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = DeepNavySurface,
            title = { Text("Reset Encrypted Keys?", color = TextPrimary) },
            text = {
                Text(
                    text = "This wipes the current AndroidKeyStore encryption key, clears cached directory credentials, and registers fresh WireGuard keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        onResetKeys()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralError)
                ) {
                    Text("Reset Keys")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBlack, MidnightNavy, ObsidianBlack)))
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .widthIn(max = 780.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Settings & Security",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )

            // Connection Automation
            SettingsSectionCard(title = "Connection & Resilience", icon = Icons.Filled.Tune) {
                SettingsSwitchRow(
                    title = "Auto-connect on launch",
                    subtitle = "Automatically connect when VPN permission is already granted",
                    checked = prefs.autoConnect,
                    onCheckedChange = onAutoConnectChange,
                    testTag = "switch_auto_connect"
                )
                HorizontalDivider(color = ColorTokens.SubtleDivider)
                SettingsSwitchRow(
                    title = "Auto-reconnect on network drop",
                    subtitle = "Re-establish tunnel if Wi-Fi/cellular switches or drops",
                    checked = prefs.autoReconnect,
                    onCheckedChange = onAutoReconnectChange,
                    testTag = "switch_auto_reconnect"
                )
                HorizontalDivider(color = ColorTokens.SubtleDivider)
                SettingsSwitchRow(
                    title = "IPv6 tunnel support",
                    subtitle = "Route ::/0 IPv6 traffic through WireGuard when server supports IPv6",
                    checked = prefs.ipv6Enabled,
                    onCheckedChange = onIpv6Change,
                    testTag = "switch_ipv6"
                )
            }

            // Protocol Selection
            SettingsSectionCard(title = "VPN Protocol Selection", icon = Icons.Filled.Security) {
                ProtocolPreference.entries.forEach { pref ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProtocolChange(pref) }
                            .minimumInteractiveComponentSize()
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = prefs.protocolPreference == pref,
                            onClick = { onProtocolChange(pref) },
                            colors = RadioButtonDefaults.colors(selectedColor = CyanAccent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(pref.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                }
            }

            // DNS Protection
            SettingsSectionCard(title = "DNS Leak Protection", icon = Icons.Filled.Dns) {
                Text(
                    text = "Forces all DNS queries inside the encrypted tunnel to prevent ISP/local network DNS leaks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                DnsMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDnsModeChange(mode) }
                            .minimumInteractiveComponentSize()
                            .padding(vertical = 3.dp)
                    ) {
                        RadioButton(
                            selected = prefs.dnsMode == mode,
                            onClick = { onDnsModeChange(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = ElectricBlueGlow)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                }
                if (prefs.dnsMode == DnsMode.CUSTOM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = customDnsDraft,
                            onValueChange = { customDnsDraft = it },
                            label = { Text("Custom DNS IP (e.g. 9.9.9.9)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricBlue,
                                unfocusedBorderColor = CardBorderNavy,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { onCustomDnsSave(customDnsDraft) },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            // Split Tunneling
            SettingsSectionCard(title = "Split Tunneling", icon = Icons.Filled.Apps) {
                SplitTunnelMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSplitTunnelModeChange(mode) }
                            .minimumInteractiveComponentSize()
                            .padding(vertical = 3.dp)
                    ) {
                        RadioButton(
                            selected = prefs.splitTunnelMode == mode,
                            onClick = { onSplitTunnelModeChange(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = AmethystPurple)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                }
                if (prefs.splitTunnelMode != SplitTunnelMode.DISABLED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            onLoadInstalledApps()
                            showAppPicker = true
                        },
                        border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                    ) {
                        Text(
                            text = "Select Apps (${prefs.splitTunnelPackages.size} selected)",
                            color = CyanAccent
                        )
                    }
                }
            }

            // Kill Switch Guidance
            SettingsSectionCard(title = "Kill Switch Guidance", icon = Icons.Filled.Policy) {
                Text(
                    text = "Android provides OS-level Kill Switch enforcement that blocks all non-VPN traffic even before apps start:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "1. Tap Open Android VPN Settings below.\n2. Tap the gear icon next to Free Public VPN.\n3. Enable \"Always-on VPN\" and \"Block connections without VPN\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }
                    },
                    border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("open_system_vpn_settings_button")
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Android VPN Settings", color = CyanAccent)
                }
            }

            // Notifications & Keystore Security
            SettingsSectionCard(title = "Notifications & Cryptographic Keys", icon = Icons.Filled.Notifications) {
                SettingsSwitchRow(
                    title = "Connection status notifications",
                    subtitle = "Show persistent foreground tunnel status & live speed indicator",
                    checked = prefs.notificationsEnabled,
                    onCheckedChange = onNotificationsChange,
                    testTag = "switch_notifications"
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    border = BorderStroke(1.dp, CoralError.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Key, contentDescription = null, tint = CoralError, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Regenerate Keystore & WireGuard Keys", color = CoralError)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        color = DeepNavySurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CardBorderNavy),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .minimumInteractiveComponentSize()
            .padding(vertical = 6.dp)
            .testTag(testTag)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextPrimary,
                checkedTrackColor = ElectricBlue,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = ElevatedNavy
            )
        )
    }
}
