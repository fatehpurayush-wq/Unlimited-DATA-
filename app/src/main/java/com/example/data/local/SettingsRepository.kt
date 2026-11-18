package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DnsMode
import com.example.model.ProtocolPreference
import com.example.model.SplitTunnelMode
import com.example.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.vpnSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "vpn_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val PROTOCOL_PREFERENCE = stringPreferencesKey("protocol_preference")
        val DNS_MODE = stringPreferencesKey("dns_mode")
        val CUSTOM_DNS_IP = stringPreferencesKey("custom_dns_ip")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SPLIT_TUNNEL_PACKAGES = stringSetPreferencesKey("split_tunnel_packages")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
    }

    val preferencesFlow: Flow<UserPreferences> = context.vpnSettingsDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            UserPreferences(
                autoConnect = prefs[Keys.AUTO_CONNECT] ?: false,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
                protocolPreference = prefs[Keys.PROTOCOL_PREFERENCE]?.let {
                    runCatching { ProtocolPreference.valueOf(it) }.getOrNull()
                } ?: ProtocolPreference.AUTO,
                dnsMode = prefs[Keys.DNS_MODE]?.let {
                    runCatching { DnsMode.valueOf(it) }.getOrNull()
                } ?: DnsMode.CLOUDFLARE,
                customDnsIp = prefs[Keys.CUSTOM_DNS_IP] ?: "",
                ipv6Enabled = prefs[Keys.IPV6_ENABLED] ?: true,
                splitTunnelMode = prefs[Keys.SPLIT_TUNNEL_MODE]?.let {
                    runCatching { SplitTunnelMode.valueOf(it) }.getOrNull()
                } ?: SplitTunnelMode.DISABLED,
                splitTunnelPackages = prefs[Keys.SPLIT_TUNNEL_PACKAGES] ?: emptySet(),
                notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
                selectedServerId = prefs[Keys.SELECTED_SERVER_ID]
            )
        }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.vpnSettingsDataStore.edit { it[Keys.AUTO_CONNECT] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.vpnSettingsDataStore.edit { it[Keys.AUTO_RECONNECT] = enabled }
    }

    suspend fun setProtocolPreference(preference: ProtocolPreference) {
        context.vpnSettingsDataStore.edit { it[Keys.PROTOCOL_PREFERENCE] = preference.name }
    }

    suspend fun setDnsMode(mode: DnsMode) {
        context.vpnSettingsDataStore.edit { it[Keys.DNS_MODE] = mode.name }
    }

    suspend fun setCustomDnsIp(ip: String) {
        context.vpnSettingsDataStore.edit { it[Keys.CUSTOM_DNS_IP] = ip.trim() }
    }

    suspend fun setIpv6Enabled(enabled: Boolean) {
        context.vpnSettingsDataStore.edit { it[Keys.IPV6_ENABLED] = enabled }
    }

    suspend fun setSplitTunnelMode(mode: SplitTunnelMode) {
        context.vpnSettingsDataStore.edit { it[Keys.SPLIT_TUNNEL_MODE] = mode.name }
    }

    suspend fun toggleSplitTunnelPackage(packageName: String) {
        context.vpnSettingsDataStore.edit { prefs ->
            val current = prefs[Keys.SPLIT_TUNNEL_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(packageName)) {
                current.remove(packageName)
            }
            prefs[Keys.SPLIT_TUNNEL_PACKAGES] = current
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.vpnSettingsDataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setSelectedServerId(serverId: String?) {
        context.vpnSettingsDataStore.edit { prefs ->
            if (serverId == null) prefs.remove(Keys.SELECTED_SERVER_ID)
            else prefs[Keys.SELECTED_SERVER_ID] = serverId
        }
    }
}
