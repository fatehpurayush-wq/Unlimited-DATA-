package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.ui.ServerFilter
import com.example.ui.VpnUiState
import com.example.ui.components.HealthStatusBadge
import com.example.ui.components.ProtocolChip
import com.example.ui.components.formatBandwidthMbps
import com.example.ui.theme.AmethystPurple
import com.example.ui.theme.CardBorderNavy
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    uiState: VpnUiState,
    onRefreshDirectories: () -> Unit,
    onProbeAllLatencies: () -> Unit,
    onProbeSingle: (VpnServer) -> Unit,
    onSelectFilter: (ServerFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleFavorite: (VpnServer) -> Unit,
    onConnectToServer: (VpnServer) -> Unit,
    onAutoBestClick: () -> Unit,
    onImportProfile: (String, String) -> Unit,
    onDeleteImported: (VpnServer) -> Unit,
    onInspectServer: (VpnServer?) -> Unit,
    getRedactedPreview: (VpnServer) -> String?,
    onOpenInOpenVpn: (VpnServer) -> Unit,
    isOpenVpnInstalled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importName by remember { mutableStateOf("") }
    var importContent by remember { mutableStateOf("") }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { text ->
                importContent = text
                if (importName.isBlank()) {
                    importName = uri.lastPathSegment?.substringAfterLast("/") ?: "Imported Config"
                }
                showImportDialog = true
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = DeepNavySurface,
            title = {
                Text(
                    text = "Import WireGuard / OpenVPN Config",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Configurations are validated and encrypted with AndroidKeyStore before storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedButton(
                        onClick = { openDocLauncher.launch(arrayOf("*/*")) },
                        border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = CyanAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pick .conf or .ovpn File", color = TextPrimary)
                    }
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it },
                        label = { Text("Profile Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = CardBorderNavy,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = importContent,
                        onValueChange = { importContent = it },
                        label = { Text("Paste .conf or .ovpn Content") },
                        minLines = 5,
                        maxLines = 8,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            unfocusedBorderColor = CardBorderNavy,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportProfile(importName, importContent)
                        showImportDialog = false
                        importName = ""
                        importContent = ""
                    },
                    enabled = importContent.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text("Validate & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    uiState.inspectedServer?.let { server ->
        val redactedPreview = remember(server.id, server.encryptedConfigPayload) {
            getRedactedPreview(server)
        }
        ModalBottomSheet(
            onDismissRequest = { onInspectServer(null) },
            containerColor = MidnightNavy
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.countryFlagEmoji, style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(
                            text = "${server.directorySource.displayName} • ${server.countryName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    ProtocolChip(server.protocol, server.inAppTunnelSupported)
                }

                Surface(
                    color = DeepNavySurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CardBorderNavy),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        DetailRow("Endpoint", "${server.ipAddress}:${server.port} (${server.transport})")
                        DetailRow("Host", server.host)
                        DetailRow("Operator", server.operatorName.ifBlank { "Public Directory Node" })
                        DetailRow("Log Policy", server.logPolicy)
                        DetailRow("Measured Ping", server.latencyMs?.let { "$it ms" } ?: "Not probed / Unreachable")
                        if (server.bandwidthBps > 0L) {
                            DetailRow("Reported Capacity", formatBandwidthMbps(server.bandwidthBps))
                        }
                        if (!server.peerPublicKey.isNullOrBlank()) {
                            DetailRow("Peer Public Key", server.peerPublicKey.take(24) + "…")
                        }
                    }
                }

                if (server.trustNotice.isNotBlank()) {
                    Text(
                        text = server.trustNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                if (!redactedPreview.isNullOrBlank()) {
                    Text(
                        text = "Sanitized Config Preview (Private Keys Redacted)",
                        style = MaterialTheme.typography.labelLarge,
                        color = CyanAccent
                    )
                    Surface(
                        color = ObsidianBlack,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CardBorderNavy),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = redactedPreview.lines().take(16).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { onProbeSingle(server) },
                        border = BorderStroke(1.dp, CardBorderNavy),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Filled.NetworkPing, contentDescription = null, tint = CyanAccent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Ping", color = TextPrimary)
                    }

                    if (server.inAppTunnelSupported) {
                        Button(
                            onClick = {
                                onInspectServer(null)
                                onConnectToServer(server)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                        ) {
                            Text("Connect Now")
                        }
                    } else if (server.protocol == VpnProtocol.OPENVPN && isOpenVpnInstalled && server.encryptedConfigPayload != null) {
                        Button(
                            onClick = {
                                onInspectServer(null)
                                onOpenInOpenVpn(server)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmethystPurple),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                        ) {
                            Text("Open in OpenVPN")
                        }
                    }
                }

                if (server.isImported) {
                    TextButton(
                        onClick = { onDeleteImported(server) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = CoralError)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete Imported Profile", color = CoralError)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ObsidianBlack, MidnightNavy, ObsidianBlack)))
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 820.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header + Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Public VPN Directory",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "${uiState.servers.size} authorized nodes discovered",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("import_config_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Import VPN configuration", tint = CyanAccent)
                    }
                    IconButton(
                        onClick = onProbeAllLatencies,
                        enabled = !uiState.isProbingLatencies,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("test_latency_button")
                    ) {
                        if (uiState.isProbingLatencies) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanAccent)
                        } else {
                            Icon(Icons.Filled.NetworkPing, contentDescription = "Test server latencies", tint = ElectricBlueGlow)
                        }
                    }
                    IconButton(
                        onClick = onRefreshDirectories,
                        enabled = !uiState.isRefreshingDirectories,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_servers_button")
                    ) {
                        if (uiState.isRefreshingDirectories) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AmethystPurple)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh public directories", tint = TextPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search country, host, or directory…", color = TextMuted) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DeepNavySurface,
                    unfocusedContainerColor = DeepNavySurface,
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = CardBorderNavy,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_search_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                ServerFilter.entries.forEach { filter ->
                    val selected = uiState.serverFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = DeepNavySurface,
                            labelColor = TextSecondary,
                            selectedContainerColor = ElectricBlue.copy(alpha = 0.24f),
                            selectedLabelColor = CyanAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = CardBorderNavy,
                            selectedBorderColor = ElectricBlue
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val listItems = uiState.filteredServers
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "auto_optimal_banner") {
                    Surface(
                        color = ElevatedNavy,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.6f), AmethystPurple.copy(alpha = 0.6f)))
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onAutoBestClick)
                            .minimumInteractiveComponentSize()
                            .testTag("auto_select_server_card")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CyanAccent)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Automatic Optimal Server",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Auto-probes live WireGuard nodes & connects to fastest verified relay",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                if (listItems.isEmpty()) {
                    item(key = "empty_servers") {
                        Surface(
                            color = DeepNavySurface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, CardBorderNavy),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = if (uiState.isRefreshingDirectories) "Discovering public directories…" else "No matching servers found",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap Refresh to query Cloudflare WARP, VPNGate, and RiseupVPN public directories, or import your own .conf / .ovpn profile.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onRefreshDirectories,
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                                ) {
                                    Text("Discover Public Servers")
                                }
                            }
                        }
                    }
                } else {
                    items(items = listItems, key = { it.id }) { server ->
                        val isSelected = uiState.selectedServer?.id == server.id
                        ServerListCard(
                            server = server,
                            isSelected = isSelected,
                            onCardClick = { onInspectServer(server) },
                            onToggleFavorite = { onToggleFavorite(server) },
                            onQuickConnect = { onConnectToServer(server) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerListCard(
    server: VpnServer,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onQuickConnect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) ElevatedNavy else DeepNavySurface
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) ElectricBlue else CardBorderNavy
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
            .testTag("server_card_${server.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = server.countryFlagEmoji,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${server.directorySource.displayName} • ${server.ipAddress}:${server.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (server.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (server.isFavorite) "Remove favorite" else "Add favorite",
                        tint = if (server.isFavorite) CyanAccent else TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProtocolChip(server.protocol, server.inAppTunnelSupported)
                    HealthStatusBadge(server.health, server.latencyMs)
                    if (server.activeSessions > 0) {
                        Text(
                            text = "${server.activeSessions} peers",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                TextButton(
                    onClick = onQuickConnect,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = if (server.inAppTunnelSupported) "Connect" else "OpenVPN",
                        color = if (server.inAppTunnelSupported) ElectricBlueGlow else AmethystPurple
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
