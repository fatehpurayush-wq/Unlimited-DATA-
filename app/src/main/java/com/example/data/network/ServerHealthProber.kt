package com.example.data.network

import com.example.model.DirectorySource
import com.example.model.ServerHealth
import com.example.model.VpnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class ProbeResult(
    val serverId: String,
    val latencyMs: Int?,
    val health: ServerHealth,
    val error: String? = null
)

data class ConnectivityCheckResult(
    val isConnected: Boolean,
    val exitIp: String? = null,
    val latencyMs: Int? = null,
    val error: String? = null
)

class ServerHealthProber(
    private val httpClient: OkHttpClient
) {
    suspend fun probeServers(servers: List<VpnServer>): List<ProbeResult> = coroutineScope {
        val semaphore = Semaphore(8)
        servers.map { server ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    probeSingle(server)
                }
            }
        }.awaitAll()
    }

    suspend fun probeSingle(server: VpnServer): ProbeResult = withContext(Dispatchers.IO) {
        val targetHost = server.ipAddress.ifBlank { server.host }
        if (targetHost.isBlank()) {
            return@withContext ProbeResult(server.id, null, ServerHealth.UNREACHABLE, "Missing server address")
        }

        val latency: Int? = when {
            server.directorySource == DirectorySource.CLOUDFLARE_WARP -> {
                // Measure real HTTPS/TCP RTT to Cloudflare edge endpoint (443) or API host
                measureTcpHandshakeMs(targetHost, 443, 2500)
                    ?: measureTcpHandshakeMs("1.1.1.1", 443, 2500)
            }
            server.transport.equals("TCP", ignoreCase = true) -> {
                measureTcpHandshakeMs(targetHost, server.port, 2500)
            }
            else -> {
                // UDP endpoints: try TCP fallback ports or ICMP reachability timing
                measureTcpHandshakeMs(targetHost, server.port, 1500)
                    ?: measureTcpHandshakeMs(targetHost, 443, 1500)
                    ?: measureIcmpReachableMs(targetHost, 2000)
            }
        }

        if (latency == null) {
            ProbeResult(
                serverId = server.id,
                latencyMs = null,
                health = ServerHealth.UNREACHABLE,
                error = "Timeout reaching $targetHost:${server.port}"
            )
        } else {
            val health = when {
                latency <= 180 && server.activeSessions < 120 -> ServerHealth.ONLINE
                else -> ServerHealth.DEGRADED
            }
            ProbeResult(
                serverId = server.id,
                latencyMs = latency,
                health = health
            )
        }
    }

    suspend fun verifyTunnelConnectivity(): ConnectivityCheckResult = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        try {
            val request = Request.Builder()
                .url("https://1.1.1.1/cdn-cgi/trace")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).toInt().coerceAtLeast(1)
                if (!response.isSuccessful) {
                    return@withContext ConnectivityCheckResult(
                        isConnected = false,
                        error = "Connectivity check returned HTTP ${response.code}"
                    )
                }
                val body = response.body?.string().orEmpty()
                val ipLine = body.lines().firstOrNull { it.startsWith("ip=") }
                val exitIp = ipLine?.removePrefix("ip=")?.trim()
                ConnectivityCheckResult(
                    isConnected = true,
                    exitIp = exitIp,
                    latencyMs = elapsedMs
                )
            }
        } catch (e: Exception) {
            // Secondary verification endpoint in case 1.1.1.1 trace is blocked
            try {
                val req2 = Request.Builder()
                    .url("https://connectivitycheck.gstatic.com/generate_204")
                    .get()
                    .build()
                httpClient.newCall(req2).execute().use { resp2 ->
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).toInt().coerceAtLeast(1)
                    if (resp2.code == 204 || resp2.isSuccessful) {
                        ConnectivityCheckResult(isConnected = true, latencyMs = elapsedMs)
                    } else {
                        ConnectivityCheckResult(false, error = "Verification failed: HTTP ${resp2.code}")
                    }
                }
            } catch (e2: Exception) {
                ConnectivityCheckResult(
                    isConnected = false,
                    error = "Tunnel connectivity check failed: ${e2.message ?: e.message ?: "timeout"}"
                )
            }
        }
    }

    private fun measureTcpHandshakeMs(host: String, port: Int, timeoutMs: Int): Int? {
        if (port !in 1..65535) return null
        return runCatching {
            val startNs = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).toInt().coerceAtLeast(1)
        }.getOrNull()
    }

    private fun measureIcmpReachableMs(host: String, timeoutMs: Int): Int? {
        return runCatching {
            val addr = InetAddress.getByName(host)
            val startNs = System.nanoTime()
            if (addr.isReachable(timeoutMs)) {
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).toInt().coerceAtLeast(1)
            } else {
                null
            }
        }.getOrNull()
    }
}
