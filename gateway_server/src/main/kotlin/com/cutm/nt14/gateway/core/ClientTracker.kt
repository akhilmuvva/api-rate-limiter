package com.cutm.nt14.gateway.core

import com.cutm.nt14.gateway.models.ClientInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class ClientTracker {
    private val clientMap = ConcurrentHashMap<String, ClientRecord>()

    private class ClientRecord(
        val id: String,
        val isDemo: Boolean
    ) {
        val totalRequests = AtomicLong(0)
        val throttledRequests = AtomicLong(0)
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        val requestTimestamps = java.util.concurrent.ConcurrentLinkedDeque<Long>()

        fun record(isThrottled: Boolean, now: Long = System.currentTimeMillis()) {
            lastSeen = now
            totalRequests.incrementAndGet()
            if (isThrottled) {
                throttledRequests.incrementAndGet()
            }
            requestTimestamps.addLast(now)
            val boundary = now - 60_000L
            while (requestTimestamps.peekFirst()?.let { it < boundary } == true) {
                requestTimestamps.pollFirst()
            }
        }

        fun getRpm(now: Long = System.currentTimeMillis()): Double {
            val boundary = now - 60_000L
            while (requestTimestamps.peekFirst()?.let { it < boundary } == true) {
                requestTimestamps.pollFirst()
            }
            return requestTimestamps.size.toDouble()
        }
    }

    fun recordRequest(clientId: String, isThrottled: Boolean, isDemo: Boolean = false) {
        val now = System.currentTimeMillis()
        val record = clientMap.computeIfAbsent(clientId) {
            ClientRecord(clientId, isDemo)
        }
        record.record(isThrottled, now)
    }

    fun getAllClients(isAdmin: Boolean, anomalyDetector: AnomalyDetector? = null): List<ClientInfo> {
        val now = System.currentTimeMillis()
        cleanExpired(now)

        return clientMap.values.map { record ->
            val isBanned = anomalyDetector?.isBanned(record.id) ?: false
            val rpm = record.getRpm(now)
            val status = when {
                isBanned -> "banned"
                record.throttledRequests.get() > 0 && rpm > 0 -> "throttled"
                else -> "active"
            }

            val masked = maskId(record.id)
            val effectiveId = if (isAdmin) record.id else masked

            ClientInfo(
                id = effectiveId,
                maskedId = masked,
                requestsPerMin = ((rpm * 10.0).roundToInt()) / 10.0,
                totalRequests = record.totalRequests.get(),
                throttledCount = record.throttledRequests.get(),
                lastSeen = record.lastSeen,
                status = status,
                isDemo = record.isDemo
            )
        }.sortedByDescending { it.requestsPerMin }
    }

    fun cleanExpired(now: Long = System.currentTimeMillis()) {
        val oneHourAgo = now - 3600_000L
        val iterator = clientMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.lastSeen < oneHourAgo) {
                iterator.remove()
            }
        }
    }

    companion object {
        fun maskId(id: String): String {
            if (id.startsWith("user:")) {
                val email = id.removePrefix("user:")
                val parts = email.split("@")
                if (parts.size == 2) {
                    val user = parts[0]
                    val domain = parts[1]
                    val maskedUser = if (user.length <= 3) "${user.first()}***" else "${user.take(3)}***"
                    return "user:$maskedUser@$domain"
                }
                return "user:***"
            }

            // IPv4: 192.168.1.100 -> 192.168.***.***
            val octets = id.split(".")
            if (octets.size == 4) {
                return "${octets[0]}.${octets[1]}.***.***"
            }

            // IPv6
            if (id.contains(":")) {
                val chunks = id.split(":")
                return if (chunks.size > 2) "${chunks.first()}:****:****:${chunks.last()}" else "ipv6:****"
            }

            return if (id.length > 4) "${id.take(3)}***" else "***"
        }
    }
}
