package com.example.data

import com.example.data.local.ServerDao
import com.example.data.local.ServerEntity
import com.example.data.network.CloudflareWarpClient
import com.example.data.network.DirectoryFetchResult
import com.example.data.network.RiseupDirectoryClient
import com.example.data.network.ServerHealthProber
import com.example.data.network.VpnGateDirectoryClient
import com.example.data.security.CryptoManager
import com.example.data.security.ValidationResult
import com.example.data.security.VpnConfigValidator
import com.example.model.DirectorySource
import com.example.model.ProtocolPreference
import com.example.model.ServerHealth
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DirectorySyncSummary(
    val totalDiscovered: Int,
    val directoryResults: List<DirectoryFetchResult>
)

class VpnServerRepository(
    private val serverDao: ServerDao,
    private val cloudflareWarpClient: CloudflareWarpClient,
    private val vpnGateDirectoryClient: VpnGateDirectoryClient,
    private val riseupDirectoryClient: RiseupDirectoryClient,
    private val healthProber: ServerHealthProber,
    private val cryptoManager: CryptoManager
) {
    val serversFlow: Flow<List<VpnServer>> = serverDao.observeServers().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getAllServersOnce(): List<VpnServer> =
        serverDao.getAllOnce().map { it.toDomain() }

    suspend fun getServerById(id: String): VpnServer? =
        serverDao.getById(id)?.toDomain()

    suspend fun refreshPublicDirectories(probeAfterFetch: Boolean = true): DirectorySyncSummary = coroutineScope {
        val existingById = serverDao.getAllOnce().associateBy { it.id }

        val cfDeferred = async { cloudflareWarpClient.discoverAndRegister() }
        val vgDeferred = async { vpnGateDirectoryClient.fetchServers(maxServers = 30) }
        val ruDeferred = async { riseupDirectoryClient.fetchServers() }

        val results = listOf(cfDeferred.await(), vgDeferred.await(), ruDeferred.await())
        val discovered = results.flatMap { it.servers }

        if (discovered.isNotEmpty()) {
            val merged = discovered.map { server ->
                val prev = existingById[server.id]
                val preservedFav = prev?.isFavorite ?: false
                ServerEntity.fromDomain(
                    server.copy(
                        isFavorite = preservedFav,
                        latencyMs = prev?.latencyMs ?: server.latencyMs,
                        health = if (prev != null && prev.health != ServerHealth.UNTESTED.name) {
                            runCatching { ServerHealth.valueOf(prev.health) }.getOrDefault(server.health)
                        } else server.health,
                        lastCheckedAt = prev?.lastCheckedAt ?: server.lastCheckedAt
                    )
                )
            }
            serverDao.clearNonFavoriteDiscovered()
            serverDao.upsertAll(merged)
        }

        if (probeAfterFetch) {
            val current = getAllServersOnce()
            val toProbe = current.filter { it.protocol == VpnProtocol.WIREGUARD }.take(12) +
                current.filter { it.protocol == VpnProtocol.OPENVPN }.take(18)
            if (toProbe.isNotEmpty()) {
                probeAndPersist(toProbe.distinctBy { it.id })
            }
        }

        DirectorySyncSummary(
            totalDiscovered = discovered.size,
            directoryResults = results
        )
    }

    suspend fun probeAllServers(): List<VpnServer> {
        val current = getAllServersOnce()
        if (current.isEmpty()) return emptyList()
        probeAndPersist(current)
        return getAllServersOnce()
    }

    suspend fun probeSingleServer(server: VpnServer): VpnServer {
        val probe = healthProber.probeSingle(server)
        val now = System.currentTimeMillis()
        serverDao.updateHealth(
            id = server.id,
            latencyMs = probe.latencyMs,
            health = probe.health.name,
            checkedAt = now,
            error = probe.error
        )
        return server.copy(
            latencyMs = probe.latencyMs,
            health = probe.health,
            lastCheckedAt = now,
            lastError = probe.error
        )
    }

    private suspend fun probeAndPersist(servers: List<VpnServer>) {
        val probes = healthProber.probeServers(servers)
        val now = System.currentTimeMillis()
        probes.forEach { probe ->
            serverDao.updateHealth(
                id = probe.serverId,
                latencyMs = probe.latencyMs,
                health = probe.health.name,
                checkedAt = now,
                error = probe.error
            )
        }
    }

    suspend fun toggleFavorite(serverId: String, favorite: Boolean) {
        serverDao.setFavorite(serverId, favorite)
    }

    suspend fun deleteImportedServer(serverId: String) {
        serverDao.deleteById(serverId)
    }

    suspend fun importProfile(name: String, rawContent: String): Result<VpnServer> {
        val trimmedName = name.trim().ifEmpty { "Imported VPN Profile" }
        return when (val wg = VpnConfigValidator.validateWireGuard(rawContent)) {
            is ValidationResult.ValidWireGuard -> {
                val encrypted = cryptoManager.encrypt(rawContent)
                val server = VpnServer(
                    id = "imported_wg_${System.currentTimeMillis()}",
                    name = trimmedName,
                    countryName = "Custom Profile",
                    countryCode = "UN",
                    host = wg.endpointHost,
                    ipAddress = wg.endpointHost,
                    port = wg.endpointPort,
                    protocol = VpnProtocol.WIREGUARD,
                    transport = "UDP",
                    directorySource = DirectorySource.USER_IMPORTED,
                    health = ServerHealth.UNTESTED,
                    operatorName = "User Imported Config",
                    logPolicy = "Managed by your WireGuard server operator",
                    trustNotice = "Valid WireGuard configuration stored encrypted via Android Keystore.",
                    supportsIpv6 = wg.supportsIpv6,
                    isImported = true,
                    inAppTunnelSupported = true,
                    peerPublicKey = wg.peerPublicKey,
                    encryptedConfigPayload = encrypted
                )
                serverDao.upsert(ServerEntity.fromDomain(server))
                Result.success(server)
            }
            is ValidationResult.Invalid -> {
                when (val ovpn = VpnConfigValidator.validateOpenVpn(rawContent)) {
                    is ValidationResult.ValidOpenVpn -> {
                        val encrypted = cryptoManager.encrypt(rawContent)
                        val server = VpnServer(
                            id = "imported_ovpn_${System.currentTimeMillis()}",
                            name = trimmedName,
                            countryName = "Custom Profile",
                            countryCode = "UN",
                            host = ovpn.remoteHost,
                            ipAddress = ovpn.remoteHost,
                            port = ovpn.remotePort,
                            protocol = VpnProtocol.OPENVPN,
                            transport = ovpn.transport,
                            directorySource = DirectorySource.USER_IMPORTED,
                            health = ServerHealth.UNTESTED,
                            operatorName = "User Imported Config",
                            logPolicy = "Managed by your OpenVPN server operator",
                            trustNotice = "Valid OpenVPN profile (${ovpn.cipher}) stored encrypted via Android Keystore. Requires external OpenVPN client.",
                            supportsIpv6 = false,
                            isImported = true,
                            inAppTunnelSupported = false,
                            encryptedConfigPayload = encrypted
                        )
                        serverDao.upsert(ServerEntity.fromDomain(server))
                        Result.success(server)
                    }
                    is ValidationResult.Invalid -> {
                        Result.failure(IllegalArgumentException(wg.reason))
                    }
                    else -> Result.failure(IllegalArgumentException("Unsupported configuration format"))
                }
            }
            else -> Result.failure(IllegalArgumentException("Unsupported configuration format"))
        }
    }

    fun selectBestCandidate(
        servers: List<VpnServer>,
        protocolPreference: ProtocolPreference,
        excludeIds: Set<String> = emptySet()
    ): VpnServer? {
        val eligible = servers.filter { server ->
            server.id !in excludeIds &&
                server.health != ServerHealth.UNREACHABLE &&
                server.encryptedConfigPayload != null &&
                when (protocolPreference) {
                    ProtocolPreference.WIREGUARD, ProtocolPreference.AUTO -> server.inAppTunnelSupported
                    ProtocolPreference.OPENVPN -> server.protocol == VpnProtocol.OPENVPN
                }
        }
        return eligible.minWithOrNull(
            compareBy<VpnServer>(
                { !it.isFavorite },
                { it.health != ServerHealth.ONLINE },
                { it.latencyMs ?: Int.MAX_VALUE }
            )
        )
    }

    fun decryptConfigPayload(server: VpnServer): String? {
        val payload = server.encryptedConfigPayload ?: return null
        return cryptoManager.decrypt(payload)
    }

    suspend fun clearAndReprovisionKeys() {
        cryptoManager.resetKeys()
        serverDao.clearNonFavoriteDiscovered()
    }
}
