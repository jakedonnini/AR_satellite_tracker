package com.jakedonnini.sattracker.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads GP element sets from CelesTrak in OMM/JSON form.
 *
 * Usage policy (https://celestrak.org/NORAD/documentation/gp-data-formats.php): data updates
 * every ~2 h, repeat downloads before then get HTTP 403, and repeated errors get the IP
 * firewalled. So this client never retries; callers must cache and rate-limit.
 */
class CelesTrakClient(
    private val http: OkHttpClient = defaultHttpClient(),
) {
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchGroup(group: String): List<OmmRecord> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL?GROUP=$group&FORMAT=JSON")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != 200) {
                throw CelesTrakException(response.code, "GROUP=$group returned HTTP ${response.code}")
            }
            json.decodeFromStream<List<OmmRecord>>(response.body!!.byteStream())
        }
    }

    companion object {
        private const val BASE_URL = "https://celestrak.org/NORAD/elements/gp.php"
        private const val USER_AGENT = "SatTracker-Android/1.0"

        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false) // a redirect means the URL is wrong; don't loop on it
            .build()
    }
}

class CelesTrakException(val httpCode: Int, message: String) : IOException(message)
