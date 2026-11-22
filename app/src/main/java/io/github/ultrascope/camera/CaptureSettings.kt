package io.github.ultrascope.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.RggbChannelVector

/**
 * Vom UI veraenderbare Aufnahmeparameter. Werden bei jedem Repeating-Request und jeder
 * Einzelaufnahme vollstaendig in den CaptureRequest geschrieben.
 */
class CaptureSettings {
    @Volatile var zoom: Float = 1f

    @Volatile var manualFocus: Boolean = false
    /** Fokus in Dioptrien (0 = unendlich, LENS_INFO_MINIMUM_FOCUS_DISTANCE = naechster Punkt). */
    @Volatile var focusDiopters: Float = 0f

    @Volatile var manualExposure: Boolean = false
    @Volatile var exposureNs: Long = 10_000_000L
    @Volatile var iso: Int = 100
    @Volatile var evIndex: Int = 0
    @Volatile var aeLock: Boolean = false

    @Volatile var awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    @Volatile var awbLock: Boolean = false
    /** Wenn gesetzt: AWB aus, diese Kanalverstaerkungen (mit [wbShift] verschoben) werden verwendet. */
    @Volatile var manualWbGains: RggbChannelVector? = null
    /** -1 … +1: verschiebt manuelle Gains Richtung blau (-) bzw. rot/warm (+). */
    @Volatile var wbShift: Float = 0f

    @Volatile var torch: Boolean = false
    /** Rauschunterdrueckung und Nachschaerfung aus (fuer messtechnisch "ehrliche" Bilder). */
    @Volatile var minimalProcessing: Boolean = false
    @Volatile var ois: Boolean = true

    fun shiftedGains(): RggbChannelVector? {
        val g = manualWbGains ?: return null
        val k = Math.pow(2.0, wbShift.toDouble()).toFloat() // bis Faktor 2 je Richtung
        return RggbChannelVector(g.red * k, g.greenEven, g.greenOdd, g.blue / k)
    }

    fun copyFrom(o: CaptureSettings) {
        zoom = o.zoom; manualFocus = o.manualFocus; focusDiopters = o.focusDiopters
        manualExposure = o.manualExposure; exposureNs = o.exposureNs; iso = o.iso; evIndex = o.evIndex
        aeLock = o.aeLock; awbMode = o.awbMode; awbLock = o.awbLock; manualWbGains = o.manualWbGains
        wbShift = o.wbShift; torch = o.torch; minimalProcessing = o.minimalProcessing; ois = o.ois
    }
}
