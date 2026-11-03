package com.cutm.nt14.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.util.Pair
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import com.cutm.nt14.di.DatabaseModule
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies Room schema v2 -> v3 migration (`DatabaseModule.MIGRATION_2_3`) against the
 * exported Room JSON schemas (`2.json` and `3.json`) using an in-memory SQLite database.
 * Asserts that:
 * 1. All v2 tables are created from `2.json` and seeded with rows across `Endpoints`,
 *    `RequestLogs`, `RateLimits`, `AbuseEvents`, and `DDoSIncidents`.
 * 2. `DatabaseModule.MIGRATION_2_3.migrate(db)` executes cleanly.
 * 3. All seeded rows survive the v2 -> v3 migration intact.
 * 4. All 4 foreign-key indices required by `3.json` and `NT14Database_Impl.java` exist in `sqlite_master`:
 *    - `index_RequestLogs_endpointId`
 *    - `index_RateLimits_endpointId`
 *    - `index_AbuseEvents_logId`
 *    - `index_DDoSIncidents_endpointId`
 */
class Migration2To3Test {

    private fun resolveSchemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/com.cutm.nt14.data.local.NT14Database/$version.json"),
            File("app/schemas/com.cutm.nt14.data.local.NT14Database/$version.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Room schema file $version.json not found in ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun migrate2To3_preservesSeededRowsAndCreatesAllForeignKeyIndices() {
        val schemaV2File = resolveSchemaFile(2)
        val schemaV3File = resolveSchemaFile(3)

        val v2Root = JsonParser.parseString(schemaV2File.readText()).asJsonObject
        val v3Root = JsonParser.parseString(schemaV3File.readText()).asJsonObject
        assertEquals(2, v2Root.getAsJsonObject("database").get("version").asInt)
        assertEquals(3, v3Root.getAsJsonObject("database").get("version").asInt)

        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = ON")

                // 1. Create v2 schema from exported 2.json
                val v2Entities = v2Root.getAsJsonObject("database").getAsJsonArray("entities")
                for (entityEl in v2Entities) {
                    val entityObj = entityEl.asJsonObject
                    val tableName = entityObj.get("tableName").asString
                    val createSql = entityObj.get("createSql").asString.replace("\${TABLE_NAME}", tableName)
                    stmt.execute(createSql)
                }

                // 2. Seed rows into v2 tables
                stmt.execute(
                    "INSERT INTO `Endpoints` (`endpointId`, `name`, `baseUrl`, `method`, `status`, `ownerEmail`, `timestamp`, `syncStatus`) " +
                        "VALUES ('ep_1', 'Escrows API', 'https://api-rate-limiter-gateway-qgfy.onrender.com/api/polylance/escrows', 'GET', 'Active', 'admin@cutm.ac.in', 1730000000000, 'SYNCED')"
                )
                stmt.execute(
                    "INSERT INTO `RequestLogs` (`logId`, `endpointId`, `timestamp`, `sourceIp`, `userId`, `statusCode`, `latencyMs`, `syncStatus`) " +
                        "VALUES ('log_1', 'ep_1', 1730000001000, 'iphash:2f5ef1fd5f4b79214f55814b', 'usr_1', 200, 42, 'SYNCED')"
                )
                stmt.execute(
                    "INSERT INTO `RateLimits` (`ruleId`, `endpointId`, `limitPerMin`, `burstLimit`, `action`, `updatedAt`, `syncStatus`) " +
                        "VALUES ('rule_1', 'ep_1', 60, 15, 'Throttle', 1730000001000, 'SYNCED')"
                )
                stmt.execute(
                    "INSERT INTO `AbuseEvents` (`eventId`, `logId`, `eventType`, `riskScore`, `action`, `createdAt`, `syncStatus`) " +
                        "VALUES ('evt_1', 'log_1', 'BurstExceeded', 85, 'Throttle', 1730000002000, 'SYNCED')"
                )
                stmt.execute(
                    "INSERT INTO `DDoSIncidents` (`incidentId`, `endpointId`, `startTime`, `requestSpike`, `severity`, `status`, `syncStatus`) " +
                        "VALUES ('inc_1', 'ep_1', 1730000003000, 450, 'HIGH', 'RESOLVED', 'SYNCED')"
                )
            }

            // 3. Execute Room MIGRATION_2_3 against SupportSQLiteDatabase adapter
            val supportDb = JdbcSupportSQLiteDatabase(conn)
            DatabaseModule.MIGRATION_2_3.migrate(supportDb)

            // 4. Verify all seeded rows survived
            conn.createStatement().use { stmt ->
                fun countRows(table: String): Int {
                    stmt.executeQuery("SELECT COUNT(*) FROM `$table`").use { rs ->
                        assertTrue(rs.next())
                        return rs.getInt(1)
                    }
                }
                assertEquals(1, countRows("Endpoints"))
                assertEquals(1, countRows("RequestLogs"))
                assertEquals(1, countRows("RateLimits"))
                assertEquals(1, countRows("AbuseEvents"))
                assertEquals(1, countRows("DDoSIncidents"))

                stmt.executeQuery("SELECT `sourceIp`, `statusCode`, `latencyMs` FROM `RequestLogs` WHERE `logId` = 'log_1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("iphash:2f5ef1fd5f4b79214f55814b", rs.getString("sourceIp"))
                    assertEquals(200, rs.getInt("statusCode"))
                    assertEquals(42, rs.getInt("latencyMs"))
                }

                // 5. Verify all indices declared in 3.json exist in sqlite_master after MIGRATION_2_3
                val existingIndices = mutableSetOf<String>()
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { rs ->
                    while (rs.next()) {
                        existingIndices.add(rs.getString("name"))
                    }
                }

                val expectedIndicesFromV3 = mutableSetOf<String>()
                val v3Entities = v3Root.getAsJsonObject("database").getAsJsonArray("entities")
                for (entityEl in v3Entities) {
                    val indices = entityEl.asJsonObject.getAsJsonArray("indices")
                    for (idxEl in indices) {
                        expectedIndicesFromV3.add(idxEl.asJsonObject.get("name").asString)
                    }
                }

                assertEquals(
                    setOf(
                        "index_RequestLogs_endpointId",
                        "index_RateLimits_endpointId",
                        "index_AbuseEvents_logId",
                        "index_DDoSIncidents_endpointId"
                    ),
                    expectedIndicesFromV3
                )
                assertTrue(
                    "All v3 indices must exist in sqlite_master after MIGRATION_2_3 (found: $existingIndices)",
                    existingIndices.containsAll(expectedIndicesFromV3)
                )
            }
        }
    }

    @Test
    fun migrate1To2To3_preservesSeededV1RowsAndCreatesAllForeignKeyIndices() {
        val schemaV1File = resolveSchemaFile(1)
        val schemaV3File = resolveSchemaFile(3)

        val v1Root = JsonParser.parseString(schemaV1File.readText()).asJsonObject
        val v3Root = JsonParser.parseString(schemaV3File.readText()).asJsonObject
        assertEquals(1, v1Root.getAsJsonObject("database").get("version").asInt)
        assertEquals(3, v3Root.getAsJsonObject("database").get("version").asInt)

        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = ON")

                val v1Entities = v1Root.getAsJsonObject("database").getAsJsonArray("entities")
                for (entityEl in v1Entities) {
                    val entityObj = entityEl.asJsonObject
                    val tableName = entityObj.get("tableName").asString
                    val createSql = entityObj.get("createSql").asString.replace("\${TABLE_NAME}", tableName)
                    stmt.execute(createSql)
                }

                stmt.execute(
                    "INSERT INTO `Endpoints` (`endpointId`, `name`, `baseUrl`, `method`, `status`, `ownerEmail`, `timestamp`, `syncStatus`) " +
                        "VALUES ('ep_v1', 'Health API', 'https://api-rate-limiter-gateway-qgfy.onrender.com/health', 'GET', 'Active', 'admin@cutm.ac.in', 1730000000000, 'SYNCED')"
                )
                stmt.execute(
                    "INSERT INTO `RequestLogs` (`logId`, `endpointId`, `timestamp`, `sourceIp`, `userId`, `statusCode`, `latencyMs`, `syncStatus`) " +
                        "VALUES ('log_v1', 'ep_v1', 1730000001000, 'iphash:2f5ef1fd5f4b79214f55814b', 'user:ee792d0ba4bd1234', 200, 38, 'SYNCED')"
                )
            }

            val supportDb = JdbcSupportSQLiteDatabase(conn)
            DatabaseModule.MIGRATION_1_2.migrate(supportDb)
            DatabaseModule.MIGRATION_2_3.migrate(supportDb)

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT `userId`, `latencyMs` FROM `RequestLogs` WHERE `logId` = 'log_v1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("user:ee792d0ba4bd1234", rs.getString("userId"))
                    assertEquals(38, rs.getInt("latencyMs"))
                }

                val existingIndices = mutableSetOf<String>()
                stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { rs ->
                    while (rs.next()) {
                        existingIndices.add(rs.getString("name"))
                    }
                }
                val expectedIndices = setOf(
                    "index_RequestLogs_endpointId",
                    "index_RateLimits_endpointId",
                    "index_AbuseEvents_logId",
                    "index_DDoSIncidents_endpointId"
                )
                assertTrue(existingIndices.containsAll(expectedIndices))
            }
        }
    }

    private class JdbcSupportSQLiteDatabase(private val connection: Connection) : SupportSQLiteDatabase {
        override fun execSQL(sql: String) {
            connection.createStatement().use { it.execute(sql) }
        }
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            connection.prepareStatement(sql).use { ps ->
                bindArgs.forEachIndexed { idx, arg -> ps.setObject(idx + 1, arg) }
                ps.execute()
            }
        }
        override fun compileStatement(sql: String): SupportSQLiteStatement = throw UnsupportedOperationException()
        override fun beginTransaction() = throw UnsupportedOperationException()
        override fun beginTransactionNonExclusive() = throw UnsupportedOperationException()
        override fun beginTransactionWithListener(transactionListener: android.database.sqlite.SQLiteTransactionListener) = throw UnsupportedOperationException()
        override fun beginTransactionWithListenerNonExclusive(transactionListener: android.database.sqlite.SQLiteTransactionListener) = throw UnsupportedOperationException()
        override fun endTransaction() = throw UnsupportedOperationException()
        override fun setTransactionSuccessful() = throw UnsupportedOperationException()
        override fun inTransaction(): Boolean = false
        override val isDbLockedByCurrentThread: Boolean = false
        override fun yieldIfContendedSafely(): Boolean = false
        override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean = false
        override var version: Int = 2
        override val maximumSize: Long = Long.MAX_VALUE
        override fun setMaximumSize(numBytes: Long): Long = numBytes
        override var pageSize: Long = 4096L
        override fun query(query: String): Cursor = throw UnsupportedOperationException()
        override fun query(query: String, bindArgs: Array<out Any?>): Cursor = throw UnsupportedOperationException()
        override fun query(query: SupportSQLiteQuery): Cursor = throw UnsupportedOperationException()
        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor = throw UnsupportedOperationException()
        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long = throw UnsupportedOperationException()
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = throw UnsupportedOperationException()
        override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int = throw UnsupportedOperationException()
        override fun needUpgrade(newVersion: Int): Boolean = newVersion > version
        override fun setLocale(locale: java.util.Locale) {}
        override fun setMaxSqlCacheSize(cacheSize: Int) {}
        override fun setForeignKeyConstraintsEnabled(enable: Boolean) {}
        override fun enableWriteAheadLogging(): Boolean = false
        override fun disableWriteAheadLogging() {}
        override val isWriteAheadLoggingEnabled: Boolean = false
        override val attachedDbs: List<Pair<String, String>>? = emptyList()
        override val isDatabaseIntegrityOk: Boolean = true
        override val path: String = ":memory:"
        override val isOpen: Boolean = !connection.isClosed
        override val isReadOnly: Boolean = false
        override fun close() = connection.close()
    }
}
