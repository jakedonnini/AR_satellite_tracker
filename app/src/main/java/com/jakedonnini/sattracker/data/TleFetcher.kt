package com.jakedonnini.sattracker.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.orekit.propagation.analytical.tle.TLE
import java.net.URL

/** Downloads two-line element sets from CelesTrak. */
object TleFetcher {
    private const val TAG = "TleFetcher"

    // only works for starlink satellites now
    private const val STARLINK_URL =
        "https://celestrak.org/NORAD/elements/gp.php?GROUP=starlink&FORMAT=tle"

    suspend fun fetchFromCelestrak(): List<TLE> = withContext(Dispatchers.IO) {
        try {
            parse(URL(STARLINK_URL).readText().lines())
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TLEs", e)
            emptyList()
        }
    }

    /** Parses 3-line (name + 2 element lines) TLE text, skipping malformed entries. */
    private fun parse(lines: List<String>): List<TLE> {
        val tleList = mutableListOf<TLE>()
        var i = 0
        while (i < lines.size - 2) {
            val nameLine = lines[i].trim()
            val line1 = lines[i + 1].trim()
            val line2 = lines[i + 2].trim()

            if (line1.startsWith("1") && line2.startsWith("2")) {
                try {
                    tleList.add(TLE(line1, line2))
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid TLE for: $nameLine", e)
                }
                i += 3
            } else {
                i++
            }
        }
        return tleList
    }
}
