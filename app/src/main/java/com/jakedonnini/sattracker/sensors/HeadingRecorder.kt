package com.jakedonnini.sattracker.sensors

import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * CSV log of the heading pipeline at about 4 Hz, for diagnosing drift on a real phone without
 * keeping adb attached (pull it later with `adb shell run-as <pkg> cat files/heading_log.csv`).
 * Columns: see [HEADER]. Capped at [MAX_ROWS] (over an hour at 4 Hz).
 */
class HeadingRecorder(file: File) {
    private val out: BufferedWriter = file.bufferedWriter().also { it.write(HEADER); it.newLine() }
    private var rows = 0
    private var lastNanos = 0L
    private var firstNanos = 0L

    @Synchronized
    fun write(
        timestampNanos: Long,
        absAzDeg: Double, gameAzDeg: Double, fusedAzDeg: Double, fusedElDeg: Double,
        offsetDeg: Double, sampleDeg: Double, innovationDeg: Double, state: String,
        magAccuracy: Int, headingAccuracyDeg: Double,
        fieldUt: Double, expectedFieldUt: Double, dipDeg: Double, expectedDipDeg: Double,
    ) {
        if (rows >= MAX_ROWS || timestampNanos - lastNanos < PERIOD_NANOS) return
        if (firstNanos == 0L) firstNanos = timestampNanos
        lastNanos = timestampNanos
        out.write(
            String.format(
                Locale.ROOT,
                "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%d,%.1f,%.1f,%.1f,%.1f,%.1f",
                (timestampNanos - firstNanos) / 1e9, absAzDeg, gameAzDeg, fusedAzDeg, fusedElDeg,
                offsetDeg, sampleDeg, innovationDeg, state, magAccuracy, headingAccuracyDeg,
                fieldUt, expectedFieldUt, dipDeg, expectedDipDeg,
            )
        )
        out.newLine()
        if (++rows % 8 == 0) out.flush()
    }

    @Synchronized fun flush() = runCatching { out.flush() }

    @Synchronized fun close() = runCatching { out.close() }

    private companion object {
        const val HEADER = "t_s,abs_az,game_az,fused_az,fused_el,offset,sample,innovation,state," +
            "mag_acc,heading_acc,field_ut,expected_field_ut,dip,expected_dip"
        const val PERIOD_NANOS = 250_000_000L
        const val MAX_ROWS = 20_000
    }
}
