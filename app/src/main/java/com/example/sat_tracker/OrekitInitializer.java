package com.example.sat_tracker;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import org.orekit.errors.OrekitException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class OrekitInitializer {

    public static void initOrekit(Context context) {
        try {
            File dataDir = new File(context.getFilesDir(), "orekit-data");

            // Copy recursively from assets if needed
            if (!dataDir.exists() || dataDir.listFiles() == null || dataDir.listFiles().length == 0) {
                copyAssetsRecursively(context.getAssets(), "orekit-data", dataDir);
                Log.d("OREKIT", "Copied orekit-data to: " + dataDir.getAbsolutePath());
            }

            // Register the data with Orekit
            DirectoryCrawler crawler = new DirectoryCrawler(dataDir);
            DataContext.getDefault().getDataProvidersManager().addProvider(crawler);
            Log.d("OREKIT", "Orekit data loaded successfully");

        } catch (Exception e) {
            Log.e("OREKIT", "Orekit initialization failed: " + e.getMessage(), e);
        }
    }

    private static void copyAssetsRecursively(AssetManager assetManager, String assetPath, File destDir) throws IOException {
        String[] assets = assetManager.list(assetPath);
        if (assets == null || assets.length == 0) {
            // It's a file
            File outFile = new File(destDir, new File(assetPath).getName());
            outFile.getParentFile().mkdirs();
            try (InputStream in = assetManager.open(assetPath); FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } else {
            // It's a directory
            File dir = new File(destDir, new File(assetPath).getName());
            dir.mkdirs();
            for (String asset : assets) {
                copyAssetsRecursively(assetManager, assetPath + "/" + asset, dir);
            }
        }
    }
}
