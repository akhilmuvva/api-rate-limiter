package com.cutm.nt14.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cutm.nt14.data.local.NT14Database
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v1 (commit 90898406) -> v2 (commit 3a18d023):
     * Only DAO `@Query("DELETE FROM ...")` methods were added between v1 and v2;
     * zero `@Entity` table definitions or columns changed (same identityHash
     * `f6fb2a17f5e2c115b8c2a65ec77688d0`). This no-op migration preserves all v1 rows intact.
     */
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Schema DDL is identical between v1 and v2; all rows are preserved intact.
        }
    }

    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_AbuseEvents_logId` ON `AbuseEvents` (`logId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_DDoSIncidents_endpointId` ON `DDoSIncidents` (`endpointId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_RequestLogs_endpointId` ON `RequestLogs` (`endpointId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_RateLimits_endpointId` ON `RateLimits` (`endpointId`)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NT14Database {
        return Room.databaseBuilder(
            context,
            NT14Database::class.java,
            "nt14_database"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    @Provides
    fun provideEndpointDao(db: NT14Database) = db.endpointDao()

    @Provides
    fun provideRequestLogDao(db: NT14Database) = db.requestLogDao()

    @Provides
    fun provideRateLimitDao(db: NT14Database) = db.rateLimitDao()

    @Provides
    fun provideAbuseEventDao(db: NT14Database) = db.abuseEventDao()

    @Provides
    fun provideFingerprintDao(db: NT14Database) = db.fingerprintDao()

    @Provides
    fun provideDDoSIncidentDao(db: NT14Database) = db.ddosIncidentDao()

    @Provides
    fun provideReportDao(db: NT14Database) = db.reportDao()
}
