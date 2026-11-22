package io.github.ultrascope.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * Prueft Focus-Stacking und Mosaik mit synthetischen Bildern bekannter Geometrie
 * (Desktop-OpenCV, gleiche Java-API wie auf Android).
 */
class ProcessingTest {

    companion object {
        @BeforeClass @JvmStatic fun load() { nu.pattern.OpenCV.loadLocally() }

        /** Feine, zufaellige Textur (aehnlich Holz/Papierfasern unter Vergroesserung). */
        fun texture(w: Int, h: Int, seed: Long): Mat {
            Core.setRNGSeed(seed.toInt())
            val noise = Mat(h, w, CvType.CV_8UC3)
            Core.randu(noise, 0.0, 255.0)
            Imgproc.GaussianBlur(noise, noise, Size(0.0, 0.0), 1.2)
            val coarse = Mat(h / 8, w / 8, CvType.CV_8UC3)
            Core.randu(coarse, 0.0, 255.0)
            val up = Mat(); Imgproc.resize(coarse, up, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            val out = Mat(); Core.addWeighted(noise, 0.5, up, 0.5, 0.0, out)
            return out
        }

        fun sharpness(m: Mat): Double {
            val g = Mat(); Imgproc.cvtColor(m, g, Imgproc.COLOR_BGR2GRAY)
            val l = Mat(); Imgproc.Laplacian(g, l, CvType.CV_64F)
            val mean = MatOfDouble(); val sd = MatOfDouble()
            Core.meanStdDev(l, mean, sd)
            return sd.get(0, 0)[0]
        }

        fun meanAbsDiff(a: Mat, b: Mat): Double {
            val d = Mat(); Core.absdiff(a, b, d)
            val s = Core.mean(d)
            return (s.`val`[0] + s.`val`[1] + s.`val`[2]) / 3
        }
    }

    private val tmp: File = Files.createTempDirectory("ultrascope").toFile()

    private fun save(m: Mat, name: String): File = File(tmp, name).also { Imgcodecs.imwrite(it.absolutePath, m) }

    @Test fun phaseCorrelateSignConvention() {
        val j = Mat(); texture(400, 300, 1).let { Imgproc.cvtColor(it, j, Imgproc.COLOR_BGR2GRAY) }
        val jf = Mat(); j.convertTo(jf, CvType.CV_32F)
        // i = j um (+10, +5) verschoben (Inhalt wandert nach rechts/unten)
        val t = Mat(2, 3, CvType.CV_64F); t.put(0, 0, 1.0, 0.0, 10.0, 0.0, 1.0, 5.0)
        val i = Mat(); Imgproc.warpAffine(jf, i, t, jf.size())
        val d = Imgproc.phaseCorrelate(jf, i)
        assertEquals(10.0, d.x, 0.3); assertEquals(5.0, d.y, 0.3)
    }

    @Test fun focusStackRecoversSharpRegions() {
        val w = 900; val h = 600
        val truth = texture(w, h, 7)
        val blurred = Mat(); Imgproc.GaussianBlur(truth, blurred, Size(0.0, 0.0), 5.0)
        val files = (0 until 3).map { k ->
            // Bild k ist im k-ten Drittel scharf, sonst unscharf
            val f = blurred.clone()
            val roi = Rect(k * w / 3, 0, w / 3, h)
            truth.submat(roi).copyTo(f.submat(roi))
            // Fokusatmung + Verwacklung simulieren (nicht fuer das Referenzbild k=1)
            val out = Mat()
            if (k == 1) f.copyTo(out) else {
                val s = if (k == 0) 1.012 else 0.988
                val m = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), 0.0, s)
                m.put(0, 2, m.get(0, 2)[0] + (if (k == 0) 3.0 else -2.0))
                m.put(1, 2, m.get(1, 2)[0] + (if (k == 0) -2.0 else 2.5))
                Imgproc.warpAffine(f, out, m, f.size(), Imgproc.INTER_LINEAR, Core.BORDER_REFLECT)
            }
            save(out, "stack_$k.png")
        }
        val res = FocusStacker(alignWidth = 900).stack(files)
        assertEquals(w, res.image.cols()); assertEquals(h, res.image.rows())
        res.alignmentScores.forEach { assertTrue("ECC fehlgeschlagen: ${res.alignmentScores}", it > 0.5) }
        val margin = 40
        for (k in 0 until 3) {
            val roi = Rect(k * w / 3 + margin, margin, w / 3 - 2 * margin, h - 2 * margin)
            val sRes = sharpness(res.image.submat(roi))
            val sTruth = sharpness(truth.submat(roi))
            val sBlur = sharpness(blurred.submat(roi))
            assertTrue("Drittel $k: Ergebnis $sRes, Original $sTruth, unscharf $sBlur", sRes > 0.75 * sTruth && sRes > 3 * sBlur)
        }
        val inner = Rect(margin, margin, w - 2 * margin, h - 2 * margin)
        val mad = meanAbsDiff(res.image.submat(inner), truth.submat(inner))
        println("Focus-Stack: mittlere Abweichung zum scharfen Original $mad, ECC ${res.alignmentScores}")
        assertTrue("Mittlere Abweichung zum Original zu gross: $mad", mad < 12.0)
        res.release()
    }

    /** Translation: exakte Rekonstruktion erwartet. Similarity (Freihand) schaetzt Drehung/Skalierung
     *  mit und ist daher messbar ungenauer – eigene, dokumentierte Schwelle. */
    private fun mosaicTest(model: PanoramaStitcher.Model, maxMad: Double) {
        val big = texture(1600, 1200, 42)
        val tw = 800; val th = 600
        // Schlangenreihenfolge 2×2, 25 % Ueberlappung
        val offsets = listOf(Point(0.0, 0.0), Point(600.0, 0.0), Point(600.0, 450.0), Point(0.0, 450.0))
        val files = offsets.mapIndexed { i, o ->
            save(big.submat(Rect(o.x.toInt(), o.y.toInt(), tw, th)).clone(), "tile_$i.png")
        }
        val res = PanoramaStitcher(model = model, workWidth = 800).stitch(files)
        assertTrue("Breite ${res.image.cols()}", abs(res.image.cols() - 1400) <= 3)
        assertTrue("Hoehe ${res.image.rows()}", abs(res.image.rows() - 1050) <= 3)
        assertEquals(1.0, res.outputScale, 1e-9)
        val cw = minOf(res.image.cols(), 1400) - 20; val ch = minOf(res.image.rows(), 1050) - 20
        val mad = meanAbsDiff(res.image.submat(Rect(10, 10, cw, ch)), big.submat(Rect(10, 10, cw, ch)))
        println("Mosaik ${model.name}: ${res.image.cols()}x${res.image.rows()}, mittlere Abweichung $mad")
        assertTrue("Mosaik weicht vom Original ab: $mad (${res.log})", mad < maxMad)
        res.release()
    }

    @Test fun mosaicTranslationReconstructsOriginal() = mosaicTest(PanoramaStitcher.Model.TRANSLATION, 3.0)

    @Test fun mosaicSimilarityReconstructsOriginal() = mosaicTest(PanoramaStitcher.Model.SIMILARITY, 8.0)

    @Test fun mosaicDownscalesWhenOverLimit() {
        val big = texture(1200, 600, 3)
        val files = listOf(
            save(big.submat(Rect(0, 0, 700, 600)).clone(), "a.png"),
            save(big.submat(Rect(500, 0, 700, 600)).clone(), "b.png"),
        )
        val res = PanoramaStitcher(maxOutputPixels = 300_000L, workWidth = 700).stitch(files)
        assertTrue(res.outputScale < 1.0)
        assertTrue(res.image.cols().toLong() * res.image.rows() <= 310_000L)
        res.release()
    }
}
