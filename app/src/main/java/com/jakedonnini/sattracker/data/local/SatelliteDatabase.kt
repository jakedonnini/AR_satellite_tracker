package com.jakedonnini.sattracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SatelliteEntity::class, CatalogFetchEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SatelliteDatabase : RoomDatabase() {
    abstract fun satelliteDao(): SatelliteDao

    companion object {
        fun create(context: Context): SatelliteDatabase =
            Room.databaseBuilder(context, SatelliteDatabase::class.java, "satellites.db")
                // The catalog is a re-downloadable cache, so schema changes just drop it.
                .fallbackToDestructiveMigration()
                .build()
    }
}
