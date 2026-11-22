package io.github.ultrascope

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.ultrascope.camera.CameraInventory
import io.github.ultrascope.storage.MediaStoreSaver
import java.util.concurrent.Executors

/**
 * Hardware-Pruefung: listet alle Kameras inkl. physischer Teilkameras (und optional per
 * ID-Probe gefundene, nicht gelistete IDs) mit ihren echten Camera2-Metadaten und der daraus
 * berechneten Nahaufnahme-Abschaetzung. Bericht kann kopiert, geteilt und gespeichert werden.
 */
class HardwareActivity : Activity() {
    private lateinit var text: TextView
    private var report = ""
    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setTextColor(getColor(R.color.text))
            background = getDrawable(R.drawable.btn_bg)
            layoutParams = LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f).apply { setMargins(4, 4, 4, 4) }
            setOnClickListener { action() }
        }
        bar.addView(btn("Neu scannen") { load(0) })
        bar.addView(btn("Versteckte IDs 0–127 pruefen") { load(127) })
        bar.addView(btn("Kopieren") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UltraScope", report))
            Toast.makeText(this, "Bericht kopiert", Toast.LENGTH_SHORT).show()
        })
        bar.addView(btn("Speichern") {
            val uri = MediaStoreSaver.saveText(this, report, "UltraScope_Hardware_${MediaStoreSaver.timestamp()}.txt", "text/plain")
            Toast.makeText(this, if (uri != null) "In Documents/UltraScope gespeichert" else "Speichern fehlgeschlagen", Toast.LENGTH_SHORT).show()
        })
        bar.addView(btn("Teilen") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
            }, "Bericht teilen"))
        })
        root.addView(bar)
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11.5f
            setTextColor(getColor(R.color.text)); setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(text) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load(0)
    }

    private fun load(probe: Int) {
        text.text = "Lese Camera2-Metadaten …"
        exec.execute {
            val r = try { CameraInventory.report(CameraInventory.enumerate(this, probe)) } catch (e: Exception) { "Fehler: ${e.message}" }
            runOnUiThread { report = r; text.text = r }
        }
    }

    override fun onDestroy() { exec.shutdown(); super.onDestroy() }
}
