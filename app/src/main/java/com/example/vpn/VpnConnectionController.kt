package com.example.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import androidx.core.content.FileProvider
import com.example.data.VpnServerRepository
import com.example.data.local.SettingsRepository
import com.example.data.network.ServerHealthProber
import com.example.model.ConnectionStep
import com.example.model.UserPreferences
import com.example.model.VpnConnectionState
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class TunnelStatsSnapshot(
    val rxBytes: Long,
    val txBytes: Long
)

interface TunnelBackendAdapter {
    fun prepareVpnIntent(context: Context): Intent?
    suspend fun bringUp(tunnelName: String, config: Config): Boolean
    suspend fun bringDown(tunnelName: String)
    suspend fun readStats(tunnelName: String): TunnelStatsSnapshot?
}

class RealWireGuardBackend(
    private val context: Context
) : TunnelBackendAdapter {

    private val goBackend: GoBackend by lazy { GoBackend(context.applicationContext) }
    private var activeTunnel: ManagedTunnel? = null

    private class ManagedTunnel(private val tunnelName: String) : Tunnel {
        @Volatile
        var currentState: Tunnel.State = Tunnel.State.DOWN
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            currentState = newState
        }
    }

    override fun prepareVpnIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    override suspend fun bringUp(tunnelName: String, config: Config): Boolean = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel?.takeIf { it.name == tunnelName } ?: ManagedTunnel(tunnelName).also {
            activeTunnel = it
        }
        val state = goBackend.setState(tunnel, Tunnel.State.UP, config)
        state == Tunnel.State.UP
    }

    override suspend fun bringDown(tunnelName: String) = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: ManagedTunnel(tunnelName)
        runCatching {
            goBackend.setState(tunnel, Tunnel.State.DOWN, null)
        }
        Unit
    }

    override suspend fun readStats(tunnelName: String): TunnelStatsSnapshot? = withContext(Dispatchers.IO) {
        val tunnel = activeTunnel ?: return@withContext null
        runCatching {
            val stats = goBackend.getStatistics(tunnel)
            TunnelStatsSnapshot(
                rxBytes = stats.totalRx(),
                txBytes = stats.totalTx()
            )
        }.getOrNull()
    }
}

class VpnConnectionController(
    private val context: Context,
    private val repository: VpnServerRepository,
    private val settingsRepository: SettingsRepository,
    private val healthProber: ServerHealthProber,
    private val backend: TunnelBackendAdapter = RealWireGuardBackend(context)
) {
    companion object {
        private const val TUNNEL_NAME = "freepublicvpn_wg0"
        private const val CONNECTION_TIMEOUT_MS = 18_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connectionState = MutableStateFlow(VpnConnectionState())
    val connectionState: StateFlow<VpnConnectionState> = _connectionState.asStateFlow()

    private var connectionJob: Job? = null
    private var telemetryJob: Job? = null
    private var networkCallbackRegistered = false
    private var manualDisconnectRequested = false
    private var reconnectAttempts = 0

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            scope.launch {
                val state = _connectionState.value
                val prefs = settingsRepository.preferencesFlow.first()
                if (state.isConnected && prefs.autoReconnect && !manualDisconnectRequested) {
                    triggerAutoReconnect(state.activeServer)
                }
            }
        }
    }

    fun prepareVpnPermission(): Intent? = backend.prepareVpnIntent(context)

    fun connectAuto() {
        connectInternal(manualServer = null)
    }

    fun connectToServer(server: VpnServer) {
        connectInternal(manualServer = server)
    }

    private fun connectInternal(manualServer: VpnServer?) {
        manualDisconnectRequested = false
        reconnectAttempts = 0
        connectionJob?.cancel()
        telemetryJob?.cancel()

        connectionJob = scope.launch {
            val prefs = settingsRepository.preferencesFlow.first()
            try {
                // Step 1: Ensure servers are discovered
                _connectionState.update {
                    it.copy(
                        step = ConnectionStep.DISCOVERING_SERVERS,
                        errorMessage = null,
                        openVpnHandoffServer = null,
                        statusDetail = "Querying authorized public VPN directories"
                    )
                }

                var candidates = repository.getAllServersOnce()
                if (manualServer == null && candidates.none { it.inAppTunnelSupported }) {
                    repository.refreshPublicDirectories(probeAfterFetch = false)
                    candidates = repository.getAllServersOnce()
                }

                val target: VpnServer = if (manualServer != null) {
                    manualServer
                } else {
                    // Step 2: Check availability & probe latencies
                    _connectionState.update {
                        it.copy(
                            step = ConnectionStep.PROBING_LATENCY,
                            statusDetail = "Measuring real-time latency across authorized nodes"
                        )
                    }
                    val wgCandidates = candidates.filter { it.inAppTunnelSupported }
                    if (wgCandidates.isEmpty()) {
                        throw IllegalStateException("No WireGuard public servers discovered. Check your internet connection and refresh.")
                    }
                    val probed = wgCandidates.map { repository.probeSingleServer(it) }
                    repository.selectBestCandidate(probed, prefs.protocolPreference)
                        ?: throw IllegalStateException("All public WireGuard servers are currently unreachable.")
                }

                // If user manually selected an OpenVPN-only server, handle honestly via external client check
                if (!target.inAppTunnelSupported || target.protocol == VpnProtocol.OPENVPN) {
                    _connectionState.update {
                        it.copy(
                            step = ConnectionStep.IDLE,
                            activeServer = target,
                            openVpnHandoffServer = target,
                            errorMessage = null,
                            statusDetail = "OpenVPN profile ready for external OpenVPN client"
                        )
                    }
                    return@launch
                }

                // Step 2b: Probe manually chosen server first
                if (manualServer != null) {
                    _connectionState.update {
                        it.copy(
                            step = ConnectionStep.PROBING_LATENCY,
                            activeServer = target,
                            statusDetail = "Verifying ${target.host}:${target.port} availability"
                        )
                    }
                    val probedManual = repository.probeSingleServer(target)
                    if (probedManual.latencyMs == null) {
                        throw IllegalStateException("${target.name} (${target.ipAddress}) did not respond to availability probe.")
                    }
                }

                // Step 3: Decrypt / validate WireGuard configuration & apply DNS/IPv6/Split-Tunnel rules
                _connectionState.update {
                    it.copy(
                        step = ConnectionStep.PROVISIONING_KEYS,
                        activeServer = target,
                        statusDetail = "Decrypting Keystore keys & applying DNS leak protection"
                    )
                }

                val rawConf = repository.decryptConfigPayload(target)
                    ?: throw IllegalStateException("Encrypted configuration unavailable. Refresh directories to re-provision.")

                val wgConfig = withContext(Dispatchers.Default) {
                    WireGuardConfigBuilder.buildTunnelConfig(
                        rawConfigText = rawConf,
                        preferences = prefs,
                        selfPackageName = context.packageName
                    )
                }

                // Step 4: Start foreground coordinator & bring WireGuard VpnService tunnel UP with timeout
                _connectionState.update {
                    it.copy(
                        step = ConnectionStep.ESTABLISHING_TUNNEL,
                        activeServer = target,
                        dnsServerDisplay = WireGuardConfigBuilder.activeDnsLabel(prefs),
                        ipv6Active = prefs.ipv6Enabled && target.supportsIpv6,
                        statusDetail = "Starting VpnService encrypted tunnel (${target.ipAddress}:${target.port})"
                    )
                }

                startCoordinatorForegroundService(prefs, target)

                val tunnelUp = withTimeout(CONNECTION_TIMEOUT_MS) {
                    backend.bringUp(TUNNEL_NAME, wgConfig)
                }
                if (!tunnelUp) {
                    throw IllegalStateException("WireGuard backend failed to bring up VpnService tunnel.")
                }

                // Step 5: Verify real connectivity over the active tunnel BEFORE showing CONNECTED
                _connectionState.update {
                    it.copy(
                        step = ConnectionStep.VERIFYING_CONNECTIVITY,
                        activeServer = target,
                        statusDetail = "Verifying handshake & encrypted internet connectivity"
                    )
                }

                delay(650)
                val verification = withTimeout(10_000L) {
                    healthProber.verifyTunnelConnectivity()
                }
                if (!verification.isConnected) {
                    backend.bringDown(TUNNEL_NAME)
                    stopCoordinatorForegroundService()
                    throw IllegalStateException(verification.error ?: "Tunnel established but internet connectivity verification failed.")
                }

                // Step 6: Genuinely Connected!
                val now = System.currentTimeMillis()
                settingsRepository.setSelectedServerId(target.id)
                registerNetworkCallback()

                _connectionState.update {
                    it.copy(
                        step = ConnectionStep.CONNECTED,
                        activeServer = target.copy(latencyMs = verification.latencyMs ?: target.latencyMs),
                        connectedSinceMs = now,
                        elapsedSeconds = 0L,
                        totalRxBytes = 0L,
                        totalTxBytes = 0L,
                        downloadBytesPerSec = 0L,
                        uploadBytesPerSec = 0L,
                        verifiedExitIp = verification.exitIp,
                        dnsServerDisplay = WireGuardConfigBuilder.activeDnsLabel(prefs),
                        ipv6Active = prefs.ipv6Enabled && target.supportsIpv6,
                        errorMessage = null,
                        statusDetail = "Encrypted WireGuard tunnel verified"
                    )
                }

                startTelemetryLoop(prefs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backend.bringDown(TUNNEL_NAME)
                stopCoordinatorForegroundService()
                unregisterNetworkCallback()
                _connectionState.update {
                    it.copy(
                        step = ConnectionStep.ERROR,
                        downloadBytesPerSec = 0L,
                        uploadBytesPerSec = 0L,
                        errorMessage = e.message ?: "Unable to establish encrypted VPN tunnel.",
                        statusDetail = "Tunnel rolled back safely"
                    )
                }
            }
        }
    }

    fun disconnect() {
        manualDisconnectRequested = true
        connectionJob?.cancel()
        telemetryJob?.cancel()
        unregisterNetworkCallback()

        scope.launch {
            _connectionState.update {
                it.copy(
                    step = ConnectionStep.DISCONNECTING,
                    statusDetail = "Closing WireGuard tunnel interface"
                )
            }
            backend.bringDown(TUNNEL_NAME)
            stopCoordinatorForegroundService()
            _connectionState.update {
                VpnConnectionState(
                    step = ConnectionStep.IDLE,
                    activeServer = it.activeServer,
                    statusDetail = "Disconnected"
                )
            }
        }
    }

    fun clearOpenVpnHandoff() {
        _connectionState.update { it.copy(openVpnHandoffServer = null) }
    }

    fun clearError() {
        _connectionState.update {
            if (it.step == ConnectionStep.ERROR) it.copy(step = ConnectionStep.IDLE, errorMessage = null)
            else it.copy(errorMessage = null)
        }
    }

    private fun triggerAutoReconnect(previousServer: VpnServer?) {
        if (manualDisconnectRequested || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        reconnectAttempts++
        telemetryJob?.cancel()
        connectionJob?.cancel()
        connectionJob = scope.launch {
            _connectionState.update {
                it.copy(
                    step = ConnectionStep.RECONNECTING,
                    statusDetail = "Network changed — reconnecting (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)"
                )
            }
            delay(1500L * reconnectAttempts)
            val prefs = settingsRepository.preferencesFlow.first()
            val all = repository.getAllServersOnce()
            val nextServer = previousServer?.takeIf { it.inAppTunnelSupported }
                ?: repository.selectBestCandidate(all, prefs.protocolPreference)
            if (nextServer != null) {
                connectInternal(nextServer)
            } else {
                disconnect()
            }
        }
    }

    private fun startTelemetryLoop(prefs: UserPreferences) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            var prevRx = 0L
            var prevTx = 0L
            var prevTickMs = System.currentTimeMillis()
            var zeroBytesCounter = 0

            while (isActive && _connectionState.value.isConnected) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val snapshot = backend.readStats(TUNNEL_NAME)
                val dtSeconds = ((now - prevTickMs).coerceAtLeast(500L)) / 1000.0
                val rx = snapshot?.rxBytes ?: prevRx
                val tx = snapshot?.txBytes ?: prevTx

                val dlRate = ((rx - prevRx).coerceAtLeast(0L) / dtSeconds).toLong()
                val ulRate = ((tx - prevTx).coerceAtLeast(0L) / dtSeconds).toLong()

                val startedAt = _connectionState.value.connectedSinceMs
                val elapsed = ((now - startedAt) / 1000L).coerceAtLeast(0L)

                prevRx = rx
                prevTx = tx
                prevTickMs = now

                _connectionState.update {
                    it.copy(
                        totalRxBytes = rx,
                        totalTxBytes = tx,
                        downloadBytesPerSec = dlRate,
                        uploadBytesPerSec = ulRate,
                        elapsedSeconds = elapsed
                    )
                }

                if (prefs.notificationsEnabled) {
                    VpnCoordinatorService.updateNotification(
                        context = context,
                        serverName = _connectionState.value.activeServer?.name ?: "Public VPN",
                        downloadRateBps = dlRate,
                        uploadRateBps = ulRate
                    )
                }

                if (snapshot == null) {
                    zeroBytesCounter++
                    if (zeroBytesCounter >= 5 && prefs.autoReconnect && !manualDisconnectRequested) {
                        triggerAutoReconnect(_connectionState.value.activeServer)
                        break
                    }
                } else {
                    zeroBytesCounter = 0
                }
            }
        }
    }

    private fun startCoordinatorForegroundService(prefs: UserPreferences, server: VpnServer) {
        if (!prefs.notificationsEnabled) return
        val intent = Intent(context, VpnCoordinatorService::class.java).apply {
            action = VpnCoordinatorService.ACTION_START
            putExtra(VpnCoordinatorService.EXTRA_SERVER_NAME, server.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopCoordinatorForegroundService() {
        runCatching {
            val intent = Intent(context, VpnCoordinatorService::class.java).apply {
                action = VpnCoordinatorService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered || connectivityManager == null) return
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered || connectivityManager == null) return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
        networkCallbackRegistered = false
    }

    fun isExternalOpenVpnClientInstalled(): Boolean {
        val pm = context.packageManager
        val knownPackages = listOf("net.openvpn.openvpn", "de.blinkt.openvpn")
        return knownPackages.any { pkg ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            }.getOrDefault(false)
        }
    }

    fun buildOpenVpnShareIntent(server: VpnServer): Intent? {
        val decrypted = repository.decryptConfigPayload(server) ?: return null
        val exportDir = File(context.cacheDir, "ovpn_exports").apply { mkdirs() }
        val safeName = server.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        val outFile = File(exportDir, "$safeName.ovpn")
        outFile.writeText(decrypted)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/x-openvpn-profile")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
