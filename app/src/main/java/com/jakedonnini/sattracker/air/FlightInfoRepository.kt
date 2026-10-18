package com.jakedonnini.sattracker.air

import android.util.Log
import com.jakedonnini.sattracker.data.remote.AdsbdbClient
import com.jakedonnini.sattracker.data.remote.AircraftDetails
import com.jakedonnini.sattracker.data.remote.FlightRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Session cache of adsbdb routes (by callsign) and airframe details (by ICAO address).
 * "Not in the database" is cached too; network failures are retried after [RETRY_MS].
 */
class FlightInfoRepository(private val client: AdsbdbClient = AdsbdbClient()) {

    /** A completed lookup; [value] null means the database doesn't know it. */
    private class Known<T>(val value: T?)

    private val routes = ConcurrentHashMap<String, Known<FlightRoute>>()
    private val airframes = ConcurrentHashMap<String, Known<AircraftDetails>>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun route(callsign: String): FlightRoute? = routes[callsign]?.value
    fun details(icao24: String): AircraftDetails? = airframes[icao24]?.value

    /** Looks up anything not cached yet; returns true if anything new was learned. */
    suspend fun fetchMissing(aircraft: List<Aircraft>): Boolean = coroutineScope {
        val now = System.currentTimeMillis()
        fun due(key: String) = now - (failedAt[key] ?: 0L) > RETRY_MS

        val callsigns = aircraft.filter(::hasCallsign).map { it.callsign }.distinct()
            .filter { !routes.containsKey(it) && due("r:$it") }
        val addresses = aircraft.map { it.icao24 }.distinct()
            .filter { !airframes.containsKey(it) && due("a:$it") }

        val jobs = callsigns.map { cs ->
            async { lookup("r:$cs") { client.route(cs) }?.also { routes[cs] = it } != null }
        } + addresses.map { id ->
            async { lookup("a:$id") { client.aircraft(id) }?.also { airframes[id] = it } != null }
        }
        jobs.awaitAll().any { it }
    }

    /** The lookup result, or null if the request failed (to retry later). */
    private suspend fun <T> lookup(key: String, block: suspend () -> T?): Known<T>? = try {
        Known(block())
    } catch (e: IOException) {
        failedAt[key] = System.currentTimeMillis()
        null
    } catch (e: RuntimeException) {
        // Malformed record: treat as unknown rather than show something wrong.
        Log.w(TAG, "Bad adsbdb record for $key", e)
        Known(null)
    }

    /** The parser substitutes the ICAO address when no callsign was sent; nothing to look up then. */
    private fun hasCallsign(a: Aircraft) = a.callsign != a.icao24.uppercase(Locale.US)

    private companion object {
        const val TAG = "FlightInfo"
        const val RETRY_MS = 60_000L
    }
}
