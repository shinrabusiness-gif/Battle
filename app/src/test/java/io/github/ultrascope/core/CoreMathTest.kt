package io.github.ultrascope.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreMathTest {

    @Test fun niceFloorFollows125Series() {
        assertEquals(1.0, ScaleMath.niceFloor(1.9), 1e-9)
        assertEquals(2.0, ScaleMath.niceFloor(4.99), 1e-9)
        assertEquals(5.0, ScaleMath.niceFloor(9.9), 1e-9)
        assertEquals(500.0, ScaleMath.niceFloor(731.0), 1e-9)
        assertEquals(0.02, ScaleMath.niceFloor(0.0333), 1e-12)
    }

    @Test fun formatLengthPicksUnits() {
        assertEquals("12,5 µm", ScaleMath.formatLength(12.5))
        assertEquals("1,500 mm", ScaleMath.formatLength(1500.0))
        assertEquals("250 nm", ScaleMath.formatLength(0.25))
    }

    @Test fun calibrationScalesWithZoomAndWidth() {
        // 1 mm Referenz ueber 100 px in einem 4000 px breiten Bild bei Zoom 2
        val c = Calibration.create("0", 1000.0, 100.0, 4000, 2f, 10f, 0L)
        assertEquals(10.0, c.umPerPx(4000, 2f), 1e-9)   // wie kalibriert
        assertEquals(20.0, c.umPerPx(4000, 1f), 1e-9)   // halber Zoom -> doppelte µm/px
        assertEquals(40.0, c.umPerPx(2000, 1f), 1e-9)   // halbe Bildbreite
    }

    @Test fun calibrationFocusDeviation() {
        val c = Calibration.create("0", 1000.0, 100.0, 4000, 1f, 10f, 0L)
        assertEquals(0.0, c.focusDeviation(10f), 1e-9)
        assertEquals(0.01, c.focusDeviation(10.1f), 1e-4)
        assertTrue(c.focusDeviation(11f) > Calibration.FOCUS_TOLERANCE)
        val af = Calibration.create("0", 1000.0, 100.0, 4000, 1f, null, 0L)
        assertEquals(0.0, af.focusDeviation(3f), 1e-9)
    }

    @Test fun bestCalibrationIsNearestFocus() {
        val a = Calibration.create("0", 1000.0, 100.0, 4000, 1f, 5f, 0L)
        val b = Calibration.create("0", 1000.0, 200.0, 4000, 1f, 10f, 0L)
        val other = Calibration.create("3", 1000.0, 300.0, 4000, 1f, 9.9f, 0L)
        assertEquals(b, Calibration.best(listOf(a, b, other), "0", 9.5f))
        assertEquals(a, Calibration.best(listOf(a, b, other), "0", 5.5f))
        assertNull(Calibration.best(listOf(a, b), "7", 5f))
    }

    @Test fun bestPrefersSameFocusKindAndLens() {
        val af = Calibration.create("0", 1000.0, 100.0, 4000, 1f, null, 0L)
        val mf = Calibration.create("0", 1000.0, 150.0, 4000, 1f, 8f, 0L)
        assertEquals(mf, Calibration.best(listOf(af, mf), "0", 9f))   // MF-Modus -> MF-Kalibrierung
        assertEquals(af, Calibration.best(listOf(af, mf), "0", null)) // AF-Modus -> AF-Kalibrierung
        val wide = Calibration.create("0", 1000.0, 100.0, 4000, 1f, null, 0L, activePhysicalId = "2")
        val tele = Calibration.create("0", 1000.0, 400.0, 4000, 4f, null, 0L, activePhysicalId = "4")
        assertEquals(tele, Calibration.best(listOf(wide, tele), "0", null, "4"))
        assertEquals(wide, Calibration.best(listOf(wide, tele), "0", null, "2"))
    }

    @Test fun opticsThinLensSanity() {
        // f = 10 mm, Objekt in 110 mm -> m = 10/100 = 0,1
        val e = Optics.estimate(Optics.Input(10.0, 2.0, 6.0, 4.5, 6000, 3000, 1000.0 / 110.0))
        assertNotNull(e); e!!
        assertEquals(0.1, e.magnification, 1e-9)
        assertEquals(60.0, e.fovWidthMm, 1e-6)
        assertEquals(10.0, e.umPerSensorPixel, 1e-6)       // 1 µm Pixel / 0,1
        assertEquals(20.0, e.umPerOutputPixel, 1e-6)       // 2 µm Ausgabe-Pixel / 0,1
        assertEquals(2.2, e.workingFNumber, 1e-9)
        assertEquals(1.22 * 0.55 * 2.2 / 0.1, e.diffractionLimitObjectUm, 1e-6)
        assertTrue(e.resolvableObjectUm >= e.diffractionLimitObjectUm)
        assertNull(Optics.estimate(Optics.Input(10.0, 2.0, 6.0, 4.5, 6000, 3000, 0.0)))
    }
}
