package com.jakedonnini.sattracker.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One CelesTrak SATCAT record (https://celestrak.org/satcat/records.php). */
@Serializable
data class SatcatRecord(
    @SerialName("OBJECT_NAME") val name: String,
    @SerialName("OBJECT_ID") val objectId: String = "",
    @SerialName("NORAD_CAT_ID") val noradId: Int,
    @SerialName("OBJECT_TYPE") val objectType: String = "",
    @SerialName("OPS_STATUS_CODE") val opsStatus: String = "",
    @SerialName("OWNER") val owner: String = "",
    @SerialName("LAUNCH_DATE") val launchDate: String = "",
    @SerialName("LAUNCH_SITE") val launchSite: String = "",
    @SerialName("PERIOD") val periodMin: Double? = null,
    @SerialName("INCLINATION") val inclinationDeg: Double? = null,
    @SerialName("APOGEE") val apogeeKm: Double? = null,
    @SerialName("PERIGEE") val perigeeKm: Double? = null,
    /** Radar cross-section, m² (a rough size indicator). */
    @SerialName("RCS") val rcsM2: Double? = null,
)

/** One SatNOGS DB satellite (https://db.satnogs.org/api/satellites/). */
@Serializable
data class SatnogsSatellite(
    val name: String = "",
    /** Comma-separated aliases, e.g. "AO-91, RADFXSAT". */
    val names: String = "",
    /** Media path relative to the SatNOGS media root, e.g. "satellites/ISS.jpg". */
    val image: String? = null,
    val status: String? = null,
    val website: String? = null,
    val operator: String? = null,
    val countries: String? = null,
) {
    val aliases: List<String> get() = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val imageUrl: String? get() = image?.takeIf { it.isNotBlank() }?.let { SATNOGS_MEDIA + it }
    /** SatNOGS uses the literal "None" for unknown operators. */
    val operatorName: String? get() = operator?.takeIf { it.isNotBlank() && it != "None" }
}

// Top level (not a companion): the serialization plugin owns @Serializable classes' companions.
private const val SATNOGS_MEDIA = "https://db-satnogs.freetls.fastly.net/media/"

/** The parts of a Wikipedia REST page summary we show. */
@Serializable
data class WikiSummary(
    val title: String,
    val description: String? = null,
    val extract: String = "",
    val thumbnail: WikiImage? = null,
    @SerialName("content_urls") val contentUrls: JsonObject? = null,
) {
    /** Link to the full article on the mobile site. */
    val pageUrl: String?
        get() = contentUrls?.get("mobile")?.jsonObject?.get("page")?.jsonPrimitive?.content
}

@Serializable
data class WikiImage(val source: String, val width: Int = 0, val height: Int = 0)

/**
 * Online look-ups for the "Analyze" panel. Every call is a single small HTTPS request made only
 * when the user asks; nothing is stored on the device (the repository keeps a session cache).
 *
 * - CelesTrak SATCAT: owner, launch, orbit and size for a NORAD id.
 * - Wikidata search (`haswbstatement:P377=<norad>`, P377 = satellite catalog number) → item id,
 *   then its English Wikipedia sitelink.
 * - Wikipedia REST page summary: description, intro text and a thumbnail.
 * - Wikipedia full-text search: article candidates when Wikidata has no NORAD link.
 * - SatNOGS DB: aliases, operator, countries, status, website and a photo, strong for
 *   amateur / small satellites.
 */
class SatelliteInfoClient(
    private val http: OkHttpClient = defaultHttpClient(),
) {
    suspend fun satcat(noradId: Int): SatcatRecord? =
        get("https://celestrak.org/satcat/records.php?CATNR=$noradId&FORMAT=JSON")
            ?.let { parseSatcat(it) }

    /** Wikidata item id (e.g. "Q25271") whose satellite catalog number is [noradId], if any. */
    suspend fun wikidataItem(noradId: Int): String? =
        get("https://www.wikidata.org/w/api.php?action=query&list=search&srlimit=1&format=json" +
            "&srsearch=haswbstatement:P377=$noradId")
            ?.let { parseWikidataSearch(it) }

    /** Title of the English Wikipedia article for a Wikidata item, if it has one. */
    suspend fun enwikiTitle(item: String): String? =
        get("https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks&sitefilter=enwiki" +
            "&format=json&ids=$item")
            ?.let { parseEnwikiTitle(it, item) }

    suspend fun wikipediaSummary(title: String): WikiSummary? {
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        return get("https://en.wikipedia.org/api/rest_v1/page/summary/$path")?.let { parseSummary(it) }
    }

    suspend fun satnogs(noradId: Int): SatnogsSatellite? =
        get("https://db.satnogs.org/api/satellites/?format=json&norad_cat_id=$noradId")
            ?.let { parseSatnogs(it) }

    /** Up to [limit] Wikipedia article titles for a full-text query, best match first. */
    suspend fun wikipediaSearch(query: String, limit: Int = 3): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        return get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=$limit&srsearch=$q")
            ?.let { parseWikipediaSearch(it) }
            .orEmpty()
    }

    suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request(url)).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        }.getOrNull()
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request(url)).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    private fun request(url: String) = Request.Builder()
        .url(url.toHttpUrl())
        .header("User-Agent", USER_AGENT)
        .build()

    companion object {
        // Wikimedia asks API clients to identify themselves with a contact URL.
        private const val USER_AGENT = "SatTracker-Android/1.0 (https://github.com/jakedonnini/AR_satellite_tracker)"

        private val json = Json { ignoreUnknownKeys = true }

        fun parseSatcat(body: String): SatcatRecord? =
            runCatching { json.decodeFromString<List<SatcatRecord>>(body).firstOrNull() }.getOrNull()

        fun parseWikidataSearch(body: String): String? = runCatching {
            json.parseToJsonElement(body).jsonObject["query"]!!.jsonObject["search"]!!
                .let { it as kotlinx.serialization.json.JsonArray }
                .firstOrNull()?.jsonObject?.get("title")?.jsonPrimitive?.content
        }.getOrNull()

        fun parseEnwikiTitle(body: String, item: String): String? = runCatching {
            json.parseToJsonElement(body).jsonObject["entities"]!!.jsonObject[item]!!.jsonObject["sitelinks"]!!
                .jsonObject["enwiki"]?.jsonObject?.get("title")?.jsonPrimitive?.content
        }.getOrNull()

        fun parseSatnogs(body: String): SatnogsSatellite? =
            runCatching { json.decodeFromString<List<SatnogsSatellite>>(body).firstOrNull() }.getOrNull()

        fun parseWikipediaSearch(body: String): List<String> = runCatching {
            (json.parseToJsonElement(body).jsonObject["query"]!!.jsonObject["search"] as kotlinx.serialization.json.JsonArray)
                .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.content }
        }.getOrDefault(emptyList())

        fun parseSummary(body: String): WikiSummary? =
            runCatching { json.decodeFromString<WikiSummary>(body) }.getOrNull()

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
