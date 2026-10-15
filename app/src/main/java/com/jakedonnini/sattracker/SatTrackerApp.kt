package com.jakedonnini.sattracker

import android.app.Application
import com.jakedonnini.sattracker.data.SatelliteRepository
import com.jakedonnini.sattracker.data.local.SatelliteDatabase
import com.jakedonnini.sattracker.data.remote.CelesTrakClient

/** Process-wide singletons. A DI framework can replace this once there are more of them. */
class SatTrackerApp : Application() {
    val database: SatelliteDatabase by lazy { SatelliteDatabase.create(this) }
    val repository: SatelliteRepository by lazy {
        SatelliteRepository(database.satelliteDao(), CelesTrakClient())
    }
}
