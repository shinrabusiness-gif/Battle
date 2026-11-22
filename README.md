# UltraScope – Mikroskop-App für das Galaxy S25 Ultra

Android-App (Kotlin, Camera2, OpenCV), die das Galaxy S25 Ultra **ohne Zusatzoptik** zu einem kalibrierten
Makro-/Messmikroskop macht: manuelle Kamerasteuerung, Live-Schärfeanalyse, Messen in µm, Focus Stacking,
Mosaik-Stitching und verlustfreies Speichern. Keine Cloud, kein Abo, keine Werbung. Bauen lässt sie sich auch direkt auf dem Handy in Termux.

Inspiriert von [JamesTheGiblet/telephoto-microscope](https://github.com/JamesTheGiblet/telephoto-microscope) (S24 Ultra).
Das Originalprojekt enthält nur Dokumentation, keinen Quellcode. UltraScope ist eine eigenständige Neuentwicklung.
Kompatibilitätsanalyse: [docs/HARDWARE_UND_PHYSIK.md](docs/HARDWARE_UND_PHYSIK.md#4-kompatibilität-des-originalprojekts-telephoto-microscope-s24-ultra).

> **Status v0.1:** Build, Mathematik und Bildverarbeitung sind geprüft (12 Unit-Tests, u. a. pixelgenaue Mosaik-Rekonstruktion).
> **Auf einem echten S25 Ultra ist die App noch nicht getestet.** Welche Objektive Samsung für Drittanbieter-Apps freigibt,
> zeigt erst der eingebaute Hardwarebericht (Button **HW**).

---

## Was physikalisch möglich ist (Kurzfassung)

| | Ohne Zusatzoptik realistisch |
|---|---|
| Abbildungsmaßstab an der Nahgrenze | ≈ 1:13 bis 1:22 (alle vier Rückkameras ähnlich) |
| Kleinste trennbare Strukturen | ≈ **30–70 µm** (theoretische Obergrenze 18–35 µm) |
| Gut sichtbar / messbar | Haare (50–100 µm), Fasern, Leiterbahnen, Düsen, Insektendetails, Gewebe- und Holzstrukturen |
| Nicht möglich | Zelldetails, Bakterien, Blutzellen. Dafür ist eine Vorsatzlinse nötig. |
| Digitalzoom | hilft beim Fokussieren und Messen, **erhöht die Auflösung nicht** |
| Focus Stacking | vervielfacht die Schärfentiefe (sonst 2–8 mm) |
| Mosaik | große Fläche bei voller Detailauflösung (Gesamtpixel ↑, µm/px gleich) |

**Gemessen auf einem SM-S938B (Android 17):** Alle vier Rückobjektive sind als physische Kameras der logischen Kamera 0
erreichbar (IDs 2 = Ultraweit, 5 = Haupt, 6 = 3×, 7 = 5×). Jede liefert aber nur **12 MP** und manuelle Steuerung.
Der Treiber meldet für Drittanbieter-Apps als Naheinstellgrenze **40 cm (3×)** und **80 cm (5×)**, nicht die 18/25 cm der Samsung-Kamera-App.
Damit sind die Teleobjektive für Mikroskopie **unbrauchbar**. Am besten geeignet sind das **Ultraweitwinkel (ID 2, 5 cm)**
und die **Hauptkamera (ID 0/5, 10 cm)**, beide mit etwa 30–36 µm pro Pixel.
Die Herleitung mit Quellen steht in [docs/HARDWARE_UND_PHYSIK.md](docs/HARDWARE_UND_PHYSIK.md#5-gemessen-sm-s938b-android-17).

## Funktionen

- **Kamerawahl mit echten Kennwerten:** alle gelisteten Kameras, physische Teilkameras (3×/5×, falls freigegeben) und eine Probe nicht gelisteter IDs. Zu jeder Kamera gibt es eine Abschätzung von Bildfeld, µm/Pixel, Beugungsgrenze und Schärfentiefe aus den Gerätemetadaten.
- **Manuelle Steuerung:** Fokus (Dioptrien, Feinschritte, „AF→MF“, „Nahgrenze“), Belichtungszeit, ISO, EV, Weißabgleich (Presets oder Fixieren + Feinjustage), LED-Dauerlicht, OIS, „minimale Verarbeitung“ (Rauschfilter und Nachschärfung aus).
- **Live-Mikroskopansicht:** 4:3-Vorschau, HUD mit Fokus/Belichtung/ISO/Zoom/**aktivem Objektiv**, Schärfewert mit Verlauf und Spitzenwert, Fokus-Peaking, kalibriertes Gitter, Fadenkreuz, Maßstabsbalken.
- **Messen & Kalibrieren:** Linien ziehen, Endpunkte verschieben, Länge in nm/µm/mm. Kalibriert wird gegen eine bekannte Länge (Lineal, Objektmikrometer), gespeichert pro Kamera, Fokusposition und Objektiv, mit Warnung bei Abweichung.
- **Focus Stacking:** automatische Fokusreihe (3–40 Bilder, Linsen-Settle-Erkennung, fixierte Belichtung/WB) → affine ECC-Registrierung (gleicht Fokusatmung aus) → Laplace-Fusion + **Tiefenkarte**.
- **Mosaik/Panorama:** geführte Kachelaufnahme mit fixiertem Fokus, Zoom und Belichtung → ORB + RANSAC → Translationsmodell (maßstabstreu) oder Freihandmodell → nahtloses Feathering.
- **Verlustfrei speichern:** Original-JPEG byte-genau in Qualität 100, optional **DNG (RAW)**, Ergebnisse als **PNG**. Dazu ein JSON-Sidecar mit allen Aufnahmeparametern, Maßstab und Messlinien, eine EXIF-Beschreibung und optional eine *separate* Kopie mit Maßstabsbalken.
- **Dunkles, wissenschaftliches Interface** im Querformat.

Speicherorte: Bilder in `Pictures/UltraScope/`, Metadaten (JSON) und Hardwarebericht in `Documents/UltraScope/`.

---

## Installation

### Variante A – direkt auf dem S25 Ultra mit Termux (ohne PC)

1. **Termux** aus [F-Droid](https://f-droid.org/packages/com.termux/) oder von [GitHub](https://github.com/termux/termux-app/releases) installieren (nicht die veraltete Play-Store-Version).
2. In Termux (bei einem privaten Repository fragt `git` nach Benutzername und Personal-Access-Token):
   ```bash
   pkg install -y git
   git clone https://github.com/shinrabusiness-gif/Battle.git UltraScope
   cd UltraScope
   git checkout ccr-2635fbfc-ohf9g8   # solange der Code noch nicht im Hauptzweig ist
   bash scripts/termux-setup.sh      # einmalig: JDK 21, ARM-aapt2, Android-SDK 34 (~300 MB)
   bash scripts/termux-build.sh      # baut die APK (erster Build 5–15 min, danach 1–3 min)
   ```
3. Das Skript kopiert die APK nach `Download/UltraScope-debug.apk` und öffnet den Paketinstaller.
   Beim ersten Mal muss **„Unbekannte Apps installieren“ für Termux** erlaubt werden.

Was die Skripte tun, nach dem bewährten Weg des Originalprojekts:
- Die AGP-eigene aapt2 ist ein x86-Programm und läuft nicht auf ARM. `termux-setup.sh` trägt deshalb
  `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` in `~/.gradle/gradle.properties` ein, also *nicht* ins Projekt. So baut dasselbe Projekt auch auf PC und CI.
- Gebaut wird ohne Gradle-Daemon, mit 2 Workern und 1,5 GB Heap, damit Android Termux nicht beendet.
- Die Unit-Tests nutzen Desktop-OpenCV (x86/x64) und laufen deshalb nur auf PC/CI, nicht in Termux.

> Falls der Termux-Build mit einer aapt2-Fehlermeldung abbricht (z. B. nach einem Termux-Update), nutze Variante C.
> Die Termux-Kette ist hier nicht auf echter Hardware getestet, nur der PC-Build.

### Variante B – am PC (Linux/macOS/Windows)
Voraussetzungen: JDK 17 oder 21 und Android SDK (Platform 34). Android Studio ist optional.
```bash
echo "sdk.dir=/pfad/zum/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk      # oder APK aufs Handy kopieren
```

### Variante C – kostenlos in der Cloud (GitHub Actions)
Bei jedem Push baut `.github/workflows/android.yml` die APK und führt die Tests aus.
Die APK findest du unter **Actions → letzter Lauf → Artifacts → UltraScope-debug-apk**. Herunterladen, entpacken, auf dem Handy installieren.

---

## Bedienung – empfohlener Messablauf

1. **HW** antippen → Hardwarebericht prüfen. Bei Bedarf „Versteckte IDs 0–127 prüfen“.
2. **Kamera** wählen: **ID 2 (Ultraweit, 5 cm)** oder **ID 0 (Haupt, 10 cm)**. Die Teleobjektive fokussieren für Drittanbieter-Apps erst ab 40 bzw. 80 cm.
3. **Nahgrenze** antippen: Der Fokus geht auf den nächsten Punkt und wird fixiert, das ergibt den größten Abbildungsmaßstab. Die Schärfe stellst du ein, indem du **Handy oder Objekt verschiebst**. Am besten mit Stativ oder Buchstapel. Die **Lautstärketasten** lösen mit 2 s Verzögerung aus, das verhindert Verwackeln.
4. Licht: seitlich mit LED oder Schreibtischlampe, bei Reflexionen diffus.
5. **Kalibrieren:** Lineal oder Objektmikrometer in die Bildebene legen, gelbe Linie über eine bekannte Länge ziehen, „Kalibrierlinie übernehmen“, Länge in mm eingeben.
   Danach Fokus und Zoom nicht mehr ändern, denn die Kalibrierung gilt pro Fokusposition. Bei mehr als 2 % Abweichung warnt die App.
6. **Messen:** Linien ziehen. Beim Auslösen werden die Messlinien in Fotopixel umgerechnet und im JSON gespeichert.
7. **Stack:** „Nah = jetzt“ und „Fern = jetzt“ festlegen (oder ±6 % um den aktuellen Fokus), Bildanzahl wählen, auslösen und das Handy ruhig halten.
8. **Mosaik:** Kachel aufnehmen, Probe um etwa ⅔ Bildbreite verschieben (≥ 30 % Überlappung), wiederholen, „Zusammensetzen“.

Tipp: Tippen in die Vorschau setzt den Schärfe-Spitzenwert zurück. Beim Scharfstellen durch Verschieben auf 100 % zielen.

---

## Projektstruktur

```
app/src/main/java/io/github/ultrascope/
  MainActivity.kt            UI, Modi (Foto/Stack/Mosaik), Kalibrierung, Speichern, Metadaten
  HardwareActivity.kt        Hardwarebericht (teilen/kopieren/speichern)
  camera/CameraInventory.kt  Kamera-Erkennung inkl. physischer/verborgener IDs, Kennwerte, Bericht
  camera/CameraController.kt Camera2-Sitzung, manuelle Parameter, Einzel-/Serienaufnahme, DNG
  camera/CaptureSettings.kt  vom UI gesteuerte Aufnahmeparameter
  camera/FocusAnalyzer.kt    Schärfemaß + Fokus-Peaking aus dem YUV-Stream (reines Kotlin)
  core/Optics.kt             Optikmodell (Maßstab, Bildfeld, Beugung, Schärfentiefe)
  core/Calibration.kt        Maßstab pro Kamera/Fokus/Objektiv, Umrechnung Zoom/Bildbreite
  core/ScaleMath.kt          1-2-5-Reihe, Einheitenformatierung
  processing/FocusStacker.kt Focus Stacking (OpenCV, streamend, Tiefenkarte)
  processing/PanoramaStitcher.kt  Mosaik (OpenCV, ORB/RANSAC, Feathering, speicherschonend)
  storage/MediaStoreSaver.kt verlustfreies Speichern über MediaStore, EXIF
  storage/CalibrationStore.kt Kalibrierungen persistent
  ui/OverlayView.kt          Gitter, Maßstab, Messlinien, Peaking, Schärfeverlauf, HUD
app/src/test/...             Unit-Tests (Mathe, Kalibrierung, Stacking, Mosaik mit synthetischen Bildern)
scripts/termux-*.sh          Einrichtung und Build auf dem Handy
docs/                        Hardware-/Physik-Analyse, Entwicklungsplan
```

Technik: Kotlin 2.0, AGP 8.5, Gradle 8.8, compileSdk/targetSdk 34, minSdk 30. Keine AndroidX-Abhängigkeiten, das hält den Termux-Build schlank.
OpenCV 4.12 (offizielles AAR, Apache 2.0) nur für arm64-v8a. Die APK ist ≈ 14 MB groß.

Weiterer Fahrplan: [docs/ENTWICKLUNGSPLAN.md](docs/ENTWICKLUNGSPLAN.md)
