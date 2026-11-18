package com.example.model

import kotlinx.serialization.Serializable

enum class VpnProtocol(val displayName: String) {
    WIREGUARD("WireGuard"),
    OPENVPN("OpenVPN")
}

enum class DirectorySource(val displayName: String, val attribution: String) {
    CLOUDFLARE_WARP("Cloudflare WARP", "Cloudflare Public WireGuard Registration API"),
    VPNGATE("VPNGate", "University of Tsukuba Academic Relay Directory"),
    RISEUP_VPN("RiseupVPN", "LEAP / Riseup Public EIP Directory"),
    USER_IMPORTED("Imported", "User-Authorized Profile")
}

enum class ServerHealth(val label: String) {
    ONLINE("Online"),
    DEGRADED("High Load"),
    UNREACHABLE("Unreachable"),
    UNTESTED("Untested")
}

enum class ProtocolPreference(val label: String) {
    AUTO("Auto (WireGuard Preferred)"),
    WIREGUARD("WireGuard Only"),
    OPENVPN("OpenVPN (External Client)")
}

enum class DnsMode(val label: String, val primaryIp: String, val secondaryIp: String, val ipv6Primary: String) {
    CLOUDFLARE("Cloudflare (1.1.1.1)", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"),
    QUAD9("Quad9 Security (9.9.9.9)", "9.9.9.9", "149.112.112.112", "2620:fe::fe"),
    ADGUARD("AdGuard DNS (94.140.14.14)", "94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff"),
    CUSTOM("Custom DNS", "", "", "")
}

enum class SplitTunnelMode(val label: String) {
    DISABLED("All Apps Use VPN"),
    EXCLUDE_SELECTED("Bypass Selected Apps"),
    INCLUDE_ONLY_SELECTED("Only Selected Apps Use VPN")
}

@Serializable
data class VpnServer(
    val id: String,
    val name: String,
    val countryName: String,
    val countryCode: String,
    val host: String,
    val ipAddress: String,
    val port: Int,
    val protocol: VpnProtocol,
    val transport: String, // "UDP" or "TCP"
    val directorySource: DirectorySource,
    val latencyMs: Int? = null,
    val health: ServerHealth = ServerHealth.UNTESTED,
    val bandwidthBps: Long = 0L,
    val activeSessions: Int = 0,
    val score: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val operatorName: String = "",
    val logPolicy: String = "See provider policy",
    val trustNotice: String = "",
    val supportsIpv6: Boolean = false,
    val isFavorite: Boolean = false,
    val isImported: Boolean = false,
    val inAppTunnelSupported: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val lastError: String? = null,
    val peerPublicKey: String? = null,
    val encryptedConfigPayload: String? = null
) {
    val countryFlagEmoji: String
        get() {
            val code = countryCode.uppercase()
            if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
            val first = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
            val second = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
            return String(Character.toChars(first)) + String(Character.toChars(second))
        }
}

enum class ConnectionStep(val title: String, val isTransitioning: Boolean) {
    IDLE("Disconnected", false),
    DISCOVERING_SERVERS("Discovering public directories…", true),
    PROBING_LATENCY("Checking server availability & latency…", true),
    PROVISIONING_KEYS("Provisioning encrypted WireGuard keys…", true),
    ESTABLISHING_TUNNEL("Establishing VpnService tunnel…", true),
    VERIFYING_CONNECTIVITY("Verifying encrypted connectivity…", true),
    CONNECTED("Connected • Encrypted", false),
    RECONNECTING("Reconnecting automatically…", true),
    DISCONNECTING("Closing VPN tunnel…", true),
    ERROR("Connection failed", false)
}

data class VpnConnectionState(
    val step: ConnectionStep = ConnectionStep.IDLE,
    val activeServer: VpnServer? = null,
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val totalRxBytes: Long = 0L,
    val totalTxBytes: Long = 0L,
    val connectedSinceMs: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val verifiedExitIp: String? = null,
    val dnsServerDisplay: String = "1.1.1.1",
    val ipv6Active: Boolean = false,
    val errorMessage: String? = null,
    val statusDetail: String? = null,
    val openVpnHandoffServer: VpnServer? = null
) {
    val isConnected: Boolean get() = step == ConnectionStep.CONNECTED
    val isBusy: Boolean get() = step.isTransitioning
}

data class UserPreferences(
    val autoConnect: Boolean = false,
    val autoReconnect: Boolean = true,
    val protocolPreference: ProtocolPreference = ProtocolPreference.AUTO,
    val dnsMode: DnsMode = DnsMode.CLOUDFLARE,
    val customDnsIp: String = "",
    val ipv6Enabled: Boolean = true,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.DISABLED,
    val splitTunnelPackages: Set<String> = emptySet(),
    val notificationsEnabled: Boolean = true,
    val selectedServerId: String? = null
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String
)
