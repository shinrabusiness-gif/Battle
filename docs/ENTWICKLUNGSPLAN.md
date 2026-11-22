# Entwicklungsplan UltraScope

Leitlinie: zuerst eine **echte, funktionierende App** mit ehrlichen Messwerten, dann Komfort und Genauigkeit.

## Phase 0 – Hardware verstehen ✅ (in v0.1 enthalten)
- Kamera-Inventar: gelistete IDs, physische Teilkameras logischer Kameras, Probe nicht gelisteter IDs 0–127
- Bericht mit allen relevanten Camera2-Kennwerten und der Optik-Abschätzung je Kamera (teilen, kopieren, speichern)
- **Aufgabe für dich:** Bericht auf dem S25 Ultra erzeugen. Er klärt, welche Objektive dritte Apps wirklich erreichen.

## Phase 1 – Prototyp v0.1 ✅ (dieser Stand)
| Funktion | Umsetzung | Status |
|---|---|---|
| Kamerawahl inkl. physischer Teleobjektive | Camera2, `setPhysicalCameraId`, Live-Anzeige des aktiven Objektivs | implementiert, auf echtem S25U **ungetestet** |
| Zoom | `CONTROL_ZOOM_RATIO` (logisch) bzw. `SCALER_CROP_REGION` (physisch, falls erlaubt) | implementiert |
| Manueller Fokus | `AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`, Feinschritte, „AF→MF“, „Nahgrenze“ | implementiert |
| Belichtung / ISO | `AE_MODE_OFF` + Zeit/ISO (logarithmische Regler) oder Auto + EV | implementiert |
| Weißabgleich | AWB-Presets, Fixieren der aktuellen Gains + Feinjustage (`COLOR_CORRECTION_GAINS`) | implementiert |
| Live-Ansicht | 4:3-Vorschau, Schärfewert + Verlauf, Fokus-Peaking, Gitter, Fadenkreuz, Maßstab | implementiert |
| Messen | Linien ziehen, Längen in µm/mm, Kalibrierung gegen bekannte Länge | implementiert, Mathe per Unit-Test geprüft |
| Focus Stacking | Fokusreihe mit Linsen-Settle-Erkennung → ECC-affin → Laplace-Fusion + Tiefenkarte | implementiert, Algorithmus per Unit-Test geprüft |
| Mosaik | geführte Kachelaufnahme → ORB/RANSAC → Feathering | implementiert, Algorithmus per Unit-Test geprüft |
| Verlustfreies Speichern | Original-JPEG byte-genau (Q100), optional DNG, PNG für Ergebnisse, JSON-Sidecar | implementiert |
| Build auf dem Handy | Termux-Skripte | Skripte geschrieben, **auf Termux ungetestet** (PC-Build geprüft) |

## Phase 2 – Feldtest & Härtung (nächster Schritt, 1–2 Wochen)
1. Hardwarebericht vom S25 Ultra auswerten und Kamerawahl/Standardkamera anpassen
2. Prüfen, ob 3×/5× als physische Kameras laufen. Falls Samsung sie sperrt: Weg über logischen Zoom mit Objektivanzeige dokumentieren
3. Messgenauigkeit mit Objektmikrometer (z. B. 1 mm / 100 Teilstriche) prüfen: Wiederholbarkeit, Randverzeichnung
4. Fokusreihe: Settle-Zeiten und Schrittweite je Objektiv ausmessen
5. Absturz- und Speichertests mit 50-MP-Bildern (Stacking von 30 Bildern, Mosaik 4×4)
6. Gegebenenfalls Ausweichkombinationen bei vom Treiber abgelehnten Stream-Kombinationen ergänzen

## Phase 3 – Genauigkeit (2–4 Wochen)
- Verzeichnungskorrektur über `LENS_DISTORTION`/`LENS_INTRINSIC_CALIBRATION` (falls vorhanden) oder per Schachbrett-Kalibrierung (OpenCV `calibrateCamera`)
- Subpixel-Messung: Kantenerkennung entlang der Messlinie statt reiner Handplatzierung
- Messen auf gespeicherten Vollauflösungsbildern (Lupe, Zoom/Pan) statt nur in der Vorschau
- Stacking: Laplace-Pyramiden-Fusion (weniger Halos), Ausreißer-Maskierung
- Mosaik: globale Bündelausgleichung für große Raster, Belichtungsausgleich

## Phase 4 – Komfort
- Galerie mit Messprotokollen, CSV-Export der Messungen
- Zeitraffer, Messreihen, Vorlagen (z. B. „Haardicke“, „Düsenverschleiß“)
- Optionale Vorsatzlinsen-Profile (Kalibrierung je Linse)
- Release-Signatur

## Bekannte Grenzen von v0.1 (ehrlich)
- **Nicht auf einem echten S25 Ultra getestet.** Kompiliert und geprüft sind Build, Algorithmen und Mathematik. Die Kamerawege hängen vom Samsung-Treiber ab.
- Die Vorschau ist auf Querformat fixiert. Messungen beziehen sich auf die Vorschau, umgerechnet auf Fotopixel.
- Digitalzoom wird auch auf das Foto angewendet, damit das Foto der Vorschau entspricht (WYSIWYG). Für maximale Originaltreue mit Zoom 1,0 aufnehmen.
- RAW (DNG) nur bei direkt geöffneten Kameras, dann ohne Schärfeanalyse-Stream (garantierte Stream-Kombinationen).
- Unit-Tests der Bildverarbeitung laufen auf PC/CI (Desktop-OpenCV), nicht in Termux.
