package com.jakedonnini.sattracker.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.jakedonnini.sattracker.geometry.Ecef
import com.jakedonnini.sattracker.geometry.Enu
import com.jakedonnini.sattracker.geometry.Geodesy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream

/**
 * Hosts the globe page (assets/globe, globe.gl + three.js) in a WebView and bridges data to it.
 *
 * - The page is served from `https://appassets.androidplatform.net/assets/globe/` by
 *   [WebViewAssetLoader], so it has a normal HTTPS origin and needs no file:// access.
 * - `/api/catalog` and `/api/positions` are answered from [catalogJson] / [positions]
 *   (pulled by the page every few seconds).
 * - Pose and locked satellites are pushed with `evaluateJavascript` into `window.SatGlobe`.
 */
class GlobeController(
    private val webView: WebView,
    private val catalogJson: () -> String?,
    private val positions: () -> ByteArray?,
) {
    @Serializable
    private class Pose(
        val obs: DoubleArray,
        val up: DoubleArray,
        val dirs: List<DoubleArray>,
        val lat: Double,
        val lon: Double,
    )

    @Serializable
    class LockedSatellite(val id: Int, val name: String, val color: String, val path: FloatArray?)

    private val json = Json { encodeDefaults = true }

    @SuppressLint("SetJavaScriptEnabled") // our own bundled page only; no remote content
    fun start() {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(webView.context))
            .addPathHandler("/api/", ::handleApi)
            .build()
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        webView.loadUrl(PAGE_URL)
    }

    /** Runs on a WebView background thread. */
    private fun handleApi(path: String): WebResourceResponse {
        val body: ByteArray?
        val mime: String
        when (path) {
            "catalog" -> { body = catalogJson()?.toByteArray(); mime = "application/json" }
            "positions" -> { body = positions(); mime = "application/octet-stream" }
            else -> return WebResourceResponse("text/plain", null, 404, "Not Found", NO_CACHE, null)
        }
        return if (body == null) {
            // Not ready yet; the page retries.
            WebResourceResponse(mime, null, 204, "No Content", NO_CACHE, null)
        } else {
            WebResourceResponse(mime, null, 200, "OK", NO_CACHE, ByteArrayInputStream(body))
        }
    }

    /**
     * Pushes the user's position and camera view.
     * @param viewDirections boresight + four corners, true-north ENU (see Projector.viewDirections).
     */
    fun pushPose(latDeg: Double, lonDeg: Double, altMeters: Double, viewDirections: List<Enu>) {
        fun Ecef.arr() = doubleArrayOf(x, y, z)
        val pose = Pose(
            obs = Geodesy.geodeticToEcefKm(latDeg, lonDeg, altMeters).arr(),
            up = Geodesy.enuToEcef(Enu(0.0, 0.0, 1.0), latDeg, lonDeg).arr(),
            dirs = viewDirections.map { Geodesy.enuToEcef(it, latDeg, lonDeg).arr() },
            lat = latDeg,
            lon = lonDeg,
        )
        call("setPose", json.encodeToString(pose))
    }

    fun pushLocked(locked: List<LockedSatellite>) = call("setLocked", json.encodeToString(locked))

    fun flyToUser() = call("flyToUser", "")

    /** Which visual layers the globe draws (design doc §11.7); all on by default. */
    @Serializable
    data class Layers(
        val visibility: Boolean = true,
        val fov: Boolean = true,
        val horizon: Boolean = true,
        val inView: Boolean = true,
        val outOfView: Boolean = true,
    )

    private var layers = Layers()

    fun setLayers(next: Layers) {
        layers = next
        call("setLayers", json.encodeToString(next))
    }

    /** Re-sends state the page may have missed while it was still loading. */
    fun resendLayers() = call("setLayers", json.encodeToString(layers))

    fun onShown() {
        webView.onResume()
        call("resume", "")
    }

    fun onHidden() {
        call("pause", "")
        webView.onPause()
    }

    private fun call(method: String, arg: String) {
        // Guarded: the page may still be loading.
        webView.evaluateJavascript("window.SatGlobe && SatGlobe.$method($arg)", null)
    }

    private companion object {
        const val PAGE_URL = "https://appassets.androidplatform.net/assets/globe/index.html"
        val NO_CACHE = mapOf("Cache-Control" to "no-store")
    }
}
