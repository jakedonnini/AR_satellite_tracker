package com.jakedonnini.sattracker.data

import android.util.Log
import com.jakedonnini.sattracker.data.local.SatelliteDao
import com.jakedonnini.sattracker.data.local.toEntity
import com.jakedonnini.sattracker.data.local.toOmm
import com.jakedonnini.sattracker.data.remote.CelesTrakClient
import com.jakedonnini.sattracker.orbit.Satellite
import com.jakedonnini.sattracker.orbit.toSatellite

/**
 * Single source of truth for the satellite catalog.
 *
 * Reads always come from the Room cache. The network is hit only when the cache is older than
 * [maxAgeMillis], and never more often than CelesTrak's update cadence ([minRefreshMillis]).
 * A failed download falls back to whatever is cached.
 */
class SatelliteRepository(
    private val dao: SatelliteDao,
    private val client: CelesTrakClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = 12 * HOUR,
    private val minRefreshMillis: Long = 2 * HOUR,
) {
    /** Returns all cached LEO satellites, refreshing from CelesTrak first if the cache is stale. */
    suspend fun getLeoSatellites(): List<Satellite> {
        refreshIfNeeded()
        return dao.getLeo().mapNotNull { entity ->
            runCatching { entity.toOmm().toSatellite() }
                .onFailure { Log.w(TAG, "Bad elements for ${entity.noradId}", it) }
                .getOrNull()
        }
    }

    private suspend fun refreshIfNeeded() {
        val lastFetch = dao.getFetch(GROUP)?.fetchedAtMillis
        val age = lastFetch?.let { clock() - it } ?: Long.MAX_VALUE
        val stale = age > maxAgeMillis || dao.count() == 0
        if (age < minRefreshMillis || !stale) return

        try {
            val records = client.fetchGroup(GROUP)
            dao.replaceCatalog(GROUP, records.map { it.toEntity() }, clock())
            Log.i(TAG, "Downloaded ${records.size} satellites from CelesTrak")
        } catch (e: Exception) {
            // Include e in the message: Log hides stack traces for UnknownHostException.
            Log.w(TAG, "Catalog refresh failed ($e); using cache (${dao.count()} sats)", e)
        }
    }

    private companion object {
        const val TAG = "SatelliteRepository"
        const val HOUR = 60 * 60 * 1000L

        // "active" is a superset of starlink/stations/etc.; CelesTrak asks not to fetch both.
        const val GROUP = "active"
    }
}
