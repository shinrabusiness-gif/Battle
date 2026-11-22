package io.github.ultrascope.camera

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wertet den kleinen YUV-Analyse-Stream aus (nur Luminanz):
 *  - Schaerfemass = RMS des Laplace-Operators im zentralen Bildbereich (relativ, einheitenlos),
 *  - Fokus-Peaking-Maske = staerkste Kanten (oberste ~3 % der Gradienten) als farbiges Overlay.
 * Reines Kotlin, ~3–6 ms pro 640×480-Frame auf aktuellen Snapdragon-Kernen.
 */
class FocusAnalyzer(private val onResult: (Result) -> Unit) : ImageReader.OnImageAvailableListener {

    class Result(val sharpness: Double, val peaking: Bitmap?, val width: Int, val height: Int)

    @Volatile var peakingEnabled = false
    @Volatile var peakingColor: Int = 0xFFFF2BD6.toInt()

    private val main = Handler(Looper.getMainLooper())
    private var lastRun = 0L
    private var luma = ByteArray(0)
    private var mag = IntArray(0)
    private var pixels = IntArray(0)
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var bmpIndex = 0
    private val hist = IntArray(1024)

    override fun onImageAvailable(reader: ImageReader) {
        val img = reader.acquireLatestImage() ?: return
        val w: Int
        val h: Int
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastRun < 70) return
            lastRun = now
            w = img.width; h = img.height
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            if (luma.size != w * h) luma = ByteArray(w * h)
            if (pixStride == 1) {
                for (y in 0 until h) {
                    buf.position(y * rowStride)
                    buf.get(luma, y * w, w)
                }
            } else {
                for (y in 0 until h) for (x in 0 until w) luma[y * w + x] = buf.get(y * rowStride + x * pixStride)
            }
        } finally {
            img.close()
        }
        analyze(w, h)
    }

    private fun analyze(w: Int, h: Int) {
        val l = luma
        // --- Schaerfe: Laplace-RMS im mittleren Viertel der Flaeche
        val x0 = w / 4; val x1 = w * 3 / 4; val y0 = h / 4; val y1 = h * 3 / 4
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val c = l[row + x].toInt() and 0xFF
                val lap = 4 * c - (l[row + x - 1].toInt() and 0xFF) - (l[row + x + 1].toInt() and 0xFF) -
                    (l[row - w + x].toInt() and 0xFF) - (l[row + w + x].toInt() and 0xFF)
                sum += (lap * lap).toDouble()
                n++
            }
        }
        val sharp = if (n > 0) sqrt(sum / n) else 0.0

        var bmp: Bitmap? = null
        if (peakingEnabled) {
            if (mag.size != w * h) { mag = IntArray(w * h); pixels = IntArray(w * h) }
            java.util.Arrays.fill(hist, 0)
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val gx = (l[row + x + 1].toInt() and 0xFF) - (l[row + x - 1].toInt() and 0xFF)
                    val gy = (l[row + w + x].toInt() and 0xFF) - (l[row - w + x].toInt() and 0xFF)
                    val m = (abs(gx) + abs(gy)).coerceAtMost(1023)
                    mag[row + x] = m
                    hist[m]++
                }
            }
            // Schwelle = 97. Perzentil, mindestens 40 (unterdrueckt Rauschen bei unscharfem Bild)
            val total = (w - 2) * (h - 2)
            var acc = 0
            var thr = 1023
            for (i in 1023 downTo 0) {
                acc += hist[i]
                if (acc >= total * 0.03) { thr = i; break }
            }
            thr = thr.coerceAtLeast(40)
            val col = peakingColor
            for (i in 0 until w * h) pixels[i] = if (mag[i] >= thr) col else 0
            bmpIndex = (bmpIndex + 1) % bitmaps.size
            var b = bitmaps[bmpIndex]
            if (b == null || b.width != w || b.height != h) {
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmaps[bmpIndex] = b
            }
            b!!.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp = b
        }
        val r = Result(sharp, bmp, w, h)
        main.post { onResult(r) }
    }
}
