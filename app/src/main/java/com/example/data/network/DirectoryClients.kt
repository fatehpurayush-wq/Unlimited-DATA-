package com.example.data.network

import android.util.Base64
import com.example.data.security.CryptoManager
import com.example.data.security.ValidationResult
import com.example.data.security.VpnConfigValidator
import com.example.model.DirectorySource
import com.example.model.ServerHealth
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit

data class DirectoryFetchResult(
    val source: DirectorySource,
    val servers: List<VpnServer>,
    val error: String? = null
)

class CloudflareWarpClient(
    private val httpClient: OkHttpClient,
    private val cryptoManager: CryptoManager
) {
    companion object {
        private const val REG_ENDPOINT = "https://api.cloudflareclient.com/v0a2158/reg"
        private const val DEFAULT_WARP_PUBKEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

        private val DOCUMENTED_ANYCAST_ENDPOINTS = listOf(
            Triple("cf-warp-anycast", "Cloudflare WARP Anycast", "engage.cloudflareclient.com" to "162.159.192.1"),
            Triple("cf-warp-edge-192", "Cloudflare WARP Edge A", "162.159.192.1" to "162.159.192.1"),
            Triple("cf-warp-edge-193", "Cloudflare WARP Edge B", "162.159.193.1" to "162.159.193.1"),
            Triple("cf-warp-edge-195", "Cloudflare WARP Edge C", "162.159.195.1" to "162.159.195.1")
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Queries the documented Cloudflare WARP public client registration API
     * and generates fresh Curve25519 keys on-device to obtain authorized WireGuard credentials.
     */
    suspend fun discoverAndRegister(): DirectoryFetchResult = withContext(Dispatchers.IO) {
        try {
            val keyPair = KeyPair()
            val timestamp = Instant.now().toString()
            val payload = """
                {
                  "install_id": "",
                  "tos": "$timestamp",
                  "key": "${keyPair.publicKey.toBase64()}",
                  "fcm_token": "",
                  "type": "Android",
                  "locale": "en_US"
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(REG_ENDPOINT)
                .header("Content-Type", "application/json")
                .header("User-Agent", "okhttp/4.10.0")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DirectoryFetchResult(
                        source = DirectorySource.CLOUDFLARE_WARP,
                        servers = emptyList(),
                        error = "Cloudflare WARP API returned HTTP ${response.code}"
                    )
                }
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                val result = root["result"]?.jsonObject ?: root
                val accountId = result["id"]?.jsonPrimitive?.contentOrNull
                val token = result["token"]?.jsonPrimitive?.contentOrNull

                if (!accountId.isNullOrBlank() && !token.isNullOrBlank()) {
                    enableWarpMode(accountId, token)
                }

                val configObj = result["config"]?.jsonObject
                val ifaceObj = configObj?.get("interface")?.jsonObject
                val addressesObj = ifaceObj?.get("addresses")?.jsonObject
                val v4Address = addressesObj?.get("v4")?.jsonPrimitive?.contentOrNull ?: "172.16.0.2"
                val v6Address = addressesObj?.get("v6")?.jsonPrimitive?.contentOrNull ?: "fd01:5ca1:ab1e::2"

                val peerObj = configObj?.get("peers")?.jsonArray?.firstOrNull()?.jsonObject
                val peerPubKey = peerObj?.get("public_key")?.jsonPrimitive?.contentOrNull ?: DEFAULT_WARP_PUBKEY
                val endpointObj = peerObj?.get("endpoint")?.jsonObject
                val apiHost = endpointObj?.get("host")?.jsonPrimitive?.contentOrNull?.substringBefore(":")
                val apiV4 = endpointObj?.get("v4")?.jsonPrimitive?.contentOrNull?.substringBefore(":")

                val normalizedV4 = if (v4Address.contains("/")) v4Address else "$v4Address/32"
                val normalizedV6 = if (v6Address.contains("/")) v6Address else "$v6Address/128"

                val servers = DOCUMENTED_ANYCAST_ENDPOINTS.mapIndexed { idx, (id, label, hostPair) ->
                    val resolvedHost = if (idx == 0 && !apiHost.isNullOrBlank()) apiHost else hostPair.first
                    val resolvedIp = if (idx == 0 && !apiV4.isNullOrBlank()) apiV4 else hostPair.second
                    val endpointPort = 2408

                    val rawConf = buildWireGuardConf(
                        privateKeyBase64 = keyPair.privateKey.toBase64(),
                        addressV4 = normalizedV4,
                        addressV6 = normalizedV6,
                        peerPublicKey = peerPubKey,
                        endpoint = "$resolvedIp:$endpointPort"
                    )
                    val encryptedPayload = cryptoManager.encrypt(rawConf)

                    VpnServer(
                        id = id,
                        name = label,
                        countryName = "Anycast Global",
                        countryCode = "UN",
                        host = resolvedHost,
                        ipAddress = resolvedIp,
                        port = endpointPort,
                        protocol = VpnProtocol.WIREGUARD,
                        transport = "UDP",
                        directorySource = DirectorySource.CLOUDFLARE_WARP,
                        health = ServerHealth.UNTESTED,
                        operatorName = "Cloudflare, Inc.",
                        logPolicy = "Zero user activity logs; temporary operational telemetry per Cloudflare WARP policy",
                        trustNotice = "Publicly registered via Cloudflare WARP WireGuard API with freshly generated on-device Curve25519 keys.",
                        supportsIpv6 = true,
                        inAppTunnelSupported = true,
                        peerPublicKey = peerPubKey,
                        encryptedConfigPayload = encryptedPayload
                    )
                }

                DirectoryFetchResult(
                    source = DirectorySource.CLOUDFLARE_WARP,
                    servers = servers
                )
            }
        } catch (e: Exception) {
            DirectoryFetchResult(
                source = DirectorySource.CLOUDFLARE_WARP,
                servers = emptyList(),
                error = "Cloudflare WARP discovery failed: ${e.message ?: "network error"}"
            )
        }
    }

    private fun enableWarpMode(accountId: String, token: String) {
        runCatching {
            val req = Request.Builder()
                .url("$REG_ENDPOINT/$accountId")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .patch("""{"warp_enabled":true}""".toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().close()
        }
    }

    private fun buildWireGuardConf(
        privateKeyBase64: String,
        addressV4: String,
        addressV6: String,
        peerPublicKey: String,
        endpoint: String
    ): String {
        return """
            [Interface]
            PrivateKey = $privateKeyBase64
            Address = $addressV4, $addressV6
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280

            [Peer]
            PublicKey = $peerPublicKey
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = $endpoint
            PersistentKeepalive = 25
        """.trimIndent()
    }
}

class VpnGateDirectoryClient(
    private val httpClient: OkHttpClient,
    private val cryptoManager: CryptoManager
) {
    companion object {
        private val ENDPOINTS = listOf(
            "https://www.vpngate.net/api/iphone/",
            "http://www.vpngate.net/api/iphone/"
        )
    }

    suspend fun fetchServers(maxServers: Int = 35): DirectoryFetchResult = withContext(Dispatchers.IO) {
        var lastError: String? = null
        for (url in ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "FreePublicVPN-Android/1.0")
                    .get()
                    .build()

                val csvBody = httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = "VPNGate HTTP ${resp.code}"
                        null
                    } else {
                        resp.body?.string()
                    }
                } ?: continue

                val parsed = parseVpnGateCsv(csvBody, maxServers)
                if (parsed.isNotEmpty()) {
                    return@withContext DirectoryFetchResult(
                        source = DirectorySource.VPNGATE,
                        servers = parsed
                    )
                } else {
                    lastError = "VPNGate directory returned zero valid OpenVPN relays"
                }
            } catch (e: Exception) {
                lastError = "VPNGate fetch error: ${e.message ?: "connection failed"}"
            }
        }
        DirectoryFetchResult(
            source = DirectorySource.VPNGATE,
            servers = emptyList(),
            error = lastError ?: "Unable to reach VPNGate directory"
        )
    }

    internal fun parseVpnGateCsv(csvText: String, maxServers: Int): List<VpnServer> {
        val servers = mutableListOf<VpnServer>()
        val lines = csvText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("#HostName") }

        for (line in lines) {
            if (servers.size >= maxServers) break
            val cols = splitCsvLine(line)
            if (cols.size < 15) continue

            val hostName = cols[0].trim()
            val ip = cols[1].trim()
            val score = cols[2].trim().toLongOrNull() ?: 0L
            val reportedPing = cols[3].trim().toIntOrNull()
            val speedBps = cols[4].trim().toLongOrNull() ?: 0L
            val countryLong = cols[5].trim().ifEmpty { "Unknown" }
            val countryShort = cols[6].trim().uppercase().ifEmpty { "UN" }
            val sessions = cols[7].trim().toIntOrNull() ?: 0
            val uptimeMs = cols[8].trim().toLongOrNull() ?: 0L
            val logType = cols[11].trim().ifEmpty { "2weeks" }
            val operator = cols[12].trim().ifEmpty { "VPNGate Volunteer" }
            val base64Ovpn = cols[14].trim()

            if (ip.isBlank() || base64Ovpn.isBlank()) continue

            val decodedOvpn = runCatching {
                String(Base64.decode(base64Ovpn, Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrNull() ?: continue

            val validation = VpnConfigValidator.validateOpenVpn(decodedOvpn)
            if (validation !is ValidationResult.ValidOpenVpn) continue

            val encryptedOvpn = cryptoManager.encrypt(decodedOvpn)

            servers += VpnServer(
                id = "vpngate_${ip}_${validation.remotePort}",
                name = "$countryLong • ${hostName.ifBlank { ip }}",
                countryName = countryLong,
                countryCode = countryShort,
                host = hostName.ifBlank { ip },
                ipAddress = ip,
                port = validation.remotePort,
                protocol = VpnProtocol.OPENVPN,
                transport = validation.transport,
                directorySource = DirectorySource.VPNGATE,
                latencyMs = reportedPing?.takeIf { it in 1..5000 },
                health = ServerHealth.UNTESTED,
                bandwidthBps = speedBps,
                activeSessions = sessions,
                score = score,
                uptimeSeconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMs),
                operatorName = operator,
                logPolicy = "VPNGate academic log policy: $logType",
                trustNotice = "Volunteer academic relay listed on VPNGate (University of Tsukuba). OpenVPN profile requires external OpenVPN client app.",
                supportsIpv6 = false,
                inAppTunnelSupported = false,
                encryptedConfigPayload = encryptedOvpn
            )
        }
        return servers
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        result += current.toString()
        return result
    }
}

class RiseupDirectoryClient(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val EIP_ENDPOINT = "https://api.black.riseup.net/3/config/eip-service.json"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchServers(): DirectoryFetchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(EIP_ENDPOINT)
                .header("User-Agent", "FreePublicVPN-Android/1.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DirectoryFetchResult(
                        source = DirectorySource.RISEUP_VPN,
                        servers = emptyList(),
                        error = "RiseupVPN directory returned HTTP ${resp.code}"
                    )
                }
                val body = resp.body?.string().orEmpty()
                val parsed = parseEipServiceJson(body)
                DirectoryFetchResult(
                    source = DirectorySource.RISEUP_VPN,
                    servers = parsed
                )
            }
        } catch (e: Exception) {
            DirectoryFetchResult(
                source = DirectorySource.RISEUP_VPN,
                servers = emptyList(),
                error = "RiseupVPN directory unavailable: ${e.message ?: "TLS/network error"}"
            )
        }
    }

    internal fun parseEipServiceJson(rawJson: String): List<VpnServer> {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val locationsObj = root["locations"]?.jsonObject ?: JsonObject(emptyMap())
        val gatewaysArr = root["gateways"]?.jsonArray ?: JsonArray(emptyList())

        val servers = mutableListOf<VpnServer>()
        for (item in gatewaysArr) {
            val gw = item.jsonObject
            val host = gw["host"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ip = gw["ip_address"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val locationKey = gw["location"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (ip.isBlank()) continue

            val locMeta = locationsObj[locationKey]?.jsonObject
            val locationName = locMeta?.get("name")?.jsonPrimitive?.contentOrNull ?: locationKey.ifBlank { "Global" }
            val countryCode = locMeta?.get("country_code")?.jsonPrimitive?.contentOrNull?.uppercase() ?: "UN"

            val capabilities = gw["capabilities"]?.jsonObject
            val transports = capabilities?.get("transport")?.jsonArray.orEmpty()
            val pubKey = gw["pubkey"]?.jsonPrimitive?.contentOrNull

            var openVpnPort = 1194
            var openVpnProto = "UDP"
            var hasOpenVpn = false
            var hasWireGuard = false
            var wireGuardPort = 51820

            for (tr in transports) {
                val trObj = tr.jsonObject
                val type = trObj["type"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                val ports = trObj["ports"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }.orEmpty()
                val protos = trObj["protocols"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.uppercase() }.orEmpty()

                if (type == "openvpn") {
                    hasOpenVpn = true
                    if (ports.isNotEmpty()) openVpnPort = ports.first()
                    if (protos.isNotEmpty()) openVpnProto = protos.first()
                } else if (type == "wireguard") {
                    hasWireGuard = true
                    if (ports.isNotEmpty()) wireGuardPort = ports.first()
                }
            }

            if (hasWireGuard && !pubKey.isNullOrBlank()) {
                servers += VpnServer(
                    id = "riseup_wg_$ip",
                    name = "Riseup • $locationName",
                    countryName = locationName,
                    countryCode = countryCode,
                    host = host.ifBlank { ip },
                    ipAddress = ip,
                    port = wireGuardPort,
                    protocol = VpnProtocol.WIREGUARD,
                    transport = "UDP",
                    directorySource = DirectorySource.RISEUP_VPN,
                    health = ServerHealth.UNTESTED,
                    operatorName = "Riseup Collective (LEAP)",
                    logPolicy = "No-logs non-profit privacy collective",
                    trustNotice = "Discovered from Riseup LEAP EIP directory. WireGuard gateway requires LEAP session allocation.",
                    supportsIpv6 = capabilities?.get("ip6")?.jsonPrimitive?.booleanOrNull ?: false,
                    inAppTunnelSupported = false,
                    peerPublicKey = pubKey
                )
            }

            if (hasOpenVpn || !hasWireGuard) {
                servers += VpnServer(
                    id = "riseup_ovpn_$ip",
                    name = "Riseup • $locationName",
                    countryName = locationName,
                    countryCode = countryCode,
                    host = host.ifBlank { ip },
                    ipAddress = ip,
                    port = openVpnPort,
                    protocol = VpnProtocol.OPENVPN,
                    transport = openVpnProto,
                    directorySource = DirectorySource.RISEUP_VPN,
                    health = ServerHealth.UNTESTED,
                    operatorName = "Riseup Collective (LEAP)",
                    logPolicy = "No-logs non-profit privacy collective",
                    trustNotice = "Public RiseupVPN OpenVPN gateway from api.black.riseup.net. Requires LEAP client certificate/OpenVPN client.",
                    supportsIpv6 = false,
                    inAppTunnelSupported = false
                )
            }
        }
        return servers
    }
}
