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
        val builder = Room.databaseBuilder(
            context,
            NT14Database::class.java,
            "nt14_database"
        ).addMigrations(MIGRATION_2_3)
        // Restrict fallbackToDestructiveMigration to non-debug builds so bad migrations fail loudly in debug
        if (!com.cutm.nt14.BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration()
        }
        return builder.build()
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
