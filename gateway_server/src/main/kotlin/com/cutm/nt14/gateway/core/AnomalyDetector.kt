package com.cutm.nt14.gateway.core

import com.cutm.nt14.gateway.db.IpPrivacy
import com.cutm.nt14.gateway.models.GatewayEvent
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

data class ClientTrafficRecord(
    val timestamp: Long,
    val statusCode: Int,
    val endpoint: String
)

@Serializable
data class BanRecord(
    val bannedUntil: Long,
    val reason: String
)

/**
 * Real-time gateway-side Anomaly and DDoS detector.
 *
 * Monitors real-time failure rates and burst traffic per client ID.
 * Automatically enforces temporary IP bans with WebSocket telemetry alerts
 * when malicious behavior (credential stuffing, rapid scanning, DDoS surges) is detected.
 */
class AnomalyDetector(
    private val failureThreshold: Int = 12, // >= 12 4xx/5xx errors in 30s triggers ban
    private val burstVolumeThreshold: Int = 80, // >= 80 reqs in 10s triggers ban
    private val banDurationSeconds: Long = 300L // 5 minute ban
) {
    private val logger = LoggerFactory.getLogger(AnomalyDetector::class.java)

    // Tracks recent client traffic: clientId -> deque of records
    private val clientHistory = ConcurrentHashMap<String, ConcurrentLinkedDeque<ClientTrafficRecord>>()

    // Active bans: clientId -> BanRecord
    private val activeBans = ConcurrentHashMap<String, BanRecord>()

    /** Persistence hooks (set by the application once storage is ready). */
    @Volatile var onBan: ((clientId: String, record: BanRecord) -> Unit)? = null
    @Volatile var onUnban: ((clientId: String) -> Unit)? = null

    /** Restores a ban loaded from storage without re-triggering the persistence hook. */
    fun restoreBan(clientId: String, record: BanRecord) {
        if (record.bannedUntil > System.currentTimeMillis()) activeBans[clientId] = record
    }

    fun recordAndInspect(
        clientId: String,
        endpoint: String,
        statusCode: Int,
        webSocketManager: WebSocketManager? = null
    ): BanRecord? {
        // If already banned, return existing ban
        checkBan(clientId)?.let { return it }

        val now = System.currentTimeMillis()
        val history = clientHistory.computeIfAbsent(clientId) { ConcurrentLinkedDeque() }
        history.add(ClientTrafficRecord(now, statusCode, endpoint))

        // Evict older than 30 seconds
        val cutoff = now - 30_000L
        while (history.isNotEmpty() && (history.peekFirst()?.timestamp ?: now) < cutoff) {
            history.pollFirst()
        }

        // Anomaly Factor 1: Excessive HTTP failures (credential stuffing / scanning)
        val failureCount = history.count { it.statusCode >= 400 }
        if (failureCount >= failureThreshold) {
            val ban = banClient(clientId, banDurationSeconds, "Excessive failure rate ($failureCount errors in 30s)")
            webSocketManager?.broadcast(
                GatewayEvent(
                    type = "ip_blocked",
                    ip = clientId,
                    endpoint = endpoint,
                    status = 403,
                    latencyMs = 1L,
                    timestamp = System.currentTimeMillis() / 1000.0
                )
            )
            return ban
        }

        // Anomaly Factor 2: Extreme burst traffic flood (DDoS volume spike)
        val recent10sCount = history.count { it.timestamp >= (now - 10_000L) }
        if (recent10sCount >= burstVolumeThreshold) {
            val ban = banClient(clientId, banDurationSeconds, "DDoS flood spike ($recent10sCount requests in 10s)")
            webSocketManager?.broadcast(
                GatewayEvent(
                    type = "ip_blocked",
                    ip = clientId,
                    endpoint = endpoint,
                    status = 429,
                    latencyMs = 1L,
                    timestamp = System.currentTimeMillis() / 1000.0
                )
            )
            return ban
        }

        return null
    }

    private fun candidateKeys(clientId: String): List<String> {
        val keys = LinkedHashSet<String>()
        keys.add(clientId)
        val raw = clientId.removePrefix("ip:")
        keys.add(raw)
        keys.add(IpPrivacy.storedClientId(raw))
        keys.add(ClientTracker.maskId(raw))
        if (IpPrivacy.looksLikeIp(raw)) {
            val hash = IpPrivacy.hash(raw)
            keys.add(hash)
            keys.add("iphash:$hash")
        }
        return keys.toList()
    }

    fun checkBan(clientId: String): BanRecord? {
        val now = System.currentTimeMillis()
        for (key in candidateKeys(clientId)) {
            val ban = activeBans[key] ?: continue
            if (now > ban.bannedUntil) {
                activeBans.remove(key)
                continue
            }
            return ban
        }
        return null
    }

    fun isBanned(clientId: String): Boolean = checkBan(clientId) != null

    fun banClient(clientId: String, durationSeconds: Long, reason: String): BanRecord {
        val raw = clientId.removePrefix("ip:").removePrefix("demo:").trim()
        val storageKey = when {
            clientId.startsWith("iphash:") -> clientId
            IpPrivacy.looksLikeIp(raw) -> "iphash:${IpPrivacy.hash(raw)}"
            Regex("^[0-9a-f]{24}$").matches(raw) -> "iphash:$raw"
            else -> clientId
        }
        val until = System.currentTimeMillis() + (durationSeconds * 1000L)
        val record = BanRecord(bannedUntil = until, reason = reason)
        activeBans[storageKey] = record
        logger.warn("BAN TRIGGERED for $storageKey: $reason (Banned until $until)")
        runCatching { onBan?.invoke(storageKey, record) }
        return record
    }

    fun unbanClient(clientId: String): Boolean {
        var removed = false
        val raw = clientId.removePrefix("ip:").removePrefix("demo:").trim()
        val extraKeys = buildList {
            addAll(candidateKeys(clientId))
            if (Regex("^[0-9a-f]{24}$").matches(raw)) {
                add(raw)
                add("iphash:$raw")
            }
        }.distinct()
        for (key in extraKeys) {
            if (activeBans.remove(key) != null) {
                removed = true
                runCatching { onUnban?.invoke(key) }
            }
        }
        return removed
    }

    fun getActiveBans(): Map<String, BanRecord> = activeBans.toMap()

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        activeBans.entries.removeIf { it.value.bannedUntil < now }
        val cutoff = now - 60_000L
        clientHistory.entries.removeIf { (_, deque) ->
            while (deque.isNotEmpty() && (deque.peekFirst()?.timestamp ?: now) < cutoff) {
                deque.pollFirst()
            }
            deque.isEmpty()
        }
    }
}
