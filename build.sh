#!/usr/bin/env bash
# Build + sign the Looker/cita-bot APK using the plain SDK command-line tools
# (aapt2 + javac + d8 + zipalign + apksigner) — no Gradle.
#
# Requires: a JDK (javac), and the Android SDK below with build-tools 34.0.0 and
# platform android-34. Install a JDK on Fedora with:  sudo dnf install java-latest-openjdk-devel
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/mnt/dev/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

OUT="$HERE/build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
APKDIR="$OUT/apk"
KEYSTORE="$OUT/debug.keystore"

command -v javac >/dev/null 2>&1 || { echo "ERROR: javac (a JDK) not found on PATH. Install one, e.g.: sudo dnf install java-latest-openjdk-devel"; exit 1; }

rm -rf "$GEN" "$CLASSES" "$DEX" "$APKDIR"
mkdir -p "$GEN" "$CLASSES" "$DEX" "$APKDIR" "$OUT/compiled_res"

echo "==> aapt2 compile resources"
"$AAPT2" compile --dir "$HERE/res" -o "$OUT/compiled_res/res.zip"

echo "==> aapt2 link (generates R.java + base APK)"
"$AAPT2" link \
  -o "$APKDIR/base-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version 26 --target-sdk-version 28 \
  "$OUT/compiled_res/res.zip"

echo "==> javac"
# android.jar goes on -classpath (for the android.* APIs), NOT -bootclasspath:
# lambdas need java.lang.invoke.LambdaMetafactory from the real JDK, and
# android.jar's stub of it has a different signature that breaks modern javac.
# Third-party jars in libs/ (Shizuku api/provider/aidl) join the classpath.
LIBS=$(find "$HERE/libs" -name '*.jar' 2>/dev/null | paste -sd: -)
CP="$PLATFORM${LIBS:+:$LIBS}"
find "$HERE/src" "$GEN" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -classpath "$CP" \
  -d "$CLASSES" @"$OUT/sources.txt"

echo "==> d8 (dex)"
# --lib android.jar lets d8 resolve platform supertypes; without it d8 NPEs.
# The libs/ jars are dexed INTO the app (compile+runtime, like implementation()).
CLASSFILES=$(find "$CLASSES" -name '*.class')
LIBJARS=$(find "$HERE/libs" -name '*.jar' 2>/dev/null)
"$D8" --min-api 26 --lib "$PLATFORM" --output "$DEX" $CLASSFILES $LIBJARS

echo "==> assemble APK (add classes.dex to the base APK)"
cp "$APKDIR/base-unsigned.apk" "$APKDIR/looker-unaligned.apk"
( cd "$DEX" && zip -q -X "$APKDIR/looker-unaligned.apk" classes.dex )

echo "==> zipalign"
"$ZIPALIGN" -f -p 4 "$APKDIR/looker-unaligned.apk" "$APKDIR/looker-aligned.apk"

echo "==> keystore (debug, created once)"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "==> apksigner sign"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --out "$APKDIR/looker-signed.apk" "$APKDIR/looker-aligned.apk"

echo
echo "Built: $APKDIR/looker-signed.apk"
echo "Install: adb install -r '$APKDIR/looker-signed.apk'"
