package io.github.ultrascope.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Camera2-Steuerung: oeffnet eine logische Kamera oder eine physische Teilkamera einer
 * logischen Multikamera, haelt Vorschau + Analyse-Stream (YUV) + JPEG (+ optional RAW) in
 * einer Sitzung und setzt alle manuellen Parameter aus [CaptureSettings].
 */
class CameraController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onCameraOpened(spec: CameraSpec, previewSize: Size, stillSize: Size, rawActive: Boolean, analysisActive: Boolean)
        fun onCameraError(message: String)
        fun onLiveInfo(info: LiveInfo)
    }

    data class LiveInfo(
        val focusDiopters: Float?,
        val lensState: Int?,
        val afState: Int?,
        val exposureNs: Long?,
        val iso: Int?,
        val zoom: Float?,
        val activePhysicalId: String?,
        val wbGains: RggbChannelVector?,
        val focalMm: Float?,
        val aperture: Float?,
        val frameNumber: Long,
    )

    class StillResult(
        val jpegFile: File,
        val dngFile: File?,
        val info: LiveInfo,
        val width: Int,
        val height: Int,
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraThread: HandlerThread? = null
    private var handler: Handler? = null
    private var analysisThread: HandlerThread? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null

    @Volatile var spec: CameraSpec? = null
        private set
    @Volatile var stillSize: Size = Size(0, 0)
        private set
    @Volatile var lastInfo: LiveInfo? = null
        private set

    val settings = CaptureSettings()
    var analyzer: FocusAnalyzer? = null

    private var physicalRequestKeys: Set<CaptureRequest.Key<*>> = emptySet()
    private var jpegOrientation = 0
    private var lastColorTransform: ColorSpaceTransform? = null
    private val frameCounter = AtomicLong(0)
    private var lastUiPost = 0L
    private val pending = AtomicReference<PendingStill?>(null)
    private var openToken = 0

    private val cacheDir: File get() = File(context.cacheDir, "frames").apply { mkdirs() }

    // ---------------------------------------------------------------- Lebenszyklus

    private fun ensureThreads() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("UltraScopeCamera").also { it.start() }
            handler = Handler(cameraThread!!.looper)
        }
        if (analysisThread == null) {
            analysisThread = HandlerThread("UltraScopeAnalysis").also { it.start() }
        }
    }

    /**
     * @param displayRotation Surface.ROTATION_* des Displays (fuer die JPEG-Orientierung)
     */
    @SuppressLint("MissingPermission")
    fun open(spec: CameraSpec, texture: SurfaceTexture, displayRotation: Int, wantRaw: Boolean, wantAnalysis: Boolean = true) {
        close()
        ensureThreads()
        val token = ++openToken
        this.spec = spec
        val still = CameraSpec.largest43(spec.jpegSizes)
        if (still == null) {
            listener.onCameraError("Kamera ${spec.key} liefert keine JPEG-Groessen.")
            return
        }
        stillSize = still
        val preview = choosePreview(spec.previewSizes, still)
        texture.setDefaultBufferSize(preview.width, preview.height)
        previewSurface = Surface(texture)

        jpegReader = ImageReader.newInstance(still.width, still.height, ImageFormat.JPEG, 2).also {
            it.setOnImageAvailableListener({ r -> onJpeg(r) }, handler)
        }
        val rawSize = if (wantRaw && spec.rawCapable && spec.physicalId == null)
            spec.rawSizes.maxByOrNull { it.width.toLong() * it.height } else null
        rawReader = rawSize?.let { s ->
            ImageReader.newInstance(s.width, s.height, ImageFormat.RAW_SENSOR, 2).also {
                it.setOnImageAvailableListener({ r -> onRaw(r) }, handler)
            }
        }
        // Analyse-Stream nur ohne RAW (garantierte Stream-Kombinationen bleiben so einfach)
        val yuvSize = if (wantAnalysis && rawReader == null) chooseAnalysis(spec.yuvSizes) else null
        yuvReader = yuvSize?.let { s ->
            ImageReader.newInstance(s.width, s.height, ImageFormat.YUV_420_888, 3).also { r ->
                analyzer?.let { a -> r.setOnImageAvailableListener(a, Handler(analysisThread!!.looper)) }
            }
        }

        val parent = try { manager.getCameraCharacteristics(spec.openId) } catch (e: Exception) { null }
        physicalRequestKeys = parent?.availablePhysicalCameraRequestKeys?.toSet() ?: emptySet()
        val sensorOrientation = spec.sensorOrientation
        val deviceDeg = when (displayRotation) {
            Surface.ROTATION_90 -> 270; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 90; else -> 0
        }
        jpegOrientation = if (spec.facing == CameraMetadata.LENS_FACING_FRONT)
            (sensorOrientation - deviceDeg + 360) % 360 else (sensorOrientation + deviceDeg) % 360

        try {
            manager.openCamera(spec.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (token != openToken) { camera.close(); return }
                    device = camera
                    createSession(spec, preview, still, true)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); if (device == camera) device = null
                    postError("Kamera getrennt (evtl. von anderer App belegt).")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); if (device == camera) device = null
                    postError("Kamerafehler $error beim Oeffnen von ${spec.key}.")
                }
            }, handler)
        } catch (e: Exception) {
            postError("Oeffnen fehlgeschlagen: ${e.message}")
        }
    }

    private fun createSession(spec: CameraSpec, preview: Size, still: Size, withAnalysis: Boolean) {
        val dev = device ?: return
        val surfaces = mutableListOf(previewSurface!!, jpegReader!!.surface)
        rawReader?.let { surfaces += it.surface }
        val analysisOn = withAnalysis && yuvReader != null
        if (analysisOn) surfaces += yuvReader!!.surface
        val outputs = surfaces.map { s ->
            OutputConfiguration(s).also { oc -> spec.physicalId?.let { oc.setPhysicalCameraId(it) } }
        }
        val executor = Executor { r -> handler?.post(r) }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    updateRepeating()
                    mainHandler.post { listener.onCameraOpened(spec, preview, still, rawReader != null, analysisOn) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (analysisOn) {
                        Log.w(TAG, "Sitzung mit Analyse-Stream abgelehnt, versuche ohne.")
                        createSession(spec, preview, still, false)
                    } else {
                        postError("Kamerasitzung fuer ${spec.key} konnte nicht konfiguriert werden " +
                            "(Stream-Kombination vom Treiber abgelehnt). RAW deaktivieren oder andere Kamera waehlen.")
                    }
                }
            })
        try {
            dev.createCaptureSession(cfg)
        } catch (e: Exception) {
            postError("Sitzung fehlgeschlagen: ${e.message}")
        }
    }

    fun close() {
        openToken++
        pending.getAndSet(null)?.fail(IllegalStateException("Kamera geschlossen"))
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        yuvReader?.close(); yuvReader = null
        previewSurface?.release(); previewSurface = null
        lastColorTransform = null
    }

    fun shutdown() {
        close()
        cameraThread?.quitSafely(); cameraThread = null; handler = null
        analysisThread?.quitSafely(); analysisThread = null
    }

    val isReady: Boolean get() = session != null

    // ---------------------------------------------------------------- Requests

    private fun newBuilder(template: Int): CaptureRequest.Builder {
        val dev = device ?: throw IllegalStateException("Kamera nicht offen")
        val pid = spec?.physicalId
        return if (pid != null) dev.createCaptureRequest(template, setOf(pid)) else dev.createCaptureRequest(template)
    }

    /** Setzt einen Schluessel fuer die logische Kamera und — falls erlaubt — fuer die physische. */
    private fun <T> put(b: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        b.set(key, value)
        val pid = spec?.physicalId ?: return
        if (key in physicalRequestKeys) {
            try { b.setPhysicalCameraKey(key, value, pid) } catch (e: Exception) { /* nicht unterstuetzt */ }
        }
    }

    private fun applySettings(b: CaptureRequest.Builder, still: Boolean) {
        val sp = spec ?: return
        val st = settings
        put(b, CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        put(b, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

        // --- Zoom
        if (sp.physicalId == null) {
            sp.zoomRange?.let { r -> b.set(CaptureRequest.CONTROL_ZOOM_RATIO, st.zoom.coerceIn(r.lower, r.upper)) }
                ?: sp.activeArray?.let { b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(it, st.zoom.coerceIn(1f, sp.maxDigitalZoom))) }
        } else if (CaptureRequest.SCALER_CROP_REGION in physicalRequestKeys) {
            sp.activeArray?.let {
                try {
                    b.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION,
                        cropFor(it, st.zoom.coerceIn(1f, sp.maxDigitalZoom)), sp.physicalId)
                } catch (_: Exception) {}
            }
        }

        // --- Fokus
        if (st.manualFocus && sp.manualFocus) {
            put(b, CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            put(b, CaptureRequest.LENS_FOCUS_DISTANCE, st.focusDiopters.coerceIn(0f, sp.minFocusDiopters))
        } else {
            val af = when {
                sp.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                sp.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> CameraMetadata.CONTROL_AF_MODE_OFF
            }
            put(b, CaptureRequest.CONTROL_AF_MODE, af)
        }

        // --- Belichtung
        if (st.manualExposure && sp.manualSensor) {
            put(b, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            val exp = sp.exposureRange?.let { st.exposureNs.coerceIn(it.lower, it.upper) } ?: st.exposureNs
            val iso = sp.isoRange?.let { st.iso.coerceIn(it.lower, it.upper) } ?: st.iso
            put(b, CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            put(b, CaptureRequest.SENSOR_SENSITIVITY, iso)
            put(b, CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
        } else {
            put(b, CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val ev = sp.aeCompRange?.let { st.evIndex.coerceIn(it.lower, it.upper) } ?: 0
            put(b, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            put(b, CaptureRequest.CONTROL_AE_LOCK, st.aeLock)
        }

        // --- Licht (Taschenlampe als Dauerlicht)
        if (sp.hasFlash) {
            put(b, CaptureRequest.FLASH_MODE, if (st.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        }

        // --- Weissabgleich
        val gains = st.shiftedGains()
        val transform = lastColorTransform
        if (gains != null && transform != null && sp.manualPostProcessing) {
            put(b, CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            put(b, CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            put(b, CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            put(b, CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
        } else {
            val mode = if (sp.awbModes.contains(st.awbMode)) st.awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO
            put(b, CaptureRequest.CONTROL_AWB_MODE, mode)
            put(b, CaptureRequest.CONTROL_AWB_LOCK, st.awbLock)
        }

        // --- Bildverarbeitung
        if (st.minimalProcessing) {
            pickMode(sp.noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                ?.let { put(b, CaptureRequest.NOISE_REDUCTION_MODE, it) }
            pickMode(sp.edgeModes, CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST)
                ?.let { put(b, CaptureRequest.EDGE_MODE, it) }
        } else if (still) {
            pickMode(sp.noiseModes, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                ?.let { put(b, CaptureRequest.NOISE_REDUCTION_MODE, it) }
            pickMode(sp.edgeModes, CameraMetadata.EDGE_MODE_HIGH_QUALITY, CameraMetadata.EDGE_MODE_FAST)
                ?.let { put(b, CaptureRequest.EDGE_MODE, it) }
        }

        // --- Optische Stabilisierung
        if (sp.oisModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            put(b, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (st.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        }
    }

    private fun pickMode(available: IntArray, vararg preferred: Int): Int? = preferred.firstOrNull { available.contains(it) }

    private fun cropFor(active: Rect, zoom: Float): Rect {
        val w = (active.width() / zoom).roundToInt()
        val h = (active.height() / zoom).roundToInt()
        val l = active.left + (active.width() - w) / 2
        val t = active.top + (active.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    /** Uebernimmt die aktuellen [settings] in die Live-Vorschau. Thread-sicher. */
    fun updateRepeating() {
        val s = session ?: return
        try {
            val b = newBuilder(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(previewSurface!!)
            yuvReader?.let { if (analyzerAttached) b.addTarget(it.surface) }
            applySettings(b, still = false)
            s.setRepeatingRequest(b.build(), repeatingCallback, handler)
        } catch (e: CameraAccessException) {
            postError("Vorschau-Request fehlgeschlagen: ${e.message}")
        } catch (e: IllegalStateException) {
            // Sitzung wurde gerade geschlossen – ignorieren
        } catch (e: IllegalArgumentException) {
            postError("Parameter vom Treiber abgelehnt: ${e.message}")
        }
    }

    private val analyzerAttached: Boolean get() = analyzer != null && yuvReader != null

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            handleResult(result)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleResult(result: TotalCaptureResult): LiveInfo {
        val pid = spec?.physicalId
        val r: CaptureResult = (if (pid != null) result.physicalCameraResults[pid] else null) ?: result
        fun <T> g(key: CaptureResult.Key<T>): T? = r.get(key) ?: result.get(key)
        g(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastColorTransform = it }
        val info = LiveInfo(
            focusDiopters = g(CaptureResult.LENS_FOCUS_DISTANCE),
            lensState = g(CaptureResult.LENS_STATE),
            afState = g(CaptureResult.CONTROL_AF_STATE),
            exposureNs = g(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = g(CaptureResult.SENSOR_SENSITIVITY),
            zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO),
            activePhysicalId = pid ?: result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
            wbGains = g(CaptureResult.COLOR_CORRECTION_GAINS),
            focalMm = g(CaptureResult.LENS_FOCAL_LENGTH),
            aperture = g(CaptureResult.LENS_APERTURE),
            frameNumber = frameCounter.incrementAndGet(),
        )
        lastInfo = info
        val now = SystemClock.uptimeMillis()
        if (now - lastUiPost > 100) {
            lastUiPost = now
            mainHandler.post { listener.onLiveInfo(info) }
        }
        return info
    }

    // ---------------------------------------------------------------- Einzelaufnahme

    private inner class PendingStill(val expectRaw: Boolean) {
        val latch = CountDownLatch(1)
        var result: TotalCaptureResult? = null
        var info: LiveInfo? = null
        var jpeg: ByteArray? = null
        var raw: Image? = null
        var error: Exception? = null
        var out: StillResult? = null

        @Synchronized fun tryFinish() {
            if (latch.count == 0L) return
            val res = result ?: return
            val bytes = jpeg ?: return
            if (expectRaw && raw == null) return
            try {
                val stamp = System.currentTimeMillis()
                val jpegFile = File(cacheDir, "still_$stamp.jpg")
                FileOutputStream(jpegFile).use { it.write(bytes) }
                var dng: File? = null
                raw?.let { img ->
                    dng = File(cacheDir, "still_$stamp.dng")
                    val ch = manager.getCameraCharacteristics(spec!!.openId)
                    DngCreator(ch, res).use { creator ->
                        creator.setOrientation(exifOrientation(jpegOrientation))
                        FileOutputStream(dng!!).use { creator.writeImage(it, img) }
                    }
                }
                out = StillResult(jpegFile, dng, info!!, stillSize.width, stillSize.height)
            } catch (e: Exception) {
                error = e
            } finally {
                raw?.close(); raw = null
                latch.countDown()
            }
        }

        @Synchronized fun fail(e: Exception) {
            if (latch.count == 0L) return
            error = e
            raw?.close(); raw = null
            latch.countDown()
        }
    }

    private fun onJpeg(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        try {
            val p = pending.get() ?: return
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            p.jpeg = bytes
            p.tryFinish()
        } finally {
            img.close()
        }
    }

    private fun onRaw(reader: ImageReader) {
        val img = reader.acquireNextImage() ?: return
        val p = pending.get()
        if (p == null || p.raw != null) { img.close(); return }
        p.raw = img
        p.tryFinish()
    }

    /**
     * Loest eine Einzelaufnahme mit den aktuellen [settings] aus und wartet auf JPEG (+ DNG).
     * Darf NICHT auf dem Main-Thread aufgerufen werden.
     */
    fun captureStillBlocking(timeoutMs: Long = 10_000): StillResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "captureStillBlocking nicht im UI-Thread aufrufen" }
        val s = session ?: throw IllegalStateException("Kamera nicht bereit")
        val p = PendingStill(expectRaw = rawReader != null)
        pending.set(p)
        val b = newBuilder(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(jpegReader!!.surface)
        rawReader?.let { b.addTarget(it.surface) }
        applySettings(b, still = true)
        s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                p.info = handleResult(result)
                p.result = result
                p.tryFinish()
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                p.fail(IllegalStateException("Aufnahme fehlgeschlagen (Grund ${failure.reason})"))
            }
        }, handler)
        if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.compareAndSet(p, null)
            throw TimeoutException("Keine Bilddaten nach $timeoutMs ms")
        }
        pending.compareAndSet(p, null)
        p.error?.let { throw it }
        return p.out ?: throw IllegalStateException("Aufnahme ohne Ergebnis")
    }

    /**
     * Wartet, bis die Linse auf [targetDiopters] steht (LENS_STATE stationaer und gemeldete
     * Fokusdistanz innerhalb der Toleranz) – fuer Fokusreihen. Gibt false bei Timeout zurueck.
     */
    fun waitForFocus(targetDiopters: Float, timeoutMs: Long = 1500): Boolean {
        val start = frameCounter.get()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val tol = max(0.01f, targetDiopters * 0.01f)
        while (SystemClock.uptimeMillis() < deadline) {
            val i = lastInfo
            if (i != null && i.frameNumber >= start + 3) {
                val stationary = i.lensState == null || i.lensState == CameraMetadata.LENS_STATE_STATIONARY
                val near = i.focusDiopters == null || abs(i.focusDiopters - targetDiopters) <= tol
                if (stationary && near) return true
            }
            Thread.sleep(15)
        }
        return false
    }

    private fun postError(msg: String) {
        Log.e(TAG, msg)
        mainHandler.post { listener.onCameraError(msg) }
    }

    companion object {
        private const val TAG = "UltraScope"

        fun exifOrientation(deg: Int) = when (deg) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

        /** Vorschau im selben Seitenverhaeltnis wie das Foto, hoechstens 1920 px breit. */
        fun choosePreview(sizes: List<Size>, still: Size): Size {
            val ratio = still.width.toDouble() / still.height
            val same = sizes.filter { abs(it.width.toDouble() / it.height - ratio) < 0.01 && it.width <= 1920 }
            return same.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.first()
        }

        /** Kleiner 4:3-YUV-Stream fuer Schaerfeanalyse und Fokus-Peaking. */
        fun chooseAnalysis(sizes: List<Size>): Size? {
            val c = sizes.filter { CameraSpec.is43(it) && it.width in 480..1024 }
            return c.minByOrNull { abs(it.width - 640) } ?: sizes.filter { it.width <= 1024 }.maxByOrNull { it.width }
        }
    }
}
