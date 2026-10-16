package com.jakedonnini.sattracker.orbit

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.orekit.data.DataContext
import org.orekit.data.DirectoryCrawler
import java.io.File

/**
 * Makes the bundled `orekit-data` (leap seconds, EOP, ephemerides) available to Orekit.
 *
 * Orekit's [DirectoryCrawler] needs a real filesystem path, so on first launch the asset
 * tree is copied into internal storage and then registered with the default data context.
 */
object OrekitInitializer {
    private const val TAG = "OREKIT"
    private const val ASSET_DIR = "orekit-data"

    @Volatile
    private var initialized = false

    /**
     * Idempotent. Blocking (copies ~37 MB on first run), so call it off the main thread.
     * [onProgress] receives 0..1 as files are copied (jumps straight to 1 when already copied).
     */
    @Synchronized
    fun init(context: Context, onProgress: (Float) -> Unit = {}) {
        if (initialized) {
            onProgress(1f)
            return
        }
        try {
            val dataDir = File(context.filesDir, ASSET_DIR)

            if (dataDir.listFiles().isNullOrEmpty()) {
                val files = listAssetFiles(context.assets, ASSET_DIR)
                files.forEachIndexed { i, path ->
                    copyAsset(context.assets, path, File(context.filesDir, path))
                    onProgress((i + 1).toFloat() / files.size)
                }
                Log.d(TAG, "Copied ${files.size} files to: ${dataDir.absolutePath}")
            }

            DataContext.getDefault().dataProvidersManager.addProvider(DirectoryCrawler(dataDir))
            initialized = true
            onProgress(1f)
            Log.d(TAG, "Orekit data loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Orekit initialization failed: ${e.message}", e)
        }
    }

    /** All file paths (not directories) under [assetPath], relative to the asset root. */
    private fun listAssetFiles(assets: AssetManager, assetPath: String): List<String> {
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) return listOf(assetPath)
        return children.flatMap { listAssetFiles(assets, "$assetPath/$it") }
    }

    private fun copyAsset(assets: AssetManager, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
