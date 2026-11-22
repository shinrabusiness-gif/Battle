# Hardware-Analyse Galaxy S25 Ultra & physikalische Grenzen

Dieses Dokument trennt strikt zwischen **belegten Herstellerangaben und Messungen Dritter**,
**daraus berechneten Abschätzungen** und **Dingen, die nur auf dem Gerät geprüft werden können**.
Die App liest alle optischen Kennwerte zur Laufzeit aus den Camera2-Metadaten des konkreten
Geräts (Button **HW**). Bitte ersetze die Annahmen unten durch deinen echten Hardwarebericht.

---

## 1. Veröffentlichte Kameradaten (Rückseite)

| Kamera | Auflösung | Blende | KB-Brennweite | Sensor / Pixel | Naheinstellgrenze |
|---|---|---|---|---|---|
| Hauptkamera | 200 MP | f/1.7 | ≈ 23–24 mm | 1/1.3″, 0,6 µm | ≈ 10 cm ¹ |
| Ultraweitwinkel | 50 MP (neu ggü. S24U: 12 MP) | f/1.9 | ≈ 13 mm | *Annahme: 1/2.5″, 0,7 µm* | ≈ 3 cm ¹ |
| Tele 3× | 10 MP | f/2.4 | 67–69 mm | *Annahme: 1/3.52″, 1,12 µm* | ≈ 18 cm ² |
| Tele 5× (Periskop) | 50 MP (Sony IMX854) | f/3.4 | 111 mm | 1/2.52″, 0,7–1,4 µm | 25–26 cm gemessen ², 30 cm laut Samsung ³ |

Alle Teleobjektive mit PDAF und OIS. Die Teleobjektive sind gegenüber dem S24 Ultra unverändert. Neu ist nur das 50-MP-Ultraweitwinkel.
„2×/10× optische Qualität“ sind bei Samsung Ausschnitte aus dem 200-MP- bzw. 50-MP-Sensor, also keine eigenen Objektive.

¹ Messung/Angabe eines Testers (X/@sondesix). ² GSMArena-Review. ³ Samsung-Supportseite (zitiert von GSMArena/Techtiper).
Kursive Sensorwerte konnte ich nicht direkt verifizieren (Spezifikationsseite nicht abrufbar). Sie stammen aus der
S24-Ultra-Generation. Die App ersetzt sie durch `SENSOR_INFO_PHYSICAL_SIZE` und `SENSOR_INFO_PIXEL_ARRAY_SIZE`.

## 2. Was die Kamera-API dritten Apps zeigt (nur auf dem Gerät prüfbar)

Belegt ist Folgendes:
- Samsung listete bei früheren Ultras (S21U) die Teleobjektive **nicht** in `getCameraIdList()`.
- Beim S24U zeigt das Debug-Protokoll des Originalprojekts die IDs 0 (Haupt, logisch, Zoom 0,6–10×), 1/3 (Front) und 2. **ID 2 ist sehr wahrscheinlich das Ultraweitwinkel** (siehe Abschnitt 4).
- In Samsungs Entwicklerforum wird für das S24U eine physische Kamera „6“ in der logischen Kamera „0“ beschrieben (mit Treiberabstürzen beim Fotografieren).

**Ob das S25 Ultra 3× und 5× als physische Kameras für Drittanbieter-Apps freigibt, ist nicht öffentlich dokumentiert.**
Deshalb probiert UltraScope alle drei möglichen Wege aus und zeigt an, welcher funktioniert:

1. **Gelistete Kameras** (`getCameraIdList`)
2. **Physische Teilkameras** jeder logischen Kamera (`getPhysicalCameraIds`). Die Streams laufen dann über `OutputConfiguration.setPhysicalCameraId`.
3. **Nicht gelistete IDs 0–127** per Probe („Versteckte IDs prüfen“ im HW-Bildschirm bzw. in der Kamerawahl)

Dazu kommt ein vierter, indirekter Weg: Die logische Kamera 0 mit `CONTROL_ZOOM_RATIO` ≥ 3 bzw. ≥ 5.
Der Treiber kann dann selbst auf ein Teleobjektiv umschalten. Im Nahbereich weicht Samsung aber oft auf einen Ausschnitt der Hauptkamera aus.
Welches Objektiv tatsächlich aktiv ist, zeigt das HUD live über `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`. Die Kalibrierung merkt sich dieses Objektiv.

Ebenfalls nur auf dem Gerät prüfbar ist, ob `MANUAL_SENSOR` (Belichtung/ISO), `MANUAL_POST_PROCESSING` (WB-Gains), RAW
und manueller Fokus pro Kamera freigegeben sind. Die App blendet nicht unterstützte Regler aus bzw. deaktiviert sie.

## 3. Physik: Was ohne Zusatzoptik möglich ist

Ein Handyobjektiv ist kein Mikroskopobjektiv. Entscheidend ist der **Abbildungsmaßstab m** an der Naheinstellgrenze
(Modell der dünnen Linse: m = f / (u − f)). Die Auflösung im Objekt begrenzen zwei Effekte:
- **Beugung:** Rayleigh-Radius 1,22 · λ · N_eff / m, mit λ = 550 nm und N_eff = N · (1 + m)
- **Abtastung:** Nyquist, 2 Pixel / m

Berechnet mit dem Modell der App (`core/Optics.kt`) aus den Daten oben (physische Brennweite aus KB-Brennweite und Sensordiagonale):

| Kamera @ Naheinstellgrenze | m (Maßstab) | Bildfeld | µm pro Pixel (gespeichert) | Beugungsgrenze | kleinste trennbare Struktur* | Schärfentiefe |
|---|---|---|---|---|---|---|
| Haupt, 12 MP gebinnt @ 10 cm | 0,067 (1:15) | 146 × 109 mm | 36 µm | 18 µm | ≈ 70 µm | ≈ 3,9 mm |
| Haupt, 200 MP voll @ 10 cm | 0,067 | 146 × 109 mm | 9 µm | 18 µm | ≈ 18–25 µm** | ≈ 2 mm |
| Ultraweit 50 MP (12,5 MP Ausgabe) @ 3 cm | 0,077 (1:13) | 74 × 55 mm | 18 µm | 18 µm | ≈ 36 µm | ≈ 1,9 mm |
| Tele 3× 10 MP @ 18 cm | 0,046 (1:22) | 89 × 67 mm | 24 µm | 37 µm | ≈ 49 µm | ≈ 8 mm |
| Tele 5× 12,5 MP @ 25 cm | 0,079 (1:13) | 72 × 54 mm | 18 µm | 31 µm | ≈ 35 µm | ≈ 5,8 mm |
| Tele 5× 50 MP voll @ 25 cm | 0,079 | 72 × 54 mm | 9 µm | 31 µm | ≈ 31 µm | ≈ 5,8 mm |

\* Größerer Wert aus Beugung und Abtastung. Das ist eine **theoretische Obergrenze**. Objektivfehler, Bayer-/Quad-Bayer-Demosaicing,
Rauschfilter und Bewegung verschlechtern das Ergebnis real oft um den Faktor 1,5–3.
\** Hängt davon ab, ob die 200-MP-Ausgabe für Drittanbieter-Apps freigegeben ist. Bei f/1.7 begrenzen eher Objektivfehler als die Beugung.

**Folgerungen**
- Alle Kameras erreichen an ihrer Nahgrenze nur etwa **1:13 bis 1:22**. Ein echtes Lichtmikroskop beginnt bei 4:1, also etwa 50–80-mal mehr.
- **Realistisch erreichbar: Strukturen ab etwa 30–70 µm**. Das reicht für Haare (50–100 µm), Papier- und Textilfasern, Leiterbahnen ab etwa 100 µm, Düsenverschleiß bei 3D-Druckern, Insektendetails, Pollen nur als Punkte.
- **Unmöglich** ohne Vorsatzlinse: Zellen (10–30 µm) im Detail, Bakterien (1–10 µm), Blutzellen.
- Das **5×-Tele** ist nicht wegen der Auflösung erste Wahl, die ähnelt der von Haupt- und Ultraweitwinkelkamera. Sein Vorteil ist der **Arbeitsabstand von 25 cm**: kein Eigenschatten, gute seitliche Beleuchtung, geringe perspektivische Verzerrung, mehr Schärfentiefe. Das macht Mess- und Mosaikaufnahmen deutlich zuverlässiger.
- **Digitalzoom fügt keine Information hinzu.** Er vergrößert nur die Vorschau, damit man genauer fokussiert und misst. Der angezeigte Wert „µm/px“ wird kleiner, die Auflösung bleibt gleich.
- **Focus Stacking** erweitert die Schärfentiefe (z. B. von 5,8 mm auf ein Vielfaches). Die Auflösung steigt dadurch nicht.
- **Mosaik/Stitching** erhöht die **Gesamtpixelzahl** bei gleichem µm/px. So entstehen große, durchgehend hochaufgelöste Übersichten.
- Eine **Vorsatzlinse** (z. B. +10 bis +25 dpt Makrolinse, umgedrehtes Handyobjektiv) würde m vervielfachen. Das ist bewusst nicht Teil dieses Prototyps, die App funktioniert damit aber unverändert (einfach neu kalibrieren).

## 4. Kompatibilität des Originalprojekts „telephoto-microscope“ (S24 Ultra)

Repository: <https://github.com/JamesTheGiblet/telephoto-microscope> (geprüft am 22.11.2026)

| Befund | Bewertung |
|---|---|
| Das Repository enthält **nur `README.md` und `DEV_LOG.md`, keinen Quellcode und keine Lizenz**. | Es gibt keinen Code zum Portieren. Ohne Lizenz darf man ohnehin nichts übernehmen. UltraScope ist eine **eigenständige Neuentwicklung**, die nur die Ideen aufgreift. |
| „Telekamera ID:2, 67 mm, f/2.4“, laut eigenem Debug-Protokoll aber **Brennweite 2,2 mm, Naheinstellgrenze 20 dpt (5 cm), 4000×3000** | 2,2 mm physische Brennweite und 12 MP passen zum **12-MP-Ultraweitwinkel des S24U**, nicht zum 10-MP-3×-Tele (≈ 7–8 mm). Auch das Bildfeld von 510 mm in 13 cm Abstand (≈ 125°) ist ein Ultraweitwinkel-Bildfeld. |
| „Laser-AF als ToF-Entfernungsmesser über CaptureResult“ | Camera2 liefert keine Laserdaten, sondern `LENS_FOCUS_DISTANCE`, also die Fokusposition der Linse in Dioptrien. Bei `UNCALIBRATED`/`APPROXIMATE` taugt sie nicht als Entfernungsmesser. UltraScope zeigt deshalb an, wie die Fokusdistanz kalibriert ist, und rechnet den Maßstab nicht aus ihr hoch. |
| „16,8 µm/px @ 8× Zoom“ | Das ist die **Pixelgröße eines 8-fach digital vergrößerten Ausschnitts**, nicht die optische Auflösung. Bei 1× wurden 124 µm/px gemessen. Die tatsächlich trennbare Struktur liegt also eher bei 250 µm. |
| Maßstab linear über die Distanz: `mmPerPx · (aktuelleDist / calDist)` | Gilt nur bei kalibrierter Fokusdistanz und weit entferntem Objekt. UltraScope kalibriert pro Kamera, Fokusposition und aktivem Objektiv und warnt bei Abweichung (Toleranz 2 %). |
| NCC-Stitching in reinem Kotlin, sichtbare Nähte | UltraScope nutzt OpenCV mit ORB, RANSAC, Translationsmodell und Feathering. Im Test wird eine Vorlage pixelgenau rekonstruiert. |
| Termux-Build-Kette (OpenJDK 21, ARM-aapt2-Override, `--no-daemon`) | **Wird übernommen.** Das ist der wertvollste, praktisch belegte Teil des Originals. |

**Übertragbarkeit auf das S25 Ultra:** Konzept und Build-Weg ja. Die fest verdrahtete Kamera-ID 2 wäre beim S25 Ultra
vermutlich wieder das (jetzt 50-MP-)Ultraweitwinkel. Das wäre für Makro sogar brauchbar, ist aber kein Tele.
UltraScope verdrahtet deshalb keine IDs fest und lässt jede Kamera mit gemessenen Kennwerten auswählen.

## Quellen
- GSMArena, Galaxy S25 Ultra Review – Kamera: <https://www.gsmarena.com/samsung_galaxy_s25_ultra-review-2793p5.php>
- GSMArena, Hands-on (5×: 50 MP IMX854, 1/2.52″, 111 mm, f/3.4): <https://www.gsmarena.com/samsung_galaxy_s25_ultra_s25_plus_s25_handson-review-2789p5.php>
- DXOMARK S25 Ultra Kameratest: <https://www.dxomark.com/samsung-galaxy-s25-ultra-camera-test/>
- TechRadar, S25 Ultra Kameras: <https://www.techradar.com/phones/samsung-galaxy-phones/samsung-galaxy-s25-ultra-cameras>
- Naheinstellgrenzen (Tester): <https://x.com/sondesix/status/1894028255017242734>, SammyGuru: <https://sammyguru.com/galaxy-s25-ultra-boasts-industry-leading-minimum-focusing-distance/>
- S26 vs. S25 Ultra Telemakro (Samsung-Angabe 30 cm): <https://www.gsmarena.com/the_galaxy_s26_ultra_doesnt_have_a_periscope__heres_why_that_matters-news-71825.php>
- Samsung Developer Forum, Telezugriff per Camera2: <https://forum.developer.samsung.com/t/accessing-telephoto-lens-using-camera2-or-camerax-in-s21-ultra/13843>, physische ID auf S24U: <https://forum.developer.samsung.com/t/crash-using-camera2-and-physical-camera-id/34955/1>
