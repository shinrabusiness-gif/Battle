package io.github.ultrascope.processing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File

/**
 * Focus-Stacking fuer eine Fokusreihe (Bilder nach Fokusdistanz sortiert).
 *
 * 1. Registrierung: ECC (Enhanced Correlation Coefficient) mit affinem Modell auf verkleinerten
 *    Graubildern. Affin ist noetig, weil sich beim Fokussieren die Vergroesserung aendert
 *    ("Focus Breathing"). Benachbarte Bilder werden paarweise registriert und zur Referenz
 *    (mittleres Bild) verkettet – benachbarte Fokusebenen aehneln sich am meisten.
 * 2. Schaerfemass: |Laplace|² des leicht entrauschten Graubildes, raeumlich geglaettet.
 * 3. Pass 1: pro Pixel Index des schaerfsten Bildes (Tiefenkarte), Median-gefiltert gegen Ausreisser.
 * 4. Pass 2: Komposition aus den Originalpixeln (keine Interpolation ausser der Registrierung).
 *
 * Speicherbedarf ist unabhaengig von der Bildanzahl (Streaming, jeweils ein Vollbild im Speicher).
 * Massstab des Ergebnisses = Massstab des Referenzbildes (Index [Result.referenceIndex]).
 */
class FocusStacker(
    private val alignWidth: Int = 1200,
    private val eccIterations: Int = 60,
) {
    class Result(
        val image: Mat,
        val depthColor: Mat,
        val referenceIndex: Int,
        val alignmentScores: List<Double>,
        val log: List<String>,
    ) {
        fun release() { image.release(); depthColor.release() }
    }

    fun stack(files: List<File>, progress: Progress = Progress { _, _ -> }): Result {
        require(files.size >= 2) { "Mindestens 2 Bilder fuer Focus-Stacking" }
        require(files.size <= 255) { "Hoechstens 255 Bilder" }
        val n = files.size
        val log = mutableListOf<String>()
        val ref = n / 2

        // ---------- Registrierung
        progress.update(0f, "Lade Vorschaubilder …")
        val first = CvUtil.readColor(files[ref])
        val fullSize = first.size()
        first.release()
        val small = files.mapIndexed { i, f ->
            progress.update(0.15f * i / n, "Lade ${i + 1}/$n")
            CvUtil.readGraySmall(f, alignWidth)
        }
        val s = fullSize.width / small[ref].cols()
        val warps = arrayOfNulls<Mat>(n)
        val scores = MutableList(n) { 1.0 }
        warps[ref] = Mat.eye(3, 3, CvType.CV_64F)
        var done = 0
        for (i in ref - 1 downTo 0) {
            val (w, sc) = ecc(small[i + 1], small[i])
            warps[i] = CvUtil.mul(w, warps[i + 1]!!); scores[i] = sc
            progress.update(0.15f + 0.25f * (++done) / (n - 1), "Registriere ${i + 1}/$n")
        }
        for (i in ref + 1 until n) {
            val (w, sc) = ecc(small[i - 1], small[i])
            warps[i] = CvUtil.mul(w, warps[i - 1]!!); scores[i] = sc
            progress.update(0.15f + 0.25f * (++done) / (n - 1), "Registriere ${i + 1}/$n")
        }
        small.forEach { it.release() }
        // Kleine Koordinaten -> volle Aufloesung: nur die Translation skaliert mit s
        val fullWarps = warps.map { w ->
            val a = CvUtil.to2x3(w!!)
            a.put(0, 2, a.get(0, 2)[0] * s)
            a.put(1, 2, a.get(1, 2)[0] * s)
            a
        }
        scores.forEachIndexed { i, sc -> if (sc < 0) log += "Bild ${i + 1}: Registrierung fehlgeschlagen, unausgerichtet verwendet" }

        // ---------- Pass 1: Tiefenkarte
        val h = fullSize.height.toInt(); val wdt = fullSize.width.toInt()
        val best = Mat(h, wdt, CvType.CV_32F, Scalar(-1.0))
        val index = Mat(h, wdt, CvType.CV_8U, Scalar(ref.toDouble()))
        val blurK = CvUtil.odd(wdt / 150, 5, 61)
        for (i in 0 until n) {
            progress.update(0.4f + 0.3f * i / n, "Schaerfeanalyse ${i + 1}/$n")
            val aligned = loadAligned(files[i], fullWarps[i], fullSize, i == ref)
            val measure = focusMeasure(aligned, blurK)
            aligned.release()
            val mask = Mat()
            Core.compare(measure, best, mask, Core.CMP_GT)
            measure.copyTo(best, mask)
            index.setTo(Scalar(i.toDouble()), mask)
            mask.release(); measure.release()
        }
        best.release()
        val idxFiltered = Mat()
        Imgproc.medianBlur(index, idxFiltered, CvUtil.odd(wdt / 300, 3, 15))
        index.release()

        // ---------- Pass 2: Komposition
        val out = Mat(h, wdt, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        val mask = Mat()
        for (i in 0 until n) {
            progress.update(0.7f + 0.28f * i / n, "Komposition ${i + 1}/$n")
            Core.inRange(idxFiltered, Scalar(i.toDouble()), Scalar(i.toDouble()), mask)
            if (Core.countNonZero(mask) == 0) continue
            val aligned = loadAligned(files[i], fullWarps[i], fullSize, i == ref)
            aligned.copyTo(out, mask)
            aligned.release()
        }
        mask.release()

        // Tiefenkarte (nah = hell) farbcodiert
        val depth8 = Mat()
        idxFiltered.convertTo(depth8, CvType.CV_8U, if (n > 1) 255.0 / (n - 1) else 0.0)
        idxFiltered.release()
        val depthColor = Mat()
        Imgproc.applyColorMap(depth8, depthColor, Imgproc.COLORMAP_VIRIDIS)
        depth8.release()
        fullWarps.forEach { it.release() }
        warps.forEach { it?.release() }
        progress.update(1f, "Fertig")
        log += "Referenzbild: ${ref + 1}/$n, Registrierungsguete (ECC): " + scores.joinToString { String.format("%.3f", it) }
        return Result(out, depthColor, ref, scores, log)
    }

    private fun loadAligned(f: File, warp: Mat, size: Size, isRef: Boolean): Mat {
        val img = CvUtil.readColor(f)
        if (isRef && img.size() == size) return img
        val out = Mat()
        Imgproc.warpAffine(img, out, warp, size, Imgproc.INTER_LANCZOS4 + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
        img.release()
        return out
    }

    /** Lokales Schaerfemass: geglaettetes Quadrat des Laplace-Operators. */
    private fun focusMeasure(color: Mat, blurK: Int): Mat {
        val gray = Mat()
        Imgproc.cvtColor(color, gray, Imgproc.COLOR_BGR2GRAY)
        val g32 = Mat()
        gray.convertTo(g32, CvType.CV_32F)
        gray.release()
        Imgproc.GaussianBlur(g32, g32, Size(3.0, 3.0), 0.0)
        val lap = Mat()
        Imgproc.Laplacian(g32, lap, CvType.CV_32F, 3)
        g32.release()
        Core.multiply(lap, lap, lap)
        Imgproc.GaussianBlur(lap, lap, Size(blurK.toDouble(), blurK.toDouble()), 0.0)
        return lap
    }

    /**
     * Liefert 3×3-Matrix W, so dass input(W·x) ≈ template(x), sowie den ECC-Wert (-1 bei Fehler).
     */
    private fun ecc(template: Mat, input: Mat): Pair<Mat, Double> {
        val warp = Mat.eye(2, 3, CvType.CV_32F)
        return try {
            val crit = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, eccIterations, 1e-5)
            val cc = Video.findTransformECC(template, input, warp, Video.MOTION_AFFINE, crit, Mat(), 5)
            Pair(CvUtil.to3x3(warp), cc)
        } catch (e: Exception) {
            Pair(Mat.eye(3, 3, CvType.CV_64F), -1.0)
        } finally {
            warp.release()
        }
    }
}
