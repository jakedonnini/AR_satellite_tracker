package com.jakedonnini.sattracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SatelliteDao {

    /**
     * Low Earth orbit per CelesTrak's definition: period ≤ 128 min (≥ 11.25 rev/day), plus
     * a near-circular cutoff so highly elliptical orbits that only dip into LEO are excluded.
     */
    @Query("SELECT * FROM satellite WHERE meanMotion >= 11.25 AND eccentricity < 0.25")
    suspend fun getLeo(): List<SatelliteEntity>

    @Query("SELECT COUNT(*) FROM satellite")
    suspend fun count(): Int

    @Query("SELECT * FROM catalog_fetch WHERE `group` = :group")
    suspend fun getFetch(group: String): CatalogFetchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(satellites: List<SatelliteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFetch(fetch: CatalogFetchEntity)

    @Query("DELETE FROM satellite")
    suspend fun clearSatellites()

    /** Atomically swaps in a freshly downloaded catalog so readers never see a partial one. */
    @Transaction
    suspend fun replaceCatalog(group: String, satellites: List<SatelliteEntity>, fetchedAtMillis: Long) {
        clearSatellites()
        upsertAll(satellites)
        upsertFetch(CatalogFetchEntity(group, fetchedAtMillis))
    }
}
