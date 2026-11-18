package com.example.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.FreePublicVpnApp
import com.example.data.VpnServerRepository
import com.example.data.local.SettingsRepository
import com.example.data.network.DirectoryFetchResult
import com.example.data.security.VpnConfigValidator
import com.example.model.DnsMode
import com.example.model.InstalledAppInfo
import com.example.model.ProtocolPreference
import com.example.model.SplitTunnelMode
import com.example.model.UserPreferences
import com.example.model.VpnConnectionState
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.vpn.VpnConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ServerFilter(val label: String) {
    ALL("All"),
    WIREGUARD("WireGuard"),
    OPENVPN("OpenVPN"),
    FAVORITES("Favorites"),
    IMPORTED("Imported")
}

data class VpnUiState(
    val servers: List<VpnServer> = emptyList(),
    val connectionState: VpnConnectionState = VpnConnectionState(),
    val preferences: UserPreferences = UserPreferences(),
    val selectedServer: VpnServer? = null,
    val isRefreshingDirectories: Boolean = false,
    val isProbingLatencies: Boolean = false,
    val directoryStatuses: List<DirectoryFetchResult> = emptyList(),
    val serverFilter: ServerFilter = ServerFilter.ALL,
    val searchQuery: String = "",
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val inspectedServer: VpnServer? = null,
    val snackbarMessage: String? = null
) {
    val filteredServers: List<VpnServer>
        get() {
            val q = searchQuery.trim().lowercase()
            return servers.filter { server ->
                val matchesFilter = when (serverFilter) {
                    ServerFilter.ALL -> true
                    ServerFilter.WIREGUARD -> server.protocol == VpnProtocol.WIREGUARD
                    ServerFilter.OPENVPN -> server.protocol == VpnProtocol.OPENVPN
                    ServerFilter.FAVORITES -> server.isFavorite
                    ServerFilter.IMPORTED -> server.isImported
                }
                val matchesQuery = q.isEmpty() ||
                    server.name.lowercase().contains(q) ||
                    server.countryName.lowercase().contains(q) ||
                    server.countryCode.lowercase().contains(q) ||
                    server.directorySource.displayName.lowercase().contains(q) ||
                    server.host.lowercase().contains(q)
                matchesFilter && matchesQuery
            }
        }
}

class VpnViewModel(
    private val appContext: Context,
    private val repository: VpnServerRepository,
    private val settingsRepository: SettingsRepository,
    private val controller: VpnConnectionController
) : ViewModel() {

    private data class AuxiliaryState(
        val isRefreshingDirectories: Boolean = false,
        val isProbingLatencies: Boolean = false,
        val directoryStatuses: List<DirectoryFetchResult> = emptyList(),
        val serverFilter: ServerFilter = ServerFilter.ALL,
        val searchQuery: String = "",
        val installedApps: List<InstalledAppInfo> = emptyList(),
        val inspectedServer: VpnServer? = null,
        val snackbarMessage: String? = null
    )

    private val auxState = MutableStateFlow(AuxiliaryState())
    private var autoConnectAttempted = false

    val uiState: StateFlow<VpnUiState> = combine(
        repository.serversFlow,
        controller.connectionState,
        settingsRepository.preferencesFlow,
        auxState
    ) { servers, connState, prefs, aux ->
        val resolvedSelected = connState.activeServer
            ?: prefs.selectedServerId?.let { id -> servers.firstOrNull { it.id == id } }
            ?: repository.selectBestCandidate(servers, prefs.protocolPreference)

        VpnUiState(
            servers = servers,
            connectionState = connState,
            preferences = prefs,
            selectedServer = resolvedSelected,
            isRefreshingDirectories = aux.isRefreshingDirectories,
            isProbingLatencies = aux.isProbingLatencies,
            directoryStatuses = aux.directoryStatuses,
            serverFilter = aux.serverFilter,
            searchQuery = aux.searchQuery,
            installedApps = aux.installedApps,
            inspectedServer = aux.inspectedServer?.let { inspected ->
                servers.firstOrNull { it.id == inspected.id } ?: inspected
            },
            snackbarMessage = aux.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VpnUiState()
    )

    init {
        initialBootstrap()
    }

    private fun initialBootstrap() {
        viewModelScope.launch {
            val cached = repository.getAllServersOnce()
            if (cached.isEmpty()) {
                refreshDirectories()
            } else {
                probeAllLatencies()
            }
            val prefs = settingsRepository.preferencesFlow.first()
            if (prefs.autoConnect && !autoConnectAttempted) {
                autoConnectAttempted = true
                if (controller.prepareVpnPermission() == null) {
                    controller.connectAuto()
                }
            }
        }
    }

    fun prepareVpnPermissionIntent(): Intent? = controller.prepareVpnPermission()

    fun onConnectOrDisconnectClicked() {
        val current = controller.connectionState.value
        if (current.isConnected || current.isBusy) {
            controller.disconnect()
            return
        }
        val selectedId = uiState.value.preferences.selectedServerId
        val chosen = selectedId?.let { id -> uiState.value.servers.firstOrNull { it.id == id } }
        if (chosen != null) {
            controller.connectToServer(chosen)
        } else {
            controller.connectAuto()
        }
    }

    fun connectAutoOptimal() {
        viewModelScope.launch {
            settingsRepository.setSelectedServerId(null)
            controller.connectAuto()
        }
    }

    fun selectServer(server: VpnServer, connectNow: Boolean = false) {
        viewModelScope.launch {
            settingsRepository.setSelectedServerId(server.id)
            if (connectNow) {
                controller.connectToServer(server)
            } else {
                showSnackbar("Selected ${server.name}")
            }
        }
    }

    fun refreshDirectories() {
        if (auxState.value.isRefreshingDirectories) return
        viewModelScope.launch {
            auxState.update { it.copy(isRefreshingDirectories = true) }
            val summary = repository.refreshPublicDirectories(probeAfterFetch = true)
            val errors = summary.directoryResults.mapNotNull { it.error }
            val msg = when {
                summary.totalDiscovered > 0 -> "Discovered ${summary.totalDiscovered} authorized public servers"
                errors.isNotEmpty() -> errors.first()
                else -> "No public servers discovered"
            }
            auxState.update {
                it.copy(
                    isRefreshingDirectories = false,
                    directoryStatuses = summary.directoryResults,
                    snackbarMessage = msg
                )
            }
        }
    }

    fun probeAllLatencies() {
        if (auxState.value.isProbingLatencies) return
        viewModelScope.launch {
            auxState.update { it.copy(isProbingLatencies = true) }
            repository.probeAllServers()
            auxState.update {
                it.copy(
                    isProbingLatencies = false,
                    snackbarMessage = "Latency check complete"
                )
            }
        }
    }

    fun probeSingleServer(server: VpnServer) {
        viewModelScope.launch {
            val updated = repository.probeSingleServer(server)
            val msg = if (updated.latencyMs != null) {
                "${updated.name}: ${updated.latencyMs} ms"
            } else {
                "${updated.name}: unreachable"
            }
            showSnackbar(msg)
        }
    }

    fun toggleFavorite(server: VpnServer) {
        viewModelScope.launch {
            repository.toggleFavorite(server.id, !server.isFavorite)
        }
    }

    fun importConfig(name: String, rawContent: String) {
        viewModelScope.launch {
            val result = repository.importProfile(name, rawContent)
            result.onSuccess { imported ->
                settingsRepository.setSelectedServerId(imported.id)
                showSnackbar("Imported ${imported.name} (${imported.protocol.displayName})")
            }.onFailure { err ->
                showSnackbar(err.message ?: "Invalid VPN configuration")
            }
        }
    }

    fun deleteImportedServer(server: VpnServer) {
        viewModelScope.launch {
            if (uiState.value.preferences.selectedServerId == server.id) {
                settingsRepository.setSelectedServerId(null)
            }
            repository.deleteImportedServer(server.id)
            auxState.update { it.copy(inspectedServer = null, snackbarMessage = "Deleted ${server.name}") }
        }
    }

    fun setFilter(filter: ServerFilter) {
        auxState.update { it.copy(serverFilter = filter) }
    }

    fun setSearchQuery(query: String) {
        auxState.update { it.copy(searchQuery = query) }
    }

    fun inspectServer(server: VpnServer?) {
        auxState.update { it.copy(inspectedServer = server) }
    }

    fun getRedactedConfigPreview(server: VpnServer): String? {
        val raw = repository.decryptConfigPayload(server) ?: return null
        return if (server.protocol == VpnProtocol.WIREGUARD) {
            VpnConfigValidator.redactSensitiveWireGuardLines(raw)
        } else {
            VpnConfigValidator.redactSensitiveOpenVpnLines(raw)
        }
    }

    fun isOpenVpnClientInstalled(): Boolean = controller.isExternalOpenVpnClientInstalled()

    fun launchOpenVpnProfile(server: VpnServer): Intent? = controller.buildOpenVpnShareIntent(server)

    fun dismissOpenVpnHandoff() = controller.clearOpenVpnHandoff()

    fun dismissError() = controller.clearError()

    fun loadInstalledApps() {
        if (auxState.value.installedApps.isNotEmpty()) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = appContext.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(launcherIntent, 0)
                }
                resolveInfos
                    .mapNotNull { info ->
                        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                        if (pkg == appContext.packageName) return@mapNotNull null
                        val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                        InstalledAppInfo(packageName = pkg, appName = label)
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.appName.lowercase() }
            }
            auxState.update { it.copy(installedApps = apps) }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoConnect(enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoReconnect(enabled) }
    }

    fun setProtocolPreference(preference: ProtocolPreference) {
        viewModelScope.launch { settingsRepository.setProtocolPreference(preference) }
    }

    fun setDnsMode(mode: DnsMode) {
        viewModelScope.launch { settingsRepository.setDnsMode(mode) }
    }

    fun setCustomDnsIp(ip: String) {
        viewModelScope.launch {
            if (ip.isBlank() || VpnConfigValidator.isValidDnsIp(ip)) {
                settingsRepository.setCustomDnsIp(ip)
                showSnackbar("Custom DNS updated")
            } else {
                showSnackbar("Enter a valid IPv4 or IPv6 DNS address")
            }
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setIpv6Enabled(enabled) }
    }

    fun setSplitTunnelMode(mode: SplitTunnelMode) {
        viewModelScope.launch { settingsRepository.setSplitTunnelMode(mode) }
    }

    fun toggleSplitTunnelPackage(packageName: String) {
        viewModelScope.launch { settingsRepository.toggleSplitTunnelPackage(packageName) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun resetAndRegenerateKeys() {
        viewModelScope.launch {
            controller.disconnect()
            repository.clearAndReprovisionKeys()
            refreshDirectories()
            showSnackbar("Keystore keys reset and public credentials refreshed")
        }
    }

    fun clearSnackbar() {
        auxState.update { it.copy(snackbarMessage = null) }
    }

    private fun showSnackbar(message: String) {
        auxState.update { it.copy(snackbarMessage = message) }
    }

    class Factory(private val app: FreePublicVpnApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VpnViewModel(
                appContext = app,
                repository = app.serverRepository,
                settingsRepository = app.settingsRepository,
                controller = app.connectionController
            ) as T
        }
    }
}
