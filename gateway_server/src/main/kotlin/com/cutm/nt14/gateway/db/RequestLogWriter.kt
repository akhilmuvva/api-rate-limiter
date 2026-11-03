package com.cutm.nt14.gateway.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** One row of request_logs, already privacy-transformed (no clear-text IP). */
data class LogEntry(
    val id: Long,
    val tsMs: Long,
    val clientId: String,
    val clientIpHash: String?,
    val method: String,
    val path: String,
    val status: Int,
    val latencyMs: Long,
    val decision: String,
    val source: String,     // "real" | "demo"
    val upstream: String?,
    val limiterMs: Long? = null,
    val upstreamMs: Long? = null,
    val totalMs: Long? = null
)

/**
 * Asynchronous, batched writer for request_logs.
 *
 * The request hot path only calls [offer], which is a non-blocking `ArrayBlockingQueue.offer`.
 * When the queue is full the entry is dropped and [dropped] is incremented (drop-and-count),
 * so a slow or unavailable database can never stall request handling.
 * A background coroutine drains the queue every [flushIntervalMs] in batches of [batchSize].
 * Each batch also upserts per-client counters into `clients`.
 */
class RequestLogWriter(
    private val db: GatewayDatabase,
    val capacity: Int = 10_000,
    private val batchSize: Int = 1_000,
    private val flushIntervalMs: Long = 1_000L
) {
    private val logger = LoggerFactory.getLogger(RequestLogWriter::class.java)
    private val queue = ArrayBlockingQueue<LogEntry>(capacity)

    val dropped = AtomicLong(0)
    val written = AtomicLong(0)
    val batches = AtomicLong(0)
    val failures = AtomicLong(0)
    @Volatile var lastFlushAt: Long = 0L
        private set

    private var job: Job? = null

    fun offer(entry: LogEntry): Boolean {
        val ok = queue.offer(entry)
        if (!ok) dropped.incrementAndGet()
        return ok
    }

    fun pending(): Int = queue.size

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(flushIntervalMs)
                runCatching { flushNow() }.onFailure { logger.warn("request_logs flush failed: ${it.message}") }
            }
        }
    }

    /** Drains everything currently queued in batches. Returns the number of rows written. */
    @Synchronized
    fun flushNow(): Int {
        var total = 0
        while (true) {
            val batch = ArrayList<LogEntry>(batchSize)
            queue.drainTo(batch, batchSize)
            if (batch.isEmpty()) break
            try {
                writeBatch(batch)
                total += batch.size
                written.addAndGet(batch.size.toLong())
                batches.incrementAndGet()
            } catch (e: Exception) {
                failures.incrementAndGet()
                dropped.addAndGet(batch.size.toLong())
                logger.error("Failed to persist ${batch.size} request logs: ${e.message}")
                break
            }
            if (batch.size < batchSize) break
        }
        lastFlushAt = System.currentTimeMillis()
        return total
    }

    private fun writeBatch(batch: List<LogEntry>) {
        db.withConnection { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """INSERT INTO request_logs (id, ts, client_id, client_ip_hash, method, path, status, latency_ms, decision, source, upstream, limiter_ms, upstream_ms, total_ms)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING"""
                ).use { ps ->
                    for (e in batch) {
                        ps.setLong(1, e.id)
                        ps.setTimestamp(2, Timestamp(e.tsMs))
                        ps.setString(3, e.clientId)
                        ps.setString(4, e.clientIpHash)
                        ps.setString(5, e.method)
                        ps.setString(6, e.path.take(512))
                        ps.setInt(7, e.status)
                        ps.setLong(8, e.latencyMs)
                        ps.setString(9, e.decision)
                        ps.setString(10, e.source)
                        ps.setString(11, e.upstream)
                        if (e.limiterMs != null) ps.setLong(12, e.limiterMs) else ps.setNull(12, java.sql.Types.BIGINT)
                        if (e.upstreamMs != null) ps.setLong(13, e.upstreamMs) else ps.setNull(13, java.sql.Types.BIGINT)
                        if (e.totalMs != null) ps.setLong(14, e.totalMs) else ps.setNull(14, java.sql.Types.BIGINT)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }

                // Per-client counters, aggregated inside the batch.
                data class Agg(var label: String, var source: String, var count: Long, var throttled: Long, var first: Long, var last: Long)
                val perClient = LinkedHashMap<String, Agg>()
                for (e in batch) {
                    val key = if (e.clientId.startsWith("ip:") && e.clientIpHash != null) "iphash:${e.clientIpHash}" else e.clientId
                    val a = perClient.getOrPut(key) { Agg(e.clientId, e.source, 0, 0, e.tsMs, e.tsMs) }
                    a.count++
                    if (e.status == 429 || e.decision != "allowed") a.throttled++
                    a.first = minOf(a.first, e.tsMs); a.last = maxOf(a.last, e.tsMs)
                }
                conn.prepareStatement(
                    """INSERT INTO clients (id, label, source, first_seen, last_seen, total_requests, throttled_count)
                       VALUES (?,?,?,?,?,?,?)
                       ON CONFLICT (id) DO UPDATE SET
                         label = EXCLUDED.label,
                         last_seen = GREATEST(clients.last_seen, EXCLUDED.last_seen),
                         total_requests = clients.total_requests + EXCLUDED.total_requests,
                         throttled_count = clients.throttled_count + EXCLUDED.throttled_count"""
                ).use { ps ->
                    for ((id, a) in perClient) {
                        ps.setString(1, id)
                        ps.setString(2, a.label)
                        ps.setString(3, a.source)
                        ps.setTimestamp(4, Timestamp(a.first))
                        ps.setTimestamp(5, Timestamp(a.last))
                        ps.setLong(6, a.count)
                        ps.setLong(7, a.throttled)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
