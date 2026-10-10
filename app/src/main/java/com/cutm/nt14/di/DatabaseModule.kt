package com.cutm.nt14.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cutm.nt14.data.local.NT14Database
import com.cutm.nt14.data.local.SyncStatus
import com.cutm.nt14.data.local.entities.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NT14Database {
        lateinit var database: NT14Database
        database = Room.databaseBuilder(
            context,
            NT14Database::class.java,
            "nt14_database"
        )
        .fallbackToDestructiveMigration()
        .build()
        return database
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
