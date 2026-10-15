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

    fun init(context: Context) {
        try {
            val dataDir = File(context.filesDir, ASSET_DIR)

            if (dataDir.listFiles().isNullOrEmpty()) {
                copyAssetsRecursively(context.assets, ASSET_DIR, context.filesDir)
                Log.d(TAG, "Copied $ASSET_DIR to: ${dataDir.absolutePath}")
            }

            DataContext.getDefault().dataProvidersManager.addProvider(DirectoryCrawler(dataDir))
            Log.d(TAG, "Orekit data loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Orekit initialization failed: ${e.message}", e)
        }
    }

    private fun copyAssetsRecursively(assets: AssetManager, assetPath: String, destDir: File) {
        val children = assets.list(assetPath)
        val target = File(destDir, File(assetPath).name)
        if (children.isNullOrEmpty()) {
            // It's a file
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            // It's a directory
            target.mkdirs()
            for (child in children) {
                copyAssetsRecursively(assets, "$assetPath/$child", target)
            }
        }
    }
}
