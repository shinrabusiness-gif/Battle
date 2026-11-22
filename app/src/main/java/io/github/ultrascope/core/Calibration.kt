package io.github.ultrascope.core

import kotlin.math.abs

/**
 * Eine Massstabskalibrierung, aufgenommen gegen ein Objekt bekannter Laenge
 * (Objektmikrometer, Lineal mit mm-Teilung, Messschieber ...).
 *
 * Der Massstab wird normiert gespeichert: Mikrometer pro Pixel eines Bildes der Breite
 * [refImageWidth] bei Zoomfaktor 1,0. Damit laesst er sich auf jede Bildbreite und jeden
 * Digitalzoom derselben Kamera umrechnen.
 *
 * Der Massstab gilt NUR fuer dieselbe Kamera und (annaehernd) dieselbe Fokusposition, weil die
 * Vergroesserung von der Objektdistanz abhaengt. Fuer Messungen daher den Fokus manuell
 * fixieren und die Schaerfe durch Verschieben des Handys bzw. Objekts einstellen.
 */
data class Calibration(
    val cameraKey: String,
    val umPerPxAtZoom1: Double,
    val refImageWidth: Int,
    /** Manueller Fokus zum Kalibrierzeitpunkt in Dioptrien; null = Autofokus/Fixfokus. */
    val focusDiopters: Float?,
    val referenceLengthUm: Double,
    val measuredPx: Double,
    val zoomAtCalibration: Float,
    val timestampMs: Long,
    /**
     * Von einer logischen Multikamera gemeldete aktive physische Kamera beim Kalibrieren.
     * Samsung wechselt bei der logischen Kamera je nach Zoom das Objektiv – dann gilt der
     * Massstab nicht mehr.
     */
    val activePhysicalId: String? = null,
) {
    /** µm pro Pixel in einem Bild der Breite [imageWidth] bei Digitalzoom [zoom]. */
    fun umPerPx(imageWidth: Int, zoom: Float): Double {
        if (imageWidth <= 0 || zoom <= 0f) return Double.NaN
        return umPerPxAtZoom1 * (refImageWidth.toDouble() / imageWidth) / zoom
    }

    /**
     * Relative Fokusabweichung (0.0 = identisch). Bei Dioptrien gilt naeherungsweise
     * m ~ f * D, d. h. 2 % Fokusabweichung entsprechen ~2 % Massstabsfehler.
     */
    fun focusDeviation(currentDiopters: Float?): Double {
        val cal = focusDiopters ?: return 0.0
        val cur = currentDiopters ?: return 1.0
        if (cal <= 0f) return if (cur <= 0f) 0.0 else 1.0
        return abs(cur - cal).toDouble() / cal
    }

    companion object {
        /** Ab dieser relativen Fokusabweichung wird der Massstab als ungueltig markiert. */
        const val FOCUS_TOLERANCE = 0.02

        /**
         * Erzeugt eine Kalibrierung aus einer Messung im Bild.
         * @param measuredImagePx Laenge der Referenz in Pixeln des Bildes mit Breite [imageWidth]
         */
        fun create(
            cameraKey: String,
            referenceLengthUm: Double,
            measuredImagePx: Double,
            imageWidth: Int,
            zoom: Float,
            focusDiopters: Float?,
            timestampMs: Long = System.currentTimeMillis(),
            activePhysicalId: String? = null,
        ): Calibration {
            require(referenceLengthUm > 0) { "Referenzlaenge muss > 0 sein" }
            require(measuredImagePx > 1.0) { "Messstrecke zu kurz" }
            require(imageWidth > 0 && zoom > 0f)
            val umPerImagePx = referenceLengthUm / measuredImagePx
            return Calibration(
                cameraKey = cameraKey,
                umPerPxAtZoom1 = umPerImagePx * zoom,
                refImageWidth = imageWidth,
                focusDiopters = focusDiopters,
                referenceLengthUm = referenceLengthUm,
                measuredPx = measuredImagePx,
                zoomAtCalibration = zoom,
                timestampMs = timestampMs,
                activePhysicalId = activePhysicalId,
            )
        }

        /**
         * Waehlt aus mehreren Kalibrierungen einer Kamera die passendste: bevorzugt dieselbe
         * Fokusart (manuell = [focusDiopters] != null, sonst Autofokus) und dasselbe aktive
         * Objektiv, dann die naechste Fokusposition.
         */
        fun best(
            list: List<Calibration>,
            cameraKey: String,
            focusDiopters: Float?,
            activePhysicalId: String? = null,
        ): Calibration? {
            val cam = list.filter { it.cameraKey == cameraKey }
            val sameKind = cam.filter { (it.focusDiopters == null) == (focusDiopters == null) }.ifEmpty { cam }
            val sameLens = sameKind.filter { it.activePhysicalId == null || activePhysicalId == null || it.activePhysicalId == activePhysicalId }
                .ifEmpty { sameKind }
            return sameLens.minByOrNull { it.focusDeviation(focusDiopters) }
        }
    }
}
