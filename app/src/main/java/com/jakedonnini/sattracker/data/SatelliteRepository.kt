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
    /**
     * Returns all cached LEO satellites, refreshing from CelesTrak first if the cache is stale.
     * [onProgress] receives 0..1: the download is most of it (0..0.85), then reading the cache.
     */
    suspend fun getLeoSatellites(onProgress: (Float) -> Unit = {}): List<Satellite> {
        refreshIfNeeded { onProgress(0.85f * it) }
        onProgress(0.85f)
        val entities = dao.getLeo()
        return entities.mapIndexedNotNull { i, entity ->
            if (i % 1000 == 0) onProgress(0.85f + 0.15f * i / entities.size)
            runCatching { entity.toOmm().toSatellite() }
                .onFailure { Log.w(TAG, "Bad elements for ${entity.noradId}", it) }
                .getOrNull()
        }.also { onProgress(1f) }
    }

    private suspend fun refreshIfNeeded(onProgress: (Float) -> Unit) {
        val lastFetch = dao.getFetch(GROUP)?.fetchedAtMillis
        val age = lastFetch?.let { clock() - it } ?: Long.MAX_VALUE
        val stale = age > maxAgeMillis || dao.count() == 0
        if (age < minRefreshMillis || !stale) return

        try {
            val records = client.fetchGroup(GROUP) { bytes, total ->
                // CelesTrak streams without a Content-Length; fall back to the typical size.
                onProgress((bytes.toFloat() / (total ?: EXPECTED_DOWNLOAD_BYTES)).coerceAtMost(0.95f))
            }
            onProgress(0.95f)
            dao.replaceCatalog(GROUP, records.map { it.toEntity() }, clock())
            onProgress(1f)
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

        /** Size of the "active" group as OMM JSON (~16.7k objects), for progress without a Content-Length. */
        const val EXPECTED_DOWNLOAD_BYTES = 9_000_000L
    }
}
