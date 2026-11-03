package com.cutm.nt14.gateway.db

import com.cutm.nt14.gateway.models.*
import java.io.Writer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/** Filters for GET /api/logs and the CSV export. All fields optional. */
data class LogQuery(
    val cursor: Long? = null,
    val limit: Int = 50,
    val status: String? = null,   // "429", "5xx", "4xx", "2xx"
    val client: String? = null,   // "user:x@y", "ip:1.2.3.x", "iphash:...", or a raw IP (hashed before matching)
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val source: String? = null    // "real" | "demo" | null = both
)

object TimeParam {
    /** Accepts epoch millis or an ISO-8601 instant. */
    fun parse(v: String?): Long? {
        if (v.isNullOrBlank()) return null
        v.trim().toLongOrNull()?.let { return it }
        return runCatching { Instant.parse(v.trim()).toEpochMilli() }.getOrNull()
    }
}

fun normalizeSource(v: String?): String? = when (v?.trim()?.lowercase()) {
    "real" -> "real"
    "demo" -> "demo"
    else -> null
}

private class Where {
    val clauses = mutableListOf<String>()
    val binds = mutableListOf<(PreparedStatement, Int) -> Unit>()
    fun add(sql: String, vararg setters: (PreparedStatement, Int) -> Unit) { clauses += sql; binds += setters }
    fun sql() = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
    fun bind(ps: PreparedStatement, start: Int = 1): Int {
        var i = start
        binds.forEach { it(ps, i++) }
        return i
    }
}

private fun logWhere(q: LogQuery, includeCursor: Boolean = true): Where {
    val w = Where()
    if (includeCursor) q.cursor?.let { c -> w.add("id < ?", { ps, i -> ps.setLong(i, c) }) }
    q.fromMs?.let { f -> w.add("ts >= ?", { ps, i -> ps.setTimestamp(i, Timestamp(f)) }) }
    q.toMs?.let { t -> w.add("ts < ?", { ps, i -> ps.setTimestamp(i, Timestamp(t)) }) }
    normalizeSource(q.source)?.let { s -> w.add("source = ?", { ps, i -> ps.setString(i, s) }) }
    q.status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { st ->
        val cls = Regex("^([1-5])xx$").find(st)?.groupValues?.get(1)?.toInt()
        when {
            cls != null -> w.add("status >= ? AND status < ?", { ps, i -> ps.setInt(i, cls * 100) }, { ps, i -> ps.setInt(i, cls * 100 + 100) })
            st.toIntOrNull() != null -> w.add("status = ?", { ps, i -> ps.setInt(i, st.toInt()) })
        }
    }
    q.client?.trim()?.takeIf { it.isNotEmpty() }?.let { c ->
        when {
            c.startsWith("iphash:") -> w.add("client_ip_hash = ?", { ps, i -> ps.setString(i, c.removePrefix("iphash:")) })
            IpPrivacy.looksLikeIp(c) -> w.add("client_ip_hash = ?", { ps, i -> ps.setString(i, IpPrivacy.hash(c)) })
            else -> w.add("client_id = ?", { ps, i -> ps.setString(i, c) })
        }
    }
    return w
}

private fun ResultSet.toLogDto() = RequestLogDto(
    id = getLong("id").toString(),
    timestamp = getTimestamp("ts").time,
    clientId = getString("client_id"),
    method = getString("method"),
    path = getString("path"),
    status = getInt("status"),
    latencyMs = getLong("latency_ms"),
    decision = getString("decision"),
    source = getString("source"),
    upstream = getString("upstream"),
    clientIpHash = getString("client_ip_hash")
)

class LogRepository(private val db: GatewayDatabase) {

    /** Keyset pagination: newest first, `cursor` is the last id of the previous page. */
    fun page(q: LogQuery): LogsPage {
        val limit = q.limit.coerceIn(1, 500)
        val w = logWhere(q)
        val sql = "SELECT * FROM request_logs ${w.sql()} ORDER BY id DESC LIMIT ?"
        val rows = db.withConnection { c ->
            c.prepareStatement(sql).use { ps ->
                val next = w.bind(ps)
                ps.setInt(next, limit + 1)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toLogDto() else null }.toList() }
            }
        }
        val hasMore = rows.size > limit
        val items = rows.take(limit)
        return LogsPage(
            items = items,
            nextCursor = if (hasMore) items.last().id else null,
            limit = limit,
            source = normalizeSource(q.source) ?: "all",
            persistence = "postgres",
            generatedAt = System.currentTimeMillis()
        )
    }

    fun since(lastId: Long, max: Int = 500): List<RequestLogDto> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM request_logs WHERE id > ? ORDER BY id ASC LIMIT ?").use { ps ->
            ps.setLong(1, lastId); ps.setInt(2, max)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toLogDto() else null }.toList() }
        }
    }

    fun count(source: String? = null): Long = db.withConnection { c ->
        val s = normalizeSource(source)
        c.prepareStatement("SELECT count(*) FROM request_logs" + if (s != null) " WHERE source = ?" else "").use { ps ->
            if (s != null) ps.setString(1, s)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Streams matching rows as CSV (oldest first). Returns the number of rows written. */
    fun exportCsv(q: LogQuery, out: Writer, maxRows: Int = 500_000): Int {
        val w = logWhere(q, includeCursor = false)
        out.write("id,ts,client_id,client_ip_hash,method,path,status,latency_ms,decision,source,upstream\n")
        var n = 0
        db.withConnection { c ->
            c.autoCommit = false // required for cursor-based streaming in the PG driver
            try {
                c.prepareStatement("SELECT * FROM request_logs ${w.sql()} ORDER BY id ASC LIMIT ?").use { ps ->
                    ps.fetchSize = 1000
                    val next = w.bind(ps)
                    ps.setInt(next, maxRows)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            out.write(listOf(
                                rs.getLong("id").toString(),
                                rs.getTimestamp("ts").toInstant().toString(),
                                rs.getString("client_id"),
                                rs.getString("client_ip_hash") ?: "",
                                rs.getString("method"),
                                rs.getString("path"),
                                rs.getInt("status").toString(),
                                rs.getLong("latency_ms").toString(),
                                rs.getString("decision"),
                                rs.getString("source"),
                                rs.getString("upstream") ?: ""
                            ).joinToString(",") { csv(it) })
                            out.write("\n")
                            n++
                        }
                    }
                }
                c.commit()
            } finally {
                c.autoCommit = true
            }
        }
        out.flush()
        return n
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun listClients(isAdmin: Boolean, anomalyDetector: com.cutm.nt14.gateway.core.AnomalyDetector? = null, limit: Int = 50): List<ClientInfo> = db.withConnection { c ->
        val out = mutableListOf<ClientInfo>()
        c.prepareStatement(
            "SELECT id, label, source, last_seen, total_requests, throttled_count FROM clients ORDER BY last_seen DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val key = rs.getString("id")
                    val label = rs.getString("label")
                    val isBanned = (anomalyDetector?.isBanned(key) == true) || (anomalyDetector?.isBanned(label) == true)
                    val throttled = rs.getLong("throttled_count")
                    val status = when {
                        isBanned -> "banned"
                        throttled > 0 -> "throttled"
                        else -> "active"
                    }
                    out += ClientInfo(
                        id = if (isAdmin) key else label,
                        maskedId = label,
                        requestsPerMin = 0.0,
                        totalRequests = rs.getLong("total_requests"),
                        throttledCount = throttled,
                        lastSeen = rs.getTimestamp("last_seen").time,
                        status = status,
                        isDemo = rs.getString("source") == "demo"
                    )
                }
            }
        }
        out
    }
}

class MetricsRepository(private val db: GatewayDatabase) {
    @Volatile var lastRollupAt: Long = 0L
        private set

    /** Recomputes metrics_1m for every minute in [fromMs, toMs) from request_logs (idempotent upsert). */
    fun rollup(fromMs: Long, toMs: Long): Int {
        val n = db.withConnection { c ->
            c.prepareStatement(
                """INSERT INTO metrics_1m (minute, endpoint, source, requests, allowed, throttled, errors, p50, p95, latency_sum_ms)
                   SELECT date_trunc('minute', ts), path, source, count(*),
                          count(*) FILTER (WHERE decision = 'allowed' AND status <> 429),
                          count(*) FILTER (WHERE status = 429 OR decision <> 'allowed'),
                          count(*) FILTER (WHERE status >= 500),
                          percentile_cont(0.5)  WITHIN GROUP (ORDER BY latency_ms),
                          percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms),
                          sum(latency_ms)
                   FROM request_logs
                   WHERE ts >= date_trunc('minute', ?::timestamptz) AND ts < ?
                   GROUP BY 1, 2, 3
                   ON CONFLICT (minute, endpoint, source) DO UPDATE SET
                     requests = EXCLUDED.requests, allowed = EXCLUDED.allowed, throttled = EXCLUDED.throttled,
                     errors = EXCLUDED.errors, p50 = EXCLUDED.p50, p95 = EXCLUDED.p95, latency_sum_ms = EXCLUDED.latency_sum_ms"""
            ).use { ps ->
                ps.setTimestamp(1, Timestamp(fromMs))
                ps.setTimestamp(2, Timestamp(toMs))
                ps.executeUpdate()
            }
        }
        lastRollupAt = System.currentTimeMillis()
        return n
    }

    fun series(range: String, source: String?, endpoint: String? = null, now: Long = System.currentTimeMillis()): MetricsSeriesDto {
        val (durationMs, bucketSec) = rangeSpec(range)
        val s = normalizeSource(source)
        val w = Where()
        w.add("minute >= ?", { ps, i -> ps.setTimestamp(i, Timestamp(now - durationMs)) })
        if (s != null) w.add("source = ?", { ps, i -> ps.setString(i, s) })
        if (!endpoint.isNullOrBlank()) w.add("endpoint = ?", { ps, i -> ps.setString(i, endpoint) })
        val sql = """SELECT to_timestamp(floor(extract(epoch FROM minute) / $bucketSec) * $bucketSec) AS bucket,
                            sum(requests) r, sum(allowed) a, sum(throttled) t, sum(errors) e,
                            sum(p50 * requests) / NULLIF(sum(requests), 0) p50w, max(p95) p95m
                     FROM metrics_1m ${w.sql()} GROUP BY bucket ORDER BY bucket"""
        val points = db.withConnection { c ->
            c.prepareStatement(sql).use { ps ->
                w.bind(ps)
                ps.executeQuery().use { rs ->
                    generateSequence {
                        if (!rs.next()) null else MetricsPointDto(
                            ts = rs.getTimestamp("bucket").time,
                            requests = rs.getLong("r"),
                            allowed = rs.getLong("a"),
                            throttled = rs.getLong("t"),
                            errors = rs.getLong("e"),
                            p50 = rs.getDouble("p50w"),
                            p95 = rs.getDouble("p95m")
                        )
                    }.toList()
                }
            }
        }
        return MetricsSeriesDto(
            range = normalizeRange(range), bucketSeconds = bucketSec, source = s ?: "all",
            points = points, lastRollupAt = lastRollupAt, generatedAt = now
        )
    }

    fun report(range: String, source: String?, now: Long = System.currentTimeMillis()): TrafficReportDto {
        val (durationMs, _) = rangeSpec(range)
        val from = now - durationMs
        val s = normalizeSource(source)
        val srcSql = if (s != null) " AND source = ?" else ""
        return db.withConnection { c ->
            fun PreparedStatement.bindRange(): PreparedStatement { setTimestamp(1, Timestamp(from)); if (s != null) setString(2, s); return this }

            val (total, allowed, throttled, errors, latSum, peakPerMin, dataFrom, dataTo) = c.prepareStatement(
                """SELECT coalesce(sum(requests),0), coalesce(sum(allowed),0), coalesce(sum(throttled),0), coalesce(sum(errors),0),
                          coalesce(sum(latency_sum_ms),0), coalesce(max(requests_per_min),0), min(minute), max(minute)
                   FROM (SELECT minute, sum(requests) requests_per_min, sum(requests) requests, sum(allowed) allowed,
                                sum(throttled) throttled, sum(errors) errors, sum(latency_sum_ms) latency_sum_ms
                         FROM metrics_1m WHERE minute >= ?$srcSql GROUP BY minute) m"""
            ).use { ps -> ps.bindRange().executeQuery().use { rs -> rs.next(); rs.run {
                Totals(getLong(1), getLong(2), getLong(3), getLong(4), getLong(5), getLong(6),
                    getTimestamp(7)?.time ?: 0L, getTimestamp(8)?.time ?: 0L)
            } } }

            val topEndpoints = c.prepareStatement(
                "SELECT endpoint, sum(requests) n FROM metrics_1m WHERE minute >= ?$srcSql GROUP BY endpoint ORDER BY n DESC LIMIT 10"
            ).use { ps -> ps.bindRange().executeQuery().use { rs ->
                val m = LinkedHashMap<String, Long>(); while (rs.next()) m[rs.getString(1)] = rs.getLong(2); m } }

            val topClients = c.prepareStatement(
                "SELECT client_id, count(*) n FROM request_logs WHERE ts >= ?$srcSql GROUP BY client_id ORDER BY n DESC LIMIT 10"
            ).use { ps -> ps.bindRange().executeQuery().use { rs ->
                val m = LinkedHashMap<String, Long>(); while (rs.next()) m[rs.getString(1)] = rs.getLong(2); m } }

            val p95 = c.prepareStatement(
                "SELECT coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) FROM request_logs WHERE ts >= ?$srcSql"
            ).use { ps -> ps.bindRange().executeQuery().use { rs -> rs.next(); rs.getDouble(1).toLong() } }

            TrafficReportDto(
                range = normalizeRange(range),
                totalRequests = total,
                allowedRequests = allowed,
                blockedRequests = throttled,
                errorRate = if (total > 0) Math.round(throttled * 1000.0 / total) / 1000.0 else 0.0,
                avgLatencyMs = if (total > 0) latSum / total else 0L,
                p95LatencyMs = p95,
                peakRps = Math.round(peakPerMin / 60.0 * 100) / 100.0,
                topEndpoints = topEndpoints,
                topClients = topClients,
                generatedAt = now,
                serverErrors = errors,
                source = s ?: "all",
                persistence = "postgres",
                dataFrom = dataFrom,
                dataTo = dataTo,
                lastRollupAt = lastRollupAt
            )
        }
    }

    private data class Totals(
        val total: Long, val allowed: Long, val throttled: Long, val errors: Long,
        val latSum: Long, val peakPerMin: Long, val dataFrom: Long, val dataTo: Long
    )

    companion object {
        fun normalizeRange(r: String?) = when (r?.lowercase()) { "24h" -> "24h"; "7d" -> "7d"; else -> "1h" }
        /** range -> (duration, bucket seconds). 1h: per minute, 24h: 15 min, 7d: hourly. */
        fun rangeSpec(r: String?): Pair<Long, Int> = when (normalizeRange(r)) {
            "24h" -> 86_400_000L to 900
            "7d" -> 7 * 86_400_000L to 3600
            else -> 3_600_000L to 60
        }
    }
}

/** Scheduled cleanup. request_logs: 7 days, metrics_1m: 90 days, probe_results: 30 days. */
class RetentionJob(
    private val db: GatewayDatabase,
    val logRetentionMs: Long = 7 * 86_400_000L,
    val metricsRetentionMs: Long = 90 * 86_400_000L,
    val probeRetentionMs: Long = 30 * 86_400_000L
) {
    data class Result(val logsDeleted: Int, val metricsDeleted: Int, val probesDeleted: Int, val bansDeleted: Int)

    fun run(now: Long = System.currentTimeMillis()): Result = db.withConnection { c ->
        fun del(sql: String, cutoff: Long) = c.prepareStatement(sql).use { it.setTimestamp(1, Timestamp(cutoff)); it.executeUpdate() }
        Result(
            logsDeleted = del("DELETE FROM request_logs WHERE ts < ?", now - logRetentionMs),
            metricsDeleted = del("DELETE FROM metrics_1m WHERE minute < ?", now - metricsRetentionMs),
            probesDeleted = del("DELETE FROM probe_results WHERE ts < ?", now - probeRetentionMs),
            bansDeleted = del("DELETE FROM bans WHERE expires_at < ?", now)
        )
    }
}

class RuleStore(private val db: GatewayDatabase) {
    fun all(): List<RateLimitRule> = db.withConnection { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT * FROM rules ORDER BY endpoint_id").use { rs ->
            generateSequence { if (rs.next()) RateLimitRule(rs.getString("endpoint_id"), rs.getInt("limit_per_min"), rs.getInt("burst_limit"), rs.getString("action")) else null }.toList()
        } }
    }
    fun upsert(r: RateLimitRule) = db.withConnection { c ->
        c.prepareStatement(
            """INSERT INTO rules (endpoint_id, limit_per_min, burst_limit, action, updated_at) VALUES (?,?,?,?, now())
               ON CONFLICT (endpoint_id) DO UPDATE SET limit_per_min = EXCLUDED.limit_per_min,
               burst_limit = EXCLUDED.burst_limit, action = EXCLUDED.action, updated_at = now()"""
        ).use { ps -> ps.setString(1, r.endpointId); ps.setInt(2, r.limitPerMin); ps.setInt(3, r.burstLimit); ps.setString(4, r.action); ps.executeUpdate() }
    }
    fun delete(endpoint: String) = db.withConnection { c ->
        c.prepareStatement("DELETE FROM rules WHERE endpoint_id = ?").use { it.setString(1, endpoint); it.executeUpdate() }
    }
}

class BanStore(private val db: GatewayDatabase) {
    data class StoredBan(val clientKey: String, val reason: String, val expiresAt: Long)
    fun active(now: Long = System.currentTimeMillis()): List<StoredBan> = db.withConnection { c ->
        c.prepareStatement("SELECT client_key, reason, expires_at FROM bans WHERE expires_at > ?").use { ps ->
            ps.setTimestamp(1, Timestamp(now))
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) StoredBan(rs.getString(1), rs.getString(2), rs.getTimestamp(3).time) else null }.toList() }
        }
    }
    fun upsert(key: String, reason: String, expiresAt: Long, by: String? = null) = db.withConnection { c ->
        c.prepareStatement(
            """INSERT INTO bans (client_key, reason, expires_at, created_by) VALUES (?,?,?,?)
               ON CONFLICT (client_key) DO UPDATE SET reason = EXCLUDED.reason, expires_at = EXCLUDED.expires_at, created_at = now()"""
        ).use { ps -> ps.setString(1, key); ps.setString(2, reason); ps.setTimestamp(3, Timestamp(expiresAt)); ps.setString(4, by); ps.executeUpdate() }
    }
    fun delete(key: String) = db.withConnection { c ->
        c.prepareStatement("DELETE FROM bans WHERE client_key = ?").use { it.setString(1, key); it.executeUpdate() }
    }
}

class IncidentStore(private val db: GatewayDatabase) {
    private fun ResultSet.toIncident() = IncidentDto(
        id = getString("id"), type = getString("type"), severity = getString("severity"),
        detail = getString("detail"), timestamp = getTimestamp("ts").time,
        targetId = getLong("target_id").takeIf { !wasNull() },
        resolvedAt = getTimestamp("resolved_at")?.time
    )

    fun insert(i: IncidentDto, targetId: Long? = null) = db.withConnection { c ->
        c.prepareStatement("INSERT INTO incidents (id, ts, type, severity, detail, target_id) VALUES (?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING").use { ps ->
            ps.setString(1, i.id); ps.setTimestamp(2, Timestamp(i.timestamp)); ps.setString(3, i.type)
            ps.setString(4, i.severity); ps.setString(5, i.detail)
            if (targetId != null) ps.setLong(6, targetId) else ps.setNull(6, Types.BIGINT)
            ps.executeUpdate()
        }
    }
    fun resolve(id: String, at: Long = System.currentTimeMillis()): IncidentDto? = db.withConnection { c ->
        c.prepareStatement("UPDATE incidents SET resolved_at = COALESCE(resolved_at, ?) WHERE id = ? RETURNING *").use { ps ->
            ps.setTimestamp(1, Timestamp(at)); ps.setString(2, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toIncident() else null }
        }
    }
    fun resolveForTarget(targetId: Long, at: Long = System.currentTimeMillis()): List<String> = db.withConnection { c ->
        c.prepareStatement("UPDATE incidents SET resolved_at = ? WHERE target_id = ? AND resolved_at IS NULL RETURNING id").use { ps ->
            ps.setTimestamp(1, Timestamp(at)); ps.setLong(2, targetId)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString("id") else null }.toList() }
        }
    }
    fun openForTarget(targetId: Long): String? = db.withConnection { c ->
        c.prepareStatement("SELECT id FROM incidents WHERE target_id = ? AND resolved_at IS NULL ORDER BY ts DESC LIMIT 1").use { ps ->
            ps.setLong(1, targetId); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
    fun recent(limit: Int = 50): List<IncidentDto> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM incidents ORDER BY ts DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toIncident() else null }.toList() }
        }
    }
}

class TargetStore(private val db: GatewayDatabase) {
    private fun ResultSet.toTarget() = MonitoredTarget(
        id = getLong("id"), name = getString("name"), url = getString("url"), method = getString("method"),
        expectedStatus = getInt("expected_status"), intervalSec = getInt("interval_sec"),
        timeoutMs = getInt("timeout_ms"), enabled = getBoolean("enabled"),
        source = runCatching { getString("source") }.getOrNull() ?: "real",
        synthetic = runCatching { getBoolean("synthetic") }.getOrDefault(false)
    )
    fun all(): List<MonitoredTarget> = db.withConnection { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT * FROM monitored_targets ORDER BY id").use { rs ->
            generateSequence { if (rs.next()) rs.toTarget() else null }.toList() } }
    }
    fun get(id: Long): MonitoredTarget? = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM monitored_targets WHERE id = ?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.toTarget() else null } }
    }
    fun create(t: TargetRequest): MonitoredTarget = db.withConnection { c ->
        c.prepareStatement(
            """INSERT INTO monitored_targets (name, url, method, expected_status, interval_sec, timeout_ms, enabled, source, synthetic)
               VALUES (?,?,?,?,?,?,?,?,?) RETURNING *"""
        ).use { ps -> bindTarget(ps, t); ps.executeQuery().use { rs -> rs.next(); rs.toTarget() } }
    }
    fun update(id: Long, t: TargetRequest): MonitoredTarget? = db.withConnection { c ->
        c.prepareStatement(
            """UPDATE monitored_targets SET name=?, url=?, method=?, expected_status=?, interval_sec=?, timeout_ms=?, enabled=?, source=?, synthetic=?
               WHERE id = ? RETURNING *"""
        ).use { ps -> bindTarget(ps, t); ps.setLong(10, id); ps.executeQuery().use { rs -> if (rs.next()) rs.toTarget() else null } }
    }
    fun delete(id: Long): Boolean = db.withConnection { c ->
        c.prepareStatement("UPDATE incidents SET resolved_at = COALESCE(resolved_at, now()) WHERE target_id = ? AND resolved_at IS NULL").use {
            it.setLong(1, id); it.executeUpdate()
        }
        c.prepareStatement("DELETE FROM monitored_targets WHERE id = ?").use { it.setLong(1, id); it.executeUpdate() > 0 }
    }
    private fun bindTarget(ps: PreparedStatement, t: TargetRequest) {
        val src = t.source.trim().lowercase().ifBlank { if (t.synthetic) "synthetic" else "real" }
        val isSyn = t.synthetic || src in setOf("synthetic", "test", "demo")
        ps.setString(1, t.name.trim()); ps.setString(2, t.url.trim()); ps.setString(3, t.method.uppercase())
        ps.setInt(4, t.expectedStatus); ps.setInt(5, t.intervalSec); ps.setInt(6, t.timeoutMs); ps.setBoolean(7, t.enabled)
        ps.setString(8, src); ps.setBoolean(9, isSyn)
    }
}

class ProbeStore(private val db: GatewayDatabase) {
    data class ProbeRow(val ts: Long, val up: Boolean, val statusCode: Int?, val latencyMs: Long, val error: String?, val transition: String?)

    fun insert(targetId: Long, r: ProbeRow) = db.withConnection { c ->
        c.prepareStatement("INSERT INTO probe_results (target_id, ts, up, status_code, latency_ms, error, transition) VALUES (?,?,?,?,?,?,?)").use { ps ->
            ps.setLong(1, targetId); ps.setTimestamp(2, Timestamp(r.ts)); ps.setBoolean(3, r.up)
            if (r.statusCode != null) ps.setInt(4, r.statusCode) else ps.setNull(4, Types.INTEGER)
            ps.setLong(5, r.latencyMs); ps.setString(6, r.error?.take(500)); ps.setString(7, r.transition)
            ps.executeUpdate()
        }
    }

    fun recent(targetId: Long, limit: Int = 30): List<ProbeRow> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM probe_results WHERE target_id = ? ORDER BY ts DESC, id DESC LIMIT ?").use { ps ->
            ps.setLong(1, targetId); ps.setInt(2, limit)
            ps.executeQuery().use { rs -> generateSequence {
                if (!rs.next()) null else ProbeRow(
                    rs.getTimestamp("ts").time, rs.getBoolean("up"),
                    rs.getInt("status_code").takeIf { !rs.wasNull() }, rs.getLong("latency_ms"),
                    rs.getString("error"), rs.getString("transition")
                )
            }.toList() }
        }
    }

    fun uptimePct(targetId: Long, sinceMs: Long): Double? = db.withConnection { c ->
        c.prepareStatement("SELECT count(*) FILTER (WHERE up), count(*) FROM probe_results WHERE target_id = ? AND ts >= ?").use { ps ->
            ps.setLong(1, targetId); ps.setTimestamp(2, Timestamp(sinceMs))
            ps.executeQuery().use { rs -> rs.next(); val total = rs.getLong(2); if (total == 0L) null else Math.round(rs.getLong(1) * 10000.0 / total) / 100.0 }
        }
    }
}

/** Convenience helper used by tests and the dbQuery tool. */
fun Connection.countRows(table: String): Long {
    require(Regex("^[a-z_0-9]+$").matches(table))
    return createStatement().use { st -> st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) } }
}
