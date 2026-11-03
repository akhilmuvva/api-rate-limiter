package com.cutm.nt14.gateway.db

import com.cutm.nt14.gateway.core.RateLimiter
import com.cutm.nt14.gateway.core.TrafficHistoryManager
import com.cutm.nt14.gateway.core.WebSocketManager
import com.cutm.nt14.gateway.core.BanRecord
import com.cutm.nt14.gateway.models.PersistenceStatus
import com.cutm.nt14.gateway.monitoring.TargetMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Wires PostgreSQL storage into the running gateway:
 * request logs (async batches), metrics_1m rollups, retention, durable rules/bans/incidents and target monitoring.
 */
class Persistence(
    val db: GatewayDatabase,
    val writer: RequestLogWriter = RequestLogWriter(db),
    val rollupIntervalMs: Long = 30_000L,
    val retentionIntervalMs: Long = 3_600_000L
) {
    private val logger = LoggerFactory.getLogger(Persistence::class.java)

    val logs = LogRepository(db)
    val metrics = MetricsRepository(db)
    val retention = RetentionJob(db)
    val rules = RuleStore(db)
    val bans = BanStore(db)
    val incidents = IncidentStore(db)
    val targets = TargetStore(db)
    val probes = ProbeStore(db)
    lateinit var monitor: TargetMonitor
        private set

    /** Loads durable state into the in-memory components and installs persistence hooks. */
    fun attach(rateLimiter: RateLimiter, history: TrafficHistoryManager, ws: WebSocketManager) {
        // Rules: seed defaults on first boot, afterwards the database is the source of truth.
        val storedRules = rules.all()
        if (storedRules.isEmpty()) {
            rateLimiter.getAllRules().forEach { rules.upsert(it) }
        } else {
            rateLimiter.replaceAllRules(storedRules)
        }
        rateLimiter.onRuleSet = { r -> runCatching { rules.upsert(r) }.onFailure { logger.warn("rule persist failed: ${it.message}") } }
        rateLimiter.onRuleRemoved = { e -> runCatching { rules.delete(e) } }

        // Bans survive restarts.
        bans.active().forEach { rateLimiter.anomalyDetector.restoreBan(it.clientKey, BanRecord(bannedUntil = it.expiresAt, reason = it.reason)) }
        rateLimiter.anomalyDetector.onBan = { key, rec -> runCatching { bans.upsert(key, rec.reason, rec.bannedUntil) } }
        rateLimiter.anomalyDetector.onUnban = { key -> runCatching { bans.delete(key) } }

        // Recent incidents into memory, new incidents to the database.
        incidents.recent(50).reversed().forEach { history.pushIncident(it) }
        history.incidentSink = { inc -> runCatching { incidents.insert(inc) } }

        // Restore historical request counts and recent logs into memory so /health metrics survive restarts.
        runCatching {
            val counts = logs.summaryCounts()
            val recentLogs = logs.page(LogQuery(limit = 200)).items.reversed()
            history.restoreFromDb(recentLogs, counts.allowed, counts.throttled, counts.serverErrors)
        }.onFailure { logger.warn("Failed to restore history metrics from DB: ${it.message}") }

        // Request logs: non-blocking enqueue on the hot path.
        history.logSink = { entry -> writer.offer(entry) }

        monitor = TargetMonitor(targets, probes, incidents, ws, history)
        ws.persistence = this
        logger.info("Persistence attached: ${storedRules.size} rules, ${bans.active().size} active bans restored")
    }

    fun start(scope: CoroutineScope, startMonitor: Boolean = true) {
        writer.start(scope)
        if (startMonitor) monitor.start(scope)

        scope.launch(Dispatchers.IO) {
            // Catch up on anything not yet rolled up (e.g. after a restart).
            runCatching { metrics.rollup(System.currentTimeMillis() - 2 * 3_600_000L, System.currentTimeMillis() + 60_000L) }
            while (isActive) {
                delay(rollupIntervalMs)
                runCatching {
                    val now = System.currentTimeMillis()
                    metrics.rollup(now - 3 * 60_000L, now + 60_000L)
                }.onFailure { logger.warn("metrics rollup failed: ${it.message}") }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val r = retention.run()
                    if (r.logsDeleted + r.metricsDeleted + r.probesDeleted + r.bansDeleted > 0) logger.info("Retention cleanup: $r")
                }.onFailure { logger.warn("retention failed: ${it.message}") }
                delay(retentionIntervalMs)
            }
        }
    }

    fun status() = PersistenceStatus(
        mode = "postgres",
        queued = writer.pending(),
        written = writer.written.get(),
        dropped = writer.dropped.get(),
        batches = writer.batches.get(),
        failures = writer.failures.get(),
        lastFlushAt = writer.lastFlushAt,
        lastRollupAt = metrics.lastRollupAt
    )

    /** Flushes queued logs and closes the pool (graceful shutdown). */
    fun shutdown(closePool: Boolean = true) {
        runCatching { writer.flushNow() }
        if (closePool) db.close()
    }
}
