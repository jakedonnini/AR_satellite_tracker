package com.jakedonnini.sattracker.data.remote

import com.jakedonnini.sattracker.air.Aircraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos

/**
 * Live aircraft near the user from The OpenSky Network's ADS-B feed
 * (https://openskynetwork.github.io/opensky-api/rest.html), anonymous access.
 *
 * Anonymous limits: 400 credits a day per IP, and a bounding box under 25 square degrees costs
 * 1 credit, with 10 s time resolution. So callers poll no faster than every 10 s, only while the
 * AR view is open, and honour the retry-after header on HTTP 429.
 */
class OpenSkyClient(
    private val http: OkHttpClient = defaultHttpClient(),
) {
    class Result(val aircraft: List<Aircraft>, val creditsRemaining: Int?)

    class RateLimited(val retryAfterSeconds: Long) : IOException("OpenSky rate limit; retry in $retryAfterSeconds s")

    /** Airborne aircraft with a fresh position inside a box around (lat, lon) of half-size [radiusKm]. */
    suspend fun aircraftNear(latDeg: Double, lonDeg: Double, radiusKm: Double): Result = withContext(Dispatchers.IO) {
        val dLat = radiusKm / KM_PER_DEG
        val dLon = radiusKm / (KM_PER_DEG * cos(Math.toRadians(latDeg)).coerceAtLeast(0.01))
        val url = String.format(
            Locale.US, "%s?lamin=%.4f&lomin=%.4f&lamax=%.4f&lomax=%.4f",
            BASE_URL, latDeg - dLat, lonDeg - dLon, latDeg + dLat, lonDeg + dLon,
        )
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 429) {
                throw RateLimited(response.header("X-Rate-Limit-Retry-After-Seconds")?.toLongOrNull() ?: DEFAULT_BACKOFF_S)
            }
            if (response.code != 200) throw IOException("OpenSky returned HTTP ${response.code}")
            Result(
                aircraft = parse(response.body!!.string(), System.currentTimeMillis() / 1000),
                creditsRemaining = response.header("X-Rate-Limit-Remaining")?.toIntOrNull(),
            )
        }
    }

    companion object {
        private const val BASE_URL = "https://opensky-network.org/api/states/all"
        private const val USER_AGENT = "SatTracker-Android/1.0"
        private const val KM_PER_DEG = 111.32
        private const val DEFAULT_BACKOFF_S = 300L

        /** Older positions are dropped: a jet covers ~7 km in 30 s, so the marker would be wrong. */
        const val MAX_POSITION_AGE_S = 30L

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a `/states/all` body. Each state is a positional array: 0 icao24, 1 callsign,
         * 2 origin country, 3 time_position, 5 lon, 6 lat, 7 baro altitude (m), 8 on_ground,
         * 9 velocity (m/s), 10 true track (deg), 11 vertical rate (m/s), 13 geometric altitude (m).
         * Aircraft on the ground, without a position or altitude, or with a stale position are left out.
         */
        fun parse(body: String, nowSec: Long): List<Aircraft> {
            val states = json.parseToJsonElement(body).jsonObject["states"]
            if (states == null || states is JsonNull) return emptyList()
            return states.jsonArray.mapNotNull { element ->
                val s = element.jsonArray
                fun str(i: Int) = s.getOrNull(i)?.jsonPrimitive?.contentOrNull
                fun num(i: Int) = s.getOrNull(i)?.jsonPrimitive?.doubleOrNull
                if (s.getOrNull(8)?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
                val icao = str(0) ?: return@mapNotNull null
                val lon = num(5) ?: return@mapNotNull null
                val lat = num(6) ?: return@mapNotNull null
                val time = s.getOrNull(3)?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                if (nowSec - time > MAX_POSITION_AGE_S) return@mapNotNull null
                val geo = num(13)
                val alt = geo ?: num(7) ?: return@mapNotNull null
                Aircraft(
                    icao24 = icao,
                    callsign = str(1)?.trim()?.takeIf { it.isNotEmpty() } ?: icao.uppercase(Locale.US),
                    originCountry = str(2).orEmpty(),
                    latDeg = lat,
                    lonDeg = lon,
                    altitudeM = alt,
                    altitudeIsGeometric = geo != null,
                    // Missing speed or track: no extrapolation rather than a guess.
                    speedMs = num(9) ?: 0.0,
                    trackDeg = num(10) ?: 0.0,
                    verticalRateMs = num(11) ?: 0.0,
                    positionTimeSec = time,
                )
            }
        }

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonArray.getOrNull(i: Int) = if (i in indices) this[i] else null
