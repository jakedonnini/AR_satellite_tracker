package com.jakedonnini.sattracker

import org.orekit.data.DataContext
import org.orekit.data.DirectoryCrawler
import java.io.File

/** Points Orekit at the bundled data. Gradle runs unit tests from the module dir (app/). */
object OrekitTestData {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val dir = File("src/main/assets/orekit-data")
        check(dir.isDirectory) { "orekit-data not found at ${dir.absolutePath}" }
        DataContext.getDefault().dataProvidersManager.addProvider(DirectoryCrawler(dir))
        loaded = true
    }

    fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "missing fixture $name" }.readText()
}
