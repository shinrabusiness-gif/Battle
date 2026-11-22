#!/data/data/com.termux/files/usr/bin/bash
# Einmalige Einrichtung der Build-Umgebung direkt auf dem Galaxy S25 Ultra (Termux aus F-Droid/GitHub).
# Laedt ~300 MB (JDK, Android-SDK-Plattform, Gradle beim ersten Build).
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$HOME/android-sdk"
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

echo "==> Pakete installieren (OpenJDK 21, ARM-aapt2, Werkzeuge)"
pkg update -y
pkg install -y openjdk-21 aapt2 wget unzip git

echo "==> Android Command-Line-Tools nach $SDK"
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  wget -q --show-progress -O "$TMPDIR/clt.zip" "$CLT_URL"
  rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
  unzip -q -o "$TMPDIR/clt.zip" -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f "$TMPDIR/clt.zip"
fi

echo "==> SDK-Lizenzen + Plattform 34 + Build-Tools 34.0.0"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0"

echo "==> Gradle auf ARM-aapt2 umstellen (die AGP-eigene aapt2 ist x86 und laeuft auf dem Handy nicht)"
mkdir -p "$HOME/.gradle"
touch "$HOME/.gradle/gradle.properties"
sed -i '/android.aapt2FromMavenOverride/d' "$HOME/.gradle/gradle.properties"
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> "$HOME/.gradle/gradle.properties"

echo "sdk.dir=$SDK" > "$PROJECT_DIR/local.properties"
chmod +x "$PROJECT_DIR/gradlew" "$PROJECT_DIR/scripts/"*.sh

echo "==> Zugriff auf den gemeinsamen Speicher (fuer die APK-Kopie nach Download/) – bitte erlauben"
termux-setup-storage || true

echo
echo "Fertig. Jetzt bauen mit:  $PROJECT_DIR/scripts/termux-build.sh"
