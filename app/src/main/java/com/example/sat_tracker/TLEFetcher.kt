package com.example.sat_tracker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.orekit.propagation.analytical.tle.TLE
import java.net.URL

object TLEFetcher {
    suspend fun fetchFromCelestrak(): List<TLE> = withContext(Dispatchers.IO) {
        val tleList = mutableListOf<TLE>()
        try {
            // only works for starlink satellites now
            val url = URL("https://celestrak.org/NORAD/elements/gp.php?GROUP=starlink&FORMAT=tle")
            val lines = url.readText().lines()
            var i = 0
            while (i < lines.size - 2) {
                val nameLine = lines[i].trim()
                val line1 = lines[i + 1].trim()
                val line2 = lines[i + 2].trim()

                if (line1.startsWith("1") && line2.startsWith("2")) {
                    try {
                        tleList.add(TLE(line1, line2))
//                        Log.d("TLEFetcher", "Fetched $line1")
                    } catch (e: Exception) {
                        Log.e("TLEFetcher", "Invalid TLE for: $nameLine", e)
                    }
                    i += 3
                } else {
                    i++
                }
            }
        } catch (e: Exception) {
            Log.e("TLEFetcher", "Error fetching TLEs", e)
        }
        tleList
    }
}
