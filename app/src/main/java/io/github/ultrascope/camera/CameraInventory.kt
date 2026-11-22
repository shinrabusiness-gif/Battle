package io.github.ultrascope.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size
import android.util.SizeF
import io.github.ultrascope.core.Optics
import java.util.Locale
import kotlin.math.abs

/**
 * Alles, was die App ueber eine (logische oder physische) Kamera wissen muss —
 * ausschliesslich aus den Camera2-Metadaten des Geraets gelesen, nichts ist hart kodiert.
 */
data class CameraSpec(
    /** Kamera-ID, die mit CameraManager.openCamera geoeffnet wird. */
    val openId: String,
    /** Wenn gesetzt: physische Teilkamera, deren Streams ueber die logische Kamera [openId] laufen. */
    val physicalId: String?,
    val source: String,
    val facing: Int,
    val focalLengths: FloatArray,
    val apertures: FloatArray,
    val sensorSize: SizeF?,
    val pixelArray: Size?,
    val activeArray: Rect?,
    val minFocusDiopters: Float,
    val hyperfocalDiopters: Float?,
    val focusCalibration: Int?,
    val hardwareLevel: Int,
    val capabilities: IntArray,
    val zoomRange: Range<Float>?,
    val maxDigitalZoom: Float,
    val jpegSizes: List<Size>,
    val rawSizes: List<Size>,
    val yuvSizes: List<Size>,
    val previewSizes: List<Size>,
    val exposureRange: Range<Long>?,
    val isoRange: Range<Int>?,
    val aeCompRange: Range<Int>?,
    val aeCompStep: Float,
    val awbModes: IntArray,
    val afModes: IntArray,
    val oisModes: IntArray,
    val noiseModes: IntArray,
    val edgeModes: IntArray,
    val hasFlash: Boolean,
    val sensorOrientation: Int,
    val physicalIds: Set<String>,
    val logicalMultiCamera: Boolean,
) {
    /** Eindeutiger Schluessel (fuer Kalibrierungen). */
    val key: String get() = if (physicalId != null) "$openId/$physicalId" else openId

    val manualSensor get() = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
    val manualPostProcessing get() = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
    val rawCapable get() = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && rawSizes.isNotEmpty()
    val manualFocus get() = minFocusDiopters > 0f && afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)
    val focalMm get() = focalLengths.firstOrNull() ?: 0f
    val fNumber get() = apertures.firstOrNull() ?: 0f

    val equivalentFocalMm: Double
        get() = sensorSize?.let { Optics.equivalentFocal(focalMm.toDouble(), it.width.toDouble(), it.height.toDouble()) } ?: 0.0

    val facingName: String
        get() = when (facing) {
            CameraMetadata.LENS_FACING_BACK -> "Rueck"
            CameraMetadata.LENS_FACING_FRONT -> "Front"
            else -> "Extern"
        }

    val label: String
        get() {
            val id = if (physicalId != null) "$openId→$physicalId" else openId
            val eq = equivalentFocalMm
            val mp = (pixelArray?.let { it.width.toLong() * it.height } ?: 0L) / 1_000_000.0
            return String.format(
                Locale.GERMANY, "ID %s · %s · %.0f mm KB · f/%.1f · %.0f MP%s",
                id, facingName, eq, fNumber, mp, if (logicalMultiCamera) " · logisch" else ""
            )
        }

    /** Optik-Abschaetzung an der Naheinstellgrenze. */
    fun closeFocusEstimate(outputWidth: Int = largest43(jpegSizes)?.width ?: 0): Optics.Estimate? {
        val s = sensorSize ?: return null
        val pa = pixelArray ?: return null
        if (outputWidth <= 0) return null
        return Optics.estimate(
            Optics.Input(
                focalMm = focalMm.toDouble(),
                fNumber = fNumber.toDouble(),
                sensorWidthMm = s.width.toDouble(),
                sensorHeightMm = s.height.toDouble(),
                pixelArrayWidth = pa.width,
                outputWidth = outputWidth,
                focusDiopters = minFocusDiopters.toDouble(),
            )
        )
    }

    fun focusCalibrationName(): String = when (focusCalibration) {
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
        CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
        else -> "unbekannt"
    }

    fun hardwareLevelName(): String = when (hardwareLevel) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?($hardwareLevel)"
    }

    companion object {
        fun is43(s: Size) = abs(s.width.toDouble() / s.height - 4.0 / 3.0) < 0.01

        fun largest43(sizes: List<Size>): Size? =
            sizes.filter { is43(it) }.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes.maxByOrNull { it.width.toLong() * it.height }
    }
}

object CameraInventory {

    /**
     * Listet alle fuer Apps sichtbaren Kameras, deren physische Teilkameras und optional
     * zusaetzlich per ID-Probe gefundene, nicht gelistete Kameras.
     */
    fun enumerate(context: Context, probeHiddenUpTo: Int = 0): List<CameraSpec> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = mutableListOf<CameraSpec>()
        val listed = try { mgr.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        val seenStandalone = mutableSetOf<String>()

        for (id in listed) {
            val ch = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            result += build(id, null, "gelistet", ch)
            seenStandalone += id
            for (pid in ch.physicalCameraIds) {
                val pch = try { mgr.getCameraCharacteristics(pid) } catch (e: Exception) { continue }
                result += build(id, pid, "physisch in logischer Kamera $id", pch)
            }
        }
        if (probeHiddenUpTo > 0) {
            for (n in 0..probeHiddenUpTo) {
                val id = n.toString()
                if (id in seenStandalone) continue
                val ch = try { mgr.getCameraCharacteristics(id) } catch (e: Exception) { continue }
                result += build(id, null, "nicht gelistet (ID-Probe)", ch)
                seenStandalone += id
            }
        }
        return result
    }

    fun characteristics(context: Context, id: String): CameraCharacteristics {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return mgr.getCameraCharacteristics(id)
    }

    private fun build(openId: String, physicalId: String?, source: String, ch: CameraCharacteristics): CameraSpec {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        fun sizes(fmt: Int): List<Size> = try { map?.getOutputSizes(fmt)?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val preview = try { map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        return CameraSpec(
            openId = openId,
            physicalId = physicalId,
            source = source,
            facing = ch.get(CameraCharacteristics.LENS_FACING) ?: -1,
            focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0),
            apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: FloatArray(0),
            sensorSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            hyperfocalDiopters = ch.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE),
            focusCalibration = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION),
            hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            capabilities = caps,
            zoomRange = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
            maxDigitalZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            jpegSizes = sizes(ImageFormat.JPEG),
            rawSizes = sizes(ImageFormat.RAW_SENSOR),
            yuvSizes = sizes(ImageFormat.YUV_420_888),
            previewSizes = preview,
            exposureRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            aeCompRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            aeCompStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
            afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
            oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0),
            noiseModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0),
            edgeModes = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0),
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            physicalIds = ch.physicalCameraIds,
            logicalMultiCamera = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
        )
    }

    /** Ausfuehrlicher Textbericht fuer die Hardware-Pruefung. */
    fun report(specs: List<CameraSpec>): String = buildString {
        appendLine("UltraScope – Hardwarebericht")
        appendLine("Geraet: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE}), Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
        appendLine("Kameras gefunden: ${specs.size}")
        appendLine()
        for (s in specs) {
            appendLine("══ ${s.label}")
            appendLine("Quelle: ${s.source}")
            appendLine("Hardware-Level: ${s.hardwareLevelName()} | logische Multikamera: ${s.logicalMultiCamera}" +
                if (s.physicalIds.isNotEmpty()) " | physische IDs: ${s.physicalIds.joinToString()}" else "")
            appendLine("Brennweite(n): ${s.focalLengths.joinToString { fmt(it, 2) }} mm (≈ ${fmt(s.equivalentFocalMm, 0)} mm KB) | Blende(n): f/${s.apertures.joinToString { fmt(it, 2) }}")
            s.sensorSize?.let { appendLine("Sensor: ${fmt(it.width, 2)} × ${fmt(it.height, 2)} mm") }
            s.pixelArray?.let { pa ->
                val pitch = s.sensorSize?.let { it.width * 1000f / pa.width }
                appendLine("Pixelarray: ${pa.width} × ${pa.height}" + (pitch?.let { " | Pixelabstand ≈ ${fmt(it, 2)} µm" } ?: ""))
            }
            appendLine("Min. Fokus: ${fmt(s.minFocusDiopters, 2)} D (≈ ${if (s.minFocusDiopters > 0) fmt(1000f / s.minFocusDiopters, 0) + " mm" else "Fixfokus"}) | Kalibrierung Fokusdistanz: ${s.focusCalibrationName()}")
            appendLine("Zoom-Ratio-Bereich: ${s.zoomRange?.let { fmt(it.lower, 2) + "–" + fmt(it.upper, 2) } ?: "–"} | max. Digitalzoom: ${fmt(s.maxDigitalZoom, 1)}")
            appendLine("Belichtung: ${s.exposureRange?.let { fmtNs(it.lower) + " – " + fmtNs(it.upper) } ?: "–"} | ISO: ${s.isoRange?.let { "${it.lower}–${it.upper}" } ?: "–"}")
            appendLine("MANUAL_SENSOR: ${s.manualSensor} | MANUAL_POST_PROCESSING: ${s.manualPostProcessing} | RAW: ${s.rawCapable} | manueller Fokus: ${s.manualFocus} | Blitz/Licht: ${s.hasFlash}")
            appendLine("JPEG max: ${CameraSpec.largest43(s.jpegSizes) ?: "–"} | alle JPEG: ${s.jpegSizes.sortedByDescending { it.width }.take(6).joinToString()}")
            if (s.rawSizes.isNotEmpty()) appendLine("RAW: ${s.rawSizes.joinToString()}")
            val est = s.closeFocusEstimate()
            if (est != null) {
                appendLine("— Abschaetzung an der Naheinstellgrenze (duenne Linse, aus obigen Werten):")
                appendLine("   Objektabstand ≈ ${fmt(est.objectDistanceMm, 0)} mm | Abbildungsmassstab ≈ 1:${fmt(1 / est.magnification, 1)} (m = ${fmt(est.magnification, 4)})")
                appendLine("   Bildfeld ≈ ${fmt(est.fovWidthMm, 1)} × ${fmt(est.fovHeightMm, 1)} mm | ${fmt(est.umPerOutputPixel, 1)} µm/Pixel (JPEG) | ${fmt(est.umPerSensorPixel, 1)} µm/Sensorpixel")
                appendLine("   Beugungsgrenze (Rayleigh, 550 nm, N_eff=${fmt(est.workingFNumber, 2)}) ≈ ${fmt(est.diffractionLimitObjectUm, 1)} µm | Abtastgrenze ≈ ${fmt(est.samplingLimitObjectUm, 1)} µm")
                appendLine("   ⇒ kleinste trennbare Struktur bestenfalls ≈ ${fmt(est.resolvableObjectUm, 0)} µm | Schaerfentiefe ≈ ${fmt(est.depthOfFieldMm, 2)} mm")
                if (s.focusCalibration != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED)
                    appendLine("   (Fokusdistanz nicht CALIBRATED → Werte nur grobe Orientierung, bitte mit Massstab kalibrieren)")
            } else {
                appendLine("— Keine Nahabschaetzung moeglich (Fixfokus oder fehlende Metadaten)")
            }
            appendLine()
        }
    }

    private fun fmt(v: Float, d: Int) = String.format(Locale.GERMANY, "%.${d}f", v)
    private fun fmt(v: Double, d: Int) = String.format(Locale.GERMANY, "%.${d}f", v)
    private fun fmtNs(ns: Long) = io.github.ultrascope.core.ScaleMath.formatExposure(ns)
}
