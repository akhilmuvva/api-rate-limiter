package com.cutm.nt14.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cutm.nt14.data.local.daos.*
import com.cutm.nt14.data.local.entities.*

@Database(
    entities = [
        Endpoint::class,
        RequestLog::class,
        RateLimit::class,
        AbuseEvent::class,
        Fingerprint::class,
        DDoSIncident::class,
        Report::class
    ],
    version = 3,
    exportSchema = true
)
abstract class NT14Database : RoomDatabase() {
    abstract fun endpointDao(): EndpointDao
    abstract fun requestLogDao(): RequestLogDao
    abstract fun rateLimitDao(): RateLimitDao
    abstract fun abuseEventDao(): AbuseEventDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun ddosIncidentDao(): DDoSIncidentDao
    abstract fun reportDao(): ReportDao
}
