#!/bin/bash
set -u
cd /sdcard/pj/GeminiNav || exit 1

echo '== javac =='
mkdir -p out dex
javac --release 8 -nowarn -cp /root/android-sdk/android-13/android.jar -d out $(find src -name '*.java') || exit 1

echo '== d8 =='
java -cp /sdcard/r8.jar com.android.tools.r8.D8 --release --lib /root/android-sdk/android-13/android.jar \
  --output dex $(find out/com/wa2000/gemininav -name '*.class') || exit 1
ls -la dex/classes.dex

echo '== aapt2 link =='
aapt2 link -o linked.apk -I /root/android-sdk/android-13/android.jar \
  --manifest AndroidManifest.xml -A assets || exit 1

echo '== add dex =='
python3 - <<'EOF'
import zipfile
z = zipfile.ZipFile('/sdcard/pj/GeminiNav/linked.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('/sdcard/pj/GeminiNav/dex/classes.dex', 'classes.dex')
z.close()
print('dex added')
EOF

echo '== sign =='
/root/android-sdk/android-14/apksigner sign \
  --ks /sdcard/pj/wa.jks --ks-pass pass:wa2000wa --key-pass pass:wa2000wa \
  --out /sdcard/pj/GeminiNav/GeminiNav-v1.1.0.apk /sdcard/pj/GeminiNav/linked.apk || exit 1

echo '== verify =='
/root/android-sdk/android-14/apksigner verify --print-certs /sdcard/pj/GeminiNav/GeminiNav-v1.1.0.apk | head -3
ls -la /sdcard/pj/GeminiNav/GeminiNav-v1.1.0.apk