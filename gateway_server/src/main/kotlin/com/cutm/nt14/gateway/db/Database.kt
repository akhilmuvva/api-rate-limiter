package com.cutm.nt14.gateway.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/** JDBC connection settings parsed from DATABASE_URL. */
data class JdbcSettings(val jdbcUrl: String, val user: String?, val password: String?)

object DatabaseUrl {
    /**
     * Accepts Render/Heroku style `postgres://user:pass@host:5432/db?sslmode=require`,
     * `postgresql://...`, or a ready `jdbc:postgresql://...` URL.
     * External Render hosts (`*.render.com`) require TLS, so sslmode=require is added when absent.
     */
    fun parse(raw: String): JdbcSettings {
        val url = raw.trim()
        if (url.startsWith("jdbc:")) return JdbcSettings(url, null, null)
        val uri = URI(url.replaceFirst(Regex("^postgres(ql)?://"), "http://"))
        val userInfo = uri.rawUserInfo?.split(":", limit = 2)
        val user = userInfo?.getOrNull(0)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        val pass = userInfo?.getOrNull(1)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        val port = if (uri.port > 0) uri.port else 5432
        val params = uri.rawQuery?.split("&")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        if (uri.host.endsWith(".render.com") && params.none { it.startsWith("sslmode=") }) {
            params += "sslmode=require"
        }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return JdbcSettings("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user, pass)
    }
}

/** Owns the connection pool and runs Flyway migrations. */
class GatewayDatabase(val dataSource: DataSource) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(GatewayDatabase::class.java)

    fun migrate(): Int {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        logger.info("Flyway: applied ${result.migrationsExecuted} migration(s); schema at ${result.targetSchemaVersion ?: "current"}")
        return result.migrationsExecuted
    }

    inline fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use(block)

    override fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    companion object {
        fun create(settings: JdbcSettings, maxPool: Int = 5): GatewayDatabase {
            val cfg = HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                settings.user?.let { username = it }
                settings.password?.let { password = it }
                maximumPoolSize = maxPool
                minimumIdle = 1
                connectionTimeout = 15_000
                poolName = "nt14-gateway"
                addDataSourceProperty("reWriteBatchedInserts", "true")
                addDataSourceProperty("ApplicationName", "nt14-gateway")
            }
            return GatewayDatabase(HikariDataSource(cfg))
        }

        /** Returns null (in-memory mode) when DATABASE_URL is not set. */
        fun fromEnv(): GatewayDatabase? {
            val raw = System.getenv("DATABASE_URL")?.takeIf { it.isNotBlank() } ?: return null
            return create(DatabaseUrl.parse(raw))
        }
    }
}

/**
 * Time-ordered 63-bit ids: (epochMillis << 16) | sequence. Monotonic inside the process and,
 * because the clock moves forward, larger than any id written before a restart. Used as the
 * request_logs primary key and as the keyset-pagination cursor.
 */
object LogIdGenerator {
    private val last = AtomicLong(0)
    fun next(nowMs: Long = System.currentTimeMillis()): Long {
        while (true) {
            val prev = last.get()
            val candidate = maxOf(prev + 1, nowMs shl 16)
            if (last.compareAndSet(prev, candidate)) return candidate
        }
    }
    fun timestampOf(id: Long): Long = id ushr 16
}

/** IP privacy helpers: masked display values and keyed hashes for correlation. */
object IpPrivacy {
    /**
     * Reads IP_HASH_SALT directly from the environment with NO default fallback in code.
     * Fails fast on startup if missing or blank.
     */
    fun resolveKey(env: (String) -> String? = System::getenv): ByteArray {
        val salt = env("IP_HASH_SALT")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("FATAL: IP_HASH_SALT environment variable is required and has no default fallback.")
        return MessageDigest.getInstance("SHA-256").digest(salt.toByteArray(StandardCharsets.UTF_8))
    }

    private val key: ByteArray by lazy { resolveKey() }

    /** Validates at startup that IP_HASH_SALT is explicitly configured. */
    fun ensureConfigured(env: (String) -> String? = System::getenv) {
        resolveKey(env)
    }

    fun hash(ip: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(ip.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }

    /** 203.0.113.42 -> 203.0.113.x ; 2001:db8:1:2::5 -> 2001:db8:1:x */
    fun mask(ip: String): String {
        val v = ip.trim()
        return when {
            v.count { it == '.' } == 3 && !v.contains(':') -> v.substringBeforeLast('.') + ".x"
            v.contains(':') -> v.split(':').filter { it.isNotEmpty() }.take(3).joinToString(":") + ":x"
            else -> v.take(4) + "…"
        }
    }

    fun looksLikeIp(v: String): Boolean =
        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(v) || (v.contains(':') && Regex("^[0-9a-fA-F:.]+$").matches(v))

    /** The value written to request_logs.client_id. Users keep their identity; IPs are masked. */
    fun storedClientId(rawClientId: String): String = when {
        rawClientId.startsWith("user:") || rawClientId.startsWith("demo:") -> rawClientId
        looksLikeIp(rawClientId) -> "ip:" + mask(rawClientId)
        else -> rawClientId
    }
}
