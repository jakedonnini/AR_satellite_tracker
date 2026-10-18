package com.jakedonnini.sattracker.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class Airport(
    val name: String,
    @SerialName("iata_code") val iata: String? = null,
    @SerialName("icao_code") val icao: String? = null,
    val municipality: String? = null,
    @SerialName("country_name") val country: String? = null,
    val latitude: Double,
    val longitude: Double,
) {
    /** "IST", falling back to the ICAO code. */
    val code: String get() = iata?.takeIf { it.isNotBlank() } ?: icao.orEmpty()
}

@Serializable
data class Airline(val name: String, val icao: String? = null, val iata: String? = null, val country: String? = null)

/** A scheduled route for a callsign. It comes from a database, not the aircraft, so check it ([RouteCheck]). */
@Serializable
data class FlightRoute(
    val callsign: String,
    val airline: Airline? = null,
    val origin: Airport,
    val destination: Airport,
)

/** Registry details for a transponder address. */
@Serializable
data class AircraftDetails(
    val type: String? = null,
    val manufacturer: String? = null,
    val registration: String? = null,
    @SerialName("registered_owner") val owner: String? = null,
    @SerialName("registered_owner_country_name") val ownerCountry: String? = null,
)

/**
 * Route and airframe lookups from adsbdb (https://www.adsbdb.com, open source, no key).
 * Returns null when the database doesn't know the callsign / address (HTTP 404).
 */
class AdsbdbClient(
    private val http: OkHttpClient = defaultHttpClient(),
) {
    suspend fun route(callsign: String): FlightRoute? =
        get("callsign/${encode(callsign)}")?.get("flightroute")?.let { json.decodeFromJsonElement(it) }

    suspend fun aircraft(icao24: String): AircraftDetails? =
        get("aircraft/${encode(icao24)}")?.get("aircraft")?.let { json.decodeFromJsonElement(it) }

    private suspend fun get(path: String): JsonObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE_URL/$path").header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            if (response.code != 200) throw IOException("adsbdb returned HTTP ${response.code}")
            parseResponse(response.body!!.string())
        }
    }

    companion object {
        private const val BASE_URL = "https://api.adsbdb.com/v0"
        private const val USER_AGENT = "SatTracker-Android/1.0"
        private val json = Json { ignoreUnknownKeys = true }

        /** The `response` object, or null for the "unknown ..." string responses. */
        fun parseResponse(body: String): JsonObject? =
            json.parseToJsonElement(body).jsonObject["response"] as? JsonObject

        fun parseRoute(body: String): FlightRoute? =
            parseResponse(body)?.get("flightroute")?.let { json.decodeFromJsonElement(it) }

        fun parseAircraft(body: String): AircraftDetails? =
            parseResponse(body)?.get("aircraft")?.let { json.decodeFromJsonElement(it) }

        private fun encode(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
