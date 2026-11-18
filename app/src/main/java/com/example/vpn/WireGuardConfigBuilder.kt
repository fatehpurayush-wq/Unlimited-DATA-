package com.example.vpn

import com.example.data.security.VpnConfigValidator
import com.example.model.DnsMode
import com.example.model.SplitTunnelMode
import com.example.model.UserPreferences
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets

object WireGuardConfigBuilder {

    fun buildTunnelConfig(
        rawConfigText: String,
        preferences: UserPreferences,
        selfPackageName: String
    ): Config {
        val baseConfig = Config.parse(
            ByteArrayInputStream(rawConfigText.toByteArray(StandardCharsets.UTF_8))
        )
        val baseInterface = baseConfig.`interface`

        val interfaceBuilder = Interface.Builder()
            .setKeyPair(baseInterface.keyPair)

        // Addresses (IPv4 always, IPv6 only if enabled in settings)
        val addresses = baseInterface.addresses.filter { addr ->
            preferences.ipv6Enabled || addr.address is Inet4Address
        }
        if (addresses.isNotEmpty()) {
            interfaceBuilder.addAddresses(addresses)
        } else {
            interfaceBuilder.addAddresses(baseInterface.addresses)
        }

        // DNS Leak Protection: enforce user-selected DNS resolver inside the tunnel
        val dnsServers = resolveDnsAddresses(preferences)
        if (dnsServers.isNotEmpty()) {
            interfaceBuilder.addDnsServers(dnsServers)
        } else if (baseInterface.dnsServers.isNotEmpty()) {
            interfaceBuilder.addDnsServers(baseInterface.dnsServers)
        }

        baseInterface.mtu.ifPresent { mtu ->
            interfaceBuilder.setMtu(mtu)
        }

        // Split tunneling
        when (preferences.splitTunnelMode) {
            SplitTunnelMode.DISABLED -> {
                // Exclude self only if needed; GoBackend already protects its own sockets
            }
            SplitTunnelMode.EXCLUDE_SELECTED -> {
                val excluded = preferences.splitTunnelPackages
                    .filter { it.isNotBlank() && it != selfPackageName }
                if (excluded.isNotEmpty()) {
                    interfaceBuilder.excludeApplications(excluded)
                }
            }
            SplitTunnelMode.INCLUDE_ONLY_SELECTED -> {
                val included = preferences.splitTunnelPackages
                    .filter { it.isNotBlank() }
                if (included.isNotEmpty()) {
                    interfaceBuilder.includeApplications(included)
                }
            }
        }

        val configBuilder = Config.Builder().setInterface(interfaceBuilder.build())

        for (basePeer in baseConfig.peers) {
            val peerBuilder = Peer.Builder()
                .setPublicKey(basePeer.publicKey)

            basePeer.endpoint.ifPresent { peerBuilder.setEndpoint(it) }
            basePeer.preSharedKey.ifPresent { peerBuilder.setPreSharedKey(it) }
            peerBuilder.setPersistentKeepalive(basePeer.persistentKeepalive.orElse(25))

            val allowedIps = basePeer.allowedIps.filter { cidr ->
                preferences.ipv6Enabled || cidr.address is Inet4Address
            }
            if (allowedIps.isNotEmpty()) {
                allowedIps.forEach { peerBuilder.addAllowedIp(it) }
            } else {
                peerBuilder.addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                if (preferences.ipv6Enabled) {
                    peerBuilder.addAllowedIp(InetNetwork.parse("::/0"))
                }
            }
            configBuilder.addPeer(peerBuilder.build())
        }

        return configBuilder.build()
    }

    fun resolveDnsAddresses(preferences: UserPreferences): List<InetAddress> {
        val ips = mutableListOf<String>()
        when (preferences.dnsMode) {
            DnsMode.CLOUDFLARE, DnsMode.QUAD9, DnsMode.ADGUARD -> {
                ips += preferences.dnsMode.primaryIp
                ips += preferences.dnsMode.secondaryIp
                if (preferences.ipv6Enabled && preferences.dnsMode.ipv6Primary.isNotBlank()) {
                    ips += preferences.dnsMode.ipv6Primary
                }
            }
            DnsMode.CUSTOM -> {
                if (VpnConfigValidator.isValidDnsIp(preferences.customDnsIp)) {
                    ips += preferences.customDnsIp.trim()
                } else {
                    ips += DnsMode.CLOUDFLARE.primaryIp
                }
            }
        }
        return ips.mapNotNull { ip ->
            runCatching { InetAddress.getByName(ip) }.getOrNull()
        }.filter { addr ->
            preferences.ipv6Enabled || addr !is Inet6Address
        }
    }

    fun activeDnsLabel(preferences: UserPreferences): String {
        return when (preferences.dnsMode) {
            DnsMode.CUSTOM -> if (VpnConfigValidator.isValidDnsIp(preferences.customDnsIp)) {
                preferences.customDnsIp.trim()
            } else {
                "1.1.1.1 (Fallback)"
            }
            else -> preferences.dnsMode.primaryIp
        }
    }
}
