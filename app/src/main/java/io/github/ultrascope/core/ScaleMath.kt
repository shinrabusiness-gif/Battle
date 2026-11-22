package io.github.ultrascope.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

object ScaleMath {

    /**
     * Groesster "runder" Wert aus der 1-2-5-Reihe, der kleiner/gleich [maxValue] ist.
     * Wird fuer Gitterabstaende und Massstabsbalken verwendet.
     */
    fun niceFloor(maxValue: Double): Double {
        if (maxValue <= 0.0 || maxValue.isNaN() || maxValue.isInfinite()) return 0.0
        val exp = floor(log10(maxValue))
        val base = 10.0.pow(exp)
        val f = maxValue / base
        val nice = when {
            f >= 5.0 -> 5.0
            f >= 2.0 -> 2.0
            else -> 1.0
        }
        return nice * base
    }

    fun distance(x1: Double, y1: Double, x2: Double, y2: Double): Double = hypot(x2 - x1, y2 - y1)

    /** Formatiert eine Laenge in Mikrometern menschenlesbar (nm/µm/mm/cm). */
    fun formatLength(um: Double): String {
        val a = abs(um)
        return when {
            a.isNaN() -> "–"
            a < 1.0 -> String.format(Locale.GERMANY, "%.0f nm", um * 1000.0)
            a < 10.0 -> String.format(Locale.GERMANY, "%.2f µm", um)
            a < 100.0 -> String.format(Locale.GERMANY, "%.1f µm", um)
            a < 1000.0 -> String.format(Locale.GERMANY, "%.0f µm", um)
            a < 10_000.0 -> String.format(Locale.GERMANY, "%.3f mm", um / 1000.0)
            a < 100_000.0 -> String.format(Locale.GERMANY, "%.2f mm", um / 1000.0)
            else -> String.format(Locale.GERMANY, "%.1f cm", um / 10_000.0)
        }
    }

    fun formatExposure(ns: Long): String {
        if (ns <= 0) return "–"
        val s = ns / 1e9
        return if (s >= 0.5) String.format(Locale.GERMANY, "%.1f s", s)
        else "1/" + String.format(Locale.US, "%.0f", 1.0 / s) + " s"
    }

    /** Fokusdistanz (Dioptrien) -> lesbarer Abstand. 0 D = unendlich. */
    fun formatFocus(diopters: Float): String {
        if (diopters <= 0.001f) return "∞"
        val mm = 1000.0 / diopters
        return if (mm < 1000) String.format(Locale.GERMANY, "%.0f mm", mm)
        else String.format(Locale.GERMANY, "%.2f m", mm / 1000.0)
    }
}
