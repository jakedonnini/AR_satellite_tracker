package com.jakedonnini.sattracker.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.jakedonnini.sattracker.data.remote.SatcatRecord
import com.jakedonnini.sattracker.data.remote.SatelliteInfoClient
import com.jakedonnini.sattracker.data.remote.SatnogsSatellite
import com.jakedonnini.sattracker.data.remote.WikiSummary
import com.jakedonnini.sattracker.orbit.Satellite
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Where the Analyze panel's photo came from, for the caption. */
enum class ImageSource { WIKIPEDIA, SATNOGS, WIKIPEDIA_GENERAL }

/** Everything the Analyze panel shows that comes from the internet. */
data class SatelliteInfo(
    val satcat: SatcatRecord?,
    val satnogs: SatnogsSatellite?,
    val wiki: WikiSummary?,
    /** True when [wiki] describes the constellation / object class rather than this satellite. */
    val wikiIsGeneral: Boolean,
    val image: Bitmap?,
    val imageSource: ImageSource?,
)

/**
 * Fetches satellite background info on demand (design doc section 13). Results are cached in
 * memory for this session only, so nothing is stored on the device.
 *
 * Article lookup, most to least specific:
 *  1. Wikidata item tagged with the NORAD number (P377) and its English Wikipedia article;
 *  2. Wikipedia full-text search on the satellite's names (CelesTrak + SatNOGS aliases), keeping
 *     only a result whose title matches a name, whose text is about a spacecraft, and which
 *     mentions the SATCAT launch year (a program-level article is shown as general info);
 *  3. the constellation / object-class article (Starlink, debris, rocket body, ...).
 * Photo: the satellite's own article, else SatNOGS, else the general article.
 */
class SatelliteInfoRepository(
    private val client: SatelliteInfoClient = SatelliteInfoClient(),
) {
    private val cache = LruCache<Int, SatelliteInfo>(32)

    suspend fun fetch(satellite: Satellite): SatelliteInfo {
        cache.get(satellite.noradId)?.let { return it }
        val info = coroutineScope {
            val satcatJob = async { client.satcat(satellite.noradId) }
            // SatNOGS is a volunteer-run server and is sometimes unreachable; don't let it stall the panel.
            val satnogsJob = async { withTimeoutOrNull(SATNOGS_TIMEOUT_MS) { client.satnogs(satellite.noradId) } }
            val wikidataJob = async {
                client.wikidataItem(satellite.noradId)?.let { client.enwikiTitle(it) }
            }
            val satcat = satcatJob.await()
            val satnogs = satnogsJob.await()

            // Wikidata's NORAD link is authoritative. A search hit must also prove it's a spacecraft
            // and match the catalog's launch year; a program-level hit counts as general info.
            val linked = wikidataJob.await()?.let { client.wikipediaSummary(it) }
            val searched = if (linked != null || SatelliteFacts.skipsSpecificSearch(satellite.name)) null
            else searchSummary(
                listOfNotNull(satellite.name, satcat?.name, satnogs?.name) + satnogs?.aliases.orEmpty(),
                launchYear = satcat?.launchDate?.take(4)?.toIntOrNull(),
            )
            val specific = linked ?: searched?.takeIf { it.second == SatelliteFacts.TitleMatch.EXACT }?.first
            val general = if (specific != null) null
            else searched?.first
                ?: SatelliteFacts.fallbackArticle(satellite.name, satcat?.objectType)?.let { client.wikipediaSummary(it) }
            val wiki = specific ?: general

            val (imageUrl, source) = when {
                specific?.thumbnail != null -> specific.thumbnail.source to ImageSource.WIKIPEDIA
                satnogs?.imageUrl != null -> satnogs.imageUrl to ImageSource.SATNOGS
                general?.thumbnail != null -> general.thumbnail.source to ImageSource.WIKIPEDIA_GENERAL
                else -> null to null
            }
            val image = imageUrl?.let { client.bytes(it) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            SatelliteInfo(satcat, satnogs, wiki, specific == null && wiki != null, image, source.takeIf { image != null })
        }
        // Don't cache a total failure (e.g. offline), so the user can retry.
        if (info.satcat != null || info.wiki != null || info.satnogs != null) cache.put(satellite.noradId, info)
        return info
    }

    /**
     * First search hit, over the candidate names, whose title matches the name *and* whose
     * article is verifiably about a spacecraft. Better no article than a wrong one.
     */
    private suspend fun searchSummary(names: List<String>, launchYear: Int?): Pair<WikiSummary, SatelliteFacts.TitleMatch>? {
        if (launchYear == null) return null // nothing to verify a search hit against
        var summariesFetched = 0
        for (candidate in SatelliteFacts.searchCandidates(names).take(MAX_SEARCHES)) {
            val hits = client.wikipediaSearch("$candidate satellite")
                .map { it to SatelliteFacts.titleMatch(candidate, it) }
                .filter { it.second != SatelliteFacts.TitleMatch.NONE }
            for ((title, match) in hits) {
                if (summariesFetched++ >= MAX_SEARCHES) return null
                val summary = client.wikipediaSummary(title) ?: continue
                if (SatelliteFacts.isSpacecraftArticle(summary.description, summary.extract) &&
                    SatelliteFacts.matchesLaunchYear(match, summary.description, summary.extract, launchYear)
                ) return summary to match
            }
        }
        return null
    }

    private companion object {
        /** Keep look-ups light: at most this many search requests per satellite. */
        const val MAX_SEARCHES = 3
        const val SATNOGS_TIMEOUT_MS = 4000L
    }
}
