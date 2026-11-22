#!/data/data/com.termux/files/usr/bin/bash
# Baut die App auf dem Handy und oeffnet den Paketinstaller.
set -euo pipefail
cd "$(dirname "$0")/.."

# Kein Daemon, nur 2 Worker: schont RAM, Termux wird nicht vom System beendet
./gradlew assembleDebug --no-daemon --max-workers=2 "$@"

APK="app/build/outputs/apk/debug/app-debug.apk"
OUT="$HOME/storage/downloads/UltraScope-debug.apk"
if [ -d "$HOME/storage/downloads" ]; then
  cp "$APK" "$OUT"
  echo "APK kopiert nach: Download/UltraScope-debug.apk"
  # Oeffnet den Android-Paketinstaller (Termux braucht einmalig "Unbekannte Apps installieren")
  termux-open "$OUT" || echo "Bitte die APK im Dateimanager unter Download/ antippen."
else
  echo "APK: $(pwd)/$APK  (termux-setup-storage ausfuehren, um sie nach Download/ zu kopieren)"
fi
