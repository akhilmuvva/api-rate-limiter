package com.cutm.nt14.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cutm.nt14.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Instrumented Room migration test using [MigrationTestHelper] and exported schemas (`1.json`, `2.json`, `3.json`).
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {

    private val testDb = "nt14_migration_test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NT14Database::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate2To3_withMigrationTestHelper_preservesSeededRowsAndValidatesSchema() {
        helper.createDatabase(testDb, 2).apply {
            execSQL(
                "INSERT INTO `Endpoints` (`endpointId`, `name`, `baseUrl`, `method`, `status`, `ownerEmail`, `timestamp`, `syncStatus`) " +
                    "VALUES ('ep_1', 'Escrows API', 'https://api-rate-limiter-gateway-qgfy.onrender.com/api/polylance/escrows', 'GET', 'Active', 'admin@cutm.ac.in', 1730000000000, 'SYNCED')"
            )
            execSQL(
                "INSERT INTO `RequestLogs` (`logId`, `endpointId`, `timestamp`, `sourceIp`, `userId`, `statusCode`, `latencyMs`, `syncStatus`) " +
                    "VALUES ('log_1', 'ep_1', 1730000001000, 'iphash:2f5ef1fd5f4b79214f55814b', 'user:ee792d0ba4bd1234', 200, 42, 'SYNCED')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 3, true, DatabaseModule.MIGRATION_2_3)
        db.query("SELECT `sourceIp`, `statusCode`, `latencyMs` FROM `RequestLogs` WHERE `logId` = 'log_1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("iphash:2f5ef1fd5f4b79214f55814b", cursor.getString(0))
            assertEquals(200, cursor.getInt(1))
            assertEquals(42, cursor.getInt(2))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To3_withMigrationTestHelper_preservesSeededRowsAndValidatesSchema() {
        helper.createDatabase(testDb + "_v1", 1).apply {
            execSQL(
                "INSERT INTO `Endpoints` (`endpointId`, `name`, `baseUrl`, `method`, `status`, `ownerEmail`, `timestamp`, `syncStatus`) " +
                    "VALUES ('ep_v1', 'Health API', 'https://api-rate-limiter-gateway-qgfy.onrender.com/health', 'GET', 'Active', 'admin@cutm.ac.in', 1730000000000, 'SYNCED')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDb + "_v1",
            3,
            true,
            DatabaseModule.MIGRATION_1_2,
            DatabaseModule.MIGRATION_2_3
        )
        db.query("SELECT `name` FROM `Endpoints` WHERE `endpointId` = 'ep_v1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Health API", cursor.getString(0))
        }
        db.close()
    }
}
