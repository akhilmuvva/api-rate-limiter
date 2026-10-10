package com.cutm.nt14.gateway.core

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import org.slf4j.LoggerFactory

object TrustedProxyResolver {
    private val logger = LoggerFactory.getLogger("TrustedProxyResolver")

    fun isTrustedProxy(ip: String, trustedSet: Set<String>): Boolean {
        val cleanIp = ip.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (cleanIp == "127.0.0.1" || cleanIp == "::1" || cleanIp == "0:0:0:0:0:0:0:1" || cleanIp == "localhost") {
            return true
        }
        if (trustedSet.contains(cleanIp)) {
            return true
        }

        // Support private IP subnets (RFC 1918)
        if (cleanIp.startsWith("10.") || cleanIp.startsWith("192.168.")) return true
        if (cleanIp.startsWith("172.")) {
            val secondOctet = cleanIp.split(".").getOrNull(1)?.toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) return true
        }
        return false
    }

    fun getTrustedSet(): Set<String> {
        val trustedProxiesEnv = System.getenv("TRUSTED_PROXIES") ?: "127.0.0.1,::1,localhost,10.0.0.0/8"
        return trustedProxiesEnv.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    fun resolveClientIp(call: ApplicationCall): String {
        val remoteHost = call.request.origin.remoteHost.trim()
        val trustedSet = getTrustedSet()

        val isPeerTrusted = isTrustedProxy(remoteHost, trustedSet)

        // Rule 1: If immediate peer is UNTRUSTED, never accept any proxy headers (prevent spoofing)
        if (!isPeerTrusted) {
            return remoteHost
        }

        // Rule 2: Peer is trusted (e.g. Cloudflare / Render reverse proxy)
        // Check CF-Connecting-IP (set by Cloudflare edge, cannot be overridden by client)
        val cfIp = call.request.headers["CF-Connecting-IP"]?.trim()
        if (!cfIp.isNullOrBlank()) {
            return cfIp
        }

        val trueClientIp = call.request.headers["True-Client-IP"]?.trim()
        if (!trueClientIp.isNullOrBlank()) {
            return trueClientIp
        }

        // Rule 3: Parse X-Forwarded-For from right to left, skipping trusted proxies
        val xff = call.request.headers["X-Forwarded-For"]
        if (!xff.isNullOrBlank()) {
            val ips = xff.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for (i in ips.indices.reversed()) {
                val ip = ips[i]
                if (!isTrustedProxy(ip, trustedSet)) {
                    return ip
                }
            }
            return ips.lastOrNull() ?: remoteHost
        }

        return remoteHost
    }
}
