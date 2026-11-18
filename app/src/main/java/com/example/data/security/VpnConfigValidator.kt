package com.example.data.security

import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

sealed class ValidationResult {
    data class ValidWireGuard(
        val config: Config,
        val endpointHost: String,
        val endpointPort: Int,
        val peerPublicKey: String,
        val supportsIpv6: Boolean,
        val sanitizedPreview: String
    ) : ValidationResult()

    data class ValidOpenVpn(
        val remoteHost: String,
        val remotePort: Int,
        val transport: String,
        val cipher: String,
        val sanitizedPreview: String
    ) : ValidationResult()

    data class Invalid(val reason: String) : ValidationResult()
}

object VpnConfigValidator {

    private val remoteRegex = Regex("""^\s*remote\s+([^\s#;]+)(?:\s+(\d+))?(?:\s+(udp|tcp|udp4|tcp4|udp6|tcp6|tcp-client))?""", RegexOption.IGNORE_CASE)
    private val protoRegex = Regex("""^\s*proto\s+(udp|tcp|udp4|tcp4|udp6|tcp6|tcp-client)""", RegexOption.IGNORE_CASE)
    private val cipherRegex = Regex("""^\s*(?:cipher|data-ciphers)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val ipv4Regex = Regex("""^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$""")

    fun isValidDnsIp(ip: String): Boolean {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) return false
        if (ipv4Regex.matches(trimmed)) return true
        return trimmed.contains(":") && trimmed.length in 3..45 && trimmed.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':'
        }
    }

    fun validateWireGuard(rawConfig: String): ValidationResult {
        if (rawConfig.isBlank()) {
            return ValidationResult.Invalid("WireGuard configuration is empty.")
        }
        return try {
            val stream = ByteArrayInputStream(rawConfig.toByteArray(StandardCharsets.UTF_8))
            val parsed = Config.parse(stream)
            val peer = parsed.peers.firstOrNull()
                ?: return ValidationResult.Invalid("WireGuard config must include at least one [Peer] section.")
            val endpoint = peer.endpoint.orElse(null)
                ?: return ValidationResult.Invalid("WireGuard [Peer] must define a valid Endpoint host:port.")
            val addresses = parsed.`interface`.addresses
            if (addresses.isEmpty()) {
                return ValidationResult.Invalid("WireGuard [Interface] must specify an Address CIDR.")
            }
            val supportsIpv6 = addresses.any { it.address is java.net.Inet6Address } ||
                peer.allowedIps.any { it.address is java.net.Inet6Address }

            ValidationResult.ValidWireGuard(
                config = parsed,
                endpointHost = endpoint.host,
                endpointPort = endpoint.port,
                peerPublicKey = peer.publicKey.toBase64(),
                supportsIpv6 = supportsIpv6,
                sanitizedPreview = redactSensitiveWireGuardLines(rawConfig)
            )
        } catch (e: Exception) {
            ValidationResult.Invalid("Invalid WireGuard configuration: ${e.message ?: "parse error"}")
        }
    }

    fun validateOpenVpn(rawOvpn: String): ValidationResult {
        if (rawOvpn.isBlank()) {
            return ValidationResult.Invalid("OpenVPN configuration is empty.")
        }
        val lines = rawOvpn.lines()
        val hasClientDirective = lines.any { it.trim().equals("client", ignoreCase = true) }
        val hasDevTun = lines.any { it.trim().startsWith("dev ", ignoreCase = true) }
        if (!hasClientDirective || !hasDevTun) {
            return ValidationResult.Invalid("OpenVPN profile missing required 'client' or 'dev tun' directives.")
        }

        var host: String? = null
        var port = 1194
        var proto = "UDP"
        var cipher = "AES-256-GCM"

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            remoteRegex.find(trimmed)?.let { match ->
                if (host == null) {
                    host = match.groupValues[1]
                    match.groupValues.getOrNull(2)?.toIntOrNull()?.let { p ->
                        if (p in 1..65535) port = p
                    }
                    match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { pr ->
                        proto = if (pr.lowercase().startsWith("tcp")) "TCP" else "UDP"
                    }
                }
            }
            protoRegex.find(trimmed)?.let { match ->
                val pr = match.groupValues[1].lowercase()
                proto = if (pr.startsWith("tcp")) "TCP" else "UDP"
            }
            cipherRegex.find(trimmed)?.let { match ->
                cipher = match.groupValues[1].trim().take(48)
            }
        }

        val resolvedHost = host
            ?: return ValidationResult.Invalid("OpenVPN profile does not specify a valid 'remote' endpoint.")

        val hasCa = rawOvpn.contains("<ca>", ignoreCase = true) ||
            lines.any { it.trim().startsWith("ca ", ignoreCase = true) }
        if (!hasCa) {
            return ValidationResult.Invalid("OpenVPN profile is missing a CA certificate (<ca>).")
        }

        return ValidationResult.ValidOpenVpn(
            remoteHost = resolvedHost,
            remotePort = port,
            transport = proto,
            cipher = cipher,
            sanitizedPreview = redactSensitiveOpenVpnLines(rawOvpn)
        )
    }

    fun redactSensitiveWireGuardLines(raw: String): String {
        return raw.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("PrivateKey", ignoreCase = true) -> "PrivateKey = [REDACTED • STORED IN KEYSTORE]"
                trimmed.startsWith("PresharedKey", ignoreCase = true) -> "PresharedKey = [REDACTED • STORED IN KEYSTORE]"
                else -> line
            }
        }
    }

    fun redactSensitiveOpenVpnLines(raw: String): String {
        val result = StringBuilder()
        var insidePrivateKey = false
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.equals("<key>", ignoreCase = true)) {
                insidePrivateKey = true
                result.appendLine("<key>")
                result.appendLine("[PRIVATE KEY REDACTED]")
                continue
            }
            if (trimmed.equals("</key>", ignoreCase = true)) {
                insidePrivateKey = false
                result.appendLine("</key>")
                continue
            }
            if (!insidePrivateKey) {
                result.appendLine(line)
            }
        }
        return result.toString().trim()
    }
}
