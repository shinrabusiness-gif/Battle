package io.github.ultrascope.processing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.calib3d.Calib3d
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mosaik-/Panorama-Stitching fuer Makro-Scans einer (annaehernd) ebenen Probe.
 *
 * Annahme: Kamera bzw. Objekt wird parallel zur Probenebene verschoben, Fokus und Zoom bleiben
 * fix. Dann ist das korrekte Modell eine reine Verschiebung (TRANSLATION) – der Massstab bleibt
 * exakt erhalten und die Messfunktion gilt auch fuer das Mosaik. SIMILARITY erlaubt zusaetzlich
 * Drehung/Skalierung (z. B. Freihand), veraendert aber den Massstab leicht.
 *
 * Ablauf: ORB-Merkmale → Abgleich jeder neuen Kachel mit allen vorherigen (beste Inlier-Zahl) →
 * RANSAC → globale Verkettung → Feathering-Ueberblendung (Zeltgewichte, normalisiert) in einen
 * 16-Bit-Akkumulator. Eine Kachel nach der anderen ist im Speicher.
 */
class PanoramaStitcher(
    private val model: Model = Model.TRANSLATION,
    private val workWidth: Int = 1200,
    private val maxOutputPixels: Long = 60_000_000L,
    private val minInliers: Int = 15,
) {
    enum class Model { TRANSLATION, SIMILARITY }

    class Result(
        val image: Mat,
        /** Ausgabepixel pro Eingabepixel (< 1, wenn wegen [maxOutputPixels] verkleinert wurde). */
        val outputScale: Double,
        val log: List<String>,
    ) {
        fun release() = image.release()
    }

    class StitchException(msg: String) : Exception(msg)

    private class Features(val kp: MatOfKeyPoint, val desc: Mat, val gray: Mat)

    fun stitch(files: List<File>, progress: Progress = Progress { _, _ -> }): Result {
        require(files.size >= 2) { "Mindestens 2 Kacheln" }
        val n = files.size
        val log = mutableListOf<String>()

        // ---------- Merkmale auf verkleinerten Bildern
        val firstFull = CvUtil.readColor(files[0])
        val tileSize = firstFull.size()
        firstFull.release()
        val orb = ORB.create(4000)
        val feats = files.mapIndexed { i, f ->
            progress.update(0.2f * i / n, "Merkmale ${i + 1}/$n")
            val g32 = CvUtil.readGraySmall(f, workWidth)
            val g8 = Mat(); g32.convertTo(g8, CvType.CV_8U, 255.0)
            val kp = MatOfKeyPoint(); val desc = Mat()
            orb.detectAndCompute(g8, Mat(), kp, desc)
            Features(kp, desc, g32).also { g8.release() }
        }
        val s = tileSize.width / feats[0].gray.cols()

        // ---------- Paarweise Registrierung, globale Verkettung (Kachel -> Kachel-0-Koordinaten)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val global = arrayOfNulls<Mat>(n)
        global[0] = Mat.eye(3, 3, CvType.CV_64F)
        for (i in 1 until n) {
            progress.update(0.2f + 0.3f * i / n, "Registriere Kachel ${i + 1}/$n")
            var bestM: Mat? = null; var bestJ = -1; var bestInl = 0
            for (j in 0 until i) {
                val r = register(matcher, feats[i], feats[j]) ?: continue
                if (r.second > bestInl) { bestM?.release(); bestM = r.first; bestJ = j; bestInl = r.second } else r.first.release()
            }
            if (bestM == null || bestInl < minInliers) {
                // Rueckfall: Phasenkorrelation zur vorherigen Kachel (gleich grosse Bilder)
                val pc = phaseCorr(feats[i - 1].gray, feats[i].gray)
                if (pc == null) {
                    feats.forEach { it.kp.release(); it.desc.release(); it.gray.release() }
                    throw StitchException("Kachel ${i + 1} konnte nicht ausgerichtet werden – mehr Ueberlappung (≥30 %) und Struktur noetig.")
                }
                bestM?.release()
                bestM = pc; bestJ = i - 1
                log += "Kachel ${i + 1}: Phasenkorrelation zu Kachel $i"
            } else {
                log += "Kachel ${i + 1}: an Kachel ${bestJ + 1} ausgerichtet ($bestInl Inlier)"
            }
            // kleine -> volle Koordinaten
            val full = bestM!!.clone()
            full.put(0, 2, full.get(0, 2)[0] * s); full.put(1, 2, full.get(1, 2)[0] * s)
            global[i] = CvUtil.mul(global[bestJ]!!, full)
            full.release(); bestM.release()
        }
        feats.forEach { it.kp.release(); it.desc.release(); it.gray.release() }

        // ---------- Leinwand
        val tw = tileSize.width; val th = tileSize.height
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (g in global) for (p in corners(tw, th)) {
            val q = apply(g!!, p); minX = min(minX, q.x); minY = min(minY, q.y); maxX = max(maxX, q.x); maxY = max(maxY, q.y)
        }
        val rawW = maxX - minX; val rawH = maxY - minY
        val k = min(1.0, sqrt(maxOutputPixels / (rawW * rawH)))
        if (k < 1.0) log += String.format("Ausgabe auf %.1f %% verkleinert (Limit %d MP)", k * 100, maxOutputPixels / 1_000_000)
        val toCanvas = CvUtil.mul(CvUtil.scaleMat(k), CvUtil.translateMat(-minX, -minY))
        val cw = ceil(rawW * k).toInt().coerceAtLeast(1); val chh = ceil(rawH * k).toInt().coerceAtLeast(1)
        val placed = global.map { CvUtil.mul(toCanvas, it!!) }

        // ---------- Pass 1: Gewichtssumme in niedriger Aufloesung
        val lowF = 8.0
        val lowSize = Size(ceil(cw / lowF), ceil(chh / lowF))
        val tileLowW = max(2, (tw / lowF).toInt()); val tileLowH = max(2, (th / lowF).toInt())
        val tent = tentWeight(tileLowW, tileLowH)
        val lowMaps = placed.map { P ->
            // Kachel-Low-Koordinaten -> Leinwand-Low-Koordinaten
            val a = CvUtil.mul(CvUtil.mul(CvUtil.scaleMat(1.0 / lowF), P), CvUtil.scaleMat(tw / tileLowW))
            CvUtil.to2x3(a)
        }
        val wsum = Mat(lowSize, CvType.CV_32F, Scalar(1e-6))
        val tmp = Mat()
        for (m in lowMaps) {
            Imgproc.warpAffine(tent, tmp, m, lowSize, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0))
            Core.add(wsum, tmp, wsum)
        }

        // ---------- Pass 2: Ueberblenden in 16-Bit-Akkumulator (Wert × 128)
        val acc = Mat(chh, cw, CvType.CV_16UC3, Scalar(0.0, 0.0, 0.0))
        val ones = Mat(th.toInt(), tw.toInt(), CvType.CV_8U, Scalar(255.0))
        for (i in 0 until n) {
            progress.update(0.5f + 0.48f * i / n, "Ueberblende Kachel ${i + 1}/$n")
            val P = placed[i]
            val roi = bbox(P, tw, th, cw, chh) ?: continue
            val local = CvUtil.to2x3(CvUtil.mul(CvUtil.translateMat(-roi.x.toDouble(), -roi.y.toDouble()), P))
            val img = CvUtil.readColor(files[i])
            val warped = Mat(); Imgproc.warpAffine(img, warped, local, roi.size(), Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
            img.release()
            val mask = Mat(); Imgproc.warpAffine(ones, mask, local, roi.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))

            // normiertes Gewicht dieser Kachel in Low-Res, dann auf ROI hochskalieren
            Imgproc.warpAffine(tent, tmp, lowMaps[i], lowSize, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0))
            val wn = Mat(); Core.divide(tmp, wsum, wn)
            val lx = floor(roi.x / lowF).toInt(); val ly = floor(roi.y / lowF).toInt()
            val lx2 = min(lowSize.width.toInt(), ceil((roi.x + roi.width) / lowF).toInt())
            val ly2 = min(lowSize.height.toInt(), ceil((roi.y + roi.height) / lowF).toInt())
            val lowRoi = wn.submat(Rect(lx, ly, max(1, lx2 - lx), max(1, ly2 - ly)))
            val wLarge = Mat()
            Imgproc.resize(lowRoi, wLarge, Size((lx2 - lx) * lowF, (ly2 - ly) * lowF), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val offX = roi.x - lx * lowF.toInt(); val offY = roi.y - ly * lowF.toInt()
            val wRoi = wLarge.submat(Rect(offX, offY, min(roi.width, wLarge.cols() - offX), min(roi.height, wLarge.rows() - offY)))
            val w = Mat(roi.size(), CvType.CV_32F, Scalar(0.0))
            wRoi.copyTo(w.submat(Rect(0, 0, wRoi.cols(), wRoi.rows())))
            // ausserhalb der Kachel Gewicht 0, x128 Festkomma
            val maskF = Mat(); mask.convertTo(maskF, CvType.CV_32F, 128.0 / 255.0)
            Core.multiply(w, maskF, w)
            val w3 = Mat(); Core.merge(listOf(w, w, w), w3)
            val wf = Mat(); warped.convertTo(wf, CvType.CV_32FC3)
            Core.multiply(wf, w3, wf)
            val w16 = Mat(); wf.convertTo(w16, CvType.CV_16UC3)
            val accRoi = acc.submat(roi)
            Core.add(accRoi, w16, accRoi)
            listOf(warped, mask, wn, wLarge, w, maskF, w3, wf, w16, local).forEach { it.release() }
        }
        ones.release(); tmp.release(); wsum.release(); tent.release()
        val out = Mat()
        acc.convertTo(out, CvType.CV_8UC3, 1.0 / 128.0)
        acc.release()
        progress.update(1f, "Fertig")
        log += "Mosaik: ${out.cols()} × ${out.rows()} px aus $n Kacheln (${model.name})"
        return Result(out, k, log)
    }

    /** Liefert (3×3-Matrix Kachel-a -> Kachel-b, Inlier-Anzahl) oder null. */
    private fun register(matcher: DescriptorMatcher, a: Features, b: Features): Pair<Mat, Int>? {
        if (a.desc.empty() || b.desc.empty() || a.desc.rows() < 8 || b.desc.rows() < 8) return null
        val knn = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(a.desc, b.desc, knn, 2)
        val good = mutableListOf<DMatch>()
        for (m in knn) {
            val arr = m.toArray()
            if (arr.size == 2 && arr[0].distance < 0.75f * arr[1].distance) good += arr[0]
            m.release()
        }
        if (good.size < minInliers) return null
        val ka = a.kp.toArray(); val kb = b.kp.toArray()
        val src = MatOfPoint2f(*good.map { ka[it.queryIdx].pt }.toTypedArray())
        val dst = MatOfPoint2f(*good.map { kb[it.trainIdx].pt }.toTypedArray())
        val inl = Mat()
        val aff = Calib3d.estimateAffinePartial2D(src, dst, inl, Calib3d.RANSAC, 3.0)
        if (aff.empty()) { src.release(); dst.release(); inl.release(); return null }
        val sp = src.toArray(); val dp = dst.toArray()
        val inIdx = (0 until inl.rows()).filter { inl.get(it, 0)[0] != 0.0 }
        val result: Mat = if (model == Model.TRANSLATION) {
            val dx = inIdx.map { dp[it].x - sp[it].x }.sorted()
            val dy = inIdx.map { dp[it].y - sp[it].y }.sorted()
            if (dx.isEmpty()) { aff.release(); src.release(); dst.release(); inl.release(); return null }
            CvUtil.translateMat(dx[dx.size / 2], dy[dy.size / 2])
        } else CvUtil.to3x3(aff)
        aff.release(); src.release(); dst.release(); inl.release()
        return Pair(result, inIdx.size)
    }

    /** Phasenkorrelation: Matrix Kachel-i -> Kachel-j oder null bei schwacher Antwort. */
    private fun phaseCorr(j: Mat, i: Mat): Mat? {
        if (j.size() != i.size()) return null
        val win = Mat()
        Imgproc.createHanningWindow(win, j.size(), CvType.CV_32F)
        val resp = DoubleArray(1)
        val d = Imgproc.phaseCorrelate(j, i, win, resp)
        win.release()
        if (resp[0] < 0.05) return null
        // i(x) ≈ j(x - d)  =>  Punkt p in i entspricht p - d in j
        return CvUtil.translateMat(-d.x, -d.y)
    }

    private fun tentWeight(w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_32F)
        val row = FloatArray(w)
        for (y in 0 until h) {
            val wy = 1.0 - kotlin.math.abs(2.0 * (y + 0.5) / h - 1.0)
            for (x in 0 until w) {
                val wx = 1.0 - kotlin.math.abs(2.0 * (x + 0.5) / w - 1.0)
                row[x] = (wx * wy).toFloat() + 1e-4f
            }
            m.put(y, 0, row)
        }
        return m
    }

    private fun corners(w: Double, h: Double) = listOf(Point(0.0, 0.0), Point(w, 0.0), Point(0.0, h), Point(w, h))

    private fun apply(m: Mat, p: Point): Point {
        val x = m.get(0, 0)[0] * p.x + m.get(0, 1)[0] * p.y + m.get(0, 2)[0]
        val y = m.get(1, 0)[0] * p.x + m.get(1, 1)[0] * p.y + m.get(1, 2)[0]
        return Point(x, y)
    }

    private fun bbox(m: Mat, w: Double, h: Double, cw: Int, ch: Int): Rect? {
        val pts = corners(w, h).map { apply(m, it) }
        val x0 = floor(pts.minOf { it.x }).toInt().coerceIn(0, cw)
        val y0 = floor(pts.minOf { it.y }).toInt().coerceIn(0, ch)
        val x1 = ceil(pts.maxOf { it.x }).toInt().coerceIn(0, cw)
        val y1 = ceil(pts.maxOf { it.y }).toInt().coerceIn(0, ch)
        return if (x1 - x0 < 1 || y1 - y0 < 1) null else Rect(x0, y0, x1 - x0, y1 - y0)
    }
}
