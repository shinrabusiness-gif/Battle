package io.github.ultrascope.processing

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Fortschrittsmeldung 0..1 mit Klartext. */
fun interface Progress {
    fun update(fraction: Float, message: String)
}

internal object CvUtil {

    fun readColor(f: File): Mat {
        val m = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_COLOR)
        require(!m.empty()) { "Bild konnte nicht gelesen werden: ${f.name}" }
        return m
    }

    /** Graustufenbild, verkleinert auf hoechstens [maxWidth] Pixel Breite, als CV_32F 0..1. */
    fun readGraySmall(f: File, maxWidth: Int): Mat {
        val reduced = Imgcodecs.imread(f.absolutePath, Imgcodecs.IMREAD_REDUCED_GRAYSCALE_2)
        require(!reduced.empty()) { "Bild konnte nicht gelesen werden: ${f.name}" }
        val g = if (reduced.cols() > maxWidth) {
            val s = maxWidth.toDouble() / reduced.cols()
            Mat().also { Imgproc.resize(reduced, it, Size(), s, s, Imgproc.INTER_AREA); reduced.release() }
        } else reduced
        val f32 = Mat()
        g.convertTo(f32, CvType.CV_32F, 1.0 / 255.0)
        g.release()
        return f32
    }

    /** 3×3 (CV_64F) aus 2×3-Affinmatrix. */
    fun to3x3(a: Mat): Mat {
        val m = Mat.eye(3, 3, CvType.CV_64F)
        val a64 = Mat()
        a.convertTo(a64, CvType.CV_64F)
        for (r in 0 until 2) for (c in 0 until 3) m.put(r, c, a64.get(r, c)[0])
        a64.release()
        return m
    }

    fun to2x3(m: Mat): Mat {
        val a = Mat(2, 3, CvType.CV_64F)
        for (r in 0 until 2) for (c in 0 until 3) a.put(r, c, m.get(r, c)[0])
        return a
    }

    fun mul(a: Mat, b: Mat): Mat {
        val out = Mat()
        Core.gemm(a, b, 1.0, Mat(), 0.0, out)
        return out
    }

    fun scaleMat(s: Double): Mat {
        val m = Mat.eye(3, 3, CvType.CV_64F)
        m.put(0, 0, s); m.put(1, 1, s)
        return m
    }

    fun translateMat(tx: Double, ty: Double): Mat {
        val m = Mat.eye(3, 3, CvType.CV_64F)
        m.put(0, 2, tx); m.put(1, 2, ty)
        return m
    }

    fun odd(v: Int, min: Int = 3, max: Int = 101): Int {
        var k = v.coerceIn(min, max)
        if (k % 2 == 0) k += 1
        return k
    }

    fun write(mat: Mat, out: File, pngCompression: Int = 3, jpegQuality: Int = 98) {
        val params = if (out.name.endsWith(".png", true))
            org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, pngCompression)
        else org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality)
        check(Imgcodecs.imwrite(out.absolutePath, mat, params)) { "Schreiben fehlgeschlagen: ${out.name}" }
    }
}
