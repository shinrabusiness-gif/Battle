package io.github.ultrascope.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.ultrascope.core.ScaleMath
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Wissenschaftliches Overlay ueber der Live-Vorschau: kalibriertes Gitter, Fadenkreuz,
 * Massstabsbalken, Mess-/Kalibrierlinien (ziehen zum Zeichnen, Endpunkte verschiebbar),
 * Fokus-Peaking und Schaerfeverlauf. Alle Koordinaten in View-Pixeln.
 */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { NONE, MEASURE, CALIBRATE }

    data class Line(var ax: Float, var ay: Float, var bx: Float, var by: Float) {
        val length get() = hypot((bx - ax).toDouble(), (by - ay).toDouble())
    }

    interface Listener {
        fun onCalibrationLineChanged(line: Line?)
        fun onTap(x: Float, y: Float)
    }

    var listener: Listener? = null
    var mode = Mode.NONE
        set(v) { field = v; invalidate() }

    /** µm pro View-Pixel, null = nicht kalibriert. */
    var umPerViewPx: Double? = null
        set(v) { field = v; invalidate() }
    var scaleValid = true
        set(v) { field = v; invalidate() }
    var scaleNote: String = ""

    var showGrid = true
    var showCrosshair = true
    var showScaleBar = true
    var showPeaking = false

    var hudLines: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var banner: String? = null
        set(v) { field = v; invalidate() }

    val lines = mutableListOf<Line>()
    var calibrationLine: Line? = null
        private set

    private var peaking: Bitmap? = null
    private var peakingRotation = 0
    private val history = FloatArray(160)
    private var histCount = 0
    private var histPeak = 0f

    private val d = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5539D0FF; strokeWidth = 1f * d }
    private val gridMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8839D0FF.toInt(); strokeWidth = 1.4f * d }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); strokeWidth = 1.2f * d; style = Paint.Style.STROKE }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DFF8A.toInt(); strokeWidth = 2f * d; style = Paint.Style.STROKE }
    private val calPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC53D.toInt(); strokeWidth = 2f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * d; typeface = Typeface.MONOSPACE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * d; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bgPaint = Paint().apply { color = 0x99000000.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39D0FF.toInt(); strokeWidth = 1.5f * d; style = Paint.Style.STROKE }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC53D.toInt(); strokeWidth = 1f * d }
    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5D5D.toInt(); textSize = 13f * d; typeface = Typeface.DEFAULT_BOLD }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val m = Matrix()

    fun setPeaking(bmp: Bitmap?, rotationDeg: Int) {
        peaking = bmp; peakingRotation = rotationDeg
        if (showPeaking) invalidate()
    }

    fun pushSharpness(v: Double) {
        System.arraycopy(history, 1, history, 0, history.size - 1)
        history[history.size - 1] = v.toFloat()
        histCount = min(histCount + 1, history.size)
        histPeak = max(histPeak * 0.995f, v.toFloat())
        invalidate()
    }

    fun resetSharpnessPeak() { histPeak = 0f }

    fun clearMeasurements() {
        lines.clear(); calibrationLine = null
        listener?.onCalibrationLineChanged(null)
        invalidate()
    }

    private fun lengthLabel(l: Line): String {
        val s = umPerViewPx
        return if (s != null) ScaleMath.formatLength(l.length * s) + if (scaleValid) "" else " (?)"
        else String.format("%.0f px", l.length)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (showPeaking) peaking?.let { b ->
            m.reset()
            val rotated = peakingRotation % 180 != 0
            val bw = if (rotated) b.height else b.width
            val bh = if (rotated) b.width else b.height
            m.postTranslate(-b.width / 2f, -b.height / 2f)
            m.postRotate(peakingRotation.toFloat())
            m.postScale(w / bw, h / bh)
            m.postTranslate(w / 2f, h / 2f)
            c.drawBitmap(b, m, bitmapPaint)
        }

        val scale = umPerViewPx
        // --- Gitter: kalibriert in runden Mikrometerschritten, sonst 10×10-Raster
        if (showGrid) {
            val stepPx: Float
            var label: String? = null
            if (scale != null && scale > 0) {
                val stepUm = ScaleMath.niceFloor(scale * min(w, h) / 6.0)
                stepPx = (stepUm / scale).toFloat()
                label = "Gitter " + ScaleMath.formatLength(stepUm)
            } else stepPx = w / 10f
            if (stepPx > 8f) {
                var i = 0
                var x = w / 2f
                while (x <= w) { c.drawLine(x, 0f, x, h, if (i % 5 == 0) gridMajorPaint else gridPaint); x += stepPx; i++ }
                i = 1; x = w / 2f - stepPx
                while (x >= 0) { c.drawLine(x, 0f, x, h, if (i % 5 == 0) gridMajorPaint else gridPaint); x -= stepPx; i++ }
                i = 0; var y = h / 2f
                while (y <= h) { c.drawLine(0f, y, w, y, if (i % 5 == 0) gridMajorPaint else gridPaint); y += stepPx; i++ }
                i = 1; y = h / 2f - stepPx
                while (y >= 0) { c.drawLine(0f, y, w, y, if (i % 5 == 0) gridMajorPaint else gridPaint); y -= stepPx; i++ }
            }
            label?.let { drawTag(c, it, 8f * d, h - 64f * d) }
        }

        if (showCrosshair) {
            val cx = w / 2f; val cy = h / 2f; val r = 18f * d
            c.drawCircle(cx, cy, r, crossPaint)
            c.drawLine(cx - 3 * r, cy, cx - r * 0.4f, cy, crossPaint)
            c.drawLine(cx + r * 0.4f, cy, cx + 3 * r, cy, crossPaint)
            c.drawLine(cx, cy - 3 * r, cx, cy - r * 0.4f, crossPaint)
            c.drawLine(cx, cy + r * 0.4f, cx, cy + 3 * r, crossPaint)
        }

        // --- Massstabsbalken
        if (showScaleBar && scale != null && scale > 0) {
            val targetUm = ScaleMath.niceFloor(scale * w * 0.22)
            val len = (targetUm / scale).toFloat()
            val x1 = w - 16f * d; val x0 = x1 - len; val y = h - 22f * d
            c.drawRect(x0 - 6 * d, y - 26 * d, x1 + 6 * d, y + 10 * d, bgPaint)
            c.drawRect(x0, y - 3 * d, x1, y + 3 * d, barPaint)
            c.drawLine(x0, y - 8 * d, x0, y + 8 * d, barPaint)
            c.drawLine(x1, y - 8 * d, x1, y + 8 * d, barPaint)
            val t = ScaleMath.formatLength(targetUm)
            c.drawText(t, x0 + (len - labelPaint.measureText(t)) / 2f, y - 10 * d, labelPaint)
        }

        // --- Messlinien
        for (l in lines) drawLine(c, l, measurePaint)
        calibrationLine?.let { drawLine(c, it, calPaint) }

        // --- HUD
        if (hudLines.isNotEmpty()) {
            val lh = textPaint.textSize * 1.25f
            val maxW = hudLines.maxOf { textPaint.measureText(it) }
            c.drawRect(0f, 0f, maxW + 16f * d, lh * hudLines.size + 10f * d, bgPaint)
            hudLines.forEachIndexed { i, s -> c.drawText(s, 8f * d, 6f * d + lh * (i + 1) - lh * 0.2f, textPaint) }
        }
        if (scale == null) drawTag(c, "UNKALIBRIERT – Masse in Pixeln", 8f * d, h - 40f * d, warnPaint)
        else if (!scaleValid) drawTag(c, "MASSSTAB UNSICHER: $scaleNote", 8f * d, h - 40f * d, warnPaint)
        banner?.let {
            val tw = labelPaint.measureText(it)
            c.drawRect((w - tw) / 2 - 10 * d, h * 0.12f - 18 * d, (w + tw) / 2 + 10 * d, h * 0.12f + 8 * d, bgPaint)
            c.drawText(it, (w - tw) / 2, h * 0.12f, labelPaint)
        }
        when (mode) {
            Mode.MEASURE -> drawTag(c, "MESSEN: Linie ziehen · Endpunkte verschieben", w / 2 - 150 * d, h - 40f * d)
            Mode.CALIBRATE -> drawTag(c, "KALIBRIEREN: Linie ueber bekannte Laenge ziehen", w / 2 - 160 * d, h - 40f * d)
            else -> {}
        }

        // --- Schaerfeverlauf (unten links)
        if (histCount > 1) {
            val gw = 160f * d; val gh = 34f * d; val gx = 8f * d; val gy = h - 8f * d - gh
            c.drawRect(gx, gy - 14 * d, gx + gw, gy + gh, bgPaint)
            val maxV = max(histPeak, 1e-3f)
            path.reset()
            val start = history.size - histCount
            for (i in start until history.size) {
                val px = gx + gw * (i - start) / (history.size - 1).toFloat()
                val py = gy + gh - gh * (history[i] / maxV).coerceIn(0f, 1f)
                if (i == start) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, sparkPaint)
            c.drawLine(gx, gy, gx + gw, gy, peakPaint)
            val cur = history[history.size - 1]
            val pct = (100f * cur / maxV).toInt()
            c.drawText("Schaerfe ${String.format("%.1f", cur)}  ($pct % vom Max)", gx + 4 * d, gy - 3 * d, textPaint)
        }
    }

    private fun drawTag(c: Canvas, s: String, x: Float, y: Float, p: Paint = labelPaint) {
        val tw = p.measureText(s)
        c.drawRect(x - 4 * d, y - p.textSize - 2 * d, x + tw + 4 * d, y + 5 * d, bgPaint)
        c.drawText(s, x, y, p)
    }

    private fun drawLine(c: Canvas, l: Line, p: Paint) {
        c.drawLine(l.ax, l.ay, l.bx, l.by, p)
        // Endmarken senkrecht zur Linie
        val ang = atan2((l.by - l.ay).toDouble(), (l.bx - l.ax).toDouble())
        val nx = (-Math.sin(ang) * 8 * d).toFloat(); val ny = (Math.cos(ang) * 8 * d).toFloat()
        val solid = Paint(p).apply { pathEffect = null }
        c.drawLine(l.ax - nx, l.ay - ny, l.ax + nx, l.ay + ny, solid)
        c.drawLine(l.bx - nx, l.by - ny, l.bx + nx, l.by + ny, solid)
        handlePaint.color = p.color
        c.drawCircle(l.ax, l.ay, 14 * d, handlePaint)
        c.drawCircle(l.bx, l.by, 14 * d, handlePaint)
        val s = lengthLabel(l)
        val mx = (l.ax + l.bx) / 2; val my = (l.ay + l.by) / 2
        val tw = labelPaint.measureText(s)
        c.drawRect(RectF(mx - tw / 2 - 5 * d, my - 30 * d, mx + tw / 2 + 5 * d, my - 10 * d), bgPaint)
        c.drawText(s, mx - tw / 2, my - 15 * d, labelPaint)
    }

    // ---------------------------------------------------------------- Touch

    private var drag: Line? = null
    private var dragA = false
    private var downX = 0f
    private var downY = 0f
    private var created = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(0f, width.toFloat()); val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; created = false
                if (mode == Mode.NONE) return true
                val candidates = if (mode == Mode.CALIBRATE) listOfNotNull(calibrationLine) else lines
                val hit = 36 * d
                drag = null
                for (l in candidates.reversed()) {
                    if (hypot(x - l.ax, y - l.ay) < hit) { drag = l; dragA = true; break }
                    if (hypot(x - l.bx, y - l.by) < hit) { drag = l; dragA = false; break }
                }
                if (drag == null) {
                    val nl = Line(x, y, x, y)
                    if (mode == Mode.CALIBRATE) calibrationLine = nl else lines += nl
                    drag = nl; dragA = false; created = true
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val l = drag ?: return true
                if (dragA) { l.ax = x; l.ay = y } else { l.bx = x; l.by = y }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.NONE) {
                    if (hypot(x - downX, y - downY) < 20 * d) listener?.onTap(x, y)
                    return true
                }
                val l = drag
                if (l != null && created && l.length < 12 * d) {
                    if (mode == Mode.CALIBRATE) calibrationLine = null else lines.remove(l)
                }
                if (mode == Mode.CALIBRATE) listener?.onCalibrationLineChanged(calibrationLine)
                drag = null
                invalidate()
            }
        }
        return true
    }
}
