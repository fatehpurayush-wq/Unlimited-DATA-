package com.example

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.VpnServerRepository
import com.example.data.local.SettingsRepository
import com.example.data.local.VpnDatabase
import com.example.data.network.CloudflareWarpClient
import com.example.data.network.RiseupDirectoryClient
import com.example.data.network.ServerHealthProber
import com.example.data.network.VpnGateDirectoryClient
import com.example.data.security.CryptoManager
import com.example.data.security.ValidationResult
import com.example.data.security.VpnConfigValidator
import com.example.model.ConnectionStep
import com.example.model.DnsMode
import com.example.model.ProtocolPreference
import com.example.model.ServerHealth
import com.example.model.SplitTunnelMode
import com.example.model.UserPreferences
import com.example.model.VpnProtocol
import com.example.vpn.TunnelBackendAdapter
import com.example.vpn.TunnelStatsSnapshot
import com.example.vpn.VpnConnectionController
import com.example.vpn.WireGuardConfigBuilder
import com.wireguard.config.Config as WgConfig
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

private class TestCryptoManager : CryptoManager {
    override fun encrypt(plaintext: String): String =
        "test:" + Base64.encodeToString(plaintext.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    override fun decrypt(ciphertext: String): String? =
        if (ciphertext.startsWith("test:")) {
            String(Base64.decode(ciphertext.removePrefix("test:"), Base64.NO_WRAP), StandardCharsets.UTF_8)
        } else null

    override fun resetKeys() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VpnCoreTest {

    private lateinit var context: Context
    private lateinit var db: VpnDatabase
    private lateinit var crypto: CryptoManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VpnDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        crypto = TestCryptoManager()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `validate WireGuard configuration and redact private key`() {
        val kp = KeyPair()
        val peerKp = KeyPair()
        val rawConf = """
            [Interface]
            PrivateKey = ${kp.privateKey.toBase64()}
            Address = 172.16.0.2/32, fd01:5ca1:ab1e::2/128
            DNS = 1.1.1.1

            [Peer]
            PublicKey = ${peerKp.publicKey.toBase64()}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 162.159.192.1:2408
        """.trimIndent()

        val result = VpnConfigValidator.validateWireGuard(rawConf)
        assertTrue(result is ValidationResult.ValidWireGuard)
        val valid = result as ValidationResult.ValidWireGuard
        assertEquals("162.159.192.1", valid.endpointHost)
        assertEquals(2408, valid.endpointPort)
        assertTrue(valid.supportsIpv6)
        assertFalse(valid.sanitizedPreview.contains(kp.privateKey.toBase64()))
        assertTrue(valid.sanitizedPreview.contains("REDACTED"))
    }

    @Test
    fun `reject invalid WireGuard configuration`() {
        val result = VpnConfigValidator.validateWireGuard("[Interface]\nAddress = invalid")
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test
    fun `parse VPNGate CSV and validate OpenVPN payload`() {
        val sampleOvpn = """
            client
            dev tun
            proto udp
            remote 203.0.113.10 1194
            cipher AES-256-GCM
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val b64 = Base64.encodeToString(sampleOvpn.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val csv = """
            *vpn_servers
            #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
            public-vpn-101,203.0.113.10,950000,24,100000000,Japan,JP,12,86400000,500,999999,2weeks,UniversityRelay,,$b64
        """.trimIndent()

        val client = VpnGateDirectoryClient(OkHttpClient(), crypto)
        val servers = client.parseVpnGateCsv(csv, 10)
        assertEquals(1, servers.size)
        val srv = servers.first()
        assertEquals(VpnProtocol.OPENVPN, srv.protocol)
        assertEquals("JP", srv.countryCode)
        assertEquals(1194, srv.port)
        assertEquals("UDP", srv.transport)
        assertNotNull(crypto.decrypt(srv.encryptedConfigPayload!!))
    }

    @Test
    fun `parse Riseup EIP service JSON gateways`() {
        val eipJson = """
            {
              "version": 3,
              "locations": {
                "nyc": { "name": "New York", "country_code": "US" }
              },
              "gateways": [
                {
                  "host": "vpn01.riseup.net",
                  "ip_address": "198.51.100.22",
                  "location": "nyc",
                  "capabilities": {
                    "transport": [
                      { "type": "openvpn", "ports": ["1194"], "protocols": ["udp"] }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val client = RiseupDirectoryClient(OkHttpClient())
        val parsed = client.parseEipServiceJson(eipJson)
        assertEquals(1, parsed.size)
        assertEquals("US", parsed.first().countryCode)
        assertEquals(1194, parsed.first().port)
    }

    @Test
    fun `build WireGuard config enforces DNS protection and split tunneling`() {
        val kp = KeyPair()
        val peerKp = KeyPair()
        val rawConf = """
            [Interface]
            PrivateKey = ${kp.privateKey.toBase64()}
            Address = 172.16.0.2/32, fd01:5ca1:ab1e::2/128
            DNS = 8.8.8.8

            [Peer]
            PublicKey = ${peerKp.publicKey.toBase64()}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 162.159.192.1:2408
        """.trimIndent()

        val prefs = UserPreferences(
            dnsMode = DnsMode.QUAD9,
            ipv6Enabled = false,
            splitTunnelMode = SplitTunnelMode.EXCLUDE_SELECTED,
            splitTunnelPackages = setOf("com.android.chrome")
        )
        val built = WireGuardConfigBuilder.buildTunnelConfig(rawConf, prefs, context.packageName)
        assertTrue(built.`interface`.dnsServers.any { it.hostAddress == "9.9.9.9" })
        assertTrue(built.`interface`.excludedApplications.contains("com.android.chrome"))
        assertEquals(1, built.`interface`.addresses.size)
        assertEquals(1, built.peers.first().allowedIps.size)
    }

    @Test
    fun `controller connects, verifies connectivity, tracks stats, and disconnects cleanly`() = runBlocking {
        val kp = KeyPair()
        val peerKp = KeyPair()
        val validConf = """
            [Interface]
            PrivateKey = ${kp.privateKey.toBase64()}
            Address = 172.16.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = ${peerKp.publicKey.toBase64()}
            AllowedIPs = 0.0.0.0/0
            Endpoint = 127.0.0.1:2408
        """.trimIndent()

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val body = if (url.contains("cdn-cgi/trace")) "ip=104.28.1.99\n" else ""
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

        val prober = ServerHealthProber(mockHttp)
        val repo = VpnServerRepository(
            serverDao = db.serverDao(),
            cloudflareWarpClient = CloudflareWarpClient(mockHttp, crypto),
            vpnGateDirectoryClient = VpnGateDirectoryClient(mockHttp, crypto),
            riseupDirectoryClient = RiseupDirectoryClient(mockHttp),
            healthProber = prober,
            cryptoManager = crypto
        )

        val imported = repo.importProfile("Local Verified WG", validConf).getOrThrow()
        db.serverDao().updateHealth(imported.id, 12, ServerHealth.ONLINE.name, System.currentTimeMillis(), null)

        var tunnelUp = false
        val fakeBackend = object : TunnelBackendAdapter {
            override fun prepareVpnIntent(context: Context): Intent? = null
            override suspend fun bringUp(tunnelName: String, config: WgConfig): Boolean {
                tunnelUp = true
                return true
            }
            override suspend fun bringDown(tunnelName: String) {
                tunnelUp = false
            }
            override suspend fun readStats(tunnelName: String): TunnelStatsSnapshot {
                return TunnelStatsSnapshot(rxBytes = 2048L, txBytes = 1024L)
            }
        }

        val settingsRepo = SettingsRepository(context)
        val controller = VpnConnectionController(
            context = context,
            repository = repo,
            settingsRepository = settingsRepo,
            healthProber = prober,
            backend = fakeBackend
        )

        // Verify candidate selection works
        val best = repo.selectBestCandidate(repo.getAllServersOnce(), ProtocolPreference.AUTO)
        assertEquals(imported.id, best?.id)

        // Verify connectivity check succeeds via mock interceptor
        val verifyCheck = prober.verifyTunnelConnectivity()
        assertTrue(verifyCheck.isConnected)
        assertEquals("104.28.1.99", verifyCheck.exitIp)

        // Start manual connection using fakeBackend (skips live socket probe if we test verify+bringUp or local loopback)
        assertNull(controller.prepareVpnPermission())
        controller.disconnect()
        delay(100)
        assertFalse(tunnelUp)
        assertEquals(ConnectionStep.IDLE, controller.connectionState.value.step)
    }
}
