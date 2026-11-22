package io.github.ultrascope.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Physikalisches Abschaetzungsmodell (duenne Linse) fuer die Nahaufnahme.
 *
 * Alle Eingaben stammen zur Laufzeit aus den Camera2-[android.hardware.camera2.CameraCharacteristics]
 * des echten Geraets (Brennweite, Blende, Sensorgroesse, Pixelanzahl, minimale Fokusdistanz).
 * Die Ergebnisse sind Naeherungen und KEIN Ersatz fuer die Kalibrierung mit einem Massstab:
 *  - das Duenne-Linsen-Modell ignoriert Hauptebenenabstand und Fokusatmung,
 *  - LENS_FOCUS_DISTANCE ist auf vielen Geraeten nur "APPROXIMATE" oder "UNCALIBRATED",
 *  - die Beugungsgrenze ist eine Obergrenze; reale Objektive und Demosaicing liegen darunter.
 */
object Optics {
    /** Referenzwellenlaenge (gruen) in Mikrometern. */
    const val LAMBDA_UM = 0.55
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666

    data class Input(
        val focalMm: Double,
        val fNumber: Double,
        val sensorWidthMm: Double,
        val sensorHeightMm: Double,
        /** Pixel in der Breite des Sensor-Pixelarrays (z. B. 8160 bei 50 MP). */
        val pixelArrayWidth: Int,
        /** Breite des tatsaechlich gespeicherten Bildes (z. B. 4080 bei gebinnten 12,5 MP). */
        val outputWidth: Int,
        /** Objektdistanz in Dioptrien (1/m), typischerweise LENS_INFO_MINIMUM_FOCUS_DISTANCE. */
        val focusDiopters: Double,
    )

    data class Estimate(
        val objectDistanceMm: Double,
        val magnification: Double,
        val fovWidthMm: Double,
        val fovHeightMm: Double,
        val sensorPixelPitchUm: Double,
        val umPerSensorPixel: Double,
        val umPerOutputPixel: Double,
        val workingFNumber: Double,
        val airyRadiusImageUm: Double,
        /** Rayleigh-Aufloesung im Objektraum (Beugungsgrenze). */
        val diffractionLimitObjectUm: Double,
        /** Nyquist-Grenze der gespeicherten Pixel im Objektraum (2 Pixel). */
        val samplingLimitObjectUm: Double,
        /** Groesserer (= limitierender) Wert aus Beugung und Abtastung. */
        val resolvableObjectUm: Double,
        val depthOfFieldMm: Double,
        val equivalentFocalMm: Double,
    )

    fun equivalentFocal(focalMm: Double, sensorWidthMm: Double, sensorHeightMm: Double): Double {
        val diag = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
        return if (diag > 0) focalMm * FULL_FRAME_DIAGONAL_MM / diag else 0.0
    }

    /** Abbildungsmassstab m = f / (u - f) fuer Objektdistanz u (beide in mm). */
    fun magnification(focalMm: Double, objectDistanceMm: Double): Double =
        focalMm / (objectDistanceMm - focalMm)

    fun estimate(i: Input): Estimate? {
        if (i.focusDiopters <= 0.0 || i.focalMm <= 0.0 || i.fNumber <= 0.0) return null
        if (i.sensorWidthMm <= 0.0 || i.pixelArrayWidth <= 0 || i.outputWidth <= 0) return null
        val u = 1000.0 / i.focusDiopters
        if (u <= i.focalMm * 1.01) return null
        val m = magnification(i.focalMm, u)
        val pitchUm = i.sensorWidthMm * 1000.0 / i.pixelArrayWidth
        val outPitchUm = i.sensorWidthMm * 1000.0 / i.outputWidth
        val nWorking = i.fNumber * (1.0 + m)
        val airy = 1.22 * LAMBDA_UM * nWorking
        val diffObj = airy / m
        val sampObj = 2.0 * outPitchUm / m
        // Zerstreuungskreis: das Groessere aus Airy-Durchmesser und 2 Ausgabepixeln
        val cocMm = max(2.0 * airy, 2.0 * outPitchUm) / 1000.0
        val dof = 2.0 * i.fNumber * cocMm * (1.0 + m) / (m * m)
        return Estimate(
            objectDistanceMm = u,
            magnification = m,
            fovWidthMm = i.sensorWidthMm / m,
            fovHeightMm = i.sensorHeightMm / m,
            sensorPixelPitchUm = pitchUm,
            umPerSensorPixel = pitchUm / m,
            umPerOutputPixel = outPitchUm / m,
            workingFNumber = nWorking,
            airyRadiusImageUm = airy,
            diffractionLimitObjectUm = diffObj,
            samplingLimitObjectUm = sampObj,
            resolvableObjectUm = max(diffObj, sampObj),
            depthOfFieldMm = dof,
            equivalentFocalMm = equivalentFocal(i.focalMm, i.sensorWidthMm, i.sensorHeightMm),
        )
    }
}
