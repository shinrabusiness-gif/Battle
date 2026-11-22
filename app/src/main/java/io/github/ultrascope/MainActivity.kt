package io.github.ultrascope

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.ultrascope.camera.CameraController
import io.github.ultrascope.camera.CameraInventory
import io.github.ultrascope.camera.CameraSpec
import io.github.ultrascope.camera.CaptureSettings
import io.github.ultrascope.camera.FocusAnalyzer
import io.github.ultrascope.core.Calibration
import io.github.ultrascope.core.ScaleMath
import io.github.ultrascope.processing.FocusStacker
import io.github.ultrascope.processing.PanoramaStitcher
import io.github.ultrascope.processing.Progress
import io.github.ultrascope.storage.CalibrationStore
import io.github.ultrascope.storage.MediaStoreSaver
import io.github.ultrascope.ui.AspectFrameLayout
import io.github.ultrascope.ui.OverlayView
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener, OverlayView.Listener {

    enum class Mode { PHOTO, STACK, PANO }

    private lateinit var camera: CameraController
    private lateinit var analyzer: FocusAnalyzer
    private lateinit var calStore: CalibrationStore
    private lateinit var prefs: SharedPreferences
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var previewFrame: AspectFrameLayout
    private lateinit var busyBox: LinearLayout
    private lateinit var busyProgress: ProgressBar
    private lateinit var busyText: TextView
    private lateinit var txtCamera: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtZoom: TextView
    private lateinit var txtFocus: TextView
    private lateinit var txtExposure: TextView
    private lateinit var txtIso: TextView
    private lateinit var txtWb: TextView
    private lateinit var txtScale: TextView
    private lateinit var txtStack: TextView
    private lateinit var txtPano: TextView
    private lateinit var seekZoom: SeekBar
    private lateinit var seekFocus: SeekBar
    private lateinit var seekEv: SeekBar
    private lateinit var seekExposure: SeekBar
    private lateinit var seekIso: SeekBar
    private lateinit var seekWbShift: SeekBar
    private lateinit var seekStackCount: SeekBar
    private lateinit var swManualFocus: Switch
    private lateinit var swManualExposure: Switch
    private lateinit var swWbManual: Switch
    private lateinit var swTorch: Switch
    private lateinit var swPanoSimilarity: Switch
    private lateinit var spAwb: Spinner
    private lateinit var btnShutter: Button
    private lateinit var btnCalApply: Button

    private var specs: List<CameraSpec> = emptyList()
    private var current: CameraSpec? = null
    private var previewSize: Size? = null
    private var mode = Mode.PHOTO
    @Volatile private var busy = false
    private var suppress = false
    private var opencvOk = false
    private var calibrations: List<Calibration> = emptyList()
    private var awbModeList: List<Int> = emptyList()

    // Optionen
    private var optRaw = false
    private var optMinimal = false
    private var optOis = true
    private var optKeepFrames = false
    private var optAnnotated = true
    private var optPng = true

    // Focus-Stacking
    private var stackCount = 12
    private var stackNear: Float? = null
    private var stackFar: Float? = null

    // Mosaik
    private val panoTiles = mutableListOf<File>()
    private var panoFirst: CameraController.StillResult? = null
    private var seriesSnapshot: CaptureSettings? = null

    private val settings get() = camera.settings

    // ================================================================= Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        prefs = getSharedPreferences("ultrascope", MODE_PRIVATE)
        calStore = CalibrationStore(this)
        calibrations = calStore.all()
        loadOptions()

        opencvOk = try { OpenCVLoader.initLocal() } catch (t: Throwable) { false }

        bindViews()
        analyzer = FocusAnalyzer { r ->
            overlay.pushSharpness(r.sharpness)
            if (r.peaking != null) overlay.setPeaking(r.peaking, analysisRotation())
        }
        camera = CameraController(this, this)
        camera.analyzer = analyzer
        camera.settings.minimalProcessing = optMinimal
        camera.settings.ois = optOis

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startCameraIfPossible()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = configureTransform()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (!opencvOk) toast("OpenCV konnte nicht geladen werden – Stack/Mosaik deaktiviert.")
        setMode(Mode.PHOTO)
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else startCameraIfPossible()
    }

    override fun onPause() {
        if (!busy) camera.close()
        super.onPause()
    }

    override fun onDestroy() {
        camera.shutdown()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCameraIfPossible()
            else txtStatus.text = "Ohne Kameraberechtigung keine Funktion."
        }
    }

    private fun startCameraIfPossible() {
        if (!texture.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (camera.isReady) return
        if (specs.isEmpty()) specs = CameraInventory.enumerate(this)
        if (specs.isEmpty()) { txtStatus.text = "Keine Kamera gefunden."; return }
        val wanted = prefs.getString("camera_key", null)
        val spec = specs.firstOrNull { it.key == wanted } ?: defaultSpec(specs)
        openCamera(spec)
    }

    /** Standard: Rueckkamera mit der feinsten abgeschaetzten Aufloesung an der Nahgrenze. */
    private fun defaultSpec(list: List<CameraSpec>): CameraSpec {
        val back = list.filter { it.facing == CameraMetadata.LENS_FACING_BACK }
        return back.filter { it.closeFocusEstimate() != null }
            .minByOrNull { it.closeFocusEstimate()!!.resolvableObjectUm }
            ?: back.firstOrNull() ?: list.first()
    }

    private fun openCamera(spec: CameraSpec) {
        current = spec
        prefs.edit().putString("camera_key", spec.key).apply()
        txtCamera.text = spec.label + "\n" + spec.source
        txtStatus.text = "Oeffne ${spec.key} …"
        overlay.clearMeasurements()
        resetPano(restore = false)
        seriesSnapshot = null
        val st = texture.surfaceTexture ?: return
        camera.open(spec, st, display?.rotation ?: Surface.ROTATION_90, optRaw)
    }

    /** Lautstaerketasten loesen aus (2 s Verzoegerung gegen Verwackeln). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0 && !busy) {
                overlay.banner = "Ausloesung in 2 s …"
                overlay.postDelayed({ overlay.banner = null; onShutter() }, 2000)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ================================================================= Kamera-Callbacks

    override fun onCameraOpened(spec: CameraSpec, previewSize: Size, stillSize: Size, rawActive: Boolean, analysisActive: Boolean) {
        this.previewSize = previewSize
        previewFrame.aspect = previewSize.width.toDouble() / previewSize.height
        configureTransform()
        setupControls(spec, newCamera = true)
        txtStatus.text = "Vorschau ${previewSize.width}×${previewSize.height} · Foto ${stillSize.width}×${stillSize.height}" +
            (if (rawActive) " + DNG" else "") + (if (!analysisActive) " · Schaerfeanalyse nicht verfuegbar" else "")
        updateScale()
    }

    override fun onCameraError(message: String) {
        txtStatus.text = message
        toast(message)
    }

    override fun onLiveInfo(info: CameraController.LiveInfo) {
        val spec = current ?: return
        val afName = when (info.afState) {
            CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED, CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "scharf"
            CameraMetadata.CONTROL_AF_STATE_PASSIVE_SCAN, CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "sucht"
            CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "unscharf"
            else -> if (settings.manualFocus) "MF" else "–"
        }
        val hud = mutableListOf<String>()
        hud += "KAM ${spec.key}" + (info.activePhysicalId?.let { "  aktiv:$it" } ?: "") +
            String.format(Locale.GERMANY, "  f=%.2fmm f/%.1f", info.focalMm ?: spec.focalMm, info.aperture ?: spec.fNumber)
        hud += "FOKUS " + (info.focusDiopters?.let { String.format(Locale.GERMANY, "%.2f D ≈ %s", it, ScaleMath.formatFocus(it)) } ?: "–") + "  [$afName]"
        hud += "BEL " + ScaleMath.formatExposure(info.exposureNs ?: 0) + "  ISO ${info.iso ?: "–"}  ZOOM " +
            String.format(Locale.GERMANY, "%.2f×", info.zoom ?: effectiveZoom())
        val sc = scaleFor(info.focusDiopters, camera.stillSize.width, info.zoom ?: effectiveZoom(), info.activePhysicalId)
        hud += "SKALA " + (sc.umPerPx?.let { String.format(Locale.GERMANY, "%.2f µm/px", it) + if (sc.valid) " [CAL]" else " [?]" } ?: "unkalibriert")
        overlay.hudLines = hud
        if (!settings.manualExposure) {
            txtExposure.text = "Auto · EV ${settings.evIndex}" + " · gemessen " + ScaleMath.formatExposure(info.exposureNs ?: 0) + " ISO ${info.iso ?: "–"}"
        }
        if (!settings.manualFocus) txtFocus.text = "AF · " + (info.focusDiopters?.let { String.format(Locale.GERMANY, "%.2f D ≈ %s", it, ScaleMath.formatFocus(it)) } ?: "–")
        updateScale(info)
    }

    // ================================================================= Overlay-Callbacks

    override fun onCalibrationLineChanged(line: OverlayView.Line?) {
        btnCalApply.isEnabled = line != null && line.length > 10
    }

    override fun onTap(x: Float, y: Float) {
        overlay.resetSharpnessPeak()
    }

    // ================================================================= Vorschau-Transformation

    private fun analysisRotation(): Int {
        val spec = current ?: return 0
        val deviceDeg = when (display?.rotation) {
            Surface.ROTATION_90 -> 270; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 90; else -> 0
        }
        return if (spec.facing == CameraMetadata.LENS_FACING_FRONT) (spec.sensorOrientation - deviceDeg + 360) % 360
        else (spec.sensorOrientation + deviceDeg) % 360
    }

    /** Standard-Camera2-Transformation fuer TextureView (Querformat-Displays). */
    private fun configureTransform() {
        val ps = previewSize ?: return
        val vw = texture.width.toFloat(); val vh = texture.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val rotation = display?.rotation ?: Surface.ROTATION_90
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        val bufferRect = RectF(0f, 0f, ps.height.toFloat(), ps.width.toFloat())
        val cx = viewRect.centerX(); val cy = viewRect.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / ps.height, vw / ps.width)
            matrix.postScale(scale, scale, cx, cy)
            matrix.postRotate(90f * (rotation - 2), cx, cy)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, cx, cy)
        }
        texture.setTransform(matrix)
    }

    // ================================================================= Massstab

    private class Scale(val umPerPx: Double?, val valid: Boolean, val cal: Calibration?, val note: String)

    /** µm pro Pixel eines Fotos der Breite [imageWidth] bei aktuellem Fokus/Zoom. */
    private fun scaleFor(focusReported: Float?, imageWidth: Int, zoom: Float, activeLens: String? = camera.lastInfo?.activePhysicalId): Scale {
        val spec = current ?: return Scale(null, false, null, "")
        val focus = if (settings.manualFocus) settings.focusDiopters else focusReported
        val lens = if (spec.physicalId == null) activeLens else null
        val cal = Calibration.best(calibrations, spec.key, if (settings.manualFocus) focus else null, lens)
            ?: return Scale(null, false, null, "")
        val dev = cal.focusDeviation(if (cal.focusDiopters != null) focus else null)
        val lensChanged = cal.activePhysicalId != null && lens != null && cal.activePhysicalId != lens
        val valid = !lensChanged && when {
            cal.focusDiopters == null -> !settings.manualFocus
            else -> settings.manualFocus && dev <= Calibration.FOCUS_TOLERANCE
        }
        val note = when {
            lensChanged -> "Objektiv gewechselt (${cal.activePhysicalId}→$lens) – Zoom aendern oder neu kalibrieren"
            cal.focusDiopters == null && settings.manualFocus -> "AF-Kalibrierung, jetzt MF"
            cal.focusDiopters == null -> "AF – Abstand wie bei Kalibrierung halten"
            !settings.manualFocus -> "MF-Kalibrierung, jetzt AF"
            dev > Calibration.FOCUS_TOLERANCE -> String.format(Locale.GERMANY, "Fokus %.1f %% abweichend", dev * 100)
            else -> ""
        }
        return Scale(cal.umPerPx(imageWidth, zoom), valid, cal, note)
    }

    private fun updateScale(info: CameraController.LiveInfo? = camera.lastInfo) {
        val still = camera.stillSize
        if (still.width == 0 || overlay.width == 0) return
        val sc = scaleFor(info?.focusDiopters, still.width, info?.zoom ?: effectiveZoom(), info?.activePhysicalId)
        overlay.umPerViewPx = sc.umPerPx?.let { it * still.width / overlay.width }
        overlay.scaleValid = sc.valid
        overlay.scaleNote = sc.note
        txtScale.text = if (sc.umPerPx == null) "Nicht kalibriert fuer ${current?.key}. Lineal/Objektmikrometer fokussieren → Kalibrieren."
        else String.format(Locale.GERMANY, "%.3f µm/px im Foto · Bildfeld %s × %s", sc.umPerPx,
            ScaleMath.formatLength(sc.umPerPx * still.width), ScaleMath.formatLength(sc.umPerPx * still.height)) +
            (if (sc.valid) "" else "\n⚠ ${sc.note}") + "\n${calibrations.count { it.cameraKey == current?.key }} Kalibrierung(en) gespeichert"
    }

    private fun effectiveZoom(): Float {
        val spec = current ?: return 1f
        return if (zoomSupported(spec)) settings.zoom else 1f
    }

    private fun zoomSupported(spec: CameraSpec) =
        (spec.physicalId == null && (spec.zoomRange != null || spec.maxDigitalZoom > 1f)) ||
            (spec.physicalId != null && spec.maxDigitalZoom > 1f)

    private fun applyCalibration() {
        val line = overlay.calibrationLine ?: return
        val spec = current ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "z. B. 1 (= 1 mm) oder 0,1"
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("Bekannte Laenge in mm")
            .setMessage("Die gelbe Linie muss exakt die bekannte Strecke abdecken (z. B. 1 mm auf dem Lineal oder 0,1 mm auf einem Objektmikrometer). " +
                "Fuer Messungen danach den Fokus nicht mehr veraendern – Schaerfe durch Abstand einstellen.")
            .setView(input)
            .setPositiveButton("Kalibrieren") { _, _ ->
                val mm = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (mm == null || mm <= 0) { toast("Ungueltige Laenge"); return@setPositiveButton }
                val still = camera.stillSize
                val imagePx = line.length * still.width / overlay.width
                val cal = Calibration.create(
                    cameraKey = spec.key, referenceLengthUm = mm * 1000.0, measuredImagePx = imagePx,
                    imageWidth = still.width, zoom = camera.lastInfo?.zoom ?: effectiveZoom(),
                    focusDiopters = if (settings.manualFocus) settings.focusDiopters else null,
                    activePhysicalId = if (spec.physicalId == null) camera.lastInfo?.activePhysicalId else null,
                )
                calStore.add(cal)
                calibrations = calStore.all()
                overlay.clearMeasurements()
                overlay.mode = OverlayView.Mode.MEASURE
                toast(String.format(Locale.GERMANY, "Kalibriert: %.3f µm/px (Linie %.0f Foto-px)", cal.umPerPx(still.width, cal.zoomAtCalibration), imagePx))
                updateScale()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ================================================================= UI-Aufbau

    private fun bindViews() {
        texture = findViewById(R.id.texture)
        overlay = findViewById(R.id.overlay)
        overlay.listener = this
        previewFrame = findViewById(R.id.previewFrame)
        busyBox = findViewById(R.id.busy)
        busyProgress = findViewById(R.id.busyProgress)
        busyText = findViewById(R.id.busyText)
        txtCamera = findViewById(R.id.txtCamera)
        txtStatus = findViewById(R.id.txtStatus)
        txtZoom = findViewById(R.id.txtZoom)
        txtFocus = findViewById(R.id.txtFocus)
        txtExposure = findViewById(R.id.txtExposure)
        txtIso = findViewById(R.id.txtIso)
        txtWb = findViewById(R.id.txtWb)
        txtScale = findViewById(R.id.txtScale)
        txtStack = findViewById(R.id.txtStack)
        txtPano = findViewById(R.id.txtPano)
        seekZoom = findViewById(R.id.seekZoom)
        seekFocus = findViewById(R.id.seekFocus)
        seekEv = findViewById(R.id.seekEv)
        seekExposure = findViewById(R.id.seekExposure)
        seekIso = findViewById(R.id.seekIso)
        seekWbShift = findViewById(R.id.seekWbShift)
        seekStackCount = findViewById(R.id.seekStackCount)
        swManualFocus = findViewById(R.id.swManualFocus)
        swManualExposure = findViewById(R.id.swManualExposure)
        swWbManual = findViewById(R.id.swWbManual)
        swTorch = findViewById(R.id.swTorch)
        swPanoSimilarity = findViewById(R.id.swPanoSimilarity)
        spAwb = findViewById(R.id.spAwb)
        btnShutter = findViewById(R.id.btnShutter)
        btnCalApply = findViewById(R.id.btnCalApply)

        findViewById<Button>(R.id.btnCamera).setOnClickListener { showCameraPicker(false) }
        findViewById<Button>(R.id.btnHardware).setOnClickListener { startActivity(Intent(this, HardwareActivity::class.java)) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showOptions() }
        findViewById<Button>(R.id.modePhoto).setOnClickListener { setMode(Mode.PHOTO) }
        findViewById<Button>(R.id.modeStack).setOnClickListener { setMode(Mode.STACK) }
        findViewById<Button>(R.id.modePano).setOnClickListener { setMode(Mode.PANO) }
        btnShutter.setOnClickListener { onShutter() }

        seekZoom.setOnSeekBarChangeListener(seek { p ->
            val spec = current ?: return@seek
            val (lo, hi) = zoomBounds(spec)
            settings.zoom = (lo * exp(ln(hi / lo) * p / 1000.0)).toFloat()
            txtZoom.text = String.format(Locale.GERMANY, "%.2f×  (%.2f–%.1f)", settings.zoom, lo, hi)
            camera.updateRepeating(); updateScale()
        })

        swManualFocus.setOnCheckedChangeListener { _, on ->
            if (suppress) return@setOnCheckedChangeListener
            if (on) {
                // Uebernimmt die zuletzt vom AF gefundene Position
                camera.lastInfo?.focusDiopters?.let { settings.focusDiopters = it }
                setFocusSlider()
            }
            settings.manualFocus = on
            seekFocus.isEnabled = on
            camera.updateRepeating(); updateFocusText(); updateScale()
        }
        seekFocus.setOnSeekBarChangeListener(seek { p ->
            val spec = current ?: return@seek
            settings.focusDiopters = spec.minFocusDiopters * p / 1000f
            camera.updateRepeating(); updateFocusText(); updateScale()
        })
        findViewById<Button>(R.id.btnFocusMinus).setOnClickListener { nudgeFocus(-1) }
        findViewById<Button>(R.id.btnFocusPlus).setOnClickListener { nudgeFocus(+1) }
        findViewById<Button>(R.id.btnFocusClosest).setOnClickListener {
            val spec = current ?: return@setOnClickListener
            if (!spec.manualFocus) return@setOnClickListener
            settings.focusDiopters = spec.minFocusDiopters
            ensureManualFocus()
        }
        findViewById<Button>(R.id.btnAfToMf).setOnClickListener {
            camera.lastInfo?.focusDiopters?.let { settings.focusDiopters = it }
            ensureManualFocus()
        }

        swManualExposure.setOnCheckedChangeListener { _, on ->
            if (suppress) return@setOnCheckedChangeListener
            if (on) camera.lastInfo?.let { i ->
                i.exposureNs?.let { settings.exposureNs = it }
                i.iso?.let { settings.iso = it }
            }
            settings.manualExposure = on
            refreshExposureUi()
            camera.updateRepeating()
        }
        seekEv.setOnSeekBarChangeListener(seek { p ->
            val r = current?.aeCompRange ?: return@seek
            settings.evIndex = r.lower + p
            camera.updateRepeating()
        })
        seekExposure.setOnSeekBarChangeListener(seek { p ->
            val (lo, hi) = exposureBounds() ?: return@seek
            settings.exposureNs = (lo * exp(ln(hi.toDouble() / lo) * p / 1000.0)).toLong()
            refreshExposureUi(); camera.updateRepeating()
        })
        seekIso.setOnSeekBarChangeListener(seek { p ->
            val r = current?.isoRange ?: return@seek
            settings.iso = (r.lower * exp(ln(r.upper.toDouble() / r.lower) * p / 1000.0)).roundToInt()
            refreshExposureUi(); camera.updateRepeating()
        })

        spAwb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                (view as? TextView)?.setTextColor(Color.WHITE)
                if (suppress) return
                awbModeList.getOrNull(pos)?.let { settings.awbMode = it; camera.updateRepeating() }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        swWbManual.setOnCheckedChangeListener { _, on ->
            if (suppress) return@setOnCheckedChangeListener
            if (on) {
                val g = camera.lastInfo?.wbGains
                if (g == null) { toast("Noch keine WB-Werte vom Treiber"); swWbManual.isChecked = false; return@setOnCheckedChangeListener }
                settings.manualWbGains = g
            } else settings.manualWbGains = null
            seekWbShift.isEnabled = on
            updateWbText(); camera.updateRepeating()
        }
        seekWbShift.setOnSeekBarChangeListener(seek { p ->
            settings.wbShift = (p - 500) / 500f
            updateWbText(); camera.updateRepeating()
        })
        swTorch.setOnCheckedChangeListener { _, on -> settings.torch = on; camera.updateRepeating() }

        val cbGrid = findViewById<CheckBox>(R.id.cbGrid)
        val cbCross = findViewById<CheckBox>(R.id.cbCross)
        val cbScale = findViewById<CheckBox>(R.id.cbScale)
        val cbPeaking = findViewById<CheckBox>(R.id.cbPeaking)
        cbGrid.setOnCheckedChangeListener { _, on -> overlay.showGrid = on; overlay.invalidate() }
        cbCross.setOnCheckedChangeListener { _, on -> overlay.showCrosshair = on; overlay.invalidate() }
        cbScale.setOnCheckedChangeListener { _, on -> overlay.showScaleBar = on; overlay.invalidate() }
        cbPeaking.setOnCheckedChangeListener { _, on -> overlay.showPeaking = on; analyzer.peakingEnabled = on; overlay.invalidate() }

        val btnMeasure = findViewById<Button>(R.id.btnMeasure)
        val btnCalibrate = findViewById<Button>(R.id.btnCalibrate)
        btnMeasure.setOnClickListener {
            overlay.mode = if (overlay.mode == OverlayView.Mode.MEASURE) OverlayView.Mode.NONE else OverlayView.Mode.MEASURE
            btnMeasure.isSelected = overlay.mode == OverlayView.Mode.MEASURE; btnCalibrate.isSelected = false
        }
        btnCalibrate.setOnClickListener {
            overlay.mode = if (overlay.mode == OverlayView.Mode.CALIBRATE) OverlayView.Mode.NONE else OverlayView.Mode.CALIBRATE
            btnCalibrate.isSelected = overlay.mode == OverlayView.Mode.CALIBRATE; btnMeasure.isSelected = false
            if (overlay.mode == OverlayView.Mode.CALIBRATE)
                toast("Tipp: Fokus auf MF fixieren, Lineal scharf stellen, gelbe Linie ueber z. B. 1 mm ziehen.")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { overlay.clearMeasurements() }
        btnCalApply.setOnClickListener { applyCalibration() }
        findViewById<Button>(R.id.btnCalReset).setOnClickListener {
            val key = current?.key ?: return@setOnClickListener
            AlertDialog.Builder(this).setMessage("Alle Kalibrierungen fuer Kamera $key loeschen?")
                .setPositiveButton("Loeschen") { _, _ -> calStore.clear(key); calibrations = calStore.all(); updateScale() }
                .setNegativeButton("Abbrechen", null).show()
        }

        seekStackCount.setOnSeekBarChangeListener(seek { p -> stackCount = p + 3; updateStackText() })
        seekStackCount.progress = stackCount - 3
        findViewById<Button>(R.id.btnStackNear).setOnClickListener { stackNear = currentFocus(); updateStackText() }
        findViewById<Button>(R.id.btnStackFar).setOnClickListener { stackFar = currentFocus(); updateStackText() }
        findViewById<Button>(R.id.btnPanoStitch).setOnClickListener { stitchPano() }
        findViewById<Button>(R.id.btnPanoReset).setOnClickListener { resetPano(restore = true) }
    }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !suppress) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun zoomBounds(spec: CameraSpec): Pair<Double, Double> {
        val r = spec.zoomRange
        return if (spec.physicalId == null && r != null) Pair(r.lower.toDouble(), r.upper.toDouble())
        else Pair(1.0, max(1.0001, spec.maxDigitalZoom.toDouble()))
    }

    private fun exposureBounds(): Pair<Long, Long>? {
        val r = current?.exposureRange ?: return null
        return Pair(max(r.lower, 1000L), r.upper.coerceAtMost(1_000_000_000L))
    }

    private fun setupControls(spec: CameraSpec, newCamera: Boolean) {
        suppress = true
        // Zoom
        val zs = zoomSupported(spec)
        seekZoom.isEnabled = zs
        val (zlo, zhi) = zoomBounds(spec)
        if (!zs) settings.zoom = 1f
        settings.zoom = settings.zoom.coerceIn(zlo.toFloat(), zhi.toFloat())
        seekZoom.progress = (1000 * ln(settings.zoom / zlo) / ln(zhi / zlo)).roundToInt().coerceIn(0, 1000)
        txtZoom.text = if (zs) String.format(Locale.GERMANY, "%.2f×  (%.2f–%.1f)", settings.zoom, zlo, zhi)
        else "nicht verfuegbar (physische Kamera ohne Crop-Unterstuetzung)"

        // Fokus
        swManualFocus.isEnabled = spec.manualFocus
        if (!spec.manualFocus) settings.manualFocus = false
        swManualFocus.isChecked = settings.manualFocus
        seekFocus.isEnabled = spec.manualFocus && settings.manualFocus
        settings.focusDiopters = settings.focusDiopters.coerceIn(0f, max(spec.minFocusDiopters, 0f))
        setFocusSlider()
        updateFocusText()

        // Belichtung
        swManualExposure.isEnabled = spec.manualSensor
        if (!spec.manualSensor) settings.manualExposure = false
        swManualExposure.isChecked = settings.manualExposure
        spec.aeCompRange?.let { seekEv.max = it.upper - it.lower; seekEv.progress = settings.evIndex - it.lower }
        refreshExposureUi()

        // Weissabgleich
        awbModeList = spec.awbModes.filter { it != CameraMetadata.CONTROL_AWB_MODE_OFF }.ifEmpty { listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, awbModeList.map { awbName(it) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spAwb.adapter = adapter
        spAwb.setSelection(awbModeList.indexOf(settings.awbMode).coerceAtLeast(0))
        swWbManual.isEnabled = spec.manualPostProcessing
        if (newCamera || !spec.manualPostProcessing) settings.manualWbGains = null
        swWbManual.isChecked = settings.manualWbGains != null
        seekWbShift.isEnabled = settings.manualWbGains != null
        seekWbShift.progress = ((settings.wbShift + 1f) * 500).roundToInt()
        updateWbText()

        swTorch.isEnabled = spec.hasFlash
        if (!spec.hasFlash) { settings.torch = false; swTorch.isChecked = false }
        swTorch.isChecked = settings.torch
        suppress = false
        updateStackText()
        camera.updateRepeating()
    }

    private fun setFocusSlider() {
        val spec = current ?: return
        if (spec.minFocusDiopters > 0f) seekFocus.progress = (1000 * settings.focusDiopters / spec.minFocusDiopters).roundToInt()
    }

    private fun updateFocusText() {
        val spec = current ?: return
        if (!spec.manualFocus) { txtFocus.text = "Fixfokus oder kein manueller Fokus verfuegbar"; return }
        if (settings.manualFocus) txtFocus.text = String.format(Locale.GERMANY, "MF %.3f D ≈ %s  (Nahgrenze %.1f D ≈ %s)",
            settings.focusDiopters, ScaleMath.formatFocus(settings.focusDiopters), spec.minFocusDiopters, ScaleMath.formatFocus(spec.minFocusDiopters))
    }

    private fun nudgeFocus(dir: Int) {
        val spec = current ?: return
        if (!spec.manualFocus) return
        if (!settings.manualFocus) camera.lastInfo?.focusDiopters?.let { settings.focusDiopters = it }
        settings.focusDiopters = (settings.focusDiopters + dir * spec.minFocusDiopters * 0.004f).coerceIn(0f, spec.minFocusDiopters)
        ensureManualFocus()
    }

    private fun ensureManualFocus() {
        val spec = current ?: return
        if (!spec.manualFocus) { toast("Diese Kamera erlaubt keinen manuellen Fokus"); return }
        settings.manualFocus = true
        suppress = true; swManualFocus.isChecked = true; suppress = false
        seekFocus.isEnabled = true
        setFocusSlider(); updateFocusText()
        camera.updateRepeating(); updateScale()
    }

    private fun currentFocus(): Float = if (settings.manualFocus) settings.focusDiopters else camera.lastInfo?.focusDiopters ?: 0f

    private fun refreshExposureUi() {
        val spec = current ?: return
        val manual = settings.manualExposure && spec.manualSensor
        seekEv.visibility = if (manual) View.GONE else View.VISIBLE
        seekEv.isEnabled = spec.aeCompRange != null && spec.aeCompRange.upper > spec.aeCompRange.lower
        seekExposure.visibility = if (manual) View.VISIBLE else View.GONE
        seekIso.visibility = if (manual) View.VISIBLE else View.GONE
        txtIso.visibility = if (manual) View.VISIBLE else View.GONE
        if (manual) {
            exposureBounds()?.let { (lo, hi) ->
                val e = settings.exposureNs.coerceIn(lo, hi); settings.exposureNs = e
                seekExposure.progress = (1000 * ln(e.toDouble() / lo) / ln(hi.toDouble() / lo)).roundToInt()
            }
            spec.isoRange?.let { r ->
                val i = settings.iso.coerceIn(r.lower, r.upper); settings.iso = i
                seekIso.progress = (1000 * ln(i.toDouble() / r.lower) / ln(r.upper.toDouble() / r.lower)).roundToInt()
                txtIso.text = "ISO $i  (${r.lower}–${r.upper})"
            }
            txtExposure.text = "Manuell · Zeit " + ScaleMath.formatExposure(settings.exposureNs)
        } else if (!spec.manualSensor) {
            txtExposure.text = "Auto (MANUAL_SENSOR nicht unterstuetzt)"
        }
    }

    private fun updateWbText() {
        val g = settings.shiftedGains()
        txtWb.text = if (g == null) "Automatik/Preset" + if (current?.manualPostProcessing == false) " (manuelle Gains nicht unterstuetzt)" else ""
        else String.format(Locale.GERMANY, "Fix: R %.2f  G %.2f  B %.2f  (Verschiebung %+.2f)", g.red, g.greenEven, g.blue, settings.wbShift)
    }

    private fun awbName(m: Int) = when (m) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Automatisch"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Gluehlampe"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Leuchtstoff"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Leuchtstoff warm"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Tageslicht"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Bewoelkt"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "Daemmerung"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Schatten"
        else -> "Modus $m"
    }

    private fun setMode(m: Mode) {
        if ((m == Mode.STACK || m == Mode.PANO) && !opencvOk) { toast("OpenCV nicht geladen"); return }
        if (mode == Mode.PANO && m != Mode.PANO && panoTiles.isNotEmpty()) resetPano(restore = true)
        mode = m
        findViewById<Button>(R.id.modePhoto).isSelected = m == Mode.PHOTO
        findViewById<Button>(R.id.modeStack).isSelected = m == Mode.STACK
        findViewById<Button>(R.id.modePano).isSelected = m == Mode.PANO
        findViewById<View>(R.id.stackBox).visibility = if (m == Mode.STACK) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panoBox).visibility = if (m == Mode.PANO) View.VISIBLE else View.GONE
        btnShutter.text = when (m) { Mode.PHOTO -> "AUFNAHME"; Mode.STACK -> "FOKUSREIHE + STACK"; Mode.PANO -> "KACHEL AUFNEHMEN" }
        updateStackText(); updatePanoText()
    }

    private fun stackRange(): Pair<Float, Float>? {
        val spec = current ?: return null
        if (!spec.manualFocus) return null
        val f = currentFocus().coerceAtLeast(0.01f)
        val near = (stackNear ?: (f * 1.06f)).coerceIn(0f, spec.minFocusDiopters)
        val far = (stackFar ?: (f * 0.94f)).coerceIn(0f, spec.minFocusDiopters)
        return if (near >= far) Pair(near, far) else Pair(far, near)
    }

    private fun updateStackText() {
        val r = stackRange()
        txtStack.text = if (r == null) "Diese Kamera unterstuetzt keinen manuellen Fokus."
        else String.format(Locale.GERMANY, "%d Bilder · nah %.3f D (%s) → fern %.3f D (%s)%s", stackCount,
            r.first, ScaleMath.formatFocus(r.first), r.second, ScaleMath.formatFocus(r.second),
            if (stackNear == null || stackFar == null) "\n(Standard ±6 % um aktuellen Fokus – mit Nah/Fern festlegen)" else "")
    }

    private fun updatePanoText() {
        txtPano.text = "${panoTiles.size} Kachel(n). Probe/Handy parallel verschieben, je ≥30 % Ueberlappung. " +
            "Fokus, Zoom und Belichtung werden ab der 1. Kachel fixiert."
    }

    // ================================================================= Optionen & Kamerawahl

    private fun loadOptions() {
        optRaw = prefs.getBoolean("opt_raw", false)
        optMinimal = prefs.getBoolean("opt_minimal", false)
        optOis = prefs.getBoolean("opt_ois", true)
        optKeepFrames = prefs.getBoolean("opt_keep", false)
        optAnnotated = prefs.getBoolean("opt_annot", true)
        optPng = prefs.getBoolean("opt_png", true)
    }

    private fun showOptions() {
        val labels = arrayOf(
            "RAW (DNG) zusaetzlich speichern (nur direkt geoeffnete Kameras)",
            "Minimale Verarbeitung (Rauschfilter + Nachschaerfung aus)",
            "Optische Bildstabilisierung (OIS) – fuer Stativ ausschalten",
            "Einzelbilder von Stack/Mosaik zusaetzlich speichern",
            "Kopie mit Massstabsbalken speichern",
            "Stack/Mosaik als PNG (verlustfrei) statt JPEG 98",
        )
        val checked = booleanArrayOf(optRaw, optMinimal, optOis, optKeepFrames, optAnnotated, optPng)
        AlertDialog.Builder(this).setTitle("Optionen")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("OK") { _, _ ->
                val rawChanged = checked[0] != optRaw
                optRaw = checked[0]; optMinimal = checked[1]; optOis = checked[2]
                optKeepFrames = checked[3]; optAnnotated = checked[4]; optPng = checked[5]
                prefs.edit().putBoolean("opt_raw", optRaw).putBoolean("opt_minimal", optMinimal).putBoolean("opt_ois", optOis)
                    .putBoolean("opt_keep", optKeepFrames).putBoolean("opt_annot", optAnnotated).putBoolean("opt_png", optPng).apply()
                settings.minimalProcessing = optMinimal
                settings.ois = optOis
                if (rawChanged) current?.let { openCamera(it) } else camera.updateRepeating()
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun showCameraPicker(probe: Boolean) {
        if (busy) return
        if (probe) specs = CameraInventory.enumerate(this, probeHiddenUpTo = 127)
        val items = specs.map { s ->
            val est = s.closeFocusEstimate()
            s.label + "\n   " + s.source + (est?.let {
                String.format(Locale.GERMANY, "\n   Nahgrenze ≈ %.0f mm · ≈ %.1f µm/px · Aufloesung ≥ %.0f µm (Schaetzung)",
                    it.objectDistanceMm, it.umPerOutputPixel, it.resolvableObjectUm)
            } ?: "")
        } + listOf(if (probe) "— Suche abgeschlossen —" else "▶ Nicht gelistete Kamera-IDs suchen (0–127)")
        AlertDialog.Builder(this).setTitle("Kamera waehlen")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == specs.size) { if (!probe) showCameraPicker(true) }
                else openCamera(specs[which])
            }.show()
    }

    // ================================================================= Aufnahme

    private fun onShutter() {
        if (busy || !camera.isReady) return
        when (mode) {
            Mode.PHOTO -> runBusy("Aufnahme …") { p ->
                val r = camera.captureStillBlocking()
                p.update(0.6f, "Speichere …")
                val name = saveStill(r, "IMG")
                runOnUiThread { toast("Gespeichert: $name") }
            }
            Mode.STACK -> captureStack()
            Mode.PANO -> capturePanoTile()
        }
    }

    /** Fixiert Belichtung und Weissabgleich (und optional Fokus) fuer eine Bildserie. */
    private fun lockForSeries(lockFocus: Boolean) {
        val spec = current ?: return
        seriesSnapshot = CaptureSettings().also { it.copyFrom(settings) }
        val i = camera.lastInfo
        if (!settings.manualExposure) {
            if (spec.manualSensor && i?.exposureNs != null && i.iso != null) {
                settings.exposureNs = i.exposureNs; settings.iso = i.iso; settings.manualExposure = true
            } else settings.aeLock = true
        }
        if (settings.manualWbGains == null) {
            if (spec.manualPostProcessing && i?.wbGains != null) settings.manualWbGains = i.wbGains else settings.awbLock = true
        }
        if (lockFocus && !settings.manualFocus && spec.manualFocus) {
            i?.focusDiopters?.let { settings.focusDiopters = it }
            settings.manualFocus = true
        }
        camera.updateRepeating()
        runOnUiThread { current?.let { setupControls(it, newCamera = false) }; updateScale() }
    }

    private fun restoreAfterSeries() {
        seriesSnapshot?.let { settings.copyFrom(it) }
        seriesSnapshot = null
        camera.updateRepeating()
        runOnUiThread { current?.let { setupControls(it, newCamera = false) }; updateScale() }
    }

    private fun captureStack() {
        val range = stackRange() ?: run { toast("Kein manueller Fokus verfuegbar"); return }
        val n = stackCount
        runBusy("Fokusreihe …") { p ->
            val files = mutableListOf<File>()
            var refInfo: CameraController.StillResult? = null
            lockForSeries(lockFocus = false)
            try {
                settings.manualFocus = true
                for (k in 0 until n) {
                    val d = range.first + (range.second - range.first) * k / (n - 1).toFloat()
                    p.update(0.4f * k / n, String.format(Locale.GERMANY, "Bild %d/%d · %.3f D", k + 1, n, d))
                    settings.focusDiopters = d
                    camera.updateRepeating()
                    camera.waitForFocus(d, 2000)
                    val r = camera.captureStillBlocking()
                    files += r.jpegFile
                    r.dngFile?.delete()
                    if (k == n / 2) refInfo = r
                    if (optKeepFrames) saveStill(r, "STACKFRAME_${k + 1}", keepFile = true)
                }
            } finally {
                restoreAfterSeries()
            }
            val ref = refInfo!!
            val stacker = FocusStacker()
            val res = stacker.stack(files) { f, msg -> p.update(0.4f + 0.55f * f, msg) }
            try {
                val ts = MediaStoreSaver.timestamp()
                val ext = if (optPng) "png" else "jpg"
                val outFile = File(cacheDir, "stack_$ts.$ext")
                val depthFile = File(cacheDir, "stack_${ts}_tiefe.png")
                writeMat(res.image, outFile); writeMat(res.depthColor, depthFile)
                val sc = scaleFor(ref.info.focusDiopters, ref.width, ref.info.zoom ?: effectiveZoom(), ref.info.activePhysicalId)
                val meta = buildMeta("focus_stack", ref.info, ref.width, ref.height, sc).apply {
                    put("frames", n)
                    put("focusNearDiopters", range.first.toDouble()); put("focusFarDiopters", range.second.toDouble())
                    put("referenceFrame", res.referenceIndex + 1)
                    put("alignmentEcc", JSONArray(res.alignmentScores))
                    put("log", JSONArray(res.log))
                }
                val name = "UltraScope_STACK_$ts.$ext"
                MediaStoreSaver.saveImage(this, outFile, name)
                MediaStoreSaver.saveImage(this, depthFile, "UltraScope_STACK_${ts}_tiefenkarte.png")
                MediaStoreSaver.saveText(this, meta.toString(2), "UltraScope_STACK_$ts.json")
                if (optAnnotated && sc.umPerPx != null) saveAnnotated(outFile, sc, "UltraScope_STACK_${ts}_massstab.jpg", "Focus-Stack $n Bilder")
                outFile.delete(); depthFile.delete()
                runOnUiThread { toast("Stack gespeichert: $name") }
            } finally {
                res.release()
                files.forEach { it.delete() }
            }
        }
    }

    private fun capturePanoTile() {
        runBusy("Kachel ${panoTiles.size + 1} …") { _ ->
            if (panoTiles.isEmpty()) {
                lockForSeries(lockFocus = true)
                Thread.sleep(300) // Parameter einschwingen lassen
            }
            val r = camera.captureStillBlocking()
            r.dngFile?.delete()
            if (panoFirst == null) panoFirst = r
            if (optKeepFrames) saveStill(r, "TILE_${panoTiles.size + 1}", keepFile = true)
            synchronized(panoTiles) { panoTiles += r.jpegFile }
            runOnUiThread {
                updatePanoText()
                overlay.banner = "Kachel ${panoTiles.size} ✓ – verschieben & naechste aufnehmen"
                overlay.postDelayed({ overlay.banner = null }, 1500)
            }
        }
    }

    private fun stitchPano() {
        if (panoTiles.size < 2) { toast("Mindestens 2 Kacheln aufnehmen"); return }
        val tiles = panoTiles.toList()
        val first = panoFirst!!
        val model = if (swPanoSimilarity.isChecked) PanoramaStitcher.Model.SIMILARITY else PanoramaStitcher.Model.TRANSLATION
        runBusy("Mosaik …") { p ->
            val res = try {
                PanoramaStitcher(model = model).stitch(tiles) { f, msg -> p.update(f * 0.9f, msg) }
            } catch (e: PanoramaStitcher.StitchException) {
                runOnUiThread { AlertDialog.Builder(this).setTitle("Mosaik nicht moeglich").setMessage(e.message).setPositiveButton("OK", null).show() }
                return@runBusy
            }
            try {
                p.update(0.92f, "Speichere …")
                val ts = MediaStoreSaver.timestamp()
                val ext = if (optPng) "png" else "jpg"
                val outFile = File(cacheDir, "pano_$ts.$ext")
                writeMat(res.image, outFile)
                val sc0 = scaleFor(first.info.focusDiopters, first.width, first.info.zoom ?: effectiveZoom(), first.info.activePhysicalId)
                val sc = Scale(sc0.umPerPx?.let { it / res.outputScale }, sc0.valid && model == PanoramaStitcher.Model.TRANSLATION, sc0.cal,
                    if (model == PanoramaStitcher.Model.SIMILARITY) "Freihand-Modell: Massstab angenaehert" else sc0.note)
                val meta = buildMeta("mosaic", first.info, res.image.cols(), res.image.rows(), sc).apply {
                    put("tiles", tiles.size); put("model", model.name); put("outputScale", res.outputScale)
                    put("log", JSONArray(res.log))
                    sc.umPerPx?.let {
                        put("mosaicWidthMm", it * res.image.cols() / 1000.0); put("mosaicHeightMm", it * res.image.rows() / 1000.0)
                    }
                }
                val name = "UltraScope_MOSAIK_$ts.$ext"
                MediaStoreSaver.saveImage(this, outFile, name)
                MediaStoreSaver.saveText(this, meta.toString(2), "UltraScope_MOSAIK_$ts.json")
                outFile.delete()
                runOnUiThread { toast("Mosaik gespeichert: $name (${res.image.cols()}×${res.image.rows()})") }
            } finally {
                res.release()
            }
            runOnUiThread { resetPano(restore = true) }
        }
    }

    private fun resetPano(restore: Boolean) {
        synchronized(panoTiles) { panoTiles.forEach { it.delete() }; panoTiles.clear() }
        panoFirst = null
        if (restore && seriesSnapshot != null) restoreAfterSeries()
        if (::txtPano.isInitialized) updatePanoText()
    }

    // ================================================================= Speichern

    private fun writeMat(m: Mat, f: File) {
        val params = if (f.name.endsWith(".png")) org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 3)
        else org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 98)
        check(Imgcodecs.imwrite(f.absolutePath, m, params)) { "Schreiben fehlgeschlagen" }
    }

    private fun buildMeta(kind: String, info: CameraController.LiveInfo, w: Int, h: Int, sc: Scale): JSONObject {
        val spec = current!!
        val still = camera.stillSize
        return JSONObject().apply {
            put("app", "UltraScope ${BuildConfigCompat.versionName(this@MainActivity)}")
            put("type", kind)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("timestamp", System.currentTimeMillis())
            put("camera", JSONObject().apply {
                put("key", spec.key); put("label", spec.label)
                put("activePhysicalId", info.activePhysicalId ?: JSONObject.NULL)
                put("focalMm", (info.focalMm ?: spec.focalMm).toDouble()); put("fNumber", (info.aperture ?: spec.fNumber).toDouble())
                put("focusDistanceCalibration", spec.focusCalibrationName())
            })
            put("capture", JSONObject().apply {
                put("focusDiopters", info.focusDiopters?.toDouble() ?: JSONObject.NULL)
                put("manualFocus", settings.manualFocus)
                put("exposureNs", info.exposureNs ?: JSONObject.NULL); put("iso", info.iso ?: JSONObject.NULL)
                put("zoomRatio", (info.zoom ?: effectiveZoom()).toDouble())
                put("minimalProcessing", settings.minimalProcessing)
                info.wbGains?.let { put("wbGainsRGGB", JSONArray(listOf(it.red, it.greenEven, it.greenOdd, it.blue))) }
            })
            put("image", JSONObject().apply { put("width", w); put("height", h) })
            put("scale", JSONObject().apply {
                put("umPerPixel", sc.umPerPx ?: JSONObject.NULL)
                put("valid", sc.valid)
                put("note", sc.note)
                sc.cal?.let { put("calibration", CalibrationStore.toJson(it)) }
            })
            // Messlinien der Live-Ansicht, umgerechnet in Bildpixel des Fotos
            if (overlay.lines.isNotEmpty() && overlay.width > 0 && kind == "photo") {
                val f = still.width.toDouble() / overlay.width
                val arr = JSONArray()
                overlay.lines.forEach { l ->
                    arr.put(JSONObject().apply {
                        put("x1", l.ax * f); put("y1", l.ay * f); put("x2", l.bx * f); put("y2", l.by * f)
                        put("lengthPx", l.length * f)
                        sc.umPerPx?.let { put("lengthUm", l.length * f * it) }
                    })
                }
                put("measurements", arr)
            }
        }
    }

    /** Speichert eine Einzelaufnahme verlustfrei (+ DNG, JSON, optional Massstabskopie). Gibt den Dateinamen zurueck. */
    private fun saveStill(r: CameraController.StillResult, prefix: String, keepFile: Boolean = false): String {
        val ts = MediaStoreSaver.timestamp()
        val base = "UltraScope_${prefix}_$ts"
        val sc = scaleFor(r.info.focusDiopters, r.width, r.info.zoom ?: effectiveZoom(), r.info.activePhysicalId)
        val meta = buildMeta(if (prefix == "IMG") "photo" else "series_frame", r.info, r.width, r.height, sc)
        val target = if (keepFile) File(cacheDir, "$base.jpg").also { r.jpegFile.copyTo(it, true) } else r.jpegFile
        val desc = "UltraScope ${current?.key} " + (sc.umPerPx?.let { String.format(Locale.US, "%.4f um/px%s", it, if (sc.valid) "" else " (unsicher)") } ?: "unkalibriert")
        MediaStoreSaver.writeExif(target, desc, meta.toString())
        MediaStoreSaver.saveImage(this, target, "$base.jpg")
        r.dngFile?.let { if (!keepFile) { MediaStoreSaver.saveImage(this, it, "$base.dng"); it.delete() } }
        MediaStoreSaver.saveText(this, meta.toString(2), "$base.json")
        if (optAnnotated && sc.umPerPx != null && prefix == "IMG") saveAnnotated(target, sc, "${base}_massstab.jpg", null)
        target.delete()
        return "$base.jpg"
    }

    /** Erstellt eine separate, beschriftete Kopie (Massstabsbalken, Messlinien, Parameter). Das Original bleibt unveraendert. */
    private fun saveAnnotated(src: File, sc: Scale, name: String, title: String?) {
        val um = sc.umPerPx ?: return
        val opts = BitmapFactory.Options().apply { inMutable = true }
        val bmp = try { BitmapFactory.decodeFile(src.absolutePath, opts) } catch (e: OutOfMemoryError) { null } ?: return
        try {
            val c = Canvas(bmp)
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            val unit = w / 1000f
            val bg = Paint().apply { color = 0xB0000000.toInt() }
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f * unit; typeface = Typeface.MONOSPACE }
            val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3DFF8A.toInt(); strokeWidth = 3f * unit; style = Paint.Style.STROKE }
            val label = Paint(white).apply { color = 0xFF3DFF8A.toInt(); textSize = 20f * unit }
            // Messlinien (nur Einzelfoto: gleiche Geometrie wie Vorschau)
            if (title == null && overlay.width > 0) {
                val f = w / overlay.width
                overlay.lines.forEach { l ->
                    c.drawLine(l.ax * f, l.ay * f, l.bx * f, l.by * f, green)
                    val t = ScaleMath.formatLength(l.length * f * um)
                    c.drawText(t, (l.ax + l.bx) / 2 * f + 8 * unit, (l.ay + l.by) / 2 * f - 8 * unit, label)
                }
            }
            val barUm = ScaleMath.niceFloor(um * w * 0.2)
            val barPx = (barUm / um).toFloat()
            val x1 = w - 30 * unit; val x0 = x1 - barPx; val y = h - 40 * unit
            c.drawRect(x0 - 15 * unit, y - 45 * unit, x1 + 15 * unit, y + 20 * unit, bg)
            c.drawRect(x0, y - 5 * unit, x1, y + 5 * unit, white)
            val bt = ScaleMath.formatLength(barUm)
            c.drawText(bt, x0 + (barPx - white.measureText(bt)) / 2, y - 15 * unit, white)
            val info = (title?.let { "$it · " } ?: "") + "${current?.key} · " + String.format(Locale.GERMANY, "%.3f µm/px", um) +
                (if (sc.valid) "" else " (unsicher: ${sc.note})")
            c.drawRect(0f, h - 34 * unit, white.measureText(info) + 24 * unit, h, bg)
            c.drawText(info, 12 * unit, h - 10 * unit, white)
            val out = File(cacheDir, name)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            MediaStoreSaver.saveImage(this, out, name)
            out.delete()
        } finally {
            bmp.recycle()
        }
    }

    // ================================================================= Hilfen

    private fun runBusy(title: String, block: (Progress) -> Unit) {
        if (busy) return
        busy = true
        btnShutter.isEnabled = false
        showBusy(title, 0f)
        worker.execute {
            try {
                block(Progress { f, msg -> runOnUiThread { showBusy(msg, f) } })
            } catch (t: Throwable) {
                Log.e("UltraScope", "Fehler", t)
                runOnUiThread { toast("Fehler: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                runOnUiThread {
                    busy = false
                    btnShutter.isEnabled = true
                    busyBox.visibility = View.GONE
                }
            }
        }
    }

    private fun showBusy(msg: String, f: Float) {
        busyBox.visibility = View.VISIBLE
        busyText.text = msg
        busyProgress.progress = (f * 1000).roundToInt()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_CAMERA = 1
    }
}

/** Versionsname ohne generierte BuildConfig (in AGP 8 standardmaessig deaktiviert). */
object BuildConfigCompat {
    fun versionName(a: Activity): String = try {
        a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }
}
