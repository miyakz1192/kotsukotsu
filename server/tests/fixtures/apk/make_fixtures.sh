#!/usr/bin/env bash
# テスト用の小さな APK を作る（AndroidManifest.xml だけ）。鍵はテスト専用で、配布には使わない。
# 必要: Android SDK build-tools（aapt2, apksigner）と JDK
set -euo pipefail
cd "$(dirname "$0")"
BT="${BUILD_TOOLS:-$HOME/android-tools/android-sdk/build-tools/35.0.0}"
JAR="${ANDROID_JAR:-$HOME/android-tools/android-sdk/platforms/android-35/android.jar}"
export PATH="${JAVA_HOME:-$HOME/android-tools/jdk-17.0.13+11}/bin:$PATH"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
for k in a b; do
  [ -f "test-key-$k.p12" ] || keytool -genkeypair -keystore "test-key-$k.p12" -storetype PKCS12 -alias t -keyalg RSA -keysize 2048 \
    -validity 36500 -storepass testpass -keypass testpass -dname "CN=test $k" >/dev/null 2>&1
done
make() { # 出力名 パッケージ versionCode versionName debuggable 鍵
  local out=$1 pkg=$2 code=$3 name=$4 dbg=$5 key=$6
  cat > "$work/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg"
  android:versionCode="$code" android:versionName="$name">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/>
  <application android:label="t" android:debuggable="$dbg"/>
</manifest>
XML
  "$BT/aapt2" link -o "$work/u.apk" -I "$JAR" --manifest "$work/AndroidManifest.xml"
  "$BT/zipalign" -f 4 "$work/u.apk" "$work/a.apk"
  "$BT/apksigner" sign --ks "test-key-$key.p12" --ks-pass pass:testpass --out "$out" "$work/a.apk"
  rm -f "$out.idsig"
}
make kuku-v3.apk      com.kotsukotsu.kuku 3 1.0.2 false a
make kuku-v4.apk      com.kotsukotsu.kuku 4 1.0.3 false a
make kuku-debug.apk   com.kotsukotsu.kuku 5 1.0.4 true  a
make kuku-otherkey.apk com.kotsukotsu.kuku 6 1.0.5 false b
make launcher-v1.apk  com.kotsukotsu.launcher 1 1.0.0 false a
make other-pkg.apk    com.example.other 7 1.0.0 false a
make parent-v1.apk    com.kotsukotsu.parent 1 1.0.0 false a
