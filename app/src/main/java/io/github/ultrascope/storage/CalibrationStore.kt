package io.github.ultrascope.storage

import android.content.Context
import io.github.ultrascope.core.Calibration
import org.json.JSONArray
import org.json.JSONObject

/** Persistiert Kalibrierungen (mehrere pro Kamera, je Fokusposition) in SharedPreferences. */
class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("calibrations", Context.MODE_PRIVATE)

    fun all(): List<Calibration> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(c: Calibration) {
        // Eine bestehende Kalibrierung derselben Kamera mit (fast) gleichem Fokus wird ersetzt.
        val kept = all().filterNot {
            it.cameraKey == c.cameraKey && it.focusDeviation(c.focusDiopters) < Calibration.FOCUS_TOLERANCE &&
                (it.focusDiopters == null) == (c.focusDiopters == null)
        }
        save(kept + c)
    }

    fun clear(cameraKey: String) = save(all().filterNot { it.cameraKey == cameraKey })

    private fun save(list: List<Calibration>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "list_v1"

        fun toJson(c: Calibration): JSONObject = JSONObject().apply {
            put("cameraKey", c.cameraKey)
            put("umPerPxAtZoom1", c.umPerPxAtZoom1)
            put("refImageWidth", c.refImageWidth)
            if (c.focusDiopters != null) put("focusDiopters", c.focusDiopters.toDouble())
            put("referenceLengthUm", c.referenceLengthUm)
            put("measuredPx", c.measuredPx)
            put("zoomAtCalibration", c.zoomAtCalibration.toDouble())
            put("timestampMs", c.timestampMs)
            if (c.activePhysicalId != null) put("activePhysicalId", c.activePhysicalId)
        }

        fun fromJson(o: JSONObject) = Calibration(
            cameraKey = o.getString("cameraKey"),
            umPerPxAtZoom1 = o.getDouble("umPerPxAtZoom1"),
            refImageWidth = o.getInt("refImageWidth"),
            focusDiopters = if (o.has("focusDiopters")) o.getDouble("focusDiopters").toFloat() else null,
            referenceLengthUm = o.getDouble("referenceLengthUm"),
            measuredPx = o.getDouble("measuredPx"),
            zoomAtCalibration = o.optDouble("zoomAtCalibration", 1.0).toFloat(),
            timestampMs = o.optLong("timestampMs", 0L),
            activePhysicalId = if (o.has("activePhysicalId")) o.getString("activePhysicalId") else null,
        )
    }
}
