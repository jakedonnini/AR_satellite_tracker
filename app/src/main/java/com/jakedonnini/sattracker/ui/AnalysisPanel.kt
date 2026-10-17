package com.jakedonnini.sattracker.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.text.inSpans
import com.jakedonnini.sattracker.R
import com.jakedonnini.sattracker.data.ImageSource
import com.jakedonnini.sattracker.data.SatelliteFacts
import com.jakedonnini.sattracker.orbit.NakedEye

/** Binds the full-screen "Analyze" panel (layout `analysisPanel`) to an [AnalysisState]. */
class AnalysisPanel(root: View, onClose: () -> Unit) {
    private val panel = root.findViewById<View>(R.id.analysisPanel)
    private val title = root.findViewById<TextView>(R.id.analysisTitle)
    private val subtitle = root.findViewById<TextView>(R.id.analysisSubtitle)
    private val image = root.findViewById<ImageView>(R.id.analysisImage)
    private val imageNote = root.findViewById<TextView>(R.id.analysisImageNote)
    private val progress = root.findViewById<ProgressBar>(R.id.analysisProgress)
    private val body = root.findViewById<TextView>(R.id.analysisBody)

    init {
        root.findViewById<Button>(R.id.analysisClose).setOnClickListener { onClose() }
        body.movementMethod = LinkMovementMethod.getInstance()
    }

    fun render(state: AnalysisState?, live: TrackedSatellite?) {
        if (state == null) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        val sat = state.satellite
        val info = state.info
        title.text = sat.name
        subtitle.text = buildString {
            append("NORAD ${sat.noradId}")
            if (sat.objectId.isNotBlank()) append("  ·  ${sat.objectId}")
            info?.wiki?.description?.takeIf { !info.wikiIsGeneral }?.let { append("\n").append(it) }
        }

        progress.visibility = if (state.loading) View.VISIBLE else View.GONE
        image.setImageBitmap(info?.image)
        image.visibility = if (info?.image != null) View.VISIBLE else View.GONE
        imageNote.text = when (info?.imageSource) {
            ImageSource.SATNOGS -> "Photo: SatNOGS DB"
            ImageSource.WIKIPEDIA_GENERAL -> info.wiki?.title?.let { "Representative image: $it" }
            else -> null
        }
        imageNote.visibility = if (imageNote.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        body.text = SpannableStringBuilder().apply {
            live?.details?.let { d ->
                heading("Right now")
                fact("Altitude", "%,.0f km".format(d.altitudeKm))
                fact("Range", "%,.0f km".format(d.rangeKm))
                fact("Speed", "%.2f km/s, %s".format(d.speedKmS, if (d.rising) "rising" else "setting"))
                fact("Direction", "Az %.1f°, El %.1f°".format(d.azimuthDeg, d.elevationDeg))
                val v = d.visibility
                fact("Naked eye", when (v.status) {
                    NakedEye.LIKELY_VISIBLE -> "Likely visible, mag %.1f".format(v.magnitude)
                    NakedEye.TOO_FAINT -> "Too faint, mag %.1f".format(v.magnitude)
                    NakedEye.IN_SHADOW -> "In Earth's shadow"
                    NakedEye.SKY_TOO_BRIGHT -> "Sky too bright (Sun %+.0f°)".format(v.observerSunElevationDeg)
                    NakedEye.TOO_LOW -> "Too low (below 10°)"
                })
            }

            info?.satcat?.let { c ->
                heading("Catalog")
                SatelliteFacts.objectType(c.objectType)?.let { fact("Type", it) }
                SatelliteFacts.status(c.opsStatus)?.let { fact("Status", it) }
                SatelliteFacts.owner(c.owner)?.let { fact("Owner", it) }
                if (c.launchDate.isNotBlank()) fact("Launched", c.launchDate)
                SatelliteFacts.launchSite(c.launchSite)?.let { fact("Launch site", it) }
                c.periodMin?.let { fact("Period", "%.1f min".format(it)) }
                c.inclinationDeg?.let { fact("Inclination", "%.1f°".format(it)) }
                if (c.apogeeKm != null && c.perigeeKm != null) {
                    fact("Orbit", "%,.0f × %,.0f km".format(c.perigeeKm, c.apogeeKm))
                }
                SatelliteFacts.sizeClass(c.rcsM2)?.let { fact("Size", it) }
            }

            info?.satnogs?.let { n ->
                val aliases = n.aliases.filter { !it.equals(sat.name, ignoreCase = true) }
                val operator = n.operatorName
                val countries = n.countries?.takeIf { it.isNotBlank() }
                if (aliases.isNotEmpty() || operator != null || countries != null || n.website != null) {
                    heading("Mission")
                    if (aliases.isNotEmpty()) fact("Also known as", aliases.joinToString(", "))
                    operator?.let { fact("Operator", it) }
                    countries?.let { fact("Countries", it) }
                    n.status?.takeIf { it.isNotBlank() }?.let { fact("SatNOGS status", it) }
                    n.website?.takeIf { it.startsWith("http") }?.let { url ->
                        inSpans(URLSpan(url)) { append("Mission website") }
                        append("\n")
                    }
                }
            }

            info?.wiki?.let { w ->
                heading(if (info.wikiIsGeneral) "About: ${w.title}" else "About")
                append(w.extract).append("\n")
                w.pageUrl?.let { url ->
                    inSpans(URLSpan(url)) { append("Read more on Wikipedia") }
                    append("\n")
                }
            }

            when {
                state.loading -> {}
                info == null || (info.satcat == null && info.wiki == null && info.satnogs == null) ->
                    inSpans(ForegroundColorSpan(MUTED)) { append("\nCouldn't load details. Check your connection and try again.\n") }
                else -> inSpans(ForegroundColorSpan(MUTED), RelativeSizeSpan(0.85f)) {
                    append("\nSources: CelesTrak SATCAT, SatNOGS DB, Wikidata, Wikipedia (CC BY-SA).")
                }
            }
        }
    }

    private fun SpannableStringBuilder.heading(text: String) {
        if (isNotEmpty()) append("\n")
        inSpans(StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.1f)) { append(text) }
        append("\n")
    }

    private fun SpannableStringBuilder.fact(label: String, value: String) {
        inSpans(ForegroundColorSpan(MUTED)) { append(label).append("  ") }
        append(value).append("\n")
    }

    private companion object {
        const val MUTED = 0xFFB0B0B0.toInt()
    }
}
