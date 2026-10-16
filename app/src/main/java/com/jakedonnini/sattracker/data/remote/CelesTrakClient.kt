package com.jakedonnini.sattracker.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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
    /**
     * @param onProgress bytes read so far and the total if the server sent a Content-Length
     *   (CelesTrak usually streams, so expect null).
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun fetchGroup(
        group: String,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): List<OmmRecord> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL?GROUP=$group&FORMAT=JSON")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code != 200) {
                throw CelesTrakException(response.code, "GROUP=$group returned HTTP ${response.code}")
            }
            val body = response.body!!
            val total = body.contentLength().takeIf { it > 0 }
            val stream = CountingInputStream(body.byteStream()) { onProgress(it, total) }
            json.decodeFromStream<List<OmmRecord>>(stream)
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

/** Reports the running byte count, throttled to every 64 KiB. */
private class CountingInputStream(
    input: InputStream,
    private val onCount: (Long) -> Unit,
) : FilterInputStream(input) {
    private var count = 0L
    private var lastReported = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(n: Long) {
        count += n
        if (count - lastReported >= 64 * 1024) {
            lastReported = count
            onCount(count)
        }
    }
}
