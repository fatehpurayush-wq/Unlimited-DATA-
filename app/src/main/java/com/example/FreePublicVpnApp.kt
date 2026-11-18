package com.example

import android.app.Application
import com.example.data.VpnServerRepository
import com.example.data.local.SettingsRepository
import com.example.data.local.VpnDatabase
import com.example.data.network.CloudflareWarpClient
import com.example.data.network.RiseupDirectoryClient
import com.example.data.network.ServerHealthProber
import com.example.data.network.VpnGateDirectoryClient
import com.example.data.security.AndroidKeyStoreCryptoManager
import com.example.data.security.CryptoManager
import com.example.vpn.VpnConnectionController
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class FreePublicVpnApp : Application() {

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    val cryptoManager: CryptoManager by lazy {
        AndroidKeyStoreCryptoManager()
    }

    val database: VpnDatabase by lazy {
        VpnDatabase.getInstance(this)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    val healthProber: ServerHealthProber by lazy {
        ServerHealthProber(httpClient)
    }

    val serverRepository: VpnServerRepository by lazy {
        VpnServerRepository(
            serverDao = database.serverDao(),
            cloudflareWarpClient = CloudflareWarpClient(httpClient, cryptoManager),
            vpnGateDirectoryClient = VpnGateDirectoryClient(httpClient, cryptoManager),
            riseupDirectoryClient = RiseupDirectoryClient(httpClient),
            healthProber = healthProber,
            cryptoManager = cryptoManager
        )
    }

    val connectionController: VpnConnectionController by lazy {
        VpnConnectionController(
            context = this,
            repository = serverRepository,
            settingsRepository = settingsRepository,
            healthProber = healthProber
        )
    }
}
