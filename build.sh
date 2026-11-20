#!/usr/bin/env bash
# GeminiNav build script — no Gradle needed.
set -eu

cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
API="${API_LEVEL:-34}"
BT="${BUILD_TOOLS:-34.0.0}"

ANDROID_JAR="$SDK/platforms/android-$API/android.jar"
AAPT2="$SDK/build-tools/$BT/aapt2"
APKSIGNER="$SDK/build-tools/$BT/apksigner"
D8_JAR="${D8_JAR:-$HOME/r8.jar}"

KS="${KS:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"

for f in "$ANDROID_JAR" "$AAPT2" "$APKSIGNER" "$D8_JAR"; do
    [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== javac =="
javac --release 8 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" \
    $(find src -name '*.java')

echo "== d8 =="
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --lib "$ANDROID_JAR" \
    --output "$OUT/dex" $(find "$OUT/classes/com/wa2000/gemininav" -name '*.class')

echo "== aapt2 =="
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml -A assets

echo "== package =="
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'EOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('dex added')
EOF

echo "== sign =="
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT/GeminiNav.apk" "$OUT/base.apk"

echo "== verify =="
"$APKSIGNER" verify --print-certs "$OUT/GeminiNav.apk" | head -3
ls -la "$OUT/GeminiNav.apk"
